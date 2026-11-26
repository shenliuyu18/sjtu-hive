//go:build darwin && !cgo

package app

import (
	"errors"
	"log/slog"
)

type Tray struct{}

func NewTray(exePath, tooltip string, logger *slog.Logger, onOpen, onQuit func()) (*Tray, error) {
	if logger != nil {
		logger.Warn("menu bar tray requires cgo on macOS")
	}
	return nil, errors.New("当前构建未启用 macOS 菜单栏支持")
}

func (t *Tray) Close() {}
