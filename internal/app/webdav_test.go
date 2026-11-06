package app

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/crypto/scrypt"
)

func TestEncryptBackupRoundTrip(t *testing.T) {
	dir := t.TempDir()
	plain := filepath.Join(dir, "backup.zip")
	payload := []byte("backup-content-123")
	if err := os.WriteFile(plain, payload, 0o600); err != nil {
		t.Fatal(err)
	}
	encrypted := filepath.Join(dir, "backup.hivebackup")
	if err := encryptBackupFile(plain, encrypted, "correct horse battery"); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(encrypted)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(data, webdavMagic) || len(data) <= len(webdavMagic)+16+12 {
		t.Fatalf("unexpected encrypted header: %x", data[:min(len(data), 32)])
	}
	offset := len(webdavMagic)
	salt := data[offset : offset+16]
	offset += 16
	key, err := scrypt.Key([]byte("correct horse battery"), salt, 32768, 8, 1, 32)
	if err != nil {
		t.Fatal(err)
	}
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	nonce := data[offset : offset+gcm.NonceSize()]
	offset += gcm.NonceSize()
	opened, err := gcm.Open(nil, nonce, data[offset:], append(append([]byte{}, webdavMagic...), salt...))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(opened, payload) {
		t.Fatalf("decrypted = %q", opened)
	}
}

func TestPutWebDAVFile(t *testing.T) {
	var gotPath, gotUser, gotPass string
	var body []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotUser, gotPass, _ = r.BasicAuth()
		body, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusCreated)
	}))
	defer server.Close()
	dir := t.TempDir()
	local := filepath.Join(dir, "payload.bin")
	if err := os.WriteFile(local, []byte("payload"), 0o600); err != nil {
		t.Fatal(err)
	}
	config := webDAVConfig{URL: server.URL, Directory: "backups", Username: "user", Password: "pass"}
	if err := putWebDAVFile(context.Background(), config, "a.hivebackup", local); err != nil {
		t.Fatal(err)
	}
	if gotPath != "/backups/a.hivebackup" || gotUser != "user" || gotPass != "pass" {
		t.Fatalf("path=%q user=%q pass=%q", gotPath, gotUser, gotPass)
	}
	if string(body) != "payload" {
		t.Fatalf("body=%q", body)
	}
}

func TestSecretExportPlain(t *testing.T) {
	store, err := NewSecretStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := store.Save("mail-credentials", map[string]string{"address": "a@b.c"}); err != nil {
		t.Fatal(err)
	}
	if err := store.Save("webdav-backup", map[string]string{"password": "secret"}); err != nil {
		t.Fatal(err)
	}
	exported, err := store.ExportPlain("webdav-backup")
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := exported["mail-credentials"]; !ok {
		t.Fatal("mail credentials missing from export")
	}
	if _, ok := exported["webdav-backup"]; ok {
		t.Fatal("webdav credentials must be excluded")
	}
}

func TestUploadWebDAVIncludesSecrets(t *testing.T) {
	root := t.TempDir()
	db, err := OpenStore(filepath.Join(root, "app.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	settings, err := NewSettings(db, nil)
	if err != nil {
		t.Fatal(err)
	}
	secrets, err := NewSecretStore(filepath.Join(root, "secrets"))
	if err != nil {
		t.Fatal(err)
	}
	if err := secrets.Save("mail-credentials", map[string]string{"address": "me@sjtu.edu.cn"}); err != nil {
		t.Fatal(err)
	}
	var uploaded []byte
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		uploaded, _ = io.ReadAll(r.Body)
		w.WriteHeader(http.StatusCreated)
	}))
	defer server.Close()
	app := &App{db: db, settings: settings, secrets: secrets, paths: Paths{}}
	if _, err := app.SaveWebDAV(server.URL, "", "", ""); err != nil {
		t.Fatal(err)
	}
	result, err := app.UploadWebDAV(context.Background(), "testpass123")
	if err != nil {
		t.Fatal(err)
	}
	if result["uploaded"] != true || len(uploaded) == 0 {
		t.Fatalf("upload failed: %#v", result)
	}
	if !bytes.HasPrefix(uploaded, webdavMagic) {
		t.Fatal("uploaded file is not encrypted")
	}
	offset := len(webdavMagic)
	salt := uploaded[offset : offset+16]
	offset += 16
	key, _ := scrypt.Key([]byte("testpass123"), salt, 32768, 8, 1, 32)
	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	nonce := uploaded[offset : offset+gcm.NonceSize()]
	offset += gcm.NonceSize()
	plain, err := gcm.Open(nil, nonce, uploaded[offset:], append(append([]byte{}, webdavMagic...), salt...))
	if err != nil {
		t.Fatal(err)
	}
	reader, err := zip.NewReader(bytes.NewReader(plain), int64(len(plain)))
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, file := range reader.File {
		if file.Name != "secrets.json" {
			continue
		}
		found = true
		src, _ := file.Open()
		data, _ := io.ReadAll(src)
		src.Close()
		var decoded map[string]json.RawMessage
		if err := json.Unmarshal(data, &decoded); err != nil {
			t.Fatal(err)
		}
		if _, ok := decoded["mail-credentials"]; !ok {
			t.Fatal("mail credentials missing from webdav backup")
		}
	}
	if !found {
		t.Fatal("secrets.json missing from webdav backup")
	}
}
