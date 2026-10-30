//go:build windows

package app

import (
	"errors"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	crypt32              = windows.NewLazySystemDLL("crypt32.dll")
	kernel32             = windows.NewLazySystemDLL("kernel32.dll")
	procCryptProtectData = crypt32.NewProc("CryptProtectData")
	procCryptUnprotect   = crypt32.NewProc("CryptUnprotectData")
	procLocalFree        = kernel32.NewProc("LocalFree")
)

type dataBlob struct {
	size uint32
	data *byte
}

const cryptProtectUIForbidden = 0x1

func protectBytes(plain []byte) ([]byte, error) {
	if len(plain) == 0 {
		plain = []byte{0}
	}
	in := dataBlob{size: uint32(len(plain)), data: &plain[0]}
	var out dataBlob
	result, _, err := procCryptProtectData.Call(
		uintptr(unsafe.Pointer(&in)),
		0,
		0,
		0,
		0,
		cryptProtectUIForbidden,
		uintptr(unsafe.Pointer(&out)),
	)
	if result == 0 {
		return nil, err
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return append([]byte(nil), unsafe.Slice(out.data, out.size)...), nil
}

func unprotectBytes(sealed []byte) ([]byte, error) {
	if len(sealed) == 0 {
		return nil, errors.New("空密文")
	}
	in := dataBlob{size: uint32(len(sealed)), data: &sealed[0]}
	var out dataBlob
	result, _, err := procCryptUnprotect.Call(
		uintptr(unsafe.Pointer(&in)),
		0,
		0,
		0,
		0,
		cryptProtectUIForbidden,
		uintptr(unsafe.Pointer(&out)),
	)
	if result == 0 {
		return nil, err
	}
	defer procLocalFree.Call(uintptr(unsafe.Pointer(out.data)))
	return append([]byte(nil), unsafe.Slice(out.data, out.size)...), nil
}
