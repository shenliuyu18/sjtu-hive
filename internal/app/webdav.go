package app

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
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

func putWebDAVFile(ctx context.Context, config webDAVConfig, name, localPath string) error {
	base, err := url.Parse(config.URL)
	if err != nil {
		return err
	}
	base.Path = path.Join(base.Path, config.Directory, name)
	target := base.String()
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
}
