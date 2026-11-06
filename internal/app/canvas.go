package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

var ErrCanvasAuth = errors.New("Canvas Token 已失效，请在设置中重新配置")

type CanvasCourse struct {
	ID       int64   `json:"id"`
	Name     string  `json:"name"`
	Term     string  `json:"term"`
	Code     string  `json:"code"`
	SyncedAt *string `json:"syncedAt"`
	Error    *string `json:"error"`
	Archived bool    `json:"archived"`
}

type CanvasItem struct {
	ID       string         `json:"id"`
	ParentID *string        `json:"parentId"`
	CourseID int64          `json:"courseId"`
	Name     string         `json:"name"`
	Kind     string         `json:"kind"`
	SourceID *string        `json:"sourceId"`
	DueAt    *string        `json:"dueAt,omitempty"`
	Position int            `json:"position"`
	Data     map[string]any `json:"data,omitempty"`
}

type CanvasState struct {
	Connected bool           `json:"connected"`
	SyncedAt  *string        `json:"syncedAt"`
	Courses   []CanvasCourse `json:"courses"`
	Items     []CanvasItem   `json:"items"`
}

type CachedCourseFile struct {
	Path  string `json:"path"`
	Title string `json:"title"`
}

type Canvas struct {
	db        *sql.DB
	paths     Paths
	secrets   *SecretStore
	media     *MediaServer
	emit      func(string, any)
	tokenMu   sync.Mutex
	token     string
	tokenRead bool
	syncMu    sync.Mutex
	download  sync.Map
}

func NewCanvas(db *sql.DB, paths Paths, secrets *SecretStore, media *MediaServer, emit func(string, any)) *Canvas {
	return &Canvas{db: db, paths: paths, secrets: secrets, media: media, emit: emit}
}

func canvasAPIBase() string {
	if value := strings.TrimSpace(os.Getenv("SJTU_CANVAS_BASE")); value != "" {
		return strings.TrimSuffix(value, "/")
	}
	return "https://oc.sjtu.edu.cn/api/v1"
}

func canvasOrigin() string {
	return strings.TrimSuffix(canvasAPIBase(), "/api/v1")
}

func (c *Canvas) loadToken() string {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	if c.tokenRead {
		return c.token
	}
	c.tokenRead = true
	data, ok, err := c.secrets.LoadRaw("canvas-token")
	if err == nil && ok {
		c.token = strings.TrimSpace(string(data))
	}
	return c.token
}

func (c *Canvas) saveToken(token string) error {
	if err := c.secrets.SaveRaw("canvas-token", []byte(strings.TrimSpace(token))); err != nil {
		return err
	}
	c.tokenMu.Lock()
	c.token = strings.TrimSpace(token)
	c.tokenRead = true
	c.tokenMu.Unlock()
	return nil
}

func (c *Canvas) clearToken() {
	_ = c.secrets.Delete("canvas-token")
	c.tokenMu.Lock()
	c.token = ""
	c.tokenRead = true
	c.tokenMu.Unlock()
}

func (c *Canvas) Connected() bool {
	return c.loadToken() != ""
}

type canvasClient struct {
	base   string
	origin string
	token  string
	http   *http.Client
}

func (c *Canvas) client() (*canvasClient, error) {
	token := c.loadToken()
	if token == "" {
		return nil, errors.New("尚未连接 Canvas")
	}
	return &canvasClient{
		base:   canvasAPIBase(),
		origin: canvasOrigin(),
		token:  token,
		http:   &http.Client{Timeout: 60 * time.Second},
	}, nil
}

var ErrCanvasNotFound = errors.New("Canvas 资源不存在或无权限")

func (client *canvasClient) doGet(rawURL string) ([]byte, http.Header, error) {
	for attempt := 0; attempt < 2; attempt++ {
		request, err := http.NewRequest(http.MethodGet, rawURL, nil)
		if err != nil {
			return nil, nil, err
		}
		request.Header.Set("Authorization", "Bearer "+client.token)
		request.Header.Set("Accept", "application/json")
		request.Header.Set("User-Agent", "SJTU-Desktop/0.1")
		response, err := client.http.Do(request)
		if err != nil {
			if attempt == 0 {
				time.Sleep(400 * time.Millisecond)
				continue
			}
			return nil, nil, fmt.Errorf("无法连接 Canvas：%w", err)
		}
		switch {
		case response.StatusCode == http.StatusUnauthorized:
			_ = response.Body.Close()
			slog.Warn("canvas unauthorized", "url", rawURL)
			return nil, nil, ErrCanvasAuth
		case response.StatusCode == http.StatusForbidden || response.StatusCode == http.StatusNotFound:
			_ = response.Body.Close()
			return nil, nil, ErrCanvasNotFound
		case response.StatusCode == http.StatusTooManyRequests:
			_ = response.Body.Close()
			if attempt == 0 {
				time.Sleep(2 * time.Second)
				continue
			}
			return nil, nil, errors.New("Canvas 请求过于频繁，请稍后再试")
		case response.StatusCode >= 500:
			_ = response.Body.Close()
			if attempt == 0 {
				time.Sleep(700 * time.Millisecond)
				continue
			}
			return nil, nil, fmt.Errorf("Canvas 服务暂时不可用（HTTP %d）", response.StatusCode)
		case response.StatusCode != http.StatusOK:
			_ = response.Body.Close()
			return nil, nil, fmt.Errorf("Canvas 返回 HTTP %d", response.StatusCode)
		}
		data, err := io.ReadAll(io.LimitReader(response.Body, 64<<20))
		headers := response.Header.Clone()
		_ = response.Body.Close()
		if err != nil {
			return nil, nil, err
		}
		return data, headers, nil
	}
	return nil, nil, errors.New("Canvas 请求失败")
}

func (client *canvasClient) verifyToken() error {
	_, _, err := client.doGet(client.base + "/users/self")
	return err
}

func (client *canvasClient) getOne(path string, query url.Values, target any) error {
	rawURL := client.base + path
	if len(query) > 0 {
		rawURL += "?" + query.Encode()
	}
	data, _, err := client.doGet(rawURL)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, target)
}

func nextLink(header http.Header, current string) string {
	for _, part := range strings.Split(header.Get("Link"), ",") {
		if !strings.Contains(part, `rel="next"`) {
			continue
		}
		start := strings.Index(part, "<")
		end := strings.Index(part, ">")
		if start < 0 || end <= start {
			continue
		}
		value := part[start+1 : end]
		if parsed, err := url.Parse(value); err == nil {
			resolved, err := url.Parse(current)
			if err == nil {
				return resolved.ResolveReference(parsed).String()
			}
		}
		return value
	}
	return ""
}

func (client *canvasClient) getAll(path string, query url.Values) ([]json.RawMessage, error) {
	if query == nil {
		query = url.Values{}
	}
	query.Set("per_page", "50")
	rawURL := client.base + path + "?" + query.Encode()
	result := []json.RawMessage{}
	for page := 0; page < 100; page++ {
		data, headers, err := client.doGet(rawURL)
		if err != nil {
			if !errors.Is(err, ErrCanvasNotFound) && !errors.Is(err, ErrCanvasAuth) {
				slog.Warn("canvas request failed", "path", path, "error", err)
			}
			return nil, err
		}
		var items []json.RawMessage
		if err := json.Unmarshal(data, &items); err != nil {
			return nil, fmt.Errorf("Canvas 返回了无法识别的数据")
		}
		result = append(result, items...)
		next := nextLink(headers, rawURL)
		if next == "" || len(items) == 0 {
			break
		}
		rawURL = next
	}
	return result, nil
}

func (c *Canvas) Configure(ctx context.Context, token string) (CanvasState, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return CanvasState{}, errors.New("请填写 Canvas Access Token")
	}
	client := &canvasClient{
		base:   canvasAPIBase(),
		origin: canvasOrigin(),
		token:  token,
		http:   &http.Client{Timeout: 30 * time.Second},
	}
	var probe []json.RawMessage
	query := url.Values{"per_page": []string{"1"}}
	rawURL := client.base + "/courses?" + query.Encode()
	data, _, err := client.doGet(rawURL)
	if err != nil {
		if errors.Is(err, ErrCanvasAuth) {
			return CanvasState{}, errors.New("Canvas Token 无效或已过期")
		}
		return CanvasState{}, err
	}
	if err := json.Unmarshal(data, &probe); err != nil {
		return CanvasState{}, errors.New("Canvas 返回了无法识别的数据")
	}
	if err := c.saveToken(token); err != nil {
		return CanvasState{}, err
	}
	c.syncMu.Lock()
	err = c.syncCourseList(ctx, client)
	c.syncMu.Unlock()
	if err != nil {
		return CanvasState{}, err
	}
	go func() {
		background, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer cancel()
		c.syncMu.Lock()
		defer c.syncMu.Unlock()
		_ = c.syncAllDetails(background)
	}()
	return c.State()
}

func (c *Canvas) Disconnect() (CanvasState, error) {
	c.clearToken()
	if _, err := c.db.Exec(`DELETE FROM canvas_sources`); err != nil {
		return CanvasState{}, err
	}
	if _, err := c.db.Exec(`DELETE FROM canvas_courses`); err != nil {
		return CanvasState{}, err
	}
	_, _ = c.db.Exec(`DELETE FROM canvas_cache`)
	state, _ := c.State()
	c.emitState()
	return state, nil
}

func (c *Canvas) SyncCourses(ctx context.Context) (CanvasState, error) {
	client, err := c.client()
	if err != nil {
		return CanvasState{}, err
	}
	c.syncMu.Lock()
	defer c.syncMu.Unlock()
	if err := c.syncCourseList(ctx, client); err != nil {
		return CanvasState{}, err
	}
	if err := c.syncAllDetails(ctx); err != nil && errors.Is(err, ErrCanvasAuth) {
		return CanvasState{}, err
	}
	return c.State()
}

func (c *Canvas) SyncCourse(ctx context.Context, courseID int64) (CanvasState, error) {
	if _, err := c.client(); err != nil {
		return CanvasState{}, err
	}
	c.syncMu.Lock()
	defer c.syncMu.Unlock()
	if err := c.syncCourseDetails(ctx, courseID); err != nil {
		return CanvasState{}, err
	}
	state, _ := c.State()
	c.emitState()
	return state, nil
}

type rawCanvasCourse struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	CourseCode string `json:"course_code"`
	Term       *struct {
		Name string `json:"name"`
	} `json:"term"`
}

func (c *Canvas) syncCourseList(ctx context.Context, client *canvasClient) error {
	items, err := client.getAll("/courses", url.Values{
		"include[]": []string{"term", "enrollments"},
	})
	if err != nil {
		return err
	}
	for _, raw := range items {
		var course rawCanvasCourse
		if err := json.Unmarshal(raw, &course); err != nil || course.ID == 0 {
			continue
		}
		term := "未标注学期"
		if course.Term != nil && strings.TrimSpace(course.Term.Name) != "" {
			term = strings.TrimSpace(course.Term.Name)
		}
		_, err := c.db.Exec(`INSERT INTO canvas_courses (id, name, term, code, archived)
			VALUES (?, ?, ?, ?, 0)
			ON CONFLICT(id) DO UPDATE SET name = excluded.name, term = excluded.term, code = excluded.code`,
			course.ID, course.Name, term, course.CourseCode)
		if err != nil {
			return err
		}
	}
	state, _ := c.State()
	c.emitState()
	_ = state
	return nil
}

func (c *Canvas) syncAllDetails(ctx context.Context) error {
	rows, err := c.db.Query(`SELECT id FROM canvas_courses WHERE archived = 0 ORDER BY id`)
	if err != nil {
		return err
	}
	ids := []int64{}
	for rows.Next() {
		var id int64
		if rows.Scan(&id) == nil {
			ids = append(ids, id)
		}
	}
	_ = rows.Close()
	for _, id := range ids {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		if err := c.syncCourseDetails(ctx, id); err != nil {
			if errors.Is(err, ErrCanvasAuth) {
				return err
			}
			_, _ = c.db.Exec(`UPDATE canvas_courses SET error = ? WHERE id = ?`, err.Error(), id)
			continue
		}
		c.emitState()
	}
	if c.emit != nil {
		c.emit("canvas.sync.finished", map[string]any{"syncedAt": Now()})
	}
	return nil
}

type rawCanvasFolder struct {
	ID             int64  `json:"id"`
	Name           string `json:"name"`
	FullName       string `json:"full_name"`
	ParentFolderID *int64 `json:"parent_folder_id"`
}

type rawCanvasFile struct {
	ID          int64  `json:"id"`
	DisplayName string `json:"display_name"`
	Filename    string `json:"filename"`
	ContentType string `json:"content_type"`
	Size        int64  `json:"size"`
	FolderID    int64  `json:"folder_id"`
	UpdatedAt   string `json:"updated_at"`
	URL         string `json:"url"`
}

type rawCanvasModuleItem struct {
	ID          int64  `json:"id"`
	Title       string `json:"title"`
	Type        string `json:"type"`
	ContentID   any    `json:"content_id"`
	PageURL     string `json:"page_url"`
	ExternalURL string `json:"external_url"`
	HTMLURL     string `json:"html_url"`
	Position    int    `json:"position"`
}

type rawCanvasModule struct {
	ID       int64                 `json:"id"`
	Name     string                `json:"name"`
	Position int                   `json:"position"`
	Items    []rawCanvasModuleItem `json:"items"`
}

type rawCanvasSubmission struct {
	WorkflowState string   `json:"workflow_state"`
	SubmittedAt   *string  `json:"submitted_at"`
	GradedAt      *string  `json:"graded_at"`
	Score         *float64 `json:"score"`
	Attempt       *int     `json:"attempt"`
	Late          bool     `json:"late"`
	Missing       bool     `json:"missing"`
}

type rawCanvasAssignment struct {
	ID              int64                `json:"id"`
	Name            string               `json:"name"`
	Description     string               `json:"description"`
	DueAt           *string              `json:"due_at"`
	HTMLURL         string               `json:"html_url"`
	PointsPossible  *float64             `json:"points_possible"`
	Published       bool                 `json:"published"`
	SubmissionTypes []string             `json:"submission_types"`
	Submission      *rawCanvasSubmission `json:"submission"`
}

type rawCanvasPage struct {
	PageID    int64  `json:"page_id"`
	URL       string `json:"url"`
	Title     string `json:"title"`
	UpdatedAt string `json:"updated_at"`
	Body      string `json:"body"`
}

type rawCanvasDiscussion struct {
	ID             int64  `json:"id"`
	Title          string `json:"title"`
	Message        string `json:"message"`
	PostedAt       string `json:"posted_at"`
	HTMLURL        string `json:"html_url"`
	IsAnnouncement bool   `json:"is_announcement"`
}

func toInt64(value any) int64 {
	switch number := value.(type) {
	case float64:
		return int64(number)
	case json.Number:
		result, _ := number.Int64()
		return result
	case string:
		result, _ := strconv.ParseInt(number, 10, 64)
		return result
	case int64:
		return number
	case int:
		return int64(number)
	}
	return 0
}

func canvasSourceID(courseID int64, kind string, id any) string {
	return fmt.Sprintf("c%d:%s:%v", courseID, kind, id)
}

func (c *Canvas) syncCourseDetails(ctx context.Context, courseID int64) error {
	client, err := c.client()
	if err != nil {
		return err
	}
	var courseName string
	_ = c.db.QueryRow(`SELECT name FROM canvas_courses WHERE id = ?`, courseID).Scan(&courseName)
	authFailures := 0
	trackAuth := func(err error) {
		if err != nil && errors.Is(err, ErrCanvasAuth) {
			authFailures++
		}
	}
	foldersData, foldersErr := client.getAll(fmt.Sprintf("/courses/%d/folders", courseID), nil)
	trackAuth(foldersErr)
	filesData, filesErr := client.getAll(fmt.Sprintf("/courses/%d/files", courseID), nil)
	trackAuth(filesErr)
	modulesData, modulesErr := client.getAll(fmt.Sprintf("/courses/%d/modules", courseID), url.Values{
		"include[]": []string{"items"},
	})
	trackAuth(modulesErr)
	assignmentsData, assignmentsErr := client.getAll(fmt.Sprintf("/courses/%d/assignments", courseID), url.Values{
		"include[]": []string{"submission"},
	})
	trackAuth(assignmentsErr)
	pagesData, pagesErr := client.getAll(fmt.Sprintf("/courses/%d/pages", courseID), nil)
	trackAuth(pagesErr)
	discussionsData, discussionsErr := client.getAll(fmt.Sprintf("/courses/%d/discussion_topics", courseID), nil)
	trackAuth(discussionsErr)
	if authFailures > 0 {
		if verifyErr := client.verifyToken(); errors.Is(verifyErr, ErrCanvasAuth) {
			return ErrCanvasAuth
		}
	}

	items := []CanvasItem{}
	position := 0
	folderNodes := map[int64]string{}
	rootFolders := map[int64]bool{}
	folderNames := map[int64]string{}
	var folders []rawCanvasFolder
	for _, raw := range foldersData {
		var folder rawCanvasFolder
		if json.Unmarshal(raw, &folder) == nil && folder.ID != 0 {
			folders = append(folders, folder)
			folderNames[folder.ID] = folder.Name
			if folder.ParentFolderID == nil {
				rootFolders[folder.ID] = true
			}
		}
	}
	for _, folder := range folders {
		if folder.ParentFolderID == nil {
			folderNodes[folder.ID] = ""
			continue
		}
		parent := ""
		if value, ok := folderNodes[*folder.ParentFolderID]; ok {
			parent = value
		} else {
			parent = canvasSourceID(courseID, "folder", *folder.ParentFolderID)
		}
		id := canvasSourceID(courseID, "folder", folder.ID)
		folderNodes[folder.ID] = id
		parentPtr := stringPointer(parent)
		items = append(items, CanvasItem{
			ID: id, ParentID: parentPtr, CourseID: courseID, Name: folder.Name, Kind: "folder",
			Position: position, Data: map[string]any{"fullName": folder.FullName},
		})
		position++
	}
	for _, raw := range filesData {
		var file rawCanvasFile
		if json.Unmarshal(raw, &file) != nil || file.ID == 0 {
			continue
		}
		name := strings.TrimSpace(file.DisplayName)
		if name == "" {
			name = file.Filename
		}
		parent := folderNodes[file.FolderID]
		folderPath := folderNames[file.FolderID]
		if file.FolderID == 0 || rootFolders[file.FolderID] {
			parent = ""
			folderPath = ""
		}
		items = append(items, CanvasItem{
			ID: canvasSourceID(courseID, "file", file.ID), ParentID: stringPointer(parent),
			CourseID: courseID, Name: name, Kind: "file",
			SourceID: stringPointer(strconv.FormatInt(file.ID, 10)),
			Position: position,
			Data: map[string]any{
				"url":         absoluteCanvasURL(file.URL),
				"size":        file.Size,
				"contentType": file.ContentType,
				"folderPath":  folderPath,
				"updatedAt":   file.UpdatedAt,
				"filename":    file.Filename,
			},
		})
		position++
	}
	for _, raw := range modulesData {
		var module rawCanvasModule
		if json.Unmarshal(raw, &module) != nil || module.ID == 0 {
			continue
		}
		moduleID := canvasSourceID(courseID, "module", module.ID)
		items = append(items, CanvasItem{
			ID: moduleID, ParentID: nil, CourseID: courseID, Name: module.Name, Kind: "module",
			Position: position, Data: map[string]any{"position": module.Position},
		})
		position++
		for _, moduleItem := range module.Items {
			kind := moduleItemKind(moduleItem.Type)
			if kind == "" {
				continue
			}
			targetType := strings.ToLower(moduleItem.Type)
			sourceID := moduleItem.PageURL
			if sourceID == "" {
				sourceID = strconv.FormatInt(toInt64(moduleItem.ContentID), 10)
			}
			if targetType == "externalurl" || targetType == "externaltool" {
				sourceID = moduleItem.ExternalURL
			}
			itemID := fmt.Sprintf("%s:item:%d", moduleID, moduleItem.ID)
			data := map[string]any{
				"moduleItem":  true,
				"targetType":  targetType,
				"htmlUrl":     absoluteCanvasURL(moduleItem.HTMLURL),
				"externalUrl": moduleItem.ExternalURL,
			}
			if value := toInt64(moduleItem.ContentID); value != 0 {
				data["contentId"] = value
				if kind == "file" {
					data["fileId"] = value
				}
			}
			if moduleItem.PageURL != "" {
				data["pageUrl"] = moduleItem.PageURL
			}
			items = append(items, CanvasItem{
				ID: itemID, ParentID: stringPointer(moduleID), CourseID: courseID,
				Name: moduleItem.Title, Kind: kind, SourceID: stringPointer(sourceID),
				Position: position, Data: data,
			})
			position++
		}
	}
	for _, raw := range assignmentsData {
		var assignment rawCanvasAssignment
		if json.Unmarshal(raw, &assignment) != nil || assignment.ID == 0 {
			continue
		}
		submissionState := "unsubmitted"
		var submittedAt, score any
		if assignment.Submission != nil {
			submissionState = assignment.Submission.WorkflowState
			if assignment.Submission.SubmittedAt != nil {
				submittedAt = *assignment.Submission.SubmittedAt
			}
			if assignment.Submission.Score != nil {
				score = *assignment.Submission.Score
			}
		}
		data := map[string]any{
			"htmlUrl":         absoluteCanvasURL(assignment.HTMLURL),
			"description":     assignment.Description,
			"points":          assignment.PointsPossible,
			"submissionState": submissionState,
			"submittedAt":     submittedAt,
			"score":           score,
			"published":       assignment.Published,
			"submissionTypes": assignment.SubmissionTypes,
		}
		items = append(items, CanvasItem{
			ID: canvasSourceID(courseID, "assignment", assignment.ID), ParentID: nil,
			CourseID: courseID, Name: assignment.Name, Kind: "assignment",
			SourceID: stringPointer(strconv.FormatInt(assignment.ID, 10)),
			DueAt:    assignment.DueAt, Position: position, Data: data,
		})
		position++
	}
	for _, raw := range pagesData {
		var page rawCanvasPage
		if json.Unmarshal(raw, &page) != nil || page.URL == "" {
			continue
		}
		items = append(items, CanvasItem{
			ID: canvasSourceID(courseID, "page", page.URL), ParentID: nil, CourseID: courseID,
			Name: page.Title, Kind: "page", SourceID: stringPointer(page.URL),
			Position: position,
			Data: map[string]any{
				"url":       page.URL,
				"updatedAt": page.UpdatedAt,
			},
		})
		position++
	}
	for _, raw := range discussionsData {
		var topic rawCanvasDiscussion
		if json.Unmarshal(raw, &topic) != nil || topic.ID == 0 {
			continue
		}
		items = append(items, CanvasItem{
			ID: canvasSourceID(courseID, "discussion", topic.ID), ParentID: nil, CourseID: courseID,
			Name: topic.Title, Kind: "discussion", SourceID: stringPointer(strconv.FormatInt(topic.ID, 10)),
			Position: position,
			Data: map[string]any{
				"htmlUrl":        absoluteCanvasURL(topic.HTMLURL),
				"isAnnouncement": topic.IsAnnouncement,
				"postedAt":       topic.PostedAt,
			},
		})
		position++
	}

	syncedAt := Now()
	err = Tx(ctx, c.db, func(tx *sql.Tx) error {
		if _, err := tx.Exec(`DELETE FROM canvas_sources WHERE course_id = ?`, courseID); err != nil {
			return err
		}
		statement, err := tx.Prepare(`INSERT INTO canvas_sources
			(id, course_id, parent_id, name, kind, source_id, due_at, position, data)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`)
		if err != nil {
			return err
		}
		defer statement.Close()
		for _, item := range items {
			data, _ := json.Marshal(item.Data)
			if _, err := statement.Exec(item.ID, item.CourseID, nullableTextPtr(item.ParentID), item.Name,
				item.Kind, nullableTextPtr(item.SourceID), nullableTextPtr(item.DueAt), item.Position, string(data)); err != nil {
				return err
			}
		}
		_, err = tx.Exec(`UPDATE canvas_courses SET synced_at = ?, error = NULL WHERE id = ?`, syncedAt, courseID)
		return err
	})
	if err != nil {
		return err
	}
	if c.emit != nil {
		c.emit("canvas.course.synced", map[string]any{"courseId": courseID, "name": courseName, "syncedAt": syncedAt})
	}
	return nil
}

func nullableTextPtr(value *string) any {
	if value == nil || strings.TrimSpace(*value) == "" {
		return nil
	}
	return strings.TrimSpace(*value)
}

func stringPointer(value string) *string {
	if value == "" {
		return nil
	}
	return &value
}

func moduleItemKind(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "file":
		return "file"
	case "assignment", "quiz":
		return "assignment"
	case "page":
		return "page"
	case "discussion":
		return "discussion"
	case "externalurl", "externaltool":
		return "link"
	default:
		return ""
	}
}

func absoluteCanvasURL(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return ""
	}
	if strings.HasPrefix(value, "http://") || strings.HasPrefix(value, "https://") {
		return value
	}
	return canvasOrigin() + value
}

func (c *Canvas) State() (CanvasState, error) {
	state := CanvasState{Connected: c.Connected(), Courses: []CanvasCourse{}, Items: []CanvasItem{}}
	rows, err := c.db.Query(`SELECT id, name, term, code, synced_at, error, archived FROM canvas_courses
		ORDER BY term DESC, name`)
	if err != nil {
		return CanvasState{}, err
	}
	for rows.Next() {
		var course CanvasCourse
		var syncedAt, courseError sql.NullString
		var archived int
		if err := rows.Scan(&course.ID, &course.Name, &course.Term, &course.Code, &syncedAt, &courseError, &archived); err != nil {
			_ = rows.Close()
			return CanvasState{}, err
		}
		course.Archived = archived == 1
		if syncedAt.Valid {
			value := syncedAt.String
			course.SyncedAt = &value
			if state.SyncedAt == nil || value > *state.SyncedAt {
				state.SyncedAt = &value
			}
		}
		if courseError.Valid {
			value := courseError.String
			course.Error = &value
		}
		state.Courses = append(state.Courses, course)
	}
	_ = rows.Close()
	itemRows, err := c.db.Query(`SELECT id, parent_id, course_id, name, kind, source_id, due_at, position, data
		FROM canvas_sources ORDER BY course_id, position, name`)
	if err != nil {
		return CanvasState{}, err
	}
	defer itemRows.Close()
	for itemRows.Next() {
		var item CanvasItem
		var parentID, sourceID, dueAt sql.NullString
		var data string
		if err := itemRows.Scan(&item.ID, &parentID, &item.CourseID, &item.Name, &item.Kind, &sourceID, &dueAt, &item.Position, &data); err != nil {
			return CanvasState{}, err
		}
		if parentID.Valid {
			value := parentID.String
			item.ParentID = &value
		}
		if sourceID.Valid {
			value := sourceID.String
			item.SourceID = &value
		}
		if dueAt.Valid {
			value := dueAt.String
			item.DueAt = &value
		}
		_ = json.Unmarshal([]byte(data), &item.Data)
		state.Items = append(state.Items, item)
	}
	return state, nil
}

func (c *Canvas) emitState() {
	if c.emit == nil {
		return
	}
	if state, err := c.State(); err == nil {
		c.emit("canvas.state", state)
	}
}

func (c *Canvas) findSource(id string) (CanvasItem, error) {
	var item CanvasItem
	var parentID, sourceID, dueAt sql.NullString
	var data string
	err := c.db.QueryRow(`SELECT id, parent_id, course_id, name, kind, source_id, due_at, position, data
		FROM canvas_sources WHERE id = ?`, id).
		Scan(&item.ID, &parentID, &item.CourseID, &item.Name, &item.Kind, &sourceID, &dueAt, &item.Position, &data)
	if errors.Is(err, sql.ErrNoRows) {
		return CanvasItem{}, errors.New("资料不存在，请先同步课程")
	}
	if err != nil {
		return CanvasItem{}, err
	}
	if parentID.Valid {
		value := parentID.String
		item.ParentID = &value
	}
	if sourceID.Valid {
		value := sourceID.String
		item.SourceID = &value
	}
	if dueAt.Valid {
		value := dueAt.String
		item.DueAt = &value
	}
	_ = json.Unmarshal([]byte(data), &item.Data)
	return item, nil
}

func (c *Canvas) findSourceBySourceID(courseID int64, kind, sourceID string) (CanvasItem, bool) {
	var item CanvasItem
	var parentID, source, dueAt sql.NullString
	var data string
	err := c.db.QueryRow(`SELECT id, parent_id, course_id, name, kind, source_id, due_at, position, data
		FROM canvas_sources WHERE course_id = ? AND kind = ? AND source_id = ? LIMIT 1`,
		courseID, kind, sourceID).
		Scan(&item.ID, &parentID, &item.CourseID, &item.Name, &item.Kind, &source, &dueAt, &item.Position, &data)
	if err != nil {
		return CanvasItem{}, false
	}
	if parentID.Valid {
		value := parentID.String
		item.ParentID = &value
	}
	if source.Valid {
		value := source.String
		item.SourceID = &value
	}
	if dueAt.Valid {
		value := dueAt.String
		item.DueAt = &value
	}
	_ = json.Unmarshal([]byte(data), &item.Data)
	return item, true
}

func (c *Canvas) cachePath(courseID, fileID int64, name string) string {
	dir := filepath.Join(c.paths.CanvasDir, strconv.FormatInt(courseID, 10))
	_ = os.MkdirAll(dir, 0o755)
	safe := sanitizeFilename(name, strconv.FormatInt(fileID, 10))
	return filepath.Join(dir, fmt.Sprintf("%d-%s", fileID, safe))
}

func (c *Canvas) sourceLock(id string) *sync.Mutex {
	value, _ := c.download.LoadOrStore(id, &sync.Mutex{})
	return value.(*sync.Mutex)
}

func (c *Canvas) DownloadSource(ctx context.Context, id string) (string, error) {
	item, err := c.findSource(id)
	if err != nil {
		return "", err
	}
	return c.downloadItem(ctx, item)
}

func (c *Canvas) downloadItem(ctx context.Context, item CanvasItem) (string, error) {
	if item.Kind != "file" {
		return "", errors.New("该资料不是文件")
	}
	sourceID := ""
	if item.SourceID != nil {
		sourceID = *item.SourceID
	}
	fileID, _ := strconv.ParseInt(sourceID, 10, 64)
	if fileID == 0 {
		return "", errors.New("文件 ID 无效")
	}
	lock := c.sourceLock(item.ID)
	lock.Lock()
	defer lock.Unlock()
	name := item.Name
	if value, ok := item.Data["filename"].(string); ok && strings.TrimSpace(value) != "" {
		name = value
	}
	target := c.cachePath(item.CourseID, fileID, name)
	if info, err := os.Stat(target); err == nil && info.Size() > 0 {
		return target, nil
	}
	rawURL, _ := item.Data["url"].(string)
	if rawURL == "" {
		file, err := c.fetchFile(ctx, item.CourseID, fileID)
		if err != nil {
			return "", err
		}
		rawURL = absoluteCanvasURL(file.URL)
	}
	if rawURL == "" {
		return "", errors.New("该文件没有下载地址")
	}
	client := &http.Client{Timeout: 5 * time.Minute, CheckRedirect: func(*http.Request, []*http.Request) error {
		return http.ErrUseLastResponse
	}}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return "", err
	}
	if strings.HasPrefix(rawURL, canvasOrigin()) {
		request.Header.Set("Authorization", "Bearer "+c.loadToken())
	}
	request.Header.Set("User-Agent", "SJTU-Desktop/0.1")
	response, err := client.Do(request)
	if err != nil {
		return "", fmt.Errorf("下载失败：%w", err)
	}
	var body io.ReadCloser
	if response.StatusCode >= 300 && response.StatusCode < 400 {
		location := response.Header.Get("Location")
		_ = response.Body.Close()
		if location == "" {
			return "", errors.New("Canvas 没有返回下载地址")
		}
		plain := &http.Client{Timeout: 5 * time.Minute}
		second, err := plain.Get(location)
		if err != nil {
			return "", fmt.Errorf("下载失败：%w", err)
		}
		if second.StatusCode != http.StatusOK {
			_ = second.Body.Close()
			return "", fmt.Errorf("下载失败：HTTP %d", second.StatusCode)
		}
		body = second.Body
	} else if response.StatusCode == http.StatusOK {
		body = response.Body
	} else {
		_ = response.Body.Close()
		if response.StatusCode == http.StatusUnauthorized {
			return "", ErrCanvasAuth
		}
		return "", fmt.Errorf("下载失败：HTTP %d", response.StatusCode)
	}
	defer body.Close()
	temp, err := os.CreateTemp(filepath.Dir(target), ".canvas-*.tmp")
	if err != nil {
		return "", err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	written, err := io.Copy(temp, io.LimitReader(body, 1<<30))
	if err != nil {
		_ = temp.Close()
		return "", err
	}
	if err := temp.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(tempName, target); err != nil {
		return "", err
	}
	_, _ = c.db.Exec(`INSERT INTO canvas_cache (source_id, path, size, cached_at) VALUES (?, ?, ?, ?)
		ON CONFLICT(source_id) DO UPDATE SET path = excluded.path, size = excluded.size, cached_at = excluded.cached_at`,
		item.ID, target, written, Now())
	return target, nil
}

type rawCanvasFileDetail struct {
	URL         string `json:"url"`
	DisplayName string `json:"display_name"`
	Filename    string `json:"filename"`
	ContentType string `json:"content_type"`
	Size        int64  `json:"size"`
}

func (c *Canvas) fetchFile(ctx context.Context, courseID, fileID int64) (rawCanvasFileDetail, error) {
	client, err := c.client()
	if err != nil {
		return rawCanvasFileDetail{}, err
	}
	var file rawCanvasFileDetail
	err = client.getOne(fmt.Sprintf("/courses/%d/files/%d", courseID, fileID), nil, &file)
	return file, err
}

func (c *Canvas) ReadSource(ctx context.Context, id string) (DocumentContent, error) {
	item, err := c.findSource(id)
	if err != nil {
		return DocumentContent{}, err
	}
	if item.Data != nil {
		if moduleItem, _ := item.Data["moduleItem"].(bool); moduleItem {
			return c.readModuleItem(ctx, item)
		}
	}
	switch item.Kind {
	case "file":
		return c.readFile(ctx, item)
	case "assignment":
		return c.readAssignment(ctx, item)
	case "page":
		return c.readPage(ctx, item)
	case "discussion":
		return c.readDiscussion(ctx, item)
	case "link":
		urlValue, _ := item.Data["externalUrl"].(string)
		return DocumentContent{
			Title: item.Name, Format: "link", Text: urlValue, ReadOnly: true,
			Size: 0, Message: "外部链接",
		}, nil
	default:
		return DocumentContent{}, errors.New("请选择文件、作业或页面")
	}
}

func (c *Canvas) readModuleItem(ctx context.Context, item CanvasItem) (DocumentContent, error) {
	targetType, _ := item.Data["targetType"].(string)
	switch targetType {
	case "file":
		fileID := toInt64(item.Data["contentId"])
		if fileID == 0 {
			fileID = toInt64(item.Data["fileId"])
		}
		if fileID != 0 {
			if target, ok := c.findSourceBySourceID(item.CourseID, "file", strconv.FormatInt(fileID, 10)); ok {
				return c.readFile(ctx, target)
			}
		}
		file, err := c.fetchFile(ctx, item.CourseID, fileID)
		if err != nil {
			return DocumentContent{}, err
		}
		return c.readFileData(ctx, item.CourseID, fileID, firstNonEmpty(file.DisplayName, item.Name), file.URL, file.ContentType, file.Size)
	case "assignment":
		contentID := toInt64(item.Data["contentId"])
		if contentID != 0 {
			if target, ok := c.findSourceBySourceID(item.CourseID, "assignment", strconv.FormatInt(contentID, 10)); ok {
				return c.readAssignment(ctx, target)
			}
		}
		return DocumentContent{}, errors.New("模块引用的作业尚未同步，请先同步课程")
	case "page":
		pageURL, _ := item.Data["pageUrl"].(string)
		if pageURL == "" {
			pageURL = *item.SourceID
		}
		if target, ok := c.findSourceBySourceID(item.CourseID, "page", pageURL); ok {
			return c.readPage(ctx, target)
		}
		return c.readPageURL(ctx, item.CourseID, pageURL, item.Name)
	case "discussion":
		contentID := toInt64(item.Data["contentId"])
		if contentID != 0 {
			if target, ok := c.findSourceBySourceID(item.CourseID, "discussion", strconv.FormatInt(contentID, 10)); ok {
				return c.readDiscussion(ctx, target)
			}
		}
		return c.readDiscussionID(ctx, item.CourseID, contentID, item.Name)
	case "externalurl", "externaltool":
		urlValue, _ := item.Data["externalUrl"].(string)
		return DocumentContent{Title: item.Name, Format: "link", Text: urlValue, ReadOnly: true, Message: "外部链接"}, nil
	default:
		return DocumentContent{}, errors.New("暂不支持该模块项")
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func (c *Canvas) readFile(ctx context.Context, item CanvasItem) (DocumentContent, error) {
	name, _ := item.Data["filename"].(string)
	if name == "" {
		name = item.Name
	}
	contentType, _ := item.Data["contentType"].(string)
	size := int64(0)
	if value, ok := item.Data["size"].(float64); ok {
		size = int64(value)
	}
	return c.previewFile(ctx, item, name, contentType, size)
}

func (c *Canvas) readFileData(ctx context.Context, courseID, fileID int64, name, rawURL, contentType string, size int64) (DocumentContent, error) {
	item := CanvasItem{
		ID: canvasSourceID(courseID, "file", fileID), CourseID: courseID, Name: name, Kind: "file",
		SourceID: stringPointer(strconv.FormatInt(fileID, 10)),
		Data:     map[string]any{"url": absoluteCanvasURL(rawURL), "contentType": contentType, "size": size, "filename": name},
	}
	return c.previewFile(ctx, item, name, contentType, size)
}

func proxyPreviewFormat(format string) bool {
	switch format {
	case "pdf", "docx", "xlsx", "pptx", "doc", "ppt", "image", "video", "audio":
		return true
	default:
		return false
	}
}

func (c *Canvas) previewFile(ctx context.Context, item CanvasItem, name, contentType string, size int64) (DocumentContent, error) {
	if decoded, err := url.PathUnescape(name); err == nil && strings.TrimSpace(decoded) != "" {
		name = decoded
	}
	ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(name)), ".")
	format := formatOf(name, ext, false)
	if known, ok := binaryExtensions[ext]; ok && proxyPreviewFormat(format) {
		rawURL, _ := item.Data["url"].(string)
		if rawURL == "" {
			file, err := c.fetchFile(ctx, item.CourseID, itemFileID(item))
			if err != nil {
				return DocumentContent{}, err
			}
			rawURL = absoluteCanvasURL(file.URL)
			if contentType == "" {
				contentType = file.ContentType
			}
		}
		if rawURL == "" {
			return DocumentContent{}, errors.New("该文件没有预览地址")
		}
		headers := http.Header{}
		if strings.HasPrefix(rawURL, canvasOrigin()) {
			headers.Set("Authorization", "Bearer "+c.loadToken())
		}
		resourceURL := c.media.AddProxy(rawURL, headers, &http.Client{Timeout: 0},
			name, firstNonEmpty(contentType, known), time.Hour)
		return DocumentContent{
			Title: name, Size: size, Format: format, ResourceURL: resourceURL,
			ReadOnly: true, ModifiedAt: Now(),
		}, nil
	}
	target, err := c.downloadItem(ctx, item)
	if err != nil {
		return DocumentContent{}, err
	}
	return c.documentFromFile(target, name, contentType, size, item.CourseID, itemFileID(item))
}

func itemFileID(item CanvasItem) int64 {
	sourceID := ""
	if item.SourceID != nil {
		sourceID = *item.SourceID
	}
	fileID, _ := strconv.ParseInt(sourceID, 10, 64)
	return fileID
}

func (c *Canvas) documentFromFile(pathValue, name, contentType string, size int64, courseID, fileID int64) (DocumentContent, error) {
	return documentFromLocalFile(c.media, pathValue, name, contentType, size)
}

func documentFromLocalFile(media *MediaServer, pathValue, name, contentType string, size int64) (DocumentContent, error) {
	ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(name)), ".")
	content := DocumentContent{
		Path: pathValue, Title: name, Size: size, ReadOnly: true,
		ModifiedAt: Now(),
	}
	if contentType == "" {
		contentType = mimeForFile(pathValue)
	}
	if known, ok := binaryExtensions[ext]; ok {
		content.Format = formatOf(pathValue, ext, false)
		content.ResourceURL, _ = media.AddFile(pathValue, name, firstNonEmpty(contentType, known), time.Hour)
		return content, nil
	}
	data, err := os.ReadFile(pathValue)
	if err != nil {
		return DocumentContent{}, err
	}
	if looksBinary(data) {
		content.Format = "binary"
		content.ResourceURL, _ = media.AddFile(pathValue, name, "application/octet-stream", time.Hour)
		content.Message = "二进制文件，可用系统默认应用打开"
		return content, nil
	}
	content.Format = formatOf(pathValue, ext, true)
	if len(data) > 1<<20 {
		data = data[:1<<20]
		content.Message = "文件大于 1 MB，当前为只读预览"
	}
	if len(data) >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF {
		data = data[3:]
	}
	content.Text = string(data)
	return content, nil
}

func (c *Canvas) readAssignment(ctx context.Context, item CanvasItem) (DocumentContent, error) {
	description, _ := item.Data["description"].(string)
	if strings.TrimSpace(description) == "" {
		client, err := c.client()
		if err != nil {
			return DocumentContent{}, err
		}
		var assignment rawCanvasAssignment
		sourceID := ""
		if item.SourceID != nil {
			sourceID = *item.SourceID
		}
		if err := client.getOne(fmt.Sprintf("/courses/%d/assignments/%s", item.CourseID, sourceID), nil, &assignment); err == nil {
			description = assignment.Description
		}
	}
	return DocumentContent{
		Title: item.Name, Format: "html", Text: description, ReadOnly: true, ModifiedAt: Now(),
		Meta: map[string]any{
			"kind": "assignment", "dueAt": item.DueAt, "htmlUrl": item.Data["htmlUrl"],
			"points": item.Data["points"], "submissionState": item.Data["submissionState"],
			"score": item.Data["score"], "submittedAt": item.Data["submittedAt"],
		},
	}, nil
}

func (c *Canvas) readPage(ctx context.Context, item CanvasItem) (DocumentContent, error) {
	sourceID := ""
	if item.SourceID != nil {
		sourceID = *item.SourceID
	}
	return c.readPageURL(ctx, item.CourseID, sourceID, item.Name)
}

func (c *Canvas) readPageURL(ctx context.Context, courseID int64, pageURL, fallbackTitle string) (DocumentContent, error) {
	client, err := c.client()
	if err != nil {
		return DocumentContent{}, err
	}
	var page rawCanvasPage
	if err := client.getOne(fmt.Sprintf("/courses/%d/pages/%s", courseID, url.PathEscape(pageURL)), nil, &page); err != nil {
		return DocumentContent{}, err
	}
	return DocumentContent{
		Title: firstNonEmpty(page.Title, fallbackTitle), Format: "html", Text: page.Body,
		ReadOnly: true, ModifiedAt: page.UpdatedAt,
		Meta: map[string]any{"kind": "page", "url": page.URL},
	}, nil
}

func (c *Canvas) readDiscussion(ctx context.Context, item CanvasItem) (DocumentContent, error) {
	sourceID := ""
	if item.SourceID != nil {
		sourceID = *item.SourceID
	}
	return c.readDiscussionID(ctx, item.CourseID, toInt64String(sourceID), item.Name)
}

func toInt64String(value string) int64 {
	result, _ := strconv.ParseInt(value, 10, 64)
	return result
}

func (c *Canvas) readDiscussionID(ctx context.Context, courseID, topicID int64, fallbackTitle string) (DocumentContent, error) {
	client, err := c.client()
	if err != nil {
		return DocumentContent{}, err
	}
	var topic rawCanvasDiscussion
	if err := client.getOne(fmt.Sprintf("/courses/%d/discussion_topics/%d", courseID, topicID), nil, &topic); err != nil {
		return DocumentContent{}, err
	}
	return DocumentContent{
		Title: firstNonEmpty(topic.Title, fallbackTitle), Format: "html", Text: topic.Message,
		ReadOnly: true, ModifiedAt: topic.PostedAt,
		Meta: map[string]any{"kind": "discussion", "htmlUrl": topic.HTMLURL, "isAnnouncement": topic.IsAnnouncement},
	}, nil
}

func (c *Canvas) ArchiveCourse(courseID int64, archived bool) (CanvasState, error) {
	if _, err := c.db.Exec(`UPDATE canvas_courses SET archived = ? WHERE id = ?`, BoolInt(archived), courseID); err != nil {
		return CanvasState{}, err
	}
	state, _ := c.State()
	c.emitState()
	return state, nil
}

func (c *Canvas) CachedFiles() ([]CachedCourseFile, error) {
	rows, err := c.db.Query(`SELECT cc.path, c.name, s.name, s.data
		FROM canvas_cache cc
		JOIN canvas_sources s ON s.id = cc.source_id
		JOIN canvas_courses c ON c.id = s.course_id
		ORDER BY cc.cached_at DESC LIMIT 200`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []CachedCourseFile{}
	for rows.Next() {
		var pathValue, courseName, name, data string
		if err := rows.Scan(&pathValue, &courseName, &name, &data); err != nil {
			return nil, err
		}
		var payload map[string]any
		_ = json.Unmarshal([]byte(data), &payload)
		folder, _ := payload["folderPath"].(string)
		title := courseName + "/" + name
		if folder != "" {
			title = courseName + "/" + folder + "/" + name
		}
		result = append(result, CachedCourseFile{Path: pathValue, Title: title})
	}
	return result, rows.Err()
}

func (c *Canvas) ExportCourseFiles(ctx context.Context, courseIDs []int64, dir string) (map[string]any, error) {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return nil, errors.New("请选择导出目录")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	query := `SELECT s.id, s.course_id, s.name, s.source_id, s.data, c.name, c.term
		FROM canvas_sources s JOIN canvas_courses c ON c.id = s.course_id
		WHERE s.kind = 'file'`
	args := []any{}
	if len(courseIDs) > 0 {
		placeholders := make([]string, len(courseIDs))
		for index, id := range courseIDs {
			placeholders[index] = "?"
			args = append(args, id)
		}
		query += " AND s.course_id IN (" + strings.Join(placeholders, ",") + ")"
	}
	rows, err := c.db.Query(query, args...)
	if err != nil {
		return nil, err
	}
	type fileRow struct {
		item       CanvasItem
		courseName string
		term       string
	}
	files := []fileRow{}
	for rows.Next() {
		var item CanvasItem
		var data, courseName, term string
		var sourceID sql.NullString
		if err := rows.Scan(&item.ID, &item.CourseID, &item.Name, &sourceID, &data, &courseName, &term); err != nil {
			_ = rows.Close()
			return nil, err
		}
		if sourceID.Valid {
			value := sourceID.String
			item.SourceID = &value
		}
		_ = json.Unmarshal([]byte(data), &item.Data)
		item.Kind = "file"
		files = append(files, fileRow{item: item, courseName: courseName, term: term})
	}
	_ = rows.Close()
	exported := 0
	failed := []string{}
	for index, file := range files {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		if c.emit != nil {
			c.emit("canvas.export.progress", map[string]any{
				"done": index, "total": len(files), "current": file.item.Name,
			})
		}
		localPath, err := c.downloadItem(ctx, file.item)
		if err != nil {
			failed = append(failed, file.courseName+"/"+file.item.Name+"："+err.Error())
			continue
		}
		folderPath, _ := file.item.Data["folderPath"].(string)
		relative := filepath.Join(safePathSegments(file.term), safePathSegments(file.courseName), safePathSegments(folderPath), file.item.Name)
		target := filepath.Join(dir, relative)
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			failed = append(failed, file.item.Name+"："+err.Error())
			continue
		}
		if err := copyFile(localPath, target); err != nil {
			failed = append(failed, file.item.Name+"："+err.Error())
			continue
		}
		exported++
	}
	if c.emit != nil {
		c.emit("canvas.export.progress", map[string]any{"done": len(files), "total": len(files), "current": ""})
	}
	return map[string]any{"dir": dir, "exported": exported, "failed": failed}, nil
}

func safePathSegments(value string) string {
	value = strings.ReplaceAll(value, "\\", "/")
	parts := strings.Split(value, "/")
	clean := []string{}
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" || part == "." || part == ".." {
			continue
		}
		clean = append(clean, sanitizeFilename(part, "item"))
	}
	return filepath.Join(clean...)
}

func copyFile(source, target string) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	output, err := os.Create(target)
	if err != nil {
		return err
	}
	defer output.Close()
	_, err = io.Copy(output, input)
	return err
}

func (c *Canvas) ExportFileTo(ctx context.Context, sourceID, projectRoot string) (string, error) {
	item, err := c.findSource(sourceID)
	if err != nil {
		return "", err
	}
	localPath, err := c.downloadItem(ctx, item)
	if err != nil {
		return "", err
	}
	var courseName, term string
	_ = c.db.QueryRow(`SELECT name, term FROM canvas_courses WHERE id = ?`, item.CourseID).Scan(&courseName, &term)
	folderPath, _ := item.Data["folderPath"].(string)
	targetDir := filepath.Join(projectRoot, "Canvas", safePathSegments(courseName), safePathSegments(folderPath))
	if err := os.MkdirAll(targetDir, 0o755); err != nil {
		return "", err
	}
	target := filepath.Join(targetDir, sanitizeFilename(item.Name, "file"))
	if err := copyFile(localPath, target); err != nil {
		return "", err
	}
	return target, nil
}

func (c *Canvas) Register(router *Router) {
	router.Handle("canvas.state", func([]json.RawMessage) (any, error) {
		return c.State()
	})
	router.Handle("canvas.configure", func(args []json.RawMessage) (any, error) {
		token, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		return c.Configure(ctx, token)
	})
	router.Handle("canvas.disconnect", func([]json.RawMessage) (any, error) {
		return c.Disconnect()
	})
	router.Handle("canvas.syncCourses", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer cancel()
		return c.SyncCourses(ctx)
	})
	router.Handle("canvas.syncCourse", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		return c.SyncCourse(ctx, id)
	})
	router.Handle("canvas.archiveCourse", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		archived, err := DecodeArg[bool](args, 1)
		if err != nil {
			return nil, err
		}
		return c.ArchiveCourse(id, archived)
	})
	router.Handle("canvas.read", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		return c.ReadSource(ctx, id)
	})
	router.Handle("canvas.download", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		pathValue, err := c.DownloadSource(ctx, id)
		if err != nil {
			return nil, err
		}
		return map[string]any{"path": pathValue}, nil
	})
	router.Handle("canvas.cachedFiles", func([]json.RawMessage) (any, error) {
		return c.CachedFiles()
	})
	router.Handle("canvas.exportCourseFiles", func(args []json.RawMessage) (any, error) {
		courseIDs, err := DecodeArg[[]int64](args, 0)
		if err != nil {
			return nil, err
		}
		dir, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Minute)
		defer cancel()
		return c.ExportCourseFiles(ctx, courseIDs, dir)
	})
	router.Handle("canvas.addToProject", func(args []json.RawMessage) (any, error) {
		sourceID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		dir, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		return c.ExportFileTo(ctx, sourceID, dir)
	})
}
