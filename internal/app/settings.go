package app

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
)

type Experiments struct {
	VideoDownload bool `json:"videoDownload"`
	Shuiyuan      bool `json:"shuiyuan"`
}

// browserDefaultFontPx is the CSS root font size Chromium starts from.
const browserDefaultFontPx = 16

type SettingsState struct {
	Theme               string      `json:"theme"`
	FontScale           int         `json:"fontScale"`
	FontBasePx          float64     `json:"fontBasePx"`
	Zoom                float64     `json:"zoom"`
	OpenAtLogin         bool        `json:"openAtLogin"`
	CampusNotifications bool        `json:"campusNotifications"`
	TaskNotifications   bool        `json:"taskNotifications"`
	Experiments         Experiments `json:"experiments"`
	NavHidden           []string    `json:"navHidden"`
	OnboardingCompleted bool        `json:"onboardingCompleted"`
	ConsentVersion      string      `json:"consentVersion"`
	VideoDownloadDir    string      `json:"videoDownloadDir"`
	MailPollMinutes     int         `json:"mailPollMinutes"`
	AutoSyncMinutes     int         `json:"autoSyncMinutes"`
	UpdateManifestURL   string      `json:"updateManifestUrl"`
	CloseAction         string      `json:"closeAction"`
	SidebarCollapsed    bool        `json:"sidebarCollapsed"`
	LastView            string      `json:"lastView"`
}

var defaultSettings = map[string]string{
	"theme":               `"system"`,
	"fontScale":           `100`,
	"zoom":                `1`,
	"openAtLogin":         `false`,
	"campusNotifications": `true`,
	"taskNotifications":   `true`,
	"experiments":         `{"videoDownload":false,"shuiyuan":true}`,
	"navHidden":           `[]`,
	"onboardingCompleted": `false`,
	"consentVersion":      `""`,
	"videoDownloadDir":    `""`,
	"mailPollMinutes":     `5`,
	"autoSyncMinutes":     `30`,
	"updateManifestUrl":   `""`,
	"closeAction":         `"tray"`,
	"sidebarCollapsed":    `false`,
	"lastView":            `"dashboard"`,
}

var navEntryIDs = map[string]bool{
	"canvas": true, "mail": true, "timetable": true,
	"classroom": true, "shuiyuan": true, "cloudpan": true, "todo": true, "workspace": true,
}

var viewIDs = map[string]bool{
	"dashboard": true, "canvas": true, "mail": true, "timetable": true,
	"classroom": true, "shuiyuan": true, "cloudpan": true, "todo": true, "workspace": true,
	"settings": true, "notifications": true,
}

type Settings struct {
	mu         sync.RWMutex
	db         *sql.DB
	cache      map[string]string
	emit       func(string, any)
	fontBasePx float64
}

func NewSettings(db *sql.DB, emit func(string, any)) (*Settings, error) {
	s := &Settings{db: db, cache: make(map[string]string), emit: emit}
	for key, value := range defaultSettings {
		if _, err := db.Exec(
			`INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO NOTHING`,
			key, value, Now(),
		); err != nil {
			return nil, err
		}
	}
	rows, err := db.Query(`SELECT key, value FROM settings`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var key, value string
		if err := rows.Scan(&key, &value); err != nil {
			return nil, err
		}
		s.cache[key] = value
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if _, err := db.Exec(`DELETE FROM settings WHERE key = 'fontSize'`); err != nil {
		return nil, err
	}
	s.fontBasePx = systemFontBasePx()
	return s, nil
}

// Reload re-reads the settings table after a backup has been restored.
func (s *Settings) Reload() error {
	for key, value := range defaultSettings {
		if _, err := s.db.Exec(
			`INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?) ON CONFLICT(key) DO NOTHING`,
			key, value, Now(),
		); err != nil {
			return err
		}
	}
	rows, err := s.db.Query(`SELECT key, value FROM settings`)
	if err != nil {
		return err
	}
	defer rows.Close()
	next := make(map[string]string)
	for rows.Next() {
		var key, value string
		if err := rows.Scan(&key, &value); err != nil {
			return err
		}
		next[key] = value
	}
	if err := rows.Err(); err != nil {
		return err
	}
	s.mu.Lock()
	s.cache = next
	s.mu.Unlock()
	if s.emit != nil {
		s.emit("settings.changed", s.State())
	}
	return nil
}

func (s *Settings) Register(router *Router) {
	router.Handle("settings.state", func([]json.RawMessage) (any, error) {
		return s.State(), nil
	})
	router.Handle("settings.set", func(args []json.RawMessage) (any, error) {
		key, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		var value any
		if err := json.Unmarshal(args[1], &value); err != nil {
			return nil, fmt.Errorf("参数格式错误：%w", err)
		}
		if err := s.Set(key, value); err != nil {
			return nil, err
		}
		return s.State(), nil
	})
	router.Handle("settings.setMany", func(args []json.RawMessage) (any, error) {
		values, err := DecodeArg[map[string]any](args, 0)
		if err != nil {
			return nil, err
		}
		if err := s.SetMany(values); err != nil {
			return nil, err
		}
		return s.State(), nil
	})
}

func (s *Settings) State() SettingsState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return SettingsState{
		Theme:               s.stringLocked("theme", "system"),
		FontScale:           s.intLocked("fontScale", 100),
		FontBasePx:          s.fontBasePx,
		Zoom:                s.floatLocked("zoom", 1),
		OpenAtLogin:         s.boolLocked("openAtLogin", false),
		CampusNotifications: s.boolLocked("campusNotifications", true),
		TaskNotifications:   s.boolLocked("taskNotifications", true),
		Experiments:         s.experimentsLocked(),
		NavHidden:           s.stringsLocked("navHidden"),
		OnboardingCompleted: s.boolLocked("onboardingCompleted", false),
		ConsentVersion:      s.stringLocked("consentVersion", ""),
		VideoDownloadDir:    s.stringLocked("videoDownloadDir", ""),
		MailPollMinutes:     s.intLocked("mailPollMinutes", 5),
		AutoSyncMinutes:     s.intLocked("autoSyncMinutes", 30),
		UpdateManifestURL:   s.stringLocked("updateManifestUrl", ""),
		CloseAction:         s.stringLocked("closeAction", "tray"),
		SidebarCollapsed:    s.boolLocked("sidebarCollapsed", false),
		LastView:            s.stringLocked("lastView", "dashboard"),
	}
}

func (s *Settings) GetString(key, fallback string) string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.stringLocked(key, fallback)
}

func (s *Settings) GetBool(key string, fallback bool) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.boolLocked(key, fallback)
}

func (s *Settings) GetInt(key string, fallback int) int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.intLocked(key, fallback)
}

func (s *Settings) GetFloat(key string, fallback float64) float64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.floatLocked(key, fallback)
}

func (s *Settings) stringsLocked(key string) []string {
	var values []string
	if err := json.Unmarshal([]byte(s.cache[key]), &values); err != nil {
		return []string{}
	}
	return values
}

func (s *Settings) experimentsLocked() Experiments {
	var value Experiments
	_ = json.Unmarshal([]byte(s.cache["experiments"]), &value)
	if _, ok := s.cache["experiments"]; !ok {
		value.Shuiyuan = true
	}
	return value
}

func (s *Settings) stringLocked(key, fallback string) string {
	var value string
	if err := json.Unmarshal([]byte(s.cache[key]), &value); err != nil || value == "" {
		return fallback
	}
	return value
}

func (s *Settings) boolLocked(key string, fallback bool) bool {
	value, err := strconv.ParseBool(s.cache[key])
	if err != nil {
		return fallback
	}
	return value
}

func (s *Settings) intLocked(key string, fallback int) int {
	value, err := strconv.Atoi(s.cache[key])
	if err != nil {
		return fallback
	}
	return value
}

func (s *Settings) floatLocked(key string, fallback float64) float64 {
	value, err := strconv.ParseFloat(s.cache[key], 64)
	if err != nil {
		return fallback
	}
	return value
}

func (s *Settings) SetMany(values map[string]any) error {
	for key, value := range values {
		if err := s.Set(key, value); err != nil {
			return err
		}
	}
	return nil
}

func (s *Settings) Set(key string, value any) error {
	encoded, err := canonicalSetting(key, value)
	if err != nil {
		return err
	}
	if key == "openAtLogin" {
		enabled, _ := strconv.ParseBool(encoded)
		if err := setOpenAtLogin(enabled); err != nil {
			return err
		}
	}
	if _, err := s.db.Exec(
		`INSERT INTO settings (key, value, updated_at) VALUES (?, ?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
		key, encoded, Now(),
	); err != nil {
		return err
	}
	s.mu.Lock()
	s.cache[key] = encoded
	s.mu.Unlock()
	if s.emit != nil {
		s.emit("settings.changed", s.State())
	}
	return nil
}

func canonicalSetting(key string, value any) (string, error) {
	raw, err := json.Marshal(value)
	if err != nil {
		return "", err
	}
	switch key {
	case "closeAction":
		var action string
		if json.Unmarshal(raw, &action) != nil || (action != "tray" && action != "quit") {
			return "", errors.New("关闭行为只能是托盘或退出")
		}
	case "theme":
		var theme string
		if json.Unmarshal(raw, &theme) != nil || (theme != "system" && theme != "light" && theme != "dark") {
			return "", errors.New("主题取值无效")
		}
	case "fontScale":
		var scale int
		if json.Unmarshal(raw, &scale) != nil || scale < 70 || scale > 180 {
			return "", errors.New("字号需在 70%–180% 之间")
		}
	case "zoom":
		var zoom float64
		if json.Unmarshal(raw, &zoom) != nil || zoom < 0.8 || zoom > 1.5 {
			return "", errors.New("缩放需在 80%–150% 之间")
		}
		raw = []byte(strconv.FormatFloat(zoom, 'f', -1, 64))
	case "openAtLogin", "campusNotifications", "taskNotifications", "onboardingCompleted", "sidebarCollapsed":
		var flag bool
		if json.Unmarshal(raw, &flag) != nil {
			return "", errors.New("开关取值无效")
		}
	case "experiments":
		var exp Experiments
		if json.Unmarshal(raw, &exp) != nil {
			return "", errors.New("试验功能取值无效")
		}
	case "navHidden":
		var ids []string
		if json.Unmarshal(raw, &ids) != nil {
			return "", errors.New("导航入口取值无效")
		}
		for _, id := range ids {
			if !navEntryIDs[id] {
				return "", errors.New("导航入口取值无效")
			}
		}
	case "videoDownloadDir", "consentVersion":
		var text string
		if json.Unmarshal(raw, &text) != nil {
			return "", errors.New("路径取值无效")
		}
	case "mailPollMinutes", "autoSyncMinutes":
		var minutes int
		if json.Unmarshal(raw, &minutes) != nil || minutes < 1 || minutes > 120 {
			return "", errors.New("同步间隔需在 1–120 分钟之间")
		}
	case "updateManifestUrl":
		var url string
		if json.Unmarshal(raw, &url) != nil {
			return "", errors.New("更新地址无效")
		}
		if url != "" && !strings.HasPrefix(url, "https://") {
			return "", errors.New("更新地址必须是 https")
		}
	case "lastView":
		var view string
		if json.Unmarshal(raw, &view) != nil || !viewIDs[view] {
			return "", errors.New("视图取值无效")
		}
	default:
		return "", fmt.Errorf("未知设置项：%s", key)
	}
	return string(raw), nil
}

func (s *Settings) ApplySystemState() {
	if s.GetBool("openAtLogin", false) {
		_ = setOpenAtLogin(true)
	}
}
