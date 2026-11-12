package main

import (
	"context"
	"embed"

	"github.com/wailsapp/wails/v2"
	"github.com/wailsapp/wails/v2/pkg/options"
	"github.com/wailsapp/wails/v2/pkg/options/assetserver"
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
		Frameless: true,
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
			IsZoomControlEnabled: false,
		},
		Debug: options.Debug{OpenInspectorOnStartup: false},
	})

	if err != nil {
		panic(err)
	}
}
