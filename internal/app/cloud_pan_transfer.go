package app

// 云盘传输队列。下载走 COS 预签名地址的 Range 续传；上传按 coFile 的
// multipart 分片协议逐片续传，confirmKey 存在本地，暂停或重启后可接着传。

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

const (
	cloudTransferChunk = 4 << 20
	cloudTransferSlots = 2
)

var ErrCloudTransferCanceled = errors.New("传输已取消")

type CloudTransfer struct {
	ID         string `json:"id"`
	Kind       string `json:"kind"`
	Name       string `json:"name"`
	LocalPath  string `json:"localPath"`
	RemotePath string `json:"remotePath"`
	SpaceID    string `json:"spaceId"`
	Size       int64  `json:"size"`
	Sent       int64  `json:"sent"`
	Status     string `json:"status"`
	Error      string `json:"error"`
	ConfirmKey string `json:"confirmKey,omitempty"`
	CreatedAt  string `json:"createdAt"`
	UpdatedAt  string `json:"updatedAt"`
}

type transferQueueItem struct {
	kind  string // upload | download
	items []transferItem
	dir   string
}

type transferItem struct {
	Path string
	Name string
	id   string
}

var transferSeq struct {
	sync.Mutex
	value int64
}

func nextTransferID() string {
	transferSeq.Lock()
	defer transferSeq.Unlock()
	transferSeq.value++
	return fmt.Sprintf("%d-%d", time.Now().UnixNano(), transferSeq.value)
}

// StartTransfers wires the queue to the app context and recovers rows that were
// running when the process exited.
func (p *CloudPan) StartTransfers(ctx context.Context) {
	p.transferCtx = ctx
	p.transferQueue = make(chan transferQueueItem, 64)
	p.transferCancels = make(map[string]context.CancelFunc)
	_, _ = p.db.Exec(`UPDATE cloud_transfers SET status = 'paused', updated_at = ? WHERE status = 'running'`, Now())
	for worker := 0; worker < cloudTransferSlots; worker++ {
		go p.transferWorker(ctx)
	}
}

func (p *CloudPan) transferWorker(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case batch, ok := <-p.transferQueue:
			if !ok {
				return
			}
			p.runTransferBatch(ctx, batch)
		}
	}
}

func (p *CloudPan) runTransferBatch(ctx context.Context, batch transferQueueItem) {
	for _, item := range batch.items {
		select {
		case <-ctx.Done():
			return
		default:
		}
		transfer, err := p.transferByID(item.id)
		if err != nil || transfer.Status == "canceled" || transfer.Status == "paused" {
			continue
		}
		p.runTransfer(ctx, transfer)
	}
}

func (p *CloudPan) transferByID(id string) (CloudTransfer, error) {
	var transfer CloudTransfer
	err := p.db.QueryRow(`SELECT id, kind, name, local_path, remote_path, space_id, size, sent, status, error, confirm_key, created_at, updated_at
		FROM cloud_transfers WHERE id = ?`, id).
		Scan(&transfer.ID, &transfer.Kind, &transfer.Name, &transfer.LocalPath, &transfer.RemotePath,
			&transfer.SpaceID, &transfer.Size, &transfer.Sent, &transfer.Status, &transfer.Error,
			&transfer.ConfirmKey, &transfer.CreatedAt, &transfer.UpdatedAt)
	return transfer, err
}

func (p *CloudPan) ListTransfers() ([]CloudTransfer, error) {
	rows, err := p.db.Query(`SELECT id, kind, name, local_path, remote_path, space_id, size, sent, status, error, confirm_key, created_at, updated_at
		FROM cloud_transfers ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := make([]CloudTransfer, 0, 16)
	for rows.Next() {
		var transfer CloudTransfer
		if err := rows.Scan(&transfer.ID, &transfer.Kind, &transfer.Name, &transfer.LocalPath,
			&transfer.RemotePath, &transfer.SpaceID, &transfer.Size, &transfer.Sent,
			&transfer.Status, &transfer.Error, &transfer.ConfirmKey, &transfer.CreatedAt,
			&transfer.UpdatedAt); err != nil {
			return nil, err
		}
		list = append(list, transfer)
	}
	return list, rows.Err()
}

func (p *CloudPan) saveTransfer(transfer CloudTransfer) error {
	transfer.UpdatedAt = Now()
	if transfer.CreatedAt == "" {
		transfer.CreatedAt = transfer.UpdatedAt
	}
	_, err := p.db.Exec(`INSERT INTO cloud_transfers
		(id, kind, name, local_path, remote_path, space_id, size, sent, status, error, confirm_key, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT(id) DO UPDATE SET
			name = excluded.name, local_path = excluded.local_path, remote_path = excluded.remote_path,
			space_id = excluded.space_id, size = excluded.size, sent = excluded.sent,
			status = excluded.status, error = excluded.error, confirm_key = excluded.confirm_key,
			updated_at = excluded.updated_at`,
		transfer.ID, transfer.Kind, transfer.Name, transfer.LocalPath, transfer.RemotePath,
		transfer.SpaceID, transfer.Size, transfer.Sent, transfer.Status, transfer.Error,
		transfer.ConfirmKey, transfer.CreatedAt, transfer.UpdatedAt)
	p.emitTransfer(transfer)
	return err
}

func (p *CloudPan) updateTransfer(id string, patch func(*CloudTransfer)) {
	transfer, err := p.transferByID(id)
	if err != nil {
		return
	}
	patch(&transfer)
	_ = p.saveTransfer(transfer)
}

func (p *CloudPan) emitTransfer(transfer CloudTransfer) {
	p.emit("cloudPan.transfer.changed", transfer)
}

func (p *CloudPan) hasTransfer(id string) bool {
	var count int
	if err := p.db.QueryRow(`SELECT COUNT(*) FROM cloud_transfers WHERE id = ?`, id).Scan(&count); err != nil {
		return false
	}
	return count > 0
}

func (p *CloudPan) enqueueTransfer(batch transferQueueItem) {
	select {
	case p.transferQueue <- batch:
	default:
		go func() { p.transferQueue <- batch }()
	}
}

func (p *CloudPan) EnqueueUploads(paths []string, dir, spaceID string) ([]CloudTransfer, error) {
	created := make([]CloudTransfer, 0, len(paths))
	batch := transferQueueItem{kind: "upload", dir: dir}
	for _, localPath := range paths {
		info, err := os.Stat(localPath)
		if err != nil || info.IsDir() {
			continue
		}
		name := filepath.Base(localPath)
		remote := name
		if clean := strings.Trim(dir, "/"); clean != "" {
			remote = clean + "/" + name
		}
		transfer := CloudTransfer{
			ID: nextTransferID(), Kind: "upload", Name: name, LocalPath: localPath,
			RemotePath: remote, SpaceID: spaceID, Size: info.Size(), Status: "queued",
		}
		if err := p.saveTransfer(transfer); err != nil {
			continue
		}
		created = append(created, transfer)
		batch.items = append(batch.items, transferItem{Path: localPath, Name: name, id: transfer.ID})
	}
	if len(batch.items) == 0 {
		return nil, errors.New("没有可上传的文件")
	}
	p.enqueueTransfer(batch)
	return created, nil
}

// EnqueueUploadFolder walks a local directory and recreates its tree in the cloud.
func (p *CloudPan) EnqueueUploadFolder(localDir, dir, spaceID string) (int, error) {
	base := filepath.Base(filepath.Clean(localDir))
	clean := strings.Trim(dir, "/")
	root := base
	if clean != "" {
		root = clean + "/" + base
	}
	batch := transferQueueItem{kind: "upload", dir: root}
	count := 0
	err := filepath.WalkDir(localDir, func(current string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return nil
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return nil
		}
		rel, err := filepath.Rel(localDir, current)
		if err != nil {
			return nil
		}
		remote := root + "/" + filepath.ToSlash(rel)
		transfer := CloudTransfer{
			ID: nextTransferID(), Kind: "upload", Name: entry.Name(), LocalPath: current,
			RemotePath: remote, SpaceID: spaceID, Size: info.Size(), Status: "queued",
		}
		if err := p.saveTransfer(transfer); err != nil {
			return nil
		}
		batch.items = append(batch.items, transferItem{Path: current, Name: entry.Name(), id: transfer.ID})
		count++
		return nil
	})
	if err != nil || count == 0 {
		return 0, errors.New("文件夹里没有可上传的文件")
	}
	p.enqueueTransfer(batch)
	return count, nil
}

func (p *CloudPan) EnqueueDownloads(items []transferItem, dir, spaceID string) ([]CloudTransfer, error) {
	if dir == "" {
		return nil, errors.New("请选择保存位置")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	created := make([]CloudTransfer, 0, len(items))
	batch := transferQueueItem{kind: "download"}
	for _, item := range items {
		name := safeTransferName(item.Name, "")
		if name == "" {
			name = safeTransferName(path.Base(item.Path), "download")
		}
		transfer := CloudTransfer{
			ID: nextTransferID(), Kind: "download", Name: name,
			LocalPath: filepath.Join(dir, name), RemotePath: item.Path,
			SpaceID: spaceID, Status: "queued",
		}
		if err := p.saveTransfer(transfer); err != nil {
			continue
		}
		created = append(created, transfer)
		batch.items = append(batch.items, transferItem{Path: item.Path, Name: name, id: transfer.ID})
	}
	if len(batch.items) == 0 {
		return nil, errors.New("没有可下载的文件")
	}
	p.enqueueTransfer(batch)
	return created, nil
}

func (p *CloudPan) PauseTransfer(id string) error {
	p.transferMu.Lock()
	entry := p.transferCancels[id]
	p.transferMu.Unlock()
	if entry != nil {
		entry()
	}
	p.updateTransfer(id, func(transfer *CloudTransfer) {
		if transfer.Status == "queued" || transfer.Status == "running" {
			transfer.Status = "paused"
		}
	})
	return nil
}

func (p *CloudPan) ResumeTransfer(id string) error {
	transfer, err := p.transferByID(id)
	if err != nil {
		return errors.New("找不到这条传输")
	}
	if transfer.Status != "paused" && transfer.Status != "failed" && transfer.Status != "queued" {
		return errors.New("当前状态不能继续")
	}
	if transfer.Status == "failed" {
		transfer.Sent = 0
	}
	transfer.Status = "queued"
	transfer.Error = ""
	if err := p.saveTransfer(transfer); err != nil {
		return err
	}
	p.enqueueTransfer(transferQueueItem{
		kind:  transfer.Kind,
		items: []transferItem{{Path: transfer.RemotePath, Name: transfer.Name, id: transfer.ID}},
	})
	return nil
}

func (p *CloudPan) PauseAllTransfers() error {
	transfers, err := p.ListTransfers()
	if err != nil {
		return err
	}
	for _, transfer := range transfers {
		if transfer.Status == "running" || transfer.Status == "queued" {
			_ = p.PauseTransfer(transfer.ID)
		}
	}
	return nil
}

func (p *CloudPan) ResumeAllTransfers() error {
	transfers, err := p.ListTransfers()
	if err != nil {
		return err
	}
	for _, transfer := range transfers {
		if transfer.Status == "paused" || transfer.Status == "failed" {
			_ = p.ResumeTransfer(transfer.ID)
		}
	}
	return nil
}

func (p *CloudPan) CancelTransfer(id string, removeFile bool) error {
	p.transferMu.Lock()
	entry := p.transferCancels[id]
	p.transferMu.Unlock()
	if entry != nil {
		entry()
	}
	transfer, err := p.transferByID(id)
	if err != nil {
		return nil
	}
	if removeFile {
		_ = os.Remove(transfer.LocalPath + ".part")
	}
	transfer.Status = "canceled"
	transfer.Error = ""
	_ = p.saveTransfer(transfer)
	return nil
}

func (p *CloudPan) RetryTransfer(id string) error {
	return p.ResumeTransfer(id)
}

func (p *CloudPan) ClearTransfer(id string, removeFile bool) error {
	p.transferMu.Lock()
	cancel := p.transferCancels[id]
	p.transferMu.Unlock()
	if cancel != nil {
		cancel()
	}
	transfer, err := p.transferByID(id)
	if err != nil {
		return nil
	}
	if removeFile {
		_ = os.Remove(transfer.LocalPath + ".part")
	}
	_, err = p.db.Exec(`DELETE FROM cloud_transfers WHERE id = ?`, id)
	p.emit("cloudPan.transfer.removed", id)
	return err
}

func (p *CloudPan) ClearFinishedTransfers() error {
	_, err := p.db.Exec(`DELETE FROM cloud_transfers WHERE status IN ('done', 'canceled')`)
	transfers, _ := p.ListTransfers()
	p.emit("cloudPan.transfers", transfers)
	return err
}

func (p *CloudPan) runTransfer(parent context.Context, transfer CloudTransfer) {
	p.transferMu.Lock()
	if p.transferCancels == nil {
		p.transferCancels = make(map[string]context.CancelFunc)
	}
	if _, running := p.transferCancels[transfer.ID]; running {
		p.transferMu.Unlock()
		return
	}
	ctx, cancel := context.WithCancel(parent)
	p.transferCancels[transfer.ID] = cancel
	p.transferMu.Unlock()
	defer func() {
		cancel()
		p.transferMu.Lock()
		delete(p.transferCancels, transfer.ID)
		p.transferMu.Unlock()
	}()

	transfer.Status = "running"
	transfer.Error = ""
	if err := p.saveTransfer(transfer); err != nil {
		return
	}

	var err error
	if transfer.Kind == "download" {
		err = p.downloadTransfer(ctx, transfer)
	} else {
		err = p.uploadTransfer(ctx, transfer)
	}
	if err == nil {
		p.updateTransfer(transfer.ID, func(value *CloudTransfer) {
			value.Status = "done"
			value.Sent = value.Size
			value.Error = ""
		})
		return
	}
	current, loadErr := p.transferByID(transfer.ID)
	if loadErr != nil {
		return
	}
	if current.Status == "paused" || current.Status == "canceled" {
		return
	}
	if errors.Is(err, context.Canceled) {
		p.updateTransfer(transfer.ID, func(value *CloudTransfer) {
			if value.Status == "running" {
				value.Status = "paused"
			}
		})
		return
	}
	p.updateTransfer(transfer.ID, func(value *CloudTransfer) {
		value.Status = "failed"
		value.Error = err.Error()
	})
}

func (p *CloudPan) downloadTransfer(ctx context.Context, transfer CloudTransfer) error {
	if info, err := os.Stat(transfer.LocalPath); err == nil && info.Size() > 0 && (transfer.Size == 0 || info.Size() == transfer.Size) {
		return nil
	}
	link, size, err := p.downloadURL(ctx, transfer.RemotePath, transfer.SpaceID)
	if err != nil {
		return err
	}
	if size > 0 && transfer.Size != size {
		transfer.Size = size
		_ = p.saveTransfer(transfer)
	}
	partPath := transfer.LocalPath + ".part"
	if err := os.MkdirAll(filepath.Dir(transfer.LocalPath), 0o755); err != nil {
		return err
	}
	offset := int64(0)
	if info, err := os.Stat(partPath); err == nil {
		offset = info.Size()
	}
	if size > 0 && offset > size {
		_ = os.Remove(partPath)
		offset = 0
	}
	if size > 0 && offset == size {
		return os.Rename(partPath, transfer.LocalPath)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return err
	}
	if offset > 0 {
		request.Header.Set("Range", fmt.Sprintf("bytes=%d-", offset))
	}
	response, err := cloudDownloadClient.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return errors.New("下载失败：无法连接对象存储")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusPartialContent {
		return fmt.Errorf("下载失败：HTTP %d", response.StatusCode)
	}
	if response.StatusCode == http.StatusOK && offset > 0 {
		offset = 0
	}
	flags := os.O_CREATE | os.O_WRONLY
	if offset > 0 {
		flags |= os.O_APPEND
	} else {
		flags |= os.O_TRUNC
	}
	file, err := os.OpenFile(partPath, flags, 0o644)
	if err != nil {
		return err
	}
	written := offset
	lastSave := time.Now()
	reader := &cloudTransferProgressReader{
		reader: response.Body,
		total:  size,
		start:  offset,
		report: func(sent int64) {
			written = sent
			if time.Since(lastSave) > 600*time.Millisecond {
				lastSave = time.Now()
				p.updateTransferProgress(transfer.ID, sent, size)
			}
		},
	}
	_, copyErr := io.Copy(file, reader)
	closeErr := file.Close()
	p.updateTransferProgress(transfer.ID, written, size)
	if copyErr != nil {
		return copyErr
	}
	if closeErr != nil {
		return closeErr
	}
	if size > 0 {
		if info, err := os.Stat(partPath); err != nil || info.Size() != size {
			return errors.New("下载不完整，可继续传输")
		}
	}
	return os.Rename(partPath, transfer.LocalPath)
}

func (p *CloudPan) updateTransferProgress(id string, sent, total int64) {
	transfer, err := p.transferByID(id)
	if err != nil {
		return
	}
	if total > 0 {
		transfer.Size = total
	}
	transfer.Sent = sent
	transfer.UpdatedAt = Now()
	_, _ = p.db.Exec(`UPDATE cloud_transfers SET sent = ?, size = ?, updated_at = ? WHERE id = ?`,
		sent, transfer.Size, transfer.UpdatedAt, id)
	p.emitTransfer(transfer)
}

type cloudTransferProgressReader struct {
	reader io.Reader
	total  int64
	start  int64
	sent   int64
	report func(sent int64)
}

func (r *cloudTransferProgressReader) Read(buffer []byte) (int, error) {
	n, err := r.reader.Read(buffer)
	if n > 0 {
		r.sent += int64(n)
		if r.report != nil {
			r.report(r.start + r.sent)
		}
	}
	return n, err
}

// downloadURL resolves the presigned COS address and size for a file.
func (p *CloudPan) downloadURL(ctx context.Context, filePath, spaceID string) (string, int64, error) {
	token, err := p.accessToken(ctx, spaceID)
	if err != nil {
		return "", 0, err
	}
	clean := strings.Trim(filePath, "/")
	if clean == "" {
		return "", 0, errors.New("缺少文件路径")
	}
	values := url.Values{}
	values.Set("access_token", token.Token)
	values.Set("content_disposition", "attachment")
	endpoint := fmt.Sprintf("%s/api/v1/file/%s/%s/%s?info&%s", cloudPanOrigin,
		url.PathEscape(token.LibraryID), url.PathEscape(token.SpaceID), escapePanPath(clean), values.Encode())
	var info struct {
		CosURL string   `json:"cosUrl"`
		Size   panInt64 `json:"size"`
	}
	if err := p.do(ctx, http.MethodGet, endpoint, "", nil, &info); err != nil {
		return "", 0, err
	}
	if err := validateCloudPanMediaURL(info.CosURL); err != nil {
		return "", 0, err
	}
	return info.CosURL, int64(info.Size), nil
}

func (p *CloudPan) uploadTransfer(ctx context.Context, transfer CloudTransfer) error {
	info, err := os.Stat(transfer.LocalPath)
	if err != nil || info.IsDir() {
		return errors.New("本地文件不存在或不可读")
	}
	if transfer.Size != info.Size() {
		transfer.Size = info.Size()
		transfer.ConfirmKey = ""
		transfer.Sent = 0
		_ = p.saveTransfer(transfer)
	}
	token, err := p.accessToken(ctx, transfer.SpaceID)
	if err != nil {
		return err
	}
	if parent := path.Dir(transfer.RemotePath); parent != "." && parent != "/" && parent != "" {
		if err := p.ensureRemoteDirectory(ctx, token, parent); err != nil {
			return err
		}
	}
	chunkCount := int((info.Size() + cloudTransferChunk - 1) / cloudTransferChunk)
	if chunkCount < 1 {
		chunkCount = 1
	}

	confirmKey := transfer.ConfirmKey
	uploaded := make(map[int]bool)
	renewed := (*chunkSession)(nil)
	if confirmKey != "" {
		session, err := p.chunkUploadInfo(ctx, token, confirmKey)
		if err == nil {
			if session.Confirmed {
				return nil
			}
			for _, part := range session.Parts {
				if part.PartNumber > 0 && part.Size > 0 {
					uploaded[part.PartNumber] = true
				}
			}
		} else {
			confirmKey = ""
		}
	}
	if confirmKey == "" {
		key, err := p.startChunkUpload(ctx, token, transfer.RemotePath, chunkCount)
		if err != nil {
			return err
		}
		confirmKey = key
		transfer.ConfirmKey = key
		_ = p.saveTransfer(transfer)
	}

	file, err := os.Open(transfer.LocalPath)
	if err != nil {
		return errors.New("无法打开本地文件")
	}
	defer file.Close()

	done := int64(len(uploaded)) * cloudTransferChunk
	if done > info.Size() {
		done = info.Size()
	}
	p.updateTransferProgress(transfer.ID, done, info.Size())

	renewParts := func(from int) error {
		remaining := make([]int, 0, 50)
		for index := from; index <= chunkCount && len(remaining) < 50; index++ {
			if !uploaded[index] {
				remaining = append(remaining, index)
			}
		}
		if len(remaining) == 0 {
			return nil
		}
		session, err := p.renewChunkUpload(ctx, token, confirmKey, remaining)
		if err != nil {
			return err
		}
		renewed = session
		return nil
	}

	for part := 1; part <= chunkCount; part++ {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if uploaded[part] {
			continue
		}
		expired := renewed == nil || renewed.Expiration.Before(time.Now().Add(30*time.Second))
		if expired || renewed.Parts[strconv.Itoa(part)].Headers == nil {
			if err := renewParts(part); err != nil {
				return err
			}
			if renewed == nil || renewed.Parts[strconv.Itoa(part)].Headers == nil {
				return fmt.Errorf("第 %d 片没有拿到上传凭证", part)
			}
		}
		offset := int64(part-1) * cloudTransferChunk
		length := int64(cloudTransferChunk)
		if remaining := info.Size() - offset; remaining < length {
			length = remaining
		}
		buffer := make([]byte, length)
		if _, err := file.ReadAt(buffer, offset); err != nil && err != io.EOF {
			return err
		}
		if err := p.putChunk(ctx, renewed, part, buffer); err != nil {
			return err
		}
		uploaded[part] = true
		sent := int64(len(uploaded)) * cloudTransferChunk
		if sent > info.Size() {
			sent = info.Size()
		}
		p.updateTransferProgress(transfer.ID, sent, info.Size())
	}
	if err := p.confirmChunkUpload(ctx, token, confirmKey); err != nil {
		return err
	}
	_ = p.refreshQuota(ctx)
	return nil
}

func (p *CloudPan) fileAPIURL(token cloudPanAccessToken, filePath string, extra url.Values, bare ...string) string {
	values := url.Values{}
	values.Set("access_token", token.Token)
	for key, list := range extra {
		for _, value := range list {
			values.Set(key, value)
		}
	}
	query := values.Encode()
	for _, key := range bare {
		if query != "" {
			query += "&"
		}
		query += key
	}
	return fmt.Sprintf("%s/api/v1/file/%s/%s/%s?%s", cloudPanOrigin,
		url.PathEscape(token.LibraryID), url.PathEscape(token.SpaceID),
		escapePanPath(strings.Trim(filePath, "/")), query)
}

func (p *CloudPan) ensureRemoteDirectory(ctx context.Context, token cloudPanAccessToken, dir string) error {
	segments := strings.Split(strings.Trim(dir, "/"), "/")
	prefix := ""
	for _, segment := range segments {
		if segment == "" {
			continue
		}
		if prefix == "" {
			prefix = segment
		} else {
			prefix += "/" + segment
		}
		values := url.Values{}
		values.Set("access_token", token.Token)
		values.Set("conflict_resolution_strategy", "ask")
		endpoint := fmt.Sprintf("%s/api/v1/directory/%s/%s/%s?%s", cloudPanOrigin,
			url.PathEscape(token.LibraryID), url.PathEscape(token.SpaceID), escapePanPath(prefix), values.Encode())
		var result struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		}
		err := p.do(ctx, http.MethodPut, endpoint, "", nil, &result)
		if err == nil {
			continue
		}
		if strings.Contains(err.Error(), "SameNameDirectoryOrFileExists") || result.Code == "SameNameDirectoryOrFileExists" {
			continue
		}
		return err
	}
	return nil
}

func (p *CloudPan) startChunkUpload(ctx context.Context, token cloudPanAccessToken, remotePath string, chunkCount int) (string, error) {
	rangeCount := chunkCount
	if rangeCount > 50 {
		rangeCount = 50
	}
	numbers := make([]int, 0, rangeCount)
	for index := 1; index <= rangeCount; index++ {
		numbers = append(numbers, index)
	}
	body, _ := json.Marshal(map[string]any{"partNumberRange": numbers})
	extra := url.Values{}
	extra.Set("conflict_resolution_strategy", "rename")
	var result struct {
		ConfirmKey string `json:"confirmKey"`
	}
	if err := p.do(ctx, http.MethodPost, p.fileAPIURL(token, remotePath, extra, "multipart"), string(body),
		map[string]string{"Content-Type": "application/json"}, &result); err != nil {
		return "", err
	}
	if result.ConfirmKey == "" {
		return "", errors.New("云盘没有返回分片上传会话")
	}
	return result.ConfirmKey, nil
}

type chunkPart struct {
	PartNumber int               `json:"PartNumber"`
	Size       int64             `json:"Size"`
	Headers    map[string]string `json:"headers"`
}

type chunkSession struct {
	Confirmed  bool                 `json:"confirmed"`
	Domain     string               `json:"domain"`
	Path       string               `json:"path"`
	UploadID   string               `json:"uploadId"`
	Expiration time.Time            `json:"-"`
	ExpiresRaw string               `json:"expiration"`
	Parts      map[string]chunkPart `json:"parts"`
}

func (s *chunkSession) normalizeExpiration() {
	if s.ExpiresRaw == "" {
		return
	}
	if parsed, err := time.Parse(time.RFC3339, s.ExpiresRaw); err == nil {
		s.Expiration = parsed
	}
}

func (p *CloudPan) chunkUploadInfo(ctx context.Context, token cloudPanAccessToken, confirmKey string) (chunkSession, error) {
	extra := url.Values{}
	extra.Set("no_upload_part_info", "1")
	var session chunkSession
	if err := p.do(ctx, http.MethodGet, p.fileAPIURL(token, confirmKey, extra, "upload"), "", nil, &session); err != nil {
		return chunkSession{}, err
	}
	session.normalizeExpiration()
	return session, nil
}

func (p *CloudPan) renewChunkUpload(ctx context.Context, token cloudPanAccessToken, confirmKey string, parts []int) (*chunkSession, error) {
	body, _ := json.Marshal(map[string]any{"partNumberRange": parts})
	var session chunkSession
	if err := p.do(ctx, http.MethodPost, p.fileAPIURL(token, confirmKey, nil, "renew"), string(body),
		map[string]string{"Content-Type": "application/json"}, &session); err != nil {
		return nil, err
	}
	session.normalizeExpiration()
	return &session, nil
}

var (
	cloudDownloadClient = http.DefaultClient
	cloudUploadClient   = &http.Client{Timeout: 0}
)

func (p *CloudPan) putChunk(ctx context.Context, session *chunkSession, part int, data []byte) error {
	if session == nil || session.Domain == "" || session.Path == "" {
		return errors.New("上传凭证缺失")
	}
	partInfo, ok := session.Parts[strconv.Itoa(part)]
	if !ok {
		return fmt.Errorf("第 %d 片缺少签名头", part)
	}
	base := session.Domain
	if !strings.Contains(base, "://") {
		base = "https://" + base
	}
	lower := strings.ToLower(base)
	if !strings.HasPrefix(lower, "https://") &&
		!strings.HasPrefix(lower, "http://127.0.0.1") && !strings.HasPrefix(lower, "http://localhost") {
		return errors.New("上传地址不受信任")
	}
	link := fmt.Sprintf("%s%s?uploadId=%s&partNumber=%d", strings.TrimRight(base, "/"), session.Path,
		url.QueryEscape(session.UploadID), part)
	request, err := http.NewRequestWithContext(ctx, http.MethodPut, link, bytes.NewReader(data))
	if err != nil {
		return err
	}
	for key, value := range partInfo.Headers {
		lower := strings.ToLower(key)
		if lower == "host" || lower == "content-length" {
			continue
		}
		request.Header.Set(key, value)
	}
	request.ContentLength = int64(len(data))
	response, err := cloudUploadClient.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		return errors.New("上传分片失败：无法连接对象存储")
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<20))
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("上传分片失败：HTTP %d", response.StatusCode)
	}
	return nil
}

func (p *CloudPan) confirmChunkUpload(ctx context.Context, token cloudPanAccessToken, confirmKey string) error {
	extra := url.Values{}
	extra.Set("conflict_resolution_strategy", "overwrite")
	if err := p.do(ctx, http.MethodPost, p.fileAPIURL(token, confirmKey, extra, "confirm"), "", nil, nil); err != nil {
		return err
	}
	return nil
}

func safeTransferName(name, fallback string) string {
	cleaned := strings.TrimSpace(strings.ReplaceAll(name, "\\", "/"))
	cleaned = path.Base(cleaned)
	if cleaned == "" || cleaned == "." || cleaned == "/" || cleaned == ".." {
		return fallback
	}
	return cleaned
}

// transferItemsFromArgs accepts [{path,name}] payloads from the renderer.
func transferItemsFromArgs(raw json.RawMessage) []transferItem {
	var items []struct {
		Path string `json:"path"`
		Name string `json:"name"`
	}
	if err := json.Unmarshal(raw, &items); err != nil {
		return nil
	}
	result := make([]transferItem, 0, len(items))
	for _, item := range items {
		if strings.TrimSpace(item.Path) == "" {
			continue
		}
		result = append(result, transferItem{Path: item.Path, Name: item.Name})
	}
	return result
}

func (p *CloudPan) registerTransferRoutes(router *Router) {
	router.Handle("cloudPan.transfers", func([]json.RawMessage) (any, error) {
		return p.ListTransfers()
	})
	router.Handle("cloudPan.transfer.upload", func(args []json.RawMessage) (any, error) {
		paths, _, _ := DecodeOptionalArg[[]string](args, 0)
		dir, _, _ := DecodeOptionalArg[string](args, 1)
		spaceID, _, _ := DecodeOptionalArg[string](args, 2)
		return p.EnqueueUploads(paths, dir, spaceID)
	})
	router.Handle("cloudPan.transfer.uploadFolder", func(args []json.RawMessage) (any, error) {
		localDir, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		dir, _, _ := DecodeOptionalArg[string](args, 1)
		spaceID, _, _ := DecodeOptionalArg[string](args, 2)
		count, err := p.EnqueueUploadFolder(localDir, dir, spaceID)
		if err != nil {
			return nil, err
		}
		return map[string]any{"count": count}, nil
	})
	router.Handle("cloudPan.transfer.download", func(args []json.RawMessage) (any, error) {
		if len(args) < 1 {
			return nil, errors.New("缺少下载项")
		}
		items := transferItemsFromArgs(args[0])
		dir, _, _ := DecodeOptionalArg[string](args, 1)
		spaceID, _, _ := DecodeOptionalArg[string](args, 2)
		return p.EnqueueDownloads(items, dir, spaceID)
	})
	router.Handle("cloudPan.transfer.pause", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, p.PauseTransfer(id)
	})
	router.Handle("cloudPan.transfer.resume", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, p.ResumeTransfer(id)
	})
	router.Handle("cloudPan.transfer.pauseAll", func([]json.RawMessage) (any, error) {
		return map[string]any{"ok": true}, p.PauseAllTransfers()
	})
	router.Handle("cloudPan.transfer.resumeAll", func([]json.RawMessage) (any, error) {
		return map[string]any{"ok": true}, p.ResumeAllTransfers()
	})
	router.Handle("cloudPan.transfer.cancel", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		removeFile, _, _ := DecodeOptionalArg[bool](args, 1)
		return map[string]any{"ok": true}, p.CancelTransfer(id, removeFile)
	})
	router.Handle("cloudPan.transfer.retry", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, p.RetryTransfer(id)
	})
	router.Handle("cloudPan.transfer.clear", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		removeFile, _, _ := DecodeOptionalArg[bool](args, 1)
		return map[string]any{"ok": true}, p.ClearTransfer(id, removeFile)
	})
	router.Handle("cloudPan.transfer.clearFinished", func([]json.RawMessage) (any, error) {
		return map[string]any{"ok": true}, p.ClearFinishedTransfers()
	})
	router.Handle("cloudPan.transfer.pickFolder", func([]json.RawMessage) (any, error) {
		if p.appCtx == nil {
			return "", errors.New("应用仍在启动")
		}
		dir, err := wruntime.OpenDirectoryDialog(p.appCtx, wruntime.OpenDialogOptions{Title: "选择要上传的文件夹"})
		return dir, err
	})
	router.Handle("cloudPan.transfer.pickDir", func([]json.RawMessage) (any, error) {
		if p.appCtx == nil {
			return "", errors.New("应用仍在启动")
		}
		dir, err := wruntime.OpenDirectoryDialog(p.appCtx, wruntime.OpenDialogOptions{Title: "选择保存位置"})
		return dir, err
	})
}

func (p *CloudPan) transferStatusCount(status string) int {
	var count int
	_ = p.db.QueryRow(`SELECT COUNT(*) FROM cloud_transfers WHERE status = ?`, status).Scan(&count)
	return count
}
