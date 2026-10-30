package app

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/aymanbagabas/go-pty"
)

type terminalSession struct {
	id        string
	pty       pty.Pty
	cmd       *pty.Cmd
	done      chan struct{}
	closeOnce sync.Once
}

func (s *terminalSession) closePTY() {
	s.closeOnce.Do(func() {
		_ = s.pty.Close()
	})
}

type TerminalManager struct {
	mu          sync.Mutex
	sessions    map[string]*terminalSession
	emit        func(string, any)
	projectPath func(string) (string, error)
}

func NewTerminalManager(emit func(string, any)) *TerminalManager {
	return &TerminalManager{sessions: map[string]*terminalSession{}, emit: emit}
}

type ShellInfo struct {
	Name string `json:"name"`
	Path string `json:"path"`
}

func availableShells() []ShellInfo {
	result := []ShellInfo{}
	candidates := []struct {
		name string
		path string
	}{
		{"Git Bash", `C:\Program Files\Git\bin\bash.exe`},
		{"Git Bash", `C:\Program Files\Git\usr\bin\bash.exe`},
		{"Bash", `C:\Program Files\Git\bin\bash.exe`},
	}
	for _, candidate := range candidates {
		if info, err := os.Stat(candidate.path); err == nil && !info.IsDir() {
			already := false
			for _, item := range result {
				if strings.EqualFold(item.Path, candidate.path) {
					already = true
					break
				}
			}
			if !already {
				result = append(result, ShellInfo{Name: candidate.name, Path: candidate.path})
			}
		}
	}
	if path, err := exec.LookPath("bash.exe"); err == nil && !strings.Contains(strings.ToLower(path), "system32") {
		result = append(result, ShellInfo{Name: "Bash", Path: path})
	}
	for _, name := range []string{"pwsh.exe", "powershell.exe"} {
		if path, err := exec.LookPath(name); err == nil {
			result = append(result, ShellInfo{Name: strings.TrimSuffix(name, ".exe"), Path: path})
		}
	}
	return result
}

func shellCommand() (string, []string, string, error) {
	if info, err := os.Stat(`C:\Program Files\Git\bin\bash.exe`); err == nil && !info.IsDir() {
		return `C:\Program Files\Git\bin\bash.exe`, []string{"-i"}, "Git Bash", nil
	}
	if info, err := os.Stat(`C:\Program Files\Git\usr\bin\bash.exe`); err == nil && !info.IsDir() {
		return `C:\Program Files\Git\usr\bin\bash.exe`, []string{"-i"}, "Bash", nil
	}
	if path, err := exec.LookPath("bash.exe"); err == nil && !strings.Contains(strings.ToLower(path), "system32") {
		return path, []string{"-i"}, "Bash", nil
	}
	if path, err := exec.LookPath("pwsh.exe"); err == nil {
		return path, []string{"-NoLogo"}, "PowerShell", nil
	}
	if path, err := exec.LookPath("powershell.exe"); err == nil {
		return path, []string{"-NoLogo"}, "PowerShell", nil
	}
	return "", nil, "", errors.New("没有找到 Git Bash 或 PowerShell")
}

func (m *TerminalManager) Start(projectID string, cols, rows int) (map[string]any, error) {
	if m.projectPath == nil {
		return nil, errors.New("终端尚未初始化")
	}
	projectDir, err := m.projectPath(projectID)
	if err != nil {
		return nil, err
	}
	shell, args, label, err := shellCommand()
	if err != nil {
		return nil, err
	}
	pseudoterminal, err := pty.New()
	if err != nil {
		return nil, err
	}
	command := pseudoterminal.Command(shell, args...)
	command.Dir = projectDir
	command.Env = append(os.Environ(), "TERM=xterm-256color", "COLORTERM=truecolor")
	if err := command.Start(); err != nil {
		_ = pseudoterminal.Close()
		return nil, err
	}
	if cols < 20 {
		cols = 100
	}
	if rows < 5 {
		rows = 30
	}
	_ = pseudoterminal.Resize(cols, rows)
	id := "term-" + randomID()[:16]
	session := &terminalSession{id: id, pty: pseudoterminal, cmd: command, done: make(chan struct{})}
	m.mu.Lock()
	m.sessions[id] = session
	m.mu.Unlock()
	go m.pump(session)
	return map[string]any{"id": id, "shell": label, "cwd": projectDir}, nil
}

func (m *TerminalManager) pump(session *terminalSession) {
	defer close(session.done)
	buffer := make([]byte, 32*1024)
	for {
		n, err := session.pty.Read(buffer)
		if n > 0 {
			encoded := base64.StdEncoding.EncodeToString(buffer[:n])
			if m.emit != nil {
				m.emit("terminal.data", map[string]any{"id": session.id, "data": encoded})
			}
		}
		if err != nil {
			break
		}
	}
	_ = session.cmd.Wait()
	session.closePTY()
	code := -1
	if session.cmd.ProcessState != nil {
		code = session.cmd.ProcessState.ExitCode()
	}
	m.mu.Lock()
	delete(m.sessions, session.id)
	m.mu.Unlock()
	if m.emit != nil {
		m.emit("terminal.exit", map[string]any{"id": session.id, "code": code})
	}
}

func (m *TerminalManager) Write(id, data string) error {
	m.mu.Lock()
	session := m.sessions[id]
	m.mu.Unlock()
	if session == nil {
		return errors.New("终端已关闭")
	}
	_, err := session.pty.Write([]byte(data))
	return err
}

func (m *TerminalManager) Resize(id string, cols, rows int) error {
	m.mu.Lock()
	session := m.sessions[id]
	m.mu.Unlock()
	if session == nil {
		return errors.New("终端已关闭")
	}
	if cols < 1 || rows < 1 {
		return nil
	}
	return session.pty.Resize(cols, rows)
}

func closeTerminalSession(session *terminalSession) {
	if session.cmd.Process != nil {
		_ = session.cmd.Process.Kill()
	}
	session.closePTY()
	select {
	case <-session.done:
	case <-time.After(2 * time.Second):
	}
}

func (m *TerminalManager) Close(id string) {
	m.mu.Lock()
	session := m.sessions[id]
	delete(m.sessions, id)
	m.mu.Unlock()
	if session == nil {
		return
	}
	closeTerminalSession(session)
}

func (m *TerminalManager) CloseAll() {
	m.mu.Lock()
	sessions := make([]*terminalSession, 0, len(m.sessions))
	for _, session := range m.sessions {
		sessions = append(sessions, session)
	}
	m.sessions = map[string]*terminalSession{}
	m.mu.Unlock()
	for _, session := range sessions {
		closeTerminalSession(session)
	}
}

func (m *TerminalManager) Register(router *Router) {
	router.Handle("terminal.start", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		cols, _, _ := DecodeOptionalArg[int](args, 1)
		rows, _, _ := DecodeOptionalArg[int](args, 2)
		return m.Start(projectID, cols, rows)
	})
	router.Handle("terminal.write", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		data, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return true, m.Write(id, data)
	})
	router.Handle("terminal.resize", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		cols, err := DecodeArg[int](args, 1)
		if err != nil {
			return nil, err
		}
		rows, err := DecodeArg[int](args, 2)
		if err != nil {
			return nil, err
		}
		return true, m.Resize(id, cols, rows)
	})
	router.Handle("terminal.close", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		m.Close(id)
		return true, nil
	})
	router.Handle("terminal.shells", func([]json.RawMessage) (any, error) {
		return availableShells(), nil
	})
}
