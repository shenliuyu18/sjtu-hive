package app

import (
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

type Project struct {
	ID           string  `json:"id"`
	Name         string  `json:"name"`
	Path         string  `json:"path"`
	CreatedAt    string  `json:"createdAt"`
	LastOpenedAt *string `json:"lastOpenedAt"`
}

type FileEntry struct {
	Path       string `json:"path"`
	Name       string `json:"name"`
	IsDir      bool   `json:"isDir"`
	Size       int64  `json:"size"`
	ModifiedAt string `json:"modifiedAt"`
	Ext        string `json:"ext"`
}

type WorkspaceRecent struct {
	Path      string  `json:"path"`
	Name      string  `json:"name"`
	ProjectID *string `json:"projectId"`
	OpenedAt  string  `json:"openedAt"`
}

type DocumentContent struct {
	Path        string         `json:"path"`
	Title       string         `json:"title"`
	Format      string         `json:"format"`
	Text        string         `json:"text,omitempty"`
	ResourceURL string         `json:"resourceUrl,omitempty"`
	AssetBase   string         `json:"assetBase,omitempty"`
	Revision    string         `json:"revision,omitempty"`
	ReadOnly    bool           `json:"readOnly,omitempty"`
	Size        int64          `json:"size"`
	ModifiedAt  string         `json:"modifiedAt"`
	Message     string         `json:"message,omitempty"`
	Meta        map[string]any `json:"meta,omitempty"`
}

type Workspace struct {
	db        *sql.DB
	paths     Paths
	media     *MediaServer
	emit      func(string, any)
	terminals *TerminalManager
}

func NewWorkspace(db *sql.DB, paths Paths, media *MediaServer, emit func(string, any)) *Workspace {
	workspace := &Workspace{db: db, paths: paths, media: media, emit: emit}
	workspace.terminals = NewTerminalManager(emit)
	workspace.terminals.projectPath = func(id string) (string, error) {
		project, err := workspace.Project(id)
		if err != nil {
			return "", err
		}
		return project.Path, nil
	}
	return workspace
}

func (w *Workspace) CloseTerminals() {
	w.terminals.CloseAll()
}

func (w *Workspace) EnsureDefaultProject() error {
	var count int
	if err := w.db.QueryRow(`SELECT COUNT(*) FROM projects`).Scan(&count); err != nil {
		return err
	}
	if count > 0 {
		return nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	base := filepath.Join(home, "Documents")
	if info, err := os.Stat(base); err != nil || !info.IsDir() {
		base = home
	}
	dir := filepath.Join(base, "SJTU Notes")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	_, err = w.RegisterProject(dir, "我的笔记")
	return err
}

func (w *Workspace) RegisterProject(dir, name string) (Project, error) {
	abs, err := filepath.Abs(strings.TrimSpace(dir))
	if err != nil || strings.TrimSpace(dir) == "" {
		return Project{}, errors.New("项目路径无效")
	}
	info, err := os.Stat(abs)
	if err != nil || !info.IsDir() {
		return Project{}, errors.New("项目目录不存在")
	}
	var existing Project
	var existingLastOpened sql.NullString
	err = w.db.QueryRow(`SELECT id, name, path, created_at, last_opened_at FROM projects WHERE path = ?`, abs).
		Scan(&existing.ID, &existing.Name, &existing.Path, &existing.CreatedAt, &existingLastOpened)
	if err == nil {
		if existingLastOpened.Valid {
			value := existingLastOpened.String
			existing.LastOpenedAt = &value
		}
		return existing, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Project{}, err
	}
	if strings.TrimSpace(name) == "" {
		name = filepath.Base(abs)
	}
	project := Project{
		ID:        "project-" + randomID()[:16],
		Name:      name,
		Path:      abs,
		CreatedAt: Now(),
	}
	_, err = w.db.Exec(`INSERT INTO projects (id, name, path, created_at, last_opened_at) VALUES (?, ?, ?, ?, ?)`,
		project.ID, project.Name, project.Path, project.CreatedAt, project.CreatedAt)
	if err != nil {
		return Project{}, err
	}
	w.emitChange("projects")
	return project, nil
}

func (w *Workspace) State() (map[string]any, error) {
	if err := w.EnsureDefaultProject(); err != nil {
		return nil, err
	}
	rows, err := w.db.Query(`SELECT id, name, path, created_at, last_opened_at FROM projects
		ORDER BY COALESCE(last_opened_at, created_at) DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	projects := []Project{}
	for rows.Next() {
		var project Project
		var lastOpened sql.NullString
		if err := rows.Scan(&project.ID, &project.Name, &project.Path, &project.CreatedAt, &lastOpened); err != nil {
			return nil, err
		}
		if lastOpened.Valid {
			value := lastOpened.String
			project.LastOpenedAt = &value
		}
		projects = append(projects, project)
	}
	recent, err := w.Recent()
	if err != nil {
		return nil, err
	}
	return map[string]any{"projects": projects, "recent": recent}, nil
}

func (w *Workspace) RemoveProject(id string) error {
	if _, err := w.Project(id); err != nil {
		return err
	}
	if _, err := w.db.Exec(`DELETE FROM projects WHERE id = ?`, id); err != nil {
		return err
	}
	_, _ = w.db.Exec(`DELETE FROM recent_files WHERE project_id = ?`, id)
	_, _ = w.db.Exec(`DELETE FROM workspace_tabs WHERE project_id = ?`, id)
	w.emitChange("projects")
	return nil
}

func (w *Workspace) RenameProject(id, name string) (Project, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return Project{}, errors.New("项目名称不能为空")
	}
	if _, err := w.db.Exec(`UPDATE projects SET name = ? WHERE id = ?`, name, id); err != nil {
		return Project{}, err
	}
	w.emitChange("projects")
	return w.Project(id)
}

func (w *Workspace) Project(id string) (Project, error) {
	var project Project
	var lastOpened sql.NullString
	err := w.db.QueryRow(`SELECT id, name, path, created_at, last_opened_at FROM projects WHERE id = ?`, id).
		Scan(&project.ID, &project.Name, &project.Path, &project.CreatedAt, &lastOpened)
	if errors.Is(err, sql.ErrNoRows) {
		return Project{}, errors.New("项目不存在")
	}
	if err != nil {
		return Project{}, err
	}
	if lastOpened.Valid {
		value := lastOpened.String
		project.LastOpenedAt = &value
	}
	return project, nil
}

func (w *Workspace) TouchProject(id string) {
	_, _ = w.db.Exec(`UPDATE projects SET last_opened_at = ? WHERE id = ?`, Now(), id)
}

func (w *Workspace) projectOfPath(pathValue string) (Project, string, error) {
	abs, err := filepath.Abs(pathValue)
	if err != nil {
		return Project{}, "", errors.New("路径无效")
	}
	rows, err := w.db.Query(`SELECT id, name, path, created_at FROM projects`)
	if err != nil {
		return Project{}, "", err
	}
	defer rows.Close()
	best := Project{}
	bestRel := ""
	for rows.Next() {
		var project Project
		if err := rows.Scan(&project.ID, &project.Name, &project.Path, &project.CreatedAt); err != nil {
			return Project{}, "", err
		}
		rel, err := filepath.Rel(project.Path, abs)
		if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) || filepath.IsAbs(rel) {
			continue
		}
		if best.ID == "" || len(project.Path) > len(best.Path) {
			best = project
			bestRel = rel
		}
	}
	if best.ID == "" {
		return Project{}, "", errors.New("该路径不在任何项目中")
	}
	return best, bestRel, nil
}

func resolveInside(root, target string, mustExist bool) (string, error) {
	abs, err := filepath.Abs(target)
	if err != nil {
		return "", errors.New("路径无效")
	}
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return "", errors.New("项目路径无效")
	}
	rel, err := filepath.Rel(rootAbs, abs)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) || filepath.IsAbs(rel) {
		return "", errors.New("路径超出项目范围")
	}
	if mustExist {
		resolved, err := filepath.EvalSymlinks(abs)
		if err != nil {
			return "", errors.New("文件不存在")
		}
		rootResolved, err := filepath.EvalSymlinks(rootAbs)
		if err != nil {
			return "", errors.New("项目路径不可用")
		}
		relResolved, err := filepath.Rel(rootResolved, resolved)
		if err != nil || relResolved == ".." || strings.HasPrefix(relResolved, ".."+string(os.PathSeparator)) {
			return "", errors.New("路径超出项目范围")
		}
		return resolved, nil
	}
	return abs, nil
}

var hiddenNames = map[string]bool{
	".git": true, ".svn": true, ".hg": true, "node_modules": true,
	".DS_Store": true, "Thumbs.db": true, "$RECYCLE.BIN": true,
}

func (w *Workspace) List(projectID, pathValue string) ([]FileEntry, error) {
	project, err := w.Project(projectID)
	if err != nil {
		return nil, err
	}
	target := project.Path
	if strings.TrimSpace(pathValue) != "" {
		target = pathValue
	}
	target, err = resolveInside(project.Path, target, true)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(target)
	if err != nil || !info.IsDir() {
		return nil, errors.New("目录不存在")
	}
	entries, err := os.ReadDir(target)
	if err != nil {
		return nil, err
	}
	result := make([]FileEntry, 0, len(entries))
	for _, entry := range entries {
		if hiddenNames[entry.Name()] {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(entry.Name())), ".")
		result = append(result, FileEntry{
			Path:       filepath.Join(target, entry.Name()),
			Name:       entry.Name(),
			IsDir:      entry.IsDir(),
			Size:       info.Size(),
			ModifiedAt: info.ModTime().Format(time.RFC3339),
			Ext:        ext,
		})
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].IsDir != result[j].IsDir {
			return result[i].IsDir
		}
		return strings.ToLower(result[i].Name) < strings.ToLower(result[j].Name)
	})
	if len(result) > 2000 {
		result = result[:2000]
	}
	w.TouchProject(project.ID)
	return result, nil
}

var binaryExtensions = map[string]string{
	"pdf":  "application/pdf",
	"png":  "image/png",
	"jpg":  "image/jpeg",
	"jpeg": "image/jpeg",
	"gif":  "image/gif",
	"webp": "image/webp",
	"bmp":  "image/bmp",
	"ico":  "image/x-icon",
	"avif": "image/avif",
	"svg":  "image/svg+xml",
	"docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	"xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	"pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
	"doc":  "application/msword",
	"dot":  "application/msword",
	"xls":  "application/vnd.ms-excel",
	"xlt":  "application/vnd.ms-excel",
	"ppt":  "application/vnd.ms-powerpoint",
	"pot":  "application/vnd.ms-powerpoint",
	"pps":  "application/vnd.ms-powerpoint",
	"zip":  "application/zip",
	"rar":  "application/vnd.rar",
	"7z":   "application/x-7z-compressed",
	"mp4":  "video/mp4",
	"webm": "video/webm",
	"mov":  "video/quicktime",
	"mkv":  "video/x-matroska",
	"mp3":  "audio/mpeg",
	"wav":  "audio/wav",
	"flac": "audio/flac",
	"m4a":  "audio/mp4",
	"ogg":  "audio/ogg",
	"exe":  "application/octet-stream",
	"dll":  "application/octet-stream",
}

func formatOf(pathValue, ext string, isText bool) string {
	switch ext {
	case "pdf":
		return "pdf"
	case "docx":
		return "docx"
	case "xlsx":
		return "xlsx"
	case "pptx":
		return "pptx"
	case "ppt", "pot", "pps":
		return "ppt"
	case "doc", "dot":
		return "doc"
	case "xls", "xlt":
		return "xlsx"
	case "png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "ico", "svg":
		return "image"
	case "html", "htm":
		return "html"
	case "mp4", "webm", "mov", "mkv":
		return "video"
	case "mp3", "wav", "flac", "m4a", "ogg":
		return "audio"
	case "md", "markdown":
		return "markdown"
	}
	if !isText {
		return "binary"
	}
	return "text"
}

func looksBinary(data []byte) bool {
	if len(data) == 0 {
		return false
	}
	if strings.ContainsRune(string(data), 0) {
		return true
	}
	return !utf8.Valid(data)
}

func (w *Workspace) Read(pathValue string) (DocumentContent, error) {
	project, _, err := w.projectOfPath(pathValue)
	if err != nil {
		return DocumentContent{}, err
	}
	resolved, err := resolveInside(project.Path, pathValue, true)
	if err != nil {
		return DocumentContent{}, err
	}
	info, err := os.Stat(resolved)
	if err != nil {
		return DocumentContent{}, errors.New("文件不存在")
	}
	if info.IsDir() {
		return DocumentContent{}, errors.New("不能预览文件夹")
	}
	w.TouchProject(project.ID)
	ext := strings.TrimPrefix(strings.ToLower(filepath.Ext(resolved)), ".")
	contentType, knownBinary := binaryExtensions[ext]
	content := DocumentContent{
		Path:       resolved,
		Title:      filepath.Base(resolved),
		Size:       info.Size(),
		ModifiedAt: info.ModTime().Format(time.RFC3339),
	}
	if knownBinary {
		format := formatOf(resolved, ext, false)
		content.Format = format
		content.ResourceURL, err = w.media.AddFile(resolved, content.Title, contentType, time.Hour)
		if err != nil {
			return DocumentContent{}, err
		}
		w.recordRecent(project.ID, resolved)
		return content, nil
	}
	head := make([]byte, 8192)
	file, err := os.Open(resolved)
	if err != nil {
		return DocumentContent{}, err
	}
	n, _ := file.Read(head)
	_ = file.Close()
	if ext != "html" && ext != "htm" && looksBinary(head[:n]) {
		content.Format = "binary"
		content.ResourceURL, err = w.media.AddFile(resolved, content.Title, "application/octet-stream", time.Hour)
		if err != nil {
			return DocumentContent{}, err
		}
		content.Message = "二进制文件，可用系统默认应用打开"
		w.recordRecent(project.ID, resolved)
		return content, nil
	}
	if ext == "html" || ext == "htm" {
		content.Format = "html"
		content.ResourceURL, err = w.media.AddFile(resolved, content.Title, "text/html; charset=utf-8", time.Hour)
		if err != nil {
			return DocumentContent{}, err
		}
		w.recordRecent(project.ID, resolved)
		return content, nil
	}
	content.Format = formatOf(resolved, ext, true)
	content.ReadOnly = info.Size() > 1<<20
	var data []byte
	if content.ReadOnly {
		file, openErr := os.Open(resolved)
		if openErr != nil {
			return DocumentContent{}, openErr
		}
		data, _ = io.ReadAll(io.LimitReader(file, 1<<20))
		_ = file.Close()
		content.Message = "文件大于 1 MB，当前为只读预览"
	} else {
		data, err = os.ReadFile(resolved)
		if err != nil {
			return DocumentContent{}, err
		}
		content.Revision = hashBytes(data)
	}
	if len(data) >= 3 && data[0] == 0xEF && data[1] == 0xBB && data[2] == 0xBF {
		data = data[3:]
	}
	content.Text = string(data)
	content.AssetBase, _ = w.media.AddDir(filepath.Dir(resolved), time.Hour)
	w.recordRecent(project.ID, resolved)
	return content, nil
}

func sanitizeFilename(name, fallback string) string {
	name = strings.TrimSpace(name)
	if name == "" {
		name = fallback
	}
	replacer := strings.NewReplacer("<", "_", ">", "_", ":", "_", `"`, "_", "/", "_", `\`, "_", "|", "_", "?", "_", "*", "_")
	name = replacer.Replace(name)
	name = strings.Trim(name, ". ")
	if name == "" {
		name = fallback
	}
	runes := []rune(name)
	if len(runes) > 160 {
		name = string(runes[:160])
	}
	return name
}

func hashBytes(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

func (w *Workspace) recordRecent(projectID, pathValue string) {
	_, _ = w.db.Exec(`INSERT INTO recent_files (path, project_id, opened_at) VALUES (?, ?, ?)
		ON CONFLICT(path) DO UPDATE SET opened_at = excluded.opened_at, project_id = excluded.project_id`,
		pathValue, projectID, Now())
}

func (w *Workspace) Recent() ([]WorkspaceRecent, error) {
	rows, err := w.db.Query(`SELECT path, project_id, opened_at FROM recent_files ORDER BY opened_at DESC LIMIT 24`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []WorkspaceRecent{}
	for rows.Next() {
		var pathValue, openedAt string
		var projectID sql.NullString
		if err := rows.Scan(&pathValue, &projectID, &openedAt); err != nil {
			return nil, err
		}
		if _, err := os.Stat(pathValue); err != nil {
			continue
		}
		item := WorkspaceRecent{Path: pathValue, Name: filepath.Base(pathValue), OpenedAt: openedAt}
		if projectID.Valid {
			value := projectID.String
			item.ProjectID = &value
		}
		result = append(result, item)
	}
	return result, rows.Err()
}

func normalizeNewlines(text, newline string) string {
	text = strings.ReplaceAll(text, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\r", "\n")
	if newline == "\r\n" {
		text = strings.ReplaceAll(text, "\n", "\r\n")
	}
	return text
}

func (w *Workspace) Save(pathValue, text, revision string) (string, error) {
	project, _, err := w.projectOfPath(pathValue)
	if err != nil {
		return "", err
	}
	resolved, err := resolveInside(project.Path, pathValue, true)
	if err != nil {
		return "", err
	}
	info, err := os.Stat(resolved)
	if err != nil || info.IsDir() {
		return "", errors.New("文件不存在")
	}
	if info.Size() > 1<<20 {
		return "", errors.New("文件大于 1 MB，当前只能只读预览")
	}
	original, err := os.ReadFile(resolved)
	if err != nil {
		return "", err
	}
	if hashBytes(original) != revision {
		return "", errors.New("文件已被外部修改，请重新打开后再保存")
	}
	hasBOM := len(original) >= 3 && original[0] == 0xEF && original[1] == 0xBB && original[2] == 0xBF
	newline := "\n"
	if strings.Contains(string(original), "\r\n") {
		newline = "\r\n"
	}
	payload := normalizeNewlines(text, newline)
	final := []byte(payload)
	if hasBOM {
		final = append([]byte{0xEF, 0xBB, 0xBF}, final...)
	}
	temp, err := os.CreateTemp(filepath.Dir(resolved), ".sjtu-*.tmp")
	if err != nil {
		return "", err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if _, err := temp.Write(final); err != nil {
		_ = temp.Close()
		return "", err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return "", err
	}
	if err := temp.Close(); err != nil {
		return "", err
	}
	if err := os.Rename(tempName, resolved); err != nil {
		return "", err
	}
	w.recordRecent(project.ID, resolved)
	newRevision := hashBytes(final)
	if w.emit != nil {
		w.emit("workspace.changed", map[string]any{"path": resolved, "projectId": project.ID})
	}
	return newRevision, nil
}

func sanitizeName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" || name == "." || name == ".." {
		return "", errors.New("名称不能为空")
	}
	if strings.ContainsAny(name, `\/:*?"<>|`+"\x00") {
		return "", errors.New(`名称不能包含 \ / : * ? " < > |`)
	}
	if len([]rune(name)) > 160 {
		return "", errors.New("名称过长")
	}
	return name, nil
}

func (w *Workspace) Create(projectID, parentPath, name, kind, text string) (FileEntry, error) {
	project, err := w.Project(projectID)
	if err != nil {
		return FileEntry{}, err
	}
	name, err = sanitizeName(name)
	if err != nil {
		return FileEntry{}, err
	}
	parent := project.Path
	if strings.TrimSpace(parentPath) != "" {
		parent = parentPath
	}
	parent, err = resolveInside(project.Path, parent, true)
	if err != nil {
		return FileEntry{}, err
	}
	info, err := os.Stat(parent)
	if err != nil || !info.IsDir() {
		return FileEntry{}, errors.New("父目录不存在")
	}
	target := filepath.Join(parent, name)
	if _, err := os.Stat(target); err == nil {
		return FileEntry{}, errors.New("同名文件或文件夹已存在")
	}
	switch kind {
	case "folder":
		if err := os.Mkdir(target, 0o755); err != nil {
			return FileEntry{}, err
		}
	case "file":
		if err := os.WriteFile(target, []byte(text), 0o644); err != nil {
			return FileEntry{}, err
		}
	default:
		return FileEntry{}, errors.New("类型无效")
	}
	w.emitChange(target)
	return FileEntry{
		Path:       target,
		Name:       name,
		IsDir:      kind == "folder",
		Size:       int64(len(text)),
		ModifiedAt: Now(),
		Ext:        strings.TrimPrefix(strings.ToLower(filepath.Ext(name)), "."),
	}, nil
}

func (w *Workspace) Rename(pathValue, newName string) (string, error) {
	project, _, err := w.projectOfPath(pathValue)
	if err != nil {
		return "", err
	}
	resolved, err := resolveInside(project.Path, pathValue, true)
	if err != nil {
		return "", err
	}
	newName, err = sanitizeName(newName)
	if err != nil {
		return "", err
	}
	target := filepath.Join(filepath.Dir(resolved), newName)
	if _, err := os.Stat(target); err == nil {
		return "", errors.New("同名文件或文件夹已存在")
	}
	if err := os.Rename(resolved, target); err != nil {
		return "", err
	}
	_, _ = w.db.Exec(`DELETE FROM recent_files WHERE path = ?`, resolved)
	w.emitChange(target)
	w.emitChange(resolved)
	return target, nil
}

func (w *Workspace) Delete(pathValue string) error {
	project, rel, err := w.projectOfPath(pathValue)
	if err != nil {
		return err
	}
	resolved, err := resolveInside(project.Path, pathValue, true)
	if err != nil {
		return err
	}
	if rel == "." {
		return errors.New("请移除项目，而不是删除项目根目录")
	}
	if err := moveToRecycleBin(resolved); err != nil {
		return err
	}
	_, _ = w.db.Exec(`DELETE FROM recent_files WHERE path = ? OR path LIKE ?`, resolved, resolved+string(os.PathSeparator)+"%")
	w.emitChange(resolved)
	return nil
}

func (w *Workspace) SaveNoteImage(notePath, name, base64Data string) (string, error) {
	project, _, err := w.projectOfPath(notePath)
	if err != nil {
		return "", err
	}
	resolved, err := resolveInside(project.Path, notePath, true)
	if err != nil {
		return "", err
	}
	raw := base64Data
	if index := strings.Index(raw, "base64,"); index >= 0 {
		raw = raw[index+7:]
	}
	data, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return "", errors.New("图片数据无效")
	}
	if len(data) > 20<<20 {
		return "", errors.New("图片不能超过 20 MB")
	}
	name, err = sanitizeName(name)
	if err != nil {
		name = "image.png"
	}
	ext := filepath.Ext(name)
	if ext == "" {
		ext = ".png"
	}
	dir := filepath.Join(filepath.Dir(resolved), "assets")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	base := strings.TrimSuffix(filepath.Base(name), filepath.Ext(name))
	target := filepath.Join(dir, fmt.Sprintf("%s-%d%s", base, time.Now().UnixMilli(), ext))
	if err := os.WriteFile(target, data, 0o644); err != nil {
		return "", err
	}
	rel, err := filepath.Rel(filepath.Dir(resolved), target)
	if err != nil {
		return "", err
	}
	return filepath.ToSlash(rel), nil
}

func (w *Workspace) Tabs(projectID string) (string, error) {
	var payload string
	err := w.db.QueryRow(`SELECT payload FROM workspace_tabs WHERE project_id = ?`, projectID).Scan(&payload)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return payload, err
}

func (w *Workspace) SaveTabs(projectID, payload string) error {
	if len(payload) > 200_000 {
		return errors.New("标签数据过大")
	}
	_, err := w.db.Exec(`INSERT INTO workspace_tabs (project_id, payload, updated_at) VALUES (?, ?, ?)
		ON CONFLICT(project_id) DO UPDATE SET payload = excluded.payload, updated_at = excluded.updated_at`,
		projectID, payload, Now())
	return err
}

func (w *Workspace) AssetBase(projectID string) (string, error) {
	project, err := w.Project(projectID)
	if err != nil {
		return "", err
	}
	return w.media.AddDir(project.Path, time.Hour)
}

func (w *Workspace) GitBranch(projectID string) (string, error) {
	project, err := w.Project(projectID)
	if err != nil {
		return "", err
	}
	return gitOutput(project.Path, "rev-parse", "--abbrev-ref", "HEAD")
}

func (w *Workspace) GitBranches(projectID string) ([]string, error) {
	project, err := w.Project(projectID)
	if err != nil {
		return nil, err
	}
	output, err := gitOutput(project.Path, "branch", "--format=%(refname:short)")
	if err != nil {
		return []string{}, nil
	}
	lines := strings.Split(strings.TrimSpace(output), "\n")
	result := []string{}
	for _, line := range lines {
		line = strings.TrimSpace(strings.TrimPrefix(line, "*"))
		if line != "" {
			result = append(result, line)
		}
	}
	return result, nil
}

func (w *Workspace) GitSwitch(projectID, branch string) error {
	project, err := w.Project(projectID)
	if err != nil {
		return err
	}
	if strings.TrimSpace(branch) == "" || strings.ContainsAny(branch, " \t\r\n;|&$`") {
		return errors.New("分支名无效")
	}
	_, err = runGit(project.Path, "switch", branch)
	return err
}

func (w *Workspace) emitChange(pathValue string) {
	if w.emit == nil {
		return
	}
	w.emit("workspace.changed", map[string]any{"path": pathValue})
}

func (w *Workspace) Register(router *Router) {
	router.Handle("workspace.state", func([]json.RawMessage) (any, error) {
		return w.State()
	})
	router.Handle("workspace.importProject", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		name, _, _ := DecodeOptionalArg[string](args, 1)
		return w.RegisterProject(pathValue, name)
	})
	router.Handle("workspace.removeProject", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return true, w.RemoveProject(id)
	})
	router.Handle("workspace.renameProject", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		name, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return w.RenameProject(id, name)
	})
	router.Handle("workspace.tree", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		pathValue, _, _ := DecodeOptionalArg[string](args, 1)
		return w.List(projectID, pathValue)
	})
	router.Handle("workspace.read", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return w.Read(pathValue)
	})
	router.Handle("workspace.save", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		text, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		revision, err := DecodeArg[string](args, 2)
		if err != nil {
			return nil, err
		}
		return w.Save(pathValue, text, revision)
	})
	router.Handle("workspace.saveNoteImage", func(args []json.RawMessage) (any, error) {
		notePath, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		name, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		data, err := DecodeArg[string](args, 2)
		if err != nil {
			return nil, err
		}
		return w.SaveNoteImage(notePath, name, data)
	})
	router.Handle("workspace.create", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		parent, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		name, err := DecodeArg[string](args, 2)
		if err != nil {
			return nil, err
		}
		kind, err := DecodeArg[string](args, 3)
		if err != nil {
			return nil, err
		}
		text, _, _ := DecodeOptionalArg[string](args, 4)
		return w.Create(projectID, parent, name, kind, text)
	})
	router.Handle("workspace.rename", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		name, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return w.Rename(pathValue, name)
	})
	router.Handle("workspace.delete", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return true, w.Delete(pathValue)
	})
	router.Handle("workspace.recent", func([]json.RawMessage) (any, error) {
		return w.Recent()
	})
	router.Handle("workspace.tabs", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return w.Tabs(projectID)
	})
	router.Handle("workspace.saveTabs", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		payload, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return true, w.SaveTabs(projectID, payload)
	})
	router.Handle("workspace.assetBase", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return w.AssetBase(projectID)
	})
	router.Handle("workspace.gitBranch", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		branch, err := w.GitBranch(projectID)
		if err != nil {
			return nil, nil
		}
		return branch, nil
	})
	router.Handle("workspace.gitBranches", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return w.GitBranches(projectID)
	})
	router.Handle("workspace.gitSwitch", func(args []json.RawMessage) (any, error) {
		projectID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		branch, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return true, w.GitSwitch(projectID, branch)
	})
	w.terminals.Register(router)
}

func gitOutput(dir string, args ...string) (string, error) {
	output, err := runGit(dir, args...)
	return strings.TrimSpace(output), err
}

func runGit(dir string, args ...string) (string, error) {
	command := newCommand("git", args...)
	command.Dir = dir
	output, err := command.CombinedOutput()
	text := strings.TrimSpace(string(output))
	if err != nil {
		if text == "" {
			text = err.Error()
		}
		return "", errors.New(text)
	}
	return text, nil
}

func newCommand(name string, args ...string) *exec.Cmd {
	command := exec.Command(name, args...)
	hideConsoleWindow(command)
	return command
}
