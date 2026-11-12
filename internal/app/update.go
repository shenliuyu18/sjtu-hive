package app

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	updateOwner      = "shenliuyu18"
	updateRepo       = "sjtu-hive"
	updateAPI        = "https://api.github.com/repos/" + updateOwner + "/" + updateRepo + "/releases/latest"
	updatePageURL    = "https://github.com/" + updateOwner + "/" + updateRepo + "/releases/latest"
	updateAssetMatch = "windows-amd64-installer.exe"
)

type UpdateAsset struct {
	Name   string `json:"name"`
	URL    string `json:"url"`
	Size   int64  `json:"size"`
	Digest string `json:"digest"`
}

type UpdateInfo struct {
	Available   bool         `json:"available"`
	Current     string       `json:"current"`
	Latest      string       `json:"latest"`
	Notes       string       `json:"notes"`
	PublishedAt string       `json:"publishedAt"`
	PageURL     string       `json:"pageURL"`
	Asset       *UpdateAsset `json:"asset,omitempty"`
	Message     string       `json:"message"`
}

type Update struct {
	settings *Settings
	paths    Paths
	emit     func(string, any)

	mu       sync.Mutex
	download bool
}

func NewUpdate(settings *Settings, paths Paths, emit func(string, any)) *Update {
	return &Update{settings: settings, paths: paths, emit: emit}
}

func splitVersion(value string) ([]int, string) {
	value = strings.TrimPrefix(strings.TrimSpace(value), "v")
	if index := strings.IndexByte(value, '+'); index >= 0 {
		value = value[:index]
	}
	prerelease := ""
	if index := strings.IndexByte(value, '-'); index >= 0 {
		prerelease = value[index+1:]
		value = value[:index]
	}
	parts := strings.Split(value, ".")
	result := make([]int, 0, len(parts))
	for _, part := range parts {
		number, _ := strconv.Atoi(part)
		result = append(result, number)
	}
	return result, prerelease
}

func comparePrerelease(left, right string) int {
	if left == right {
		return 0
	}
	a := strings.Split(left, ".")
	b := strings.Split(right, ".")
	for index := 0; index < len(a) || index < len(b); index++ {
		av, bv := "", ""
		if index < len(a) {
			av = a[index]
		}
		if index < len(b) {
			bv = b[index]
		}
		if av == bv {
			continue
		}
		if av == "" {
			return -1
		}
		if bv == "" {
			return 1
		}
		an, aerr := strconv.Atoi(av)
		bn, berr := strconv.Atoi(bv)
		switch {
		case aerr == nil && berr == nil:
			if an > bn {
				return 1
			}
			return -1
		case aerr == nil:
			return -1
		case berr == nil:
			return 1
		case av > bv:
			return 1
		default:
			return -1
		}
	}
	return 0
}

func compareVersions(left, right string) int {
	a, aPre := splitVersion(left)
	b, bPre := splitVersion(right)
	for index := 0; index < len(a) || index < len(b); index++ {
		var av, bv int
		if index < len(a) {
			av = a[index]
		}
		if index < len(b) {
			bv = b[index]
		}
		if av > bv {
			return 1
		}
		if av < bv {
			return -1
		}
	}
	// 同为核心版本时，正式版高于预发布版。
	if aPre == "" && bPre != "" {
		return 1
	}
	if aPre != "" && bPre == "" {
		return -1
	}
	return comparePrerelease(aPre, bPre)
}

func (u *Update) Check() (UpdateInfo, error) {
	info := UpdateInfo{Current: Version, PageURL: updatePageURL}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, updateAPI, nil)
	if err != nil {
		return info, err
	}
	request.Header.Set("Accept", "application/vnd.github+json")
	request.Header.Set("User-Agent", "SJTU-Hive/"+Version)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return info, errors.New("无法连接 GitHub 检查更新")
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusForbidden || response.StatusCode == http.StatusTooManyRequests {
		info.Message = "GitHub 接口访问受限，请稍后再试。"
		return info, nil
	}
	if response.StatusCode != http.StatusOK {
		return info, fmt.Errorf("检查更新失败：GitHub HTTP %d", response.StatusCode)
	}
	var release struct {
		TagName     string `json:"tag_name"`
		Name        string `json:"name"`
		Body        string `json:"body"`
		PublishedAt string `json:"published_at"`
		HTMLURL     string `json:"html_url"`
		Assets      []struct {
			Name   string `json:"name"`
			URL    string `json:"browser_download_url"`
			Size   int64  `json:"size"`
			Digest string `json:"digest"`
		} `json:"assets"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 2<<20)).Decode(&release); err != nil {
		return info, errors.New("更新信息格式无法识别")
	}
	latest := strings.TrimPrefix(strings.TrimSpace(release.TagName), "v")
	if latest == "" {
		return info, errors.New("GitHub 没有返回版本号")
	}
	info.Latest = latest
	info.Notes = release.Body
	info.PublishedAt = release.PublishedAt
	if release.HTMLURL != "" {
		info.PageURL = release.HTMLURL
	}
	for _, asset := range release.Assets {
		if strings.Contains(strings.ToLower(asset.Name), updateAssetMatch) {
			info.Asset = &UpdateAsset{Name: asset.Name, URL: asset.URL, Size: asset.Size, Digest: asset.Digest}
			break
		}
	}
	if compareVersions(latest, Version) <= 0 {
		info.Message = "当前已是最新版本。"
		return info, nil
	}
	info.Available = true
	info.Message = "发现新版本 " + latest
	if info.Asset == nil {
		info.Message = "发现新版本 " + latest + "，但这个版本没有提供 Windows 安装包，请到发布页下载。"
	}
	return info, nil
}

func (u *Update) updateDir() string {
	return filepath.Join(filepath.Dir(u.paths.CacheDir), "updates")
}

func validateUpdateURL(raw string) error {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
		return errors.New("更新下载地址无效")
	}
	host := strings.ToLower(parsed.Hostname())
	if host == "github.com" || strings.HasSuffix(host, ".githubusercontent.com") {
		return nil
	}
	return errors.New("更新下载地址不属于 GitHub，已阻止")
}

func (u *Update) Download(assetURL, name, digest string) (map[string]any, error) {
	if err := validateUpdateURL(assetURL); err != nil {
		return nil, err
	}
	fileName := filepath.Base(strings.TrimSpace(name))
	if !strings.HasSuffix(strings.ToLower(fileName), ".exe") {
		return nil, errors.New("更新包不是 Windows 安装程序")
	}
	u.mu.Lock()
	if u.download {
		u.mu.Unlock()
		return nil, errors.New("已有更新包正在下载")
	}
	u.download = true
	u.mu.Unlock()
	defer func() {
		u.mu.Lock()
		u.download = false
		u.mu.Unlock()
	}()

	dir := u.updateDir()
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	target := filepath.Join(dir, fileName)
	part := target + ".part"

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Minute)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, assetURL, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", "SJTU-Hive/"+Version)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return nil, errors.New("下载更新包失败：无法连接 GitHub")
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("下载更新包失败：HTTP %d", response.StatusCode)
	}
	file, err := os.OpenFile(part, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, err
	}
	hash := sha256.New()
	total := response.ContentLength
	received := int64(0)
	last := time.Now()
	writer := io.MultiWriter(file, hash)
	buffer := make([]byte, 256<<10)
	for {
		n, readErr := response.Body.Read(buffer)
		if n > 0 {
			if _, writeErr := writer.Write(buffer[:n]); writeErr != nil {
				_ = file.Close()
				_ = os.Remove(part)
				return nil, writeErr
			}
			received += int64(n)
			if time.Since(last) > 400*time.Millisecond {
				last = time.Now()
				u.emit("update.progress", map[string]any{"received": received, "total": total})
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			_ = file.Close()
			return nil, readErr
		}
	}
	if err := file.Close(); err != nil {
		return nil, err
	}
	sum := hex.EncodeToString(hash.Sum(nil))
	expected := strings.TrimPrefix(strings.ToLower(strings.TrimSpace(digest)), "sha256:")
	if expected != "" && expected != sum {
		_ = os.Remove(part)
		return nil, errors.New("更新包校验失败，已删除下载文件")
	}
	if err := os.Rename(part, target); err != nil {
		return nil, err
	}
	u.emit("update.progress", map[string]any{"received": received, "total": received, "done": true})
	return map[string]any{"path": target, "size": received, "sha256": sum}, nil
}

func (u *Update) Install(path string) error {
	clean := filepath.Clean(strings.TrimSpace(path))
	dir := filepath.Clean(u.updateDir())
	relative, err := filepath.Rel(dir, clean)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(os.PathSeparator)) {
		return errors.New("安装包路径不在更新目录内")
	}
	if _, err := os.Stat(clean); err != nil {
		return errors.New("安装包不存在，请重新下载")
	}
	command := exec.Command(clean)
	command.Dir = dir
	if err := command.Start(); err != nil {
		return fmt.Errorf("启动安装程序失败：%w", err)
	}
	return nil
}

func (u *Update) Register(router *Router) {
	router.Handle("update.check", func([]json.RawMessage) (any, error) {
		return u.Check()
	})
	router.Handle("update.download", func(args []json.RawMessage) (any, error) {
		assetURL, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		name, _, _ := DecodeOptionalArg[string](args, 1)
		digest, _, _ := DecodeOptionalArg[string](args, 2)
		return u.Download(assetURL, name, digest)
	})
	router.Handle("update.install", func(args []json.RawMessage) (any, error) {
		path, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		if err := u.Install(path); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	})
}
