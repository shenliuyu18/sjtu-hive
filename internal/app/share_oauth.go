package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"time"
)

const shareOAuthCallback = "https://share.dyweb.sjtu.cn/auth/jaccount/callback"

// loginWithCampus completes the site's jAccount OAuth flow with the cookies
// already saved by the campus login in Settings, so no second login is needed.
func (s *Share) loginWithCampus(ctx context.Context) (string, error) {
	if s.campus == nil {
		return "", errors.New("设置中的 jAccount 登录不可用，请先在设置中登录")
	}
	jaccountURL, _ := url.Parse("https://jaccount.sjtu.edu.cn/")
	campusCookies := s.campus.Cookies(jaccountURL)
	if len(campusCookies) == 0 {
		return "", errors.New("设置中的 jAccount 登录不可用，请先在设置中登录")
	}
	jar, _ := cookiejar.New(nil)
	jar.SetCookies(jaccountURL, campusCookies)
	client := &http.Client{
		Jar:     jar,
		Timeout: 30 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	get := func(target string) ([]byte, string, error) {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
		if err != nil {
			return nil, "", err
		}
		request.Header.Set("User-Agent", portalUserAgent)
		request.Header.Set("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
		response, err := client.Do(request)
		if err != nil {
			return nil, "", err
		}
		defer response.Body.Close()
		body, _ := io.ReadAll(io.LimitReader(response.Body, 1<<20))
		if response.StatusCode >= 300 && response.StatusCode < 400 {
			return body, response.Header.Get("Location"), nil
		}
		return body, "", nil
	}

	configURL := shareAPIBase + "/auth/jaccount/config?redirect_uri=" + url.QueryEscape(shareOAuthCallback)
	body, _, err := get(configURL)
	if err != nil {
		return "", fmt.Errorf("读取 jAccount 登录配置失败：%w", err)
	}
	var configEnvelope struct {
		Success bool   `json:"success"`
		Message string `json:"message"`
		Data    struct {
			Endpoint struct {
				AuthURL string `json:"auth_url"`
			} `json:"endpoint"`
			ClientID    string   `json:"client_id"`
			RedirectURI string   `json:"redirect_uri"`
			Scopes      []string `json:"scopes"`
			State       string   `json:"state"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &configEnvelope); err != nil || !configEnvelope.Success {
		return "", errors.New("读取 jAccount 登录配置失败")
	}
	config := configEnvelope.Data
	if config.Endpoint.AuthURL == "" || config.ClientID == "" || config.State == "" {
		return "", errors.New("jAccount 登录配置不完整")
	}
	redirectURI := config.RedirectURI
	if redirectURI == "" {
		redirectURI = shareOAuthCallback
	}
	values := url.Values{}
	values.Set("client_id", config.ClientID)
	values.Set("redirect_uri", redirectURI)
	values.Set("response_type", "code")
	values.Set("scope", strings.Join(config.Scopes, " "))
	values.Set("state", config.State)
	current := config.Endpoint.AuthURL + "?" + values.Encode()

	code := ""
	for step := 0; step < 15; step++ {
		if !shareOAuthHostAllowed(current) {
			return "", errors.New("jAccount 登录跳转到了不受信任的地址")
		}
		_, location, err := get(current)
		if err != nil {
			return "", fmt.Errorf("jAccount 登录失败：%w", err)
		}
		if location == "" {
			return "", errors.New("设置中的 jAccount 登录已失效，请先在设置中重新登录")
		}
		next, err := url.Parse(location)
		if err != nil {
			return "", errors.New("jAccount 登录返回了无效地址")
		}
		if next.Host == "" {
			base, _ := url.Parse(current)
			next = base.ResolveReference(next)
		}
		current = next.String()
		if strings.HasPrefix(current, shareOAuthCallback) {
			parsed, _ := url.Parse(current)
			code = parsed.Query().Get("code")
			if state := parsed.Query().Get("state"); state != "" && state != config.State {
				return "", errors.New("jAccount 登录状态校验失败")
			}
			break
		}
	}
	if code == "" {
		return "", errors.New("jAccount 没有返回授权码，请先在设置中重新登录")
	}

	authorizeURL := shareAPIBase + "/auth/jaccount/authorize?code=" + url.QueryEscape(code) + "&state=" + url.QueryEscape(config.State)
	body, _, err = get(authorizeURL)
	if err != nil {
		return "", fmt.Errorf("jAccount 授权失败：%w", err)
	}
	var authorizeEnvelope struct {
		Success bool            `json:"success"`
		Message string          `json:"message"`
		Data    json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(body, &authorizeEnvelope); err != nil || !authorizeEnvelope.Success {
		return "", errors.New("jAccount 授权没有返回有效登录信息")
	}
	var token string
	if err := json.Unmarshal(authorizeEnvelope.Data, &token); err != nil || strings.TrimSpace(token) == "" {
		return "", errors.New("jAccount 授权没有返回有效登录信息")
	}
	return strings.TrimSpace(token), nil
}

func shareOAuthHostAllowed(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" {
		return false
	}
	host := strings.ToLower(parsed.Hostname())
	return host == "jaccount.sjtu.edu.cn" ||
		host == "share.dyweb.sjtu.cn" ||
		host == "api-v2.share.dyweb.sjtu.cn"
}
