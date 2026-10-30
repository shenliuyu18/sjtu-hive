//go:build !windows

package app

import (
	"context"
	"errors"
	"log/slog"
)

func edgeLogin(context.Context, *slog.Logger, func(string)) ([]cookieRecord, error) {
	return nil, errors.New("当前平台不支持 Edge 登录")
}
