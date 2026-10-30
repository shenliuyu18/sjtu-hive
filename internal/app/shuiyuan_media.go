package app

import (
	"net/http"
	"net/url"
	"path"
	"regexp"
	"strings"
	"time"
)

// Shuiyuan runs Discourse with secure uploads: /secure-uploads/** and /uploads/**
// answer 404 unless the request carries the User-Api-Key header. The WebView never
// sends that header, so those assets are streamed through the loopback media proxy.
var shuiyuanAssetAttrPattern = regexp.MustCompile(`(?i)\b(src|srcset|data-download-url)="([^"]*)"`)

const (
	shuiyuanAssetTTL       = 2 * time.Hour
	shuiyuanAssetUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36 Edg/124.0"
)

func (s *Shuiyuan) rewriteAssets(html string) string {
	if html == "" {
		return html
	}
	proxy := s.assetProxy()
	return shuiyuanAssetAttrPattern.ReplaceAllStringFunc(html, func(match string) string {
		groups := shuiyuanAssetAttrPattern.FindStringSubmatch(match)
		if len(groups) != 3 {
			return match
		}
		name, value := groups[1], groups[2]
		if strings.EqualFold(name, "srcset") {
			candidates := strings.Split(value, ",")
			for index, candidate := range candidates {
				parts := strings.Fields(candidate)
				if len(parts) == 0 {
					continue
				}
				parts[0] = s.assetURL(parts[0], proxy)
				candidates[index] = strings.Join(parts, " ")
			}
			return name + `="` + strings.Join(candidates, ", ") + `"`
		}
		return name + `="` + s.assetURL(value, proxy) + `"`
	})
}

func (s *Shuiyuan) assetURL(raw string, proxy func(string) string) string {
	target := strings.TrimSpace(raw)
	if target == "" || strings.HasPrefix(strings.ToLower(target), "data:") {
		return raw
	}
	absolute, shuiyuan := resolveShuiyuanAsset(target)
	if !shuiyuan {
		return target
	}
	if proxy == nil || !shuiyuanAssetNeedsAuth(absolute) {
		return absolute
	}
	return proxy(absolute)
}

// resolveShuiyuanAsset turns site-relative paths into absolute ones. The app page is
// served from http://wails.localhost, so a root-relative src would resolve locally.
func resolveShuiyuanAsset(target string) (string, bool) {
	if strings.HasPrefix(target, "//") {
		return "https:" + target, strings.Contains(target, "//"+shuiyuanHost)
	}
	parsed, err := url.Parse(target)
	if err != nil {
		return target, false
	}
	if parsed.Scheme == "" && parsed.Host == "" {
		absolute := shuiyuanOrigin + parsed.Path
		if parsed.RawQuery != "" {
			absolute += "?" + parsed.RawQuery
		}
		return absolute, true
	}
	if strings.EqualFold(parsed.Host, shuiyuanHost) {
		return target, true
	}
	return target, false
}

func shuiyuanAssetNeedsAuth(absolute string) bool {
	parsed, err := url.Parse(absolute)
	if err != nil {
		return false
	}
	return strings.HasPrefix(parsed.Path, "/secure-uploads/") || strings.HasPrefix(parsed.Path, "/uploads/")
}

func (s *Shuiyuan) assetProxy() func(string) string {
	if s.media == nil {
		return nil
	}
	credential, err := s.credential()
	if err != nil {
		return nil
	}
	headers := http.Header{}
	headers.Set("User-Api-Key", credential.Key)
	headers.Set("User-Api-Client-Id", credential.ClientID)
	headers.Set("Referer", shuiyuanOrigin+"/")
	headers.Set("User-Agent", shuiyuanAssetUserAgent)
	client := &http.Client{Timeout: 30 * time.Second}
	return func(rawURL string) string {
		name := path.Base(strings.Split(rawURL, "?")[0])
		return s.media.AddProxyCached(rawURL, headers, client, name, shuiyuanAssetTTL)
	}
}

func rewriteOrSame(rewrite func(string) string, value string) string {
	if rewrite == nil {
		return value
	}
	return rewrite(value)
}
