package app

// 交大云盘（pan.sjtu.edu.cn，腾讯 coFile 私有化版）。
// 鉴权链路：jAccount OAuth2 授权码 -> verify-account-login 换 user_token（30 天）
// -> space/personal 换 access_token（30 分钟）。文件下载返回的是免 cookie 的
// COS 预签名地址，可直接交给本地资源代理做流式预览。

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const cloudPanOrigin = "https://pan.sjtu.edu.cn"

var ErrCloudPanAuth = errors.New("交大云盘会话已失效，请重新登录")

type cloudPanTeam struct {
	SpaceID string `json:"spaceId"`
	Name    string `json:"name"`
}

type cloudPanSession struct {
	UserID      int            `json:"userId"`
	UserToken   string         `json:"userToken"`
	CorpID      string         `json:"corpId"`
	LibraryID   string         `json:"libraryId"`
	SpaceID     string         `json:"spaceId"`
	DisplayName string         `json:"displayName"`
	Teams       []cloudPanTeam `json:"teams,omitempty"`
	ExpiresAt   time.Time      `json:"expiresAt"`
}

func (s cloudPanSession) valid() bool {
	return s.UserToken != "" && time.Now().Before(s.ExpiresAt)
}

type cloudPanAccessToken struct {
	Token     string
	SpaceID   string
	LibraryID string
	ExpiresAt time.Time
}

type CloudPanFile struct {
	Name        string `json:"name"`
	Path        string `json:"path"`
	Directory   bool   `json:"directory"`
	Size        int64  `json:"size"`
	ContentType string `json:"contentType"`
	ModifiedAt  string `json:"modifiedAt"`
	CanPreview  bool   `json:"canPreview"`
	CanDownload bool   `json:"canDownload"`
}

type CloudPanList struct {
	Path      string         `json:"path"`
	Parent    string         `json:"parent"`
	Files     []CloudPanFile `json:"files"`
	Total     int            `json:"total"`
	SpaceID   string         `json:"spaceId"`
	SpaceName string         `json:"spaceName"`
}

type CloudPanState struct {
	Connected    bool   `json:"connected"`
	Account      string `json:"account"`
	UsedBytes    int64  `json:"usedBytes"`
	TotalBytes   int64  `json:"totalBytes"`
	LastSyncedAt string `json:"lastSyncedAt"`
	Error        string `json:"error"`
}

type CloudPan struct {
	secrets *SecretStore
	campus  *CampusSession
	media   *MediaServer
	db      *sql.DB
	emit    func(string, any)

	client  *http.Client
	mu      sync.Mutex
	session cloudPanSession
	token   cloudPanAccessToken
	state   CloudPanState
}

func NewCloudPan(db *sql.DB, secrets *SecretStore, campus *CampusSession, media *MediaServer, emit func(string, any)) *CloudPan {
	pan := &CloudPan{
		db: db, secrets: secrets, campus: campus, media: media, emit: emit,
		client: &http.Client{Timeout: 30 * time.Second},
	}
	var stored cloudPanSession
	if ok, err := secrets.Load("cloudpan-session", &stored); err == nil && ok && stored.valid() {
		pan.session = stored
		pan.state.Connected = true
		pan.state.Account = stored.DisplayName
	}
	return pan
}

func (p *CloudPan) State() CloudPanState {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.state
}

func (p *CloudPan) setState(patch func(*CloudPanState)) {
	p.mu.Lock()
	patch(&p.state)
	state := p.state
	p.mu.Unlock()
	p.emit("cloudPan.changed", state)
}

func (p *CloudPan) deviceID() string {
	var value string
	_ = p.db.QueryRow(`SELECT value FROM meta WHERE key = 'cloudPanDevice'`).Scan(&value)
	if value != "" {
		return value
	}
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		value = fmt.Sprintf("sjtu-%d", time.Now().UnixNano())
	} else {
		value = "sjtu-" + hex.EncodeToString(buffer)
	}
	_, _ = p.db.Exec(`INSERT INTO meta (key, value) VALUES ('cloudPanDevice', ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, value)
	return value
}

func (p *CloudPan) do(ctx context.Context, method, rawURL string, body string, headers map[string]string, target any) error {
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	request, err := http.NewRequestWithContext(ctx, method, rawURL, reader)
	if err != nil {
		return err
	}
	request.Header.Set("User-Agent", portalUserAgent)
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("Origin", cloudPanOrigin)
	request.Header.Set("Referer", cloudPanOrigin+"/")
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, err := p.client.Do(request)
	if err != nil {
		return errors.New("无法连接交大云盘，请检查网络后重试")
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 8<<20))
	if err != nil {
		return err
	}
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		return ErrCloudPanAuth
	}
	if response.StatusCode >= 400 {
		var failure struct {
			Code    string `json:"code"`
			Message string `json:"message"`
		}
		_ = json.Unmarshal(data, &failure)
		if failure.Message != "" {
			return fmt.Errorf("交大云盘返回 %d：%s", response.StatusCode, failure.Message)
		}
		return fmt.Errorf("交大云盘返回 HTTP %d", response.StatusCode)
	}
	if target != nil {
		if err := json.Unmarshal(data, target); err != nil {
			return fmt.Errorf("交大云盘响应格式无法识别：%w", err)
		}
	}
	return nil
}

// Login walks the jAccount SSO chain silently: the campus cookie jar already holds
// a JAAuthCookie, so the authorize endpoint redirects straight back with a code.
func (p *CloudPan) Login(ctx context.Context) (CloudPanState, error) {
	if !p.campus.Connected() {
		return p.State(), errors.New("请先登录教学网（交我办同一套 jAccount 账号）")
	}
	client := *p.campus.RedirectClient()
	client.Timeout = 40 * time.Second

	var orgList struct {
		OrganizationList []struct {
			Name   string `json:"name"`
			CorpID string `json:"corpId"`
		} `json:"organizationList"`
	}
	if err := p.requestWith(ctx, &client, http.MethodGet,
		cloudPanOrigin+"/user/v1/organization/login-org-list", "", nil, &orgList); err != nil {
		return p.State(), err
	}
	corpID := ""
	for _, organization := range orgList.OrganizationList {
		if strings.Contains(organization.Name, "交通") || strings.Contains(organization.Name, "交大") {
			corpID = organization.CorpID
			break
		}
	}
	if corpID == "" && len(orgList.OrganizationList) > 0 {
		corpID = orgList.OrganizationList[0].CorpID
	}
	if corpID == "" {
		return p.State(), errors.New("交大云盘没有返回可用组织")
	}

	redirect := struct {
		URL string `json:"url"`
	}{}
	if err := p.requestWith(ctx, &client, http.MethodGet,
		cloudPanOrigin+"/user/v1/sign-in/sso-login-redirect/"+url.PathEscape(corpID)+
			"?auto_redirect=false&from=web", "", nil, &redirect); err != nil {
		return p.State(), err
	}
	if redirect.URL == "" {
		return p.State(), errors.New("交大云盘没有返回单点登录地址")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, redirect.URL, nil)
	if err != nil {
		return p.State(), err
	}
	request.Header.Set("User-Agent", portalUserAgent)
	response, err := client.Do(request)
	if err != nil {
		return p.State(), errors.New("交大云盘单点登录失败：" + err.Error())
	}
	code := ""
	if response.Request != nil && response.Request.URL != nil {
		code = response.Request.URL.Query().Get("code")
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<20))
	_ = response.Body.Close()
	if code == "" {
		return p.State(), errors.New("交大云盘单点登录没有返回授权码，请重新登录教学网")
	}

	values := url.Values{}
	values.Set("device_id", p.deviceID())
	values.Set("type", "sso")
	values.Set("credential", code)
	values.Set("state", "oauth_v1_web_")
	var verified struct {
		UserID        int    `json:"userId"`
		UserToken     string `json:"userToken"`
		ExpiresIn     int64  `json:"expiresIn"`
		Organizations []struct {
			ID        int    `json:"id"`
			LibraryID string `json:"libraryId"`
			Name      string `json:"name"`
			User      struct {
				Nickname string `json:"nickname"`
			} `json:"orgUser"`
		} `json:"organizations"`
	}
	if err := p.requestWith(ctx, &client, http.MethodPost,
		cloudPanOrigin+"/user/v1/sign-in/verify-account-login/"+url.PathEscape(corpID)+"?"+values.Encode(),
		"{}", map[string]string{"Content-Type": "application/json"}, &verified); err != nil {
		return p.State(), err
	}
	if verified.UserToken == "" {
		return p.State(), ErrCloudPanAuth
	}
	session := cloudPanSession{
		UserID:    verified.UserID,
		UserToken: verified.UserToken,
		CorpID:    corpID,
		ExpiresAt: time.Now().Add(time.Duration(maxInt64(verified.ExpiresIn, 3600)) * time.Second),
	}
	for _, organization := range verified.Organizations {
		if session.LibraryID == "" {
			session.LibraryID = organization.LibraryID
			session.DisplayName = firstNonEmpty(organization.User.Nickname, fmt.Sprint(verified.UserID))
		}
	}
	if session.LibraryID == "" {
		return p.State(), errors.New("交大云盘没有返回个人空间")
	}
	p.mu.Lock()
	p.session = session
	p.token = cloudPanAccessToken{}
	p.mu.Unlock()

	if err := p.refreshPersonalSpace(ctx, &client, session); err != nil {
		return p.State(), err
	}
	p.setState(func(state *CloudPanState) {
		state.Connected = true
		state.Account = session.DisplayName
		state.Error = ""
		state.LastSyncedAt = Now()
	})
	return p.State(), nil
}

func (p *CloudPan) refreshPersonalSpace(ctx context.Context, client *http.Client, session cloudPanSession) error {
	values := url.Values{}
	values.Set("user_token", session.UserToken)
	values.Set("device_id", p.deviceID())
	var personal struct {
		SpaceID   string `json:"spaceId"`
		LibraryID string `json:"libraryId"`
	}
	if err := p.requestWith(ctx, client, http.MethodPost,
		cloudPanOrigin+"/user/v1/space/1/personal?"+values.Encode(), "{}",
		map[string]string{"Content-Type": "application/json"}, &personal); err != nil {
		return err
	}
	if personal.SpaceID == "" {
		return errors.New("交大云盘没有返回个人空间标识")
	}
	session.SpaceID = personal.SpaceID
	if personal.LibraryID != "" {
		session.LibraryID = personal.LibraryID
	}
	p.mu.Lock()
	p.session = session
	p.mu.Unlock()
	if err := p.secrets.Save("cloudpan-session", session); err != nil {
		return err
	}
	var quota struct {
		Capacity string `json:"capacity"`
		Size     string `json:"size"`
	}
	_ = p.requestWith(ctx, client, http.MethodGet,
		cloudPanOrigin+"/user/v1/space/1?"+values.Encode(), "", nil, &quota)
	p.setState(func(state *CloudPanState) {
		state.UsedBytes = parseByteSize(quota.Size)
		state.TotalBytes = parseByteSize(quota.Capacity)
		state.LastSyncedAt = Now()
	})
	return nil
}

func (p *CloudPan) requestWith(ctx context.Context, client *http.Client, method, rawURL, body string, headers map[string]string, target any) error {
	saved := p.client
	p.client = client
	defer func() { p.client = saved }()
	return p.do(ctx, method, rawURL, body, headers, target)
}

// accessToken returns a valid per-space token, refreshing it when it expires.
func (p *CloudPan) accessToken(ctx context.Context, spaceID string) (cloudPanAccessToken, error) {
	p.mu.Lock()
	session := p.session
	cached := p.token
	p.mu.Unlock()
	if !session.valid() {
		return cloudPanAccessToken{}, ErrCloudPanAuth
	}
	if spaceID == "" {
		spaceID = session.SpaceID
	}
	if cached.Token != "" && cached.SpaceID == spaceID && time.Now().Before(cached.ExpiresAt) {
		return cached, nil
	}
	values := url.Values{}
	values.Set("user_token", session.UserToken)
	values.Set("device_id", p.deviceID())
	endpoint := cloudPanOrigin + "/user/v1/space/1/personal?" + values.Encode()
	if spaceID != session.SpaceID {
		endpoint = fmt.Sprintf("%s/user/v1/space/1/token/%s?%s",
			cloudPanOrigin, url.PathEscape(spaceID), values.Encode())
	}
	var issued struct {
		AccessToken string `json:"accessToken"`
		LibraryID   string `json:"libraryId"`
		SpaceID     string `json:"spaceId"`
		ExpiresIn   int64  `json:"expiresIn"`
	}
	if err := p.do(ctx, http.MethodPost, endpoint, "{}", map[string]string{"Content-Type": "application/json"}, &issued); err != nil {
		if strings.Contains(err.Error(), "token is empty") || strings.Contains(err.Error(), "401") {
			return cloudPanAccessToken{}, ErrCloudPanAuth
		}
		return cloudPanAccessToken{}, err
	}
	if issued.AccessToken == "" {
		return cloudPanAccessToken{}, ErrCloudPanAuth
	}
	token := cloudPanAccessToken{
		Token:     issued.AccessToken,
		SpaceID:   firstNonEmpty(issued.SpaceID, spaceID),
		LibraryID: firstNonEmpty(issued.LibraryID, session.LibraryID),
		ExpiresAt: time.Now().Add(time.Duration(maxInt64(issued.ExpiresIn, 300)-60) * time.Second),
	}
	p.mu.Lock()
	p.token = token
	p.mu.Unlock()
	return token, nil
}

func (p *CloudPan) List(ctx context.Context, requested, spaceID string) (CloudPanList, error) {
	token, err := p.accessToken(ctx, spaceID)
	if err != nil {
		return CloudPanList{}, err
	}
	clean := strings.Trim(requested, "/")
	values := url.Values{}
	values.Set("access_token", token.Token)
	values.Set("page", "1")
	values.Set("page_size", "200")
	values.Set("order_by", "name")
	values.Set("order_by_type", "asc")
	endpoint := fmt.Sprintf("%s/api/v1/directory/%s/%s/%s?%s", cloudPanOrigin,
		url.PathEscape(token.LibraryID), url.PathEscape(token.SpaceID), escapePanPath(clean), values.Encode())
	var payload struct {
		Contents []struct {
			Name             string   `json:"name"`
			Type             string   `json:"type"`
			Path             []string `json:"path"`
			Size             panInt64 `json:"size"`
			ContentType      string   `json:"contentType"`
			ModificationTime string   `json:"modificationTime"`
			AuthorityList    struct {
				CanDownload bool `json:"canDownload"`
				CanPreview  bool `json:"canPreview"`
			} `json:"authorityList"`
		} `json:"contents"`
		TotalNum int `json:"totalNum"`
	}
	if err := p.do(ctx, http.MethodGet, endpoint, "", nil, &payload); err != nil {
		return CloudPanList{}, err
	}
	list := CloudPanList{
		Path: clean, Files: []CloudPanFile{}, Total: payload.TotalNum,
		SpaceID: token.SpaceID, SpaceName: "个人空间",
	}
	if idx := strings.LastIndex(clean, "/"); idx >= 0 {
		list.Parent = clean[:idx]
	}
	for _, item := range payload.Contents {
		name := strings.TrimSpace(item.Name)
		if name == "" || name == "." {
			continue
		}
		full := name
		if clean != "" {
			full = clean + "/" + name
		}
		list.Files = append(list.Files, CloudPanFile{
			Name:        name,
			Path:        full,
			Directory:   strings.EqualFold(item.Type, "dir"),
			Size:        int64(item.Size),
			ContentType: item.ContentType,
			ModifiedAt:  item.ModificationTime,
			CanPreview:  item.AuthorityList.CanPreview || !item.AuthorityList.CanDownload,
			CanDownload: item.AuthorityList.CanDownload,
		})
	}
	sortCloudPanFiles(list.Files)
	return list, nil
}

// Preview resolves the COS presigned URL and streams it through the local proxy.
func (p *CloudPan) Preview(ctx context.Context, filePath, spaceID string) (DocumentContent, error) {
	token, err := p.accessToken(ctx, spaceID)
	if err != nil {
		return DocumentContent{}, err
	}
	clean := strings.Trim(filePath, "/")
	if clean == "" {
		return DocumentContent{}, errors.New("请选择要预览的文件")
	}
	values := url.Values{}
	values.Set("access_token", token.Token)
	values.Set("content_disposition", "inline")
	endpoint := fmt.Sprintf("%s/api/v1/file/%s/%s/%s?info&%s", cloudPanOrigin,
		url.PathEscape(token.LibraryID), url.PathEscape(token.SpaceID), escapePanPath(clean), values.Encode())
	var info struct {
		CosURL      string   `json:"cosUrl"`
		Size        panInt64 `json:"size"`
		ContentType string   `json:"contentType"`
	}
	if err := p.do(ctx, http.MethodGet, endpoint, "", nil, &info); err != nil {
		return DocumentContent{}, err
	}
	if err := validateCloudPanMediaURL(info.CosURL); err != nil {
		return DocumentContent{}, err
	}
	name := path.Base(clean)
	resource := p.media.AddProxyCached(info.CosURL, http.Header{}, nil, name, 2*time.Hour)
	format := formatOf(name, strings.TrimPrefix(path.Ext(name), "."), false)
	return DocumentContent{
		Title: name, Size: int64(info.Size), Format: format,
		ResourceURL: resource, ReadOnly: true, ModifiedAt: Now(),
	}, nil
}

// Download streams a file to the chosen local path.
func (p *CloudPan) Download(ctx context.Context, filePath, spaceID, target string) (string, error) {
	token, err := p.accessToken(ctx, spaceID)
	if err != nil {
		return "", err
	}
	clean := strings.Trim(filePath, "/")
	values := url.Values{}
	values.Set("access_token", token.Token)
	values.Set("content_disposition", "attachment")
	endpoint := fmt.Sprintf("%s/api/v1/file/%s/%s/%s?info&%s", cloudPanOrigin,
		url.PathEscape(token.LibraryID), url.PathEscape(token.SpaceID), escapePanPath(clean), values.Encode())
	var info struct {
		CosURL string `json:"cosUrl"`
	}
	if err := p.do(ctx, http.MethodGet, endpoint, "", nil, &info); err != nil {
		return "", err
	}
	if err := validateCloudPanMediaURL(info.CosURL); err != nil {
		return "", err
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, info.CosURL, nil)
	if err != nil {
		return "", err
	}
	response, err := p.client.Do(request)
	if err != nil {
		return "", err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK && response.StatusCode != http.StatusPartialContent {
		return "", fmt.Errorf("下载失败：HTTP %d", response.StatusCode)
	}
	if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
		return "", err
	}
	file, err := os.OpenFile(target, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o644)
	if err != nil {
		return "", err
	}
	defer file.Close()
	written, err := io.Copy(file, response.Body)
	if err != nil {
		return "", err
	}
	return fmt.Sprint(written), nil
}

func (p *CloudPan) Logout() (CloudPanState, error) {
	p.mu.Lock()
	session := p.session
	p.session = cloudPanSession{}
	p.token = cloudPanAccessToken{}
	p.mu.Unlock()
	if session.UserToken != "" {
		values := url.Values{}
		values.Set("user_token", session.UserToken)
		values.Set("device_id", p.deviceID())
		_ = p.do(context.Background(), http.MethodPost, cloudPanOrigin+"/user/v1/sign-out?"+values.Encode(), "{}",
			map[string]string{"Content-Type": "application/json"}, nil)
	}
	_ = p.secrets.Delete("cloudpan-session")
	p.setState(func(state *CloudPanState) {
		state.Connected = false
		state.Account = ""
		state.UsedBytes = 0
		state.TotalBytes = 0
		state.Error = ""
	})
	return p.State(), nil
}

func (p *CloudPan) Register(router *Router) {
	router.Handle("cloudPan.state", func([]json.RawMessage) (any, error) {
		return p.State(), nil
	})
	router.Handle("cloudPan.login", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		return p.Login(ctx)
	})
	router.Handle("cloudPan.list", func(args []json.RawMessage) (any, error) {
		filePath, _, _ := DecodeOptionalArg[string](args, 0)
		spaceID, _, _ := DecodeOptionalArg[string](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return p.List(ctx, filePath, spaceID)
	})
	router.Handle("cloudPan.preview", func(args []json.RawMessage) (any, error) {
		filePath, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		spaceID, _, _ := DecodeOptionalArg[string](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return p.Preview(ctx, filePath, spaceID)
	})
	router.Handle("cloudPan.download", func(args []json.RawMessage) (any, error) {
		filePath, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		target, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		spaceID, _, _ := DecodeOptionalArg[string](args, 2)
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Minute)
		defer cancel()
		return p.Download(ctx, filePath, spaceID, target)
	})
	router.Handle("cloudPan.logout", func([]json.RawMessage) (any, error) {
		return p.Logout()
	})
}

// validateCloudPanMediaURL keeps the presigned download host inside the university
// even if the drive API is coerced into returning something else.
func validateCloudPanMediaURL(raw string) error {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed.Scheme != "https" {
		return errors.New("交大云盘返回的下载地址不受信任")
	}
	host := strings.ToLower(parsed.Hostname())
	if !strings.HasSuffix(host, ".sjtu.edu.cn") && !strings.HasSuffix(host, ".cofile.net") {
		return errors.New("交大云盘返回的下载地址不受信任")
	}
	return nil
}

// panInt64 accepts both 12345 and "12345"; the drive API mixes the two.
type panInt64 int64

func (v *panInt64) UnmarshalJSON(data []byte) error {
	trimmed := strings.TrimSpace(string(data))
	if trimmed == "null" || trimmed == `""` {
		*v = 0
		return nil
	}
	if strings.HasPrefix(trimmed, `"`) {
		var text string
		if err := json.Unmarshal(data, &text); err != nil {
			return err
		}
		parsed, err := strconv.ParseInt(strings.TrimSpace(text), 10, 64)
		if err != nil {
			return errors.New("交大云盘返回了无法解析的数值")
		}
		*v = panInt64(parsed)
		return nil
	}
	var number int64
	if err := json.Unmarshal(data, &number); err != nil {
		return err
	}
	*v = panInt64(number)
	return nil
}

func escapePanPath(value string) string {
	if value == "" {
		return ""
	}
	segments := strings.Split(value, "/")
	for index, segment := range segments {
		segments[index] = url.PathEscape(segment)
	}
	return strings.Join(segments, "/")
}

func sortCloudPanFiles(files []CloudPanFile) {
	for i := 1; i < len(files); i++ {
		for j := i; j > 0 && cloudPanLess(files[j], files[j-1]); j-- {
			files[j], files[j-1] = files[j-1], files[j]
		}
	}
}

func cloudPanLess(a, b CloudPanFile) bool {
	if a.Directory != b.Directory {
		return a.Directory
	}
	return strings.ToLower(a.Name) < strings.ToLower(b.Name)
}

func parseByteSize(value string) int64 {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return 0
	}
	var parsed int64
	for _, character := range trimmed {
		if character < '0' || character > '9' {
			return parsed
		}
		parsed = parsed*10 + int64(character-'0')
	}
	return parsed
}

func maxInt64(value, minimum int64) int64 {
	if value < minimum {
		return minimum
	}
	return value
}
