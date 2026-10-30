package app

// 教学信息服务网「学生考试查询」（kwgl/kscx_cxXsksxxIndex.html，gnmkdm=N358105）。
// 该接口与课表不同：必须 POST 到 Index.html 并带 doType=query，参数只放 body。

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	examPageURL   = campusOrigin + "/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default"
	examQueryURL  = campusOrigin + "/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"
	examSyncEvery = 6 * time.Hour
)

type ExamItem struct {
	ID         int64   `json:"id"`
	Semester   string  `json:"semester"`
	SourceID   string  `json:"sourceId"`
	CourseCode string  `json:"courseCode"`
	CourseName string  `json:"courseName"`
	ExamName   string  `json:"examName"`
	ExamDate   string  `json:"examDate"`
	TimeText   string  `json:"timeText"`
	StartAt    string  `json:"startAt"`
	EndAt      string  `json:"endAt"`
	Campus     string  `json:"campus"`
	Classroom  string  `json:"classroom"`
	Seat       string  `json:"seat"`
	Form       string  `json:"form"`
	ClassName  string  `json:"className"`
	Teacher    string  `json:"teacher"`
	Credits    float64 `json:"credits"`
	SyncedAt   string  `json:"syncedAt"`
}

type ExamState struct {
	Connected    bool   `json:"connected"`
	Semester     string `json:"semester"`
	Count        int    `json:"count"`
	LastSyncedAt string `json:"lastSyncedAt"`
	Error        string `json:"error"`
}

type rawExamItem struct {
	KCMC  string `json:"kcmc"`
	KCH   string `json:"kch"`
	KSMC  string `json:"ksmc"`
	KSSJ  string `json:"kssj"`
	CDMC  string `json:"cdmc"`
	CDBH  string `json:"cdbh"`
	XQMC  string `json:"cdxqmc"`
	KSFS  string `json:"ksfs"`
	KHFS  string `json:"khfs"`
	JXBMC string `json:"jxbmc"`
	JSXX  string `json:"jsxx"`
	XF    string `json:"xf"`
	SJBH  string `json:"sjbh"`
	XNM   string `json:"xnm"`
	XQMMC string `json:"xqmmc"`
}

type Exams struct {
	db       *sql.DB
	campus   *CampusSession
	notifier *Notifications
	logger   *slog.Logger
	emit     func(string, any)

	mu      sync.RWMutex
	lastErr string
}

func NewExams(db *sql.DB, campus *CampusSession, notifier *Notifications, logger *slog.Logger, emit func(string, any)) *Exams {
	return &Exams{db: db, campus: campus, notifier: notifier, logger: logger, emit: emit}
}

func (e *Exams) State() ExamState {
	state := ExamState{Connected: e.campus.Connected(), Semester: currentSemester(time.Now())}
	var synced string
	_ = e.db.QueryRow(`SELECT value FROM meta WHERE key = 'examLastSynced'`).Scan(&synced)
	state.LastSyncedAt = synced
	var count int
	_ = e.db.QueryRow(`SELECT COUNT(*) FROM exam_items`).Scan(&count)
	state.Count = count
	e.mu.RLock()
	state.Error = e.lastErr
	e.mu.RUnlock()
	return state
}

func (e *Exams) List() ([]ExamItem, error) {
	rows, err := e.db.Query(`SELECT id, semester, source_id, course_code, course_name, exam_name, exam_date,
		time_text, start_at, end_at, campus, classroom, seat, form, class_name, teacher, credits, synced_at
		FROM exam_items ORDER BY start_at ASC LIMIT 200`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []ExamItem{}
	for rows.Next() {
		var item ExamItem
		var sourceID, examName, date, timeText, startAt, endAt, campus, classroom, seat, form, className, teacher, code, synced, semester string
		var credits float64
		var id int64
		if err := rows.Scan(&id, &semester, &sourceID, &code, &item.CourseName, &examName, &date,
			&timeText, &startAt, &endAt, &campus, &classroom, &seat, &form, &className, &teacher,
			&credits, &synced); err != nil {
			return nil, err
		}
		item.ID = id
		item.Semester = semester
		item.SourceID = sourceID
		item.CourseCode = code
		item.ExamName = examName
		item.ExamDate = date
		item.TimeText = timeText
		item.StartAt = startAt
		item.EndAt = endAt
		item.Campus = campus
		item.Classroom = classroom
		item.Seat = seat
		item.Form = form
		item.ClassName = className
		item.Teacher = teacher
		item.Credits = credits
		item.SyncedAt = synced
		result = append(result, item)
	}
	return result, rows.Err()
}

var (
	examHiddenNamePattern = regexp.MustCompile(`(?is)<input[^>]+name="([^"]+)"[^>]*value="([^"]*)"`)
	examHiddenIDPattern   = regexp.MustCompile(`(?is)<input[^>]+id="(sessionUserKey|gnmkdmKey|pkey|localeKey)"[^>]*value="([^"]*)"`)
	examTimePattern       = regexp.MustCompile(`(\d{4}-\d{2}-\d{2})\s*[（(](\d{1,2}):(\d{2})\s*[-–~至]\s*(\d{1,2}):(\d{2})[）)]`)
	examDateOnlyPattern   = regexp.MustCompile(`(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})`)
)

func parseExamTokens(page string) map[string]string {
	tokens := map[string]string{}
	for _, match := range examHiddenNamePattern.FindAllStringSubmatch(page, -1) {
		name := strings.TrimSpace(match[1])
		if name == "csrftoken" || name == "pkey" || name == "localeKey" || name == "gnmkdmKey" {
			if _, exists := tokens[name]; !exists {
				tokens[name] = strings.TrimSpace(html.UnescapeString(match[2]))
			}
		}
	}
	for _, match := range examHiddenIDPattern.FindAllStringSubmatch(page, -1) {
		key := strings.TrimSpace(match[1])
		if _, exists := tokens[key]; !exists {
			tokens[key] = strings.TrimSpace(html.UnescapeString(match[2]))
		}
	}
	return tokens
}

// parseExamTime turns "2026-11-11(13:10-15:10)" into local RFC3339 bounds.
func parseExamTime(value string) (string, string, string, string) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" {
		return "", "", "", ""
	}
	if match := examTimePattern.FindStringSubmatch(trimmed); match != nil {
		start, err := time.ParseInLocation("2006-1-2 15:04", match[1]+" "+match[2]+":"+match[3], time.Local)
		if err != nil {
			return trimmed, "", "", ""
		}
		end, err := time.ParseInLocation("2006-1-2 15:04", match[1]+" "+match[4]+":"+match[5], time.Local)
		if err != nil {
			end = start.Add(2 * time.Hour)
		}
		return match[1], start.Format("15:04") + "-" + end.Format("15:04"),
			start.Format(time.RFC3339), end.Format(time.RFC3339)
	}
	if match := examDateOnlyPattern.FindStringSubmatch(trimmed); match != nil {
		return match[1] + "-" + strings.TrimSpace(match[2]) + "-" + match[3], trimmed, "", ""
	}
	return "", trimmed, "", ""
}

func (e *Exams) Sync(ctx context.Context) (ExamState, error) {
	if !e.campus.Connected() {
		e.mu.Lock()
		e.lastErr = "尚未登录教学信息服务网"
		e.mu.Unlock()
		return e.State(), errors.New("请先登录教学信息服务网")
	}
	semester := currentSemester(time.Now())
	xnm, xqm := semesterParams(semester)
	page, err := e.campus.Request(ctx, http.MethodGet, examPageURL, map[string]string{
		"Referer": campusOrigin + "/student/index.html",
	}, "")
	if err != nil {
		e.mu.Lock()
		e.lastErr = err.Error()
		e.mu.Unlock()
		return e.State(), err
	}
	tokens := parseExamTokens(string(page))
	values := url.Values{}
	values.Set("xnm", xnm)
	values.Set("xqm", xqm)
	values.Set("ksmcdmb_id", "")
	values.Set("kch", "")
	values.Set("kc", "")
	values.Set("ksrq", "")
	values.Set("kkbm_id", "")
	values.Set("csrftoken", tokens["csrftoken"])
	values.Set("pkey", tokens["pkey"])
	values.Set("sessionUserKey", tokens["sessionUserKey"])
	values.Set("gnmkdmKey", tokens["gnmkdmKey"])
	values.Set("localeKey", tokens["localeKey"])
	values.Set("queryModel.currentPage", "1")
	values.Set("queryModel.showCount", "100")
	values.Set("_search", "false")
	values.Set("nd", strconv.FormatInt(time.Now().UnixMilli(), 10))
	values.Set("time", "1")

	data, err := e.campus.Request(ctx, http.MethodPost, examQueryURL, map[string]string{
		"Content-Type":     "application/x-www-form-urlencoded; charset=UTF-8",
		"X-Requested-With": "XMLHttpRequest",
		"Referer":          examPageURL,
		"Origin":           campusOrigin,
	}, values.Encode())
	if err != nil {
		e.mu.Lock()
		e.lastErr = err.Error()
		e.mu.Unlock()
		return e.State(), err
	}
	var payload struct {
		Items       []rawExamItem `json:"items"`
		TotalResult int           `json:"totalResult"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		e.mu.Lock()
		e.lastErr = "考试接口没有返回预期数据"
		e.mu.Unlock()
		return e.State(), errors.New("考试接口没有返回预期数据，请重新登录后再试")
	}

	syncedAt := Now()
	tx, err := e.db.BeginTx(ctx, nil)
	if err != nil {
		return e.State(), err
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.Exec(`DELETE FROM exam_items WHERE semester = ?`, semester); err != nil {
		return e.State(), err
	}
	upcoming := []ExamItem{}
	for _, raw := range payload.Items {
		name := strings.TrimSpace(raw.KCMC)
		if name == "" {
			continue
		}
		date, timeText, startAt, endAt := parseExamTime(raw.KSSJ)
		sourceID := strings.TrimSpace(raw.SJBH)
		if sourceID == "" {
			sourceID = fmt.Sprintf("%s|%s|%s", name, date, timeText)
		}
		credits, _ := strconv.ParseFloat(strings.TrimSpace(raw.XF), 64)
		item := ExamItem{
			Semester: semester, SourceID: sourceID, CourseCode: strings.TrimSpace(raw.KCH),
			CourseName: name, ExamName: strings.TrimSpace(raw.KSMC), ExamDate: date, TimeText: timeText,
			StartAt: startAt, EndAt: endAt, Campus: strings.TrimSpace(raw.XQMC),
			Classroom: firstNonEmpty(strings.TrimSpace(raw.CDMC), strings.TrimSpace(raw.CDBH)),
			Form:      firstNonEmpty(strings.TrimSpace(raw.KSFS), strings.TrimSpace(raw.KHFS)),
			ClassName: strings.TrimSpace(raw.JXBMC), Teacher: strings.TrimSpace(raw.JSXX),
			Credits: credits, SyncedAt: syncedAt,
		}
		if _, err := tx.Exec(`INSERT OR REPLACE INTO exam_items
			(semester, source_id, course_code, course_name, exam_name, exam_date, time_text,
			 start_at, end_at, campus, classroom, seat, form, class_name, teacher, credits, synced_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			item.Semester, item.SourceID, item.CourseCode, item.CourseName, item.ExamName, item.ExamDate,
			item.TimeText, item.StartAt, item.EndAt, item.Campus, item.Classroom, item.Seat, item.Form,
			item.ClassName, item.Teacher, item.Credits, item.SyncedAt); err != nil {
			return e.State(), err
		}
		if item.StartAt != "" {
			upcoming = append(upcoming, item)
		}
	}
	if _, err := tx.Exec(`INSERT INTO meta (key, value) VALUES ('examLastSynced', ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, syncedAt); err != nil {
		return e.State(), err
	}
	if err := tx.Commit(); err != nil {
		return e.State(), err
	}

	e.mu.Lock()
	e.lastErr = ""
	e.mu.Unlock()
	e.notifyUpcoming(upcoming)
	e.emit("exam.changed", e.State())
	return e.State(), nil
}

// notifyUpcoming warns once per exam inside the next 36 hours.
func (e *Exams) notifyUpcoming(items []ExamItem) {
	if e.notifier == nil {
		return
	}
	now := time.Now()
	for _, item := range items {
		start, err := time.Parse(time.RFC3339, item.StartAt)
		if err != nil {
			continue
		}
		until := start.Sub(now)
		if until <= 0 || until > 36*time.Hour {
			continue
		}
		body := fmt.Sprintf("%s %s", start.Format("1月2日 15:04"), firstNonEmpty(item.Classroom, "地点待定"))
		if item.Form != "" {
			body += " · " + item.Form
		}
		e.notifier.Add("exam", "考试提醒："+item.CourseName, body,
			map[string]any{"view": "timetable"}, "exam:"+item.Semester+":"+item.SourceID, true)
	}
}

func (e *Exams) Register(router *Router) {
	router.Handle("exam.state", func([]json.RawMessage) (any, error) {
		return e.State(), nil
	})
	router.Handle("exam.list", func([]json.RawMessage) (any, error) {
		return e.List()
	})
	router.Handle("exam.sync", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
		defer cancel()
		return e.Sync(ctx)
	})
}

func (a *App) startExamLoop() {
	go func() {
		time.Sleep(4 * time.Second)
		syncOnce := func() {
			ctx, cancel := context.WithTimeout(a.ctx, 3*time.Minute)
			defer cancel()
			if _, err := a.exams.Sync(ctx); err != nil && a.logger != nil {
				a.logger.Warn("exam sync failed", "error", err)
			}
		}
		syncOnce()
		ticker := time.NewTicker(examSyncEvery)
		defer ticker.Stop()
		for {
			select {
			case <-a.ctx.Done():
				return
			case <-ticker.C:
				syncOnce()
			}
		}
	}()
}
