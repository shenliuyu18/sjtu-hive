//go:build windows

package app

// 办理页窗口：Wails v2 没有多窗口 API，这里单独起一个 UI 线程，
// 用 go-webview2 创建一个独立的 WebView2 窗口，并在导航前写入
// 从 CampusSession 拿到的 jAccount Cookie，避免二次登录。

import (
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"unsafe"

	"github.com/wailsapp/go-webview2/pkg/edge"
	"golang.org/x/sys/windows"
)

const (
	serviceWindowClass = "SJTUHiveServiceForm"
	serviceWindowTitle = "SJTU Hive · 办事大厅"

	wmSize          = 0x0005
	wmActivate      = 0x0006
	wmClose         = 0x0010
	wmDestroy       = 0x0002
	wmNcLButtonDown = 0x00A1
	wmSysCommand    = 0x0112

	scClose = 0xF060
	htClose = 20

	swpNoSize     = 0x0001
	swpNoMove     = 0x0002
	swpShowWindow = 0x0040
	hwndTop       = 0

	swShowNormal = 1
	cwUseDefault = 0x80000000
	wsOverlapped = 0x00CF0000
	csRedraw     = 0x0003
	csVRedraw    = 0x0001
	idcArrow     = 32512
	idiApp       = 32512
	coinitAPT    = 0x2
)

var (
	serviceUser32 = windows.NewLazySystemDLL("user32.dll")
	serviceOle32  = windows.NewLazySystemDLL("ole32.dll")

	procShowWindow       = serviceUser32.NewProc("ShowWindow")
	procUpdateWindow     = serviceUser32.NewProc("UpdateWindow")
	procAdjustWindowRect = serviceUser32.NewProc("AdjustWindowRect")
	procSetWindowTextW   = serviceUser32.NewProc("SetWindowTextW")
	procLoadCursorW      = serviceUser32.NewProc("LoadCursorW")
	procSetForeground    = serviceUser32.NewProc("SetForegroundWindow")
	procBringWindowToTop = serviceUser32.NewProc("BringWindowToTop")
	procIsWindowEnabled  = serviceUser32.NewProc("IsWindowEnabled")
	procCoInitializeEx   = serviceOle32.NewProc("CoInitializeEx")
	procCoUninitialize   = serviceOle32.NewProc("CoUninitialize")

	serviceWindowsMu sync.Mutex
	serviceWindows   = map[uintptr]*edge.Chromium{}
	serviceLogger    *slog.Logger
	serviceClassOnce sync.Once
	serviceClassErr  error
)

type wndClassExW struct {
	Size       uint32
	Style      uint32
	WndProc    uintptr
	ClsExtra   int32
	WndExtra   int32
	Instance   windows.Handle
	Icon       windows.Handle
	Cursor     windows.Handle
	Background windows.Handle
	MenuName   *uint16
	ClassName  *uint16
	IconSm     windows.Handle
}

type winRect struct{ Left, Top, Right, Bottom int32 }

type winPoint struct{ X, Y int32 }

type winMsg struct {
	Hwnd     uintptr
	Message  uint32
	pad0     uint32
	WParam   uintptr
	LParam   uintptr
	Time     uint32
	Pt       winPoint
	LPrivate uint32
	pad1     uint32
}

func validateServiceLink(link string) error {
	parsed, err := url.Parse(strings.TrimSpace(link))
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
		return errors.New("办理地址无效")
	}
	host := strings.ToLower(parsed.Hostname())
	if host == "sjtu.edu.cn" || strings.HasSuffix(host, ".sjtu.edu.cn") {
		return nil
	}
	return errors.New("办理地址不属于交大域名，已阻止打开")
}

func (s *PortalServices) OpenForm(link, name string) error {
	if err := validateServiceLink(link); err != nil {
		return err
	}
	if !s.campus.Connected() {
		return errors.New("请先登录教学网")
	}
	title := strings.TrimSpace(name)
	if title == "" {
		title = "办事大厅"
	}
	cookies := s.campus.CookieSnapshot()
	logger := s.logger
	serviceLogger = logger
	paths := s.paths
	go func() {
		if err := runServiceWindow(link, title, cookies, paths, logger); err != nil && logger != nil {
			logger.Warn("service window failed", "error", err)
		}
	}()
	return nil
}

func registerServiceClass(wndProc uintptr, instance windows.Handle) error {
	serviceClassOnce.Do(func() {
		cursor, _, _ := procLoadCursorW.Call(0, idcArrow)
		icon, _, _ := procLoadIconW.Call(0, idiApp)
		name, err := windows.UTF16PtrFromString(serviceWindowClass)
		if err != nil {
			serviceClassErr = err
			return
		}
		class := wndClassExW{
			Size:       uint32(unsafe.Sizeof(wndClassExW{})),
			Style:      csRedraw | csVRedraw,
			WndProc:    wndProc,
			Instance:   instance,
			Icon:       windows.Handle(icon),
			Cursor:     windows.Handle(cursor),
			Background: 6, // COLOR_WINDOW+1
			ClassName:  name,
		}
		result, _, callErr := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&class)))
		if result == 0 {
			serviceClassErr = fmt.Errorf("RegisterClassExW: %v", callErr)
		}
	})
	return serviceClassErr
}

func serviceWindowProc(hwnd, message, wparam, lparam uintptr) uintptr {
	switch message {
	case wmSize:
		serviceWindowsMu.Lock()
		chromium := serviceWindows[hwnd]
		serviceWindowsMu.Unlock()
		if chromium != nil {
			chromium.Resize()
		}
		return 0
	case wmActivate:
		if serviceLogger != nil {
			enabled, _, _ := procIsWindowEnabled.Call(hwnd)
			serviceLogger.Info("service window activate",
				"hwnd", fmt.Sprintf("%x", hwnd), "state", wparam&0xFFFF, "enabled", enabled != 0)
		}
		break
	case wmNcLButtonDown:
		if serviceLogger != nil {
			serviceLogger.Info("service window nc click",
				"hwnd", fmt.Sprintf("%x", hwnd), "hit", int32(wparam))
		}
		// 关闭按钮自己处理，避免窗口失焦/被遮挡时 DefWindowProc 漏掉这次点击。
		if int32(wparam) == htClose {
			procPostMessageW.Call(hwnd, wmClose, 0, 0)
			return 0
		}
		break
	case wmSysCommand:
		if wparam&0xFFF0 == scClose {
			if serviceLogger != nil {
				serviceLogger.Info("service window sc close", "hwnd", fmt.Sprintf("%x", hwnd))
			}
			procPostMessageW.Call(hwnd, wmClose, 0, 0)
			return 0
		}
		break
	case wmClose:
		// 先让 WebView2 停止回调，再做窗口销毁；避免销毁过程中浏览器进程仍向该 HWND 发消息。
		serviceWindowsMu.Lock()
		chromium := serviceWindows[hwnd]
		serviceWindowsMu.Unlock()
		if chromium != nil {
			chromium.ShuttingDown()
		}
		result, _, callErr := procDestroyWindow.Call(hwnd)
		if serviceLogger != nil {
			serviceLogger.Info("service window close",
				"hwnd", fmt.Sprintf("%x", hwnd), "result", result, "error", callErr)
		}
		return 0
	case wmDestroy:
		serviceWindowsMu.Lock()
		chromium := serviceWindows[hwnd]
		delete(serviceWindows, hwnd)
		serviceWindowsMu.Unlock()
		if serviceLogger != nil {
			serviceLogger.Info("service window destroyed", "hwnd", fmt.Sprintf("%x", hwnd))
		}
		if chromium != nil {
			chromium.ShuttingDown()
		}
		procPostQuitMessage.Call(0)
		return 0
	}
	result, _, _ := procDefWindowProcW.Call(hwnd, message, wparam, lparam)
	return result
}

func runServiceWindow(link, name string, cookies []cookieRecord, paths Paths, logger *slog.Logger) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	if hr, _, _ := procCoInitializeEx.Call(0, coinitAPT); int32(hr) < 0 && int32(hr) != -2147417850 {
		return fmt.Errorf("CoInitializeEx: %#x", hr)
	}
	defer procCoUninitialize.Call()

	instance, _, _ := procGetModuleHandleW.Call(0)
	wndProc := windows.NewCallback(serviceWindowProc)
	if err := registerServiceClass(wndProc, windows.Handle(instance)); err != nil {
		return err
	}

	className, err := windows.UTF16PtrFromString(serviceWindowClass)
	if err != nil {
		return err
	}
	title, err := windows.UTF16PtrFromString(serviceWindowTitle)
	if err != nil {
		return err
	}

	const (
		clientWidth  = 1240
		clientHeight = 860
	)
	frame := winRect{Left: 0, Top: 0, Right: clientWidth, Bottom: clientHeight}
	procAdjustWindowRect.Call(uintptr(unsafe.Pointer(&frame)), wsOverlapped, 0)
	width := frame.Right - frame.Left
	height := frame.Bottom - frame.Top

	hwnd, _, callErr := procCreateWindowExW.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(title)),
		wsOverlapped,
		uintptr(cwUseDefault),
		uintptr(cwUseDefault),
		uintptr(width),
		uintptr(height),
		0, 0,
		instance,
		0,
	)
	if hwnd == 0 {
		return fmt.Errorf("CreateWindowExW: %v", callErr)
	}
	if name != "" && name != "办事大厅" {
		if value, err := windows.UTF16PtrFromString(serviceWindowTitle + " · " + name); err == nil {
			procSetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(value)))
		}
	}

	procShowWindow.Call(hwnd, swShowNormal)
	procUpdateWindow.Call(hwnd)
	// 置顶并抢到前台：窗口刚创建时会被主界面盖住，用户会误以为没有打开。
	procSetWindowPos.Call(hwnd, hwndTop, 0, 0, 0, 0,
		swpNoSize|swpNoMove|swpShowWindow)
	procBringWindowToTop.Call(hwnd)
	procSetForeground.Call(hwnd)

	chromium := edge.NewChromium()
	chromium.DataPath = filepath.Join(paths.CacheDir, "service-webview")
	chromium.SetErrorCallback(func(err error) {
		if err != nil {
			_, _ = fmt.Fprintln(os.Stderr, "service webview:", err)
		}
	})
	serviceWindowsMu.Lock()
	serviceWindows[hwnd] = chromium
	serviceWindowsMu.Unlock()

	if !chromium.Embed(hwnd) {
		serviceWindowsMu.Lock()
		delete(serviceWindows, hwnd)
		serviceWindowsMu.Unlock()
		return errors.New("内嵌窗口初始化失败")
	}
	chromium.Resize()
	_ = chromium.Show()
	injectServiceCookies(chromium, cookies)
	if logger != nil {
		logger.Info("service form ready", "url", link, "cookies", len(cookies))
	}
	chromium.Navigate(link)

	var message winMsg
	for {
		result, _, callErr := procGetMessageW.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if int32(result) <= 0 {
			if serviceLogger != nil {
				serviceLogger.Info("service window loop exit",
					"hwnd", fmt.Sprintf("%x", hwnd), "result", int32(result), "error", callErr)
			}
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&message)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&message)))
	}
	return nil
}

func injectServiceCookies(chromium *edge.Chromium, cookies []cookieRecord) {
	manager, err := chromium.GetCookieManager()
	if err != nil {
		return
	}
	defer manager.Release()
	for _, record := range cookies {
		domain := strings.TrimSpace(record.Domain)
		if domain == "" || !strings.Contains(domain, ".") {
			continue
		}
		path := record.Path
		if path == "" {
			path = "/"
		}
		cookie, err := manager.CreateCookie(record.Name, record.Value, domain, path)
		if err != nil {
			continue
		}
		if !record.Expires.IsZero() {
			_ = cookie.PutExpires(float64(record.Expires.Unix()))
		}
		_ = cookie.PutIsSecure(true)
		_ = cookie.PutIsHttpOnly(record.HttpOnly)
		_ = cookie.PutSameSite(0) // None: form pages load cross-site resources
		_ = manager.AddOrUpdateCookie(cookie)
		cookie.Release()
	}
}
