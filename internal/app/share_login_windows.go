//go:build windows

package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

// loginShareToken opens a dedicated Edge window so the user can complete the
// site's jAccount OAuth flow; the token is then read from its localStorage.
func loginShareToken(ctx context.Context) (string, error) {
	edge, err := findEdge()
	if err != nil {
		return "", err
	}
	campusProfile, err := ensureEdgeProfile()
	if err != nil {
		return "", err
	}
	profileDir := filepath.Join(filepath.Dir(campusProfile), "edge-share")
	if err := os.MkdirAll(profileDir, 0o755); err != nil {
		return "", err
	}
	probeCtx, cancelProbe := context.WithTimeout(ctx, 8*time.Second)
	processes, _ := edgeProfileProcesses(probeCtx, profileDir)
	cancelProbe()
	for _, process := range processes {
		stopEdgeProcess(process.ProcessID)
	}
	if len(processes) > 0 {
		time.Sleep(700 * time.Millisecond)
	}

	port, err := freePort()
	if err != nil {
		return "", err
	}
	command := exec.Command(edge,
		"--user-data-dir="+profileDir,
		"--remote-debugging-port="+strconv.Itoa(port),
		"--remote-allow-origins=*",
		"--no-first-run",
		"--no-default-browser-check",
		"--disable-sync",
		"--disable-features=msEdgeSidebarV2",
		"--app=https://share.dyweb.sjtu.cn/",
		"--window-size=1120,820",
	)
	if err := command.Start(); err != nil {
		return "", fmt.Errorf("无法启动传承交大登录窗口：%w", err)
	}
	cleanup := func() {
		_ = command.Process.Kill()
		closeCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		stale, _ := edgeProfileProcesses(closeCtx, profileDir)
		cancel()
		for _, process := range stale {
			stopEdgeProcess(process.ProcessID)
		}
	}
	defer cleanup()

	var wsURL string
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		resp, requestErr := http.Get(fmt.Sprintf("http://127.0.0.1:%d/json/list", port))
		if requestErr == nil {
			body, _ := io.ReadAll(resp.Body)
			_ = resp.Body.Close()
			var targets []struct {
				Type                 string `json:"type"`
				URL                  string `json:"url"`
				WebSocketDebuggerURL string `json:"webSocketDebuggerUrl"`
			}
			if json.Unmarshal(body, &targets) == nil {
				for _, target := range targets {
					if target.Type == "page" && strings.Contains(target.URL, "dyweb.sjtu.cn") && target.WebSocketDebuggerURL != "" {
						wsURL = target.WebSocketDebuggerURL
						break
					}
				}
			}
		}
		if wsURL != "" {
			break
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(500 * time.Millisecond):
		}
	}
	if wsURL == "" {
		return "", errors.New("等待传承交大登录窗口超时，请重试")
	}
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, _, err := dialer.DialContext(ctx, wsURL, nil)
	if err != nil {
		return "", fmt.Errorf("连接登录窗口失败：%w", err)
	}
	client := &cdpClient{conn: conn}
	defer client.close()
	_ = client.call("Runtime.enable", nil, nil)

	authDeadline := time.Now().Add(3 * time.Minute)
	for {
		var evaluated struct {
			Result struct {
				Value any `json:"value"`
			} `json:"result"`
		}
		_ = client.call("Runtime.evaluate", map[string]any{
			"expression":    "localStorage.getItem('token')",
			"returnByValue": true,
		}, &evaluated)
		if token, _ := evaluated.Result.Value.(string); strings.TrimSpace(token) != "" {
			_ = client.call("Browser.close", nil, nil)
			return token, nil
		}
		if time.Now().After(authDeadline) {
			return "", errors.New("等待 jAccount 登录超时，请重新点击登录并在窗口中完成登录")
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(time.Second):
		}
	}
}
