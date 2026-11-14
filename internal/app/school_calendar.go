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
	"strings"
	"sync"
	"time"
)

var ErrSchoolCalendarAuth = errors.New("请先登录教学信息服务网")

type SchoolEvent struct {
	ID           string  `json:"id"`
	Title        string  `json:"title"`
	StartAt      string  `json:"startAt"`
	EndAt        string  `json:"endAt"`
	Location     *string `json:"location"`
	CalendarID   string  `json:"calendarId"`
	CalendarName string  `json:"calendarName"`
	Color        *string `json:"color"`
	AllDay       bool    `json:"allDay"`
	Status       *string `json:"status"`
	Recurrence   int     `json:"recurrence"`
}

type SchoolCalendarState struct {
	Connected    bool    `json:"connected"`
	Account      string  `json:"account,omitempty"`
	LastSyncedAt *string `json:"lastSyncedAt,omitempty"`
	Error        string  `json:"error,omitempty"`
}

// 交我办日历接口收发的时间都是学校本地时间（UTC+8），
// 与运行机器的时区无关；用 time.Local 会在非中国时区的机器上算错 8 小时。
var campusZone = time.FixedZone("CST", 8*60*60)

type SchoolCalendar struct {
	db     *sql.DB
	campus *CampusSession
	emit   func(string, any)

	syncMu    sync.Mutex
	stateMu   sync.RWMutex
	lastError string
}

func NewSchoolCalendar(db *sql.DB, campus *CampusSession, emit func(string, any)) *SchoolCalendar {
	return &SchoolCalendar{db: db, campus: campus, emit: emit}
}

func (s *SchoolCalendar) Register(router *Router) {
	router.Handle("schoolCalendar.state", func([]json.RawMessage) (any, error) {
		return s.State(), nil
	})
	router.Handle("schoolCalendar.sync", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		return s.Sync(ctx)
	})
	router.Handle("schoolCalendar.query", func(args []json.RawMessage) (any, error) {
		start, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		end, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
		defer cancel()
		return s.Events(ctx, start, end)
	})
	router.Handle("schoolCalendar.connect", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Minute)
		defer cancel()
		if _, err := s.campus.Login(ctx); err != nil {
			return nil, err
		}
		return s.Sync(ctx)
	})
}

func (s *SchoolCalendar) State() SchoolCalendarState {
	s.stateMu.RLock()
	lastError := s.lastError
	s.stateMu.RUnlock()
	state := SchoolCalendarState{Error: lastError}
	if synced := s.meta("last_synced_at"); synced != "" {
		state.LastSyncedAt = &synced
		state.Connected = lastError == ""
	}
	state.Account = s.meta("account")
	return state
}

// Account is the display name learned from the calendar service, if any.
func (s *SchoolCalendar) Account() string {
	return s.meta("account")
}

func (s *SchoolCalendar) meta(key string) string {
	var value string
	_ = s.db.QueryRow(`SELECT value FROM school_calendar_meta WHERE key = ?`, key).Scan(&value)
	return value
}

func (s *SchoolCalendar) setMeta(key, value string) {
	_, _ = s.db.Exec(`INSERT INTO school_calendar_meta (key, value) VALUES (?, ?)
		ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
}

func (s *SchoolCalendar) setError(err error) {
	s.stateMu.Lock()
	defer s.stateMu.Unlock()
	if err == nil {
		s.lastError = ""
		return
	}
	s.lastError = err.Error()
}

func (s *SchoolCalendar) emitState() {
	if s.emit == nil {
		return
	}
	s.emit("schoolCalendar.changed", s.State())
}

func (s *SchoolCalendar) client() *http.Client {
	base := s.campus.RedirectClient()
	client := *base
	client.Timeout = 45 * time.Second
	return &client
}

func (s *SchoolCalendar) get(ctx context.Context, client *http.Client, rawURL string, target any) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return err
	}
	request.Header.Set("Accept", "application/json, text/plain, */*")
	request.Header.Set("Referer", "https://calendar.sjtu.edu.cn/")
	request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0")
	response, err := client.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusUnauthorized || response.StatusCode == http.StatusForbidden {
		return ErrSchoolCalendarAuth
	}
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("日程服务返回 HTTP %d", response.StatusCode)
	}
	contentType := response.Header.Get("Content-Type")
	if !strings.Contains(contentType, "json") {
		return ErrSchoolCalendarAuth
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 16<<20))
	if err != nil {
		return err
	}
	if target != nil {
		if err := json.Unmarshal(data, target); err != nil {
			return fmt.Errorf("日程数据格式无法识别：%w", err)
		}
	}
	return nil
}

func (s *SchoolCalendar) bootstrap(ctx context.Context, client *http.Client) error {
	for _, page := range []string{
		"https://my.sjtu.edu.cn/ui/calendar/",
		"https://calendar.sjtu.edu.cn/?tenantUserId=",
	} {
		request, err := http.NewRequestWithContext(ctx, http.MethodGet, page, nil)
		if err != nil {
			return err
		}
		request.Header.Set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0")
		response, err := client.Do(request)
		if err != nil {
			return err
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 2<<20))
		_ = response.Body.Close()
	}
	return nil
}

type schoolCalendarUser struct {
	Status  int  `json:"status"`
	Success bool `json:"success"`
	Data    struct {
		AccountNo string `json:"accountNo"`
		Name      string `json:"name"`
	} `json:"data"`
}

func (s *SchoolCalendar) authenticate(ctx context.Context, client *http.Client) (schoolCalendarUser, error) {
	var user schoolCalendarUser
	err := s.get(ctx, client, "https://calendar.sjtu.edu.cn/api/user/get", &user)
	if err == nil && user.Success && user.Data.AccountNo != "" {
		return user, nil
	}
	if err := s.bootstrap(ctx, client); err != nil {
		return schoolCalendarUser{}, err
	}
	user = schoolCalendarUser{}
	err = s.get(ctx, client, "https://calendar.sjtu.edu.cn/api/user/get", &user)
	if errors.Is(err, ErrSchoolCalendarAuth) || (err == nil && (!user.Success || user.Data.AccountNo == "")) {
		return schoolCalendarUser{}, ErrSchoolCalendarAuth
	}
	if err != nil {
		return schoolCalendarUser{}, err
	}
	return user, nil
}

type rawSchoolCalendar struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Color string `json:"color"`
}

type rawSchoolEvent struct {
	EventID    string  `json:"eventId"`
	Title      string  `json:"title"`
	TitleEn    string  `json:"titleEn"`
	StartTime  string  `json:"startTime"`
	EndTime    string  `json:"endTime"`
	Location   *string `json:"location"`
	LocationEn *string `json:"locationEn"`
	CalendarID string  `json:"calendarId"`
	AllDay     bool    `json:"allDay"`
	Status     *string `json:"status"`
	Recurrence int     `json:"recurrence"`
}

func (s *SchoolCalendar) Sync(ctx context.Context) (SchoolCalendarState, error) {
	s.syncMu.Lock()
	defer s.syncMu.Unlock()
	now := time.Now()
	start := now.AddDate(0, -4, 0)
	end := now.AddDate(0, 8, 0)
	if err := s.fetchAndStore(ctx, start, end, true); err != nil {
		s.setError(err)
		s.emitState()
		return s.State(), err
	}
	s.setError(nil)
	s.emitState()
	return s.State(), nil
}

func (s *SchoolCalendar) SyncRange(ctx context.Context, start, end time.Time) error {
	s.syncMu.Lock()
	defer s.syncMu.Unlock()
	if err := s.fetchAndStore(ctx, start, end, false); err != nil {
		s.setError(err)
		s.emitState()
		return err
	}
	s.setError(nil)
	s.emitState()
	return nil
}

func (s *SchoolCalendar) fetchAndStore(ctx context.Context, start, end time.Time, replaceAll bool) error {
	client := s.client()
	user, err := s.authenticate(ctx, client)
	if err != nil {
		return err
	}
	var calendars struct {
		Status  int  `json:"status"`
		Success bool `json:"success"`
		Data    struct {
			My    []rawSchoolCalendar `json:"my"`
			Share []rawSchoolCalendar `json:"share"`
		} `json:"data"`
	}
	if err := s.get(ctx, client, "https://calendar.sjtu.edu.cn/api/calendar/list", &calendars); err != nil {
		return err
	}
	names := map[string]string{}
	colors := map[string]string{}
	for _, calendar := range append(calendars.Data.My, calendars.Data.Share...) {
		names[calendar.ID] = calendar.Name
		colors[calendar.ID] = calendar.Color
	}
	names["schoolCalendar"] = firstNonEmpty(names["schoolCalendar"], "校历")
	colors["schoolCalendar"] = firstNonEmpty(colors["schoolCalendar"], "#3788D8")

	query := url.Values{}
	query.Set("startDate", start.In(campusZone).Format("2006-01-02 15:04"))
	query.Set("endDate", end.In(campusZone).Format("2006-01-02 15:04"))
	query.Set("weekly", "false")
	query.Set("ids", "")
	var payload struct {
		Status  int  `json:"status"`
		Success bool `json:"success"`
		Data    struct {
			Events         []rawSchoolEvent `json:"events"`
			SchoolCalendar struct {
				Events []rawSchoolEvent `json:"events"`
			} `json:"schoolCalendar"`
		} `json:"data"`
	}
	rawURL := "https://calendar.sjtu.edu.cn/api/event/list?" + query.Encode()
	if err := s.get(ctx, client, rawURL, &payload); err != nil {
		return err
	}
	events := make([]SchoolEvent, 0, len(payload.Data.Events)+len(payload.Data.SchoolCalendar.Events))
	seen := map[string]bool{}
	for _, raw := range append(payload.Data.Events, payload.Data.SchoolCalendar.Events...) {
		event, ok := s.mapEvent(raw, names, colors)
		if !ok || seen[event.ID] {
			continue
		}
		seen[event.ID] = true
		events = append(events, event)
	}
	syncedAt := Now()
	err = Tx(ctx, s.db, func(tx *sql.Tx) error {
		if replaceAll {
			if _, err := tx.Exec(`DELETE FROM school_events`); err != nil {
				return err
			}
		}
		statement, err := tx.Prepare(`INSERT INTO school_events
			(id, title, start_at, end_at, location, calendar_id, calendar_name, color, all_day, status, recurrence, synced_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT(id) DO UPDATE SET title = excluded.title, start_at = excluded.start_at,
			  end_at = excluded.end_at, location = excluded.location, calendar_id = excluded.calendar_id,
			  calendar_name = excluded.calendar_name, color = excluded.color, all_day = excluded.all_day,
			  status = excluded.status, recurrence = excluded.recurrence, synced_at = excluded.synced_at`)
		if err != nil {
			return err
		}
		defer statement.Close()
		for _, event := range events {
			if _, err := statement.Exec(event.ID, event.Title, event.StartAt, event.EndAt,
				nullableTextPtr(event.Location), event.CalendarID, event.CalendarName,
				nullableTextPtr(event.Color), BoolInt(event.AllDay), nullableTextPtr(event.Status),
				event.Recurrence, syncedAt); err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return err
	}
	s.setMeta("account", firstNonEmpty(user.Data.Name, user.Data.AccountNo))
	s.setMeta("last_synced_at", syncedAt)
	return nil
}

func (s *SchoolCalendar) mapEvent(raw rawSchoolEvent, names, colors map[string]string) (SchoolEvent, bool) {
	start, ok := parseSchoolTime(raw.StartTime)
	if !ok {
		return SchoolEvent{}, false
	}
	end, ok := parseSchoolTime(raw.EndTime)
	if !ok || !end.After(start) {
		end = start.Add(30 * time.Minute)
	}
	title := firstNonEmpty(raw.Title, raw.TitleEn)
	if title == "" || raw.EventID == "" {
		return SchoolEvent{}, false
	}
	location := raw.Location
	if location == nil || strings.TrimSpace(*location) == "" {
		location = raw.LocationEn
	}
	calendarID := firstNonEmpty(raw.CalendarID, "schoolCalendar")
	name := firstNonEmpty(names[calendarID], calendarID)
	color := firstNonEmpty(colors[calendarID], "#5698DB")
	return SchoolEvent{
		ID: raw.EventID, Title: title,
		StartAt: start.UTC().Format(time.RFC3339), EndAt: end.UTC().Format(time.RFC3339),
		Location: location, CalendarID: calendarID, CalendarName: name,
		Color: &color, AllDay: raw.AllDay, Status: raw.Status, Recurrence: raw.Recurrence,
	}, true
}

func parseSchoolTime(value string) (time.Time, bool) {
	value = strings.TrimSpace(value)
	for _, layout := range []string{"2006-01-02 15:04", "2006-01-02 15:04:05", "2006-01-02", time.RFC3339} {
		if parsed, err := time.ParseInLocation(layout, value, campusZone); err == nil {
			return parsed, true
		}
	}
	return time.Time{}, false
}

func (s *SchoolCalendar) Events(ctx context.Context, startValue, endValue string) ([]SchoolEvent, error) {
	start, err := time.Parse(time.RFC3339, startValue)
	if err != nil {
		return nil, errors.New("开始时间格式无效")
	}
	end, err := time.Parse(time.RFC3339, endValue)
	if err != nil {
		return nil, errors.New("结束时间格式无效")
	}
	if !end.After(start) {
		return nil, errors.New("结束时间必须晚于开始时间")
	}
	events, err := s.query(start, end)
	if err != nil {
		return nil, err
	}
	if len(events) > 0 {
		return events, nil
	}
	last, _ := time.Parse(time.RFC3339, s.meta("last_synced_at"))
	if last.IsZero() || time.Since(last) > 30*time.Minute {
		if err := s.SyncRange(ctx, start.Add(-24*time.Hour), end.Add(24*time.Hour)); err != nil && !errors.Is(err, ErrSchoolCalendarAuth) {
			return nil, err
		}
		return s.query(start, end)
	}
	return events, nil
}

func (s *SchoolCalendar) query(start, end time.Time) ([]SchoolEvent, error) {
	rows, err := s.db.Query(`SELECT id, title, start_at, end_at, location, calendar_id, calendar_name, color, all_day, status, recurrence
		FROM school_events WHERE start_at < ? AND end_at > ? ORDER BY start_at`, end.UTC().Format(time.RFC3339), start.UTC().Format(time.RFC3339))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []SchoolEvent{}
	for rows.Next() {
		var event SchoolEvent
		var location, status sql.NullString
		var color sql.NullString
		var allDay int
		if err := rows.Scan(&event.ID, &event.Title, &event.StartAt, &event.EndAt, &location,
			&event.CalendarID, &event.CalendarName, &color, &allDay, &status, &event.Recurrence); err != nil {
			return nil, err
		}
		if location.Valid {
			event.Location = &location.String
		}
		if color.Valid {
			event.Color = &color.String
		}
		if status.Valid {
			event.Status = &status.String
		}
		event.AllDay = allDay == 1
		result = append(result, event)
	}
	return result, rows.Err()
}

func (a *App) startSchoolCalendarLoop() {
	go func() {
		time.Sleep(2 * time.Second)
		ctx, cancel := context.WithTimeout(a.ctx, 2*time.Minute)
		if _, err := a.schoolCalendar.Sync(ctx); err != nil && a.logger != nil && !errors.Is(err, ErrSchoolCalendarAuth) {
			a.logger.Warn("school calendar sync failed", "error", err)
		}
		cancel()
		ticker := time.NewTicker(time.Hour)
		defer ticker.Stop()
		for {
			select {
			case <-a.ctx.Done():
				return
			case <-ticker.C:
				ctx, cancel := context.WithTimeout(a.ctx, 2*time.Minute)
				if _, err := a.schoolCalendar.Sync(ctx); err != nil && a.logger != nil && !errors.Is(err, ErrSchoolCalendarAuth) {
					a.logger.Warn("school calendar sync failed", "error", err)
				}
				cancel()
			}
		}
	}()
}
