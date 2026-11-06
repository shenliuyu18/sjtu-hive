package app

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	shuiyuanOrigin      = "https://shuiyuan.sjtu.edu.cn"
	shuiyuanHost        = "shuiyuan.sjtu.edu.cn"
	shuiyuanFeaturedTag = "精品"
)

var ErrShuiyuanAuth = errors.New("水源密钥已失效或缺少权限，请重新绑定")

type ShuiyuanState struct {
	Connected bool    `json:"connected"`
	Username  *string `json:"username"`
	BoundAt   *string `json:"boundAt"`
}

type ShuiyuanCredential struct {
	Key      string `json:"key"`
	ClientID string `json:"clientId"`
	Username string `json:"username"`
	BoundAt  string `json:"boundAt"`
}

type ShuiyuanCategory struct {
	ID         int64  `json:"id"`
	Name       string `json:"name"`
	Slug       string `json:"slug"`
	Color      string `json:"color"`
	ParentID   *int64 `json:"parentId"`
	TopicCount *int64 `json:"topicCount"`
}

type ShuiyuanTag struct {
	Name       string `json:"name"`
	TopicCount *int64 `json:"topicCount"`
}

type ShuiyuanCustomEmoji struct {
	Name string `json:"name"`
	URL  string `json:"url"`
}

type ShuiyuanSiteStructure struct {
	Categories  []ShuiyuanCategory    `json:"categories"`
	Tags        []ShuiyuanTag         `json:"tags"`
	CustomEmoji []ShuiyuanCustomEmoji `json:"customEmoji"`
}

type ShuiyuanTopicSummary struct {
	ID                 int64    `json:"id"`
	Title              string   `json:"title"`
	Excerpt            *string  `json:"excerpt"`
	CategoryID         *int64   `json:"categoryId"`
	Tags               []string `json:"tags"`
	PostsCount         int64    `json:"postsCount"`
	Views              *int64   `json:"views"`
	LikeCount          *int64   `json:"likeCount"`
	CreatedAt          string   `json:"createdAt"`
	LastPostedAt       *string  `json:"lastPostedAt"`
	Pinned             bool     `json:"pinned"`
	Closed             bool     `json:"closed"`
	Bookmarked         bool     `json:"bookmarked"`
	Archetype          string   `json:"archetype"`
	LastPosterUsername *string  `json:"lastPosterUsername"`
	AuthorUsername     *string  `json:"authorUsername"`
	AuthorAvatar       *string  `json:"authorAvatarTemplate"`
}

type ShuiyuanTopicPage struct {
	Topics  []ShuiyuanTopicSummary `json:"topics"`
	MoreURL *string                `json:"moreUrl"`
}

type ShuiyuanPost struct {
	ID                int64   `json:"id"`
	PostNumber        int64   `json:"postNumber"`
	Username          string  `json:"username"`
	Name              *string `json:"name"`
	AvatarTemplate    *string `json:"avatarTemplate"`
	CreatedAt         string  `json:"createdAt"`
	Cooked            string  `json:"cooked"`
	ReplyToPostNumber *int64  `json:"replyToPostNumber"`
	LikeCount         *int64  `json:"likeCount"`
	Liked             bool    `json:"liked"`
	Yours             bool    `json:"yours"`
}

type ShuiyuanTopicDetail struct {
	ID           int64          `json:"id"`
	Title        string         `json:"title"`
	CategoryID   *int64         `json:"categoryId"`
	Tags         []string       `json:"tags"`
	PostsCount   int64          `json:"postsCount"`
	LikeCount    *int64         `json:"likeCount"`
	Views        *int64         `json:"views"`
	CreatedAt    string         `json:"createdAt"`
	LastPostedAt *string        `json:"lastPostedAt"`
	Closed       bool           `json:"closed"`
	Archetype    string         `json:"archetype"`
	Bookmarked   bool           `json:"bookmarked"`
	Stream       []int64        `json:"stream"`
	Posts        []ShuiyuanPost `json:"posts"`
}

type ShuiyuanBookmarkItem struct {
	ID               int64   `json:"id"`
	TopicID          int64   `json:"topicId"`
	Title            string  `json:"title"`
	Excerpt          *string `json:"excerpt"`
	CreatedAt        string  `json:"createdAt"`
	LinkedPostNumber *int64  `json:"linkedPostNumber"`
}

type ShuiyuanNotificationItem struct {
	ID              int64   `json:"id"`
	Type            int64   `json:"type"`
	Read            bool    `json:"read"`
	HighPriority    bool    `json:"highPriority"`
	CreatedAt       string  `json:"createdAt"`
	TopicID         *int64  `json:"topicId"`
	PostNumber      *int64  `json:"postNumber"`
	FancyTitle      *string `json:"fancyTitle"`
	DisplayUsername *string `json:"displayUsername"`
}

type ShuiyuanCounts struct {
	Notifications int64 `json:"notifications"`
	Messages      int64 `json:"messages"`
}

type ShuiyuanUpload struct {
	ShortURL         string  `json:"shortUrl"`
	URL              *string `json:"url"`
	OriginalFilename *string `json:"originalFilename"`
}

type shuiyuanPendingAuth struct {
	PrivateKey *rsa.PrivateKey
	ClientID   string
	Nonce      string
	CreatedAt  time.Time
}

type Shuiyuan struct {
	secrets *SecretStore
	media   *MediaServer
	campus  *CampusSession
	emit    func(string, any)
	client  *http.Client

	campusMu       sync.Mutex
	campusClient   *http.Client
	campusUsername string

	authMu  sync.Mutex
	pending *shuiyuanPendingAuth

	cacheMu     sync.Mutex
	structure   *ShuiyuanSiteStructure
	structureAt time.Time
	counts      *ShuiyuanCounts
	countsAt    time.Time
}

func NewShuiyuan(secrets *SecretStore, media *MediaServer, campus *CampusSession, emit func(string, any)) *Shuiyuan {
	return &Shuiyuan{secrets: secrets, media: media, campus: campus, emit: emit, client: &http.Client{Timeout: 30 * time.Second}}
}

func (s *Shuiyuan) credential() (*ShuiyuanCredential, error) {
	var credential ShuiyuanCredential
	ok, err := s.secrets.Load("shuiyuan-key", &credential)
	if err != nil {
		return nil, err
	}
	if !ok || credential.Key == "" {
		return nil, errors.New("尚未绑定水源社区")
	}
	return &credential, nil
}

func (s *Shuiyuan) State() ShuiyuanState {
	credential, err := s.credential()
	if err == nil {
		username := credential.Username
		boundAt := credential.BoundAt
		return ShuiyuanState{Connected: true, Username: &username, BoundAt: &boundAt}
	}
	if s.campus != nil && s.campus.Connected() {
		s.campusMu.Lock()
		username := s.campusUsername
		s.campusMu.Unlock()
		if username == "" {
			username = "jAccount"
		}
		boundAt := Now()
		return ShuiyuanState{Connected: true, Username: &username, BoundAt: &boundAt}
	}
	return ShuiyuanState{Connected: false}
}

func (s *Shuiyuan) StartAuth() (map[string]string, error) {
	privateKey, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return nil, err
	}
	clientIDBytes := make([]byte, 16)
	nonceBytes := make([]byte, 16)
	if _, err := rand.Read(clientIDBytes); err != nil {
		return nil, err
	}
	if _, err := rand.Read(nonceBytes); err != nil {
		return nil, err
	}
	clientID := hex.EncodeToString(clientIDBytes)
	nonce := hex.EncodeToString(nonceBytes)
	publicDER, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
	if err != nil {
		return nil, err
	}
	publicPEM := strings.TrimSpace(string(pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: publicDER})))
	values := url.Values{}
	values.Set("scopes", "read,write")
	values.Set("application_name", AppName)
	values.Set("client_id", clientID)
	values.Set("nonce", nonce)
	values.Set("public_key", publicPEM)
	authURL := shuiyuanOrigin + "/user-api-key/new?" + values.Encode()
	s.authMu.Lock()
	s.pending = &shuiyuanPendingAuth{
		PrivateKey: privateKey, ClientID: clientID, Nonce: nonce, CreatedAt: time.Now(),
	}
	s.authMu.Unlock()
	return map[string]string{"url": authURL}, nil
}

func (s *Shuiyuan) Bind(payload string) (ShuiyuanState, error) {
	payload = strings.Join(strings.Fields(payload), "")
	if payload == "" {
		return ShuiyuanState{}, errors.New("请粘贴授权密文")
	}
	s.authMu.Lock()
	pending := s.pending
	s.authMu.Unlock()
	if pending == nil || time.Since(pending.CreatedAt) > 15*time.Minute {
		return ShuiyuanState{}, errors.New("授权已过期，请重新点击「登录并授权」")
	}
	ciphertext, err := base64.StdEncoding.DecodeString(payload)
	if err != nil {
		if ciphertext, err = base64.RawStdEncoding.DecodeString(payload); err != nil {
			return ShuiyuanState{}, errors.New("密文格式无法识别，请整段重新复制")
		}
	}
	plain, err := rsa.DecryptPKCS1v15(rand.Reader, pending.PrivateKey, ciphertext)
	if err != nil {
		return ShuiyuanState{}, errors.New("密文解密失败：内容可能复制不完整")
	}
	var decrypted struct {
		Key   string `json:"key"`
		Nonce string `json:"nonce"`
	}
	if err := json.Unmarshal(plain, &decrypted); err != nil || decrypted.Key == "" {
		return ShuiyuanState{}, errors.New("密文内容无法识别，请重新发起授权")
	}
	if decrypted.Nonce != pending.Nonce {
		return ShuiyuanState{}, errors.New("密文与本次授权不匹配，请重新点击「登录并授权」")
	}
	credential, err := s.verifyKey(decrypted.Key, pending.ClientID)
	if err != nil {
		return ShuiyuanState{}, err
	}
	if err := s.secrets.Save("shuiyuan-key", credential); err != nil {
		return ShuiyuanState{}, err
	}
	s.authMu.Lock()
	s.pending = nil
	s.authMu.Unlock()
	s.cacheMu.Lock()
	s.structure = nil
	s.counts = nil
	s.cacheMu.Unlock()
	return s.State(), nil
}

func (s *Shuiyuan) verifyKey(key, clientID string) (ShuiyuanCredential, error) {
	data, err := s.requestWithKey(http.MethodGet, "/session/current.json", nil, key, clientID)
	if err != nil {
		return ShuiyuanCredential{}, fmt.Errorf("验证水源密钥失败：%w", err)
	}
	var payload struct {
		CurrentUser struct {
			Username string `json:"username"`
		} `json:"current_user"`
	}
	if err := json.Unmarshal(data, &payload); err != nil || payload.CurrentUser.Username == "" {
		return ShuiyuanCredential{}, errors.New("水源拒绝了该密钥")
	}
	return ShuiyuanCredential{
		Key: key, ClientID: clientID, Username: payload.CurrentUser.Username, BoundAt: Now(),
	}, nil
}

func (s *Shuiyuan) Unbind() (ShuiyuanState, error) {
	if credential, err := s.credential(); err == nil {
		_, _ = s.requestWithKey(http.MethodPost, "/user-api-key/revoke", nil, credential.Key, credential.ClientID)
	}
	_ = s.secrets.Delete("shuiyuan-key")
	s.cacheMu.Lock()
	s.structure = nil
	s.counts = nil
	s.cacheMu.Unlock()
	return s.State(), nil
}

func (s *Shuiyuan) requestWithKey(method, path string, body any, key, clientID string) ([]byte, error) {
	var reader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(data)
	}
	request, err := http.NewRequestWithContext(context.Background(), method, shuiyuanOrigin+path, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Api-Key", key)
	request.Header.Set("User-Api-Client-Id", clientID)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "SJTU-Desktop/0.1")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := s.client.Do(request)
	if err != nil {
		return nil, errors.New("无法连接水源社区，请检查网络后重试")
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	if response.StatusCode >= 200 && response.StatusCode < 300 {
		return data, nil
	}
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		return nil, ErrShuiyuanAuth
	}
	if response.StatusCode == http.StatusTooManyRequests {
		return nil, errors.New("请求过于频繁，已触发水源限流，请稍后再试")
	}
	var payload struct {
		Errors []string `json:"errors"`
	}
	_ = json.Unmarshal(data, &payload)
	if len(payload.Errors) > 0 {
		return nil, errors.New("水源返回错误：" + strings.Join(payload.Errors, "；"))
	}
	return nil, fmt.Errorf("水源接口返回 HTTP %d", response.StatusCode)
}

func (s *Shuiyuan) request(method, path string, body any) ([]byte, error) {
	if credential, err := s.credential(); err == nil {
		data, keyErr := s.requestWithKey(method, path, body, credential.Key, credential.ClientID)
		if keyErr == nil {
			return data, nil
		}
		if !errors.Is(keyErr, ErrShuiyuanAuth) {
			return nil, keyErr
		}
	}
	return s.requestWithCampus(method, path, body)
}

func (s *Shuiyuan) campusSessionClient() (*http.Client, error) {
	s.campusMu.Lock()
	defer s.campusMu.Unlock()
	if s.campusClient != nil {
		return s.campusClient, nil
	}
	if s.campus == nil || !s.campus.Connected() {
		return nil, errors.New("请先在设置中登录 jAccount")
	}
	jaccount, _ := url.Parse("https://jaccount.sjtu.edu.cn/")
	jar, _ := cookiejar.New(nil)
	jar.SetCookies(jaccount, s.campus.Cookies(jaccount))
	client := &http.Client{Jar: jar, Timeout: 30 * time.Second}
	loginRequest, _ := http.NewRequest(http.MethodGet, shuiyuanOrigin+"/auth/jaccount", nil)
	loginRequest.Header.Set("User-Agent", portalUserAgent)
	response, err := client.Do(loginRequest)
	if err != nil {
		return nil, errors.New("连接水源社区失败，请检查网络后重试")
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<20))
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, errors.New("设置中的 jAccount 登录已失效，请先在设置中重新登录")
	}
	currentRequest, _ := http.NewRequest(http.MethodGet, shuiyuanOrigin+"/session/current.json", nil)
	currentRequest.Header.Set("Accept", "application/json")
	currentRequest.Header.Set("User-Agent", portalUserAgent)
	currentResponse, err := client.Do(currentRequest)
	if err != nil {
		return nil, errors.New("连接水源社区失败，请检查网络后重试")
	}
	currentBody, _ := io.ReadAll(io.LimitReader(currentResponse.Body, 1<<20))
	_ = currentResponse.Body.Close()
	var current struct {
		CurrentUser struct {
			Username string `json:"username"`
		} `json:"current_user"`
	}
	if err := json.Unmarshal(currentBody, &current); err != nil || current.CurrentUser.Username == "" {
		return nil, errors.New("jAccount 未能登录水源社区，请先在设置中重新登录")
	}
	s.campusClient = client
	s.campusUsername = current.CurrentUser.Username
	return client, nil
}

func (s *Shuiyuan) clearCampusClient() {
	s.campusMu.Lock()
	s.campusClient = nil
	s.campusMu.Unlock()
}

func (s *Shuiyuan) requestWithCampus(method, path string, body any) ([]byte, error) {
	client, err := s.campusSessionClient()
	if err != nil {
		return nil, err
	}
	var reader io.Reader
	if body != nil {
		data, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(data)
	}
	request, err := http.NewRequestWithContext(context.Background(), method, shuiyuanOrigin+path, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", portalUserAgent)
	request.Header.Set("Referer", shuiyuanOrigin+"/")
	request.Header.Set("Origin", shuiyuanOrigin)
	if method != http.MethodGet && method != http.MethodHead {
		csrf, err := s.campusCSRF(client)
		if err != nil {
			return nil, err
		}
		request.Header.Set("X-CSRF-Token", csrf)
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, errors.New("无法连接水源社区，请检查网络后重试")
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(response.Body, 32<<20))
	if response.StatusCode >= 200 && response.StatusCode < 300 {
		return data, nil
	}
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		s.clearCampusClient()
		return nil, ErrShuiyuanAuth
	}
	if response.StatusCode == http.StatusTooManyRequests {
		return nil, errors.New("请求过于频繁，已触发水源限流，请稍后再试")
	}
	var payload struct {
		Errors []string `json:"errors"`
	}
	_ = json.Unmarshal(data, &payload)
	if len(payload.Errors) > 0 {
		return nil, errors.New("水源返回错误：" + strings.Join(payload.Errors, "；"))
	}
	return nil, fmt.Errorf("水源接口返回 HTTP %d", response.StatusCode)
}

func (s *Shuiyuan) campusCSRF(client *http.Client) (string, error) {
	request, _ := http.NewRequest(http.MethodGet, shuiyuanOrigin+"/session/csrf.json", nil)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", portalUserAgent)
	response, err := client.Do(request)
	if err != nil {
		return "", errors.New("无法连接水源社区，请检查网络后重试")
	}
	defer response.Body.Close()
	data, _ := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	var payload struct {
		CSRF string `json:"csrf"`
	}
	if err := json.Unmarshal(data, &payload); err != nil || payload.CSRF == "" {
		return "", errors.New("水源没有返回 CSRF 校验")
	}
	return payload.CSRF, nil
}

func (s *Shuiyuan) SiteStructure() (ShuiyuanSiteStructure, error) {
	s.cacheMu.Lock()
	if s.structure != nil && time.Since(s.structureAt) < 10*time.Minute {
		value := *s.structure
		s.cacheMu.Unlock()
		return value, nil
	}
	s.cacheMu.Unlock()
	data, err := s.request(http.MethodGet, "/site.json", nil)
	if err != nil {
		return ShuiyuanSiteStructure{}, err
	}
	var raw struct {
		Categories []struct {
			ID               int64  `json:"id"`
			Name             string `json:"name"`
			Slug             string `json:"slug"`
			Color            string `json:"color"`
			ParentCategoryID *int64 `json:"parent_category_id"`
			TopicCount       *int64 `json:"topic_count"`
		} `json:"categories"`
		TopTags []struct {
			Name       string `json:"name"`
			TopicCount *int64 `json:"topic_count"`
		} `json:"top_tags"`
		CustomEmoji []struct {
			Name string `json:"name"`
			URL  string `json:"url"`
		} `json:"custom_emoji"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return ShuiyuanSiteStructure{}, errors.New("水源站点数据结构无法识别")
	}
	structure := ShuiyuanSiteStructure{Categories: []ShuiyuanCategory{}, Tags: []ShuiyuanTag{}, CustomEmoji: []ShuiyuanCustomEmoji{}}
	for _, category := range raw.Categories {
		if category.Slug == "uncategorized" {
			continue
		}
		color := category.Color
		if color == "" {
			color = "808281"
		}
		structure.Categories = append(structure.Categories, ShuiyuanCategory{
			ID: category.ID, Name: category.Name, Slug: category.Slug, Color: color,
			ParentID: category.ParentCategoryID, TopicCount: category.TopicCount,
		})
	}
	for _, tag := range raw.TopTags {
		if tag.Name != "" {
			structure.Tags = append(structure.Tags, ShuiyuanTag{Name: tag.Name, TopicCount: tag.TopicCount})
		}
	}
	for _, emoji := range raw.CustomEmoji {
		if emoji.Name != "" && emoji.URL != "" {
			structure.CustomEmoji = append(structure.CustomEmoji, ShuiyuanCustomEmoji{Name: emoji.Name, URL: emoji.URL})
		}
	}
	s.cacheMu.Lock()
	s.structure = &structure
	s.structureAt = time.Now()
	s.cacheMu.Unlock()
	return structure, nil
}

type shuiyuanUser struct {
	ID             int64  `json:"id"`
	Username       string `json:"username"`
	AvatarTemplate string `json:"avatar_template"`
}

type shuiyuanTagList []string

func (t *shuiyuanTagList) UnmarshalJSON(data []byte) error {
	trimmed := strings.TrimSpace(string(data))
	if trimmed == "null" || trimmed == "" {
		*t = nil
		return nil
	}
	var names []string
	if err := json.Unmarshal(data, &names); err == nil {
		*t = names
		return nil
	}
	var objects []struct {
		Name string `json:"name"`
	}
	if err := json.Unmarshal(data, &objects); err == nil {
		result := make([]string, 0, len(objects))
		for _, object := range objects {
			if object.Name != "" {
				result = append(result, object.Name)
			}
		}
		*t = result
		return nil
	}
	return errors.New("tags 字段格式无法识别")
}

type shuiyuanTopicRaw struct {
	ID           int64           `json:"id"`
	Title        string          `json:"title"`
	FancyTitle   string          `json:"fancy_title"`
	Excerpt      *string         `json:"excerpt"`
	CategoryID   *int64          `json:"category_id"`
	Tags         shuiyuanTagList `json:"tags"`
	PostsCount   int64           `json:"posts_count"`
	Views        *int64          `json:"views"`
	LikeCount    *int64          `json:"like_count"`
	CreatedAt    string          `json:"created_at"`
	LastPostedAt *string         `json:"last_posted_at"`
	Pinned       bool            `json:"pinned"`
	Closed       bool            `json:"closed"`
	Bookmarked   bool            `json:"bookmarked"`
	Archetype    string          `json:"archetype"`
	Posters      []struct {
		UserID int64 `json:"user_id"`
	} `json:"posters"`
	LastPosterUsername *string `json:"last_poster_username"`
}

func usersByID(users []shuiyuanUser) map[int64]shuiyuanUser {
	result := map[int64]shuiyuanUser{}
	for _, user := range users {
		result[user.ID] = user
	}
	return result
}

func mapShuiyuanTopic(raw shuiyuanTopicRaw, users map[int64]shuiyuanUser) ShuiyuanTopicSummary {
	title := raw.Title
	if title == "" {
		title = raw.FancyTitle
	}
	if title == "" {
		title = "(无标题)"
	}
	archetype := raw.Archetype
	if archetype == "" {
		archetype = "regular"
	}
	item := ShuiyuanTopicSummary{
		ID: raw.ID, Title: title, Excerpt: raw.Excerpt, CategoryID: raw.CategoryID,
		Tags: []string(raw.Tags), PostsCount: raw.PostsCount, Views: raw.Views, LikeCount: raw.LikeCount,
		CreatedAt: raw.CreatedAt, LastPostedAt: raw.LastPostedAt, Pinned: raw.Pinned,
		Closed: raw.Closed, Bookmarked: raw.Bookmarked, Archetype: archetype,
		LastPosterUsername: raw.LastPosterUsername,
	}
	if item.Tags == nil {
		item.Tags = []string{}
	}
	if len(raw.Posters) > 0 {
		if user, ok := users[raw.Posters[0].UserID]; ok {
			username := user.Username
			item.AuthorUsername = &username
			if user.AvatarTemplate != "" {
				avatar := user.AvatarTemplate
				item.AuthorAvatar = &avatar
			}
		}
	}
	return item
}

func (s *Shuiyuan) ListTopics(feed map[string]any, moreURL string) (ShuiyuanTopicPage, error) {
	kind := strings.TrimSpace(inputText(feed, "kind"))
	if kind == "" {
		kind = "latest"
	}
	if moreURL != "" {
		parsed, err := url.Parse(moreURL)
		if err != nil {
			return ShuiyuanTopicPage{}, errors.New("分页地址无效")
		}
		if parsed.Host == "" {
			parsed, err = url.Parse(shuiyuanOrigin + moreURL)
			if err != nil {
				return ShuiyuanTopicPage{}, errors.New("分页地址无效")
			}
		}
		if parsed.Host != "shuiyuan.sjtu.edu.cn" {
			return ShuiyuanTopicPage{}, errors.New("分页地址无效")
		}
		path := strings.TrimSuffix(parsed.Path, ".json") + ".json"
		if parsed.RawQuery != "" {
			path += "?" + parsed.RawQuery
		}
		return s.fetchTopicPage(kind, path, inputText(feed, "query"))
	}
	switch kind {
	case "latest":
		return s.fetchTopicPage(kind, "/latest.json", "")
	case "featured":
		return s.fetchTopicPage(kind, "/tag/"+url.PathEscape(shuiyuanFeaturedTag)+".json", "")
	case "tag":
		tag := strings.TrimSpace(inputText(feed, "tag"))
		if tag == "" {
			return ShuiyuanTopicPage{}, errors.New("请选择标签")
		}
		return s.fetchTopicPage(kind, "/tag/"+url.PathEscape(tag)+".json", "")
	case "category":
		categoryID := intFromAny(feed["categoryId"], 0)
		structure, err := s.SiteStructure()
		if err != nil {
			return ShuiyuanTopicPage{}, err
		}
		for _, category := range structure.Categories {
			if category.ID == int64(categoryID) {
				return s.fetchTopicPage(kind, fmt.Sprintf("/c/%s/%d.json", url.PathEscape(category.Slug), category.ID), "")
			}
		}
		return ShuiyuanTopicPage{}, errors.New("该分类不存在，请刷新后重试")
	case "search":
		query := strings.TrimSpace(inputText(feed, "query"))
		if query == "" {
			return ShuiyuanTopicPage{}, errors.New("请输入搜索关键词")
		}
		return s.fetchTopicPage(kind, "/search.json?q="+url.QueryEscape(query)+"&page=1", query)
	default:
		return ShuiyuanTopicPage{}, errors.New("未知的水源列表")
	}
}

func (s *Shuiyuan) fetchTopicPage(kind, path, query string) (ShuiyuanTopicPage, error) {
	data, err := s.request(http.MethodGet, path, nil)
	if err != nil {
		return ShuiyuanTopicPage{}, err
	}
	if kind == "search" {
		return mapShuiyuanSearch(data, query, path)
	}
	var raw struct {
		TopicList struct {
			Topics []shuiyuanTopicRaw `json:"topics"`
			More   *string            `json:"more_topics_url"`
		} `json:"topic_list"`
		Users []shuiyuanUser `json:"users"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return ShuiyuanTopicPage{}, errors.New("水源主题列表格式无法识别")
	}
	users := usersByID(raw.Users)
	page := ShuiyuanTopicPage{Topics: make([]ShuiyuanTopicSummary, 0, len(raw.TopicList.Topics)), MoreURL: raw.TopicList.More}
	for _, topic := range raw.TopicList.Topics {
		page.Topics = append(page.Topics, mapShuiyuanTopic(topic, users))
	}
	return page, nil
}

func mapShuiyuanSearch(data []byte, query, path string) (ShuiyuanTopicPage, error) {
	var raw struct {
		Topics []shuiyuanTopicRaw `json:"topics"`
		Posts  []struct {
			TopicID *int64  `json:"topic_id"`
			Blurb   *string `json:"blurb"`
		} `json:"posts"`
		Users   []shuiyuanUser `json:"users"`
		Grouped struct {
			More *bool `json:"more_full_page_results"`
		} `json:"grouped_search_result"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return ShuiyuanTopicPage{}, errors.New("水源搜索结果格式无法识别")
	}
	blurbs := map[int64]string{}
	for _, post := range raw.Posts {
		if post.TopicID != nil && post.Blurb != nil {
			if _, exists := blurbs[*post.TopicID]; !exists {
				blurbs[*post.TopicID] = *post.Blurb
			}
		}
	}
	users := usersByID(raw.Users)
	topics := make([]ShuiyuanTopicSummary, 0, len(raw.Topics))
	for _, topic := range raw.Topics {
		item := mapShuiyuanTopic(topic, users)
		if blurb, ok := blurbs[item.ID]; ok {
			item.Excerpt = &blurb
		}
		topics = append(topics, item)
	}
	page := ShuiyuanTopicPage{Topics: topics}
	if raw.Grouped.More != nil && *raw.Grouped.More {
		pageNumber := int64(1)
		if parsed, err := url.Parse(path); err == nil {
			if value, err := strconv.ParseInt(parsed.Query().Get("page"), 10, 64); err == nil {
				pageNumber = value
			}
		}
		next := fmt.Sprintf("/search.json?q=%s&page=%d", url.QueryEscape(query), pageNumber+1)
		page.MoreURL = &next
	}
	return page, nil
}

type shuiyuanPostRaw struct {
	ID                int64   `json:"id"`
	PostNumber        int64   `json:"post_number"`
	Username          string  `json:"username"`
	Name              *string `json:"name"`
	AvatarTemplate    *string `json:"avatar_template"`
	CreatedAt         string  `json:"created_at"`
	Cooked            string  `json:"cooked"`
	ReplyToPostNumber *int64  `json:"reply_to_post_number"`
	ActionsSummary    []struct {
		ID    int64  `json:"id"`
		Count *int64 `json:"count"`
		Acted *bool  `json:"acted"`
	} `json:"actions_summary"`
	Yours bool `json:"yours"`
}

func (s *Shuiyuan) mapPost(raw shuiyuanPostRaw) ShuiyuanPost {
	post := ShuiyuanPost{
		ID: raw.ID, PostNumber: raw.PostNumber, Username: raw.Username, Name: raw.Name,
		AvatarTemplate: raw.AvatarTemplate, CreatedAt: raw.CreatedAt, Cooked: s.rewriteAssets(raw.Cooked),
		ReplyToPostNumber: raw.ReplyToPostNumber, Yours: raw.Yours,
	}
	for _, action := range raw.ActionsSummary {
		if action.ID == 2 {
			post.LikeCount = action.Count
			post.Liked = action.Acted != nil && *action.Acted
		}
	}
	return post
}

func (s *Shuiyuan) GetTopic(id int64, postNumber *int64) (ShuiyuanTopicDetail, error) {
	path := fmt.Sprintf("/t/%d.json", id)
	if postNumber != nil && *postNumber > 0 {
		path = fmt.Sprintf("/t/%d/%d.json", id, *postNumber)
	}
	data, err := s.request(http.MethodGet, path, nil)
	if err != nil {
		return ShuiyuanTopicDetail{}, err
	}
	var raw struct {
		shuiyuanTopicRaw
		PostStream struct {
			Stream []int64           `json:"stream"`
			Posts  []shuiyuanPostRaw `json:"posts"`
		} `json:"post_stream"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return ShuiyuanTopicDetail{}, errors.New("水源主题格式无法识别")
	}
	detail := ShuiyuanTopicDetail{
		ID: raw.ID, Title: firstNonEmpty(raw.Title, raw.FancyTitle, "(无标题)"),
		CategoryID: raw.CategoryID, Tags: []string(raw.Tags), PostsCount: raw.PostsCount,
		LikeCount: raw.LikeCount, Views: raw.Views, CreatedAt: raw.CreatedAt,
		LastPostedAt: raw.LastPostedAt, Closed: raw.Closed, Archetype: raw.Archetype,
		Bookmarked: raw.Bookmarked, Stream: raw.PostStream.Stream,
		Posts: make([]ShuiyuanPost, 0, len(raw.PostStream.Posts)),
	}
	if detail.Archetype == "" {
		detail.Archetype = "regular"
	}
	if detail.Tags == nil {
		detail.Tags = []string{}
	}
	if detail.Stream == nil {
		detail.Stream = []int64{}
	}
	for _, post := range raw.PostStream.Posts {
		detail.Posts = append(detail.Posts, s.mapPost(post))
	}
	return detail, nil
}

func (s *Shuiyuan) LoadPosts(topicID int64, postIDs []int64) ([]ShuiyuanPost, error) {
	if topicID <= 0 || len(postIDs) == 0 {
		return []ShuiyuanPost{}, nil
	}
	if len(postIDs) > 50 {
		postIDs = postIDs[:50]
	}
	parts := make([]string, 0, len(postIDs))
	for _, id := range postIDs {
		parts = append(parts, "post_ids[]="+strconv.FormatInt(id, 10))
	}
	data, err := s.request(http.MethodGet, fmt.Sprintf("/t/%d/posts.json?%s", topicID, strings.Join(parts, "&")), nil)
	if err != nil {
		return nil, err
	}
	var raw struct {
		PostStream struct {
			Posts []shuiyuanPostRaw `json:"posts"`
		} `json:"post_stream"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, errors.New("水源楼层数据格式无法识别")
	}
	result := make([]ShuiyuanPost, 0, len(raw.PostStream.Posts))
	for _, post := range raw.PostStream.Posts {
		result = append(result, s.mapPost(post))
	}
	return result, nil
}

func (s *Shuiyuan) Reply(topicID int64, raw string, replyTo *int64) (ShuiyuanPost, error) {
	raw = strings.TrimSpace(raw)
	if topicID <= 0 || raw == "" {
		return ShuiyuanPost{}, errors.New("回复内容不能为空")
	}
	if len([]rune(raw)) > 20000 {
		return ShuiyuanPost{}, errors.New("回复内容过长")
	}
	body := map[string]any{"topic_id": topicID, "raw": raw}
	if replyTo != nil && *replyTo > 0 {
		body["reply_to_post_number"] = *replyTo
	}
	data, err := s.request(http.MethodPost, "/posts.json", body)
	if err != nil {
		return ShuiyuanPost{}, err
	}
	var post shuiyuanPostRaw
	if err := json.Unmarshal(data, &post); err != nil {
		return ShuiyuanPost{}, errors.New("水源回复结果无法识别")
	}
	s.counts = nil
	return s.mapPost(post), nil
}

func (s *Shuiyuan) CreateTopic(title, raw string, categoryID int64, tags []string) (map[string]any, error) {
	title = strings.TrimSpace(title)
	raw = strings.TrimSpace(raw)
	if title == "" || raw == "" {
		return nil, errors.New("标题和正文不能为空")
	}
	if len([]rune(title)) > 255 || len([]rune(raw)) > 20000 {
		return nil, errors.New("标题或正文过长")
	}
	body := map[string]any{"title": title, "raw": raw}
	if categoryID > 0 {
		body["category"] = categoryID
	}
	if len(tags) > 0 {
		body["tags"] = tags
	}
	data, err := s.request(http.MethodPost, "/posts.json", body)
	if err != nil {
		return nil, err
	}
	var post shuiyuanPostRaw
	if err := json.Unmarshal(data, &post); err != nil {
		return nil, errors.New("水源发帖结果无法识别")
	}
	var payload struct {
		TopicID int64 `json:"topic_id"`
	}
	_ = json.Unmarshal(data, &payload)
	topicID := payload.TopicID
	if topicID == 0 {
		topicID = post.ID
	}
	return map[string]any{"topicId": topicID, "post": s.mapPost(post)}, nil
}

func (s *Shuiyuan) SendMessage(targets []string, title, raw string) (int64, error) {
	if len(targets) == 0 {
		return 0, errors.New("请填写收件人")
	}
	title = strings.TrimSpace(title)
	raw = strings.TrimSpace(raw)
	if title == "" || raw == "" {
		return 0, errors.New("私信标题和内容不能为空")
	}
	data, err := s.request(http.MethodPost, "/posts.json", map[string]any{
		"raw": raw, "title": title, "archetype": "private_message", "target_usernames": targets,
	})
	if err != nil {
		return 0, err
	}
	var post shuiyuanPostRaw
	if err := json.Unmarshal(data, &post); err != nil {
		return 0, errors.New("水源私信结果无法识别")
	}
	var payload struct {
		TopicID int64 `json:"topic_id"`
	}
	_ = json.Unmarshal(data, &payload)
	if payload.TopicID != 0 {
		return payload.TopicID, nil
	}
	return post.ID, nil
}

func (s *Shuiyuan) LikePost(postID int64, liked bool) error {
	if postID <= 0 {
		return errors.New("楼层 ID 无效")
	}
	if liked {
		_, err := s.request(http.MethodPost, "/post_actions.json", map[string]any{
			"id": postID, "post_action_type_id": 2,
		})
		return err
	}
	_, err := s.request(http.MethodDelete, fmt.Sprintf("/post_actions/%d.json?post_action_type_id=2", postID), nil)
	if err != nil {
		return errors.New("取消点赞失败；水源可能拒绝了该操作，请到网页端取消")
	}
	return nil
}

func (s *Shuiyuan) Bookmarks() ([]ShuiyuanBookmarkItem, error) {
	credential, err := s.credential()
	if err != nil {
		return nil, err
	}
	data, err := s.request(http.MethodGet, "/u/"+url.PathEscape(credential.Username)+"/bookmarks.json", nil)
	if err != nil {
		return nil, err
	}
	var raw struct {
		UserBookmarkList struct {
			Bookmarks []struct {
				ID               int64   `json:"id"`
				TopicID          *int64  `json:"topic_id"`
				BookmarkableID   *int64  `json:"bookmarkable_id"`
				Title            string  `json:"title"`
				FancyTitle       string  `json:"fancy_title"`
				Excerpt          *string `json:"excerpt"`
				CreatedAt        string  `json:"created_at"`
				LinkedPostNumber *int64  `json:"linked_post_number"`
			} `json:"bookmarks"`
		} `json:"user_bookmark_list"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, errors.New("水源收藏格式无法识别")
	}
	result := make([]ShuiyuanBookmarkItem, 0, len(raw.UserBookmarkList.Bookmarks))
	for _, bookmark := range raw.UserBookmarkList.Bookmarks {
		topicID := int64(0)
		if bookmark.TopicID != nil {
			topicID = *bookmark.TopicID
		} else if bookmark.BookmarkableID != nil {
			topicID = *bookmark.BookmarkableID
		}
		if topicID <= 0 {
			continue
		}
		result = append(result, ShuiyuanBookmarkItem{
			ID: bookmark.ID, TopicID: topicID,
			Title:   firstNonEmpty(bookmark.Title, bookmark.FancyTitle, "(无标题)"),
			Excerpt: bookmark.Excerpt, CreatedAt: bookmark.CreatedAt,
			LinkedPostNumber: bookmark.LinkedPostNumber,
		})
	}
	return result, nil
}

func (s *Shuiyuan) ToggleBookmark(topicID int64, on bool) (bool, error) {
	if topicID <= 0 {
		return false, errors.New("主题 ID 无效")
	}
	if on {
		_, err := s.request(http.MethodPost, "/bookmarks", map[string]any{
			"bookmarkable_id": topicID, "bookmarkable_type": "Topic",
		})
		return err == nil, err
	}
	bookmarks, err := s.Bookmarks()
	if err != nil {
		return false, err
	}
	for _, bookmark := range bookmarks {
		if bookmark.TopicID == topicID {
			_, err := s.request(http.MethodDelete, fmt.Sprintf("/bookmarks/%d", bookmark.ID), nil)
			return false, err
		}
	}
	return false, nil
}

func (s *Shuiyuan) Notifications() ([]ShuiyuanNotificationItem, error) {
	data, err := s.request(http.MethodGet, "/notifications.json?recent=true", nil)
	if err != nil {
		return nil, err
	}
	var raw struct {
		Notifications []struct {
			ID               int64   `json:"id"`
			NotificationType int64   `json:"notification_type"`
			Read             bool    `json:"read"`
			HighPriority     bool    `json:"high_priority"`
			CreatedAt        string  `json:"created_at"`
			TopicID          *int64  `json:"topic_id"`
			PostNumber       *int64  `json:"post_number"`
			FancyTitle       *string `json:"fancy_title"`
			Data             struct {
				TopicTitle      *string `json:"topic_title"`
				DisplayUsername *string `json:"display_username"`
			} `json:"data"`
		} `json:"notifications"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, errors.New("水源通知格式无法识别")
	}
	result := make([]ShuiyuanNotificationItem, 0, len(raw.Notifications))
	for _, notification := range raw.Notifications {
		title := notification.FancyTitle
		if title == nil {
			title = notification.Data.TopicTitle
		}
		result = append(result, ShuiyuanNotificationItem{
			ID: notification.ID, Type: notification.NotificationType, Read: notification.Read,
			HighPriority: notification.HighPriority, CreatedAt: notification.CreatedAt,
			TopicID: notification.TopicID, PostNumber: notification.PostNumber,
			FancyTitle: title, DisplayUsername: notification.Data.DisplayUsername,
		})
	}
	return result, nil
}

func (s *Shuiyuan) MarkNotificationsRead() error {
	_, err := s.request(http.MethodPut, "/notifications/mark-read", nil)
	if err == nil {
		s.cacheMu.Lock()
		s.counts = nil
		s.cacheMu.Unlock()
	}
	return err
}

func (s *Shuiyuan) Counts() (ShuiyuanCounts, error) {
	s.cacheMu.Lock()
	if s.counts != nil && time.Since(s.countsAt) < time.Minute {
		value := *s.counts
		s.cacheMu.Unlock()
		return value, nil
	}
	s.cacheMu.Unlock()
	data, err := s.request(http.MethodGet, "/session/current.json", nil)
	if err != nil {
		return ShuiyuanCounts{}, err
	}
	var raw struct {
		CurrentUser struct {
			UnreadNotifications int64 `json:"unread_notifications"`
			UnreadMessages      int64 `json:"unread_private_messages"`
		} `json:"current_user"`
	}
	if err := json.Unmarshal(data, &raw); err != nil {
		return ShuiyuanCounts{}, errors.New("水源计数格式无法识别")
	}
	value := ShuiyuanCounts{
		Notifications: raw.CurrentUser.UnreadNotifications,
		Messages:      raw.CurrentUser.UnreadMessages,
	}
	s.cacheMu.Lock()
	s.counts = &value
	s.countsAt = time.Now()
	s.cacheMu.Unlock()
	return value, nil
}

func (s *Shuiyuan) Upload(fileName, base64Data string) (ShuiyuanUpload, error) {
	fileName = strings.TrimSpace(fileName)
	if fileName == "" {
		return ShuiyuanUpload{}, errors.New("文件名不能为空")
	}
	raw := base64Data
	if index := strings.Index(raw, "base64,"); index >= 0 {
		raw = raw[index+7:]
	}
	data, err := base64.StdEncoding.DecodeString(raw)
	if err != nil {
		return ShuiyuanUpload{}, errors.New("附件数据无效")
	}
	if len(data) > 20<<20 {
		return ShuiyuanUpload{}, errors.New("附件超过 20 MB")
	}
	credential, err := s.credential()
	if err != nil {
		return ShuiyuanUpload{}, err
	}
	var buffer bytes.Buffer
	writer := multipart.NewWriter(&buffer)
	_ = writer.WriteField("type", "composer")
	part, err := writer.CreateFormFile("files[]", fileName)
	if err != nil {
		return ShuiyuanUpload{}, err
	}
	if _, err := part.Write(data); err != nil {
		return ShuiyuanUpload{}, err
	}
	_ = writer.Close()
	request, err := http.NewRequest(http.MethodPost, shuiyuanOrigin+"/uploads.json", &buffer)
	if err != nil {
		return ShuiyuanUpload{}, err
	}
	request.Header.Set("User-Api-Key", credential.Key)
	request.Header.Set("User-Api-Client-Id", credential.ClientID)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Content-Type", writer.FormDataContentType())
	response, err := s.client.Do(request)
	if err != nil {
		return ShuiyuanUpload{}, errors.New("上传附件时无法连接水源")
	}
	defer response.Body.Close()
	responseData, _ := io.ReadAll(io.LimitReader(response.Body, 4<<20))
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		var payload struct {
			Errors []string `json:"errors"`
		}
		_ = json.Unmarshal(responseData, &payload)
		if len(payload.Errors) > 0 {
			return ShuiyuanUpload{}, errors.New("水源返回错误：" + strings.Join(payload.Errors, "；"))
		}
		return ShuiyuanUpload{}, fmt.Errorf("上传失败：HTTP %d", response.StatusCode)
	}
	var payload struct {
		ShortURL         *string `json:"short_url"`
		URL              *string `json:"url"`
		OriginalFilename *string `json:"original_filename"`
	}
	if err := json.Unmarshal(responseData, &payload); err != nil {
		return ShuiyuanUpload{}, errors.New("水源返回的附件响应无法识别")
	}
	short := ""
	if payload.ShortURL != nil {
		short = *payload.ShortURL
	}
	if short == "" && payload.URL != nil {
		short = *payload.URL
	}
	if short == "" {
		return ShuiyuanUpload{}, errors.New("水源没有返回附件地址")
	}
	original := payload.OriginalFilename
	if original == nil || *original == "" {
		original = &fileName
	}
	return ShuiyuanUpload{ShortURL: short, URL: payload.URL, OriginalFilename: original}, nil
}

func (s *Shuiyuan) UploadPath(path string) (ShuiyuanUpload, error) {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return ShuiyuanUpload{}, errors.New("文件不存在")
	}
	if info.Size() > 20<<20 {
		return ShuiyuanUpload{}, errors.New("附件超过 20 MB")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return ShuiyuanUpload{}, err
	}
	return s.Upload(filepath.Base(path), base64.StdEncoding.EncodeToString(data))
}

func (s *Shuiyuan) Register(router *Router) {
	router.Handle("shuiyuan.state", func([]json.RawMessage) (any, error) {
		return s.State(), nil
	})
	router.Handle("shuiyuan.startAuth", func([]json.RawMessage) (any, error) {
		return s.StartAuth()
	})
	router.Handle("shuiyuan.bind", func(args []json.RawMessage) (any, error) {
		payload, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return s.Bind(payload)
	})
	router.Handle("shuiyuan.unbind", func([]json.RawMessage) (any, error) {
		return s.Unbind()
	})
	router.Handle("shuiyuan.site", func([]json.RawMessage) (any, error) {
		return s.SiteStructure()
	})
	router.Handle("shuiyuan.topics", func(args []json.RawMessage) (any, error) {
		feed, err := DecodeArg[map[string]any](args, 0)
		if err != nil {
			return nil, err
		}
		moreURL, _, _ := DecodeOptionalArg[string](args, 1)
		return s.ListTopics(feed, moreURL)
	})
	router.Handle("shuiyuan.topic", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		postNumber, ok, err := DecodeOptionalArg[int64](args, 1)
		if err != nil {
			return nil, err
		}
		if !ok {
			return s.GetTopic(id, nil)
		}
		return s.GetTopic(id, &postNumber)
	})
	router.Handle("shuiyuan.loadPosts", func(args []json.RawMessage) (any, error) {
		topicID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		postIDs, err := DecodeArg[[]int64](args, 1)
		if err != nil {
			return nil, err
		}
		return s.LoadPosts(topicID, postIDs)
	})
	router.Handle("shuiyuan.reply", func(args []json.RawMessage) (any, error) {
		topicID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		raw, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		replyTo, ok, _ := DecodeOptionalArg[int64](args, 2)
		if ok {
			return s.Reply(topicID, raw, &replyTo)
		}
		return s.Reply(topicID, raw, nil)
	})
	router.Handle("shuiyuan.createTopic", func(args []json.RawMessage) (any, error) {
		title, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		raw, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		categoryID, _, _ := DecodeOptionalArg[int64](args, 2)
		tags, _, _ := DecodeOptionalArg[[]string](args, 3)
		return s.CreateTopic(title, raw, categoryID, tags)
	})
	router.Handle("shuiyuan.sendMessage", func(args []json.RawMessage) (any, error) {
		targets, err := DecodeArg[[]string](args, 0)
		if err != nil {
			return nil, err
		}
		title, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		raw, err := DecodeArg[string](args, 2)
		if err != nil {
			return nil, err
		}
		return s.SendMessage(targets, title, raw)
	})
	router.Handle("shuiyuan.likePost", func(args []json.RawMessage) (any, error) {
		postID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		liked, err := DecodeArg[bool](args, 1)
		if err != nil {
			return nil, err
		}
		return true, s.LikePost(postID, liked)
	})
	router.Handle("shuiyuan.bookmarks", func([]json.RawMessage) (any, error) {
		return s.Bookmarks()
	})
	router.Handle("shuiyuan.toggleBookmark", func(args []json.RawMessage) (any, error) {
		topicID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		on, err := DecodeArg[bool](args, 1)
		if err != nil {
			return nil, err
		}
		return s.ToggleBookmark(topicID, on)
	})
	router.Handle("shuiyuan.profile", func(args []json.RawMessage) (any, error) {
		username, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return s.GetProfile(username)
	})
	router.Handle("shuiyuan.activity", func(args []json.RawMessage) (any, error) {
		username, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return s.GetActivity(username)
	})
	router.Handle("shuiyuan.notifications", func([]json.RawMessage) (any, error) {
		return s.Notifications()
	})
	router.Handle("shuiyuan.markNotificationsRead", func([]json.RawMessage) (any, error) {
		return true, s.MarkNotificationsRead()
	})
	router.Handle("shuiyuan.counts", func([]json.RawMessage) (any, error) {
		return s.Counts()
	})
	router.Handle("shuiyuan.uploadPath", func(args []json.RawMessage) (any, error) {
		path, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return s.UploadPath(path)
	})
	router.Handle("shuiyuan.upload", func(args []json.RawMessage) (any, error) {
		fileName, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		data, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return s.Upload(fileName, data)
	})
}
