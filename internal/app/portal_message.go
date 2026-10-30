package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

// 交我办站内消息（https://my.sjtu.edu.cn/ui/message/）。
// 与日历共用 jAccount SSO 会话，接口前缀 /api/jwbmessage。
const (
	portalMessagePage   = "https://my.sjtu.edu.cn/ui/message/"
	portalMessageOrigin = "https://my.sjtu.edu.cn"
	portalMessageTTL    = 10 * time.Minute
)

var ErrPortalMessageAuth = errors.New("交我办会话已失效，请重新登录教学网")

type PortalMessage struct {
	ID         string `json:"id"`
	Title      string `json:"title"`
	Body       string `json:"body"`
	Kind       string `json:"kind"`
	SourceName string `json:"sourceName"`
	URL        string `json:"url"`
	CreatedAt  string `json:"createdAt"`
	Read       bool   `json:"read"`
}

type PortalMessageState struct {
	Connected    bool   `json:"connected"`
	Account      string `json:"account"`
	Unread       int    `json:"unread"`
	LastSyncedAt string `json:"lastSyncedAt"`
	Error        string `json:"error"`
}

type portalMessageGroup struct {
	GroupID   string `json:"groupId"`
	GroupName string `json:"groupName"`
	IsGroup   bool   `json:"isGroup"`
	UnreadNum int    `json:"unreadNum"`
}

type portalMessageRaw struct {
	MessageID   string `json:"messageId"`
	Title       string `json:"title"`
	PushTitle   string `json:"pushTitle"`
	PushContent string `json:"pushContent"`
	Description string `json:"description"`
	Type        string `json:"type"`
	CreateTime  string `json:"createTime"`
	Read        bool   `json:"read"`
	Context     []struct {
		Key   string `json:"key"`
		Value string `json:"value"`
	} `json:"context"`
	AuthClient struct {
		Name string `json:"name"`
	} `json:"authClient"`
	URLList []struct {
		URL     string `json:"url"`
		URLName string `json:"urlName"`
		URLType string `json:"urlType"`
	} `json:"urlList"`
}

type portalMessageEnvelope struct {
	Success  bool               `json:"success"`
	Errno    int                `json:"errno"`
	Message  string             `json:"message"`
	Total    int                `json:"total"`
	Entities []portalMessageRaw `json:"entities"`
	Entity   struct {
		Total int `json:"total"`
	} `json:"entity"`
}

type PortalMessages struct {
	db       *sql.DB
	campus   *CampusSession
	notifier *Notifications
	account  func() string
	emit     func(string, any)

	mu           sync.RWMutex
	lastSyncedAt string
	lastError    string
	unread       int

	syncMu sync.Mutex
}

func NewPortalMessages(db *sql.DB, campus *CampusSession, notifier *Notifications, account func() string, emit func(string, any)) *PortalMessages {
	value := &PortalMessages{db: db, campus: campus, notifier: notifier, account: account, emit: emit}
	var synced, unread string
	_ = db.QueryRow(`SELECT value FROM portal_message_meta WHERE key = 'last_synced'`).Scan(&synced)
	_ = db.QueryRow(`SELECT value FROM portal_message_meta WHERE key = 'unread'`).Scan(&unread)
	value.lastSyncedAt = synced
	value.unread = parseIntOr(unread, 0)
	return value
}

func (p *PortalMessages) State() PortalMessageState {
	p.mu.RLock()
	defer p.mu.RUnlock()
	state := PortalMessageState{
		Connected:    p.campus.Connected(),
		Unread:       p.unread,
		LastSyncedAt: p.lastSyncedAt,
		Error:        p.lastError,
	}
	if p.account != nil {
		state.Account = p.account()
	}
	return state
}

func (p *PortalMessages) setError(message string) {
	p.mu.Lock()
	p.lastError = message
	p.mu.Unlock()
}

func (p *PortalMessages) List(limit int) ([]PortalMessage, error) {
	if limit <= 0 || limit > 100 {
		limit = 30
	}
	rows, err := p.db.Query(`SELECT id, title, body, kind, source_name, url, created_at, is_read
		FROM portal_messages ORDER BY created_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []PortalMessage{}
	for rows.Next() {
		var item PortalMessage
		var read int
		if err := rows.Scan(&item.ID, &item.Title, &item.Body, &item.Kind, &item.SourceName,
			&item.URL, &item.CreatedAt, &read); err != nil {
			return nil, err
		}
		item.Read = read != 0
		result = append(result, item)
	}
	return result, rows.Err()
}

func (p *PortalMessages) client() *http.Client {
	client := *p.campus.RedirectClient()
	client.Timeout = 25 * time.Second
	return &client
}

func (p *PortalMessages) request(ctx context.Context, client *http.Client, rawURL string, target any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("X-Requested-With", "XMLHttpRequest")
	request.Header.Set("Referer", portalMessagePage)
	request.Header.Set("User-Agent", portalUserAgent)
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		return ErrPortalMessageAuth
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("交我办消息返回 HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 8<<20))
	if err != nil {
		return err
	}
	if err := json.Unmarshal(data, target); err != nil {
		return ErrPortalMessageAuth
	}
	return nil
}

// bootstrap walks the jAccount SSO chain so my.sjtu issues its own session cookie.
func (p *PortalMessages) bootstrap(ctx context.Context, client *http.Client) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, portalMessagePage, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", portalUserAgent)
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<20))
	return nil
}

func (p *PortalMessages) Sync(ctx context.Context) (PortalMessageState, error) {
	if !p.campus.Connected() {
		p.setError("尚未登录教学网")
		return p.State(), errors.New("尚未登录教学网，无法读取交我办消息")
	}
	p.syncMu.Lock()
	defer p.syncMu.Unlock()

	client := p.client()
	groups, err := p.loadGroups(ctx, client)
	if errors.Is(err, ErrPortalMessageAuth) {
		if bootErr := p.bootstrap(ctx, client); bootErr != nil {
			p.setError(bootErr.Error())
			return p.State(), bootErr
		}
		groups, err = p.loadGroups(ctx, client)
	}
	if err != nil {
		p.setError(err.Error())
		return p.State(), err
	}

	unread := 0
	fetched := []PortalMessage{}
	for _, group := range groups {
		unread += group.UnreadNum
		if group.UnreadNum == 0 {
			continue
		}
		if len(fetched) >= 30 {
			break
		}
		messages, err := p.loadMessages(ctx, client, group)
		if err != nil {
			p.setError(err.Error())
			return p.State(), err
		}
		fetched = append(fetched, messages...)
		time.Sleep(400 * time.Millisecond)
	}

	syncedAt := Now()
	for _, message := range fetched {
		seen, err := p.store(message, syncedAt)
		if err != nil {
			p.setError(err.Error())
			return p.State(), err
		}
		if !seen && p.notifier != nil {
			p.notifier.Add("portal", message.Title, message.Body,
				map[string]any{"view": "notifications"}, "portal:"+message.ID, true)
		}
	}

	p.mu.Lock()
	p.unread = unread
	p.lastSyncedAt = syncedAt
	p.lastError = ""
	p.mu.Unlock()
	_, _ = p.db.Exec(`INSERT INTO portal_message_meta (key, value) VALUES ('last_synced', ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, syncedAt)
	_, _ = p.db.Exec(`INSERT INTO portal_message_meta (key, value) VALUES ('unread', ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, fmt.Sprint(unread))
	p.emit("portalMessage.changed", p.State())
	return p.State(), nil
}

func (p *PortalMessages) loadGroups(ctx context.Context, client *http.Client) ([]portalMessageGroup, error) {
	values := url.Values{}
	values.Set("key", "")
	values.Set("page", "0")
	values.Set("pageSize", "30")
	values.Set("read", "false")
	var envelope struct {
		Success  bool   `json:"success"`
		Message  string `json:"message"`
		Entities []struct {
			GroupID   string `json:"groupId"`
			GroupName string `json:"groupName"`
			IsGroup   bool   `json:"isGroup"`
			UnreadNum int    `json:"unreadNum"`
		} `json:"entities"`
	}
	if err := p.request(ctx, client, portalMessageOrigin+"/api/jwbmessage/group?"+values.Encode(), &envelope); err != nil {
		return nil, err
	}
	if !envelope.Success {
		return nil, ErrPortalMessageAuth
	}
	groups := make([]portalMessageGroup, 0, len(envelope.Entities))
	for _, item := range envelope.Entities {
		groups = append(groups, portalMessageGroup{
			GroupID: item.GroupID, GroupName: item.GroupName, IsGroup: item.IsGroup, UnreadNum: item.UnreadNum,
		})
	}
	return groups, nil
}

func (p *PortalMessages) loadMessages(ctx context.Context, client *http.Client, group portalMessageGroup) ([]PortalMessage, error) {
	values := url.Values{}
	values.Set("groupId", group.GroupID)
	values.Set("isGroup", fmt.Sprint(group.IsGroup))
	values.Set("page", "0")
	values.Set("pageSize", "20")
	values.Set("key", "")
	values.Set("read", "false")
	var envelope struct {
		Success  bool               `json:"success"`
		Message  string             `json:"message"`
		Entities []portalMessageRaw `json:"entities"`
	}
	if err := p.request(ctx, client, portalMessageOrigin+"/api/jwbmessage/messagelist?"+values.Encode(), &envelope); err != nil {
		return nil, err
	}
	if !envelope.Success {
		return nil, errors.New("交我办消息返回失败：" + firstNonEmpty(envelope.Message, "未知错误"))
	}
	result := make([]PortalMessage, 0, len(envelope.Entities))
	for _, raw := range envelope.Entities {
		if strings.TrimSpace(raw.MessageID) == "" {
			continue
		}
		body := firstNonEmpty(raw.PushContent, raw.Description)
		if extra := portalMessageContext(raw); extra != "" {
			body = firstNonEmpty(body, extra)
		}
		link := ""
		for _, item := range raw.URLList {
			if strings.HasPrefix(item.URL, "http") {
				link = item.URL
				break
			}
		}
		result = append(result, PortalMessage{
			ID:         raw.MessageID,
			Title:      firstNonEmpty(strings.TrimSpace(raw.Title), strings.TrimSpace(raw.PushTitle), group.GroupName),
			Body:       body,
			Kind:       firstNonEmpty(raw.Type, "basic"),
			SourceName: firstNonEmpty(raw.AuthClient.Name, group.GroupName),
			URL:        link,
			CreatedAt:  raw.CreateTime,
			Read:       raw.Read,
		})
	}
	return result, nil
}

func portalMessageContext(raw portalMessageRaw) string {
	parts := make([]string, 0, len(raw.Context))
	for _, item := range raw.Context {
		if strings.TrimSpace(item.Value) == "" {
			continue
		}
		parts = append(parts, strings.TrimSpace(item.Value))
	}
	return strings.Join(parts, " · ")
}

// store inserts a message and reports whether it was already known.
func (p *PortalMessages) store(message PortalMessage, syncedAt string) (bool, error) {
	var existing int
	err := p.db.QueryRow(`SELECT COUNT(*) FROM portal_messages WHERE id = ?`, message.ID).Scan(&existing)
	if err != nil {
		return false, err
	}
	if existing > 0 {
		if _, err := p.db.Exec(`UPDATE portal_messages SET is_read = ? WHERE id = ?`,
			boolToInt(message.Read), message.ID); err != nil {
			return true, err
		}
		return true, nil
	}
	_, err = p.db.Exec(`INSERT INTO portal_messages
		(id, title, body, kind, source_name, url, created_at, is_read, fetched_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		message.ID, message.Title, message.Body, message.Kind, message.SourceName, message.URL,
		message.CreatedAt, boolToInt(message.Read), syncedAt)
	return false, err
}

func (p *PortalMessages) Register(router *Router) {
	router.Handle("portalMessage.state", func([]json.RawMessage) (any, error) {
		return p.State(), nil
	})
	router.Handle("portalMessage.list", func(args []json.RawMessage) (any, error) {
		limit, _, _ := DecodeOptionalArg[int64](args, 0)
		return p.List(int(limit))
	})
	router.Handle("portalMessage.refresh", func([]json.RawMessage) (any, error) {
		return p.Sync(context.Background())
	})
}

func (a *App) startPortalMessageLoop() {
	go func() {
		time.Sleep(6 * time.Second)
		syncOnce := func() {
			ctx, cancel := context.WithTimeout(a.ctx, 90*time.Second)
			defer cancel()
			if _, err := a.portalMessages.Sync(ctx); err != nil && a.logger != nil && !errors.Is(err, ErrPortalMessageAuth) {
				a.logger.Warn("portal message sync failed", "error", err)
			}
		}
		syncOnce()
		ticker := time.NewTicker(portalMessageTTL)
		defer ticker.Stop()
		for {
			select {
			case <-a.ctx.Done():
				return
			case <-ticker.C:
				syncOnce()
			}
		}
	}()
}

func boolToInt(value bool) int {
	if value {
		return 1
	}
	return 0
}

func parseIntOr(text string, fallback int) int {
	parsed, err := strconv.Atoi(strings.TrimSpace(text))
	if err != nil {
		return fallback
	}
	return parsed
}

const portalUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0"
