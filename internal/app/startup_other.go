//go:build !windows

package app

import "errors"

func setOpenAtLogin(bool) error {
	return errors.New("当前平台不支持开机启动")
}

func openAtLoginValue() (bool, error) {
	return false, errors.New("当前平台不支持开机启动")
}

func migrateAutostartName() {}
