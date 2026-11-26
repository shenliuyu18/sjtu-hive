package app

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestShuiyuanImagePreview(t *testing.T) {
	dir := t.TempDir()
	pngPath := filepath.Join(dir, "sample.png")
	if err := os.WriteFile(pngPath, []byte{0x89, 'P', 'N', 'G'}, 0o644); err != nil {
		t.Fatal(err)
	}

	s := &Shuiyuan{}
	preview, err := s.ImagePreview(pngPath)
	if err != nil {
		t.Fatalf("ImagePreview() error = %v", err)
	}
	if preview.Name != "sample.png" {
		t.Fatalf("Name = %q, want sample.png", preview.Name)
	}
	if !strings.HasPrefix(preview.DataURL, "data:image/png;base64,") {
		t.Fatalf("DataURL prefix = %q", preview.DataURL)
	}

	txtPath := filepath.Join(dir, "note.txt")
	if err := os.WriteFile(txtPath, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ImagePreview(txtPath); err == nil {
		t.Fatal("ImagePreview() accepted a non-image file")
	}
	if _, err := s.ImagePreview(filepath.Join(dir, "missing.png")); err == nil {
		t.Fatal("ImagePreview() accepted a missing file")
	}
}