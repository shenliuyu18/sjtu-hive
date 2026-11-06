package app

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
)

const shuiyuanCookedSample = `<div class="lightbox-wrapper"><a class="lightbox" href="/uploads/short-url/abc123.jpeg?dl=1" ` +
	`data-download-url="/uploads/short-url/abc123.jpeg?dl=1">` +
	`<img src="https://shuiyuan.sjtu.edu.cn/secure-uploads/optimized/4X/7/3/6/pic_2_345x281.jpeg" ` +
	`srcset="https://shuiyuan.sjtu.edu.cn/secure-uploads/optimized/4X/7/3/6/pic_2_345x281.jpeg, ` +
	`/secure-uploads/optimized/4X/7/3/6/pic_2_690x562.jpeg 2x" width="345" height="281"></a></div>` +
	`<img src="https://shuiyuan.sjtu.edu.cn/user_avatar/shuiyuan.sjtu.edu.cn/nova/96/1.png">` +
	`<img src="/images/avatar.png">` +
	`<img src="https://external.example.com/shot.png">` +
	`<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">`

func TestShuiyuanSecureUploadRewrite(t *testing.T) {
	secrets, err := NewSecretStore(t.TempDir())
	if err != nil {
		t.Fatalf("secrets: %v", err)
	}
	if err := secrets.Save("shuiyuan-key", ShuiyuanCredential{
		Key: "test-key", ClientID: "test-client", Username: "tester",
	}); err != nil {
		t.Fatalf("save: %v", err)
	}
	media, err := NewMediaServer()
	if err != nil {
		t.Fatalf("media: %v", err)
	}
	defer media.Stop()

	svc := NewShuiyuan(secrets, media, nil, func(string, any) {})
	out := svc.rewriteAssets(shuiyuanCookedSample)

	if !strings.Contains(out, media.BaseURL()) {
		t.Fatalf("expected proxied urls, got %s", out)
	}
	for _, fragment := range []string{
		`src="` + media.BaseURL(),
		`data-download-url="` + media.BaseURL(),
	} {
		if !strings.Contains(out, fragment) {
			t.Fatalf("missing %s in %s", fragment, out)
		}
	}
	// public avatars and external images must not be proxied, but shuiyuan
	// relative paths must become absolute or the WebView resolves them locally.
	if strings.Contains(out, `src="`+media.BaseURL()+`/r/`+`/user_avatar`) {
		t.Fatalf("avatar should not be proxied: %s", out)
	}
	if !strings.Contains(out, `src="https://shuiyuan.sjtu.edu.cn/user_avatar/`) {
		t.Fatalf("avatar url should stay direct: %s", out)
	}
	if !strings.Contains(out, `src="https://shuiyuan.sjtu.edu.cn/images/avatar.png"`) {
		t.Fatalf("relative default avatar should be absolutised: %s", out)
	}
	if !strings.Contains(out, `src="https://external.example.com/shot.png"`) {
		t.Fatalf("external image should be untouched: %s", out)
	}
	if !strings.Contains(out, `src="data:image/gif;base64,R0lGODlhAQABAAAAACw="`) {
		t.Fatalf("data uri should be untouched: %s", out)
	}
	// srcset keeps its density descriptor while both candidates are proxied.
	if !strings.Contains(out, " 2x") {
		t.Fatalf("srcset descriptor lost: %s", out)
	}
	proxied := proxiedCount(out, media.BaseURL())
	if proxied != 4 {
		t.Fatalf("expected 4 proxied references (download url, src and both srcset candidates), got %d: %s", proxied, out)
	}
	if !strings.Contains(out, `href="/uploads/short-url/abc123.jpeg?dl=1"`) {
		t.Fatalf("links should stay untouched: %s", out)
	}

	// The same upload referenced twice must reuse one proxy entry.
	repeat := svc.rewriteAssets(`<img src="/secure-uploads/original/4X/a/b/c/dup.png"><img src="/secure-uploads/original/4X/a/b/c/dup.png">`)
	parts := strings.Split(repeat, `<img src="`)
	if len(parts) != 3 {
		t.Fatalf("unexpected markup: %s", repeat)
	}
	first := parts[1][:strings.Index(parts[1], `"`)]
	second := parts[2][:strings.Index(parts[2], `"`)]
	if first != second {
		t.Fatalf("duplicate urls should map to one proxy: %s vs %s", first, second)
	}
	if !strings.HasPrefix(first, media.BaseURL()) {
		t.Fatalf("proxy url should point at the media server: %s", first)
	}

	// Registered proxies must carry the user api key so secure uploads resolve.
	item := proxyHeadersFor(t, media, first)
	if item.Get("User-Api-Key") != "test-key" {
		t.Fatalf("proxy should send the user api key, got %q", item.Get("User-Api-Key"))
	}

	// Without a bound account the body must stay readable.
	anonymous := NewShuiyuan(mustEmptySecrets(t), media, nil, func(string, any) {})
	if got := anonymous.rewriteAssets(shuiyuanCookedSample); strings.Contains(got, media.BaseURL()) {
		t.Fatalf("unbound service should not proxy: %s", got)
	} else if !strings.Contains(got, `src="https://shuiyuan.sjtu.edu.cn/secure-uploads/`) {
		t.Fatalf("unbound service should still absolutise: %s", got)
	}
}

func mustEmptySecrets(t *testing.T) *SecretStore {
	t.Helper()
	store, err := NewSecretStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return store
}

func proxiedCount(value, base string) int {
	return strings.Count(value, base+"/r/")
}

func proxyHeadersFor(t *testing.T, media *MediaServer, proxyURL string) http.Header {
	t.Helper()
	parsed, err := url.Parse(proxyURL)
	if err != nil {
		t.Fatal(err)
	}
	id := strings.TrimPrefix(parsed.Path, "/r/")
	if index := strings.IndexByte(id, '/'); index >= 0 {
		id = id[:index]
	}
	media.mu.Lock()
	defer media.mu.Unlock()
	item, ok := media.items[id]
	if !ok {
		t.Fatalf("proxy item %s missing", id)
	}
	return item.headers
}
