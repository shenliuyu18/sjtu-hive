package main

import (
	"context"
	"embed"
	goruntime "runtime"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
	"github.com/wailsapp/wails/v2/pkg/options/mac"
	"github.com/wailsapp/wails/v2/pkg/options/windows"

	"sjtu-desk/internal/app"
)

//go:embed all:frontend/dist
var assets embed.FS

func main() {
	app.ApplyStartupPriority()
	instance := app.NewApp()
	paths, _ := app.LoadPaths()

	err := wails.Run(&options.App{
		Title:     "SJTU Hive",
		Width:     1440,
		Height:    900,
		MinWidth:  1024,
		MinHeight: 680,
		Frameless: goruntime.GOOS != "darwin",
		AssetServer: &assetserver.Options{
			Assets: assets,
		},
		BackgroundColour: &options.RGBA{R: 246, G: 247, B: 249, A: 1},
		OnStartup:        instance.Startup,
		OnShutdown:       instance.Shutdown,
		Bind:             []interface{}{instance},
		SingleInstanceLock: &options.SingleInstanceLock{
			UniqueId:               "sjtu-desk-single-instance",
			OnSecondInstanceLaunch: func(options.SecondInstanceData) { instance.ShowMainWindow() },
		},
		OnBeforeClose: func(context.Context) bool { return instance.PreventClose() },
		DragAndDrop: &options.DragAndDrop{
			EnableFileDrop:     true,
			DisableWebViewDrop: true,
		},
		EnableDefaultContextMenu: true,
		Windows: &windows.Options{
			WebviewUserDataPath:  paths.WebViewDir,
			Theme:                windows.SystemDefault,
			ZoomFactor:           1,
			IsZoomControlEnabled: false,
			DisablePinchZoom:     true,
		},
		Mac: &mac.Options{
			TitleBar:   mac.TitleBarHiddenInset(),
			Appearance: mac.DefaultAppearance,
			About: &mac.AboutInfo{
				Title:   "SJTU Hive",
				Message: "A local-first campus desktop app for SJTU students.",
			},
		},
		Debug: options.Debug{OpenInspectorOnStartup: false},
	})

	if err != nil {
		panic(err)
	}
}
