package app

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func newTransferTestPan(t *testing.T, server *httptest.Server) *CloudPan {
	t.Helper()
	db, err := OpenStore(filepath.Join(t.TempDir(), "transfer.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = db.Close() })
	pan := &CloudPan{
		db:     db,
		client: server.Client(),
		session: cloudPanSession{
			UserToken: "token", SpaceID: "space", CorpID: "corp", LibraryID: "library",
			ExpiresAt: time.Now().Add(time.Hour),
		},
		token: cloudPanAccessToken{
			Token: "access", SpaceID: "space", LibraryID: "library",
			ExpiresAt: time.Now().Add(time.Hour),
		},
		emit: func(string, any) {},
	}
	return pan
}

func TestUploadTransferResumesFromConfirmKey(t *testing.T) {
	oldOrigin := cloudPanOrigin
	oldValidator := validateCloudPanMediaURL
	oldUploadClient := cloudUploadClient
	defer func() {
		cloudPanOrigin = oldOrigin
		validateCloudPanMediaURL = oldValidator
		cloudUploadClient = oldUploadClient
	}()

	var mu sync.Mutex
	base := ""
	uploadedParts := map[string]int{}
	renewCalls := 0
	confirmCalls := 0

	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/obj"):
			mu.Lock()
			uploadedParts[r.URL.Query().Get("partNumber")]++
			mu.Unlock()
			w.WriteHeader(http.StatusOK)
		case strings.Contains(r.URL.Path, "/api/v1/directory/"):
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{}`))
		case strings.Contains(r.URL.Path, "/api/v1/file/"):
			switch {
			case r.URL.Query().Has("upload"):
				_, _ = w.Write([]byte(`{"confirmed":false,"parts":{"1":{"PartNumber":1,"Size":4194304}}}`))
			case r.URL.Query().Has("renew"):
				mu.Lock()
				renewCalls++
				mu.Unlock()
				parts := map[string]any{}
				for _, number := range []string{"2", "3"} {
					parts[number] = map[string]any{"PartNumber": 2, "headers": map[string]string{}}
				}
				payload, _ := json.Marshal(map[string]any{
					"domain": strings.TrimPrefix(base, "https://"), "path": "/obj",
					"uploadId": "upload-1", "expiration": time.Now().Add(time.Hour).Format(time.RFC3339),
					"parts": parts,
				})
				_, _ = w.Write(payload)
			case r.URL.Query().Has("confirm"):
				mu.Lock()
				confirmCalls++
				mu.Unlock()
				_, _ = w.Write([]byte(`{}`))
			default:
				w.WriteHeader(http.StatusBadRequest)
			}
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer server.Close()

	cloudPanOrigin = server.URL
	validateCloudPanMediaURL = func(string) error { return nil }
	cloudUploadClient = server.Client()
	base = server.URL

	pan := newTransferTestPan(t, server)
	local := filepath.Join(t.TempDir(), "report.pdf")
	if err := os.WriteFile(local, make([]byte, 9<<20), 0o644); err != nil {
		t.Fatal(err)
	}
	transfer := CloudTransfer{
		ID: "upload-1", Kind: "upload", Name: "report.pdf", LocalPath: local,
		RemotePath: "docs/report.pdf", Size: 9 << 20, Status: "running", ConfirmKey: "confirm-key",
	}
	if err := pan.saveTransfer(transfer); err != nil {
		t.Fatal(err)
	}
	if err := pan.uploadTransfer(context.Background(), transfer); err != nil {
		t.Fatalf("upload: %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if renewCalls == 0 {
		t.Fatal("expected renew to be called for missing parts")
	}
	if confirmCalls != 1 {
		t.Fatalf("confirm calls = %d", confirmCalls)
	}
	if uploadedParts["1"] != 0 {
		t.Fatal("part 1 was already uploaded and should be skipped")
	}
	if uploadedParts["2"] != 1 || uploadedParts["3"] != 1 {
		t.Fatalf("uploaded parts = %v", uploadedParts)
	}
}

func TestDownloadTransferResumesWithRange(t *testing.T) {
	oldOrigin := cloudPanOrigin
	oldValidator := validateCloudPanMediaURL
	oldClient := cloudDownloadClient
	defer func() {
		cloudPanOrigin = oldOrigin
		validateCloudPanMediaURL = oldValidator
		cloudDownloadClient = oldClient
	}()

	content := []byte("0123456789")
	base := ""
	rangeSeen := ""
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/obj") {
			rangeSeen = r.Header.Get("Range")
			offset := 0
			if strings.HasPrefix(rangeSeen, "bytes=") {
				_, _ = fmt.Sscanf(rangeSeen, "bytes=%d-", &offset)
			}
			if offset > 0 {
				w.WriteHeader(http.StatusPartialContent)
			}
			_, _ = w.Write(content[offset:])
			return
		}
		if strings.Contains(r.URL.Path, "/api/v1/file/") && r.URL.Query().Has("info") {
			payload, _ := json.Marshal(map[string]any{"cosUrl": base + "/obj/file.bin", "size": len(content)})
			_, _ = w.Write(payload)
			return
		}
		w.WriteHeader(http.StatusNotFound)
	}))
	defer server.Close()

	cloudPanOrigin = server.URL
	validateCloudPanMediaURL = func(string) error { return nil }
	cloudDownloadClient = server.Client()
	base = server.URL

	pan := newTransferTestPan(t, server)
	dir := t.TempDir()
	target := filepath.Join(dir, "file.bin")
	if err := os.WriteFile(target+".part", content[:4], 0o644); err != nil {
		t.Fatal(err)
	}
	transfer := CloudTransfer{
		ID: "download-1", Kind: "download", Name: "file.bin", LocalPath: target,
		RemotePath: "file.bin", Size: int64(len(content)), Status: "running",
	}
	if err := pan.saveTransfer(transfer); err != nil {
		t.Fatal(err)
	}
	if err := pan.downloadTransfer(context.Background(), transfer); err != nil {
		t.Fatalf("download: %v", err)
	}
	if rangeSeen != "bytes=4-" {
		t.Fatalf("range header = %q", rangeSeen)
	}
	got, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(content) {
		t.Fatalf("downloaded %q", got)
	}
	if _, err := os.Stat(target + ".part"); !os.IsNotExist(err) {
		t.Fatal("part file should be renamed away")
	}
}
