//go:build windows

package app

import (
	"errors"
	"fmt"
	"log/slog"
	"runtime"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	trayUser32   = windows.NewLazySystemDLL("user32.dll")
	trayShell32  = windows.NewLazySystemDLL("shell32.dll")
	trayKernel32 = windows.NewLazySystemDLL("kernel32.dll")

	procRegisterClassExW    = trayUser32.NewProc("RegisterClassExW")
	procCreateWindowExW     = trayUser32.NewProc("CreateWindowExW")
	procDefWindowProcW      = trayUser32.NewProc("DefWindowProcW")
	procGetMessageW         = trayUser32.NewProc("GetMessageW")
	procTranslateMessage    = trayUser32.NewProc("TranslateMessage")
	procDispatchMessageW    = trayUser32.NewProc("DispatchMessageW")
	procDestroyWindow       = trayUser32.NewProc("DestroyWindow")
	procPostQuitMessage     = trayUser32.NewProc("PostQuitMessage")
	procPostMessageW        = trayUser32.NewProc("PostMessageW")
	procCreatePopupMenu     = trayUser32.NewProc("CreatePopupMenu")
	procDestroyMenu         = trayUser32.NewProc("DestroyMenu")
	procAppendMenuW         = trayUser32.NewProc("AppendMenuW")
	procTrackPopupMenuEx    = trayUser32.NewProc("TrackPopupMenuEx")
	procSetForegroundWindow = trayUser32.NewProc("SetForegroundWindow")
	procGetCursorPos        = trayUser32.NewProc("GetCursorPos")
	procGetModuleHandleW    = trayKernel32.NewProc("GetModuleHandleW")
	procLoadIconW           = trayUser32.NewProc("LoadIconW")
	procShellNotifyIconW    = trayShell32.NewProc("Shell_NotifyIconW")
	procExtractIconExW      = trayShell32.NewProc("ExtractIconExW")
)

const (
	trayWSPopup = 0x80000000

	trayWMDestroy      = 0x0002
	trayWMClose        = 0x0010
	trayWMLButtonUp    = 0x0202
	trayWMLButtonDblCl = 0x0203
	trayWMRButtonUp    = 0x0205
	trayWMTrayIcon     = 0x0400 + 1

	trayNifMessage = 0x1
	trayNifIcon    = 0x2
	trayNifTip     = 0x4
	trayNifInfo    = 0x10

	trayNimAdd          = 0x0
	trayNimModify       = 0x1
	trayNimDelete       = 0x2
	trayNimSetVersion   = 0x4
	trayNotifyVersionV4 = 4

	trayNiifInfo = 0x1

	trayMfString = 0x0

	trayTpmReturnCmd  = 0x100
	trayTpmRightAlign = 0x8

	trayMenuOpen = 1
	trayMenuQuit = 2
)

type trayPoint struct {
	x, y int32
}

type trayMsg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	ptX     int32
	ptY     int32
}

type trayWndClassEx struct {
	size          uint32
	style         uint32
	lpfnWndProc   uintptr
	cbClsExtra    int32
	cbWndExtra    int32
	hInstance     uintptr
	hIcon         uintptr
	hCursor       uintptr
	hbrBackground uintptr
	lpszMenuName  *uint16
	lpszClassName *uint16
	hIconSm       uintptr
}

type trayGUID struct {
	data1 uint32
	data2 uint16
	data3 uint16
	data4 [8]byte
}

type trayNotifyIconData struct {
	size         uint32
	hwnd         uintptr
	id           uint32
	flags        uint32
	callbackMsg  uint32
	icon         uintptr
	tip          [128]uint16
	state        uint32
	stateMask    uint32
	info         [256]uint16
	timeoutOrVer uint32
	infoTitle    [64]uint16
	infoFlags    uint32
	guidItem     trayGUID
	balloonIcon  uintptr
}

// Tray owns the notification-area icon. The same icon carries balloon/toast
// notifications, which Windows shows without an AppUserModelID registration.
type Tray struct {
	exePath string
	tooltip string
	logger  *slog.Logger
	onOpen  func()
	onQuit  func()

	ready chan error
	done  chan struct{}

	mu    sync.Mutex
	nid   trayNotifyIconData
	hwnd  uintptr
	added bool

	closeOnce sync.Once
}

var activeTray *Tray

func NewTray(exePath, tooltip string, logger *slog.Logger, onOpen, onQuit func()) (*Tray, error) {
	tray := &Tray{
		exePath: exePath,
		tooltip: tooltip,
		logger:  logger,
		onOpen:  onOpen,
		onQuit:  onQuit,
		ready:   make(chan error, 1),
		done:    make(chan struct{}),
	}
	go tray.run()
	select {
	case err := <-tray.ready:
		if err != nil {
			return nil, err
		}
		return tray, nil
	case <-tray.done:
		if err := <-tray.ready; err != nil {
			return nil, err
		}
		return nil, errors.New("托盘线程已退出")
	}
}

func (t *Tray) run() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	defer close(t.done)
	// A tray bug must never take the whole application down with it.
	defer func() {
		if recovered := recover(); recovered != nil {
			report := fmt.Errorf("托盘线程异常：%v", recovered)
			select {
			case t.ready <- report:
			default:
			}
			if t.logger != nil {
				t.logger.Error("tray panic", "panic", report)
			}
		}
	}()

	instance, _, _ := procGetModuleHandleW.Call(0)
	className, err := windows.UTF16PtrFromString("SJTUTrayMessageWindow")
	if err != nil {
		t.ready <- err
		return
	}
	windowTitle, err := windows.UTF16PtrFromString("SJTU 托盘")
	if err != nil {
		t.ready <- err
		return
	}

	class := trayWndClassEx{
		lpfnWndProc:   windows.NewCallback(trayWndProc),
		hInstance:     instance,
		lpszClassName: className,
	}
	class.size = uint32(unsafe.Sizeof(class))
	registration, _, err := procRegisterClassExW.Call(uintptr(unsafe.Pointer(&class)))
	if registration == 0 {
		t.ready <- fmt.Errorf("注册托盘窗口类失败：%w", err)
		return
	}

	// A hidden top-level window is required: message-only windows do not receive
	// Shell_NotifyIcon callbacks.
	hwnd, _, err := procCreateWindowExW.Call(
		0,
		uintptr(unsafe.Pointer(className)),
		uintptr(unsafe.Pointer(windowTitle)),
		trayWSPopup,
		0, 0, 0, 0,
		0,
		0,
		instance,
		0,
	)
	if hwnd == 0 {
		t.ready <- fmt.Errorf("创建托盘窗口失败：%w", err)
		return
	}
	t.hwnd = hwnd
	activeTray = t

	if err := t.addIcon(); err != nil {
		t.ready <- err
		procDestroyWindow.Call(hwnd)
		activeTray = nil
		return
	}
	t.ready <- nil

	var message trayMsg
	for {
		result, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&message)), 0, 0, 0)
		if result == 0 || result == ^uintptr(0) {
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&message)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&message)))
	}

	t.removeIcon()
	activeTray = nil
}

func (t *Tray) addIcon() error {
	icon := loadTrayIcon(t.exePath)
	if icon == 0 {
		return errors.New("无法加载应用图标")
	}
	nid := trayNotifyIconData{
		hwnd:        t.hwnd,
		callbackMsg: trayWMTrayIcon,
		icon:        icon,
		flags:       trayNifMessage | trayNifIcon | trayNifTip,
	}
	copyTrayText(nid.tip[:], t.tooltip)
	nid.size = uint32(unsafe.Sizeof(nid))
	if err := shellNotifyIcon(trayNimAdd, &nid); err != nil {
		return fmt.Errorf("添加托盘图标失败：%w", err)
	}
	versioned := nid
	versioned.timeoutOrVer = trayNotifyVersionV4
	_ = shellNotifyIcon(trayNimSetVersion, &versioned)
	t.nid = nid
	t.added = true
	return nil
}

func (t *Tray) removeIcon() {
	t.mu.Lock()
	defer t.mu.Unlock()
	if !t.added {
		return
	}
	_ = shellNotifyIcon(trayNimDelete, &t.nid)
	t.added = false
}

func (t *Tray) Close() {
	if t == nil {
		return
	}
	t.closeOnce.Do(func() {
		if t.hwnd != 0 {
			procPostMessageW.Call(t.hwnd, trayWMClose, 0, 0)
		}
	})
}

func shellNotifyIcon(action uint32, nid *trayNotifyIconData) error {
	result, _, err := procShellNotifyIconW.Call(uintptr(action), uintptr(unsafe.Pointer(nid)))
	if result == 0 {
		return err
	}
	return nil
}

func copyTrayText(target []uint16, value string) {
	for index := range target {
		target[index] = 0
	}
	encoded, err := windows.UTF16FromString(value)
	if err != nil {
		return
	}
	copy(target, encoded)
}

func loadTrayIcon(exePath string) uintptr {
	var large, small uintptr
	count, _, _ := procExtractIconExW.Call(
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(exePath))),
		0,
		uintptr(unsafe.Pointer(&large)),
		uintptr(unsafe.Pointer(&small)),
		1,
	)
	if count > 0 && small != 0 {
		return small
	}
	if count > 0 && large != 0 {
		return large
	}
	// IDI_APPLICATION = MAKEINTRESOURCE(32512)
	icon, _, _ := procLoadIconW.Call(0, 32512)
	return icon
}

func trayWndProc(hwnd, message, wParam, lParam uintptr) uintptr {
	tray := activeTray
	switch message {
	case trayWMTrayIcon:
		if tray == nil {
			return 0
		}
		switch uint32(lParam & 0xffff) {
		case trayWMLButtonUp, trayWMLButtonDblCl:
			if tray.onOpen != nil {
				go tray.onOpen()
			}
		case trayWMRButtonUp:
			tray.showMenu()
		}
		return 0
	case trayWMClose:
		procDestroyWindow.Call(hwnd)
		return 0
	case trayWMDestroy:
		procPostQuitMessage.Call(0)
		return 0
	}
	result, _, _ := procDefWindowProcW.Call(hwnd, message, wParam, lParam)
	return result
}

func (t *Tray) showMenu() {
	menu, _, _ := procCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	defer procDestroyMenu.Call(menu)
	openLabel, _ := windows.UTF16FromString("打开主界面")
	quitLabel, _ := windows.UTF16FromString("退出 SJTU Hive")
	procAppendMenuW.Call(menu, trayMfString, trayMenuOpen, uintptr(unsafe.Pointer(&openLabel[0])))
	procAppendMenuW.Call(menu, trayMfString, trayMenuQuit, uintptr(unsafe.Pointer(&quitLabel[0])))
	// Without foreground ownership the menu closes as soon as it appears.
	procSetForegroundWindow.Call(t.hwnd)
	var point trayPoint
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&point)))
	command, _, _ := procTrackPopupMenuEx.Call(
		menu,
		trayTpmRightAlign|trayTpmReturnCmd,
		uintptr(point.x),
		uintptr(point.y),
		t.hwnd,
		0,
	)
	switch command {
	case trayMenuOpen:
		if t.onOpen != nil {
			go t.onOpen()
		}
	case trayMenuQuit:
		if t.onQuit != nil {
			go t.onQuit()
		}
	}
}
