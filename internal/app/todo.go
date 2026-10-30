package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

type TodoItem struct {
	ID              string  `json:"id"`
	Title           string  `json:"title"`
	Notes           string  `json:"notes"`
	DueAt           *string `json:"dueAt"`
	Done            bool    `json:"done"`
	Source          string  `json:"source"`
	SourceID        *string `json:"sourceId"`
	CourseID        *int64  `json:"courseId"`
	CourseName      *string `json:"courseName"`
	CreatedAt       string  `json:"createdAt"`
	UpdatedAt       string  `json:"updatedAt"`
	ReadAt          *string `json:"readAt"`
	CalendarEventID *int64  `json:"calendarEventId"`
}

type TodoState struct {
	Items     []TodoItem `json:"items"`
	Today     int        `json:"today"`
	Overdue   int        `json:"overdue"`
	Completed int        `json:"completed"`
}

type Todo struct {
	db   *sql.DB
	emit func(string, any)
}

func NewTodo(db *sql.DB, emit func(string, any)) *Todo {
	return &Todo{db: db, emit: emit}
}

func (t *Todo) List() ([]TodoItem, error) {
	rows, err := t.db.Query(`SELECT t.id, t.title, t.notes, t.due_at, t.done, t.source, t.source_id, t.course_id, t.course_name,
		t.created_at, t.updated_at, t.read_at, e.id FROM todo_items t
		LEFT JOIN calendar_events e ON e.todo_id = t.id
		ORDER BY t.done ASC, CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END, t.due_at ASC, t.created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := []TodoItem{}
	for rows.Next() {
		var item TodoItem
		var dueAt, sourceID, courseName, readAt sql.NullString
		var courseID, eventID sql.NullInt64
		var done int
		if err := rows.Scan(&item.ID, &item.Title, &item.Notes, &dueAt, &done, &item.Source,
			&sourceID, &courseID, &courseName, &item.CreatedAt, &item.UpdatedAt, &readAt, &eventID); err != nil {
			return nil, err
		}
		item.Done = done == 1
		if dueAt.Valid {
			value := dueAt.String
			item.DueAt = &value
		}
		if sourceID.Valid {
			value := sourceID.String
			item.SourceID = &value
		}
		if courseID.Valid {
			value := courseID.Int64
			item.CourseID = &value
		}
		if courseName.Valid {
			value := courseName.String
			item.CourseName = &value
		}
		if readAt.Valid {
			value := readAt.String
			item.ReadAt = &value
		}
		if eventID.Valid {
			value := eventID.Int64
			item.CalendarEventID = &value
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (t *Todo) State() (TodoState, error) {
	items, err := t.List()
	if err != nil {
		return TodoState{}, err
	}
	state := TodoState{Items: items}
	now := time.Now()
	start := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.Local)
	end := start.AddDate(0, 0, 1)
	for _, item := range items {
		if item.Done {
			state.Completed++
			continue
		}
		if item.DueAt == nil {
			continue
		}
		due, err := time.Parse(time.RFC3339, *item.DueAt)
		if err != nil {
			continue
		}
		if due.Before(now) {
			state.Overdue++
		}
		if !due.Before(start) && due.Before(end) {
			state.Today++
		}
	}
	return state, nil
}

func (t *Todo) emitState() {
	if t.emit == nil {
		return
	}
	if state, err := t.State(); err == nil {
		t.emit("todo.state", state)
	}
}

func parseDueAt(value any) (string, bool, error) {
	if value == nil {
		return "", false, nil
	}
	text := strings.TrimSpace(fmt.Sprint(value))
	if text == "" {
		return "", false, nil
	}
	if len(text) == 10 {
		date, err := time.ParseInLocation("2006-01-02", text, time.Local)
		if err != nil {
			return "", false, errors.New("截止日期格式无效")
		}
		date = time.Date(date.Year(), date.Month(), date.Day(), 23, 59, 0, 0, time.Local)
		return date.Format(time.RFC3339), true, nil
	}
	date, err := time.Parse(time.RFC3339, text)
	if err != nil {
		return "", false, errors.New("截止时间格式无效")
	}
	return date.Format(time.RFC3339), true, nil
}

func (t *Todo) Save(input map[string]any) (TodoItem, error) {
	id := strings.TrimSpace(inputText(input, "id"))
	title := strings.TrimSpace(inputText(input, "title"))
	notes := strings.TrimSpace(inputText(input, "notes"))
	if title == "" || len([]rune(title)) > 200 {
		return TodoItem{}, errors.New("标题不能为空且不超过 200 字")
	}
	if len([]rune(notes)) > 8000 {
		return TodoItem{}, errors.New("备注不能超过 8000 字")
	}
	dueAt, hasDue, err := parseDueAt(input["dueAt"])
	if err != nil {
		return TodoItem{}, err
	}
	done, _ := input["done"].(bool)
	addToCalendar, _ := input["addToCalendar"].(bool)
	now := Now()
	created := now
	if id == "" {
		id = "todo-" + randomID()[:20]
	}
	var existingCreated string
	var existingManual bool
	err = t.db.QueryRow(`SELECT created_at, manual_done FROM todo_items WHERE id = ?`, id).
		Scan(&existingCreated, &existingManual)
	exists := err == nil
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return TodoItem{}, err
	}
	if exists {
		created = existingCreated
		manualDone := 1
		if !done {
			manualDone = 1
		}
		_, err = t.db.Exec(`UPDATE todo_items SET title = ?, notes = ?, due_at = ?, done = ?, done_at = ?,
			manual_done = ?, updated_at = ?, read_at = ? WHERE id = ?`,
			title, notes, nullableText(dueAt), BoolInt(done), doneAtValue(done),
			manualDone, now, now, id)
	} else {
		source := strings.TrimSpace(inputText(input, "source"))
		if source == "" {
			source = "local"
		}
		var courseID any
		var courseName any
		if value, ok := input["courseId"]; ok && value != nil {
			courseID = value
		}
		if value := strings.TrimSpace(inputText(input, "courseName")); value != "" {
			courseName = value
		}
		_, err = t.db.Exec(`INSERT INTO todo_items
			(id, title, notes, due_at, done, done_at, manual_done, source, course_id, course_name, created_at, updated_at, read_at)
			VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)`,
			id, title, notes, nullableText(dueAt), BoolInt(done), doneAtValue(done),
			source, courseID, courseName, created, now, now)
	}
	if err != nil {
		return TodoItem{}, err
	}
	if hasDue && addToCalendar {
		if err := t.upsertCalendarEvent(id, title, dueAt); err != nil {
			return TodoItem{}, err
		}
	} else {
		_, _ = t.db.Exec(`DELETE FROM calendar_events WHERE todo_id = ?`, id)
	}
	t.emitState()
	if t.emit != nil {
		t.emit("timetable.changed", map[string]any{"todoId": id})
	}
	return t.get(id)
}

func doneAtValue(done bool) any {
	if done {
		return Now()
	}
	return nil
}

func (t *Todo) upsertCalendarEvent(todoID, title, dueAt string) error {
	due, err := time.Parse(time.RFC3339, dueAt)
	if err != nil {
		return err
	}
	start := due.Add(-30 * time.Minute)
	end := due
	if due.Hour() == 23 && due.Minute() == 59 {
		start = time.Date(due.Year(), due.Month(), due.Day(), 9, 0, 0, 0, time.Local)
		end = start.Add(30 * time.Minute)
	}
	now := Now()
	var eventID int64
	err = t.db.QueryRow(`SELECT id FROM calendar_events WHERE todo_id = ?`, todoID).Scan(&eventID)
	if errors.Is(err, sql.ErrNoRows) {
		_, err = t.db.Exec(`INSERT INTO calendar_events (title, start_at, end_at, location, color, todo_id, created_at, updated_at)
			VALUES (?, ?, ?, '', 'sand', ?, ?, ?)`,
			title, start.Format(time.RFC3339), end.Format(time.RFC3339), todoID, now, now)
		return err
	}
	if err != nil {
		return err
	}
	_, err = t.db.Exec(`UPDATE calendar_events SET title = ?, start_at = ?, end_at = ?, updated_at = ? WHERE id = ?`,
		title, start.Format(time.RFC3339), end.Format(time.RFC3339), now, eventID)
	return err
}

func (t *Todo) get(id string) (TodoItem, error) {
	items, err := t.List()
	if err != nil {
		return TodoItem{}, err
	}
	for _, item := range items {
		if item.ID == id {
			return item, nil
		}
	}
	return TodoItem{}, errors.New("待办不存在")
}

func (t *Todo) Remove(id string) error {
	if strings.TrimSpace(id) == "" {
		return errors.New("缺少待办 id")
	}
	if _, err := t.db.Exec(`DELETE FROM calendar_events WHERE todo_id = ?`, id); err != nil {
		return err
	}
	if _, err := t.db.Exec(`DELETE FROM todo_items WHERE id = ?`, id); err != nil {
		return err
	}
	t.emitState()
	if t.emit != nil {
		t.emit("timetable.changed", map[string]any{"deletedTodo": id})
	}
	return nil
}

func (t *Todo) SetDone(id string, done bool) error {
	if strings.TrimSpace(id) == "" {
		return errors.New("缺少待办 id")
	}
	_, err := t.db.Exec(`UPDATE todo_items SET done = ?, done_at = ?, manual_done = 1, updated_at = ? WHERE id = ?`,
		BoolInt(done), doneAtValue(done), Now(), id)
	if err == nil {
		t.emitState()
		if t.emit != nil {
			t.emit("timetable.changed", map[string]any{"todoId": id})
		}
	}
	return err
}

func (t *Todo) MarkRead(id string) error {
	_, err := t.db.Exec(`UPDATE todo_items SET read_at = ? WHERE id = ? AND read_at IS NULL`, Now(), id)
	if err == nil {
		t.emitState()
	}
	return err
}

func (t *Todo) Refresh() (TodoState, error) {
	if err := t.importCanvasAssignments(); err != nil {
		return TodoState{}, err
	}
	state, err := t.State()
	if err != nil {
		return TodoState{}, err
	}
	if t.emit != nil {
		t.emit("todo.state", state)
	}
	return state, nil
}

func (t *Todo) importCanvasAssignments() error {
	rows, err := t.db.Query(`SELECT s.course_id, s.name, s.due_at, s.source_id, s.data, c.name
		FROM canvas_sources s JOIN canvas_courses c ON c.id = s.course_id
		WHERE s.kind = 'assignment' AND s.due_at IS NOT NULL AND c.archived = 0`)
	if err != nil {
		return err
	}
	defer rows.Close()
	now := Now()
	type assignment struct {
		courseID   int64
		courseName string
		name       string
		dueAt      string
		sourceID   string
		state      string
	}
	assignments := []assignment{}
	for rows.Next() {
		var item assignment
		var data string
		var assignmentID sql.NullString
		if err := rows.Scan(&item.courseID, &item.name, &item.dueAt, &assignmentID, &data, &item.courseName); err != nil {
			return err
		}
		item.sourceID = assignmentID.String
		var payload map[string]any
		_ = json.Unmarshal([]byte(data), &payload)
		if value, ok := payload["submissionState"].(string); ok {
			item.state = value
		}
		assignments = append(assignments, item)
	}
	if err := rows.Err(); err != nil {
		_ = rows.Close()
		return err
	}
	_ = rows.Close()
	tx, err := t.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, item := range assignments {
		if item.sourceID == "" {
			continue
		}
		done := item.state == "submitted" || item.state == "graded" || item.state == "pending_review"
		id := fmt.Sprintf("canvas-%d-%s", item.courseID, item.sourceID)
		_, err := tx.Exec(`INSERT INTO todo_items
			(id, title, notes, due_at, done, done_at, manual_done, source, source_id, course_id, course_name, created_at, updated_at)
			VALUES (?, ?, '', ?, ?, ?, 0, 'canvas', ?, ?, ?, ?, ?)
			ON CONFLICT DO UPDATE SET
			  title = excluded.title,
			  due_at = excluded.due_at,
			  course_id = excluded.course_id,
			  course_name = excluded.course_name,
			  done = CASE WHEN todo_items.manual_done = 1 THEN todo_items.done ELSE excluded.done END,
			  updated_at = excluded.updated_at`,
			id, item.name, item.dueAt, BoolInt(done), doneAtValue(done),
			item.sourceID, item.courseID, item.courseName, now, now)
		if err != nil {
			return err
		}
	}
	return tx.Commit()
}

func (t *Todo) Register(router *Router) {
	router.Handle("todo.list", func([]json.RawMessage) (any, error) {
		return t.List()
	})
	router.Handle("todo.state", func([]json.RawMessage) (any, error) {
		return t.State()
	})
	router.Handle("todo.refresh", func([]json.RawMessage) (any, error) {
		return t.Refresh()
	})
	router.Handle("todo.save", func(args []json.RawMessage) (any, error) {
		input, err := DecodeArg[map[string]any](args, 0)
		if err != nil {
			return nil, err
		}
		return t.Save(input)
	})
	router.Handle("todo.remove", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return true, t.Remove(id)
	})
	router.Handle("todo.setDone", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		done, err := DecodeArg[bool](args, 1)
		if err != nil {
			return nil, err
		}
		return true, t.SetDone(id, done)
	})
	router.Handle("todo.markRead", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return true, t.MarkRead(id)
	})
}
