//go:build darwin && cgo

package app

/*
#cgo CFLAGS: -x objective-c -fblocks
#cgo LDFLAGS: -framework Cocoa
#include <stdlib.h>
extern int hive_tray_start(const char *tooltip);
extern void hive_tray_stop(void);
*/
import "C"

import (
	"errors"
	"log/slog"
	"sync"
	"time"
	"unsafe"
)

type Tray struct {
	closeOnce sync.Once
}

var darwinTrayCallbacks struct {
	mu   sync.Mutex
	open func()
	quit func()
}

//export hiveTrayOpen
func hiveTrayOpen() {
	darwinTrayCallbacks.mu.Lock()
	open := darwinTrayCallbacks.open
	darwinTrayCallbacks.mu.Unlock()
	if open != nil {
		go open()
	}
}

//export hiveTrayQuit
func hiveTrayQuit() {
	darwinTrayCallbacks.mu.Lock()
	quit := darwinTrayCallbacks.quit
	darwinTrayCallbacks.mu.Unlock()
	if quit != nil {
		go quit()
	}
}

var (
	darwinTrayReadyOnce sync.Once
	darwinTrayReadyCh   = make(chan bool, 1)
)

//export hiveTrayReady
func hiveTrayReady(ok C.int) {
	darwinTrayReadyOnce.Do(func() {
		darwinTrayReadyCh <- ok != 0
	})
}

func NewTray(exePath, tooltip string, logger *slog.Logger, onOpen, onQuit func()) (*Tray, error) {
	darwinTrayCallbacks.mu.Lock()
	darwinTrayCallbacks.open = onOpen
	darwinTrayCallbacks.quit = onQuit
	darwinTrayCallbacks.mu.Unlock()

	text := C.CString(tooltip)
	defer C.free(unsafe.Pointer(text))
	if C.hive_tray_start(text) == 0 {
		return nil, errors.New("无法创建 macOS 菜单栏图标")
	}
	select {
	case ok := <-darwinTrayReadyCh:
		if !ok {
			return nil, errors.New("无法创建 macOS 菜单栏图标")
		}
	case <-time.After(3 * time.Second):
		return nil, errors.New("创建 macOS 菜单栏图标超时")
	}
	if logger != nil {
		logger.Info("menu bar tray ready")
	}
	return &Tray{}, nil
}

func (t *Tray) Close() {
	t.closeOnce.Do(func() {
		C.hive_tray_stop()
		darwinTrayCallbacks.mu.Lock()
		darwinTrayCallbacks.open = nil
		darwinTrayCallbacks.quit = nil
		darwinTrayCallbacks.mu.Unlock()
	})
}
