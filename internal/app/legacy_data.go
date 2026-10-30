package app

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"time"
)

// The application was renamed from "SJTU" to "SJTU Hive". Everything on disk is
// keyed by the product name, so the first launch under the new name copies the old
// folders over and then renames them aside instead of deleting them.
func migrateLegacyData(newRoaming, newLocal, legacyRoaming, legacyLocal string) error {
	if err := mergeLegacyDir(legacyRoaming, newRoaming, "sjtu.db"); err != nil {
		return err
	}
	return mergeLegacyDir(legacyLocal, newLocal, "")
}

// mergeLegacyDir copies legacy into target when target is missing the marker file
// (any file, when marker is empty), then parks the legacy directory aside.
func mergeLegacyDir(legacy, target, marker string) error {
	if legacy == "" || legacy == target {
		return nil
	}
	info, err := os.Stat(legacy)
	if err != nil || !info.IsDir() {
		return nil
	}
	if marker == "" {
		if entries, readErr := os.ReadDir(target); readErr == nil && len(entries) > 0 {
			return nil
		}
	} else {
		if _, statErr := os.Stat(filepath.Join(target, marker)); statErr == nil {
			return nil
		}
	}
	if err := os.MkdirAll(target, 0o755); err != nil {
		return err
	}
	if err := copyTree(legacy, target); err != nil {
		return err
	}
	parked := legacy + ".migrated-" + time.Now().Format("20060102-150405")
	return os.Rename(legacy, parked)
}

func copyTree(source, target string) error {
	return filepath.WalkDir(source, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		relative, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		destination := filepath.Join(target, relative)
		if entry.IsDir() {
			return os.MkdirAll(destination, 0o755)
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if existing, statErr := os.Stat(destination); statErr == nil && !existing.ModTime().Before(info.ModTime()) {
			return nil
		}
		return copyLegacyFile(path, destination, info.Mode())
	})
}

func copyLegacyFile(source, target string, mode os.FileMode) error {
	from, err := os.Open(source)
	if err != nil {
		return err
	}
	defer from.Close()
	to, err := os.OpenFile(target+".tmp", os.O_CREATE|os.O_TRUNC|os.O_WRONLY, mode)
	if err != nil {
		return err
	}
	written, copyErr := io.Copy(to, from)
	closeErr := to.Close()
	if copyErr != nil || closeErr != nil {
		_ = os.Remove(target + ".tmp")
		if copyErr == nil {
			copyErr = closeErr
		}
		return copyErr
	}
	if written == 0 {
		return errors.New("复制文件为空")
	}
	return os.Rename(target+".tmp", target)
}
