package app

// 日程模块只保留本地日程（calendar_events）与交我办日历的读取；
// 原教学网课表（i.sjtu.edu.cn 课表 JSON → timetable_lessons）已下线：
// 课程数据统一走交我办日历（school_events，每小时同步），避免两套数据互相打架。

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"
)

// 教学网保留下来给「考试查询」使用（exam.go），课表同步已下线。
const campusOrigin = "https://i.sjtu.edu.cn"

// semesterParams 把形如 2026-2027-1 的学期号转换成教务系统的 xnm / xqm 参数。
func semesterParams(semester string) (string, string) {
	parts := strings.Split(semester, "-")
	xnm := parts[0]
	term := "3"
	switch parts[2] {
	case "2":
		term = "12"
	case "3":
		term = "16"
	}
	return xnm, term
}

type Timetable struct {
	db   *sql.DB
	emit func(string, any)
}

func NewTimetable(db *sql.DB, emit func(string, any)) *Timetable {
	return &Timetable{db: db, emit: emit}
}

// 交我办日历里课程所属的日历名。
const courseCalendarName = "课程"

type CalendarEvent struct {
	ID       int64   `json:"id"`
	Title    string  `json:"title"`
	StartAt  string  `json:"startAt"`
	EndAt    string  `json:"endAt"`
	Location *string `json:"location"`
	Color    *string `json:"color"`
	TodoID   *string `json:"todoId"`
}
func currentSemester(now time.Time) string {
	year, month := now.Year(), int(now.Month())
	if month >= 9 {
		return fmt.Sprintf("%d-%d-1", year, year+1)
	}
	if month <= 2 {
		return fmt.Sprintf("%d-%d-1", year-1, year)
	}
	if month <= 7 {
		return fmt.Sprintf("%d-%d-2", year-1, year)
	}
	return fmt.Sprintf("%d-%d-3", year-1, year)
}
var calendarColors = map[string]bool{
	"rose": true, "peach": true, "sand": true, "sage": true,
	"teal": true, "blue": true, "lilac": true, "slate": true,
}
var coursePalette = []string{"blue", "teal", "sage", "sand", "lilac", "peach", "rose", "slate"}
func colorForCourse(value string) string {
	hash := 0
	for _, r := range value {
		hash = (hash*31 + int(r)) % 100000
	}
	return coursePalette[hash%len(coursePalette)]
}
func nullableText(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return strings.TrimSpace(value)
}
func (t *Timetable) Events(startValue, endValue string) ([]CalendarEvent, error) {
	rows, err := t.db.Query(`SELECT id, title, start_at, end_at, location, color, todo_id
		FROM calendar_events WHERE end_at > ? AND start_at < ? ORDER BY start_at`, startValue, endValue)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []CalendarEvent{}
	for rows.Next() {
		var event CalendarEvent
		var location, color, todoID sql.NullString
		if err := rows.Scan(&event.ID, &event.Title, &event.StartAt, &event.EndAt, &location, &color, &todoID); err != nil {
			return nil, err
		}
		if location.Valid {
			value := location.String
			event.Location = &value
		}
		if color.Valid {
			value := color.String
			event.Color = &value
		}
		if todoID.Valid {
			value := todoID.String
			event.TodoID = &value
		}
		result = append(result, event)
	}
	return result, rows.Err()
}
func (t *Timetable) CreateEvent(input map[string]any) (CalendarEvent, error) {
	title := strings.TrimSpace(inputText(input, "title"))
	startAt := strings.TrimSpace(inputText(input, "startAt"))
	endAt := strings.TrimSpace(inputText(input, "endAt"))
	location := strings.TrimSpace(inputText(input, "location"))
	color := strings.TrimSpace(inputText(input, "color"))
	if title == "" || len([]rune(title)) > 120 {
		return CalendarEvent{}, errors.New("事件标题不能为空且不超过 120 字")
	}
	start, err := time.Parse(time.RFC3339, startAt)
	if err != nil {
		return CalendarEvent{}, errors.New("开始时间格式无效")
	}
	end, err := time.Parse(time.RFC3339, endAt)
	if err != nil || !end.After(start) {
		return CalendarEvent{}, errors.New("结束时间必须晚于开始时间")
	}
	if color != "" && !calendarColors[color] {
		return CalendarEvent{}, errors.New("事件颜色无效")
	}
	now := Now()
	result, err := t.db.Exec(`INSERT INTO calendar_events (title, start_at, end_at, location, color, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?)`, title, start.Format(time.RFC3339), end.Format(time.RFC3339),
		nullableText(location), nullableText(color), now, now)
	if err != nil {
		return CalendarEvent{}, err
	}
	id, _ := result.LastInsertId()
	if t.emit != nil {
		t.emit("timetable.changed", map[string]any{"eventId": id})
	}
	return CalendarEvent{
		ID:       id,
		Title:    title,
		StartAt:  start.Format(time.RFC3339),
		EndAt:    end.Format(time.RFC3339),
		Location: stringPtr(location),
		Color:    stringPtr(color),
	}, nil
}
func inputText(input map[string]any, key string) string {
	value, ok := input[key]
	if !ok || value == nil {
		return ""
	}
	return fmt.Sprint(value)
}
func stringPtr(value string) *string {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return &value
}
func (t *Timetable) UpdateEvent(id int64, input map[string]any) (CalendarEvent, error) {
	title := strings.TrimSpace(inputText(input, "title"))
	startAt := strings.TrimSpace(inputText(input, "startAt"))
	endAt := strings.TrimSpace(inputText(input, "endAt"))
	location := strings.TrimSpace(inputText(input, "location"))
	color := strings.TrimSpace(inputText(input, "color"))
	if title == "" || len([]rune(title)) > 120 {
		return CalendarEvent{}, errors.New("事件标题不能为空且不超过 120 字")
	}
	start, err := time.Parse(time.RFC3339, startAt)
	if err != nil {
		return CalendarEvent{}, errors.New("开始时间格式无效")
	}
	end, err := time.Parse(time.RFC3339, endAt)
	if err != nil || !end.After(start) {
		return CalendarEvent{}, errors.New("结束时间必须晚于开始时间")
	}
	if color != "" && !calendarColors[color] {
		return CalendarEvent{}, errors.New("事件颜色无效")
	}
	var todoID sql.NullString
	err = t.db.QueryRow(`SELECT todo_id FROM calendar_events WHERE id = ?`, id).Scan(&todoID)
	if errors.Is(err, sql.ErrNoRows) {
		return CalendarEvent{}, errors.New("日程不存在")
	}
	if err != nil {
		return CalendarEvent{}, err
	}
	if todoID.Valid {
		return CalendarEvent{}, errors.New("该日程来自 TODO，请到 TODO 中修改")
	}
	_, err = t.db.Exec(`UPDATE calendar_events SET title = ?, start_at = ?, end_at = ?, location = ?, color = ?, updated_at = ? WHERE id = ?`,
		title, start.Format(time.RFC3339), end.Format(time.RFC3339), nullableText(location), nullableText(color), Now(), id)
	if err != nil {
		return CalendarEvent{}, err
	}
	if t.emit != nil {
		t.emit("timetable.changed", map[string]any{"eventId": id})
	}
	return CalendarEvent{
		ID: id, Title: title, StartAt: start.Format(time.RFC3339), EndAt: end.Format(time.RFC3339),
		Location: stringPtr(location), Color: stringPtr(color),
	}, nil
}
func (t *Timetable) DeleteEvent(id int64) error {
	var todoID sql.NullString
	err := t.db.QueryRow(`SELECT todo_id FROM calendar_events WHERE id = ?`, id).Scan(&todoID)
	if errors.Is(err, sql.ErrNoRows) {
		return errors.New("日程不存在")
	}
	if err != nil {
		return err
	}
	if todoID.Valid {
		return errors.New("该日程来自 TODO，请到 TODO 中修改或删除")
	}
	if _, err := t.db.Exec(`DELETE FROM calendar_events WHERE id = ?`, id); err != nil {
		return err
	}
	if t.emit != nil {
		t.emit("timetable.changed", map[string]any{"deleted": id})
	}
	return nil
}
// Query 返回区间内的课程与本地日程。课程来自交我办日历，本地日程来自 calendar_events。
func (t *Timetable) Query(startValue, endValue, _ string) (map[string]any, error) {
	startAt, err := time.Parse(time.RFC3339, startValue)
	if err != nil {
		return nil, errors.New("开始时间格式无效")
	}
	endAt, err := time.Parse(time.RFC3339, endValue)
	if err != nil {
		return nil, errors.New("结束时间格式无效")
	}
	if !endAt.After(startAt) || endAt.Sub(startAt) > 366*24*time.Hour {
		return nil, errors.New("查询范围无效")
	}
	// school_events 统一存 UTC 的 RFC3339，用同格式边界做字符串比较。
	startISO := startAt.UTC().Format(time.RFC3339)
	endISO := endAt.UTC().Format(time.RFC3339)

	items := []map[string]any{}
	rows, err := t.db.Query(`SELECT title, start_at, end_at, location FROM school_events
		WHERE calendar_name = ? AND end_at > ? AND start_at < ? ORDER BY start_at`,
		courseCalendarName, startISO, endISO)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var title, start, end string
		var location sql.NullString
		if err := rows.Scan(&title, &start, &end, &location); err != nil {
			rows.Close()
			return nil, err
		}
		items = append(items, map[string]any{
			"source":   "course",
			"title":    title,
			"startAt":  start,
			"endAt":    end,
			"location": nullableText(location.String),
			"color":    colorForCourse(title),
		})
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, err
	}
	rows.Close()

	events, err := t.Events(startValue, endValue)
	if err != nil {
		return nil, err
	}
	for _, event := range events {
		items = append(items, map[string]any{
			"source":   "event",
			"eventId":  event.ID,
			"title":    event.Title,
			"startAt":  event.StartAt,
			"endAt":    event.EndAt,
			"location": event.Location,
			"color":    event.Color,
			"todoId":   event.TodoID,
		})
	}
	sort.SliceStable(items, func(i, j int) bool {
		return items[i]["startAt"].(string) < items[j]["startAt"].(string)
	})
	return map[string]any{"items": items}, nil
}

func (t *Timetable) Register(router *Router) {
	router.Handle("timetable.query", func(args []json.RawMessage) (any, error) {
		start, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		end, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return t.Query(start, end, "")
	})
	router.Handle("timetable.listEvents", func(args []json.RawMessage) (any, error) {
		start, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		end, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		return t.Events(start, end)
	})
	router.Handle("timetable.createEvent", func(args []json.RawMessage) (any, error) {
		input, err := DecodeArg[map[string]any](args, 0)
		if err != nil {
			return nil, err
		}
		return t.CreateEvent(input)
	})
	router.Handle("timetable.updateEvent", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		input, err := DecodeArg[map[string]any](args, 1)
		if err != nil {
			return nil, err
		}
		return t.UpdateEvent(id, input)
	})
	router.Handle("timetable.deleteEvent", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		return true, t.DeleteEvent(id)
	})
}
