package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type NotificationItem struct {
	ID           int64          `json:"id"`
	Kind         string         `json:"kind"`
	Title        string         `json:"title"`
	Body         string         `json:"body"`
	Route        map[string]any `json:"route"`
	CreatedAt    string         `json:"createdAt"`
	ReadAt       *string        `json:"readAt"`
	SnoozedUntil *string        `json:"snoozedUntil"`
}

type Notifications struct {
	db       *sql.DB
	settings *Settings
	shuiyuan *Shuiyuan
	logger   *slog.Logger
	emit     func(string, any)

	mu          sync.Mutex
	initialized bool
	toaster     func(title, body string) error

	// 启动后的第一次同步窗口内不逐条弹窗，等汇总后只弹一条。
	startupSince     string
	startupActive    atomic.Bool
	startupRemaining atomic.Int32
	startupDone      chan struct{}
	startupDoneOnce  sync.Once
}

func NewNotifications(db *sql.DB, settings *Settings, shuiyuan *Shuiyuan, logger *slog.Logger, emit func(string, any)) *Notifications {
	value := &Notifications{
		db: db, settings: settings, shuiyuan: shuiyuan, logger: logger, emit: emit,
		startupSince: time.Now().Format(time.RFC3339),
		startupDone:  make(chan struct{}),
	}
	value.startupActive.Store(true)
	value.startupRemaining.Store(2) // 本地首轮刷新 + 交我办首轮同步
	return value
}

// SetToaster installs the system notification channel (the tray balloon).
func (n *Notifications) SetToaster(toast func(title, body string) error) {
	n.mu.Lock()
	n.toaster = toast
	n.mu.Unlock()
}

func (n *Notifications) toast(title, body string) {
	n.mu.Lock()
	toaster := n.toaster
	n.mu.Unlock()
	if toaster == nil {
		return
	}
	if err := toaster(title, body); err != nil && n.logger != nil {
		n.logger.Warn("system notification failed", "error", err)
	}
}

func (n *Notifications) Unread() int {
	var count int
	now := Now()
	_ = n.db.QueryRow(`SELECT COUNT(*) FROM notifications WHERE read_at IS NULL
		AND (snoozed_until IS NULL OR snoozed_until <= ?)`, now).Scan(&count)
	return count
}

func (n *Notifications) emitChanged() {
	if n.emit != nil {
		n.emit("notifications.changed", map[string]any{"unread": n.Unread()})
	}
}

func (n *Notifications) Add(kind, title, body string, route map[string]any, dedupe string, allowToast bool) bool {
	settings := n.settings.State()
	switch kind {
	case "portal", "mail", "ddl", "shuiyuan":
		if !settings.CampusNotifications {
			return false
		}
	case "todo":
		if !settings.TaskNotifications {
			return false
		}
	}
	routeJSON, _ := json.Marshal(route)
	result, err := n.db.Exec(`INSERT OR IGNORE INTO notifications
		(kind, title, body, route, dedupe_key, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		kind, title, body, string(routeJSON), nullableText(dedupe), Now())
	if err != nil {
		return false
	}
	affected, _ := result.RowsAffected()
	if affected == 0 {
		return false
	}
	n.emitChanged()
	if allowToast && n.initialized && !n.startupActive.Load() {
		n.toast(title, body)
	}
	return true
}

type startupNotification struct {
	Kind  string
	Title string
}

// startupNotifications returns entries discovered since this launch, i.e. the
// messages that arrived while the app was not running. In-app list is unchanged.
func (n *Notifications) startupNotifications(since string) []startupNotification {
	rows, err := n.db.Query(`SELECT kind, title FROM notifications
		WHERE created_at >= ? AND read_at IS NULL
		AND (snoozed_until IS NULL OR snoozed_until <= ?)
		ORDER BY created_at DESC`, since, Now())
	if err != nil {
		return nil
	}
	defer rows.Close()
	result := []startupNotification{}
	for rows.Next() {
		var item startupNotification
		if rows.Scan(&item.Kind, &item.Title) == nil {
			result = append(result, item)
		}
	}
	return result
}

func formatStartupNotification(items []startupNotification) (string, string) {
	if len(items) == 0 {
		return "", ""
	}
	labels := map[string]string{
		"portal":   "交我办",
		"mail":     "邮件",
		"ddl":      "DDL",
		"todo":     "待办",
		"shuiyuan": "水源",
	}
	order := []string{"portal", "mail", "ddl", "todo", "shuiyuan"}
	seen := map[string]bool{}
	for _, kind := range order {
		seen[kind] = true
	}
	counts := map[string]int{}
	for _, item := range items {
		kind := strings.TrimSpace(item.Kind)
		if kind == "" {
			kind = "other"
		}
		counts[kind]++
		if !seen[kind] {
			order = append(order, kind)
			seen[kind] = true
		}
	}
	parts := []string{}
	for _, kind := range order {
		count := counts[kind]
		if count == 0 {
			continue
		}
		label := labels[kind]
		if label == "" {
			label = "消息"
		}
		unit := "条"
		if kind == "mail" {
			unit = "封"
		}
		parts = append(parts, fmt.Sprintf("%s %d %s", label, count, unit))
	}
	titles := []string{}
	for _, item := range items {
		title := strings.TrimSpace(item.Title)
		if title == "" {
			continue
		}
		titles = append(titles, title)
		if len(titles) >= 3 {
			break
		}
	}
	body := strings.Join(parts, " · ")
	if len(titles) > 0 {
		body += " · " + strings.Join(titles, "、")
	}
	if len(items) > len(titles) && len(titles) >= 3 {
		body += " 等"
	}
	return fmt.Sprintf("你有 %d 条新消息", len(items)), body
}

// MarkStartupSourceDone is called by each first-run sync source. The startup
// summary waits for all of them so slow IMAP/portal syncs are still aggregated.
func (n *Notifications) MarkStartupSourceDone() {
	if n.startupRemaining.Add(-1) <= 0 {
		n.startupDoneOnce.Do(func() { close(n.startupDone) })
	}
}

func (n *Notifications) waitStartupSources(ctx context.Context, timeout time.Duration) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-n.startupDone:
	case <-timer.C:
	}
}

func (n *Notifications) flushStartup() {
	if !n.startupActive.CompareAndSwap(true, false) {
		return
	}
	since := n.startupSince
	title, body := formatStartupNotification(n.startupNotifications(since))
	if title != "" {
		n.toast(title, body)
	}
}

func (n *Notifications) List() ([]NotificationItem, error) {
	now := Now()
	rows, err := n.db.Query(`SELECT id, kind, title, body, route, created_at, read_at, snoozed_until
		FROM notifications WHERE snoozed_until IS NULL OR snoozed_until <= ?
		ORDER BY created_at DESC LIMIT 100`, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []NotificationItem{}
	for rows.Next() {
		var item NotificationItem
		var route, readAt, snoozedUntil sql.NullString
		if err := rows.Scan(&item.ID, &item.Kind, &item.Title, &item.Body, &route, &item.CreatedAt, &readAt, &snoozedUntil); err != nil {
			return nil, err
		}
		_ = json.Unmarshal([]byte(route.String), &item.Route)
		if item.Route == nil {
			item.Route = map[string]any{}
		}
		if readAt.Valid {
			value := readAt.String
			item.ReadAt = &value
		}
		if snoozedUntil.Valid {
			value := snoozedUntil.String
			item.SnoozedUntil = &value
		}
		result = append(result, item)
	}
	return result, rows.Err()
}

func (n *Notifications) MarkRead(id int64) error {
	_, err := n.db.Exec(`UPDATE notifications SET read_at = ? WHERE id = ? AND read_at IS NULL`, Now(), id)
	if err == nil {
		n.emitChanged()
	}
	return err
}

func (n *Notifications) MarkAllRead() error {
	_, err := n.db.Exec(`UPDATE notifications SET read_at = ? WHERE read_at IS NULL`, Now())
	if err == nil {
		n.emitChanged()
	}
	return err
}

func (n *Notifications) Snooze(id int64, until string) error {
	if _, err := time.Parse(time.RFC3339, until); err != nil {
		return err
	}
	_, err := n.db.Exec(`UPDATE notifications SET snoozed_until = ? WHERE id = ?`, until, id)
	if err == nil {
		n.emitChanged()
	}
	return err
}

func (n *Notifications) Refresh() {
	n.mu.Lock()
	defer n.mu.Unlock()
	budget := 3
	if !n.initialized {
		budget = 0
	}
	now := time.Now()
	cutoff := now.AddDate(0, 0, -7).Format(time.RFC3339)
	type mailNotice struct {
		id    int64
		title string
		body  string
	}
	mailNotices := []mailNotice{}
	rows, err := n.db.Query(`SELECT m.id, m.subject, m.from_name, m.from_addr, m.snippet
		FROM mail_messages m WHERE m.seen = 0 AND m.deleted_at IS NULL AND m.date > ?
		ORDER BY m.date DESC LIMIT 20`, cutoff)
	if err == nil {
		for rows.Next() {
			var id int64
			var subject, fromName, fromAddr, snippet sql.NullString
			if rows.Scan(&id, &subject, &fromName, &fromAddr, &snippet) == nil {
				mailNotices = append(mailNotices, mailNotice{
					id:    id,
					title: firstNonEmpty(subject.String, "(无主题)"),
					body:  firstNonEmpty(fromName.String, fromAddr.String, snippet.String),
				})
			}
		}
		_ = rows.Close()
	}
	for _, notice := range mailNotices {
		if n.Add("mail", "新邮件："+notice.title, notice.body,
			map[string]any{"view": "mail", "mailMessageId": notice.id}, "mail:"+int64String(notice.id), budget > 0) {
			budget--
		}
	}

	ddlCutoff := now.AddDate(0, 0, 7).Format(time.RFC3339)
	type ddlNotice struct {
		id, name, dueAt, courseName string
	}
	ddlNotices := []ddlNotice{}
	rows, err = n.db.Query(`SELECT s.id, s.name, s.due_at, c.name FROM canvas_sources s
		JOIN canvas_courses c ON c.id = s.course_id
		WHERE s.kind = 'assignment' AND s.due_at IS NOT NULL AND s.due_at > ? AND s.due_at < ? AND c.archived = 0
		ORDER BY s.due_at ASC LIMIT 30`, now.Format(time.RFC3339), ddlCutoff)
	if err == nil {
		for rows.Next() {
			var item ddlNotice
			if rows.Scan(&item.id, &item.name, &item.dueAt, &item.courseName) == nil {
				ddlNotices = append(ddlNotices, item)
			}
		}
		_ = rows.Close()
	}
	for _, notice := range ddlNotices {
		if n.Add("ddl", "DDL 临近："+notice.name, notice.courseName+" · "+notice.dueAt,
			map[string]any{"view": "canvas", "canvasSourceId": notice.id}, "ddl:"+notice.id, budget > 0) {
			budget--
		}
	}

	todoCutoff := now.Add(24 * time.Hour).Format(time.RFC3339)
	type todoNotice struct {
		id, title, dueAt string
		courseName       sql.NullString
	}
	todoNotices := []todoNotice{}
	rows, err = n.db.Query(`SELECT id, title, due_at, course_name FROM todo_items
		WHERE done = 0 AND due_at IS NOT NULL AND due_at > ? AND due_at <= ?
		ORDER BY due_at ASC LIMIT 30`, now.Format(time.RFC3339), todoCutoff)
	if err == nil {
		for rows.Next() {
			var item todoNotice
			if rows.Scan(&item.id, &item.title, &item.dueAt, &item.courseName) == nil {
				todoNotices = append(todoNotices, item)
			}
		}
		_ = rows.Close()
	}
	for _, notice := range todoNotices {
		body := firstNonEmpty(notice.courseName.String, notice.dueAt)
		if n.Add("todo", "待办即将到期："+notice.title, body,
			map[string]any{"view": "todo", "todoId": notice.id}, "todo:"+notice.id+":24h", budget > 0) {
			budget--
		}
	}
	n.initialized = true
}

func (n *Notifications) RefreshShuiyuan() {
	if n.shuiyuan == nil || !n.shuiyuan.State().Connected {
		return
	}
	items, err := n.shuiyuan.Notifications()
	if err != nil {
		return
	}
	budget := 3
	if !n.initialized {
		budget = 0
	}
	for _, item := range items {
		if item.Read {
			continue
		}
		title := firstNonEmpty(stringValue(item.FancyTitle), "水源新通知")
		body := firstNonEmpty(stringValue(item.DisplayUsername), "")
		route := map[string]any{"view": "shuiyuan"}
		if item.TopicID != nil {
			route["shuiyuanTopicId"] = *item.TopicID
		}
		if item.PostNumber != nil {
			route["shuiyuanPostNumber"] = *item.PostNumber
		}
		if n.Add("shuiyuan", title, body, route, "shuiyuan:"+int64String(item.ID), budget > 0) {
			budget--
		}
	}
}

func stringValue(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func int64String(value int64) string {
	return strconvFormatInt(value)
}

func strconvFormatInt(value int64) string {
	if value == 0 {
		return "0"
	}
	negative := value < 0
	if negative {
		value = -value
	}
	buffer := [20]byte{}
	index := len(buffer)
	for value > 0 {
		index--
		buffer[index] = byte('0' + value%10)
		value /= 10
	}
	if negative {
		index--
		buffer[index] = '-'
	}
	return string(buffer[index:])
}

func (n *Notifications) StartBackground(ctx context.Context) {
	go func() {
		// 启动窗口内先让首次同步发现新消息，汇总后再弹一条通知。
		go func() {
			timer := time.NewTimer(20 * time.Second)
			defer timer.Stop()
			select {
			case <-ctx.Done():
				return
			case <-timer.C:
			}
			// 再刷一次本地通知，接住启动后才同步完成的邮件。
			n.Refresh()
			n.MarkStartupSourceDone()
			n.waitStartupSources(ctx, 30*time.Second)
			n.flushStartup()
		}()
		time.Sleep(4 * time.Second)
		n.Refresh()
		n.RefreshShuiyuan()
		ticker := time.NewTicker(time.Minute)
		defer ticker.Stop()
		shuiyuanTicker := time.NewTicker(5 * time.Minute)
		defer shuiyuanTicker.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-ticker.C:
				n.Refresh()
			case <-shuiyuanTicker.C:
				n.RefreshShuiyuan()
			}
		}
	}()
}

func (n *Notifications) Register(router *Router) {
	router.Handle("notifications.list", func([]json.RawMessage) (any, error) {
		return n.List()
	})
	router.Handle("notifications.unread", func([]json.RawMessage) (any, error) {
		return map[string]any{"unread": n.Unread()}, nil
	})
	router.Handle("notifications.refresh", func([]json.RawMessage) (any, error) {
		n.Refresh()
		return n.List()
	})
	router.Handle("notifications.markRead", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		return true, n.MarkRead(id)
	})
	router.Handle("notifications.markAllRead", func([]json.RawMessage) (any, error) {
		return true, n.MarkAllRead()
	})
	router.Handle("notifications.snooze", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		until, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return true, n.Snooze(id, until)
	})
	router.Handle("notifications.delete", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		_, err = n.db.Exec(`DELETE FROM notifications WHERE id = ?`, id)
		return true, err
	})
}
