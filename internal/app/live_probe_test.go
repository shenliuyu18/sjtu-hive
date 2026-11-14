package app

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 真实校园服务的只读探测。默认跳过，避免 CI 依赖校外网络与个人凭据。
//
//	HIVE_LIVE=1 go test ./internal/app -run TestLiveCampus -v
//
// 只做读取与状态查询，不写数据、不发消息、不改远端状态。
func TestLiveCampus(t *testing.T) {
	if os.Getenv("HIVE_LIVE") != "1" {
		t.Skip("设置 HIVE_LIVE=1 才会访问真实校园服务")
	}

	paths, err := LoadPaths()
	if err != nil {
		t.Fatalf("读取数据目录失败：%v", err)
	}
	db, err := OpenStore(filepath.Join(paths.DataDir, "sjtu.db"))
	if err != nil {
		t.Fatalf("打开数据库失败：%v", err)
	}
	defer db.Close()
	secrets, err := NewSecretStore(paths.SecretsDir)
	if err != nil {
		t.Fatalf("打开凭据库失败：%v", err)
	}
	media, err := NewMediaServer()
	if err != nil {
		t.Fatalf("启动媒体服务失败：%v", err)
	}
	defer media.Stop()

	emit := func(string, any) {}
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError}))
	campus := NewCampusSession(secrets, emit, logger)

	type outcome struct {
		name   string
		status string
		detail string
		took   time.Duration
	}
	var results []outcome

	check := func(name string, fn func(context.Context) (string, error)) {
		ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
		defer cancel()
		start := time.Now()
		detail, err := fn(ctx)
		took := time.Since(start).Round(time.Millisecond)
		if err != nil {
			results = append(results, outcome{name, "✗ 失败", err.Error(), took})
			return
		}
		if detail == "" {
			detail = "ok"
		}
		results = append(results, outcome{name, "✓ 正常", detail, took})
	}

	summarize := func(value any) string {
		encoded, err := json.Marshal(value)
		if err != nil {
			text := fmt.Sprintf("%+v", value)
			if len(text) > 220 {
				text = text[:220] + "…"
			}
			return text
		}
		text := string(encoded)
		if len(text) > 220 {
			text = text[:220] + "…"
		}
		return text
	}

	check("jAccount 会话", func(ctx context.Context) (string, error) {
		result, err := campus.Test(ctx)
		if err != nil {
			return "", err
		}
		return summarize(result), nil
	})

	check("Canvas 资料", func(ctx context.Context) (string, error) {
		canvas := NewCanvas(db, paths, secrets, media, campus, emit)
		if !canvas.Connected() {
			return "", fmt.Errorf("未连接：Canvas 既没有可用令牌，jAccount 也未换取到 Canvas 会话")
		}
		state, err := canvas.State()
		if err != nil {
			return "", err
		}
		return summarize(state), nil
	})

	check("校园邮箱", func(ctx context.Context) (string, error) {
		mail := NewMail(db, paths, secrets, media, emit)
		folders, err := mail.ListFolders()
		if err != nil {
			return "", err
		}
		unread := 0
		for _, folder := range folders {
			if folder.Unread != nil {
				unread += int(*folder.Unread)
			}
		}
		return fmt.Sprintf("%d 个文件夹，未读 %d", len(folders), unread), nil
	})

	check("日程与课程", func(ctx context.Context) (string, error) {
		timetable := NewTimetable(db, emit)
		start := time.Now().Truncate(24 * time.Hour)
		result, err := timetable.Query(start.Format(time.RFC3339), start.Add(24*time.Hour).Format(time.RFC3339), "")
		if err != nil {
			return "", err
		}
		items, _ := result["items"].([]map[string]any)
		courses := 0
		for _, item := range items {
			if item["source"] == "course" {
				courses++
			}
		}
		return fmt.Sprintf("今日课程 %d 门，今日条目 %d 条", courses, len(items)), nil
	})

	check("交我办日历", func(ctx context.Context) (string, error) {
		calendar := NewSchoolCalendar(db, campus, emit)
		return summarize(calendar.State()), nil
	})

	check("课堂视频", func(ctx context.Context) (string, error) {
		classroom := NewClassroom(db, campus, media, emit)
		courses, err := classroom.ListCourses(ctx)
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("%d 门课程有录像", len(courses)), nil
	})

	check("交大云盘", func(ctx context.Context) (string, error) {
		cloudPan := NewCloudPan(db, secrets, campus, media, emit)
		list, err := cloudPan.List(ctx, "", "")
		if err != nil {
			return "", err
		}
		return summarize(list), nil
	})

	check("水源社区", func(ctx context.Context) (string, error) {
		shuiyuan := NewShuiyuan(secrets, media, campus, emit)
		return summarize(shuiyuan.State()), nil
	})

	check("选课社区", func(ctx context.Context) (string, error) {
		course := NewCourseCommunity(secrets)
		state := summarize(course.State())
		hot, err := course.Hot(ctx, "", 3)
		if err != nil {
			return state, fmt.Errorf("%s；热门课程读取失败：%w", state, err)
		}
		return fmt.Sprintf("%s；热门 %d 条", state, len(hot.Items)), nil
	})

	check("传承交大", func(ctx context.Context) (string, error) {
		share := NewShare(secrets, campus)
		return summarize(share.State()), nil
	})

	t.Log("\n模块                  结果     耗时      详情")
	t.Log("--------------------------------------------------------------")
	for _, item := range results {
		t.Logf("%-20s %-7s %-8s %s", item.name, item.status, item.took, item.detail)
	}
	for _, item := range results {
		if item.status != "✓ 正常" {
			t.Errorf("%s：%s", item.name, item.detail)
		}
	}
}
