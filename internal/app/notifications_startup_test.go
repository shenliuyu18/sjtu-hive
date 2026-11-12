package app

import (
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestFormatStartupNotification(t *testing.T) {
	title, body := formatStartupNotification([]startupNotification{
		{Kind: "portal", Title: "活动已报名通知"},
		{Kind: "portal", Title: "日程提醒"},
		{Kind: "mail", Title: "关于选课的通知"},
		{Kind: "ddl", Title: "作业截止"},
	})
	if title != "你有 4 条新消息" {
		t.Fatalf("unexpected title: %q", title)
	}
	for _, want := range []string{"交我办 2 条", "邮件 1 封", "DDL 1 条", "活动已报名通知"} {
		if !strings.Contains(body, want) {
			t.Fatalf("body %q missing %q", body, want)
		}
	}
}

func TestFlushStartupAggregatesNewNotificationsOnce(t *testing.T) {
	db, err := OpenStore(filepath.Join(t.TempDir(), "notifications.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	n := &Notifications{
		db:           db,
		startupSince: Now(),
	}
	n.startupActive.Store(true)
	toasts := []string{}
	n.toaster = func(title, body string) error {
		toasts = append(toasts, title+"|"+body)
		return nil
	}
	insert := func(kind, title, createdAt string) {
		if _, err := db.Exec(`INSERT INTO notifications (kind, title, body, route, dedupe_key, created_at)
			VALUES (?, ?, '', '{}', NULL, ?)`, kind, title, createdAt); err != nil {
			t.Fatal(err)
		}
	}
	insert("portal", "活动通知", Now())
	insert("portal", "日程提醒", Now())
	insert("mail", "关于选课的通知", Now())
	insert("portal", "很久以前的消息", time.Now().AddDate(0, 0, -3).Format(time.RFC3339))

	n.flushStartup()
	if len(toasts) != 1 {
		t.Fatalf("expected one aggregated toast, got %d: %#v", len(toasts), toasts)
	}
	if !strings.HasPrefix(toasts[0], "你有 3 条新消息|") {
		t.Fatalf("unexpected toast: %q", toasts[0])
	}
	if !strings.Contains(toasts[0], "交我办 2 条") || !strings.Contains(toasts[0], "邮件 1 封") {
		t.Fatalf("unexpected summary: %q", toasts[0])
	}

	n.flushStartup()
	if len(toasts) != 1 {
		t.Fatalf("startup flush should only toast once, got %d", len(toasts))
	}
	if n.startupActive.Load() {
		t.Fatal("startup window should be closed after flush")
	}
}

func TestStartupSourcesGate(t *testing.T) {
	n := &Notifications{startupDone: make(chan struct{})}
	n.startupRemaining.Store(2)
	n.MarkStartupSourceDone()
	select {
	case <-n.startupDone:
		t.Fatal("startup gate closed before all sources finished")
	default:
	}
	n.MarkStartupSourceDone()
	select {
	case <-n.startupDone:
	default:
		t.Fatal("startup gate should close after both sources finished")
	}
}
