package app

import (
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"time"
)

const maxLogSize = 5 << 20

func SetupLogging(dir string) (*slog.Logger, func(), error) {
	path := filepath.Join(dir, "app.log")
	if info, err := os.Stat(path); err == nil && info.Size() > maxLogSize {
		_ = os.Rename(path, filepath.Join(dir, fmt.Sprintf("app-%s.log", time.Now().Format("20060102-150405"))))
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o644)
	if err != nil {
		return nil, nil, err
	}
	writer := io.MultiWriter(file, os.Stderr)
	logger := slog.New(slog.NewTextHandler(writer, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)
	return logger, func() { _ = file.Close() }, nil
}
