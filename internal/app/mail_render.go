package app

// 部分营销邮件的图片放在国内无法解析的 CDN 上，WebView 会渲染成一排“破图”方块。
// 打开邮件时先把解析不到的图片从正文里摘掉，并在详情里标注，前端据此默认展示纯文本。

import (
	"context"
	"net"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"
)

var (
	mailImageTagPattern = regexp.MustCompile(`(?is)<img\b[^>]*>`)
	mailImageSrcPattern = regexp.MustCompile(`(?is)\bsrc\s*=\s*["']([^"']+)["']`)
)

type mailHostCache struct {
	mu    sync.Mutex
	items map[string]mailHostEntry
}

type mailHostEntry struct {
	ok      bool
	expires time.Time
}

var mailReachability = mailHostCache{items: make(map[string]mailHostEntry)}

func (c *mailHostCache) resolves(host string) bool {
	now := time.Now()
	c.mu.Lock()
	if entry, ok := c.items[host]; ok && now.Before(entry.expires) {
		c.mu.Unlock()
		return entry.ok
	}
	c.mu.Unlock()
	ctx, cancel := context.WithTimeout(context.Background(), 1500*time.Millisecond)
	defer cancel()
	addresses, err := net.DefaultResolver.LookupHost(ctx, host)
	ok := err == nil && len(addresses) > 0
	c.mu.Lock()
	c.items[host] = mailHostEntry{ok: ok, expires: now.Add(5 * time.Minute)}
	c.mu.Unlock()
	return ok
}

// filterRemoteImages drops <img> tags whose hosts cannot be resolved, so the
// mail body keeps its text and layout without browser broken-image boxes.
func filterRemoteImages(html string) (string, []string) {
	if html == "" {
		return html, nil
	}
	blocked := make([]string, 0, 2)
	checkedHosts := make(map[string]bool)
	blockedHosts := make(map[string]bool)
	lookedUp := 0
	cleaned := mailImageTagPattern.ReplaceAllStringFunc(html, func(tag string) string {
		match := mailImageSrcPattern.FindStringSubmatch(tag)
		if len(match) != 2 {
			return tag
		}
		parsed, err := url.Parse(strings.TrimSpace(match[1]))
		if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") {
			return tag
		}
		host := strings.ToLower(parsed.Hostname())
		if host == "" || host == "localhost" || host == "127.0.0.1" || host == "::1" {
			return tag
		}
		if blockedHosts[host] {
			return ""
		}
		if !checkedHosts[host] {
			if lookedUp >= 6 {
				return tag
			}
			lookedUp++
			checkedHosts[host] = true
			if mailReachability.resolves(host) {
				return tag
			}
			blockedHosts[host] = true
			blocked = append(blocked, host)
		}
		return ""
	})
	return cleaned, blocked
}
