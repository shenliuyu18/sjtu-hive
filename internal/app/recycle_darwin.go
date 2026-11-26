//go:build darwin

package app

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

func moveToRecycleBin(path string) error {
	if path == "" {
		return errors.New("路径为空")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return err
	}
	trash := filepath.Join(home, ".Trash")
	if err := os.MkdirAll(trash, 0o700); err != nil {
		return err
	}
	target := filepath.Join(trash, filepath.Base(path))
	if _, err := os.Stat(target); err == nil {
		// 同名文件已存在时加时间后缀，避免覆盖回收站里的旧文件。
		ext := filepath.Ext(target)
		base := target[:len(target)-len(ext)]
		target = fmt.Sprintf("%s-%d%s", base, time.Now().Unix(), ext)
	}
	if err := os.Rename(path, target); err != nil {
		return fmt.Errorf("移入废纸篓失败：%w", err)
	}
	return nil
}
