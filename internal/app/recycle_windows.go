//go:build windows

package app

import (
	"errors"
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	shell32Proc          = windows.NewLazySystemDLL("shell32.dll")
	procSHFileOperationW = shell32Proc.NewProc("SHFileOperationW")
)

type shFileOpStructW struct {
	hwnd                 uintptr
	wFunc                uint32
	_                    uint32
	from                 *uint16
	to                   *uint16
	flags                uint16
	_                    uint16
	anyOperationsAborted int32
	nameMappings         uintptr
	progressTitle        *uint16
}

const (
	fileOpDelete         = 3
	fileOpSilent         = 0x0004
	fileOpNoConfirmation = 0x0010
	fileOpAllowUndo      = 0x0040
	fileOpNoErrorUI      = 0x0400
)

func moveToRecycleBin(path string) error {
	if path == "" {
		return errors.New("路径为空")
	}
	from, err := syscall.UTF16FromString(path)
	if err != nil {
		return err
	}
	from = append(from, 0)
	operation := shFileOpStructW{
		wFunc: fileOpDelete,
		from:  &from[0],
		flags: fileOpSilent | fileOpNoConfirmation | fileOpAllowUndo | fileOpNoErrorUI,
	}
	result, _, callErr := procSHFileOperationW.Call(uintptr(unsafe.Pointer(&operation)))
	if result != 0 {
		return fmt.Errorf("移入回收站失败（%v），请直接在资源管理器中删除", callErr)
	}
	if operation.anyOperationsAborted != 0 {
		return errors.New("已取消删除")
	}
	return nil
}
