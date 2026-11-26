//go:build !windows

package app

import (
	"crypto/rand"
	"errors"
	"os"
	"path/filepath"
	"sync"
)

var (
	fileKeyOnce  sync.Once
	fileKeyValue []byte
	fileKeyErr   error
)

func fileSecretKeyPath() (string, error) {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, AppName, "secret.key"), nil
}

func hasFileSecretKey() bool {
	path, err := fileSecretKeyPath()
	if err != nil {
		return false
	}
	info, err := os.Stat(path)
	return err == nil && info.Size() == 32
}

// fileSecretKey is the fallback for non-Windows builds without a native
// credential store (and for darwin builds compiled with CGO disabled).
func fileSecretKey() ([]byte, error) {
	fileKeyOnce.Do(func() {
		path, err := fileSecretKeyPath()
		if err != nil {
			fileKeyErr = err
			return
		}
		base := filepath.Dir(path)
		if err := os.MkdirAll(base, 0o700); err != nil {
			fileKeyErr = err
			return
		}
		if data, readErr := os.ReadFile(path); readErr == nil && len(data) == 32 {
			fileKeyValue = data
			return
		}
		key := make([]byte, 32)
		if _, err := rand.Read(key); err != nil {
			fileKeyErr = err
			return
		}
		temp := path + ".tmp"
		if err := os.WriteFile(temp, key, 0o600); err != nil {
			fileKeyErr = err
			return
		}
		if err := os.Rename(temp, path); err != nil {
			fileKeyErr = err
			return
		}
		fileKeyValue = key
	})
	if fileKeyErr != nil {
		return nil, fileKeyErr
	}
	if len(fileKeyValue) != 32 {
		return nil, errors.New("密钥长度无效")
	}
	out := make([]byte, len(fileKeyValue))
	copy(out, fileKeyValue)
	return out, nil
}
