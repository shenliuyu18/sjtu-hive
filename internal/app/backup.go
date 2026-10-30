package app

import (
	"archive/zip"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"time"

	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

func (a *App) ExportBackup() (string, error) {
	defaultName := "SJTU-backup-" + time.Now().Format("20060102-150405") + ".zip"
	target, err := wruntime.SaveFileDialog(a.ctx, wruntime.SaveDialogOptions{
		Title:            "导出本地数据",
		DefaultFilename:  defaultName,
		DefaultDirectory: a.paths.DownloadsDir,
		Filters:          []wruntime.FileFilter{{DisplayName: "ZIP 压缩包 (*.zip)", Pattern: "*.zip"}},
	})
	if err != nil {
		return "", err
	}
	if target == "" {
		return "", nil
	}
	tempDir, err := os.MkdirTemp("", "sjtu-backup-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(tempDir)
	dbPath := filepath.Join(tempDir, "sjtu.db")
	if _, err := a.db.Exec(`VACUUM INTO ?`, dbPath); err != nil {
		return "", err
	}
	file, err := os.Create(target)
	if err != nil {
		return "", err
	}
	defer file.Close()
	writer := zip.NewWriter(file)
	addFile := func(name, path string) error {
		input, err := os.Open(path)
		if err != nil {
			return err
		}
		defer input.Close()
		info, err := input.Stat()
		if err != nil {
			return err
		}
		header, err := zip.FileInfoHeader(info)
		if err != nil {
			return err
		}
		header.Name = name
		header.Method = zip.Deflate
		entry, err := writer.CreateHeader(header)
		if err != nil {
			return err
		}
		_, err = io.Copy(entry, input)
		return err
	}
	if err := addFile("sjtu.db", dbPath); err != nil {
		_ = writer.Close()
		return "", err
	}
	settingsJSON, _ := json.MarshalIndent(a.settings.State(), "", "  ")
	if err := addBytes(writer, "settings.json", settingsJSON); err != nil {
		_ = writer.Close()
		return "", err
	}
	if a.workspace != nil {
		if state, err := a.workspace.State(); err == nil {
			projectsJSON, _ := json.MarshalIndent(state["projects"], "", "  ")
			if err := addBytes(writer, "projects.json", projectsJSON); err != nil {
				_ = writer.Close()
				return "", err
			}
		}
	}
	readme := []byte("SJTU 本地数据备份\n\n" +
		"包含：SQLite 数据库、应用设置、项目列表。\n" +
		"不包含：Canvas Token、邮箱密码、校园 Cookie、水源密钥（这些凭据由 Windows DPAPI 加密，换机后需要重新配置）。\n" +
		"笔记与项目文件保存在各自的磁盘目录中，不在本压缩包内。\n")
	if err := addBytes(writer, "README.txt", readme); err != nil {
		_ = writer.Close()
		return "", err
	}
	if err := writer.Close(); err != nil {
		return "", err
	}
	return target, nil
}

func addBytes(writer *zip.Writer, name string, data []byte) error {
	header := &zip.FileHeader{Name: name, Method: zip.Deflate}
	header.SetMode(0o644)
	entry, err := writer.CreateHeader(header)
	if err != nil {
		return err
	}
	_, err = entry.Write(data)
	return err
}
