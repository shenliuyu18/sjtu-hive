package app

// 交我办办事大厅（https://my.sjtu.edu.cn/ui/app/）。
// 目录、收藏、最近使用与流程列表都走 /api/task/me 系列接口，
// 与日历、站内消息共用同一份 jAccount 会话。

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	portalServicesOrigin = "https://my.sjtu.edu.cn"
	portalServicesUA     = portalUserAgent
	portalIconEndpoint   = "https://jwb.sjtu.edu.cn/jwbMaintenance/unlimited/icon/list"
	portalCatalogTTL     = 30 * time.Minute
)

var ErrPortalServicesAuth = errors.New("交我办会话已失效，请重新登录教学网")

type ServiceApp struct {
	ID              string  `json:"id"`
	Code            string  `json:"code"`
	Name            string  `json:"name"`
	NameEn          string  `json:"nameEn"`
	Department      string  `json:"department"`
	URI             string  `json:"uri"`
	CategoryName    string  `json:"categoryName"`
	TopCategoryName string  `json:"topCategoryName"`
	IconID          int     `json:"iconId"`
	Ready           bool    `json:"ready"`
	Rating          float64 `json:"rating"`
	Rated           float64 `json:"rated"`
	Favorite        bool    `json:"favorite"`
}

type ServiceCategory struct {
	ID         int    `json:"id"`
	Title      string `json:"title"`
	TitleEn    string `json:"titleEn"`
	ParentID   int    `json:"parentId"`
	ParentName string `json:"parentName"`
	Type       string `json:"type"`
	Count      int    `json:"count"`
}

type ServiceRecent struct {
	ItemID          string `json:"itemId"`
	ItemName        string `json:"itemName"`
	URL             string `json:"url"`
	UpdateTimestamp string `json:"updateTimestamp"`
}

type ServiceCatalog struct {
	Apps       []ServiceApp      `json:"apps"`
	Categories []ServiceCategory `json:"categories"`
	Favorites  []string          `json:"favorites"`
	Recent     []ServiceRecent   `json:"recent"`
	FetchedAt  string            `json:"fetchedAt"`
}

type ServiceProcess struct {
	ID        string  `json:"id"`
	Name      string  `json:"name"`
	AppName   string  `json:"appName"`
	Status    string  `json:"status"`
	Entry     string  `json:"entry"`
	StepName  string  `json:"stepName"`
	URI       string  `json:"uri"`
	Percent   float64 `json:"percent"`
	Read      bool    `json:"read"`
	UpdateAt  string  `json:"updateAt"`
	Category  string  `json:"category"`
	Applicant string  `json:"applicant"`
}

type ServiceProcessPage struct {
	Items []ServiceProcess `json:"items"`
	Total int              `json:"total"`
}

type PortalServices struct {
	campus *CampusSession
	paths  Paths
	logger *slog.Logger
	emit   func(string, any)

	mu        sync.Mutex
	catalog   ServiceCatalog
	cachedAt  time.Time
	iconCache map[int]string
}

func NewPortalServices(campus *CampusSession, paths Paths, logger *slog.Logger, emit func(string, any)) *PortalServices {
	return &PortalServices{
		campus:    campus,
		paths:     paths,
		logger:    logger,
		emit:      emit,
		iconCache: make(map[int]string),
	}
}

func (s *PortalServices) client() *http.Client {
	client := *s.campus.RedirectClient()
	client.Timeout = 30 * time.Second
	return &client
}

type portalEnvelope struct {
	Entities []json.RawMessage `json:"entities"`
	Total    int               `json:"total"`
	Errno    json.RawMessage   `json:"errno"`
	Error    string            `json:"error"`
}

func (s *PortalServices) get(ctx context.Context, client *http.Client, target string, out any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, portalServicesOrigin+target, nil)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("User-Agent", portalServicesUA)
	request.Header.Set("Referer", portalServicesOrigin+"/ui/app/")
	request.Header.Set("X-Requested-With", "XMLHttpRequest")
	response, err := client.Do(request)
	if err != nil {
		return errors.New("无法连接交我办，请检查网络后重试")
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized {
		return ErrPortalServicesAuth
	}
	if response.StatusCode >= 400 {
		return fmt.Errorf("交我办返回 HTTP %d", response.StatusCode)
	}
	if err := json.NewDecoder(response.Body).Decode(out); err != nil {
		return fmt.Errorf("交我办响应格式无法识别：%w", err)
	}
	return nil
}

func (s *PortalServices) fetchEnvelope(ctx context.Context, client *http.Client, target string) ([]json.RawMessage, int, error) {
	var envelope portalEnvelope
	if err := s.get(ctx, client, target, &envelope); err != nil {
		return nil, 0, err
	}
	if errno := rawNumber(envelope.Errno); errno != "" && errno != "0" {
		message := firstNonEmpty(envelope.Error, "交我办返回错误 "+errno)
		if errno == "401" {
			return nil, 0, ErrPortalServicesAuth
		}
		return nil, 0, errors.New(message)
	}
	return envelope.Entities, envelope.Total, nil
}

func rawString(value json.RawMessage) string {
	if len(value) == 0 {
		return ""
	}
	var text string
	if err := json.Unmarshal(value, &text); err == nil {
		return text
	}
	return strings.TrimSpace(string(value))
}

func rawNumber(value json.RawMessage) string {
	if len(value) == 0 {
		return ""
	}
	var number json.Number
	if err := json.Unmarshal(value, &number); err == nil {
		return number.String()
	}
	var text string
	if err := json.Unmarshal(value, &text); err == nil {
		return strings.TrimSpace(text)
	}
	return ""
}

func rawBool(value json.RawMessage) bool {
	if len(value) == 0 {
		return false
	}
	var flag bool
	if err := json.Unmarshal(value, &flag); err == nil {
		return flag
	}
	return rawString(value) == "true"
}

func rawFloat(value json.RawMessage) float64 {
	text := rawNumber(value)
	if text == "" {
		return 0
	}
	number, _ := strconv.ParseFloat(text, 64)
	return number
}

func parseServiceApp(raw json.RawMessage) (ServiceApp, bool) {
	var item struct {
		ID           json.RawMessage   `json:"id"`
		Code         string            `json:"code"`
		Name         string            `json:"name"`
		NameEn       string            `json:"nameEn"`
		Department   string            `json:"department"`
		URI          string            `json:"uri"`
		Tags         string            `json:"tags"`
		Rating       json.RawMessage   `json:"rating"`
		Rated        json.RawMessage   `json:"rated"`
		Ready        json.RawMessage   `json:"ready"`
		CategoryIcon []json.RawMessage `json:"categoryIcon"`
	}
	if err := json.Unmarshal(raw, &item); err != nil {
		return ServiceApp{}, false
	}
	id := rawString(item.ID)
	if id == "" || strings.TrimSpace(item.Name) == "" {
		return ServiceApp{}, false
	}
	app := ServiceApp{
		ID:         id,
		Code:       item.Code,
		Name:       strings.TrimSpace(item.Name),
		NameEn:     item.NameEn,
		Department: item.Department,
		URI:        item.URI,
		Ready:      len(item.Ready) == 0 || rawBool(item.Ready),
		Rating:     rawFloat(item.Rating),
		Rated:      rawFloat(item.Rated),
	}
	if match := categoryNamePattern.FindStringSubmatch(item.Tags); len(match) == 2 {
		app.CategoryName = strings.TrimSpace(match[1])
	}
	app.TopCategoryName, app.IconID = topCategory(item.CategoryIcon)
	return app, true
}

var categoryNamePattern = regexp.MustCompile(`#CategoryName:([^,#]+)`)

func topCategory(raw []json.RawMessage) (string, int) {
	for _, entry := range raw {
		var fields map[string]json.RawMessage
		if err := json.Unmarshal(entry, &fields); err != nil {
			continue
		}
		for key, value := range fields {
			if key == "应用分类" || key == "sort" {
				continue
			}
			if id, err := strconv.Atoi(rawNumber(value)); err == nil && id > 0 {
				return key, id
			}
		}
	}
	return "", 0
}

func parseServiceCategory(raw json.RawMessage) (ServiceCategory, bool) {
	var item struct {
		ID         json.RawMessage `json:"id"`
		Title      string          `json:"title"`
		TitleEn    string          `json:"titleEn"`
		ParentID   json.RawMessage `json:"parentId"`
		ParentName string          `json:"parentName"`
		Type       string          `json:"type"`
	}
	if err := json.Unmarshal(raw, &item); err != nil {
		return ServiceCategory{}, false
	}
	id, err := strconv.Atoi(rawNumber(item.ID))
	if err != nil || strings.TrimSpace(item.Title) == "" {
		return ServiceCategory{}, false
	}
	parent, _ := strconv.Atoi(rawNumber(item.ParentID))
	return ServiceCategory{
		ID: id, Title: strings.TrimSpace(item.Title), TitleEn: item.TitleEn,
		ParentID: parent, ParentName: item.ParentName, Type: item.Type,
	}, true
}

func parseServiceRecent(raw json.RawMessage) (ServiceRecent, bool) {
	var item struct {
		ItemID          json.RawMessage `json:"itemId"`
		ItemName        string          `json:"itemName"`
		URL             string          `json:"url"`
		UpdateTimestamp string          `json:"updateTimestamp"`
	}
	if err := json.Unmarshal(raw, &item); err != nil {
		return ServiceRecent{}, false
	}
	id := rawString(item.ItemID)
	if id == "" || strings.TrimSpace(item.ItemName) == "" {
		return ServiceRecent{}, false
	}
	return ServiceRecent{
		ItemID: id, ItemName: strings.TrimSpace(item.ItemName), URL: item.URL,
		UpdateTimestamp: item.UpdateTimestamp,
	}, true
}

func parseServiceProcess(raw json.RawMessage, kind string) (ServiceProcess, bool) {
	var item struct {
		ID         json.RawMessage `json:"id"`
		Name       string          `json:"name"`
		Status     json.RawMessage `json:"status"`
		URI        string          `json:"uri"`
		Read       json.RawMessage `json:"read"`
		Update     json.RawMessage `json:"update"`
		AssignTime json.RawMessage `json:"assignTime"`
		Applicant  string          `json:"applicant"`
		Category   string          `json:"category"`
		Process    struct {
			ID     json.RawMessage `json:"id"`
			Name   string          `json:"name"`
			Status json.RawMessage `json:"status"`
			Entry  json.RawMessage `json:"entry"`
			URI    string          `json:"uri"`
			Update json.RawMessage `json:"update"`
			App    struct {
				Name string `json:"name"`
			} `json:"app"`
			Milestone struct {
				Percent json.RawMessage `json:"percent"`
			} `json:"milestone"`
		} `json:"process"`
	}
	if err := json.Unmarshal(raw, &item); err != nil {
		return ServiceProcess{}, false
	}
	id := firstNonEmpty(rawString(item.ID), rawString(item.Process.ID))
	name := firstNonEmpty(strings.TrimSpace(item.Process.Name), strings.TrimSpace(item.Name))
	if id == "" || name == "" {
		return ServiceProcess{}, false
	}
	process := ServiceProcess{
		ID:     id,
		Name:   name,
		Status: firstNonEmpty(rawString(item.Process.Status), rawString(item.Status)),
		Entry:  rawString(item.Process.Entry),
		URI:    firstNonEmpty(item.URI, item.Process.URI),
		Read:   len(item.Read) == 0 || rawBool(item.Read),
	}
	if kind == "todo" {
		process.StepName = strings.TrimSpace(item.Name)
		if process.StepName == process.Name {
			process.StepName = ""
		}
		process.AppName = strings.TrimSpace(item.Process.App.Name)
		process.Percent = rawFloat(item.Process.Milestone.Percent)
	}
	if process.Entry == "" {
		process.Entry = process.URI
	}
	stamp := rawNumber(item.AssignTime)
	if stamp == "" {
		stamp = rawNumber(item.Process.Update)
	}
	if stamp == "" {
		stamp = rawNumber(item.Update)
	}
	if stamp != "" {
		if seconds, err := strconv.ParseInt(stamp, 10, 64); err == nil && seconds > 0 {
			process.UpdateAt = time.Unix(seconds, 0).Format(time.RFC3339)
		}
	}
	process.Applicant = item.Applicant
	process.Category = item.Category
	return process, true
}

func (s *PortalServices) Catalog(force bool) (ServiceCatalog, error) {
	s.mu.Lock()
	if !force && !s.cachedAt.IsZero() && time.Since(s.cachedAt) < portalCatalogTTL && len(s.catalog.Apps) > 0 {
		cached := s.catalog
		s.mu.Unlock()
		return cached, nil
	}
	s.mu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	client := s.client()

	var (
		appRaw, categoryRaw, favoriteRaw, recentRaw  []json.RawMessage
		appsErr, categoryErr, favoriteErr, recentErr error
		wait                                         sync.WaitGroup
	)
	wait.Add(4)
	go func() {
		defer wait.Done()
		appRaw, _, appsErr = s.fetchEnvelope(ctx, client, "/api/task/me/apps")
	}()
	go func() {
		defer wait.Done()
		categoryRaw, _, categoryErr = s.fetchEnvelope(ctx, client, "/api/task/me/category")
	}()
	go func() {
		defer wait.Done()
		favoriteRaw, _, favoriteErr = s.fetchEnvelope(ctx, client, "/api/task/me/preference")
	}()
	go func() {
		defer wait.Done()
		recentRaw, _, recentErr = s.fetchEnvelope(ctx, client, "/api/task/me/recentlyApp?limit=12")
	}()
	wait.Wait()
	if appsErr != nil {
		return ServiceCatalog{}, appsErr
	}
	if categoryErr != nil {
		return ServiceCatalog{}, categoryErr
	}

	catalog := ServiceCatalog{Apps: []ServiceApp{}, Categories: []ServiceCategory{}, Favorites: []string{}, Recent: []ServiceRecent{}}
	for _, raw := range appRaw {
		if app, ok := parseServiceApp(raw); ok {
			catalog.Apps = append(catalog.Apps, app)
		}
	}
	if len(catalog.Apps) == 0 {
		return ServiceCatalog{}, errors.New("交我办没有返回任何办事应用，可能是登录态失效，请重新登录教学网")
	}
	for _, raw := range categoryRaw {
		if category, ok := parseServiceCategory(raw); ok {
			catalog.Categories = append(catalog.Categories, category)
		}
	}
	if favoriteErr == nil && len(favoriteRaw) > 0 {
		var entity struct {
			FavoriteApps []string `json:"FavoriteApps"`
		}
		if err := json.Unmarshal(favoriteRaw[0], &entity); err == nil {
			catalog.Favorites = append(catalog.Favorites, entity.FavoriteApps...)
		}
	}
	if recentErr == nil {
		for _, raw := range recentRaw {
			if recent, ok := parseServiceRecent(raw); ok {
				catalog.Recent = append(catalog.Recent, recent)
			}
		}
	}
	favorites := make(map[string]bool, len(catalog.Favorites))
	for _, id := range catalog.Favorites {
		favorites[id] = true
	}
	counts := make(map[string]int)
	for index := range catalog.Apps {
		catalog.Apps[index].Favorite = favorites[catalog.Apps[index].ID]
		if catalog.Apps[index].TopCategoryName != "" {
			counts[catalog.Apps[index].TopCategoryName]++
		}
	}
	for index := range catalog.Categories {
		catalog.Categories[index].Count = counts[catalog.Categories[index].Title]
	}
	sort.SliceStable(catalog.Categories, func(i, j int) bool {
		return catalog.Categories[i].Count > catalog.Categories[j].Count
	})
	catalog.FetchedAt = Now()

	s.mu.Lock()
	s.catalog = catalog
	s.cachedAt = time.Now()
	s.mu.Unlock()
	return catalog, nil
}

func (s *PortalServices) Processes(kind string, limit, start int) (ServiceProcessPage, error) {
	endpoint := ""
	switch kind {
	case "todo":
		endpoint = "/api/task/me/processes/todo?thing=false"
	case "completed":
		endpoint = "/api/task/me/processes/completed"
	case "cc":
		endpoint = "/api/task/me/processes/cc"
	default:
		return ServiceProcessPage{}, errors.New("未知的流程类型")
	}
	if kind != "todo" {
		if limit <= 0 || limit > 50 {
			limit = 20
		}
		if start < 0 {
			start = 0
		}
		endpoint = fmt.Sprintf("%s?limit=%d&start=%d&order=auto&keyword=", endpoint, limit, start)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	entities, total, err := s.fetchEnvelope(ctx, s.client(), endpoint)
	if err != nil {
		return ServiceProcessPage{}, err
	}
	page := ServiceProcessPage{Items: []ServiceProcess{}, Total: total}
	for _, raw := range entities {
		if process, ok := parseServiceProcess(raw, kind); ok {
			page.Items = append(page.Items, process)
		}
	}
	return page, nil
}

func (s *PortalServices) Favorite(appID string, favorite bool) error {
	appID = strings.TrimSpace(appID)
	if appID == "" {
		return errors.New("缺少应用 ID")
	}
	method := http.MethodDelete
	if favorite {
		method = http.MethodPut
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, method,
		portalServicesOrigin+"/api/task/me/preference/FavoriteApps/"+url.PathEscape(appID), nil)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("User-Agent", portalServicesUA)
	request.Header.Set("Referer", portalServicesOrigin+"/ui/app/")
	request.Header.Set("X-Requested-With", "XMLHttpRequest")
	response, err := s.client().Do(request)
	if err != nil {
		return errors.New("收藏失败：无法连接交我办")
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized {
		return ErrPortalServicesAuth
	}
	var envelope portalEnvelope
	_ = json.NewDecoder(response.Body).Decode(&envelope)
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("收藏失败：HTTP %d", response.StatusCode)
	}
	if errno := rawNumber(envelope.Errno); errno != "" && errno != "0" {
		return errors.New(firstNonEmpty(envelope.Error, "收藏失败"))
	}
	s.mu.Lock()
	s.cachedAt = time.Time{}
	if favorite {
		s.catalog.Favorites = append(s.catalog.Favorites, appID)
	} else {
		filtered := s.catalog.Favorites[:0]
		for _, id := range s.catalog.Favorites {
			if id != appID {
				filtered = append(filtered, id)
			}
		}
		s.catalog.Favorites = filtered
	}
	for index := range s.catalog.Apps {
		if s.catalog.Apps[index].ID == appID {
			s.catalog.Apps[index].Favorite = favorite
		}
	}
	s.mu.Unlock()
	s.emit("services.catalog.changed", s.catalog)
	return nil
}

func (s *PortalServices) TouchRecent(itemID, itemName, link string) error {
	values := url.Values{}
	values.Set("itemId", itemID)
	values.Set("itemName", itemName)
	values.Set("url", link)
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodPut,
		portalServicesOrigin+"/api/task/me/recentlyApp?"+values.Encode(), nil)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("User-Agent", portalServicesUA)
	request.Header.Set("Referer", portalServicesOrigin+"/ui/app/")
	request.Header.Set("X-Requested-With", "XMLHttpRequest")
	response, err := s.client().Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized {
		return ErrPortalServicesAuth
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("记录最近使用失败：HTTP %d", response.StatusCode)
	}
	return nil
}

// Icons loads application icons from the jwb icon service and returns data URLs
// the renderer can use directly without extra image requests.
func (s *PortalServices) Icons(ids []int) (map[int]string, error) {
	unique := make([]int, 0, len(ids))
	seen := make(map[int]bool, len(ids))
	for _, id := range ids {
		if id <= 0 || seen[id] {
			continue
		}
		seen[id] = true
		unique = append(unique, id)
	}
	result := make(map[int]string, len(unique))
	missing := make([]int, 0, len(unique))
	s.mu.Lock()
	for _, id := range unique {
		if data, ok := s.iconCache[id]; ok {
			result[id] = data
		} else {
			missing = append(missing, id)
		}
	}
	s.mu.Unlock()
	if len(missing) == 0 {
		return result, nil
	}

	payload, err := json.Marshal(map[string]any{"ids": missing})
	if err != nil {
		return result, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, portalIconEndpoint, strings.NewReader(string(payload)))
	if err != nil {
		return result, err
	}
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("Content-Type", "application/json;charset=UTF-8")
	request.Header.Set("User-Agent", portalServicesUA)
	request.Header.Set("Referer", portalServicesOrigin+"/ui/app/")
	response, err := s.client().Do(request)
	if err != nil {
		return result, nil
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return result, nil
	}
	var envelope struct {
		Entities []struct {
			ID   int    `json:"id"`
			Data string `json:"data"`
		} `json:"entities"`
	}
	if err := json.NewDecoder(response.Body).Decode(&envelope); err != nil {
		return result, nil
	}
	s.mu.Lock()
	for _, entity := range envelope.Entities {
		if entity.ID <= 0 || entity.Data == "" {
			continue
		}
		data := iconDataSource(entity.Data)
		if data == "" {
			continue
		}
		s.iconCache[entity.ID] = data
		result[entity.ID] = data
	}
	s.mu.Unlock()
	return result, nil
}

func iconDataSource(data string) string {
	trimmed := strings.TrimSpace(data)
	if trimmed == "" {
		return ""
	}
	head := strings.ToLower(trimmed)
	if len(head) > 100 {
		head = head[:100]
	}
	switch {
	case strings.HasPrefix(head, "<svg") || strings.HasPrefix(head, "<?xml") || strings.HasPrefix(head, "<"):
		return "data:image/svg+xml;base64," + base64.StdEncoding.EncodeToString([]byte(trimmed))
	case strings.HasPrefix(head, "https://") || strings.HasPrefix(head, "http://") || strings.HasPrefix(head, "data:image/"):
		return trimmed
	default:
		return "data:image/png;base64," + strings.Join(strings.Fields(trimmed), "")
	}
}

func (s *PortalServices) Register(router *Router) {
	router.Handle("services.catalog", func(args []json.RawMessage) (any, error) {
		force, _, _ := DecodeOptionalArg[bool](args, 0)
		return s.Catalog(force)
	})
	router.Handle("services.processes", func(args []json.RawMessage) (any, error) {
		kind := "todo"
		if value, ok, _ := DecodeOptionalArg[string](args, 0); ok && value != "" {
			kind = value
		}
		limit, _, _ := DecodeOptionalArg[int](args, 1)
		start, _, _ := DecodeOptionalArg[int](args, 2)
		return s.Processes(kind, limit, start)
	})
	router.Handle("services.favorite", func(args []json.RawMessage) (any, error) {
		appID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		favorite, err := DecodeArg[bool](args, 1)
		if err != nil {
			return nil, err
		}
		if err := s.Favorite(appID, favorite); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	})
	router.Handle("services.touch", func(args []json.RawMessage) (any, error) {
		itemID, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		name, _, _ := DecodeOptionalArg[string](args, 1)
		link, _, _ := DecodeOptionalArg[string](args, 2)
		if err := s.TouchRecent(itemID, name, link); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	})
	router.Handle("services.icons", func(args []json.RawMessage) (any, error) {
		ids, _, _ := DecodeOptionalArg[[]int](args, 0)
		return s.Icons(ids)
	})
	router.Handle("services.open", func(args []json.RawMessage) (any, error) {
		link, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		name, _, _ := DecodeOptionalArg[string](args, 1)
		if err := s.OpenForm(link, name); err != nil {
			return nil, err
		}
		return map[string]any{"ok": true}, nil
	})
}
