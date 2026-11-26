//go:build !windows && !darwin

package app

func systemFontBasePx() float64 {
	return browserDefaultFontPx
}
