//go:build !windows

package app

import (
	"context"
	"errors"
)

func loginShareToken(context.Context) (string, error) {
	return "", errors.New("传承交大登录目前仅支持 Windows")
}
