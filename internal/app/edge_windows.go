//go:build windows

package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/gorilla/websocket"
)

const edgeLoginTimeout = 4 * time.Minute

type cdpClient struct {
	conn *websocket.Conn
	mu   sync.Mutex
	id   int64
}

func (c *cdpClient) call(method string, params any, result any) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.id++
	id := c.id
	message := map[string]any{"id": id, "method": method}
	if params != nil {
		message["params"] = params
	}
	if err := c.conn.SetWriteDeadline(time.Now().Add(15 * time.Second)); err != nil {
		return err
	}
	if err := c.conn.WriteJSON(message); err != nil {
		return err
	}
	deadline := time.Now().Add(20 * time.Second)
	for {
		if err := c.conn.SetReadDeadline(deadline); err != nil {
			return err
		}
		var raw json.RawMessage
		if err := c.conn.ReadJSON(&raw); err != nil {
			return err
		}
		var envelope struct {
			ID     int64           `json:"id"`
			Result json.RawMessage `json:"result"`
			Error  *struct {
				Message string `json:"message"`
			} `json:"error"`
		}
		if err := json.Unmarshal(raw, &envelope); err != nil {
			continue
		}
		if envelope.ID != id {
			continue
		}
		if envelope.Error != nil {
			return errors.New(envelope.Error.Message)
		}
		if result != nil && len(envelope.Result) > 0 {
			if err := json.Unmarshal(envelope.Result, result); err != nil {
				return err
			}
		}
		return nil
	}
}

func (c *cdpClient) close() {
	_ = c.conn.Close()
}

type edgeCookie struct {
	Name     string  `json:"name"`
	Value    string  `json:"value"`
	Domain   string  `json:"domain"`
	Path     string  `json:"path"`
	Expires  float64 `json:"expires"`
	HTTPOnly bool    `json:"httpOnly"`
	Secure   bool    `json:"secure"`
	Session  bool    `json:"session"`
}

type edgeTarget struct {
	TargetID string `json:"targetId"`
	Type     string `json:"type"`
	URL      string `json:"url"`
	Title    string `json:"title"`
}

func findEdge() (string, error) {
	candidates := []string{
		filepath.Join(os.Getenv("ProgramFiles(x86)"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(os.Getenv("ProgramFiles"), `Microsoft\Edge\Application\msedge.exe`),
		filepath.Join(os.Getenv("LOCALAPPDATA"), `Microsoft\Edge\Application\msedge.exe`),
	}
	for _, candidate := range candidates {
		if candidate == "" {
			continue
		}
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	if path, err := exec.LookPath("msedge.exe"); err == nil {
		return path, nil
	}
	return "", errors.New("没有找到 Microsoft Edge，请安装 Edge 后重试，或使用 Cookie 粘贴方式")
}

func freePort() (int, error) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port, nil
}

func cdpHTTP(port int, path string) ([]byte, error) {
	client := http.Client{Timeout: 2 * time.Second}
	response, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d%s", port, path))
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	return io.ReadAll(io.LimitReader(response.Body, 1<<20))
}

func cdpBrowserURL(port int) (string, error) {
	data, err := cdpHTTP(port, "/json/version")
	if err != nil {
		return "", err
	}
	var value struct {
		Browser              string `json:"Browser"`
		WebSocketDebuggerURL string `json:"webSocketDebuggerUrl"`
	}
	if err := json.Unmarshal(data, &value); err != nil {
		return "", err
	}
	if value.WebSocketDebuggerURL == "" {
		return "", errors.New("Edge 调试端口没有返回 WebSocket 地址")
	}
	return value.WebSocketDebuggerURL, nil
}

var edgeLoginMu sync.Mutex

type edgeProcess struct {
	ProcessID   int    `json:"ProcessId"`
	CommandLine string `json:"CommandLine"`
}

var edgePortPattern = regexp.MustCompile(`--remote-debugging-port=(\d+)`)

func edgeDebugPortPath(profileDir string) string {
	return filepath.Join(profileDir, ".cdp-port")
}

func readDevToolsActivePort(profileDir string) (int, bool) {
	data, err := os.ReadFile(filepath.Join(profileDir, "DevToolsActivePort"))
	if err != nil {
		return 0, false
	}
	line := strings.TrimSpace(strings.SplitN(string(data), "\n", 2)[0])
	port, err := strconv.Atoi(line)
	if err != nil || port <= 0 || port > 65535 {
		return 0, false
	}
	return port, true
}

func edgeProfileProcesses(ctx context.Context, profileDir string) ([]edgeProcess, error) {
	script := `$ErrorActionPreference='Stop'; $p=$env:SJTU_EDGE_PROFILE; ` +
		`Get-CimInstance Win32_Process -Filter "Name='msedge.exe'" | ` +
		`Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($p,[System.StringComparison]::OrdinalIgnoreCase) -ge 0 } | ` +
		`Select-Object ProcessId,CommandLine | ConvertTo-Json -Compress`
	command := exec.CommandContext(ctx, "powershell.exe", "-NoProfile", "-NonInteractive",
		"-ExecutionPolicy", "Bypass", "-Command", script)
	command.Env = append(os.Environ(), "SJTU_EDGE_PROFILE="+profileDir)
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	output, err := command.Output()
	if err != nil {
		return nil, err
	}
	text := strings.TrimSpace(string(output))
	if text == "" {
		return nil, nil
	}
	var list []edgeProcess
	if err := json.Unmarshal([]byte(text), &list); err == nil {
		return list, nil
	}
	var single edgeProcess
	if err := json.Unmarshal([]byte(text), &single); err != nil {
		return nil, err
	}
	return []edgeProcess{single}, nil
}

func edgeProcessPort(process edgeProcess) int {
	match := edgePortPattern.FindStringSubmatch(process.CommandLine)
	if len(match) != 2 {
		return 0
	}
	port, err := strconv.Atoi(match[1])
	if err != nil {
		return 0
	}
	return port
}

func stopEdgeProcess(pid int) {
	command := exec.Command("taskkill.exe", "/PID", strconv.Itoa(pid), "/T", "/F")
	hideConsoleWindow(command)
	command.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000}
	_ = command.Run()
}

func readEdgeDebugPort(profileDir string) (int, bool) {
	data, err := os.ReadFile(edgeDebugPortPath(profileDir))
	if err != nil {
		return 0, false
	}
	port, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || port <= 0 || port > 65535 {
		return 0, false
	}
	return port, true
}

func writeEdgeDebugPort(profileDir string, port int) {
	_ = os.WriteFile(edgeDebugPortPath(profileDir), []byte(strconv.Itoa(port)), 0o600)
}

func clearEdgeDebugPort(profileDir string) {
	_ = os.Remove(edgeDebugPortPath(profileDir))
}

func edgeLogin(ctx context.Context, logger *slog.Logger, progress func(string)) ([]cookieRecord, error) {
	edgeLoginMu.Lock()
	defer edgeLoginMu.Unlock()

	edge, err := findEdge()
	if err != nil {
		return nil, err
	}
	profileDir, err := ensureEdgeProfile()
	if err != nil {
		return nil, err
	}
	port := 0
	attached := false
	if saved, ok := readEdgeDebugPort(profileDir); ok {
		if _, probeErr := cdpBrowserURL(saved); probeErr == nil {
			port = saved
			attached = true
			if logger != nil {
				logger.Info("edge login reusing browser", "port", port)
			}
		} else {
			clearEdgeDebugPort(profileDir)
		}
	}
	if !attached {
		if active, ok := readDevToolsActivePort(profileDir); ok {
			if _, probeErr := cdpBrowserURL(active); probeErr == nil {
				port = active
				attached = true
				writeEdgeDebugPort(profileDir, port)
				if logger != nil {
					logger.Info("edge login reusing browser", "port", port, "source", "DevToolsActivePort")
				}
			}
		}
	}
	if !attached {
		probeCtx, cancelProbe := context.WithTimeout(ctx, 12*time.Second)
		processes, scanErr := edgeProfileProcesses(probeCtx, profileDir)
		cancelProbe()
		if scanErr != nil && logger != nil {
			logger.Warn("edge process scan failed", "error", scanErr)
		}
		browserProcesses := make([]edgeProcess, 0, 1)
		for _, process := range processes {
			if !strings.Contains(process.CommandLine, "--type=") {
				browserProcesses = append(browserProcesses, process)
			}
		}
		if len(browserProcesses) > 0 {
			processes = browserProcesses
		}
		for _, process := range processes {
			candidate := edgeProcessPort(process)
			if candidate <= 0 {
				continue
			}
			if _, probeErr := cdpBrowserURL(candidate); probeErr != nil {
				continue
			}
			port = candidate
			attached = true
			writeEdgeDebugPort(profileDir, port)
			if logger != nil {
				logger.Info("edge login reusing browser", "port", port, "pid", process.ProcessID)
			}
			break
		}
		if !attached && len(processes) > 0 {
			for _, process := range processes {
				stopEdgeProcess(process.ProcessID)
				if logger != nil {
					logger.Info("edge login closed stale browser", "pid", process.ProcessID)
				}
			}
			time.Sleep(900 * time.Millisecond)
		}
	}
	var command *exec.Cmd
	if !attached {
		port, err = freePort()
		if err != nil {
			return nil, err
		}
		if progress != nil {
			progress("waiting")
		}
		args := []string{
			"--user-data-dir=" + profileDir,
			"--remote-debugging-port=" + strconv.Itoa(port),
			"--remote-allow-origins=*",
			"--no-first-run",
			"--no-default-browser-check",
			"--disable-sync",
			"--disable-features=msEdgeSidebarV2",
			"--app=https://i.sjtu.edu.cn/jaccountlogin",
			"--window-size=1120,820",
		}
		command = exec.Command(edge, args...)
		if err := command.Start(); err != nil {
			return nil, fmt.Errorf("无法启动 Edge 登录窗口：%w", err)
		}
		writeEdgeDebugPort(profileDir, port)
		if logger != nil {
			logger.Info("edge login launched", "port", port)
		}
	}

	var client *cdpClient
	defer func() {
		if client != nil {
			_ = client.call("Browser.close", nil, nil)
			client.close()
		}
		if command != nil && command.Process != nil {
			_ = command.Process.Kill()
		}
		clearEdgeDebugPort(profileDir)
	}()

	var exited <-chan error
	if command != nil {
		channel := make(chan error, 1)
		go func() {
			channel <- command.Wait()
		}()
		exited = channel
	}

	deadline := time.Now().Add(25 * time.Second)
	var wsURL string
waitDebugger:
	for time.Now().Before(deadline) {
		if value, probeErr := cdpBrowserURL(port); probeErr == nil {
			wsURL = value
			break
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case err := <-exited:
			if value, probeErr := cdpBrowserURL(port); probeErr == nil {
				wsURL = value
				break waitDebugger
			}
			if attached {
				return nil, fmt.Errorf("Edge 登录窗口已关闭：%v", err)
			}
			return nil, fmt.Errorf("Edge 登录实例被已有窗口接管，调试端口未能就绪（%v）。请关闭所有使用应用登录配置的 Edge 窗口后重试", err)
		case <-time.After(400 * time.Millisecond):
		}
	}
	if wsURL == "" {
		return nil, errors.New("等待 Edge 登录窗口超时，请重试")
	}
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, _, err := dialer.DialContext(ctx, wsURL, nil)
	if err != nil {
		return nil, fmt.Errorf("连接 Edge 调试端口失败：%w", err)
	}
	client = &cdpClient{conn: conn}

	var targetList struct {
		TargetInfos []edgeTarget `json:"targetInfos"`
	}
	_ = client.call("Target.getTargets", nil, &targetList)

	authDeadline := time.Now().Add(edgeLoginTimeout)
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case err := <-exited:
			return nil, fmt.Errorf("登录窗口已关闭：%v", err)
		case <-ticker.C:
			if time.Now().After(authDeadline) {
				return nil, errors.New("等待交大登录超时，请重新点击登录并在窗口中完成登录")
			}
			cookies, err := readEdgeCookies(client)
			if err != nil {
				select {
				case waitErr := <-exited:
					return nil, fmt.Errorf("登录窗口已关闭：%v", waitErr)
				default:
				}
				continue
			}
			if hasCampusAuth(cookies) {
				if progress != nil {
					progress("video")
				}
				time.Sleep(500 * time.Millisecond)
				_ = client.call("Target.createTarget", map[string]any{
					"url": "https://v.sjtu.edu.cn/jy-application-resourcemanage-ui/",
				}, nil)
				time.Sleep(3 * time.Second)
				finalCookies, readErr := readEdgeCookies(client)
				if readErr == nil && len(finalCookies) >= len(cookies) {
					cookies = finalCookies
				}
				_ = client.call("Browser.close", nil, nil)
				return cookies, nil
			}
		}
	}
}

func ensureEdgeProfile() (string, error) {
	base := os.Getenv("LOCALAPPDATA")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		base = filepath.Join(home, "AppData", "Local")
	}
	dir := filepath.Join(base, AppName, "edge-campus")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	return dir, nil
}

func readEdgeCookies(client *cdpClient) ([]cookieRecord, error) {
	var value struct {
		Cookies []edgeCookie `json:"cookies"`
	}
	if err := client.call("Storage.getCookies", map[string]any{}, &value); err != nil {
		return nil, err
	}
	result := make([]cookieRecord, 0, len(value.Cookies))
	for _, cookie := range value.Cookies {
		if !strings.Contains(strings.ToLower(cookie.Domain), "sjtu.edu.cn") {
			continue
		}
		record := cookieRecord{
			Domain:   cookie.Domain,
			Path:     cookie.Path,
			Name:     cookie.Name,
			Value:    cookie.Value,
			Secure:   cookie.Secure,
			HttpOnly: cookie.HTTPOnly,
			HostOnly: !strings.HasPrefix(cookie.Domain, "."),
		}
		if record.Path == "" {
			record.Path = "/"
		}
		if !cookie.Session && cookie.Expires > 0 {
			record.Expires = time.Unix(int64(cookie.Expires), 0)
		}
		result = append(result, record)
	}
	return result, nil
}
