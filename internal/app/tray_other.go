//go:build !windows

package app

import (
	"errors"
	"log/slog"
)

type Tray struct{}

func NewTray(exePath, tooltip string, logger *slog.Logger, onOpen, onQuit func()) (*Tray, error) {
	return nil, errors.New("当前平台不支持系统托盘")
}

func (t *Tray) Close() {}
