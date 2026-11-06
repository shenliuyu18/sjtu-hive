package app

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func newShareForTest(t *testing.T, token string) (*Share, *SecretStore) {
	t.Helper()
	store, err := NewSecretStore(t.TempDir())
	if err != nil {
		t.Fatalf("NewSecretStore: %v", err)
	}
	if token != "" {
		if err := store.Save(shareSecret, map[string]string{"token": token}); err != nil {
			t.Fatalf("save token: %v", err)
		}
	}
	return &Share{secrets: store, token: token}, store
}

func useShareBase(t *testing.T, base string) {
	t.Helper()
	previous := shareAPIBase
	shareAPIBase = base
	t.Cleanup(func() { shareAPIBase = previous })
}

func TestShareDownloadPurchaseRequiredKeepsSession(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusForbidden)
		_, _ = io.WriteString(w, `{"code":403,"data":null,"message":"you have not purchase the material!","success":false}`)
	}))
	defer server.Close()
	useShareBase(t, server.URL)

	share, store := newShareForTest(t, "token-1")
	_, err := share.Download(context.Background(), 61552)
	if err == nil || err.Error() != "该资料需要先兑换或领取后才能下载" {
		t.Fatalf("Download error = %v", err)
	}
	if !share.State().LoggedIn {
		t.Fatal("403 must not log the user out")
	}
	if _, ok, _ := store.LoadRaw(shareSecret); !ok {
		t.Fatal("403 must not delete the saved token")
	}
}

func TestShareUnauthorizedClearsSession(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"code":401,"data":null,"message":"unauthorized","success":false}`)
	}))
	defer server.Close()
	useShareBase(t, server.URL)

	share, store := newShareForTest(t, "token-1")
	_, err := share.Download(context.Background(), 1)
	if !errors.Is(err, errShareAuth) {
		t.Fatalf("Download error = %v, want errShareAuth", err)
	}
	if share.State().LoggedIn {
		t.Fatal("401 must log the user out")
	}
	if _, ok, _ := store.LoadRaw(shareSecret); ok {
		t.Fatal("401 must delete the saved token")
	}
}

func TestShareSaveToWritesFile(t *testing.T) {
	var server *httptest.Server
	server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/api/v1/material/download/9" {
			_, _ = io.WriteString(w, `{"success":true,"data":"`+server.URL+`/file"}`)
			return
		}
		if r.URL.Path == "/file" {
			_, _ = io.WriteString(w, "material-bytes")
			return
		}
		http.NotFound(w, r)
	}))
	defer server.Close()
	useShareBase(t, server.URL)

	share, _ := newShareForTest(t, "token-1")
	target := filepath.Join(t.TempDir(), "资料.pdf")
	if err := share.SaveTo(context.Background(), 9, target); err != nil {
		t.Fatalf("SaveTo: %v", err)
	}
	data, err := os.ReadFile(target)
	if err != nil {
		t.Fatalf("read saved file: %v", err)
	}
	if string(data) != "material-bytes" {
		t.Fatalf("saved content = %q", data)
	}
}

func TestShareLateUnauthorizedKeepsNewerToken(t *testing.T) {
	started := make(chan struct{})
	release := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		close(started)
		<-release
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"success":false,"message":"unauthorized"}`)
	}))
	defer server.Close()
	useShareBase(t, server.URL)

	share, _ := newShareForTest(t, "stale-token")
	done := make(chan error, 1)
	go func() {
		_, err := share.Download(context.Background(), 1)
		done <- err
	}()
	<-started
	share.mu.Lock()
	share.token = "fresh-token"
	share.mu.Unlock()
	close(release)
	if err := <-done; !errors.Is(err, errShareAuth) {
		t.Fatalf("Download error = %v, want errShareAuth", err)
	}
	if !share.State().LoggedIn {
		t.Fatal("a stale 401 must not clear a newer token")
	}
}
