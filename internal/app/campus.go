package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

var (
	ErrCampusAuth      = errors.New("校园登录已失效，请重新登录")
	ErrCampusTransient = errors.New("校园服务暂时不可用，请稍后重试")
)

type cookieRecord struct {
	Domain   string    `json:"domain"`
	Path     string    `json:"path"`
	Name     string    `json:"name"`
	Value    string    `json:"value"`
	Expires  time.Time `json:"expires,omitempty"`
	Secure   bool      `json:"secure"`
	HttpOnly bool      `json:"httpOnly"`
	HostOnly bool      `json:"hostOnly"`
}

type campusSnapshot struct {
	Cookies   []cookieRecord `json:"cookies"`
	UpdatedAt string         `json:"updatedAt"`
}

type CampusSession struct {
	mu        sync.Mutex
	cookies   map[string]cookieRecord
	updatedAt string
	secrets   *SecretStore
	emit      func(string, any)
	logger    *slog.Logger

	client         *http.Client
	redirectClient *http.Client
	saveTimer      *time.Timer
	lastRenew      time.Time
}

func NewCampusSession(secrets *SecretStore, emit func(string, any), logger *slog.Logger) *CampusSession {
	session := &CampusSession{
		cookies: make(map[string]cookieRecord),
		secrets: secrets,
		emit:    emit,
		logger:  logger,
	}
	session.client = &http.Client{
		Jar:     session,
		Timeout: 30 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	session.redirectClient = &http.Client{
		Jar:     session,
		Timeout: 0,
	}
	var snapshot campusSnapshot
	if ok, err := secrets.Load("campus-session", &snapshot); err != nil {
		logger.Warn("load campus session", "error", err)
	} else if ok {
		for _, cookie := range snapshot.Cookies {
			session.cookies[cookieKey(cookie)] = cookie
		}
		session.updatedAt = snapshot.UpdatedAt
	}
	return session
}

func cookieKey(cookie cookieRecord) string {
	return strings.Join([]string{cookie.Domain, cookie.Path, cookie.Name}, "\x00")
}

func cookieHost(domain string) string {
	return strings.TrimPrefix(domain, ".")
}

func (s *CampusSession) SetCookies(requestURL *url.URL, cookies []*http.Cookie) {
	s.mu.Lock()
	changed := false
	for _, cookie := range cookies {
		domain := cookie.Domain
		hostOnly := false
		if domain == "" {
			if requestURL == nil {
				continue
			}
			domain = requestURL.Hostname()
			hostOnly = true
		} else if !strings.HasPrefix(domain, ".") {
			hostOnly = true
		}
		record := cookieRecord{
			Domain:   domain,
			Path:     cookie.Path,
			Name:     cookie.Name,
			Value:    cookie.Value,
			Secure:   cookie.Secure,
			HttpOnly: cookie.HttpOnly,
			HostOnly: hostOnly,
		}
		if record.Path == "" {
			record.Path = "/"
		}
		if cookie.Expires.After(time.Now()) {
			record.Expires = cookie.Expires
		}
		key := cookieKey(record)
		if cookie.MaxAge < 0 || (!cookie.Expires.IsZero() && cookie.Expires.Before(time.Now())) {
			if _, ok := s.cookies[key]; ok {
				delete(s.cookies, key)
				changed = true
			}
			continue
		}
		s.cookies[key] = record
		changed = true
	}
	if changed {
		s.updatedAt = Now()
	}
	s.mu.Unlock()
	if changed {
		s.scheduleSave()
		s.emitStatus()
	}
}

func (s *CampusSession) Cookies(u *url.URL) []*http.Cookie {
	host := u.Hostname()
	secure := u.Scheme == "https"
	pathValue := u.Path
	if pathValue == "" {
		pathValue = "/"
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	result := make([]*http.Cookie, 0, len(s.cookies))
	for _, record := range s.cookies {
		if record.Expires.After(time.Time{}) && record.Expires.Before(now) {
			continue
		}
		if record.Secure && !secure {
			continue
		}
		domainHost := cookieHost(record.Domain)
		if record.HostOnly {
			if host != domainHost {
				continue
			}
		} else if host != domainHost && !strings.HasSuffix(host, "."+domainHost) {
			continue
		}
		if !strings.HasPrefix(pathValue, record.Path) {
			continue
		}
		result = append(result, &http.Cookie{
			Name:     record.Name,
			Value:    record.Value,
			Path:     record.Path,
			Domain:   record.Domain,
			Expires:  record.Expires,
			Secure:   record.Secure,
			HttpOnly: record.HttpOnly,
		})
	}
	return result
}

func (s *CampusSession) scheduleSave() {
	s.mu.Lock()
	if s.saveTimer != nil {
		s.saveTimer.Stop()
	}
	s.saveTimer = time.AfterFunc(400*time.Millisecond, func() {
		s.mu.Lock()
		cookies := make([]cookieRecord, 0, len(s.cookies))
		for _, cookie := range s.cookies {
			cookies = append(cookies, cookie)
		}
		updatedAt := s.updatedAt
		s.mu.Unlock()
		if err := s.secrets.Save("campus-session", campusSnapshot{Cookies: cookies, UpdatedAt: updatedAt}); err != nil && s.logger != nil {
			s.logger.Warn("save campus session", "error", err)
		}
	})
	s.mu.Unlock()
}

func (s *CampusSession) emitStatus() {
	if s.emit == nil {
		return
	}
	s.emit("campus.status", s.Status())
}

func (s *CampusSession) Status() map[string]any {
	s.mu.Lock()
	defer s.mu.Unlock()
	return map[string]any{
		"connected": s.authenticatedLocked(),
		"cookies":   len(s.cookies),
		"updatedAt": s.updatedAt,
	}
}

func (s *CampusSession) Connected() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.authenticatedLocked()
}

func (s *CampusSession) authenticatedLocked() bool {
	now := time.Now()
	active := make([]cookieRecord, 0, len(s.cookies))
	for _, cookie := range s.cookies {
		if cookie.Expires.After(time.Time{}) && cookie.Expires.Before(now) {
			continue
		}
		active = append(active, cookie)
	}
	return hasCampusAuth(active)
}

func hasCampusAuth(cookies []cookieRecord) bool {
	hasAuth := false
	hasSession := false
	for _, cookie := range cookies {
		name := strings.ToLower(cookie.Name)
		domain := strings.ToLower(cookie.Domain)
		if name == "jaauthcookie" && cookie.Value != "" {
			hasAuth = true
		}
		if name == "jsessionid" && cookie.Value != "" && strings.Contains(domain, "i.sjtu.edu.cn") {
			hasSession = true
		}
	}
	return hasAuth && hasSession
}

func cookieDomainForHeader(name string) string {
	switch strings.ToLower(strings.TrimSpace(name)) {
	case "jsessionid", "keepalive":
		return "i.sjtu.edu.cn"
	default:
		return ".sjtu.edu.cn"
	}
}

func (s *CampusSession) Replace(cookies []cookieRecord) {
	s.mu.Lock()
	s.cookies = make(map[string]cookieRecord, len(cookies))
	for _, cookie := range cookies {
		if cookie.Path == "" {
			cookie.Path = "/"
		}
		cookie.HostOnly = !strings.HasPrefix(cookie.Domain, ".")
		s.cookies[cookieKey(cookie)] = cookie
	}
	s.updatedAt = Now()
	s.mu.Unlock()
	s.scheduleSave()
	s.emitStatus()
}

// Reload swaps the in-memory cookies for the stored ones, used after a backup
// restore replaced the secret store.
func (s *CampusSession) Reload() {
	var snapshot campusSnapshot
	ok, err := s.secrets.Load("campus-session", &snapshot)
	if err != nil && s.logger != nil {
		s.logger.Warn("reload campus session", "error", err)
	}
	s.mu.Lock()
	s.cookies = make(map[string]cookieRecord)
	if ok {
		for _, cookie := range snapshot.Cookies {
			s.cookies[cookieKey(cookie)] = cookie
		}
		s.updatedAt = snapshot.UpdatedAt
	} else {
		s.updatedAt = ""
	}
	s.mu.Unlock()
	s.emitStatus()
}

func (s *CampusSession) Clear() {
	s.mu.Lock()
	s.cookies = make(map[string]cookieRecord)
	s.updatedAt = ""
	s.mu.Unlock()
	_ = s.secrets.Delete("campus-session")
	s.emitStatus()
}

func (s *CampusSession) ImportCookieHeader(text string) error {
	text = strings.TrimSpace(text)
	if text == "" {
		return errors.New("请粘贴 Cookie")
	}
	if strings.HasPrefix(text, "[") {
		var values []struct {
			Name     string  `json:"name"`
			Value    string  `json:"value"`
			Domain   string  `json:"domain"`
			Path     string  `json:"path"`
			Secure   bool    `json:"secure"`
			HTTPOnly bool    `json:"httpOnly"`
			Expires  float64 `json:"expires"`
		}
		if err := json.Unmarshal([]byte(text), &values); err == nil && len(values) > 0 {
			cookies := make([]cookieRecord, 0, len(values))
			for _, value := range values {
				domain := value.Domain
				if domain == "" || (strings.EqualFold(domain, ".sjtu.edu.cn") && cookieDomainForHeader(value.Name) == "i.sjtu.edu.cn") {
					domain = cookieDomainForHeader(value.Name)
				}
				record := cookieRecord{
					Domain:   domain,
					Path:     value.Path,
					Name:     value.Name,
					Value:    value.Value,
					Secure:   value.Secure,
					HttpOnly: value.HTTPOnly,
				}
				if record.Path == "" {
					record.Path = "/"
				}
				if value.Expires > 0 {
					record.Expires = time.Unix(int64(value.Expires), 0)
				}
				if record.Name != "" && strings.Contains(domain, "sjtu.edu.cn") {
					cookies = append(cookies, record)
				}
			}
			if len(cookies) == 0 {
				return errors.New("没有识别到交大 Cookie")
			}
			if err := validateCampusImport(cookies); err != nil {
				return err
			}
			s.Replace(cookies)
			return nil
		}
	}
	cookies := make([]cookieRecord, 0)
	for _, pair := range strings.Split(text, ";") {
		pair = strings.TrimSpace(pair)
		if pair == "" {
			continue
		}
		name, value, ok := strings.Cut(pair, "=")
		if !ok || strings.TrimSpace(name) == "" {
			continue
		}
		cookies = append(cookies, cookieRecord{
			Domain:   cookieDomainForHeader(name),
			Path:     "/",
			Name:     strings.TrimSpace(name),
			Value:    strings.TrimSpace(value),
			Secure:   true,
			HostOnly: false,
		})
	}
	if len(cookies) == 0 {
		return errors.New("Cookie 格式无法识别")
	}
	if err := validateCampusImport(cookies); err != nil {
		return err
	}
	s.Replace(cookies)
	return nil
}

func validateCampusImport(cookies []cookieRecord) error {
	if hasCampusAuth(cookies) {
		return nil
	}
	missing := make([]string, 0, 2)
	names := map[string]bool{}
	for _, cookie := range cookies {
		names[strings.ToLower(cookie.Name)] = true
	}
	if !names["jaauthcookie"] {
		missing = append(missing, "JAAuthCookie")
	}
	if !names["jsessionid"] {
		missing = append(missing, "i.sjtu.edu.cn 的 JSESSIONID")
	}
	if len(missing) > 0 {
		return fmt.Errorf("Cookie 缺少 %s，请从已登录教学网的浏览器复制完整 Cookie", strings.Join(missing, " 和 "))
	}
	return errors.New("Cookie 中的 JSESSIONID 不属于 i.sjtu.edu.cn，请重新从教学网页面复制")
}

// Request 在会话失效时先用 jAccount 单点登录静默续期，再重试一次。
func (s *CampusSession) Request(ctx context.Context, method, rawURL string, headers map[string]string, body string) ([]byte, error) {
	data, err := s.doRequest(ctx, method, rawURL, headers, body)
	if !errors.Is(err, ErrCampusAuth) {
		return data, err
	}
	if renewErr := s.renew(ctx, rawURL); renewErr != nil {
		if s.logger != nil {
			s.logger.Warn("campus session renew skipped", "error", renewErr)
		}
		return nil, err
	}
	if s.logger != nil {
		s.logger.Info("campus session renewed", "url", rawURL)
	}
	return s.doRequest(ctx, method, rawURL, headers, body)
}

const renewCooldown = 45 * time.Second

// renew 用已保存的 jAccount Cookie 走一遍站点 SSO，换回新的站点会话。
func (s *CampusSession) renew(ctx context.Context, rawURL string) error {
	if !s.Connected() {
		return errors.New("缺少 jAccount 登录态")
	}
	s.mu.Lock()
	if time.Since(s.lastRenew) < renewCooldown {
		s.mu.Unlock()
		return errors.New("续期冷却中")
	}
	s.lastRenew = time.Now()
	s.mu.Unlock()

	parsed, err := url.Parse(rawURL)
	if err != nil {
		return err
	}
	host := strings.ToLower(parsed.Hostname())
	timeoutCtx, cancel := context.WithTimeout(ctx, 25*time.Second)
	defer cancel()

	if strings.Contains(host, "i.sjtu.edu.cn") {
		// 教学网必须先打开登录页拿到会话，再走 /jaccountlogin 才会下发新的 JSESSIONID。
		_ = s.follow(timeoutCtx, "https://i.sjtu.edu.cn/student/index.html")
		_ = s.follow(timeoutCtx, "https://i.sjtu.edu.cn/jaccountlogin")
		_ = s.follow(timeoutCtx, "https://i.sjtu.edu.cn/student/index.html")
	} else {
		_ = s.follow(timeoutCtx, parsed.Scheme+"://"+parsed.Host+"/")
	}
	if !s.Connected() {
		return errors.New("续期后仍未取得完整会话")
	}
	return nil
}

func (s *CampusSession) follow(ctx context.Context, target string) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0")
	request.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	response, err := s.redirectClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 512<<10))
	return nil
}

func (s *CampusSession) doRequest(ctx context.Context, method, rawURL string, headers map[string]string, body string) ([]byte, error) {
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	request, err := http.NewRequestWithContext(ctx, method, rawURL, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0")
	if request.Header.Get("Accept") == "" {
		request.Header.Set("Accept", "application/json, text/plain, */*")
	}
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, err := s.client.Do(request)
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return nil, fmt.Errorf("%w：%v", ErrCampusTransient, err)
	}
	defer response.Body.Close()
	status := response.StatusCode
	if status >= 300 && status < 400 || status == 401 || status == 403 {
		return nil, ErrCampusAuth
	}
	if status == 408 || status >= 500 {
		return nil, fmt.Errorf("%w（HTTP %d）", ErrCampusTransient, status)
	}
	if status != 200 {
		return nil, fmt.Errorf("校园服务返回 HTTP %d", status)
	}
	limited := io.LimitReader(response.Body, 32<<20)
	data, err := io.ReadAll(limited)
	if err != nil {
		return nil, fmt.Errorf("%w：响应中断", ErrCampusTransient)
	}
	return data, nil
}

func (s *CampusSession) RequestRaw(ctx context.Context, method, rawURL string, headers map[string]string, body string) (int, []byte, error) {
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	request, err := http.NewRequestWithContext(ctx, method, rawURL, reader)
	if err != nil {
		return 0, nil, err
	}
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0")
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, err := s.client.Do(request)
	if err != nil {
		return 0, nil, err
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	return response.StatusCode, data, err
}

func (s *CampusSession) RedirectClient() *http.Client {
	return s.redirectClient
}

func (s *CampusSession) Test(ctx context.Context) (map[string]any, error) {
	if !s.Connected() {
		return map[string]any{"ok": false, "error": "尚未登录"}, nil
	}
	_, err := s.Request(ctx, http.MethodGet, "https://i.sjtu.edu.cn/kbcx/xskbcx_cxXsgrkbIndex.html?gnmkdm=N2151", map[string]string{
		"Referer": "https://i.sjtu.edu.cn/",
	}, "")
	if err != nil {
		if s.logger != nil {
			s.logger.Warn("campus test failed", "error", err)
		}
		return map[string]any{"ok": false, "error": err.Error()}, nil
	}
	if s.logger != nil {
		s.logger.Info("campus test ok")
	}
	return map[string]any{"ok": true}, nil
}

func (s *CampusSession) Login(ctx context.Context) (map[string]any, error) {
	if s.logger != nil {
		s.logger.Info("campus login starting")
	}
	cookies, err := edgeLogin(ctx, s.logger, func(phase string) {
		if s.emit != nil {
			s.emit("campus.login.progress", map[string]any{"phase": phase})
		}
	})
	if err != nil {
		return nil, err
	}
	if !hasCampusAuth(cookies) {
		if s.logger != nil {
			s.logger.Warn("campus login incomplete", "cookies", len(cookies))
		}
		return nil, errors.New("没有取得完整的教学网登录状态（缺少 JAAuthCookie 或 i.sjtu.edu.cn 的 JSESSIONID），请重新登录")
	}
	s.Replace(cookies)
	if s.logger != nil {
		s.logger.Info("campus login completed", "cookies", len(cookies))
	}
	return s.Status(), nil
}

func (s *CampusSession) Logout() {
	s.Clear()
}

func (s *CampusSession) Register(router *Router) {
	router.Handle("campus.status", func([]json.RawMessage) (any, error) {
		return s.Status(), nil
	})
	router.Handle("campus.test", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
		defer cancel()
		return s.Test(ctx)
	})
	router.Handle("campus.login", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Minute)
		defer cancel()
		return s.Login(ctx)
	})
	router.Handle("campus.logout", func([]json.RawMessage) (any, error) {
		s.Logout()
		return s.Status(), nil
	})
	router.Handle("campus.importCookies", func(args []json.RawMessage) (any, error) {
		text, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		if err := s.ImportCookieHeader(text); err != nil {
			return nil, err
		}
		return s.Status(), nil
	})
}
