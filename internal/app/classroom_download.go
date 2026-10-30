package app

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

type classroomDownloadTask struct {
	info   VideoDownload
	cancel context.CancelFunc
	target string
}

func (c *Classroom) Downloads() []VideoDownload {
	c.downloadMu.Lock()
	defer c.downloadMu.Unlock()
	result := make([]VideoDownload, 0, len(c.downloads))
	for _, task := range c.downloads {
		result = append(result, task.info)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].ID < result[j].ID })
	return result
}

func (c *Classroom) updateDownload(id string, update func(*VideoDownload)) {
	c.downloadMu.Lock()
	task := c.downloads[id]
	if task != nil {
		update(&task.info)
		info := task.info
		c.downloadMu.Unlock()
		if c.emit != nil {
			c.emit("classroom.download", info)
		}
		return
	}
	c.downloadMu.Unlock()
}

func (c *Classroom) StartDownload(videoID int64, view int, name, target string) (string, error) {
	if videoID <= 0 || view <= 0 {
		return "", errors.New("录像参数无效")
	}
	if strings.TrimSpace(target) == "" {
		return "", errors.New("没有选择保存位置")
	}
	if strings.TrimSpace(name) == "" {
		name = fmt.Sprintf("课堂视频-%d-画面%d.mp4", videoID, view)
	}
	id := fmt.Sprintf("%d/%d", videoID, view)
	c.downloadMu.Lock()
	if existing, ok := c.downloads[id]; ok && existing.info.State == "progressing" {
		c.downloadMu.Unlock()
		return "", errors.New("该画面正在下载")
	}
	ctx, cancel := context.WithCancel(context.Background())
	task := &classroomDownloadTask{
		info:   VideoDownload{ID: id, Name: name, State: "progressing"},
		cancel: cancel,
		target: target,
	}
	c.downloads[id] = task
	c.downloadMu.Unlock()
	go c.runDownload(ctx, id, videoID, view, name, target)
	return id, nil
}

func (c *Classroom) runDownload(ctx context.Context, id string, videoID int64, view int, name, target string) {
	fail := func(message string) {
		c.updateDownload(id, func(info *VideoDownload) {
			info.State = "interrupted"
		})
		if c.emit != nil {
			c.emit("classroom.download.error", map[string]any{"id": id, "error": message})
		}
	}
	streams, err := c.Streams(ctx, videoID)
	if err != nil {
		fail(err.Error())
		return
	}
	streamURL := ""
	for _, stream := range streams {
		if stream.View == view {
			streamURL = stream.PlaybackURL
			break
		}
	}
	if streamURL == "" {
		fail("该画面暂不可用")
		return
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		fail(err.Error())
		return
	}
	temp, err := os.CreateTemp(filepath.Dir(target), ".video-*.tmp")
	if err != nil {
		fail(err.Error())
		return
	}
	tempName := temp.Name()
	cleanup := func() {
		_ = temp.Close()
		_ = os.Remove(tempName)
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, streamURL, nil)
	if err != nil {
		cleanup()
		fail(err.Error())
		return
	}
	client := &http.Client{}
	response, err := client.Do(request)
	if err != nil {
		cleanup()
		fail(err.Error())
		return
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusPartialContent {
		cleanup()
		fail(fmt.Sprintf("下载失败：HTTP %d", response.StatusCode))
		return
	}
	total := response.ContentLength
	if total < 0 {
		total = 0
	}
	c.updateDownload(id, func(info *VideoDownload) {
		info.Total = total
	})
	buffer := make([]byte, 256*1024)
	var received int64
	lastEmit := time.Now()
	for {
		n, readErr := response.Body.Read(buffer)
		if n > 0 {
			if _, writeErr := temp.Write(buffer[:n]); writeErr != nil {
				cleanup()
				fail(writeErr.Error())
				return
			}
			received += int64(n)
			if time.Since(lastEmit) > 400*time.Millisecond {
				lastEmit = time.Now()
				c.updateDownload(id, func(info *VideoDownload) {
					info.Received = received
				})
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			cleanup()
			if ctx.Err() != nil {
				c.updateDownload(id, func(info *VideoDownload) { info.State = "cancelled" })
			} else {
				fail(readErr.Error())
			}
			return
		}
	}
	if err := temp.Close(); err != nil {
		_ = os.Remove(tempName)
		fail(err.Error())
		return
	}
	if err := os.Rename(tempName, target); err != nil {
		_ = os.Remove(tempName)
		fail(err.Error())
		return
	}
	c.updateDownload(id, func(info *VideoDownload) {
		info.Received = received
		info.Total = received
		info.State = "completed"
	})
}

func (c *Classroom) CancelDownload(id string) {
	c.downloadMu.Lock()
	task := c.downloads[id]
	c.downloadMu.Unlock()
	if task != nil && task.cancel != nil {
		task.cancel()
		c.updateDownload(id, func(info *VideoDownload) { info.State = "cancelled" })
	}
}

func (c *Classroom) RevealDownload(id string) error {
	c.downloadMu.Lock()
	task := c.downloads[id]
	var target string
	if task != nil {
		target = task.target
	}
	c.downloadMu.Unlock()
	if target == "" {
		return errors.New("下载记录不存在")
	}
	if _, err := os.Stat(target); err != nil {
		return errors.New("文件已被移动或删除")
	}
	return exec.Command("explorer.exe", "/select,"+target).Start()
}

func videoFileName(name string, view int) string {
	base := sanitizeFilename(strings.TrimSpace(name), "课堂视频")
	base = strings.TrimSuffix(base, ".mp4")
	if base == "" {
		base = "课堂视频"
	}
	return fmt.Sprintf("%s-画面%d.mp4", base, view)
}
