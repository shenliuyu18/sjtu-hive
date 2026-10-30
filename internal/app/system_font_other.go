//go:build !windows

package app

func systemFontBasePx() float64 {
	return browserDefaultFontPx
}
