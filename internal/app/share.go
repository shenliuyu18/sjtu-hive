package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

var shareAPIBase = "https://api-v2.share.dyweb.sjtu.cn"

const shareSecret = "share-token"

var errShareAuth = errors.New("传承交大登录已失效，请重新登录")

type Share struct {
	secrets *SecretStore
	campus  *CampusSession

	mu    sync.Mutex
	token string
}

type shareState struct {
	LoggedIn bool `json:"loggedIn"`
}

func NewShare(secrets *SecretStore, campus *CampusSession) *Share {
	service := &Share{secrets: secrets, campus: campus}
	var saved struct {
		Token string `json:"token"`
	}
	if ok, _ := secrets.Load(shareSecret, &saved); ok {
		service.token = strings.TrimSpace(saved.Token)
	}
	return service
}

func (s *Share) State() shareState {
	s.mu.Lock()
	defer s.mu.Unlock()
	return shareState{LoggedIn: s.token != ""}
}

func (s *Share) Login(ctx context.Context) (shareState, error) {
	if token, err := s.loginWithCampus(ctx); err == nil {
		return s.storeToken(token)
	}
	token, err := loginShareToken(ctx)
	if err != nil {
		return shareState{}, err
	}
	return s.storeToken(token)
}

// LoginSilent reuses the jAccount session saved from Settings without user input.
func (s *Share) LoginSilent(ctx context.Context) (shareState, error) {
	if s.State().LoggedIn {
		return shareState{LoggedIn: true}, nil
	}
	token, err := s.loginWithCampus(ctx)
	if err != nil {
		return shareState{}, err
	}
	return s.storeToken(token)
}

func (s *Share) storeToken(token string) (shareState, error) {
	token = strings.TrimSpace(token)
	if token == "" {
		return shareState{}, errors.New("没有读取到传承交大登录凭据")
	}
	s.mu.Lock()
	s.token = token
	_ = s.secrets.Save(shareSecret, map[string]string{"token": token})
	s.mu.Unlock()
	return shareState{LoggedIn: true}, nil
}

func (s *Share) Logout() shareState {
	s.mu.Lock()
	s.token = ""
	_ = s.secrets.Delete(shareSecret)
	s.mu.Unlock()
	return shareState{LoggedIn: false}
}

// clearToken only removes the token used by the failed request, so a late 401
// from an old request cannot wipe a newer login.
func (s *Share) clearToken(used string) {
	s.mu.Lock()
	if s.token == used {
		s.token = ""
		_ = s.secrets.Delete(shareSecret)
	}
	s.mu.Unlock()
}

func shareErrorMessage(message string) string {
	switch strings.TrimSpace(message) {
	case "you have not purchase the material!":
		return "该资料需要先兑换或领取后才能下载"
	default:
		return strings.TrimSpace(message)
	}
}

func (s *Share) api(ctx context.Context, method, path string, body any) (json.RawMessage, error) {
	s.mu.Lock()
	token := s.token
	s.mu.Unlock()
	if token == "" {
		return nil, errShareAuth
	}
	var reader io.Reader
	if body != nil {
		data, _ := json.Marshal(body)
		reader = bytes.NewReader(data)
	}
	request, err := http.NewRequestWithContext(ctx, method, shareAPIBase+path, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Auth", token)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", portalUserAgent)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := (&http.Client{Timeout: 25 * time.Second}).Do(request)
	if err != nil {
		return nil, fmt.Errorf("连接传承交大失败：%w", err)
	}
	data, _ := io.ReadAll(io.LimitReader(response.Body, 8<<20))
	_ = response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized {
		s.clearToken(token)
		return nil, errShareAuth
	}
	var envelope struct {
		Success bool            `json:"success"`
		Message string          `json:"message"`
		Data    json.RawMessage `json:"data"`
	}
	if err := json.Unmarshal(data, &envelope); err != nil {
		if response.StatusCode < 200 || response.StatusCode >= 300 {
			return nil, fmt.Errorf("传承交大返回 HTTP %d", response.StatusCode)
		}
		return data, nil
	}
	if !envelope.Success {
		if response.StatusCode == http.StatusUnauthorized {
			s.clearToken(token)
			return nil, errShareAuth
		}
		if message := shareErrorMessage(envelope.Message); message != "" {
			return nil, errors.New(message)
		}
		return nil, fmt.Errorf("传承交大返回 HTTP %d", response.StatusCode)
	}
	if len(envelope.Data) == 0 {
		return json.RawMessage("null"), nil
	}
	return envelope.Data, nil
}

func (s *Share) Banners(ctx context.Context) (json.RawMessage, error) {
	return s.api(ctx, http.MethodGet, "/api/v1/banner", nil)
}

func (s *Share) SearchCourses(ctx context.Context, keyword string) (json.RawMessage, error) {
	return s.api(ctx, http.MethodPost, "/api/v1/course/search", map[string]string{"keyword": strings.TrimSpace(keyword)})
}

func (s *Share) Materials(ctx context.Context, courseID int64) (json.RawMessage, error) {
	data, err := s.api(ctx, http.MethodGet, fmt.Sprintf("/api/v1/material?course_id=%d", courseID), nil)
	if err != nil {
		return nil, err
	}
	var payload struct {
		Unarchived []json.RawMessage `json:"unarchived"`
		Classes    []struct {
			Materials []json.RawMessage `json:"materials"`
		} `json:"classes"`
	}
	if json.Unmarshal(data, &payload) != nil {
		return data, nil
	}
	items := make([]json.RawMessage, 0, len(payload.Unarchived))
	items = append(items, payload.Unarchived...)
	for _, class := range payload.Classes {
		items = append(items, class.Materials...)
	}
	return json.Marshal(items)
}

func (s *Share) Material(ctx context.Context, id int64) (json.RawMessage, error) {
	return s.api(ctx, http.MethodGet, fmt.Sprintf("/api/v1/material/get/%d", id), nil)
}

func (s *Share) Download(ctx context.Context, id int64) (json.RawMessage, error) {
	return s.api(ctx, http.MethodGet, fmt.Sprintf("/api/v1/material/download/%d", id), nil)
}

func (s *Share) Purchase(ctx context.Context, id int64) (json.RawMessage, error) {
	return s.api(ctx, http.MethodGet, fmt.Sprintf("/api/v1/material/purchase/%d", id), nil)
}

func shareDownloadURL(data json.RawMessage) string {
	var direct string
	if json.Unmarshal(data, &direct) == nil && strings.TrimSpace(direct) != "" {
		return strings.TrimSpace(direct)
	}
	var payload map[string]any
	if json.Unmarshal(data, &payload) != nil {
		return ""
	}
	for _, key := range []string{"url", "download_url", "link", "file_url"} {
		if value, ok := payload[key].(string); ok && strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

// SaveTo streams the material behind the signed download URL into target.
func (s *Share) SaveTo(ctx context.Context, id int64, target string) error {
	data, err := s.Download(ctx, id)
	if err != nil {
		return err
	}
	link := shareDownloadURL(data)
	if link == "" {
		return errors.New("没有获取到下载链接")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return err
	}
	response, err := (&http.Client{Timeout: 5 * time.Minute}).Do(request)
	if err != nil {
		return fmt.Errorf("下载资料失败：%w", err)
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return fmt.Errorf("下载资料失败：HTTP %d", response.StatusCode)
	}
	temp, err := os.CreateTemp(filepath.Dir(target), ".share-*.part")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if _, err := io.Copy(temp, response.Body); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, target)
}

func (s *Share) Register(router *Router) {
	router.Handle("share.state", func([]json.RawMessage) (any, error) {
		return s.State(), nil
	})
	router.Handle("share.login", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
		defer cancel()
		return s.Login(ctx)
	})
	router.Handle("share.loginSilent", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
		defer cancel()
		return s.LoginSilent(ctx)
	})
	router.Handle("share.logout", func([]json.RawMessage) (any, error) {
		return s.Logout(), nil
	})
	router.Handle("share.banners", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return s.Banners(ctx)
	})
	router.Handle("share.searchCourses", func(args []json.RawMessage) (any, error) {
		keyword, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return s.SearchCourses(ctx, keyword)
	})
	router.Handle("share.materials", func(args []json.RawMessage) (any, error) {
		courseID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return s.Materials(ctx, courseID)
	})
	router.Handle("share.material", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return s.Material(ctx, id)
	})
	router.Handle("share.download", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return s.Download(ctx, id)
	})
	router.Handle("share.purchase", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return s.Purchase(ctx, id)
	})
}
