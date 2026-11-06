package app

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"sort"
	"strconv"
	"strings"
	"time"

	"golang.org/x/crypto/scrypt"
)

const webdavSecret = "webdav-backup"

var webdavMagic = []byte("SJTUHIVE\x01")

type webDAVConfig struct {
	URL       string `json:"url"`
	Directory string `json:"directory"`
	Username  string `json:"username"`
	Password  string `json:"password"`
}

func (a *App) loadWebDAV() (webDAVConfig, error) {
	var config webDAVConfig
	ok, err := a.secrets.Load(webdavSecret, &config)
	if err != nil {
		return webDAVConfig{}, err
	}
	if !ok || strings.TrimSpace(config.URL) == "" {
		return webDAVConfig{}, errors.New("尚未配置 WebDAV 备份")
	}
	return config, nil
}

func (a *App) WebDAVState() map[string]any {
	config, err := a.loadWebDAV()
	if err != nil {
		return map[string]any{"configured": false}
	}
	return map[string]any{
		"configured": true,
		"url":        config.URL,
		"directory":  config.Directory,
		"username":   config.Username,
	}
}

func (a *App) SaveWebDAV(rawURL, directory, username, password string) (map[string]any, error) {
	rawURL = strings.TrimSpace(rawURL)
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return nil, errors.New("WebDAV 地址需要是完整的 http(s) URL")
	}
	current, _ := a.loadWebDAV()
	if password == "" {
		password = current.Password
	}
	config := webDAVConfig{
		URL:       strings.TrimRight(rawURL, "/"),
		Directory: strings.Trim(strings.TrimSpace(directory), "/"),
		Username:  strings.TrimSpace(username),
		Password:  password,
	}
	if err := a.secrets.Save(webdavSecret, config); err != nil {
		return nil, err
	}
	return a.WebDAVState(), nil
}

func (a *App) ClearWebDAV() map[string]any {
	_ = a.secrets.Delete(webdavSecret)
	return map[string]any{"configured": false}
}

func (a *App) UploadWebDAV(ctx context.Context, passphrase string) (map[string]any, error) {
	passphrase = strings.TrimSpace(passphrase)
	if len([]rune(passphrase)) < 8 {
		return nil, errors.New("加密口令至少需要 8 个字符")
	}
	config, err := a.loadWebDAV()
	if err != nil {
		return nil, err
	}
	dir, err := os.MkdirTemp("", "sjtu-webdav-*")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	plain := path.Join(dir, "backup.zip")
	if err := a.createBackupZip(plain, true); err != nil {
		return nil, err
	}
	encrypted := path.Join(dir, "backup.hivebackup")
	if err := encryptBackupFile(plain, encrypted, passphrase); err != nil {
		return nil, err
	}
	info, err := os.Stat(encrypted)
	if err != nil {
		return nil, err
	}
	name := "SJTU-Hive-backup-" + time.Now().Format("20060102-150405") + ".hivebackup"
	if err := putWebDAVFile(ctx, config, name, encrypted); err != nil {
		return nil, err
	}
	return map[string]any{
		"uploaded": true,
		"name":     name,
		"size":     info.Size(),
	}, nil
}

func encryptBackupFile(plainPath, targetPath, passphrase string) error {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		return err
	}
	key, err := scrypt.Key([]byte(passphrase), salt, 32768, 8, 1, 32)
	if err != nil {
		return err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return err
	}
	plain, err := os.ReadFile(plainPath)
	if err != nil {
		return err
	}
	sealed := gcm.Seal(nil, nonce, plain, append(append([]byte{}, webdavMagic...), salt...))
	file, err := os.Create(targetPath)
	if err != nil {
		return err
	}
	defer file.Close()
	if _, err := file.Write(webdavMagic); err != nil {
		return err
	}
	if _, err := file.Write(salt); err != nil {
		return err
	}
	if _, err := file.Write(nonce); err != nil {
		return err
	}
	if _, err := file.Write(sealed); err != nil {
		return err
	}
	return file.Sync()
}

func webdavTarget(config webDAVConfig, name string) (string, error) {
	base, err := url.Parse(config.URL)
	if err != nil {
		return "", err
	}
	segments := []string{base.Path}
	if config.Directory != "" {
		segments = append(segments, config.Directory)
	}
	if name != "" {
		segments = append(segments, name)
	}
	base.Path = path.Join(segments...)
	if name == "" && !strings.HasSuffix(base.Path, "/") {
		base.Path += "/"
	}
	return base.String(), nil
}

func decryptBackupFile(sourcePath, targetPath, passphrase string) error {
	data, err := os.ReadFile(sourcePath)
	if err != nil {
		return err
	}
	if len(data) < len(webdavMagic)+16+12 || !bytes.HasPrefix(data, webdavMagic) {
		return errors.New("不是有效的加密备份文件")
	}
	offset := len(webdavMagic)
	salt := data[offset : offset+16]
	offset += 16
	key, err := scrypt.Key([]byte(passphrase), salt, 32768, 8, 1, 32)
	if err != nil {
		return err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return err
	}
	if len(data) < offset+gcm.NonceSize() {
		return errors.New("备份文件不完整")
	}
	nonce := data[offset : offset+gcm.NonceSize()]
	opened, err := gcm.Open(nil, nonce, data[offset+gcm.NonceSize():], append(append([]byte{}, webdavMagic...), salt...))
	if err != nil {
		return errors.New("解密失败：口令错误或备份文件已损坏")
	}
	return os.WriteFile(targetPath, opened, 0o600)
}

func putWebDAVFile(ctx context.Context, config webDAVConfig, name, localPath string) error {
	target, err := webdavTarget(config, name)
	if err != nil {
		return err
	}
	file, err := os.Open(localPath)
	if err != nil {
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPut, target, file)
	if err != nil {
		return err
	}
	request.ContentLength = info.Size()
	request.Header.Set("Content-Type", "application/octet-stream")
	request.Header.Set("User-Agent", AppName)
	if config.Username != "" || config.Password != "" {
		request.SetBasicAuth(config.Username, config.Password)
	}
	client := &http.Client{Timeout: 3 * time.Minute}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("连接 WebDAV 失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 4096))
		message := strings.TrimSpace(string(body))
		if message == "" {
			message = response.Status
		}
		return fmt.Errorf("WebDAV 上传失败：%s", message)
	}
	return nil
}

type WebDAVBackup struct {
	Name       string `json:"name"`
	Size       int64  `json:"size"`
	ModifiedAt string `json:"modifiedAt"`
}

type davMultistatus struct {
	Responses []davResponse `xml:"response"`
}

type davResponse struct {
	Href     string        `xml:"href"`
	Propstat []davPropstat `xml:"propstat"`
}

type davPropstat struct {
	Prop   davProp `xml:"prop"`
	Status string  `xml:"status"`
}

type davProp struct {
	ContentLength string `xml:"getcontentlength"`
	LastModified  string `xml:"getlastmodified"`
}

const webdavPropfindBody = `<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:">
  <d:prop><d:getcontentlength/><d:getlastmodified/></d:prop>
</d:propfind>`

func applyWebDAVAuth(request *http.Request, config webDAVConfig) {
	request.Header.Set("User-Agent", AppName)
	if config.Username != "" || config.Password != "" {
		request.SetBasicAuth(config.Username, config.Password)
	}
}

func (a *App) ListWebDAVBackups(ctx context.Context) ([]WebDAVBackup, error) {
	config, err := a.loadWebDAV()
	if err != nil {
		return nil, err
	}
	target, err := webdavTarget(config, "")
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, "PROPFIND", target, strings.NewReader(webdavPropfindBody))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Depth", "1")
	request.Header.Set("Content-Type", "application/xml; charset=utf-8")
	applyWebDAVAuth(request, config)
	client := &http.Client{Timeout: 60 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("连接 WebDAV 失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound {
		return nil, errors.New("WebDAV 备份目录不存在，请先在服务器上创建该目录")
	}
	if response.StatusCode != http.StatusMultiStatus && response.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 2048))
		message := strings.TrimSpace(string(body))
		if message == "" {
			message = response.Status
		}
		return nil, fmt.Errorf("读取 WebDAV 备份列表失败：%s", message)
	}
	var listing davMultistatus
	if err := xml.NewDecoder(io.LimitReader(response.Body, 4<<20)).Decode(&listing); err != nil {
		return nil, errors.New("WebDAV 返回的备份列表无法解析")
	}
	backups := []WebDAVBackup{}
	for _, item := range listing.Responses {
		parsed, err := url.Parse(strings.TrimSpace(item.Href))
		if err != nil {
			continue
		}
		name, err := url.PathUnescape(path.Base(strings.TrimRight(parsed.Path, "/")))
		if err != nil || !strings.HasSuffix(strings.ToLower(name), ".hivebackup") {
			continue
		}
		backup := WebDAVBackup{Name: name}
		for _, propstat := range item.Propstat {
			if !strings.Contains(propstat.Status, "200") {
				continue
			}
			if size, err := strconv.ParseInt(strings.TrimSpace(propstat.Prop.ContentLength), 10, 64); err == nil {
				backup.Size = size
			}
			backup.ModifiedAt = strings.TrimSpace(propstat.Prop.LastModified)
		}
		backups = append(backups, backup)
	}
	sort.Slice(backups, func(i, j int) bool { return backups[i].Name > backups[j].Name })
	return backups, nil
}

func validateBackupName(name string) error {
	if name == "" || name != path.Base(name) || strings.ContainsAny(name, `\/`) {
		return errors.New("备份文件名无效")
	}
	if !strings.HasSuffix(strings.ToLower(name), ".hivebackup") {
		return errors.New("只能恢复 .hivebackup 加密备份")
	}
	return nil
}

func (a *App) RestoreWebDAVBackup(ctx context.Context, name, passphrase string) (map[string]any, error) {
	if err := validateBackupName(name); err != nil {
		return nil, err
	}
	passphrase = strings.TrimSpace(passphrase)
	if len([]rune(passphrase)) < 8 {
		return nil, errors.New("加密口令至少需要 8 个字符")
	}
	config, err := a.loadWebDAV()
	if err != nil {
		return nil, err
	}
	target, err := webdavTarget(config, name)
	if err != nil {
		return nil, err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return nil, err
	}
	applyWebDAVAuth(request, config)
	client := &http.Client{Timeout: 5 * time.Minute}
	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("连接 WebDAV 失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNotFound {
		return nil, fmt.Errorf("远程备份 %s 不存在", name)
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		body, _ := io.ReadAll(io.LimitReader(response.Body, 2048))
		message := strings.TrimSpace(string(body))
		if message == "" {
			message = response.Status
		}
		return nil, fmt.Errorf("下载 WebDAV 备份失败：%s", message)
	}
	dir, err := os.MkdirTemp("", "sjtu-webdav-restore-*")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)
	encrypted := path.Join(dir, "backup.hivebackup")
	file, err := os.Create(encrypted)
	if err != nil {
		return nil, err
	}
	if _, err := io.Copy(file, io.LimitReader(response.Body, 4<<30)); err != nil {
		_ = file.Close()
		return nil, fmt.Errorf("下载 WebDAV 备份失败：%w", err)
	}
	if err := file.Close(); err != nil {
		return nil, err
	}
	plain := path.Join(dir, "backup.zip")
	if err := decryptBackupFile(encrypted, plain, passphrase); err != nil {
		return nil, err
	}
	return a.restoreBackupArchive(plain, name)
}

func (a *App) registerWebDAV(router *Router) {
	router.Handle("backup.webdav.state", func([]json.RawMessage) (any, error) {
		return a.WebDAVState(), nil
	})
	router.Handle("backup.webdav.save", func(args []json.RawMessage) (any, error) {
		rawURL, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		directory, _, _ := DecodeOptionalArg[string](args, 1)
		username, _, _ := DecodeOptionalArg[string](args, 2)
		password, _, _ := DecodeOptionalArg[string](args, 3)
		return a.SaveWebDAV(rawURL, directory, username, password)
	})
	router.Handle("backup.webdav.clear", func([]json.RawMessage) (any, error) {
		return a.ClearWebDAV(), nil
	})
	router.Handle("backup.webdav.upload", func(args []json.RawMessage) (any, error) {
		passphrase, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
		defer cancel()
		return a.UploadWebDAV(ctx, passphrase)
	})
	router.Handle("backup.webdav.list", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		defer cancel()
		return a.ListWebDAVBackups(ctx)
	})
	router.Handle("backup.webdav.restore", func(args []json.RawMessage) (any, error) {
		name, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		passphrase, _, _ := DecodeOptionalArg[string](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Minute)
		defer cancel()
		return a.RestoreWebDAVBackup(ctx, name, passphrase)
	})
}
