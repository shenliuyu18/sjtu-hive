package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// 第二轮探测：在每个模块里再往下走一层（列表→详情），仍然只读。
//
//	HIVE_LIVE=1 go test ./internal/app -run TestLiveCampusDeep -v
func TestLiveCampusDeep(t *testing.T) {
	if os.Getenv("HIVE_LIVE") != "1" {
		t.Skip("设置 HIVE_LIVE=1 才会访问真实校园服务")
	}
	db, paths, secrets, media, campus := liveSetup(t)
	defer db.Close()
	defer media.Stop()
	emit := func(string, any) {}

	type outcome struct {
		name   string
		status string
		detail string
	}
	var results []outcome

	check := func(name string, fn func(context.Context) (string, error)) {
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		detail, err := fn(ctx)
		if err != nil {
			results = append(results, outcome{name, "✗ 失败", err.Error()})
			return
		}
		results = append(results, outcome{name, "✓ 正常", detail})
	}

	check("邮箱：文件夹 → 邮件列表", func(ctx context.Context) (string, error) {
		mail := NewMail(db, paths, secrets, media, emit)
		folders, err := mail.ListFolders()
		if err != nil {
			return "", err
		}
		if len(folders) == 0 {
			return "", fmt.Errorf("没有任何文件夹")
		}
		target := folders[0]
		for _, folder := range folders {
			if folder.Name == "INBOX" || folder.Name == "收件箱" {
				target = folder
				break
			}
		}
		messages, err := mail.ListMessages(target.ID, "", 5)
		if err != nil {
			return "", fmt.Errorf("读取「%s」失败：%w", target.Name, err)
		}
		return fmt.Sprintf("「%s」取到 %d 封", target.Name, len(messages)), nil
	})

	check("课堂视频：课程 → 录像列表", func(ctx context.Context) (string, error) {
		classroom := NewClassroom(db, campus, media, emit)
		courses, err := classroom.ListCourses(ctx)
		if err != nil {
			return "", err
		}
		if len(courses) == 0 {
			return "", fmt.Errorf("没有可用课程")
		}
		raw, err := json.Marshal(courses[0])
		if err != nil {
			return "", err
		}
		var course map[string]any
		_ = json.Unmarshal(raw, &course)
		id, _ := course["id"].(float64)
		videos, err := classroom.ListVideos(ctx, int64(id))
		if err != nil {
			return "", err
		}
		return fmt.Sprintf("课程 %v 有 %d 个录像", course["name"], len(videos)), nil
	})

	check("云盘：根目录 → 子目录", func(ctx context.Context) (string, error) {
		cloudPan := NewCloudPan(db, secrets, campus, media, emit)
		root, err := cloudPan.List(ctx, "", "")
		if err != nil {
			return "", err
		}
		raw, _ := json.Marshal(root)
		var list map[string]any
		_ = json.Unmarshal(raw, &list)
		files, _ := list["files"].([]any)
		if len(files) == 0 {
			return "根目录为空", nil
		}
		first, _ := files[0].(map[string]any)
		path, _ := first["path"].(string)
		if path == "" {
			return fmt.Sprintf("根目录 %d 项（首项无路径）", len(files)), nil
		}
		inner, err := cloudPan.List(ctx, path, "")
		if err != nil {
			return "", fmt.Errorf("进入「%s」失败：%w", path, err)
		}
		innerRaw, _ := json.Marshal(inner)
		var innerList map[string]any
		_ = json.Unmarshal(innerRaw, &innerList)
		innerFiles, _ := innerList["files"].([]any)
		return fmt.Sprintf("根 %d 项；「%s」下 %d 项", len(files), path, len(innerFiles)), nil
	})

	check("水源：通知 / 收藏 / 计数", func(ctx context.Context) (string, error) {
		shuiyuan := NewShuiyuan(secrets, media, campus, emit)
		notifications, err := shuiyuan.Notifications()
		if err != nil {
			return "", fmt.Errorf("通知读取失败：%w", err)
		}
		bookmarks, err := shuiyuan.Bookmarks()
		if err != nil {
			return "", fmt.Errorf("收藏读取失败：%w", err)
		}
		counts, err := shuiyuan.Counts()
		if err != nil {
			return "", fmt.Errorf("未读计数读取失败：%w", err)
		}
		return fmt.Sprintf("通知 %d 条，收藏 %d 条，计数 %+v", len(notifications), len(bookmarks), counts), nil
	})

	check("传承交大：会话恢复 → 横幅 → 搜索", func(ctx context.Context) (string, error) {
		share := NewShare(secrets, campus)
		state := share.State()
		recovered := false
		if !state.LoggedIn {
			// 应用打开页面时就是这个动作：拿 jAccount 静默换一次会话。
			if _, err := share.LoginSilent(ctx); err != nil {
				return "", fmt.Errorf("未登录且静默恢复失败：%w", err)
			}
			recovered = true
		}
		banners, err := share.Banners(ctx)
		if err != nil {
			return "", fmt.Errorf("横幅读取失败：%w", err)
		}
		courses, err := share.SearchCourses(ctx, "高等数学")
		if err != nil {
			return "", fmt.Errorf("课程搜索失败：%w", err)
		}
		note := ""
		if recovered {
			note = "（本次由静默登录恢复）"
		}
		return fmt.Sprintf("%s横幅 %d 字节，搜索「高等数学」返回 %d 字节", note, len(banners), len(courses)), nil
	})

	check("选课社区：热门 → 详情 → 评价", func(ctx context.Context) (string, error) {
		course := NewCourseCommunity(secrets)
		hot, err := course.Hot(ctx, "", 3)
		if err != nil {
			return "", err
		}
		if len(hot.Items) == 0 {
			return "热门列表为空", nil
		}
		id := hot.Items[0].Course.ID
		detail, err := course.Detail(ctx, id)
		if err != nil {
			return "", fmt.Errorf("课程详情失败：%w", err)
		}
		reviews, err := course.Reviews(ctx, id, 1, 5, "", "")
		if err != nil {
			return "", fmt.Errorf("评价列表失败：%w", err)
		}
		raw, _ := json.Marshal(detail)
		var summary map[string]any
		_ = json.Unmarshal(raw, &summary)
		return fmt.Sprintf("课程 %v：详情 %d 字节，评价 %d 条", summary["name"], len(raw), len(reviews.Items)), nil
	})

	check("选课社区：我的课程", func(ctx context.Context) (string, error) {
		course := NewCourseCommunity(secrets)
		enrolled, err := course.Enrolled(ctx)
		if err != nil {
			return "", fmt.Errorf("选课记录失败：%w", err)
		}
		followed, err := course.Followed(ctx, 1, 10)
		if err != nil {
			return "", fmt.Errorf("关注列表失败：%w", err)
		}
		return fmt.Sprintf("选课记录 %d 条，关注 %d 条", len(enrolled), len(followed.Items)), nil
	})

	check("日程：今日课程与本地日程", func(ctx context.Context) (string, error) {
		timetable := NewTimetable(db, emit)
		day := time.Now().Truncate(24 * time.Hour)
		result, err := timetable.Query(
			day.Format(time.RFC3339),
			day.Add(24*time.Hour).Format(time.RFC3339),
			"",
		)
		if err != nil {
			return "", err
		}
		items, _ := result["items"].([]map[string]any)
		titles := make([]string, 0, len(items))
		for _, item := range items {
			titles = append(titles, fmt.Sprint(item["title"]))
		}
		return fmt.Sprintf("%d 条：%s", len(items), strings.Join(titles, "、")), nil
	})

	t.Log("\n检查项                          结果     详情")
	t.Log("----------------------------------------------------------------")
	for _, item := range results {
		t.Logf("%-30s %-7s %s", item.name, item.status, item.detail)
	}
	for _, item := range results {
		if item.status != "✓ 正常" {
			t.Errorf("%s：%s", item.name, item.detail)
		}
	}
}

// liveSetup 复用与 Startup 相同的初始化路径，但不启动 Wails 与后台轮询。
func liveSetup(t *testing.T) (*sql.DB, Paths, *SecretStore, *MediaServer, *CampusSession) {
	t.Helper()
	paths, err := LoadPaths()
	if err != nil {
		t.Fatalf("读取数据目录失败：%v", err)
	}
	db, err := OpenStore(filepath.Join(paths.DataDir, "sjtu.db"))
	if err != nil {
		t.Fatalf("打开数据库失败：%v", err)
	}
	secrets, err := NewSecretStore(paths.SecretsDir)
	if err != nil {
		t.Fatalf("打开凭据库失败：%v", err)
	}
	media, err := NewMediaServer()
	if err != nil {
		t.Fatalf("启动媒体服务失败：%v", err)
	}
	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelError}))
	return db, paths, secrets, media, NewCampusSession(secrets, func(string, any) {}, logger)
}
