package app

import (
	"os"
	"path/filepath"
)

const (
	AppName = "SJTU Hive"
	// LegacyAppName is the pre-rename product name still on disk for upgrades.
	LegacyAppName = "SJTU"
	Version       = "1.3.2"
)

type Paths struct {
	DataDir      string
	CacheDir     string
	LogDir       string
	WebViewDir   string
	DownloadsDir string
	SecretsDir   string
	BackupDir    string
	CanvasDir    string
	MailDir      string
	EdgeProfile  string
}

func LoadPaths() (Paths, error) {
	appData := os.Getenv("APPDATA")
	if appData == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return Paths{}, err
		}
		appData = filepath.Join(home, "AppData", "Roaming")
	}
	localAppData := os.Getenv("LOCALAPPDATA")
	if localAppData == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return Paths{}, err
		}
		localAppData = filepath.Join(home, "AppData", "Local")
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return Paths{}, err
	}

	legacyRoaming := filepath.Join(appData, LegacyAppName)
	legacyLocal := filepath.Join(localAppData, LegacyAppName)
	paths := Paths{
		DataDir:      filepath.Join(appData, AppName),
		CacheDir:     filepath.Join(localAppData, AppName, "cache"),
		LogDir:       filepath.Join(appData, AppName, "logs"),
		WebViewDir:   filepath.Join(localAppData, AppName, "EBWebView"),
		DownloadsDir: filepath.Join(home, "Downloads"),
		SecretsDir:   filepath.Join(appData, AppName, "secrets"),
		BackupDir:    filepath.Join(localAppData, AppName, "backups"),
		CanvasDir:    filepath.Join(localAppData, AppName, "cache", "canvas"),
		MailDir:      filepath.Join(localAppData, AppName, "cache", "mail"),
		EdgeProfile:  filepath.Join(localAppData, AppName, "edge-campus"),
	}
	_ = migrateLegacyData(paths.DataDir, filepath.Join(localAppData, AppName), legacyRoaming, legacyLocal)
	for _, dir := range []string{
		paths.DataDir,
		paths.CacheDir,
		paths.LogDir,
		paths.WebViewDir,
		paths.SecretsDir,
		paths.BackupDir,
		paths.CanvasDir,
		paths.MailDir,
		paths.EdgeProfile,
	} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return Paths{}, err
		}
	}
	return paths, nil
}
