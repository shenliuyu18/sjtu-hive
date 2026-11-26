//go:build !windows && !darwin

package app

import (
	"os"
	"path/filepath"
)

func platformPaths(home string) (platformPathSet, error) {
	data := os.Getenv("XDG_DATA_HOME")
	if data == "" {
		data = filepath.Join(home, ".local", "share")
	}
	cache := os.Getenv("XDG_CACHE_HOME")
	if cache == "" {
		cache = filepath.Join(home, ".cache")
	}
	return platformPathSet{
		Data:      filepath.Join(data, AppName),
		Cache:     filepath.Join(cache, AppName),
		Logs:      filepath.Join(data, AppName, "logs"),
		WebView:   filepath.Join(cache, AppName, "WebKit"),
		Backups:   filepath.Join(data, AppName, "backups"),
		Downloads: filepath.Join(home, "Downloads"),
	}, nil
}
