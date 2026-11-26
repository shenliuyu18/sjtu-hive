//go:build windows

package app

import (
	"os"
	"path/filepath"
)

func platformPaths(home string) (platformPathSet, error) {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		appData = filepath.Join(home, "AppData", "Roaming")
	}
	localAppData := os.Getenv("LOCALAPPDATA")
	if localAppData == "" {
		localAppData = filepath.Join(home, "AppData", "Local")
	}
	return platformPathSet{
		Data:      filepath.Join(appData, AppName),
		Cache:     filepath.Join(localAppData, AppName, "cache"),
		Logs:      filepath.Join(appData, AppName, "logs"),
		WebView:   filepath.Join(localAppData, AppName, "EBWebView"),
		Backups:   filepath.Join(localAppData, AppName, "backups"),
		Downloads: filepath.Join(home, "Downloads"),
	}, nil
}
