package app

import (
	"os"
	"path/filepath"
	goruntime "runtime"
)

const (
	AppName = "SJTU Hive"
	// LegacyAppName is the pre-rename product name still on disk for upgrades.
	LegacyAppName = "SJTU"
	Version       = "1.3.3-beta.3"
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

type platformPathSet struct {
	Data, Cache, Logs, WebView, Backups, Downloads string
}

func LoadPaths() (Paths, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return Paths{}, err
	}
	dirs, err := platformPaths(home)
	if err != nil {
		return Paths{}, err
	}
	paths := Paths{
		DataDir:      dirs.Data,
		CacheDir:     dirs.Cache,
		LogDir:       dirs.Logs,
		WebViewDir:   dirs.WebView,
		DownloadsDir: dirs.Downloads,
		SecretsDir:   filepath.Join(dirs.Data, "secrets"),
		BackupDir:    dirs.Backups,
		CanvasDir:    filepath.Join(dirs.Cache, "canvas"),
		MailDir:      filepath.Join(dirs.Cache, "mail"),
		EdgeProfile:  filepath.Join(dirs.Cache, "browser-login"),
	}
	if goruntime.GOOS == "windows" {
		appData := os.Getenv("APPDATA")
		localAppData := os.Getenv("LOCALAPPDATA")
		_ = migrateLegacyData(paths.DataDir, filepath.Dir(paths.CacheDir),
			filepath.Join(appData, LegacyAppName), filepath.Join(localAppData, LegacyAppName))
	}
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
