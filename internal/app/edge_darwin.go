//go:build darwin

package app

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

var browserLoginMu sync.Mutex

func findChromium() (string, error) {
	home, _ := os.UserHomeDir()
	candidates := []string{
		"/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
		"/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
		"/Applications/Chromium.app/Contents/MacOS/Chromium",
		"/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
		filepath.Join(home, "Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"),
		filepath.Join(home, "Applications/Google Chrome.app/Contents/MacOS/Google Chrome"),
	}
	for _, candidate := range candidates {
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	for _, name := range []string{"msedge", "google-chrome", "chromium", "brave"} {
		if path, err := exec.LookPath(name); err == nil {
			return path, nil
		}
	}
	return "", errors.New("没有找到 Edge / Chrome / Chromium，请安装后再试，或使用 Cookie 粘贴方式")
}

func ensureBrowserProfile() (string, error) {
	cache, err := os.UserCacheDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(cache, AppName, "browser-login")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	return dir, nil
}

func stopProfileProcesses(profileDir string) {
	_ = exec.Command("pkill", "-f", "user-data-dir="+profileDir).Run()
}

func edgeLogin(ctx context.Context, logger *slog.Logger, progress func(string)) ([]cookieRecord, error) {
	browserLoginMu.Lock()
	defer browserLoginMu.Unlock()

	browser, err := findChromium()
	if err != nil {
		return nil, err
	}
	profileDir, err := ensureBrowserProfile()
	if err != nil {
		return nil, err
	}
	port := 0
	if saved, ok := readEdgeDebugPort(profileDir); ok {
		if _, probeErr := cdpBrowserURL(saved); probeErr == nil {
			port = saved
			if logger != nil {
				logger.Info("browser login reusing session", "port", port)
			}
		} else {
			clearEdgeDebugPort(profileDir)
		}
	}
	if port == 0 {
		if active, ok := readDevToolsActivePort(profileDir); ok {
			if _, probeErr := cdpBrowserURL(active); probeErr == nil {
				port = active
				writeEdgeDebugPort(profileDir, port)
			}
		}
	}
	var command *exec.Cmd
	if port == 0 {
		stopProfileProcesses(profileDir)
		time.Sleep(500 * time.Millisecond)
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
			"--app=https://i.sjtu.edu.cn/jaccountlogin",
			"--window-size=1120,820",
		}
		command = exec.Command(browser, args...)
		if err := command.Start(); err != nil {
			return nil, fmt.Errorf("无法启动登录窗口：%w", err)
		}
		writeEdgeDebugPort(profileDir, port)
		if logger != nil {
			logger.Info("browser login launched", "port", port)
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
	for time.Now().Before(deadline) {
		if value, probeErr := cdpBrowserURL(port); probeErr == nil {
			wsURL = value
			break
		}
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case err := <-exited:
			return nil, fmt.Errorf("登录窗口已关闭：%v", err)
		case <-time.After(400 * time.Millisecond):
		}
	}
	if wsURL == "" {
		return nil, errors.New("等待登录窗口超时，请重试")
	}
	dialer := websocket.Dialer{HandshakeTimeout: 10 * time.Second}
	conn, _, err := dialer.DialContext(ctx, wsURL, nil)
	if err != nil {
		return nil, fmt.Errorf("连接登录窗口失败：%w", err)
	}
	client = &cdpClient{conn: conn}

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
