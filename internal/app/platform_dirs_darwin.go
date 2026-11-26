//go:build darwin

package app

import "path/filepath"

func platformPaths(home string) (platformPathSet, error) {
	library := filepath.Join(home, "Library")
	return platformPathSet{
		Data:      filepath.Join(library, "Application Support", AppName),
		Cache:     filepath.Join(library, "Caches", AppName),
		Logs:      filepath.Join(library, "Logs", AppName),
		WebView:   filepath.Join(library, "Caches", AppName, "WebKit"),
		Backups:   filepath.Join(library, "Application Support", AppName, "backups"),
		Downloads: filepath.Join(home, "Downloads"),
	}, nil
}
