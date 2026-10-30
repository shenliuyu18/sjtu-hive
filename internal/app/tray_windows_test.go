//go:build windows

package app

import (
	"testing"
	"unsafe"
)

func TestTrayNotifyIconDataLayout(t *testing.T) {
	// NOTIFYICONDATAW on x64: Shell_NotifyIcon rejects the call unless cbSize
	// matches the exact Win32 layout.
	if size := unsafe.Sizeof(trayNotifyIconData{}); size != 976 {
		t.Fatalf("NOTIFYICONDATAW size changed: %d", size)
	}
	if unsafe.Offsetof(trayNotifyIconData{}.balloonIcon) != 0x3C8 {
		t.Fatalf("balloonIcon offset changed")
	}
}
