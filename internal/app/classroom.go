package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	videoHome    = "https://v.sjtu.edu.cn/jy-application-resourcemanage-ui/"
	videoBase    = "https://v.sjtu.edu.cn/jy-application-resourcemanage"
	videoAPIBase = videoBase + "/v1"
	videoME      = videoBase + "/authority/me"
	videoToken   = videoBase + "/oauth2/token"
	videoLogin   = "https://v.sjtu.edu.cn/jy-application-resourcemanage-ui/?type=cas"
)

const campusUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Safari/537.36 Edg/155.0.0.0"

var ErrClassroomAuth = errors.New("交大视频登录已失效，请重新登录")

type VideoCourse struct {
	ID   int64  `json:"id"`
	Code string `json:"code"`
	Name string `json:"name"`
}

type ClassroomVideo struct {
	ID     int64  `json:"id"`
	Start  string `json:"start"`
	End    string `json:"end"`
	Room   string `json:"room"`
	Period string `json:"period"`
}

type VideoStream struct {
	View        int     `json:"view"`
	Duration    float64 `json:"duration"`
	PlaybackURL string  `json:"playbackUrl"`
}

type TranscriptSegment struct {
	StartMS int64  `json:"startMs"`
	EndMS   int64  `json:"endMs"`
	Text    string `json:"text"`
}

type VideoTranscript struct {
	VideoID  int64               `json:"videoId"`
	Segments []TranscriptSegment `json:"segments"`
}

type VideoDownload struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Received int64  `json:"received"`
	Total    int64  `json:"total"`
	State    string `json:"state"`
}

type classroomStreamEntry struct {
	streams    []VideoStream
	resolvedAt time.Time
}

type classroomTranscriptEntry struct {
	transcript *VideoTranscript
	resolvedAt time.Time
}

type Classroom struct {
	db     *sql.DB
	campus *CampusSession
	media  *MediaServer
	emit   func(string, any)

	mu          sync.Mutex
	streams     map[int64]classroomStreamEntry
	transcripts map[int64]classroomTranscriptEntry

	tokenMu    sync.Mutex
	jwt        string
	refreshing bool

	downloadMu sync.Mutex
	downloads  map[string]*classroomDownloadTask
}

func NewClassroom(db *sql.DB, campus *CampusSession, media *MediaServer, emit func(string, any)) *Classroom {
	return &Classroom{
		db: db, campus: campus, media: media, emit: emit,
		streams: map[int64]classroomStreamEntry{}, transcripts: map[int64]classroomTranscriptEntry{},
		downloads: map[string]*classroomDownloadTask{},
	}
}

type videoEnvelope struct {
	OK     *bool           `json:"ok"`
	DataOK *bool           `json:"dataOk"`
	Status *int            `json:"status"`
	Code   json.RawMessage `json:"code"`
	Result json.RawMessage `json:"result"`
	Data   json.RawMessage `json:"data"`
}

func (c *Classroom) token() string {
	c.tokenMu.Lock()
	defer c.tokenMu.Unlock()
	return c.jwt
}

func (c *Classroom) setToken(token string) {
	c.tokenMu.Lock()
	c.jwt = token
	c.tokenMu.Unlock()
}

func (c *Classroom) headers(extra map[string]string) map[string]string {
	headers := make(map[string]string, len(extra)+1)
	for key, value := range extra {
		headers[key] = value
	}
	if token := c.token(); token != "" {
		headers["jwt-token"] = token
	}
	return headers
}

func (c *Classroom) requestOnce(ctx context.Context, path string) ([]byte, error) {
	headers := c.headers(map[string]string{
		"Accept":  "application/json",
		"Referer": videoHome,
		"Origin":  "https://v.sjtu.edu.cn",
	})
	data, err := c.campus.Request(ctx, http.MethodGet, videoAPIBase+path, headers, "")
	if errors.Is(err, ErrCampusAuth) {
		return nil, ErrClassroomAuth
	}
	return data, err
}

func (c *Classroom) request(ctx context.Context, path string) ([]byte, error) {
	data, err := c.requestOnce(ctx, path)
	if err == nil {
		return data, nil
	}
	if errors.Is(err, ErrCampusTransient) {
		time.Sleep(250 * time.Millisecond)
		if data, retryErr := c.requestOnce(ctx, path); retryErr == nil {
			return data, nil
		} else {
			err = retryErr
		}
	}
	if !errors.Is(err, ErrClassroomAuth) {
		return nil, err
	}
	// The site's own JWT expired: exchange a fresh one through jAccount SSO.
	if loginErr := c.loginVideo(ctx); loginErr != nil {
		return nil, ErrClassroomAuth
	}
	return c.requestOnce(ctx, path)
}

// loginVideo completes the site's CAS/OAuth exchange with the campus jAccount
// cookies and stores the JWT that the classroom API expects.
func (c *Classroom) loginVideo(ctx context.Context) error {
	c.tokenMu.Lock()
	if c.refreshing {
		c.tokenMu.Unlock()
		return ErrClassroomAuth
	}
	c.refreshing = true
	c.tokenMu.Unlock()
	defer func() {
		c.tokenMu.Lock()
		c.refreshing = false
		c.tokenMu.Unlock()
	}()
	if !c.campus.Connected() {
		return ErrClassroomAuth
	}
	detail := ""
	for attempt := 0; attempt < 2; attempt++ {
		if attempt > 0 {
			time.Sleep(400 * time.Millisecond)
		}
		blocked, note := c.bootstrapVideoSession(ctx)
		if blocked {
			detail = note
			continue
		}
		token, err := c.exchangeVideoToken(ctx)
		if err == nil {
			c.setToken(token)
			return nil
		}
		detail = note + " token: " + err.Error()
	}
	c.logVideoLogin("login failed", detail)
	return ErrClassroomAuth
}

// bootstrapVideoSession follows the site's authorize redirects so the server
// issues a session cookie. The first pass can trip a redirect loop before that
// cookie lands, so callers retry once instead of failing outright.
func (c *Classroom) bootstrapVideoSession(ctx context.Context) (bool, string) {
	authorize := videoBase + "/oauth2/authorize?json=0&returnUri=" + url.QueryEscape(videoLogin)
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, authorize, nil)
	if err != nil {
		return true, "authorize request: " + err.Error()
	}
	request.Header.Set("User-Agent", campusUserAgent)
	request.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
	var chain []string
	client := &http.Client{
		Jar: c.campus,
		CheckRedirect: func(next *http.Request, via []*http.Request) error {
			chain = append(chain, next.URL.Host+next.URL.Path)
			if len(via) >= 20 {
				return errors.New("redirect loop")
			}
			return nil
		},
	}
	response, err := client.Do(request)
	if err != nil {
		return false, err.Error() + " chain=" + strings.Join(chain, " -> ")
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 1<<20))
	finalURL := response.Request.URL
	_ = response.Body.Close()
	if strings.Contains(finalURL.Hostname(), "jaccount.sjtu.edu.cn") {
		return true, "jAccount page: " + finalURL.Host + finalURL.Path
	}
	return false, ""
}

func (c *Classroom) exchangeVideoToken(ctx context.Context) (string, error) {
	status, data, err := c.campus.RequestRaw(ctx, http.MethodGet, videoToken, map[string]string{
		"Accept":  "application/json",
		"Referer": videoHome,
	}, "")
	if err != nil {
		return "", err
	}
	if status != http.StatusOK {
		return "", fmt.Errorf("HTTP %d", status)
	}
	var payload struct {
		Code   json.RawMessage `json:"code"`
		JWT    string          `json:"jwt_token"`
		Result struct {
			JWT string `json:"jwt_token"`
		} `json:"result"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		return "", err
	}
	token := payload.JWT
	if token == "" {
		token = payload.Result.JWT
	}
	if token == "" {
		detail := strings.TrimSpace(string(data))
		if len(detail) > 200 {
			detail = detail[:200]
		}
		return "", fmt.Errorf("token missing: %s", detail)
	}
	return token, nil
}

func (c *Classroom) logVideoLogin(message, detail string) {
	if c.campus != nil && c.campus.logger != nil {
		c.campus.logger.Warn("classroom video login", "message", message, "detail", detail)
	}
}

func unwrapVideo(data []byte) (json.RawMessage, error) {
	trimmed := strings.TrimSpace(string(data))
	if trimmed == "" {
		return nil, errors.New("交大视频没有返回数据")
	}
	if strings.HasPrefix(trimmed, "[") {
		return json.RawMessage(data), nil
	}
	var envelope videoEnvelope
	if err := json.Unmarshal(data, &envelope); err != nil {
		return nil, errors.New("交大视频返回了无法识别的数据")
	}
	code := strings.Trim(string(envelope.Code), `"`)
	if code == "-1" || (envelope.Status != nil && (*envelope.Status == 401 || *envelope.Status == 403)) {
		return nil, ErrClassroomAuth
	}
	if (envelope.OK != nil && !*envelope.OK) || (envelope.DataOK != nil && !*envelope.DataOK) {
		return nil, errors.New("交大视频接口请求失败")
	}
	if envelope.Status != nil && *envelope.Status != 200 {
		return nil, fmt.Errorf("交大视频接口返回 HTTP %d", *envelope.Status)
	}
	if len(envelope.Result) > 0 && string(envelope.Result) != "null" {
		return envelope.Result, nil
	}
	if len(envelope.Data) > 0 && string(envelope.Data) != "null" {
		return envelope.Data, nil
	}
	return nil, nil
}

func (c *Classroom) checkLogin(ctx context.Context) error {
	if err := c.checkLoginOnce(ctx); err == nil {
		return nil
	}
	if err := c.loginVideo(ctx); err != nil {
		return err
	}
	return c.checkLoginOnce(ctx)
}

func (c *Classroom) checkLoginOnce(ctx context.Context) error {
	status, data, err := c.campus.RequestRaw(ctx, http.MethodGet, videoME, c.headers(map[string]string{
		"Accept":  "application/json",
		"Referer": videoHome,
	}), "")
	if err != nil || status != http.StatusOK {
		return ErrClassroomAuth
	}
	var envelope struct {
		Code   json.RawMessage `json:"code"`
		Result json.RawMessage `json:"result"`
	}
	if err := json.Unmarshal(data, &envelope); err != nil {
		return ErrClassroomAuth
	}
	if strings.Trim(string(envelope.Code), `"`) != "0" {
		return ErrClassroomAuth
	}
	var result struct {
		User any `json:"user"`
	}
	if err := json.Unmarshal(envelope.Result, &result); err != nil || result.User == nil {
		return ErrClassroomAuth
	}
	return nil
}

func (c *Classroom) Connect(ctx context.Context) map[string]any {
	if err := c.checkLogin(ctx); err == nil {
		return map[string]any{"ok": true}
	}
	if _, err := c.campus.Login(ctx); err != nil {
		return map[string]any{"ok": false, "error": err.Error()}
	}
	if err := c.checkLogin(ctx); err != nil {
		return map[string]any{"ok": false, "error": "登录成功，但交大视频仍未授权。请在打开的登录窗口中访问课堂视频后再试。"}
	}
	return map[string]any{"ok": true}
}

func (c *Classroom) allPages(ctx context.Context, path string, params map[string]string) ([]json.RawMessage, error) {
	result := []json.RawMessage{}
	for page := 1; page <= 100; page++ {
		query := url.Values{}
		for key, value := range params {
			query.Set(key, value)
		}
		query.Set("page.pageIndex", strconv.Itoa(page))
		query.Set("page.pageSize", "1000")
		data, err := c.request(ctx, path+"?"+query.Encode())
		if err != nil {
			return nil, err
		}
		unwrapped, err := unwrapVideo(data)
		if err != nil {
			return nil, err
		}
		if unwrapped == nil {
			return nil, errors.New("交大视频列表数据格式已变化")
		}
		var pageData struct {
			Records   []json.RawMessage `json:"records"`
			PageCount *int              `json:"pageCount"`
		}
		if err := json.Unmarshal(unwrapped, &pageData); err != nil {
			return nil, errors.New("交大视频列表数据格式已变化")
		}
		if page == 1 && pageData.Records == nil && pageData.PageCount != nil && *pageData.PageCount == 0 {
			return []json.RawMessage{}, nil
		}
		result = append(result, pageData.Records...)
		if pageData.PageCount != nil {
			if page >= *pageData.PageCount {
				break
			}
		} else if len(pageData.Records) < 1000 {
			break
		}
	}
	return result, nil
}

func (c *Classroom) ListCourses(ctx context.Context) ([]VideoCourse, error) {
	if err := c.checkLogin(ctx); err != nil {
		return nil, err
	}
	termData, err := c.request(ctx, "/list/termYear")
	if err != nil {
		return nil, err
	}
	var terms []struct {
		ID          int64 `json:"id"`
		CurrentTerm bool  `json:"currentTerm"`
	}
	trimmed := strings.TrimSpace(string(termData))
	if strings.HasPrefix(trimmed, "[") {
		if err := json.Unmarshal(termData, &terms); err != nil {
			return nil, errors.New("交大视频学期数据格式已变化")
		}
	} else {
		unwrapped, err := unwrapVideo(termData)
		if err != nil {
			return nil, err
		}
		if err := json.Unmarshal(unwrapped, &terms); err != nil {
			return nil, errors.New("交大视频学期数据格式已变化")
		}
	}
	if len(terms) == 0 {
		return []VideoCourse{}, nil
	}
	term := terms[len(terms)-1]
	for _, value := range terms {
		if value.CurrentTerm {
			term = value
			break
		}
	}
	records, err := c.allPages(ctx, "/myself/curriculum", map[string]string{
		"acteId":        strconv.FormatInt(term.ID, 10),
		"courBeginTime": "",
		"courEndTime":   "",
	})
	if err != nil {
		return nil, err
	}
	if len(records) == 0 {
		records, err = c.allPages(ctx, "/group_subject_vod_list/t-1", map[string]string{
			"page.orders[0].asc":   "false",
			"page.orders[0].field": "courBeginTime",
		})
		if err != nil {
			return nil, err
		}
	}
	courses := map[int64]VideoCourse{}
	for _, raw := range records {
		var record struct {
			ActeID   int64  `json:"acteId"`
			TeclID   *int64 `json:"teclId"`
			SubjCode string `json:"subjCode"`
			SubjName string `json:"subjName"`
		}
		if json.Unmarshal(raw, &record) != nil || record.TeclID == nil {
			continue
		}
		if record.ActeID != term.ID {
			continue
		}
		courses[*record.TeclID] = VideoCourse{ID: *record.TeclID, Code: record.SubjCode, Name: record.SubjName}
	}
	result := make([]VideoCourse, 0, len(courses))
	for _, course := range courses {
		result = append(result, course)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Name < result[j].Name })
	return result, nil
}

func (c *Classroom) ListVideos(ctx context.Context, courseID int64) ([]ClassroomVideo, error) {
	if courseID <= 0 {
		return nil, errors.New("课程 ID 无效")
	}
	if err := c.checkLogin(ctx); err != nil {
		return nil, err
	}
	records, err := c.allPages(ctx, "/subject_vod_list_new", map[string]string{
		"teclIds":              strconv.FormatInt(courseID, 10),
		"page.orders[0].asc":   "false",
		"page.orders[0].field": "courBeginTime",
	})
	if err != nil {
		return nil, err
	}
	result := make([]ClassroomVideo, 0, len(records))
	for _, raw := range records {
		var record struct {
			ID            int64  `json:"id"`
			CourBeginTime string `json:"courBeginTime"`
			CourEndTime   string `json:"courEndTime"`
			ClroName      string `json:"clroName"`
			LetiNumber    any    `json:"letiNumber"`
		}
		if json.Unmarshal(raw, &record) != nil || record.ID == 0 {
			continue
		}
		period := ""
		if record.LetiNumber != nil {
			period = fmt.Sprint(record.LetiNumber)
		}
		result = append(result, ClassroomVideo{
			ID: record.ID, Start: record.CourBeginTime, End: record.CourEndTime,
			Room: record.ClroName, Period: period,
		})
	}
	return result, nil
}

func (c *Classroom) Streams(ctx context.Context, videoID int64) ([]VideoStream, error) {
	if videoID <= 0 {
		return nil, errors.New("录像 ID 无效")
	}
	c.mu.Lock()
	if entry, ok := c.streams[videoID]; ok && time.Since(entry.resolvedAt) < 10*time.Minute {
		c.mu.Unlock()
		return entry.streams, nil
	}
	c.mu.Unlock()
	if err := c.checkLogin(ctx); err != nil {
		return nil, err
	}
	data, err := c.request(ctx, "/course_vod_urls_new?courseId="+strconv.FormatInt(videoID, 10))
	if err != nil {
		return nil, err
	}
	unwrapped, err := unwrapVideo(data)
	if err != nil {
		return nil, err
	}
	if unwrapped == nil {
		return nil, errors.New("暂未获取到可播放录像，请稍后重试")
	}
	var payload struct {
		CourseVodViewList []struct {
			URL     string  `json:"url"`
			ViewNum int     `json:"viewNum"`
			VodTime float64 `json:"vodTime"`
		} `json:"courseVodViewList"`
	}
	if err := json.Unmarshal(unwrapped, &payload); err != nil {
		return nil, errors.New("暂未获取到可播放录像，请稍后重试")
	}
	headers := http.Header{}
	headers.Set("Referer", videoHome)
	headers.Set("Origin", "https://v.sjtu.edu.cn")
	headers.Set("Accept", "*/*")
	streams := make([]VideoStream, 0, len(payload.CourseVodViewList))
	for _, stream := range payload.CourseVodViewList {
		parsed, err := url.Parse(stream.URL)
		if err != nil || parsed.Scheme != "https" || parsed.Hostname() != "live.sjtu.edu.cn" || parsed.User != nil {
			continue
		}
		if parsed.Port() != "" && parsed.Port() != "443" {
			continue
		}
		if !strings.HasSuffix(strings.ToLower(parsed.Path), ".mp4") {
			continue
		}
		proxyURL := c.media.AddProxy(parsed.String(), headers, c.campus.RedirectClient(),
			fmt.Sprintf("video-%d-view-%d.mp4", videoID, stream.ViewNum), "video/mp4", 6*time.Hour)
		streams = append(streams, VideoStream{
			View: stream.ViewNum, Duration: stream.VodTime, PlaybackURL: proxyURL,
		})
	}
	if len(streams) == 0 {
		return nil, errors.New("交大视频没有返回可播放的画面")
	}
	sort.Slice(streams, func(i, j int) bool { return streams[i].View < streams[j].View })
	c.mu.Lock()
	c.streams[videoID] = classroomStreamEntry{streams: streams, resolvedAt: time.Now()}
	c.mu.Unlock()
	return streams, nil
}

func (c *Classroom) Transcript(ctx context.Context, videoID int64) (*VideoTranscript, error) {
	if videoID <= 0 {
		return nil, errors.New("录像 ID 无效")
	}
	c.mu.Lock()
	if entry, ok := c.transcripts[videoID]; ok && time.Since(entry.resolvedAt) < 5*time.Minute {
		c.mu.Unlock()
		return entry.transcript, nil
	}
	c.mu.Unlock()
	if err := c.checkLogin(ctx); err != nil {
		return nil, err
	}
	data, err := c.request(ctx, "/course/ai/translate/"+strconv.FormatInt(videoID, 10)+"?useOriginal=true")
	if err != nil {
		return nil, err
	}
	unwrapped, err := unwrapVideo(data)
	if err != nil {
		return nil, err
	}
	var transcript *VideoTranscript
	if unwrapped != nil {
		var payload struct {
			AfterAssemblyList []struct {
				BeginMS float64 `json:"bg"`
				EndMS   float64 `json:"ed"`
				Text    string  `json:"res"`
			} `json:"afterAssemblyList"`
		}
		if err := json.Unmarshal(unwrapped, &payload); err != nil {
			return nil, errors.New("交大视频文稿数据格式已变化")
		}
		segments := make([]TranscriptSegment, 0, len(payload.AfterAssemblyList))
		for _, segment := range payload.AfterAssemblyList {
			text := strings.TrimSpace(segment.Text)
			if text == "" || segment.EndMS < segment.BeginMS {
				continue
			}
			segments = append(segments, TranscriptSegment{
				StartMS: int64(segment.BeginMS), EndMS: int64(segment.EndMS), Text: text,
			})
		}
		if len(segments) > 0 {
			sort.Slice(segments, func(i, j int) bool { return segments[i].StartMS < segments[j].StartMS })
			transcript = &VideoTranscript{VideoID: videoID, Segments: segments}
		}
	}
	c.mu.Lock()
	c.transcripts[videoID] = classroomTranscriptEntry{transcript: transcript, resolvedAt: time.Now()}
	c.mu.Unlock()
	return transcript, nil
}

func transcriptText(transcript *VideoTranscript, title string) string {
	var builder strings.Builder
	builder.WriteString(title)
	builder.WriteString("\n")
	if transcript == nil {
		builder.WriteString("\n（暂无语音识别文稿）\n")
		return builder.String()
	}
	builder.WriteString(fmt.Sprintf("录像 ID：%d\n\n", transcript.VideoID))
	for _, segment := range transcript.Segments {
		builder.WriteString(fmt.Sprintf("[%s–%s] %s\n\n", transcriptTimestamp(segment.StartMS), transcriptTimestamp(segment.EndMS), segment.Text))
	}
	return builder.String()
}

func transcriptTimestamp(ms int64) string {
	seconds := ms / 1000
	return fmt.Sprintf("%02d:%02d:%02d.%03d", seconds/3600, (seconds/60)%60, seconds%60, ms%1000)
}

func (c *Classroom) ExportTranscript(ctx context.Context, videoID int64, title, target string) (bool, error) {
	transcript, err := c.Transcript(ctx, videoID)
	if err != nil {
		return false, err
	}
	if strings.TrimSpace(title) == "" {
		title = fmt.Sprintf("课堂视频 %d", videoID)
	}
	if target == "" {
		return false, errors.New("没有选择保存位置")
	}
	if err := os.WriteFile(target, []byte(transcriptText(transcript, title)), 0o644); err != nil {
		return false, err
	}
	return true, nil
}

func (c *Classroom) SaveProgress(videoID int64, position, duration float64) error {
	if videoID <= 0 {
		return errors.New("录像 ID 无效")
	}
	_, err := c.db.Exec(`INSERT INTO video_progress (video_id, position, duration, updated_at) VALUES (?, ?, ?, ?)
		ON CONFLICT(video_id) DO UPDATE SET position = excluded.position, duration = excluded.duration, updated_at = excluded.updated_at`,
		videoID, position, duration, Now())
	return err
}

func (c *Classroom) Progress(videoID int64) (map[string]any, error) {
	var position, duration float64
	var updatedAt string
	err := c.db.QueryRow(`SELECT position, duration, updated_at FROM video_progress WHERE video_id = ?`, videoID).
		Scan(&position, &duration, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return map[string]any{"position": 0, "duration": 0, "updatedAt": ""}, nil
	}
	if err != nil {
		return nil, err
	}
	return map[string]any{"position": position, "duration": duration, "updatedAt": updatedAt}, nil
}

func (c *Classroom) State() map[string]any {
	return map[string]any{"connected": c.campus.Connected()}
}

func (c *Classroom) Register(router *Router) {
	router.Handle("classroom.state", func([]json.RawMessage) (any, error) {
		return c.State(), nil
	})
	router.Handle("classroom.connect", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Minute)
		defer cancel()
		return c.Connect(ctx), nil
	})
	router.Handle("classroom.courses", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		defer cancel()
		return c.ListCourses(ctx)
	})
	router.Handle("classroom.videos", func(args []json.RawMessage) (any, error) {
		courseID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		defer cancel()
		return c.ListVideos(ctx, courseID)
	})
	router.Handle("classroom.streams", func(args []json.RawMessage) (any, error) {
		videoID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		return c.Streams(ctx, videoID)
	})
	router.Handle("classroom.transcript", func(args []json.RawMessage) (any, error) {
		videoID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		return c.Transcript(ctx, videoID)
	})
	router.Handle("classroom.progress", func(args []json.RawMessage) (any, error) {
		videoID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		return c.Progress(videoID)
	})
	router.Handle("classroom.saveProgress", func(args []json.RawMessage) (any, error) {
		videoID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		position, err := DecodeArg[float64](args, 1)
		if err != nil {
			return nil, err
		}
		duration, _, _ := DecodeOptionalArg[float64](args, 2)
		return true, c.SaveProgress(videoID, position, duration)
	})
	router.Handle("classroom.downloads", func([]json.RawMessage) (any, error) {
		return c.Downloads(), nil
	})
	router.Handle("classroom.cancelDownload", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		c.CancelDownload(id)
		return true, nil
	})
	router.Handle("classroom.revealDownload", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return true, c.RevealDownload(id)
	})
}
