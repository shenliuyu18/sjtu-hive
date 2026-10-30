package main

import (
	"fmt"
	"image"
	"image/color"
	"image/png"
	"log"
	"os"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	user32                     = windows.NewLazySystemDLL("user32.dll")
	gdi32                      = windows.NewLazySystemDLL("gdi32.dll")
	procSetProcessDpiAwareness = user32.NewProc("SetProcessDpiAwarenessContext")
	procFindWindowW            = user32.NewProc("FindWindowW")
	procEnumChildWindows       = user32.NewProc("EnumChildWindows")
	procGetClassNameW          = user32.NewProc("GetClassNameW")
	procGetWindowRect          = user32.NewProc("GetWindowRect")
	procGetWindowDC            = user32.NewProc("GetWindowDC")
	procReleaseDC              = user32.NewProc("ReleaseDC")
	procPrintWindow            = user32.NewProc("PrintWindow")
	procCreateCompatibleDC     = gdi32.NewProc("CreateCompatibleDC")
	procCreateCompatibleBitmap = gdi32.NewProc("CreateCompatibleBitmap")
	procSelectObject           = gdi32.NewProc("SelectObject")
	procGetDIBits              = gdi32.NewProc("GetDIBits")
	procDeleteObject           = gdi32.NewProc("DeleteObject")
	procDeleteDC               = gdi32.NewProc("DeleteDC")
)

type rect struct {
	left, top, right, bottom int32
}

type bitmapInfoHeader struct {
	size          uint32
	width         int32
	height        int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xPelsPerMeter int32
	yPelsPerMeter int32
	clrUsed       uint32
	clrImportant  uint32
}

type bitmapInfo struct {
	header bitmapInfoHeader
	colors [3]uint32
}

type HWND = windows.HWND

func main() {
	// DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4
	procSetProcessDpiAwareness.Call(^uintptr(3))
	title, _ := windows.UTF16PtrFromString("SJTU Hive")
	hwnd, _, _ := procFindWindowW.Call(0, uintptr(unsafe.Pointer(title)))
	if hwnd == 0 {
		log.Fatal("SJTU window not found")
	}
	// Prefer the WebView2 host child: the top-level window's non-client frame is
	// not useful and PrintWindow on it can return a black client area.
	selected := hwnd
	callback := windows.NewCallback(func(child HWND, _ uintptr) uintptr {
		className := make([]uint16, 256)
		procGetClassNameW.Call(uintptr(child), uintptr(unsafe.Pointer(&className[0])), uintptr(len(className)))
		if windows.UTF16ToString(className) == "Chrome_WidgetWin_1" {
			selected = uintptr(child)
			return 0
		}
		return 1
	})
	procEnumChildWindows.Call(hwnd, callback, 0)
	hwnd = selected
	var windowRect rect
	procGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&windowRect)))
	width := int(windowRect.right - windowRect.left)
	height := int(windowRect.bottom - windowRect.top)
	if width <= 0 || height <= 0 {
		log.Fatalf("invalid window size %dx%d", width, height)
	}
	hdc, _, _ := procGetWindowDC.Call(hwnd)
	mdc, _, _ := procCreateCompatibleDC.Call(hdc)
	bmp, _, _ := procCreateCompatibleBitmap.Call(hdc, uintptr(width), uintptr(height))
	procSelectObject.Call(mdc, bmp)
	procPrintWindow.Call(hwnd, mdc, 2)
	info := bitmapInfo{}
	info.header.size = uint32(unsafe.Sizeof(info.header))
	info.header.width = int32(width)
	info.header.height = -int32(height)
	info.header.planes = 1
	info.header.bitCount = 32
	pixels := make([]byte, width*height*4)
	procGetDIBits.Call(mdc, bmp, 0, uintptr(height), uintptr(unsafe.Pointer(&pixels[0])), uintptr(unsafe.Pointer(&info)), 0)
	imageData := image.NewRGBA(image.Rect(0, 0, width, height))
	for y := 0; y < height; y++ {
		for x := 0; x < width; x++ {
			offset := (y*width + x) * 4
			imageData.SetRGBA(x, y, color.RGBA{R: pixels[offset+2], G: pixels[offset+1], B: pixels[offset], A: 255})
		}
	}
	target := "docs/screenshots/wails-native.png"
	if len(os.Args) > 1 {
		target = os.Args[1]
	}
	file, err := os.Create(target)
	if err != nil {
		log.Fatal(err)
	}
	defer file.Close()
	if err := png.Encode(file, imageData); err != nil {
		log.Fatal(err)
	}
	procDeleteObject.Call(bmp)
	procDeleteDC.Call(mdc)
	procReleaseDC.Call(hwnd, hdc)
	fmt.Printf("saved %s (%dx%d)\n", target, width, height)
}
