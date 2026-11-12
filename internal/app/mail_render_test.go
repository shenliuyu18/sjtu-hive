package app

import (
	"strings"
	"testing"
)

func TestFilterRemoteImagesDropsUnresolvableHosts(t *testing.T) {
	html := `<p><img src="https://blocked.invalid/a.png"><img src="https://www.baidu.com/logo.png"></p>`
	cleaned, blocked := filterRemoteImages(html)
	if strings.Contains(cleaned, "blocked.invalid") {
		t.Fatalf("unresolvable image kept: %s", cleaned)
	}
	if !strings.Contains(cleaned, "www.baidu.com/logo.png") {
		t.Fatalf("resolvable image removed: %s", cleaned)
	}
	if len(blocked) != 1 || blocked[0] != "blocked.invalid" {
		t.Fatalf("blocked hosts = %v", blocked)
	}
}
