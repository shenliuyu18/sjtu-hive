package app

import (
	"archive/zip"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func writeBackupArchive(t *testing.T, target, databasePath string, secrets map[string]any) {
	t.Helper()
	file, err := os.Create(target)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	writer := zip.NewWriter(file)
	dbData, err := os.ReadFile(databasePath)
	if err != nil {
		t.Fatal(err)
	}
	if err := addBytes(writer, "sjtu.db", dbData); err != nil {
		t.Fatal(err)
	}
	if secrets != nil {
		encoded, err := json.Marshal(secrets)
		if err != nil {
			t.Fatal(err)
		}
		if err := addBytes(writer, "secrets.json", encoded); err != nil {
			t.Fatal(err)
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestImportBackupRestoresDatabaseAndSecrets(t *testing.T) {
	root := t.TempDir()
	source, err := OpenStore(filepath.Join(root, "source.db"))
	if err != nil {
		t.Fatal(err)
	}
	sourceSettings, err := NewSettings(source, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := sourceSettings.Set("theme", "dark"); err != nil {
		t.Fatal(err)
	}
	snapshot := filepath.Join(root, "snapshot.db")
	if _, err := source.Exec(`VACUUM INTO ?`, snapshot); err != nil {
		t.Fatal(err)
	}
	_ = source.Close()

	archive := filepath.Join(root, "backup.zip")
	writeBackupArchive(t, archive, snapshot, map[string]any{
		"mail-credentials": map[string]string{"address": "me@sjtu.edu.cn"},
	})

	dest, err := OpenStore(filepath.Join(root, "dest.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer dest.Close()
	destSettings, err := NewSettings(dest, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := destSettings.Set("theme", "light"); err != nil {
		t.Fatal(err)
	}
	secrets, err := NewSecretStore(filepath.Join(root, "secrets"))
	if err != nil {
		t.Fatal(err)
	}
	if err := secrets.Save("canvas-token", map[string]string{"token": "old"}); err != nil {
		t.Fatal(err)
	}
	if err := secrets.Save(webdavSecret, map[string]string{"url": "https://dav.example.com"}); err != nil {
		t.Fatal(err)
	}

	app := &App{db: dest, settings: destSettings, secrets: secrets, paths: Paths{}}
	summary, err := app.importBackupFile(archive, "")
	if err != nil {
		t.Fatal(err)
	}
	if summary["restored"] != true {
		t.Fatalf("unexpected summary: %#v", summary)
	}
	if theme := destSettings.GetString("theme", "system"); theme != "dark" {
		t.Fatalf("theme = %q, want dark", theme)
	}
	var mail map[string]string
	ok, err := secrets.Load("mail-credentials", &mail)
	if err != nil || !ok || mail["address"] != "me@sjtu.edu.cn" {
		t.Fatalf("mail credentials not restored: ok=%v err=%v value=%#v", ok, err, mail)
	}
	if secrets.Exists("canvas-token") {
		t.Fatal("stale credentials must be removed by a full restore")
	}
	if !secrets.Exists(webdavSecret) {
		t.Fatal("webdav configuration must survive the restore")
	}
}

func TestImportEncryptedBackupRequiresPassphrase(t *testing.T) {
	root := t.TempDir()
	source, err := OpenStore(filepath.Join(root, "source.db"))
	if err != nil {
		t.Fatal(err)
	}
	sourceSettings, err := NewSettings(source, nil)
	if err != nil {
		t.Fatal(err)
	}
	secrets, err := NewSecretStore(filepath.Join(root, "secrets"))
	if err != nil {
		t.Fatal(err)
	}
	app := &App{db: source, settings: sourceSettings, secrets: secrets, paths: Paths{}}
	plain := filepath.Join(root, "backup.zip")
	if err := app.createBackupZip(plain, true); err != nil {
		t.Fatal(err)
	}
	encrypted := filepath.Join(root, "backup.hivebackup")
	if err := encryptBackupFile(plain, encrypted, "testpass123"); err != nil {
		t.Fatal(err)
	}
	_ = source.Close()

	dest, err := OpenStore(filepath.Join(root, "dest.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer dest.Close()
	destSettings, err := NewSettings(dest, nil)
	if err != nil {
		t.Fatal(err)
	}
	destSecrets, err := NewSecretStore(filepath.Join(root, "dest-secrets"))
	if err != nil {
		t.Fatal(err)
	}
	restoreApp := &App{db: dest, settings: destSettings, secrets: destSecrets, paths: Paths{}}
	if _, err := restoreApp.importBackupFile(encrypted, ""); err == nil {
		t.Fatal("expected an error without a passphrase")
	}
	if _, err := restoreApp.importBackupFile(encrypted, "wrong-password"); err == nil {
		t.Fatal("expected an error for a wrong passphrase")
	}
	if _, err := restoreApp.importBackupFile(encrypted, "testpass123"); err != nil {
		t.Fatal(err)
	}
}

func TestListWebDAVBackups(t *testing.T) {
	multistatus := `<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/dav/backups/</d:href>
    <d:propstat><d:prop><d:getcontentlength>0</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/backups/SJTU-Hive-backup-20260101-120000.hivebackup</d:href>
    <d:propstat><d:prop><d:getcontentlength>123</d:getcontentlength><d:getlastmodified>Thu, 01 Jan 2026 12:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/backups/SJTU-Hive-backup-20260201-120000.hivebackup</d:href>
    <d:propstat><d:prop><d:getcontentlength>456</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
  <d:response>
    <d:href>/dav/backups/notes.txt</d:href>
    <d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
  </d:response>
</d:multistatus>`
	var gotMethod, gotPath, gotUser, gotPass, gotDepth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		gotPath = r.URL.Path
		gotUser, gotPass, _ = r.BasicAuth()
		gotDepth = r.Header.Get("Depth")
		w.Header().Set("Content-Type", "application/xml; charset=utf-8")
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = w.Write([]byte(multistatus))
	}))
	defer server.Close()
	secrets, err := NewSecretStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	app := &App{secrets: secrets, paths: Paths{}}
	if _, err := app.SaveWebDAV(server.URL+"/dav", "backups", "user", "pass"); err != nil {
		t.Fatal(err)
	}
	backups, err := app.ListWebDAVBackups(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if gotMethod != "PROPFIND" || gotPath != "/dav/backups/" || gotDepth != "1" {
		t.Fatalf("request = %s %s depth=%s", gotMethod, gotPath, gotDepth)
	}
	if gotUser != "user" || gotPass != "pass" {
		t.Fatalf("auth = %s/%s", gotUser, gotPass)
	}
	if len(backups) != 2 {
		t.Fatalf("backups = %#v", backups)
	}
	if backups[0].Name != "SJTU-Hive-backup-20260201-120000.hivebackup" || backups[0].Size != 456 {
		t.Fatalf("unexpected first backup: %#v", backups[0])
	}
	if backups[1].Name != "SJTU-Hive-backup-20260101-120000.hivebackup" || backups[1].Size != 123 {
		t.Fatalf("unexpected second backup: %#v", backups[1])
	}
}

func TestRestoreWebDAVBackup(t *testing.T) {
	root := t.TempDir()
	source, err := OpenStore(filepath.Join(root, "source.db"))
	if err != nil {
		t.Fatal(err)
	}
	sourceSettings, err := NewSettings(source, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := sourceSettings.Set("theme", "dark"); err != nil {
		t.Fatal(err)
	}
	sourceSecrets, err := NewSecretStore(filepath.Join(root, "source-secrets"))
	if err != nil {
		t.Fatal(err)
	}
	plain := filepath.Join(root, "backup.zip")
	if err := (&App{db: source, settings: sourceSettings, secrets: sourceSecrets}).createBackupZip(plain, true); err != nil {
		t.Fatal(err)
	}
	encrypted := filepath.Join(root, "backup.hivebackup")
	if err := encryptBackupFile(plain, encrypted, "testpass123"); err != nil {
		t.Fatal(err)
	}
	payload, err := os.ReadFile(encrypted)
	if err != nil {
		t.Fatal(err)
	}
	_ = source.Close()

	const name = "SJTU-Hive-backup-20260101-120000.hivebackup"
	var gotPath, gotUser, gotPass string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotUser, gotPass, _ = r.BasicAuth()
		_, _ = w.Write(payload)
	}))
	defer server.Close()

	dest, err := OpenStore(filepath.Join(root, "dest.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer dest.Close()
	destSettings, err := NewSettings(dest, nil)
	if err != nil {
		t.Fatal(err)
	}
	secrets, err := NewSecretStore(filepath.Join(root, "secrets"))
	if err != nil {
		t.Fatal(err)
	}
	app := &App{db: dest, settings: destSettings, secrets: secrets, paths: Paths{}}
	if _, err := app.SaveWebDAV(server.URL+"/dav", "backups", "user", "pass"); err != nil {
		t.Fatal(err)
	}
	summary, err := app.RestoreWebDAVBackup(context.Background(), name, "testpass123")
	if err != nil {
		t.Fatal(err)
	}
	if summary["restored"] != true {
		t.Fatalf("unexpected summary: %#v", summary)
	}
	if gotPath != "/dav/backups/"+name || gotUser != "user" || gotPass != "pass" {
		t.Fatalf("request = %s auth=%s/%s", gotPath, gotUser, gotPass)
	}
	if theme := destSettings.GetString("theme", "system"); theme != "dark" {
		t.Fatalf("theme = %q, want dark", theme)
	}
}

func TestValidateBackupName(t *testing.T) {
	if err := validateBackupName("SJTU-Hive-backup-20260101-120000.hivebackup"); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"", "../evil.hivebackup", "dir/evil.hivebackup", `dir\evil.hivebackup`, "backup.zip"} {
		if err := validateBackupName(name); err == nil {
			t.Fatalf("expected %q to be rejected", name)
		}
	}
}
