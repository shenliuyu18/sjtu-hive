package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type Update struct {
	settings *Settings
}

func NewUpdate(settings *Settings) *Update {
	return &Update{settings: settings}
}

func normalizeVersion(value string) ([]int, string) {
	value = strings.TrimPrefix(strings.TrimSpace(value), "v")
	if index := strings.IndexByte(value, '+'); index >= 0 {
		value = value[:index]
	}
	prerelease := ""
	if index := strings.IndexByte(value, '-'); index >= 0 {
		prerelease = value[index+1:]
		value = value[:index]
	}
	parts := strings.Split(value, ".")
	result := make([]int, 0, len(parts))
	for _, part := range parts {
		number, _ := strconv.Atoi(part)
		result = append(result, number)
	}
	return result, prerelease
}

func comparePrerelease(left, right string) int {
	if left == right {
		return 0
	}
	if left == "" {
		return 1
	}
	if right == "" {
		return -1
	}
	a, b := strings.Split(left, "."), strings.Split(right, ".")
	for index := 0; index < len(a) && index < len(b); index++ {
		if a[index] == b[index] {
			continue
		}
		an, aerr := strconv.Atoi(a[index])
		bn, berr := strconv.Atoi(b[index])
		switch {
		case aerr == nil && berr == nil:
			if an > bn {
				return 1
			}
			return -1
		case aerr == nil:
			return -1
		case berr == nil:
			return 1
		case a[index] > b[index]:
			return 1
		default:
			return -1
		}
	}
	if len(a) > len(b) {
		return 1
	}
	return -1
}

func compareVersions(left, right string) int {
	a, aPre := normalizeVersion(left)
	b, bPre := normalizeVersion(right)
	for index := 0; index < len(a) || index < len(b); index++ {
		var av, bv int
		if index < len(a) {
			av = a[index]
		}
		if index < len(b) {
			bv = b[index]
		}
		if av > bv {
			return 1
		}
		if av < bv {
			return -1
		}
	}
	return comparePrerelease(aPre, bPre)
}

func (u *Update) Check() (map[string]any, error) {
	manifestURL := strings.TrimSpace(u.settings.GetString("updateManifestUrl", ""))
	result := map[string]any{
		"available": false,
		"current":   Version,
	}
	if manifestURL == "" {
		result["message"] = "当前构建未配置更新源。"
		return result, nil
	}
	parsed, err := url.Parse(manifestURL)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" {
		return nil, errors.New("更新地址无效")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "SJTU-Desktop/"+Version)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return nil, fmt.Errorf("检查更新失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("检查更新失败：HTTP %d", response.StatusCode)
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	if err != nil {
		return nil, err
	}
	var manifest struct {
		Version     string `json:"version"`
		URL         string `json:"url"`
		Notes       string `json:"notes"`
		PublishedAt string `json:"publishedAt"`
	}
	if err := json.Unmarshal(data, &manifest); err != nil {
		return nil, errors.New("更新源返回的数据无法识别")
	}
	if manifest.Version == "" {
		return nil, errors.New("更新源没有提供版本号")
	}
	result["latest"] = manifest.Version
	result["notes"] = manifest.Notes
	result["publishedAt"] = manifest.PublishedAt
	if manifest.URL != "" {
		target, err := url.Parse(manifest.URL)
		if err != nil || target.Scheme != "https" || target.Host == "" {
			return nil, errors.New("更新下载地址无效")
		}
		result["url"] = manifest.URL
	}
	if compareVersions(manifest.Version, Version) > 0 {
		result["available"] = true
		result["message"] = "发现新版本 " + manifest.Version
	} else {
		result["message"] = "当前已是最新版本。"
	}
	return result, nil
}

func (u *Update) Register(router *Router) {
	router.Handle("update.check", func([]json.RawMessage) (any, error) {
		return u.Check()
	})
}
