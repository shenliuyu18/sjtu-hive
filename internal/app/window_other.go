//go:build !windows

package app

import wruntime "github.com/wailsapp/wails/v2/pkg/runtime"

func (a *App) applyInitialWindowSize() {
	if a.ctx == nil {
		return
	}
	wruntime.WindowSetSize(a.ctx, 1440, 900)
	wruntime.WindowCenter(a.ctx)
}
