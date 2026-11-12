package app

// 交大云盘分享（coFile /user/v1/share）。创建、列出与删除都使用
// 30 天有效的 user_token；链接形如 https://pan.sjtu.edu.cn/web/share/{code}。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type CloudShare struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Code           string `json:"code"`
	URL            string `json:"url"`
	Type           string `json:"type"`
	Size           int64  `json:"size"`
	ExpireTime     string `json:"expireTime"`
	CreationTime   string `json:"creationTime"`
	IsPermanent    bool   `json:"isPermanent"`
	ExtractionCode string `json:"extractionCode"`
	CanPreview     bool   `json:"canPreview"`
	CanDownload    bool   `json:"canDownload"`
	CanSave        bool   `json:"canSaveToNetDisc"`
}

type CloudShareItem struct {
	Path     string `json:"path"`
	IsFolder bool   `json:"isFolder"`
}

type CloudShareOptions struct {
	Name           string `json:"name"`
	Permanent      bool   `json:"permanent"`
	ExpireDays     int    `json:"expireDays"`
	ExtractionCode string `json:"extractionCode"`
	CanPreview     bool   `json:"canPreview"`
	CanDownload    bool   `json:"canDownload"`
	CanSave        bool   `json:"canSaveToNetDisc"`
}

func (p *CloudPan) organizationID(ctx context.Context, userToken string) int {
	values := url.Values{}
	values.Set("user_token", userToken)
	values.Set("device_id", p.deviceID())
	var organizations []struct {
		ID             int  `json:"id"`
		IsLastSignedIn bool `json:"isLastSignedIn"`
	}
	if err := p.do(ctx, http.MethodGet, cloudPanOrigin+"/user/v1/organization?"+values.Encode(), "", nil, &organizations); err != nil {
		return 1
	}
	for _, organization := range organizations {
		if organization.IsLastSignedIn && organization.ID > 0 {
			return organization.ID
		}
	}
	for _, organization := range organizations {
		if organization.ID > 0 {
			return organization.ID
		}
	}
	return 1
}

func (p *CloudPan) CreateShare(ctx context.Context, items []CloudShareItem, options CloudShareOptions) (CloudShare, error) {
	p.mu.Lock()
	session := p.session
	p.mu.Unlock()
	if session.UserToken == "" {
		return CloudShare{}, ErrCloudPanAuth
	}
	token, err := p.accessToken(ctx, session.SpaceID)
	if err != nil {
		return CloudShare{}, err
	}
	orgID := p.organizationID(ctx, session.UserToken)

	clean := make([]CloudShareItem, 0, len(items))
	for _, item := range items {
		path := strings.Trim(item.Path, "/")
		if path == "" {
			continue
		}
		clean = append(clean, CloudShareItem{Path: path, IsFolder: item.IsFolder})
	}
	if len(clean) == 0 {
		return CloudShare{}, errors.New("请选择要分享的文件")
	}
	name := strings.TrimSpace(options.Name)
	if name == "" {
		if len(clean) == 1 {
			name = pathBase(clean[0].Path)
		} else {
			name = fmt.Sprintf("%s 等 %d 项", pathBase(clean[0].Path), len(clean))
		}
	}
	expire := time.Now().AddDate(0, 0, options.ExpireDays)
	if options.Permanent || options.ExpireDays <= 0 {
		expire = time.Now().AddDate(100, 0, 0)
	}
	directoryInfo := make([]map[string]any, 0, len(clean))
	for _, item := range clean {
		kind := "file"
		if item.IsFolder {
			kind = "dir"
		}
		directoryInfo = append(directoryInfo, map[string]any{
			"spaceOrgId": orgID,
			"spaceId":    token.SpaceID,
			"path":       item.Path,
			"type":       kind,
		})
	}
	body := map[string]any{
		"name":                name,
		"directoryInfoList":   directoryInfo,
		"expireTime":          expire.Format("2006-01-02"),
		"linkToLatestVersion": true,
		"canPreview":          options.CanPreview,
		"canDownload":         options.CanDownload,
		"canSaveToNetDisc":    options.CanSave,
	}
	if code := strings.TrimSpace(options.ExtractionCode); code != "" {
		body["extractionCode"] = code
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return CloudShare{}, err
	}
	values := url.Values{}
	values.Set("user_token", session.UserToken)
	endpoint := fmt.Sprintf("%s/user/v1/share/%d/?%s", cloudPanOrigin, orgID, values.Encode())
	var result struct {
		ID         int    `json:"id"`
		Code       string `json:"code"`
		Endpoint   string `json:"endpoint"`
		ExpireTime string `json:"expireTime"`
	}
	if err := p.do(ctx, http.MethodPost, endpoint, string(payload),
		map[string]string{"Content-Type": "application/json"}, &result); err != nil {
		return CloudShare{}, err
	}
	if result.Code == "" {
		return CloudShare{}, errors.New("云盘没有返回分享码")
	}
	share := CloudShare{
		ID:             strconv.Itoa(result.ID),
		Name:           name,
		Code:           result.Code,
		ExpireTime:     firstNonEmpty(result.ExpireTime, expire.Format("2006-01-02")),
		IsPermanent:    options.Permanent || options.ExpireDays <= 0,
		ExtractionCode: strings.TrimSpace(options.ExtractionCode),
		CanPreview:     options.CanPreview,
		CanDownload:    options.CanDownload,
		CanSave:        options.CanSave,
	}
	base := strings.TrimRight(strings.TrimSpace(result.Endpoint), "/")
	if base == "" {
		base = cloudPanOrigin
	}
	share.URL = base + "/web/share/" + url.PathEscape(result.Code)
	return share, nil
}

func pathBase(value string) string {
	trimmed := strings.Trim(value, "/")
	if index := strings.LastIndex(trimmed, "/"); index >= 0 {
		return trimmed[index+1:]
	}
	return trimmed
}

func (p *CloudPan) ListShares(ctx context.Context, page, pageSize int) ([]CloudShare, int, error) {
	p.mu.Lock()
	session := p.session
	p.mu.Unlock()
	if session.UserToken == "" {
		return nil, 0, ErrCloudPanAuth
	}
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	orgID := p.organizationID(ctx, session.UserToken)
	values := url.Values{}
	values.Set("user_token", session.UserToken)
	values.Set("page", strconv.Itoa(page))
	values.Set("page_size", strconv.Itoa(pageSize))
	values.Set("order_by", "creationTime")
	values.Set("order_by_type", "desc")
	endpoint := fmt.Sprintf("%s/user/v1/share/%d/list?%s", cloudPanOrigin, orgID, values.Encode())
	var payload struct {
		Contents []struct {
			ID             json.RawMessage `json:"id"`
			Name           string          `json:"name"`
			Code           string          `json:"code"`
			Type           string          `json:"type"`
			Size           panInt64        `json:"size"`
			ExpireTime     string          `json:"expireTime"`
			CreationTime   string          `json:"creationTime"`
			IsPermanent    bool            `json:"isPermanent"`
			ExtractionCode string          `json:"extractionCode"`
			AuthorityList  struct {
				CanPreview  bool `json:"canPreview"`
				CanDownload bool `json:"canDownload"`
				CanSave     bool `json:"canSaveToNetDisc"`
			} `json:"authorityList"`
		} `json:"contents"`
		TotalNum int `json:"totalNum"`
	}
	if err := p.do(ctx, http.MethodGet, endpoint, "", nil, &payload); err != nil {
		return nil, 0, err
	}
	shares := make([]CloudShare, 0, len(payload.Contents))
	for _, item := range payload.Contents {
		code := strings.TrimSpace(item.Code)
		share := CloudShare{
			ID:             rawString(item.ID),
			Name:           item.Name,
			Code:           code,
			Type:           item.Type,
			Size:           int64(item.Size),
			ExpireTime:     item.ExpireTime,
			CreationTime:   item.CreationTime,
			IsPermanent:    item.IsPermanent,
			ExtractionCode: item.ExtractionCode,
			CanPreview:     item.AuthorityList.CanPreview,
			CanDownload:    item.AuthorityList.CanDownload,
			CanSave:        item.AuthorityList.CanSave,
		}
		if code != "" {
			share.URL = cloudPanOrigin + "/web/share/" + url.PathEscape(code)
		}
		shares = append(shares, share)
	}
	return shares, payload.TotalNum, nil
}

func (p *CloudPan) DeleteShares(ctx context.Context, ids []string) error {
	p.mu.Lock()
	session := p.session
	p.mu.Unlock()
	if session.UserToken == "" {
		return ErrCloudPanAuth
	}
	clean := make([]string, 0, len(ids))
	for _, id := range ids {
		if trimmed := strings.TrimSpace(id); trimmed != "" {
			clean = append(clean, trimmed)
		}
	}
	if len(clean) == 0 {
		return nil
	}
	orgID := p.organizationID(ctx, session.UserToken)
	values := url.Values{}
	values.Set("user_token", session.UserToken)
	endpoint := fmt.Sprintf("%s/user/v1/share/%d/delete?%s", cloudPanOrigin, orgID, values.Encode())
	payload, _ := json.Marshal(clean)
	if err := p.do(ctx, http.MethodPost, endpoint, string(payload),
		map[string]string{"Content-Type": "application/json"}, nil); err != nil {
		return err
	}
	return nil
}

func (p *CloudPan) registerShareRoutes(router *Router) {
	router.Handle("cloudPan.share.create", func(args []json.RawMessage) (any, error) {
		if len(args) < 1 {
			return nil, errors.New("缺少分享内容")
		}
		var items []CloudShareItem
		if err := json.Unmarshal(args[0], &items); err != nil {
			return nil, errors.New("分享内容格式无效")
		}
		options := CloudShareOptions{CanPreview: true, CanDownload: true}
		if len(args) > 1 {
			_ = json.Unmarshal(args[1], &options)
		}
		ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
		defer cancel()
		return p.CreateShare(ctx, items, options)
	})
	router.Handle("cloudPan.share.list", func(args []json.RawMessage) (any, error) {
		page, _, _ := DecodeOptionalArg[int](args, 0)
		pageSize, _, _ := DecodeOptionalArg[int](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		shares, total, err := p.ListShares(ctx, page, pageSize)
		if err != nil {
			return nil, err
		}
		return map[string]any{"items": shares, "total": total}, nil
	})
	router.Handle("cloudPan.share.delete", func(args []json.RawMessage) (any, error) {
		ids, _, _ := DecodeOptionalArg[[]string](args, 0)
		if len(ids) == 0 {
			return map[string]any{"ok": true}, nil
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if err := p.DeleteShares(ctx, ids); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	})
}
