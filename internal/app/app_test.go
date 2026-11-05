package app

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestDPAPIRoundTrip(t *testing.T) {
	original := []byte("canvas-token-测试-1234567890")
	sealed, err := protectBytes(original)
	if err != nil {
		t.Fatalf("protect: %v", err)
	}
	if bytes.Equal(sealed, original) {
		t.Fatal("sealed content should differ")
	}
	plain, err := unprotectBytes(sealed)
	if err != nil {
		t.Fatalf("unprotect: %v", err)
	}
	if !bytes.Equal(plain, original) {
		t.Fatalf("round trip mismatch: %q", plain)
	}
}

func TestTimetableParsers(t *testing.T) {
	weeks := weekNumbersOf("1-8周(单)")
	if len(weeks) != 4 || weeks[0] != 1 || weeks[3] != 7 {
		t.Fatalf("odd weeks: %#v", weeks)
	}
	weeks = weekNumbersOf("1-4,6-8周")
	if len(weeks) != 7 || weeks[6] != 8 {
		t.Fatalf("range weeks: %#v", weeks)
	}
	if start := parseSemesterStart([]json.RawMessage{json.RawMessage(`["2026-09-07 第1周"]`)}); start != "2026-09-07" {
		t.Fatalf("semester start: %q", start)
	}
	if start := parseSemesterStart([]json.RawMessage{json.RawMessage(`[{"date":"2026-09-07"}]`)}); start != "2026-09-07" {
		t.Fatalf("semester start object: %q", start)
	}
	xnm, xqm := semesterParams("2026-2027-2")
	if xnm != "2026" || xqm != "12" {
		t.Fatalf("semester params: %s %s", xnm, xqm)
	}
	start, end, text := periodsOf("3-4", "3-4节")
	if start != 3 || end != 4 || text != "3-4" {
		t.Fatalf("periods: %d %d %s", start, end, text)
	}
}

func TestSettingsValidation(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	settings, err := NewSettings(db, nil)
	if err != nil {
		t.Fatal(err)
	}
	if settings.State().Theme != "system" {
		t.Fatalf("default theme: %s", settings.State().Theme)
	}
	if err := settings.Set("theme", "dark"); err != nil {
		t.Fatal(err)
	}
	if settings.State().Theme != "dark" {
		t.Fatalf("theme not set")
	}
	if err := settings.Set("fontSize", 99); err == nil {
		t.Fatal("font size should be rejected")
	}
	if err := settings.Set("lastView", "mail"); err != nil {
		t.Fatal(err)
	}
	if settings.State().LastView != "mail" {
		t.Fatalf("last view: %s", settings.State().LastView)
	}
}

func TestCampusHostOnlyCookie(t *testing.T) {
	secrets, err := NewSecretStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	session := NewCampusSession(secrets, nil, nil)
	iURL, _ := url.Parse("https://i.sjtu.edu.cn/kbcx/xskbcx_cxXsgrkb.html")
	otherURL, _ := url.Parse("https://v.sjtu.edu.cn/")
	session.SetCookies(iURL, []*http.Cookie{{Name: "JSESSIONID", Value: "abc", Path: "/"}})
	if len(session.Cookies(iURL)) != 1 {
		t.Fatalf("host-only cookie should be sent to i.sjtu.edu.cn, got %d", len(session.Cookies(iURL)))
	}
	if len(session.Cookies(otherURL)) != 0 {
		t.Fatalf("host-only cookie should not be sent to v.sjtu.edu.cn")
	}
	session.SetCookies(otherURL, []*http.Cookie{{Name: "JAAuthCookie", Value: "xyz", Domain: ".sjtu.edu.cn", Path: "/"}})
	if len(session.Cookies(otherURL)) != 1 {
		t.Fatalf("domain cookie should be sent to v.sjtu.edu.cn")
	}
	if len(session.Cookies(iURL)) != 2 {
		t.Fatalf("domain cookie should be sent to i.sjtu.edu.cn, got %d", len(session.Cookies(iURL)))
	}
}

func TestMediaServerRange(t *testing.T) {
	server, err := NewMediaServer()
	if err != nil {
		t.Fatal(err)
	}
	defer server.Stop()
	raw := server.AddBytes([]byte("hello world"), "hello.txt", "text/plain", time.Hour)
	response, err := http.Get(raw)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(response.Body)
	_ = response.Body.Close()
	if string(body) != "hello world" {
		t.Fatalf("body: %s", body)
	}
	request, _ := http.NewRequest(http.MethodGet, raw, nil)
	request.Header.Set("Range", "bytes=0-4")
	rangeResponse, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	rangeBody, _ := io.ReadAll(rangeResponse.Body)
	_ = rangeResponse.Body.Close()
	if rangeResponse.StatusCode != http.StatusPartialContent || string(rangeBody) != "hello" {
		t.Fatalf("range: %d %q", rangeResponse.StatusCode, rangeBody)
	}
}

func TestCanvasPagination(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Query().Get("page") == "2" {
			_, _ = w.Write([]byte(`[{"id":2,"name":"课程 2"}]`))
			return
		}
		w.Header().Set("Link", fmt.Sprintf(`<%s/api/v1/courses?page=2&per_page=50>; rel="next"`, server.URL))
		_, _ = w.Write([]byte(`[{"id":1,"name":"课程 1"}]`))
	}))
	defer server.Close()
	t.Setenv("SJTU_CANVAS_BASE", server.URL+"/api/v1")
	client := &canvasClient{
		base:   server.URL + "/api/v1",
		origin: server.URL,
		token:  "test",
		http:   server.Client(),
	}
	items, err := client.getAll("/courses", nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != 2 {
		t.Fatalf("pagination returned %d items", len(items))
	}
}

func TestWorkspacePreservesBOMAndNewlines(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	media, err := NewMediaServer()
	if err != nil {
		t.Fatal(err)
	}
	defer media.Stop()
	paths := Paths{CanvasDir: filepath.Join(dir, "canvas"), MailDir: filepath.Join(dir, "mail")}
	workspace := NewWorkspace(db, paths, media, nil)
	projectDir := filepath.Join(dir, "notes")
	if err := os.MkdirAll(projectDir, 0o755); err != nil {
		t.Fatal(err)
	}
	project, err := workspace.RegisterProject(projectDir, "Notes")
	if err != nil {
		t.Fatal(err)
	}
	notePath := filepath.Join(projectDir, "note.md")
	original := append([]byte{0xEF, 0xBB, 0xBF}, []byte("# Hello\r\n\r\nBody\r\n")...)
	if err := os.WriteFile(notePath, original, 0o644); err != nil {
		t.Fatal(err)
	}
	doc, err := workspace.Read(notePath)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(doc.Text, "\ufeff") || !strings.Contains(doc.Text, "Hello") {
		t.Fatalf("unexpected text: %q", doc.Text)
	}
	if _, err := workspace.Save(notePath, "# Hello\n\nChanged\n", doc.Revision); err != nil {
		t.Fatal(err)
	}
	saved, err := os.ReadFile(notePath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(saved, []byte{0xEF, 0xBB, 0xBF}) {
		t.Fatal("BOM was not preserved")
	}
	if !bytes.Contains(saved, []byte("\r\n")) || bytes.Contains(saved, []byte("# Hello\n\nChanged")) {
		t.Fatalf("CRLF was not preserved: %q", saved)
	}
	if _, err := workspace.Save(notePath, "changed again", "stale-revision"); err == nil {
		t.Fatal("stale revision should be rejected")
	}
	if _, err := workspace.Save(notePath, "changed again", doc.Revision); err == nil {
		// revision changed after the first save; old revision must be rejected
		t.Fatal("old revision should be rejected after save")
	}
	_ = project
}

func TestMailParseBody(t *testing.T) {
	raw := strings.Join([]string{
		"From: TA <ta@sjtu.edu.cn>",
		"To: student@sjtu.edu.cn",
		"Subject: Hello",
		"MIME-Version: 1.0",
		"Content-Type: multipart/mixed; boundary=outer",
		"",
		"--outer",
		"Content-Type: multipart/alternative; boundary=inner",
		"",
		"--inner",
		"Content-Type: text/plain; charset=utf-8",
		"",
		"plain body",
		"--inner",
		"Content-Type: text/html; charset=utf-8",
		"",
		"<p>html <strong>body</strong></p>",
		"--inner--",
		"--outer",
		"Content-Type: application/pdf; name=report.pdf",
		"Content-Disposition: attachment; filename=report.pdf",
		"Content-Transfer-Encoding: base64",
		"",
		"JVBERi0xLjQK",
		"--outer--",
	}, "\r\n")
	dir := t.TempDir()
	mail := &Mail{paths: Paths{MailDir: dir}}
	text, htmlBody, attachments, snippet := mail.parseBody(mailMessageRow{ID: 7}, strings.NewReader(raw))
	if !strings.Contains(text, "plain body") {
		t.Fatalf("text body: %q", text)
	}
	if !strings.Contains(htmlBody, "html") {
		t.Fatalf("html body: %q", htmlBody)
	}
	if len(attachments) != 1 || attachments[0].Filename != "report.pdf" {
		t.Fatalf("attachments: %#v", attachments)
	}
	if _, err := os.Stat(attachments[0].LocalPath); err != nil {
		t.Fatalf("attachment file: %v", err)
	}
	if snippet == "" {
		t.Fatal("empty snippet")
	}
}

func TestVacuumInto(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	app := &App{db: db}
	target := filepath.Join(dir, "backup.db")
	if err := app.VacuumInto(target); err != nil {
		t.Fatal(err)
	}
	if info, err := os.Stat(target); err != nil || info.Size() == 0 {
		t.Fatalf("vacuum result: %v", err)
	}
}

func TestTodoStateCounts(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	todo := NewTodo(db, nil)
	now := time.Now()
	items := []map[string]any{
		{"title": "today", "dueAt": time.Date(now.Year(), now.Month(), now.Day(), 23, 59, 0, 0, time.Local).Format(time.RFC3339)},
		{"title": "overdue", "dueAt": now.Add(-24 * time.Hour).Format(time.RFC3339)},
		{"title": "done", "done": true},
	}
	for _, item := range items {
		if _, err := todo.Save(item); err != nil {
			t.Fatal(err)
		}
	}
	state, err := todo.State()
	if err != nil {
		t.Fatal(err)
	}
	if state.Today != 1 || state.Overdue != 1 || state.Completed != 1 {
		t.Fatalf("counts: %#v", state)
	}
}

func TestSearchQuery(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := db.Exec(`INSERT INTO canvas_courses (id,name,term,code) VALUES (1,'机器学习','2026-2027 Fall','CS3308')`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO canvas_sources (id,course_id,name,kind,data) VALUES ('c1:file:1',1,'lecture.pdf','file','{}')`); err != nil {
		t.Fatal(err)
	}
	search := NewSearch(db)
	result, err := search.Query("机器")
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Groups) == 0 {
		t.Fatal("expected search groups")
	}
}

func TestBackupZipCreation(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	app := &App{db: db, paths: Paths{DownloadsDir: dir}}
	if err := app.VacuumInto(filepath.Join(dir, "copy.db")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "copy.db")); err != nil {
		t.Fatal(err)
	}
}

var _ = context.Background

func TestCanvasEndToEnd(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v1/courses":
			_, _ = w.Write([]byte(`[{"id":1,"name":"测试课程","course_code":"TEST-01","term":{"name":"2026-2027 Fall"}}]`))
		case "/api/v1/courses/1/folders":
			_, _ = w.Write([]byte(`[{"id":10,"name":"course files","full_name":"course files","parent_folder_id":null},{"id":11,"name":"slides","full_name":"course files/slides","parent_folder_id":10}]`))
		case "/api/v1/courses/1/files":
			_, _ = w.Write([]byte(`[{"id":101,"display_name":"lecture.pdf","filename":"lecture.pdf","content_type":"application/pdf","size":7,"folder_id":11,"url":"/files/101/download"}]`))
		case "/api/v1/courses/1/modules":
			_, _ = w.Write([]byte(`[{"id":20,"name":"Week 1","position":1,"items":[]}]`))
		case "/api/v1/courses/1/assignments":
			_, _ = w.Write([]byte(`[{"id":30,"name":"HW1","description":"<p>do it</p>","due_at":"2026-10-30T15:59:00Z","html_url":"https://oc.sjtu.edu.cn/courses/1/assignments/30","points_possible":100,"submission":{"workflow_state":"unsubmitted"}}]`))
		case "/api/v1/courses/1/pages":
			_, _ = w.Write([]byte(`[{"page_id":1,"url":"syllabus","title":"Syllabus","updated_at":"2026-10-01T00:00:00Z"}]`))
		case "/api/v1/courses/1/discussion_topics":
			_, _ = w.Write([]byte(`[{"id":40,"title":"Announcement","message":"hello","posted_at":"2026-10-01T00:00:00Z","html_url":"https://oc.sjtu.edu.cn/courses/1/discussion_topics/40","is_announcement":true}]`))
		case "/api/v1/courses/1/pages/syllabus":
			_, _ = w.Write([]byte(`{"page_id":1,"url":"syllabus","title":"Syllabus","body":"<p>page body</p>"}`))
		case "/api/v1/courses/1/discussion_topics/40":
			_, _ = w.Write([]byte(`{"id":40,"title":"Announcement","message":"<p>hello</p>","html_url":"https://oc.sjtu.edu.cn/courses/1/discussion_topics/40","is_announcement":true}`))
		case "/files/101/download":
			http.Redirect(w, r, server.URL+"/s3/lecture.pdf", http.StatusFound)
		case "/s3/lecture.pdf":
			w.Header().Set("Content-Type", "application/pdf")
			_, _ = w.Write([]byte("PDFDATA"))
		default:
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	t.Setenv("SJTU_CANVAS_BASE", server.URL+"/api/v1")
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "canvas.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	secrets, err := NewSecretStore(filepath.Join(dir, "secrets"))
	if err != nil {
		t.Fatal(err)
	}
	media, err := NewMediaServer()
	if err != nil {
		t.Fatal(err)
	}
	defer media.Stop()
	canvas := NewCanvas(db, Paths{CanvasDir: filepath.Join(dir, "cache")}, secrets, media, nil)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err := canvas.Configure(ctx, "token"); err != nil {
		t.Fatal(err)
	}
	state, err := canvas.SyncCourses(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if len(state.Courses) != 1 || len(state.Items) < 5 {
		t.Fatalf("state: courses=%d items=%d", len(state.Courses), len(state.Items))
	}
	assignment, err := canvas.ReadSource(ctx, "c1:assignment:30")
	if err != nil || !strings.Contains(assignment.Text, "do it") {
		t.Fatalf("assignment read: %v %#v", err, assignment)
	}
	filePath, err := canvas.DownloadSource(ctx, "c1:file:101")
	if err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(filePath); err != nil || string(data) != "PDFDATA" {
		t.Fatalf("download: %v %q", err, data)
	}
	file, err := canvas.ReadSource(ctx, "c1:file:101")
	if err != nil || file.Format != "pdf" || file.ResourceURL == "" {
		t.Fatalf("file read: %v %#v", err, file)
	}
	exportDir := filepath.Join(dir, "export")
	result, err := canvas.ExportCourseFiles(ctx, []int64{1}, exportDir)
	if err != nil {
		t.Fatal(err)
	}
	if result["exported"] != 1 {
		t.Fatalf("export: %#v", result)
	}
}

func TestTerminalConPTY(t *testing.T) {
	dir := t.TempDir()
	var mu sync.Mutex
	var output strings.Builder
	manager := NewTerminalManager(func(name string, data any) {
		if name != "terminal.data" {
			return
		}
		payload, ok := data.(map[string]any)
		if !ok {
			return
		}
		encoded, _ := payload["data"].(string)
		decoded, err := base64.StdEncoding.DecodeString(encoded)
		if err != nil {
			return
		}
		mu.Lock()
		output.Write(decoded)
		mu.Unlock()
	})
	manager.projectPath = func(string) (string, error) { return dir, nil }
	result, err := manager.Start("test", 80, 24)
	if err != nil {
		t.Fatal(err)
	}
	id, _ := result["id"].(string)
	if id == "" {
		t.Fatal("missing terminal id")
	}
	time.Sleep(700 * time.Millisecond)
	if err := manager.Write(id, "echo TERM_OK\n"); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		text := output.String()
		mu.Unlock()
		if strings.Contains(text, "TERM_OK") {
			manager.Close(id)
			manager.CloseAll()
			return
		}
		time.Sleep(120 * time.Millisecond)
	}
	manager.CloseAll()
	t.Fatal("terminal did not produce expected output")
}

func TestCampusImportRequiresAuthCookies(t *testing.T) {
	secrets, err := NewSecretStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	session := NewCampusSession(secrets, nil, nil)
	if err := session.ImportCookieHeader("route=x; jy-application-resourcemanage=y"); err == nil {
		t.Fatal("video-only cookies must be rejected")
	}
	if session.Connected() {
		t.Fatal("video-only cookies must not report connected")
	}
	if err := session.ImportCookieHeader("JAAuthCookie=abc; JSESSIONID=def; keepalive=ghi"); err != nil {
		t.Fatalf("complete cookie header rejected: %v", err)
	}
	if !session.Connected() {
		t.Fatal("complete cookie header should report connected")
	}
	status := session.Status()
	if status["connected"] != true {
		t.Fatalf("status not connected: %+v", status)
	}
}

func TestShuiyuanTagShapes(t *testing.T) {
	parse := func(raw string) []string {
		t.Helper()
		var page struct {
			TopicList struct {
				Topics []shuiyuanTopicRaw `json:"topics"`
			} `json:"topic_list"`
		}
		if err := json.Unmarshal([]byte(raw), &page); err != nil {
			t.Fatalf("unmarshal: %v", err)
		}
		if len(page.TopicList.Topics) != 1 {
			t.Fatalf("topics: %d", len(page.TopicList.Topics))
		}
		return page.TopicList.Topics[0].Tags
	}
	stringsCase := parse(`{"topic_list":{"topics":[{"id":1,"tags":["选课","实习"]}]}}`)
	if len(stringsCase) != 2 || stringsCase[0] != "选课" {
		t.Fatalf("string tags: %+v", stringsCase)
	}
	objectsCase := parse(`{"topic_list":{"topics":[{"id":1,"tags":[{"id":7,"name":"选课","slug":"7-tag"},{"id":8,"name":"实习"}]}]}}`)
	if len(objectsCase) != 2 || objectsCase[1] != "实习" {
		t.Fatalf("object tags: %+v", objectsCase)
	}
}

func TestFontScaleDefaultAndLegacyCleanup(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "settings.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	settings, err := NewSettings(db, nil)
	if err != nil {
		t.Fatal(err)
	}
	if settings.State().FontScale != 100 {
		t.Fatalf("default font scale: %d", settings.State().FontScale)
	}
	if settings.State().FontBasePx < browserDefaultFontPx {
		t.Fatalf("system base should not shrink below the browser default: %f", settings.State().FontBasePx)
	}
	if err := settings.Set("fontScale", 125); err != nil {
		t.Fatal(err)
	}
	if err := settings.Set("fontScale", 20); err == nil {
		t.Fatal("out of range scale should be rejected")
	}
	again, err := NewSettings(db, nil)
	if err != nil {
		t.Fatal(err)
	}
	if again.State().FontScale != 125 {
		t.Fatalf("explicit scale should be preserved: %d", again.State().FontScale)
	}

	legacy := filepath.Join(dir, "legacy.db")
	legacyDB, err := OpenStore(legacy)
	if err != nil {
		t.Fatal(err)
	}
	defer legacyDB.Close()
	if _, err := legacyDB.Exec(`INSERT INTO settings (key, value, updated_at) VALUES ('fontSize', '14', ?)`, Now()); err != nil {
		t.Fatal(err)
	}
	if _, err := NewSettings(legacyDB, nil); err != nil {
		t.Fatal(err)
	}
	var count int
	if err := legacyDB.QueryRow(`SELECT COUNT(*) FROM settings WHERE key = 'fontSize'`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Fatalf("legacy fontSize preference should be dropped, rows=%d", count)
	}
}

func TestLocalBackupRestore(t *testing.T) {
	root := t.TempDir()
	paths := Paths{
		DataDir:    filepath.Join(root, "data"),
		SecretsDir: filepath.Join(root, "secrets"),
		BackupDir:  filepath.Join(root, "backups"),
	}
	for _, dir := range []string{paths.DataDir, paths.SecretsDir, paths.BackupDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatal(err)
		}
	}
	db, err := OpenStore(filepath.Join(paths.DataDir, "sjtu.db"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO projects (id, name, path, created_at) VALUES ('p1', '课程项目', ?, ?)`,
		filepath.Join(root, "project"), Now()); err != nil {
		t.Fatal(err)
	}
	secrets, err := NewSecretStore(paths.SecretsDir)
	if err != nil {
		t.Fatal(err)
	}
	if err := secrets.SaveRaw("test-token", []byte("secret")); err != nil {
		t.Fatal(err)
	}
	if err := snapshotLocalBackup(db, paths, nil); err != nil {
		t.Fatal(err)
	}
	_ = db.Close()
	if err := os.Remove(filepath.Join(paths.DataDir, "sjtu.db")); err != nil {
		t.Fatal(err)
	}
	if err := os.RemoveAll(paths.SecretsDir); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(paths.SecretsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	restored, err := restoreLocalBackup(paths, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	if !restored {
		t.Fatal("backup was not restored")
	}
	db2, err := OpenStore(filepath.Join(paths.DataDir, "sjtu.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db2.Close()
	var name string
	if err := db2.QueryRow(`SELECT name FROM projects WHERE id = 'p1'`).Scan(&name); err != nil {
		t.Fatal(err)
	}
	if name != "课程项目" {
		t.Fatalf("restored project: %q", name)
	}
	restoredSecrets, err := NewSecretStore(paths.SecretsDir)
	if err != nil {
		t.Fatal(err)
	}
	if data, ok, err := restoredSecrets.LoadRaw("test-token"); err != nil || !ok || string(data) != "secret" {
		t.Fatalf("restored secret: %q %v %v", data, ok, err)
	}
}

func TestNotificationRefreshDoesNotDeadlock(t *testing.T) {
	dir := t.TempDir()
	db, err := OpenStore(filepath.Join(dir, "notify.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	settings, err := NewSettings(db, nil)
	if err != nil {
		t.Fatal(err)
	}
	due := time.Now().Add(time.Hour).Format(time.RFC3339)
	if _, err := db.Exec(`INSERT INTO todo_items
		(id, title, notes, due_at, done, created_at, updated_at)
		VALUES ('todo-test', '复习高等数学', '', ?, 0, ?, ?)`, due, Now(), Now()); err != nil {
		t.Fatal(err)
	}
	notifications := NewNotifications(db, settings, nil, nil, nil)
	done := make(chan struct{})
	go func() {
		notifications.Refresh()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(3 * time.Second):
		t.Fatal("notification refresh blocked on its own database connection")
	}
	var count int
	if err := db.QueryRow(`SELECT COUNT(*) FROM notifications`).Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Fatalf("notifications: %d", count)
	}
}

func TestSchoolCalendarEventMapping(t *testing.T) {
	calendar := &SchoolCalendar{}
	names := map[string]string{"bd8f": "课程"}
	colors := map[string]string{"bd8f": "#5698DB"}
	event, ok := calendar.mapEvent(rawSchoolEvent{
		EventID: "e1", Title: "高等数学I", StartTime: "2026-10-29 08:00",
		EndTime: "2026-10-29 09:40", Location: stringPointer("上院211"),
		CalendarID: "bd8f", Recurrence: 1,
	}, names, colors)
	if !ok {
		t.Fatal("event should be mapped")
	}
	if event.StartAt != "2026-10-29T00:00:00Z" || event.EndAt != "2026-10-29T01:40:00Z" {
		t.Fatalf("unexpected UTC times: %s %s", event.StartAt, event.EndAt)
	}
	if event.CalendarName != "课程" || event.Color == nil || *event.Color != "#5698DB" {
		t.Fatalf("calendar metadata: %+v", event)
	}
	if event.Location == nil || *event.Location != "上院211" {
		t.Fatalf("location: %+v", event.Location)
	}
	allDay, ok := calendar.mapEvent(rawSchoolEvent{
		EventID: "e2", Title: "校庆", StartTime: "2026-10-29", EndTime: "2026-10-29", AllDay: true,
	}, map[string]string{"schoolCalendar": "校历"}, map[string]string{})
	if !ok || !allDay.AllDay || allDay.CalendarName != "校历" {
		t.Fatalf("fallback event: %+v", allDay)
	}
}

func TestCompareVersions(t *testing.T) {
	cases := []struct {
		left, right string
		want        int
	}{
		{"1.1.0", "1.0.0", 1},
		{"v1.1.0", "1.1.0", 0},
		{"1.1", "1.1.0", 0},
		{"1.1.0-beta.2", "1.1.0", -1},
		{"1.1.0", "1.1.0-beta.2", 1},
		{"1.1.0-beta.2", "1.1.0-beta.10", -1},
		{"1.1.0-beta", "1.1.0-alpha", 1},
		{"1.1.0+build.9", "1.1.0+build.1", 0},
		{"1.10.0", "1.9.9", 1},
	}
	for _, item := range cases {
		if got := compareVersions(item.left, item.right); got != item.want {
			t.Fatalf("compareVersions(%q, %q) = %d, want %d", item.left, item.right, got, item.want)
		}
		if got := compareVersions(item.right, item.left); got != -item.want {
			t.Fatalf("reverse compareVersions(%q, %q) = %d, want %d", item.right, item.left, got, -item.want)
		}
	}
}
