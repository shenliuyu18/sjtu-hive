package app

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLegacyDataMigration(t *testing.T) {
	root := t.TempDir()
	legacy := filepath.Join(root, "SJTU")
	target := filepath.Join(root, "SJTU Hive")
	if err := os.MkdirAll(filepath.Join(legacy, "secrets"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(legacy, "sjtu.db"), []byte("database"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(legacy, "secrets", "shuiyuan-key.bin"), []byte("sealed"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := migrateLegacyData(target, filepath.Join(root, "local"), legacy, filepath.Join(root, "missing-local")); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	for _, relative := range []string{"sjtu.db", filepath.Join("secrets", "shuiyuan-key.bin")} {
		data, err := os.ReadFile(filepath.Join(target, relative))
		if err != nil || string(data) == "" {
			t.Fatalf("missing migrated %s: %v", relative, err)
		}
	}
	entries, err := os.ReadDir(root)
	if err != nil {
		t.Fatal(err)
	}
	parked := false
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), "SJTU.migrated-") {
			parked = true
		}
		if entry.Name() == "SJTU" {
			t.Fatal("legacy directory should be parked aside, not left in place")
		}
	}
	if !parked {
		t.Fatal("expected a .migrated- backup of the legacy directory")
	}

	// A second launch must not touch anything.
	if err := migrateLegacyData(target, filepath.Join(root, "local"), legacy, filepath.Join(root, "missing-local")); err != nil {
		t.Fatalf("second migrate: %v", err)
	}
	if _, err := os.Stat(target); err != nil {
		t.Fatalf("target removed: %v", err)
	}

	// An existing database means the new profile wins; legacy data stays untouched.
	second := filepath.Join(root, "second")
	secondLegacy := filepath.Join(root, "SJTU-second")
	if err := os.MkdirAll(second, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(second, "sjtu.db"), []byte("newer"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(secondLegacy, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(secondLegacy, "sjtu.db"), []byte("older"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := migrateLegacyData(second, filepath.Join(root, "local2"), secondLegacy, ""); err != nil {
		t.Fatalf("third migrate: %v", err)
	}
	data, err := os.ReadFile(filepath.Join(second, "sjtu.db"))
	if err != nil || string(data) != "newer" {
		t.Fatalf("existing profile overwritten: %q %v", data, err)
	}
	if _, err := os.Stat(secondLegacy); err != nil {
		t.Fatalf("legacy dir should remain when the new profile exists: %v", err)
	}
}
