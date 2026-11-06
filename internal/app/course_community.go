package app

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	courseAPIBase = "https://course.sjtu.plus/api"
	courseOrigin  = "https://course.sjtu.plus/"
	courseSecret  = "course-session"
)

var errCourseAuth = errors.New("选课社区登录已失效，请重新登录")

type CourseUser struct {
	ID       int64  `json:"id"`
	Username string `json:"username"`
	Email    string `json:"email"`
	Role     string `json:"role"`
}

type courseState struct {
	LoggedIn bool        `json:"loggedIn"`
	User     *CourseUser `json:"user"`
}

type courseCookie struct {
	Name    string    `json:"name"`
	Value   string    `json:"value"`
	Path    string    `json:"path"`
	Domain  string    `json:"domain"`
	Expires time.Time `json:"expires"`
	Secure  bool      `json:"secure"`
}

type courseSession struct {
	Cookies []courseCookie `json:"cookies"`
	User    *CourseUser    `json:"user"`
}

type CourseRating struct {
	Count        int     `json:"count"`
	Avg          float64 `json:"avg"`
	Score        float64 `json:"score"`
	Distribution []int   `json:"distribution"`
}

type CourseTeacher struct {
	ID         int64  `json:"id"`
	Code       string `json:"code"`
	Name       string `json:"name"`
	Department string `json:"department"`
	Title      string `json:"title"`
}

type CourseSummary struct {
	ID           int64          `json:"id"`
	Code         string         `json:"code"`
	Name         string         `json:"name"`
	Credit       float64        `json:"credit"`
	Department   string         `json:"department"`
	Language     string         `json:"language"`
	TargetYears  []string       `json:"target_years"`
	Categories   []string       `json:"categories"`
	LastSemester string         `json:"last_semester"`
	MainTeacher  *CourseTeacher `json:"main_teacher"`
	Rating       CourseRating   `json:"rating"`
}

type CourseOffering struct {
	Semester    string   `json:"semester"`
	Language    string   `json:"language"`
	TargetYears []string `json:"target_years"`
	Categories  []string `json:"categories"`
}

type CourseDetail struct {
	CourseSummary
	ModeratorRemark    string           `json:"moderator_remark"`
	OfferedCourses     []CourseOffering `json:"offered_courses"`
	SameCodeCourses    []CourseSummary  `json:"same_code_courses"`
	SameTeacherCourses []CourseSummary  `json:"same_teacher_courses"`
	NotificationLevel  int              `json:"notification_level"`
}

type CourseHotItem struct {
	Course CourseSummary `json:"course"`
	Score  float64       `json:"score"`
}

type CourseHot struct {
	Period    string          `json:"period"`
	PeriodKey string          `json:"period_key"`
	Items     []CourseHotItem `json:"items"`
}

type CourseFilterOption struct {
	Name  string `json:"name"`
	Count int    `json:"count"`
}

type CourseFilters struct {
	Credits     []CourseFilterOption `json:"credits"`
	Departments []CourseFilterOption `json:"departments"`
	Categories  []CourseFilterOption `json:"categories"`
	TargetYears []CourseFilterOption `json:"target_years"`
	Languages   []CourseFilterOption `json:"languages"`
	Semesters   []CourseFilterOption `json:"semesters"`
}

type CourseList struct {
	Items    []CourseSummary `json:"items"`
	Total    int             `json:"total"`
	Page     int             `json:"page"`
	PageSize int             `json:"page_size"`
}

type CourseEnrollment struct {
	ID       int64         `json:"id"`
	Semester string        `json:"semester"`
	Course   CourseSummary `json:"course"`
}

type CourseReview struct {
	ID              int64          `json:"id"`
	CourseID        int64          `json:"course_id"`
	Course          *CourseSummary `json:"course"`
	Semester        string         `json:"semester"`
	Score           string         `json:"score"`
	Rating          int            `json:"rating"`
	Content         string         `json:"content"`
	ModeratorRemark string         `json:"moderator_remark"`
	Vote            map[string]any `json:"vote"`
	CreatedAt       string         `json:"created_at"`
	UpdatedAt       string         `json:"updated_at"`
}

type CourseReviewList struct {
	Items    []CourseReview `json:"items"`
	Total    int            `json:"total"`
	Page     int            `json:"page"`
	PageSize int            `json:"page_size"`
}

type CourseReviewFilter struct {
	Semesters []CourseFilterOption `json:"semesters"`
	Ratings   []CourseFilterOption `json:"ratings"`
}

// CourseCommunity is a small authenticated client for course.sjtu.plus.
// Only session cookies are persisted; the community password never is.
type CourseCommunity struct {
	secrets *SecretStore

	mu     sync.Mutex
	jar    *cookiejar.Jar
	client *http.Client
	user   *CourseUser
}

func NewCourseCommunity(secrets *SecretStore) *CourseCommunity {
	service := &CourseCommunity{secrets: secrets}
	service.resetClient()
	var saved courseSession
	if ok, _ := secrets.Load(courseSecret, &saved); ok {
		base, _ := url.Parse(courseOrigin)
		for _, item := range saved.Cookies {
			service.jar.SetCookies(base, []*http.Cookie{{
				Name: item.Name, Value: item.Value, Path: item.Path, Domain: item.Domain,
				Expires: item.Expires, Secure: item.Secure, HttpOnly: true,
			}})
		}
		service.user = saved.User
	}
	return service
}

func (c *CourseCommunity) resetClient() {
	jar, _ := cookiejar.New(nil)
	c.jar = jar
	c.client = &http.Client{
		Jar:     jar,
		Timeout: 25 * time.Second,
		CheckRedirect: func(request *http.Request, via []*http.Request) error {
			if len(via) >= 10 {
				return errors.New("too many redirects")
			}
			return nil
		},
	}
	c.user = nil
}

func (c *CourseCommunity) State() courseState {
	c.mu.Lock()
	defer c.mu.Unlock()
	return courseState{LoggedIn: c.user != nil, User: c.user}
}

func (c *CourseCommunity) clearLocked() {
	c.resetClient()
	_ = c.secrets.Delete(courseSecret)
}

func (c *CourseCommunity) saveLocked() {
	if c.user == nil {
		return
	}
	base, _ := url.Parse(courseOrigin)
	cookies := c.jar.Cookies(base)
	saved := courseSession{User: c.user, Cookies: make([]courseCookie, 0, len(cookies))}
	for _, cookie := range cookies {
		saved.Cookies = append(saved.Cookies, courseCookie{
			Name: cookie.Name, Value: cookie.Value, Path: cookie.Path, Domain: cookie.Domain,
			Expires: cookie.Expires, Secure: cookie.Secure,
		})
	}
	_ = c.secrets.Save(courseSecret, saved)
}

func (c *CourseCommunity) Login(ctx context.Context, email, password string) (CourseUser, error) {
	email = strings.TrimSpace(email)
	if email == "" || password == "" {
		return CourseUser{}, errors.New("请填写邮箱和选课社区密码")
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.resetClient()

	csrfRequest, _ := http.NewRequestWithContext(ctx, http.MethodGet, courseAPIBase+"/auth/csrf", nil)
	csrfRequest.Header.Set("User-Agent", portalUserAgent)
	csrfRequest.Header.Set("Accept", "application/json")
	response, err := c.client.Do(csrfRequest)
	if err != nil {
		return CourseUser{}, fmt.Errorf("连接选课社区失败：%w", err)
	}
	_ = response.Body.Close()
	token := response.Header.Get("X-CSRF-Token")
	if response.StatusCode != http.StatusNoContent && response.StatusCode != http.StatusOK {
		return CourseUser{}, fmt.Errorf("获取登录校验失败（HTTP %d）", response.StatusCode)
	}
	if token == "" {
		return CourseUser{}, errors.New("选课社区没有返回登录校验")
	}

	payload, _ := json.Marshal(map[string]string{"email": email, "password": password})
	loginRequest, _ := http.NewRequestWithContext(ctx, http.MethodPost, courseAPIBase+"/auth/login", bytes.NewReader(payload))
	loginRequest.Header.Set("User-Agent", portalUserAgent)
	loginRequest.Header.Set("Accept", "application/json")
	loginRequest.Header.Set("Content-Type", "application/json")
	loginRequest.Header.Set("Origin", strings.TrimSuffix(courseOrigin, "/"))
	loginRequest.Header.Set("Referer", courseOrigin+"login")
	loginRequest.Header.Set("X-CSRF-Token", token)
	response, err = c.client.Do(loginRequest)
	if err != nil {
		return CourseUser{}, fmt.Errorf("登录选课社区失败：%w", err)
	}
	body, _ := io.ReadAll(io.LimitReader(response.Body, 1<<20))
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return CourseUser{}, errors.New(courseErrorMessage(body, "邮箱或密码不正确"))
	}
	var user CourseUser
	if err := json.Unmarshal(body, &user); err != nil || user.ID == 0 {
		return CourseUser{}, errors.New("选课社区返回了无法识别的登录结果")
	}
	c.user = &user
	c.saveLocked()
	return user, nil
}

func (c *CourseCommunity) Logout(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	request, _ := http.NewRequestWithContext(ctx, http.MethodPost, courseAPIBase+"/auth/logout", nil)
	request.Header.Set("User-Agent", portalUserAgent)
	request.Header.Set("Origin", strings.TrimSuffix(courseOrigin, "/"))
	request.Header.Set("Referer", courseOrigin)
	if _, err := c.client.Do(request); err == nil {
		// best effort
	}
	c.clearLocked()
	return nil
}

func (c *CourseCommunity) do(ctx context.Context, method, path string, body any) ([]byte, error) {
	c.mu.Lock()
	client := c.client
	user := c.user
	jar := c.jar
	c.mu.Unlock()
	if user == nil {
		return nil, errCourseAuth
	}
	var reader io.Reader
	if body != nil {
		data, _ := json.Marshal(body)
		reader = bytes.NewReader(data)
	}
	request, err := http.NewRequestWithContext(ctx, method, courseAPIBase+path, reader)
	if err != nil {
		return nil, err
	}
	request.Header.Set("User-Agent", portalUserAgent)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("Origin", strings.TrimSuffix(courseOrigin, "/"))
	request.Header.Set("Referer", courseOrigin)
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("连接选课社区失败：%w", err)
	}
	data, _ := io.ReadAll(io.LimitReader(response.Body, 8<<20))
	_ = response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		c.mu.Lock()
		c.clearLocked()
		c.mu.Unlock()
		return nil, errCourseAuth
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, errors.New(courseErrorMessage(data, fmt.Sprintf("选课社区返回 HTTP %d", response.StatusCode)))
	}
	c.mu.Lock()
	c.jar = jar
	c.saveLocked()
	c.mu.Unlock()
	return data, nil
}

func courseErrorMessage(body []byte, fallback string) string {
	var payload struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(body, &payload) == nil && strings.TrimSpace(payload.Error) != "" {
		return payload.Error
	}
	return fallback
}

func (c *CourseCommunity) Hot(ctx context.Context, period string, limit int) (CourseHot, error) {
	if period == "" {
		period = "week"
	}
	if limit <= 0 || limit > 20 {
		limit = 6
	}
	values := url.Values{}
	values.Set("period", period)
	values.Set("limit", strconv.Itoa(limit))
	data, err := c.do(ctx, http.MethodGet, "/course/hot?"+values.Encode(), nil)
	if err != nil {
		return CourseHot{}, err
	}
	var result CourseHot
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) Filters(ctx context.Context) (CourseFilters, error) {
	data, err := c.do(ctx, http.MethodGet, "/course/filter", nil)
	if err != nil {
		return CourseFilters{}, err
	}
	var result CourseFilters
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) List(ctx context.Context, query string, page, pageSize int, department, semester, category string) (CourseList, error) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 || pageSize > 50 {
		pageSize = 20
	}
	values := url.Values{}
	values.Set("page", strconv.Itoa(page))
	values.Set("page_size", strconv.Itoa(pageSize))
	if strings.TrimSpace(query) != "" {
		values.Set("q", strings.TrimSpace(query))
	}
	if department != "" {
		values.Set("department", department)
	}
	if semester != "" {
		values.Set("semester", semester)
	}
	if category != "" {
		values.Set("category", category)
	}
	data, err := c.do(ctx, http.MethodGet, "/course/?"+values.Encode(), nil)
	if err != nil {
		return CourseList{}, err
	}
	var result CourseList
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) Detail(ctx context.Context, id int64) (CourseDetail, error) {
	data, err := c.do(ctx, http.MethodGet, "/course/"+strconv.FormatInt(id, 10), nil)
	if err != nil {
		return CourseDetail{}, err
	}
	var result CourseDetail
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) Reviews(ctx context.Context, id int64, page, pageSize int, semester, rating string) (CourseReviewList, error) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 || pageSize > 50 {
		pageSize = 10
	}
	values := url.Values{}
	values.Set("page", strconv.Itoa(page))
	values.Set("page_size", strconv.Itoa(pageSize))
	if semester != "" {
		values.Set("semester", semester)
	}
	if rating != "" {
		values.Set("rating", rating)
	}
	data, err := c.do(ctx, http.MethodGet, fmt.Sprintf("/course/%d/review?%s", id, values.Encode()), nil)
	if err != nil {
		return CourseReviewList{}, err
	}
	var result CourseReviewList
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) RecentReviews(ctx context.Context, page, pageSize int) (CourseReviewList, error) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 || pageSize > 50 {
		pageSize = 20
	}
	values := url.Values{}
	values.Set("page", strconv.Itoa(page))
	values.Set("page_size", strconv.Itoa(pageSize))
	data, err := c.do(ctx, http.MethodGet, "/review?"+values.Encode(), nil)
	if err != nil {
		return CourseReviewList{}, err
	}
	var result CourseReviewList
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) Followed(ctx context.Context, page, pageSize int) (CourseList, error) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 || pageSize > 50 {
		pageSize = 10
	}
	values := url.Values{}
	values.Set("page", strconv.Itoa(page))
	values.Set("page_size", strconv.Itoa(pageSize))
	data, err := c.do(ctx, http.MethodGet, "/course/followed?"+values.Encode(), nil)
	if err != nil {
		return CourseList{}, err
	}
	var result CourseList
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) Ignored(ctx context.Context, page, pageSize int) (CourseList, error) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 || pageSize > 50 {
		pageSize = 10
	}
	values := url.Values{}
	values.Set("page", strconv.Itoa(page))
	values.Set("page_size", strconv.Itoa(pageSize))
	data, err := c.do(ctx, http.MethodGet, "/course/ignored?"+values.Encode(), nil)
	if err != nil {
		return CourseList{}, err
	}
	var result CourseList
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) Enrolled(ctx context.Context) ([]CourseEnrollment, error) {
	data, err := c.do(ctx, http.MethodGet, "/course/enrolled", nil)
	if err != nil {
		return nil, err
	}
	var items []CourseEnrollment
	if json.Unmarshal(data, &items) == nil {
		return items, nil
	}
	var page CourseList
	if err := json.Unmarshal(data, &page); err == nil && page.Items != nil {
		items = make([]CourseEnrollment, 0, len(page.Items))
		for _, course := range page.Items {
			items = append(items, CourseEnrollment{Course: course, Semester: course.LastSemester})
		}
		return items, nil
	}
	var wrapped struct {
		Items []CourseEnrollment `json:"items"`
	}
	if err := json.Unmarshal(data, &wrapped); err != nil {
		return nil, err
	}
	return wrapped.Items, nil
}

func (c *CourseCommunity) ReviewFilters(ctx context.Context, id int64) (CourseReviewFilter, error) {
	data, err := c.do(ctx, http.MethodGet, fmt.Sprintf("/course/%d/review/filter", id), nil)
	if err != nil {
		return CourseReviewFilter{}, err
	}
	var result CourseReviewFilter
	err = json.Unmarshal(data, &result)
	return result, err
}

func (c *CourseCommunity) Register(router *Router) {
	router.Handle("course.state", func([]json.RawMessage) (any, error) {
		return c.State(), nil
	})
	router.Handle("course.login", func(args []json.RawMessage) (any, error) {
		email, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		password, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Login(ctx, email, password)
	})
	router.Handle("course.logout", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		return true, c.Logout(ctx)
	})
	router.Handle("course.hot", func(args []json.RawMessage) (any, error) {
		period, _, _ := DecodeOptionalArg[string](args, 0)
		limit, _, _ := DecodeOptionalArg[int](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Hot(ctx, period, limit)
	})
	router.Handle("course.filters", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Filters(ctx)
	})
	router.Handle("course.list", func(args []json.RawMessage) (any, error) {
		query, _, _ := DecodeOptionalArg[string](args, 0)
		page, _, _ := DecodeOptionalArg[int](args, 1)
		pageSize, _, _ := DecodeOptionalArg[int](args, 2)
		department, _, _ := DecodeOptionalArg[string](args, 3)
		semester, _, _ := DecodeOptionalArg[string](args, 4)
		category, _, _ := DecodeOptionalArg[string](args, 5)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.List(ctx, query, page, pageSize, department, semester, category)
	})
	router.Handle("course.detail", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Detail(ctx, id)
	})
	router.Handle("course.reviews", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		page, _, _ := DecodeOptionalArg[int](args, 1)
		pageSize, _, _ := DecodeOptionalArg[int](args, 2)
		semester, _, _ := DecodeOptionalArg[string](args, 3)
		rating, _, _ := DecodeOptionalArg[string](args, 4)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Reviews(ctx, id, page, pageSize, semester, rating)
	})
	router.Handle("course.recentReviews", func(args []json.RawMessage) (any, error) {
		page, _, _ := DecodeOptionalArg[int](args, 0)
		pageSize, _, _ := DecodeOptionalArg[int](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.RecentReviews(ctx, page, pageSize)
	})
	router.Handle("course.followed", func(args []json.RawMessage) (any, error) {
		page, _, _ := DecodeOptionalArg[int](args, 0)
		pageSize, _, _ := DecodeOptionalArg[int](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Followed(ctx, page, pageSize)
	})
	router.Handle("course.ignored", func(args []json.RawMessage) (any, error) {
		page, _, _ := DecodeOptionalArg[int](args, 0)
		pageSize, _, _ := DecodeOptionalArg[int](args, 1)
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Ignored(ctx, page, pageSize)
	})
	router.Handle("course.enrolled", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.Enrolled(ctx)
	})
	router.Handle("course.reviewFilters", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		return c.ReviewFilters(ctx, id)
	})
}
