//go:build windows

package app

import "golang.org/x/sys/windows/registry"

// systemFontBasePx reports the text size the OS considers default.
// WebView2 already scales CSS pixels with the display DPI, but Chromium ignores
// the Windows "make text bigger" slider, so it is folded into the base size here.
func systemFontBasePx() float64 {
	key, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Accessibility`, registry.QUERY_VALUE)
	if err != nil {
		return browserDefaultFontPx
	}
	defer key.Close()
	factor, _, err := key.GetIntegerValue("TextScaleFactor")
	if err != nil || factor < 100 || factor > 300 {
		return browserDefaultFontPx
	}
	return browserDefaultFontPx * float64(factor) / 100
}
