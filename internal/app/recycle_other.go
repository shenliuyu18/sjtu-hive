//go:build !windows

package app

import "errors"

func moveToRecycleBin(string) error {
	return errors.New("当前平台不支持回收站")
}
