package app

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/gorilla/websocket"
)

// Shared Chromium DevTools helpers used by the Windows and macOS login flows.

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
		return "", errors.New("浏览器调试端口没有返回 WebSocket 地址")
	}
	return value.WebSocketDebuggerURL, nil
}

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
