package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	campusOrigin = "https://i.sjtu.edu.cn"
	timetableURL = campusOrigin + "/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"
)

var classPeriods = [][2]int{
	{8 * 60, 8*60 + 45},
	{8*60 + 55, 9*60 + 40},
	{10 * 60, 10*60 + 45},
	{10*60 + 55, 11*60 + 40},
	{12 * 60, 12*60 + 45},
	{12*60 + 55, 13*60 + 40},
	{14 * 60, 14*60 + 45},
	{14*60 + 55, 15*60 + 40},
	{16 * 60, 16*60 + 45},
	{16*60 + 55, 17*60 + 40},
	{18 * 60, 18*60 + 45},
	{18*60 + 55, 19*60 + 40},
	{19*60 + 50, 20*60 + 20},
}

type TimetableLesson struct {
	ID          int64    `json:"id"`
	Semester    string   `json:"semester"`
	CourseCode  string   `json:"courseCode"`
	CourseName  string   `json:"courseName"`
	ClassName   string   `json:"className"`
	Teacher     string   `json:"teacher"`
	Campus      string   `json:"campus"`
	Classroom   string   `json:"classroom"`
	Weekday     int      `json:"weekday"`
	StartPeriod int      `json:"startPeriod"`
	EndPeriod   int      `json:"endPeriod"`
	PeriodsText string   `json:"periodsText"`
	WeeksText   string   `json:"weeksText"`
	WeekNumbers []int    `json:"weekNumbers"`
	Credits     *float64 `json:"credits"`
	Note        string   `json:"note"`
}

type CalendarEvent struct {
	ID       int64   `json:"id"`
	Title    string  `json:"title"`
	StartAt  string  `json:"startAt"`
	EndAt    string  `json:"endAt"`
	Location *string `json:"location"`
	Color    *string `json:"color"`
	TodoID   *string `json:"todoId"`
}

type TimetableState struct {
	Semester      string   `json:"semester"`
	Semesters     []string `json:"semesters"`
	LastSyncedAt  string   `json:"lastSyncedAt"`
	SemesterStart string   `json:"semesterStart"`
	Connected     bool     `json:"connected"`
}

type TimetableResult struct {
	OK     bool   `json:"ok"`
	Error  string `json:"error,omitempty"`
	Reason string `json:"reason,omitempty"`
}

type Timetable struct {
	db     *sql.DB
	campus *CampusSession
	emit   func(string, any)
}

func NewTimetable(db *sql.DB, campus *CampusSession, emit func(string, any)) *Timetable {
	return &Timetable{db: db, campus: campus, emit: emit}
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

func resolveSemester(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return currentSemester(time.Now()), nil
	}
	matched, _ := regexp.MatchString(`^\d{4}-\d{4}-[123]$`, value)
	if !matched {
		return "", errors.New("学期格式无效")
	}
	return value, nil
}

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

func weekNumbersOf(value string) []int {
	if strings.TrimSpace(value) == "" {
		return []int{}
	}
	odd := strings.Contains(value, "单")
	even := strings.Contains(value, "双")
	clean := strings.NewReplacer("周", "", "(", "", ")", "", "（", "", "）", "", "单", "", "双", "").Replace(value)
	result := map[int]bool{}
	for _, part := range strings.FieldsFunc(clean, func(r rune) bool {
		return r == ',' || r == '，' || r == '、'
	}) {
		numbers := findNumbers(part)
		if len(numbers) == 0 {
			continue
		}
		start := numbers[0]
		end := start
		if len(numbers) > 1 {
			end = numbers[1]
		}
		if end < start {
			start, end = end, start
		}
		for week := start; week <= end && week <= 30; week++ {
			if odd && week%2 == 0 {
				continue
			}
			if even && week%2 == 1 {
				continue
			}
			result[week] = true
		}
	}
	weeks := make([]int, 0, len(result))
	for week := range result {
		weeks = append(weeks, week)
	}
	sort.Ints(weeks)
	return weeks
}

func findNumbers(value string) []int {
	re := regexp.MustCompile(`\d+`)
	matches := re.FindAllString(value, -1)
	numbers := make([]int, 0, len(matches))
	for _, match := range matches {
		if number, err := strconv.Atoi(match); err == nil {
			numbers = append(numbers, number)
		}
	}
	return numbers
}

func periodsOf(values ...string) (int, int, string) {
	for _, value := range values {
		if strings.TrimSpace(value) == "" {
			continue
		}
		numbers := findNumbers(value)
		if len(numbers) == 0 {
			continue
		}
		start := numbers[0]
		end := start
		if len(numbers) > 1 {
			end = numbers[len(numbers)-1]
		}
		if start < 1 {
			start = 1
		}
		if start > 20 {
			start = 20
		}
		if end < start {
			end = start
		}
		if end > 20 {
			end = 20
		}
		return start, end, strings.TrimSpace(value)
	}
	return 1, 1, ""
}

var semesterDatePattern = regexp.MustCompile(`20\d{2}[-/.]\d{1,2}[-/.]\d{1,2}`)

func parseSemesterStart(values []json.RawMessage) string {
	best := ""
	for _, raw := range values {
		text := string(raw)
		for _, match := range semesterDatePattern.FindAllString(text, -1) {
			parts := strings.FieldsFunc(match, func(r rune) bool { return r == '-' || r == '/' || r == '.' })
			if len(parts) != 3 {
				continue
			}
			year, _ := strconv.Atoi(parts[0])
			month, _ := strconv.Atoi(parts[1])
			day, _ := strconv.Atoi(parts[2])
			if year == 0 || month < 1 || month > 12 || day < 1 || day > 31 {
				continue
			}
			date := time.Date(year, time.Month(month), day, 0, 0, 0, 0, time.Local)
			if date.Year() != year || int(date.Month()) != month || date.Day() != day {
				continue
			}
			value := date.Format("2006-01-02")
			if best == "" || value < best {
				best = value
			}
		}
	}
	return best
}

type rawKBLesson struct {
	CDMC  string `json:"cdmc"`
	JC    string `json:"jc"`
	JCS   string `json:"jcs"`
	JXBID string `json:"jxb_id"`
	JXBMC string `json:"jxbmc"`
	KCH   string `json:"kch"`
	KCHID string `json:"kch_id"`
	KCMC  string `json:"kcmc"`
	XF    string `json:"xf"`
	XKBZ  string `json:"xkbz"`
	XM    string `json:"xm"`
	XQJ   string `json:"xqj"`
	XQMC  string `json:"xqmc"`
	ZCD   string `json:"zcd"`
}

func (t *Timetable) Sync(ctx context.Context, requested string) TimetableResult {
	semester, err := resolveSemester(requested)
	if err != nil {
		return TimetableResult{OK: false, Error: err.Error()}
	}
	if !t.campus.Connected() {
		return TimetableResult{OK: false, Error: "请先登录教学信息服务网", Reason: "auth"}
	}
	xnm, xqm := semesterParams(semester)
	body := fmt.Sprintf("xnm=%s&xqm=%s&kzlx=ck&xsdm=", xnm, xqm)
	data, err := t.campus.Request(ctx, http.MethodPost, timetableURL, map[string]string{
		"Content-Type":     "application/x-www-form-urlencoded; charset=UTF-8",
		"X-Requested-With": "XMLHttpRequest",
		"Referer":          campusOrigin + "/kbcx/xskbcx_cxXsgrkbIndex.html?gnmkdm=N2151",
	}, body)
	if err != nil {
		reason := ""
		if errors.Is(err, ErrCampusAuth) {
			reason = "auth"
			err = errors.New("登录已失效，请重新登录教学信息服务网")
		}
		return TimetableResult{OK: false, Error: err.Error(), Reason: reason}
	}
	var payload struct {
		KBList    []rawKBLesson     `json:"kbList"`
		RQAZCList []json.RawMessage `json:"rqazcList"`
	}
	if err := json.Unmarshal(data, &payload); err != nil {
		return TimetableResult{OK: false, Error: "课表接口没有返回预期数据，请重新登录后再试"}
	}
	semesterStart := parseSemesterStart(payload.RQAZCList)
	lessons := make([]TimetableLesson, 0, len(payload.KBList))
	for index, raw := range payload.KBList {
		name := strings.TrimSpace(raw.KCMC)
		weekday, _ := strconv.Atoi(strings.TrimSpace(raw.XQJ))
		if name == "" || weekday < 1 || weekday > 7 {
			continue
		}
		start, end, periodsText := periodsOf(raw.JCS, raw.JC)
		weeksText := strings.TrimSpace(raw.ZCD)
		if weeksText == "" {
			weeksText = "周次未提供"
		}
		sourceID := strings.TrimSpace(raw.JXBID)
		if sourceID == "" {
			sourceID = strings.TrimSpace(raw.KCHID)
		}
		if sourceID == "" {
			sourceID = fmt.Sprintf("%s-%d", name, index)
		}
		credits := (*float64)(nil)
		if value, err := strconv.ParseFloat(strings.TrimSpace(raw.XF), 64); err == nil {
			credits = &value
		}
		lessons = append(lessons, TimetableLesson{
			Semester:    semester,
			CourseCode:  strings.TrimSpace(raw.KCH),
			CourseName:  name,
			ClassName:   nullText(raw.JXBMC),
			Teacher:     nullText(raw.XM),
			Campus:      nullText(raw.XQMC),
			Classroom:   nullText(raw.CDMC),
			Weekday:     weekday,
			StartPeriod: start,
			EndPeriod:   end,
			PeriodsText: periodsText,
			WeeksText:   weeksText,
			WeekNumbers: weekNumbersOf(weeksText),
			Credits:     credits,
			Note:        nullText(raw.XKBZ),
		})
	}
	syncedAt := Now()
	err = Tx(ctx, t.db, func(tx *sql.Tx) error {
		if _, err := tx.Exec(`DELETE FROM timetable_lessons WHERE semester = ?`, semester); err != nil {
			return err
		}
		statement, err := tx.Prepare(`INSERT INTO timetable_lessons
			(semester, source_id, course_code, course_name, class_name, teacher, campus, classroom,
			 weekday, start_period, end_period, periods_text, weeks_text, week_numbers, credits, note)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`)
		if err != nil {
			return err
		}
		defer statement.Close()
		for _, lesson := range lessons {
			weeks, _ := json.Marshal(lesson.WeekNumbers)
			var credits any
			if lesson.Credits != nil {
				credits = *lesson.Credits
			}
			if _, err := statement.Exec(
				lesson.Semester, lesson.CourseCode+"|"+lesson.ClassName, lesson.CourseCode, lesson.CourseName,
				lesson.ClassName, lesson.Teacher, lesson.Campus, lesson.Classroom,
				lesson.Weekday, lesson.StartPeriod, lesson.EndPeriod, lesson.PeriodsText,
				lesson.WeeksText, string(weeks), credits, nullableText(lesson.Note),
			); err != nil {
				return err
			}
		}
		_, err = tx.Exec(`INSERT INTO timetable_sync (semester, last_synced_at, semester_start) VALUES (?, ?, ?)
			ON CONFLICT(semester) DO UPDATE SET
			  last_synced_at = excluded.last_synced_at,
			  semester_start = COALESCE(excluded.semester_start, timetable_sync.semester_start)`,
			semester, syncedAt, nullableText(semesterStart))
		return err
	})
	if err != nil {
		return TimetableResult{OK: false, Error: err.Error()}
	}
	if t.emit != nil {
		t.emit("timetable.changed", map[string]any{"semester": semester})
	}
	return TimetableResult{OK: true}
}

func nullText(value string) string {
	return strings.TrimSpace(value)
}

func nullableText(value string) any {
	if strings.TrimSpace(value) == "" {
		return nil
	}
	return strings.TrimSpace(value)
}

func (t *Timetable) State(requested string) (TimetableState, error) {
	semester, err := resolveSemester(requested)
	if err != nil {
		return TimetableState{}, err
	}
	var lastSynced, semesterStart sql.NullString
	_ = t.db.QueryRow(`SELECT last_synced_at, semester_start FROM timetable_sync WHERE semester = ?`, semester).
		Scan(&lastSynced, &semesterStart)
	semesters := t.semesterList()
	return TimetableState{
		Semester:      semester,
		Semesters:     semesters,
		LastSyncedAt:  lastSynced.String,
		SemesterStart: semesterStart.String,
		Connected:     t.campus.Connected(),
	}, nil
}

func (t *Timetable) semesterList() []string {
	rows, err := t.db.Query(`SELECT semester FROM timetable_sync
		UNION SELECT semester FROM timetable_lessons ORDER BY semester DESC`)
	if err != nil {
		return []string{currentSemester(time.Now())}
	}
	defer rows.Close()
	values := []string{}
	seen := map[string]bool{}
	for rows.Next() {
		var value string
		if rows.Scan(&value) == nil && !seen[value] {
			seen[value] = true
			values = append(values, value)
		}
	}
	current := currentSemester(time.Now())
	if !seen[current] {
		values = append([]string{current}, values...)
	}
	return values
}

func (t *Timetable) Lessons(requested string) ([]TimetableLesson, error) {
	semester, err := resolveSemester(requested)
	if err != nil {
		return nil, err
	}
	rows, err := t.db.Query(`SELECT id, semester, course_code, course_name, class_name, teacher, campus, classroom,
		weekday, start_period, end_period, periods_text, weeks_text, week_numbers, credits, note
		FROM timetable_lessons WHERE semester = ? ORDER BY weekday, start_period, course_name`, semester)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := []TimetableLesson{}
	for rows.Next() {
		var lesson TimetableLesson
		var className, teacher, campusName, classroom, weeks, note sql.NullString
		var credits sql.NullFloat64
		if err := rows.Scan(
			&lesson.ID, &lesson.Semester, &lesson.CourseCode, &lesson.CourseName,
			&className, &teacher, &campusName, &classroom, &lesson.Weekday,
			&lesson.StartPeriod, &lesson.EndPeriod, &lesson.PeriodsText,
			&lesson.WeeksText, &weeks, &credits, &note,
		); err != nil {
			return nil, err
		}
		_ = json.Unmarshal([]byte(weeks.String), &lesson.WeekNumbers)
		lesson.ClassName = className.String
		lesson.Teacher = teacher.String
		lesson.Campus = campusName.String
		lesson.Classroom = classroom.String
		lesson.Note = note.String
		if credits.Valid {
			value := credits.Float64
			lesson.Credits = &value
		}
		result = append(result, lesson)
	}
	return result, rows.Err()
}

func (t *Timetable) Query(startValue, endValue, requested string) (map[string]any, error) {
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
	semester, err := resolveSemester(requested)
	if err != nil {
		return nil, err
	}
	lessons, err := t.Lessons(semester)
	if err != nil {
		return nil, err
	}
	var semesterStart sql.NullString
	_ = t.db.QueryRow(`SELECT semester_start FROM timetable_sync WHERE semester = ?`, semester).Scan(&semesterStart)
	items := []map[string]any{}
	warnings := []string{}
	if semesterStart.String == "" {
		warnings = append(warnings, "缺少学期开始日期，无法把课程转换为实际日期")
	} else {
		startDate, err := time.ParseInLocation("2006-01-02", semesterStart.String, time.Local)
		if err != nil {
			warnings = append(warnings, "学期开始日期无效")
		} else {
			monday := startDate.AddDate(0, 0, -((int(startDate.Weekday()) + 6) % 7))
			for _, lesson := range lessons {
				if len(lesson.WeekNumbers) == 0 {
					warnings = append(warnings, fmt.Sprintf("%s：缺少有效周次，未计入时间安排", lesson.CourseName))
					continue
				}
				if lesson.StartPeriod < 1 || lesson.EndPeriod > len(classPeriods) {
					warnings = append(warnings, fmt.Sprintf("%s：节次超出范围，未计入时间安排", lesson.CourseName))
					continue
				}
				startMinutes := classPeriods[lesson.StartPeriod-1][0]
				endMinutes := classPeriods[lesson.EndPeriod-1][1]
				for _, week := range lesson.WeekNumbers {
					date := monday.AddDate(0, 0, (week-1)*7+(lesson.Weekday-1))
					begin := time.Date(date.Year(), date.Month(), date.Day(), 0, startMinutes, 0, 0, time.Local)
					finish := time.Date(date.Year(), date.Month(), date.Day(), 0, endMinutes, 0, 0, time.Local)
					if finish.After(startAt) && begin.Before(endAt) {
						items = append(items, map[string]any{
							"source":   "course",
							"lessonId": lesson.ID,
							"title":    lesson.CourseName,
							"startAt":  begin.Format(time.RFC3339),
							"endAt":    finish.Format(time.RFC3339),
							"location": nullableText(lesson.Classroom),
							"color":    colorForCourse(lesson.CourseCode + lesson.CourseName),
							"teacher":  nullableText(lesson.Teacher),
						})
					}
				}
			}
		}
	}
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
		return fmt.Sprint(items[i]["startAt"]) < fmt.Sprint(items[j]["startAt"])
	})
	return map[string]any{
		"semester": semester,
		"items":    items,
		"warnings": warnings,
	}, nil
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

func (t *Timetable) SetSemesterStart(requested, value string) error {
	semester, err := resolveSemester(requested)
	if err != nil {
		return err
	}
	date, err := time.ParseInLocation("2006-01-02", value, time.Local)
	if err != nil {
		return errors.New("日期格式无效")
	}
	_, err = t.db.Exec(`INSERT INTO timetable_sync (semester, last_synced_at, semester_start) VALUES (?, '', ?)
		ON CONFLICT(semester) DO UPDATE SET semester_start = excluded.semester_start`,
		semester, date.Format("2006-01-02"))
	if err != nil {
		return err
	}
	if t.emit != nil {
		t.emit("timetable.changed", map[string]any{"semester": semester})
	}
	return nil
}

func (t *Timetable) Register(router *Router) {
	router.Handle("timetable.state", func(args []json.RawMessage) (any, error) {
		semester, _, err := DecodeOptionalArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return t.State(semester)
	})
	router.Handle("timetable.listLessons", func(args []json.RawMessage) (any, error) {
		semester, _, err := DecodeOptionalArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		return t.Lessons(semester)
	})
	router.Handle("timetable.sync", func(args []json.RawMessage) (any, error) {
		semester, _, err := DecodeOptionalArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		return t.Sync(ctx, semester), nil
	})
	router.Handle("timetable.connect", func([]json.RawMessage) (any, error) {
		ctx, cancel := context.WithTimeout(context.Background(), 6*time.Minute)
		defer cancel()
		if _, err := t.campus.Login(ctx); err != nil {
			return TimetableResult{OK: false, Error: err.Error(), Reason: "auth"}, nil
		}
		return t.Sync(ctx, ""), nil
	})
	router.Handle("timetable.disconnect", func([]json.RawMessage) (any, error) {
		t.campus.Logout()
		return TimetableResult{OK: true}, nil
	})
	router.Handle("timetable.query", func(args []json.RawMessage) (any, error) {
		start, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		end, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		semester, _, err := DecodeOptionalArg[string](args, 2)
		if err != nil {
			return nil, err
		}
		return t.Query(start, end, semester)
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
	router.Handle("timetable.setSemesterStart", func(args []json.RawMessage) (any, error) {
		semester, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		value, err := DecodeArg[string](args, 1)
		if err != nil {
			return nil, err
		}
		if err := t.SetSemesterStart(semester, value); err != nil {
			return nil, err
		}
		return t.State(semester)
	})
}
