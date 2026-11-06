package app

import (
	"archive/zip"
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

func (a *App) ChooseBackup(encrypted bool) (string, error) {
	options := wruntime.OpenDialogOptions{
		Title:            "选择 SJTU Hive 备份",
		DefaultDirectory: a.paths.DownloadsDir,
	}
	if encrypted {
		options.Title = "选择加密备份"
		options.Filters = []wruntime.FileFilter{{DisplayName: "加密备份 (*.hivebackup)", Pattern: "*.hivebackup"}}
	} else {
		options.Title = "选择本地数据备份"
		options.Filters = []wruntime.FileFilter{{DisplayName: "数据备份 (*.zip)", Pattern: "*.zip"}}
	}
	return wruntime.OpenFileDialog(a.ctx, options)
}

func (a *App) ImportBackup(sourcePath, passphrase string, encrypted bool) (map[string]any, error) {
	return a.importBackupFile(sourcePath, passphrase, encrypted)
}

func (a *App) importBackupFile(sourcePath, passphrase string, expectEncrypted bool) (map[string]any, error) {
	sourcePath = strings.TrimSpace(sourcePath)
	if sourcePath == "" {
		return nil, errors.New("未选择备份文件")
	}
	info, err := os.Stat(sourcePath)
	if err != nil || info.IsDir() {
		return nil, errors.New("备份文件不存在")
	}
	isEncrypted := encryptedBackup(sourcePath)
	if isEncrypted && !expectEncrypted {
		return nil, errors.New("该文件已加密，请使用「导入加密备份」并输入口令")
	}
	if !isEncrypted && expectEncrypted {
		return nil, errors.New("所选文件不是加密备份（*.hivebackup）")
	}
	dir, err := os.MkdirTemp("", "sjtu-import-*")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)

	plain := sourcePath
	if expectEncrypted {
		passphrase = strings.TrimSpace(passphrase)
		if len([]rune(passphrase)) < 8 {
			return nil, errors.New("请先输入该备份的加密口令（至少 8 个字符）")
		}
		plain = filepath.Join(dir, "backup.zip")
		if err := decryptBackupFile(sourcePath, plain, passphrase); err != nil {
			return nil, err
		}
	}
	return a.restoreBackupArchive(plain, filepath.Base(sourcePath))
}

// encryptedBackup checks the magic header so a renamed *.hivebackup still asks
// for the passphrase instead of failing as a broken zip.
func encryptedBackup(path string) bool {
	if strings.EqualFold(filepath.Ext(path), ".hivebackup") {
		return true
	}
	file, err := os.Open(path)
	if err != nil {
		return false
	}
	defer file.Close()
	head := make([]byte, len(webdavMagic))
	if _, err := io.ReadFull(file, head); err != nil {
		return false
	}
	return bytes.Equal(head, webdavMagic)
}

func (a *App) restoreBackupArchive(archivePath, label string) (map[string]any, error) {
	dir, err := os.MkdirTemp("", "sjtu-restore-*")
	if err != nil {
		return nil, err
	}
	defer os.RemoveAll(dir)

	reader, err := zip.OpenReader(archivePath)
	if err != nil {
		return nil, errors.New("备份文件损坏或不是有效的压缩文件")
	}
	defer reader.Close()

	var databasePath, secretsPayload string
	extract := func(file *zip.File, target string) error {
		if file.UncompressedSize64 > 4<<30 {
			return errors.New("备份内容过大，无法导入")
		}
		source, err := file.Open()
		if err != nil {
			return err
		}
		defer source.Close()
		output, err := os.Create(target)
		if err != nil {
			return err
		}
		defer output.Close()
		_, err = io.Copy(output, io.LimitReader(source, 4<<30))
		return err
	}
	for _, file := range reader.File {
		name := filepath.Base(file.Name)
		switch name {
		case "sjtu.db":
			databasePath = filepath.Join(dir, "sjtu.db")
			if err := extract(file, databasePath); err != nil {
				return nil, fmt.Errorf("读取备份数据库失败：%w", err)
			}
		case "secrets.json":
			source, err := file.Open()
			if err != nil {
				return nil, err
			}
			data, err := io.ReadAll(io.LimitReader(source, 4<<20))
			source.Close()
			if err != nil {
				return nil, err
			}
			secretsPayload = string(data)
		}
	}
	if databasePath == "" {
		return nil, errors.New("备份中缺少数据库，无法恢复")
	}

	if a.paths.BackupDir != "" {
		a.snapshotLocalBackup()
	}
	if err := importDatabase(a.db, databasePath); err != nil {
		return nil, err
	}
	if a.settings != nil {
		if err := a.settings.Reload(); err != nil {
			return nil, fmt.Errorf("恢复设置失败：%w", err)
		}
		a.settings.ApplySystemState()
	}
	secretsRestored := 0
	if secretsPayload != "" && a.secrets != nil {
		var values map[string]json.RawMessage
		if err := json.Unmarshal([]byte(secretsPayload), &values); err != nil {
			return nil, errors.New("备份中的登录凭据格式不正确")
		}
		if err := a.secrets.ReplaceAll(values, webdavSecret); err != nil {
			return nil, fmt.Errorf("恢复登录凭据失败：%w", err)
		}
		secretsRestored = len(values)
		if a.campus != nil {
			a.campus.Reload()
		}
	}
	summary := map[string]any{
		"restored":        true,
		"name":            label,
		"secrets":         secretsRestored,
		"restartRequired": true,
	}
	a.emit("backup.restored", summary)
	return summary, nil
}

func importDatabase(db *sql.DB, sourcePath string) error {
	if db == nil {
		return errors.New("数据库尚未就绪")
	}
	if _, err := db.Exec(`ATTACH DATABASE ? AS restore`, sourcePath); err != nil {
		return fmt.Errorf("打开备份数据库失败：%w", err)
	}
	defer func() {
		_, _ = db.Exec(`DETACH DATABASE restore`)
	}()
	tables, err := listTables(db, "main")
	if err != nil {
		return err
	}
	restoreTables, err := listTables(db, "restore")
	if err != nil {
		return err
	}
	available := make(map[string]bool, len(restoreTables))
	for _, name := range restoreTables {
		available[name] = true
	}
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, table := range tables {
		if !available[table] || !validTableName(table) {
			continue
		}
		columns, err := sharedColumns(tx, table)
		if err != nil {
			return err
		}
		if len(columns) == 0 {
			continue
		}
		quoted := make([]string, len(columns))
		for index, column := range columns {
			quoted[index] = quoteIdent(column)
		}
		list := strings.Join(quoted, ", ")
		if _, err := tx.Exec(`DELETE FROM main.` + quoteIdent(table)); err != nil {
			return fmt.Errorf("清空 %s 失败：%w", table, err)
		}
		if _, err := tx.Exec(
			`INSERT INTO main.` + quoteIdent(table) + ` (` + list + `) SELECT ` + list + ` FROM restore.` + quoteIdent(table),
		); err != nil {
			return fmt.Errorf("写入 %s 失败：%w", table, err)
		}
	}
	return tx.Commit()
}

type rowQueryer interface {
	Query(string, ...any) (*sql.Rows, error)
}

func listTables(q rowQueryer, schema string) ([]string, error) {
	rows, err := q.Query(`SELECT name FROM ` + schema + `.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	names := []string{}
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			return nil, err
		}
		names = append(names, name)
	}
	return names, rows.Err()
}

func sharedColumns(q rowQueryer, table string) ([]string, error) {
	local, err := tableColumns(q, "main", table)
	if err != nil {
		return nil, err
	}
	backup, err := tableColumns(q, "restore", table)
	if err != nil {
		return nil, err
	}
	existing := make(map[string]bool, len(backup))
	for _, column := range backup {
		existing[column] = true
	}
	columns := make([]string, 0, len(local))
	for _, column := range local {
		if existing[column] {
			columns = append(columns, column)
		}
	}
	return columns, nil
}

func tableColumns(q rowQueryer, schema, table string) ([]string, error) {
	rows, err := q.Query(`PRAGMA ` + schema + `.table_info(` + quoteIdent(table) + `)`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	columns := []string{}
	for rows.Next() {
		var cid, notNull, primaryKey int
		var name, columnType string
		var defaultValue any
		if err := rows.Scan(&cid, &name, &columnType, &notNull, &defaultValue, &primaryKey); err != nil {
			return nil, err
		}
		columns = append(columns, name)
	}
	return columns, rows.Err()
}

func quoteIdent(name string) string {
	return `"` + strings.ReplaceAll(name, `"`, `""`) + `"`
}

func validTableName(name string) bool {
	if name == "" {
		return false
	}
	for _, r := range name {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '_' {
			continue
		}
		return false
	}
	return true
}
