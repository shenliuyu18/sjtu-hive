//go:build !windows

package app

import "errors"

func protectBytes([]byte) ([]byte, error) {
	return nil, errors.New("当前平台不支持 DPAPI")
}

func unprotectBytes([]byte) ([]byte, error) {
	return nil, errors.New("当前平台不支持 DPAPI")
}
