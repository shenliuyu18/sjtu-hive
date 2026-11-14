package app

import (
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type SearchItem struct {
	ID       string         `json:"id"`
	Title    string         `json:"title"`
	Subtitle string         `json:"subtitle,omitempty"`
	Route    map[string]any `json:"route"`
}

type SearchGroup struct {
	Kind  string       `json:"kind"`
	Label string       `json:"label"`
	Items []SearchItem `json:"items"`
}

type SearchResponse struct {
	Groups []SearchGroup `json:"groups"`
}

type Search struct {
	db *sql.DB
}

func NewSearch(db *sql.DB) *Search {
	return &Search{db: db}
}

func escapeLike(value string) string {
	return strings.NewReplacer("\\", "\\\\", "%", "\\%", "_", "\\_").Replace(value)
}

func canvasKindLabel(kind string) string {
	switch kind {
	case "file":
		return "文件"
	case "folder":
		return "文件夹"
	case "assignment":
		return "作业"
	case "page":
		return "页面"
	case "discussion":
		return "讨论"
	case "module":
		return "模块"
	case "link":
		return "链接"
	default:
		return kind
	}
}

func (s *Search) Query(query string) (SearchResponse, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return SearchResponse{Groups: []SearchGroup{}}, nil
	}
	like := "%" + escapeLike(query) + "%"
	groups := []SearchGroup{}

	courses := []SearchItem{}
	rows, err := s.db.Query(`SELECT id, name, term, code FROM canvas_courses
		WHERE name LIKE ? ESCAPE '\' OR code LIKE ? ESCAPE '\' ORDER BY name LIMIT 10`, like, like)
	if err == nil {
		for rows.Next() {
			var id int64
			var name, term, code string
			if rows.Scan(&id, &name, &term, &code) == nil {
				courses = append(courses, SearchItem{
					ID:       "course-" + name,
					Title:    name,
					Subtitle: firstNonEmpty(term, code),
					Route:    map[string]any{"view": "canvas", "canvasCourseId": id},
				})
			}
		}
		_ = rows.Close()
	}
	if len(courses) > 0 {
		groups = append(groups, SearchGroup{Kind: "course", Label: "课程", Items: courses})
	}

	sources := []SearchItem{}
	rows, err = s.db.Query(`SELECT s.id, s.name, s.kind, s.course_id, c.name
		FROM canvas_sources s JOIN canvas_courses c ON c.id = s.course_id
		WHERE s.name LIKE ? ESCAPE '\' ORDER BY s.kind, s.name LIMIT 14`, like)
	if err == nil {
		for rows.Next() {
			var id, name, kind, courseName string
			var courseID int64
			if rows.Scan(&id, &name, &kind, &courseID, &courseName) == nil {
				sources = append(sources, SearchItem{
					ID:       id,
					Title:    name,
					Subtitle: courseName + " · " + canvasKindLabel(kind),
					Route:    map[string]any{"view": "canvas", "canvasSourceId": id, "canvasCourseId": courseID},
				})
			}
		}
		_ = rows.Close()
	}
	if len(sources) > 0 {
		groups = append(groups, SearchGroup{Kind: "source", Label: "课程资料", Items: sources})
	}

	messages := []SearchItem{}
	rows, err = s.db.Query(`SELECT m.id, m.subject, m.from_name, m.from_addr, f.path
		FROM mail_messages m JOIN mail_folders f ON f.id = m.folder_id
		WHERE m.deleted_at IS NULL AND (m.subject LIKE ? ESCAPE '\' OR m.from_name LIKE ? ESCAPE '\' OR m.from_addr LIKE ? ESCAPE '\' OR m.snippet LIKE ? ESCAPE '\')
		ORDER BY m.date DESC LIMIT 14`, like, like, like, like)
	if err == nil {
		for rows.Next() {
			var id int64
			var subject, fromName, fromAddr, folderPath sql.NullString
			if rows.Scan(&id, &subject, &fromName, &fromAddr, &folderPath) == nil {
				title := subject.String
				if title == "" {
					title = "(无主题)"
				}
				messages = append(messages, SearchItem{
					ID:       "mail-" + fromName.String,
					Title:    title,
					Subtitle: firstNonEmpty(fromName.String, fromAddr.String),
					Route:    map[string]any{"view": "mail", "mailMessageId": id, "mailFolder": folderPath.String},
				})
			}
		}
		_ = rows.Close()
	}
	if len(messages) > 0 {
		groups = append(groups, SearchGroup{Kind: "mail", Label: "邮件", Items: messages})
	}

	timetable := []SearchItem{}
	rows, err = s.db.Query(`SELECT id, title, start_at, location FROM school_events
		WHERE calendar_name = '课程' AND (title LIKE ? ESCAPE '\' OR location LIKE ? ESCAPE '\')
		ORDER BY start_at DESC LIMIT 12`, like, like)
	if err == nil {
		for rows.Next() {
			var id string
			var title, startAt string
			var location sql.NullString
			if rows.Scan(&id, &title, &startAt, &location) == nil {
				subtitle := location.String
				if at, parseErr := time.Parse(time.RFC3339, startAt); parseErr == nil {
					stamp := at.Local().Format("01-02 15:04")
					if subtitle == "" {
						subtitle = stamp
					} else {
						subtitle += " · " + stamp
					}
				}
				timetable = append(timetable, SearchItem{
					ID:       "course-" + id,
					Title:    title,
					Subtitle: subtitle,
					Route:    map[string]any{"view": "timetable", "timetableDate": startAt},
				})
			}
		}
		_ = rows.Close()
	}
	rows, err = s.db.Query(`SELECT id, title, start_at, location FROM calendar_events
		WHERE title LIKE ? ESCAPE '\' OR location LIKE ? ESCAPE '\' ORDER BY start_at DESC LIMIT 8`, like, like)
	if err == nil {
		for rows.Next() {
			var id int64
			var title, startAt string
			var location sql.NullString
			if rows.Scan(&id, &title, &startAt, &location) == nil {
				timetable = append(timetable, SearchItem{
					ID:       "event-" + title,
					Title:    title,
					Subtitle: firstNonEmpty(location.String, startAt),
					Route:    map[string]any{"view": "timetable", "timetableDate": startAt},
				})
			}
		}
		_ = rows.Close()
	}
	if len(timetable) > 0 {
		groups = append(groups, SearchGroup{Kind: "timetable", Label: "课表与日程", Items: timetable})
	}

	todos := []SearchItem{}
	rows, err = s.db.Query(`SELECT id, title, course_name, due_at FROM todo_items
		WHERE title LIKE ? ESCAPE '\' OR notes LIKE ? ESCAPE '\' ORDER BY done ASC, due_at ASC LIMIT 14`, like, like)
	if err == nil {
		for rows.Next() {
			var id, title string
			var courseName, dueAt sql.NullString
			if rows.Scan(&id, &title, &courseName, &dueAt) == nil {
				todos = append(todos, SearchItem{
					ID:       id,
					Title:    title,
					Subtitle: firstNonEmpty(courseName.String, dueAt.String),
					Route:    map[string]any{"view": "todo", "todoId": id},
				})
			}
		}
		_ = rows.Close()
	}
	if len(todos) > 0 {
		groups = append(groups, SearchGroup{Kind: "todo", Label: "TODO", Items: todos})
	}

	files := s.searchFiles(query, like)
	if len(files) > 0 {
		groups = append(groups, SearchGroup{Kind: "file", Label: "文件", Items: files})
	}

	settings := searchSettings(query)
	if len(settings) > 0 {
		groups = append(groups, SearchGroup{Kind: "settings", Label: "设置", Items: settings})
	}
	return SearchResponse{Groups: groups}, nil
}

func nonEmpty(values ...string) []string {
	result := []string{}
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			result = append(result, strings.TrimSpace(value))
		}
	}
	return result
}

func (s *Search) searchFiles(query, like string) []SearchItem {
	rows, err := s.db.Query(`SELECT id, name, path FROM projects ORDER BY COALESCE(last_opened_at, created_at) DESC`)
	if err != nil {
		return []SearchItem{}
	}
	type projectRow struct {
		id, name, path string
	}
	projects := []projectRow{}
	for rows.Next() {
		var project projectRow
		if rows.Scan(&project.id, &project.name, &project.path) == nil {
			projects = append(projects, project)
		}
	}
	_ = rows.Close()
	lowerQuery := strings.ToLower(query)
	result := []SearchItem{}
	deadline := time.Now().Add(1500 * time.Millisecond)
	for _, project := range projects {
		if strings.Contains(strings.ToLower(project.name), lowerQuery) {
			result = append(result, SearchItem{
				ID:       "project-" + project.id,
				Title:    project.name,
				Subtitle: project.path,
				Route:    map[string]any{"view": "workspace", "workspaceProjectId": project.id},
			})
		}
		_ = filepath.WalkDir(project.path, func(pathValue string, entry os.DirEntry, walkErr error) error {
			if walkErr != nil || entry == nil || time.Now().After(deadline) || len(result) >= 20 {
				return filepath.SkipDir
			}
			if entry.IsDir() {
				if pathValue != project.path && (hiddenNames[entry.Name()] || strings.Count(filepath.ToSlash(pathValue), "/")-strings.Count(filepath.ToSlash(project.path), "/") >= 3) {
					return filepath.SkipDir
				}
				return nil
			}
			if strings.Contains(strings.ToLower(entry.Name()), lowerQuery) {
				result = append(result, SearchItem{
					ID:       pathValue,
					Title:    entry.Name(),
					Subtitle: project.name + " · " + strings.TrimPrefix(pathValue, project.path),
					Route:    map[string]any{"view": "workspace", "workspacePath": pathValue, "workspaceProjectId": project.id},
				})
			}
			return nil
		})
		if len(result) >= 20 || time.Now().After(deadline) {
			break
		}
	}
	return result
}

func searchSettings(query string) []SearchItem {
	entries := []struct {
		id       string
		label    string
		section  string
		keywords string
	}{
		{"canvas", "Canvas 账户", "accounts", "canvas token 课程"},
		{"mail", "邮箱账户", "accounts", "邮箱 mail imap smtp 密码"},
		{"campus", "教学网登录", "accounts", "教学网 课表 登录 cookie"},
		{"shuiyuan", "水源账户", "accounts", "水源 社区 discourse"},
		{"theme", "主题与外观", "appearance", "主题 深色 浅色 字号 缩放 外观"},
		{"login", "开机自动启动", "general", "开机 启动 登录"},
		{"nav", "导航入口", "general", "侧栏 导航 隐藏"},
		{"cache", "缓存清理", "storage", "缓存 清理 空间"},
		{"about", "关于与更新", "about", "版本 更新 条款 隐私"},
	}
	text := strings.ToLower(query)
	result := []SearchItem{}
	for _, entry := range entries {
		haystack := strings.ToLower(entry.label + " " + entry.keywords)
		if strings.Contains(haystack, text) {
			result = append(result, SearchItem{
				ID: entry.id, Title: entry.label, Subtitle: "设置",
				Route: map[string]any{"view": "settings", "settingsSection": entry.section},
			})
		}
	}
	return result
}

func (s *Search) Register(router *Router) {
	router.Handle("search.query", func(args []json.RawMessage) (any, error) {
		query, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return s.Query(query)
	})
}
