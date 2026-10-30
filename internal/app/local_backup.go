package app

import (
	"database/sql"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const localBackupInterval = 15 * time.Minute

func copyFileAtomic(source, target string) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	temp, err := os.CreateTemp(filepath.Dir(target), ".sjtu-backup-*")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if _, err := io.Copy(temp, input); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Sync(); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, target)
}

func restoreLocalBackup(paths Paths, force bool, logger *slog.Logger) (bool, error) {
	latest := filepath.Join(paths.BackupDir, "latest")
	backupDB := filepath.Join(latest, "sjtu.db")
	if info, err := os.Stat(backupDB); err != nil || info.Size() == 0 {
		return false, nil
	}
	dbPath := filepath.Join(paths.DataDir, "sjtu.db")
	if info, err := os.Stat(dbPath); err == nil && info.Size() > 0 && !force {
		return false, nil
	}
	if force {
		if _, err := os.Stat(dbPath); err == nil {
			corrupt := fmt.Sprintf("%s.corrupt-%s", dbPath, time.Now().Format("20060102-150405"))
			_ = os.Rename(dbPath, corrupt)
		}
	}
	if err := copyFileAtomic(backupDB, dbPath); err != nil {
		return false, err
	}
	entries, _ := os.ReadDir(filepath.Join(latest, "secrets"))
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".bin") {
			continue
		}
		target := filepath.Join(paths.SecretsDir, entry.Name())
		if _, err := os.Stat(target); err == nil {
			continue
		}
		if err := copyFileAtomic(filepath.Join(latest, "secrets", entry.Name()), target); err != nil && logger != nil {
			logger.Warn("restore secret failed", "name", entry.Name(), "error", err)
		}
	}
	return true, nil
}

func snapshotLocalBackup(db *sql.DB, paths Paths, logger *slog.Logger) error {
	if db == nil {
		return nil
	}
	tempDir, err := os.MkdirTemp(paths.BackupDir, ".snapshot-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(tempDir)
	tempDB := filepath.Join(tempDir, "sjtu.db")
	if _, err := db.Exec(`VACUUM INTO ?`, tempDB); err != nil {
		return err
	}
	secretsDir := filepath.Join(tempDir, "secrets")
	if err := os.MkdirAll(secretsDir, 0o700); err != nil {
		return err
	}
	entries, _ := os.ReadDir(paths.SecretsDir)
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".bin") {
			continue
		}
		if err := copyFileAtomic(filepath.Join(paths.SecretsDir, entry.Name()), filepath.Join(secretsDir, entry.Name())); err != nil && logger != nil {
			logger.Warn("backup secret failed", "name", entry.Name(), "error", err)
		}
	}
	latest := filepath.Join(paths.BackupDir, "latest")
	previous := latest + ".previous"
	_ = os.RemoveAll(previous)
	if _, err := os.Stat(latest); err == nil {
		if err := os.Rename(latest, previous); err != nil {
			return err
		}
	}
	if err := os.Rename(tempDir, latest); err != nil {
		_ = os.Rename(previous, latest)
		return err
	}
	_ = os.RemoveAll(previous)
	return nil
}

func (a *App) snapshotLocalBackup() {
	if a == nil || a.db == nil {
		return
	}
	if err := snapshotLocalBackup(a.db, a.paths, a.logger); err != nil && a.logger != nil {
		a.logger.Warn("local backup failed", "error", err)
	}
}

func (a *App) startLocalBackupLoop() {
	go func() {
		a.snapshotLocalBackup()
		ticker := time.NewTicker(localBackupInterval)
		defer ticker.Stop()
		for {
			select {
			case <-a.ctx.Done():
				return
			case <-ticker.C:
				a.snapshotLocalBackup()
			}
		}
	}()
}
