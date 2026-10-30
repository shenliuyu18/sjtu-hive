//go:build windows

package app

import (
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	winUser32           = windows.NewLazySystemDLL("user32.dll")
	procFindWindowW     = winUser32.NewProc("FindWindowW")
	procSetWindowPos    = winUser32.NewProc("SetWindowPos")
	procGetSystemMetric = winUser32.NewProc("GetSystemMetrics")
)

func (a *App) applyInitialWindowSize() {
	if a.ctx == nil {
		return
	}
	title, err := windows.UTF16PtrFromString("SJTU")
	if err != nil {
		return
	}
	hwnd, _, _ := procFindWindowW.Call(0, uintptr(unsafe.Pointer(title)))
	if hwnd == 0 {
		return
	}
	screenWidth, _, _ := procGetSystemMetric.Call(0)
	screenHeight, _, _ := procGetSystemMetric.Call(1)
	cx := int(screenWidth)
	cy := int(screenHeight)
	width, height := 2160, 1350
	if cx > 0 && width > cx-40 {
		width = cx - 40
	}
	if cy > 0 && height > cy-40 {
		height = cy - 40
	}
	if width < 1024 {
		width = 1024
	}
	if height < 680 {
		height = 680
	}
	x := (cx - width) / 2
	y := (cy - height) / 2
	if x < 0 {
		x = 0
	}
	if y < 0 {
		y = 0
	}
	procSetWindowPos.Call(hwnd, 0, uintptr(x), uintptr(y), uintptr(width), uintptr(height), 0x0004|0x0010)
	if a.logger != nil {
		a.logger.Info("window sized", "screenWidth", cx, "screenHeight", cy, "windowWidth", width, "windowHeight", height)
	}
}
