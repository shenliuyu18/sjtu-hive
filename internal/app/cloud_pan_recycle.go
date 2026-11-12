package app

// 交大云盘删除与回收站（coFile）：删除默认移入回收站，回收站支持恢复与永久删除。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type CloudRecycled struct {
	ID             int64  `json:"id"`
	Name           string `json:"name"`
	Path           string `json:"path"`
	SpaceID        string `json:"spaceId"`
	Size           int64  `json:"size"`
	RemovalTime    string `json:"removalTime"`
	RemainingDays  int    `json:"remainingDays"`
	Directory      bool   `json:"directory"`
	CanRestore     bool   `json:"canRestore"`
	CanDelete      bool   `json:"canDelete"`
	RestoreExpired bool   `json:"restoreExpired"`
}

type CloudRecycledRef struct {
	SpaceID string `json:"spaceId"`
	ID      int64  `json:"id"`
}

func (p *CloudPan) Delete(ctx context.Context, filePath, spaceID string, directory, permanent bool) error {
	clean := strings.Trim(strings.TrimSpace(filePath), "/")
	if clean == "" {
		return errors.New("不能删除云盘根目录")
	}
	token, err := p.accessToken(ctx, spaceID)
	if err != nil {
		return err
	}
	kind := "file"
	if directory {
		kind = "directory"
	}
	flag := "0"
	if permanent {
		flag = "1"
	}
	values := url.Values{}
	values.Set("access_token", token.Token)
	values.Set("permanent", flag)
	endpoint := fmt.Sprintf("%s/api/v1/%s/%s/%s/%s?%s", cloudPanOrigin, kind,
		url.PathEscape(token.LibraryID), url.PathEscape(token.SpaceID), escapePanPath(clean), values.Encode())
	return p.do(ctx, http.MethodDelete, endpoint, "", nil, nil)
}

func (p *CloudPan) Recycled(ctx context.Context) ([]CloudRecycled, error) {
	p.mu.Lock()
	session := p.session
	p.mu.Unlock()
	if session.UserToken == "" {
		return nil, ErrCloudPanAuth
	}
	token, err := p.accessToken(ctx, session.SpaceID)
	if err != nil {
		return nil, err
	}
	orgID := p.organizationID(ctx, session.UserToken)
	values := url.Values{}
	values.Set("user_token", session.UserToken)
	values.Set("limit", "50")
	values.Set("order_by", "delete_time")
	values.Set("order_by_type", "desc")
	body, err := json.Marshal(map[string]any{
		"spaceItems": []map[string]any{{
			"spaceId":    token.SpaceID,
			"spaceOrgId": orgID,
		}},
		"withAllTeamSpace":  false,
		"withAllGroupSpace": false,
	})
	if err != nil {
		return nil, err
	}
	endpoint := fmt.Sprintf("%s/user/v1/recycled/%d/list?%s", cloudPanOrigin, orgID, values.Encode())
	var payload struct {
		Contents []struct {
			RecycledItemID int64    `json:"recycledItemId"`
			Name           string   `json:"name"`
			OriginalPath   []string `json:"originalPath"`
			Size           panInt64 `json:"size"`
			SpaceID        string   `json:"spaceId"`
			Type           string   `json:"type"`
			RemovalTime    string   `json:"removalTime"`
			RemainingTime  panInt64 `json:"remainingTime"`
			IsOverTime     bool     `json:"isOverRestoreTime"`
			AuthorityList  struct {
				CanRestore bool `json:"canRestore"`
				CanDelete  bool `json:"canDelete"`
			} `json:"authorityList"`
		} `json:"contents"`
	}
	if err := p.do(ctx, http.MethodPost, endpoint, string(body),
		map[string]string{"Content-Type": "application/json"}, &payload); err != nil {
		return nil, err
	}
	items := make([]CloudRecycled, 0, len(payload.Contents))
	for _, item := range payload.Contents {
		if item.RecycledItemID <= 0 {
			continue
		}
		name := strings.TrimSpace(item.Name)
		if name == "" && len(item.OriginalPath) > 0 {
			name = item.OriginalPath[len(item.OriginalPath)-1]
		}
		items = append(items, CloudRecycled{
			ID:             item.RecycledItemID,
			Name:           name,
			Path:           strings.Join(item.OriginalPath, "/"),
			SpaceID:        item.SpaceID,
			Size:           int64(item.Size),
			RemovalTime:    item.RemovalTime,
			RemainingDays:  int(item.RemainingTime),
			Directory:      strings.EqualFold(item.Type, "dir"),
			CanRestore:     item.AuthorityList.CanRestore,
			CanDelete:      item.AuthorityList.CanDelete,
			RestoreExpired: item.IsOverTime,
		})
	}
	return items, nil
}

func (p *CloudPan) RestoreRecycled(ctx context.Context, items []CloudRecycledRef) error {
	return p.recycledAction(ctx, "restore", items)
}

func (p *CloudPan) DeleteRecycled(ctx context.Context, items []CloudRecycledRef) error {
	return p.recycledAction(ctx, "delete", items)
}

func (p *CloudPan) recycledAction(ctx context.Context, action string, items []CloudRecycledRef) error {
	if len(items) == 0 {
		return errors.New("请选择回收站项目")
	}
	p.mu.Lock()
	session := p.session
	p.mu.Unlock()
	if session.UserToken == "" {
		return ErrCloudPanAuth
	}
	orgID := p.organizationID(ctx, session.UserToken)
	refs := make([]map[string]any, 0, len(items))
	for _, item := range items {
		refs = append(refs, map[string]any{
			"spaceId":        item.SpaceID,
			"recycledItemId": item.ID,
		})
	}
	body, err := json.Marshal(map[string]any{"recycledItems": refs})
	if err != nil {
		return err
	}
	values := url.Values{}
	values.Set("user_token", session.UserToken)
	query := "?" + action
	if encoded := values.Encode(); encoded != "" {
		query += "&" + encoded
	}
	endpoint := fmt.Sprintf("%s/user/v1/recycled/%d%s", cloudPanOrigin, orgID, query)
	return p.do(ctx, http.MethodPost, endpoint, string(body),
		map[string]string{"Content-Type": "application/json"}, nil)
}

func (p *CloudPan) registerRecycleRoutes(router *Router) {
	router.Handle("cloudPan.delete", func(args []json.RawMessage) (any, error) {
		filePath, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		spaceID, _, _ := DecodeOptionalArg[string](args, 1)
		directory, _, _ := DecodeOptionalArg[bool](args, 2)
		permanent, _, _ := DecodeOptionalArg[bool](args, 3)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return map[string]any{"ok": true}, p.Delete(ctx, filePath, spaceID, directory, permanent)
	})
	router.Handle("cloudPan.recycled", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return p.Recycled(ctx)
	})
	router.Handle("cloudPan.recycled.restore", func(args []json.RawMessage) (any, error) {
		items, err := DecodeArg[[]CloudRecycledRef](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return map[string]any{"ok": true}, p.RestoreRecycled(ctx, items)
	})
	router.Handle("cloudPan.recycled.delete", func(args []json.RawMessage) (any, error) {
		items, err := DecodeArg[[]CloudRecycledRef](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return map[string]any{"ok": true}, p.DeleteRecycled(ctx, items)
	})
}
