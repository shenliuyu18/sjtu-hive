package app

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	goruntime "runtime"
	"strings"
	"sync/atomic"
	"time"

	wruntime "github.com/wailsapp/wails/v2/pkg/runtime"
)

type AppInfo struct {
	Version      string `json:"version"`
	Name         string `json:"name"`
	Platform     string `json:"platform"`
	Arch         string `json:"arch"`
	GoVersion    string `json:"goVersion"`
	WailsVersion string `json:"wailsVersion"`
	DataDir      string `json:"dataDir"`
	CacheDir     string `json:"cacheDir"`
	LogDir       string `json:"logDir"`
	DownloadsDir string `json:"downloadsDir"`
	ResourceBase string `json:"resourceBase"`
	StartedAt    string `json:"startedAt"`
}

type App struct {
	ctx            context.Context
	cancel         context.CancelFunc
	paths          Paths
	db             *sql.DB
	logger         *slog.Logger
	closeLog       func()
	secrets        *SecretStore
	settings       *Settings
	media          *MediaServer
	router         *Router
	campus         *CampusSession
	timetable      *Timetable
	todo           *Todo
	workspace      *Workspace
	canvas         *Canvas
	mail           *Mail
	classroom      *Classroom
	shuiyuan       *Shuiyuan
	schoolCalendar *SchoolCalendar
	portalMessages *PortalMessages
	cloudPan       *CloudPan
	exams          *Exams
	search         *Search
	notifications  *Notifications
	update         *Update
	tray           *Tray
	quitRequested  atomic.Bool
	trayHintShown  atomic.Bool
	startErr       error
	started        time.Time
}

func NewApp() *App {
	return &App{started: time.Now()}
}

func (a *App) Startup(ctx context.Context) {
	a.ctx = ctx
	a.ctx, a.cancel = context.WithCancel(ctx)
	paths, err := LoadPaths()
	if err != nil {
		a.startErr = fmt.Errorf("初始化数据目录失败：%w", err)
		slog.Error("load paths", "error", err)
		return
	}
	a.paths = paths
	logger, closeLog, err := SetupLogging(paths.LogDir)
	if err != nil {
		a.startErr = fmt.Errorf("初始化日志失败：%w", err)
		return
	}
	a.logger = logger
	a.closeLog = closeLog
	restored, restoreErr := restoreLocalBackup(paths, false, logger)
	if restoreErr != nil {
		logger.Warn("restore local backup failed", "error", restoreErr)
	}
	db, err := OpenStore(filepath.Join(paths.DataDir, "sjtu.db"))
	if err != nil {
		a.startErr = fmt.Errorf("打开本地数据库失败：%w", err)
		logger.Error("open store", "error", err)
		return
	}
	a.db = db
	if restored {
		logger.Info("restored local backup")
	}
	secrets, err := NewSecretStore(paths.SecretsDir)
	if err != nil {
		a.startErr = fmt.Errorf("初始化凭据存储失败：%w", err)
		return
	}
	a.secrets = secrets
	a.router = NewRouter()
	settings, err := NewSettings(db, a.emit)
	if err != nil {
		a.startErr = fmt.Errorf("读取设置失败：%w", err)
		return
	}
	a.settings = settings
	migrateAutostartName()
	settings.ApplySystemState()
	settings.Register(a.router)
	media, err := NewMediaServer()
	if err != nil {
		a.startErr = fmt.Errorf("启动资源服务失败：%w", err)
		return
	}
	a.media = media
	a.campus = NewCampusSession(a.secrets, a.emit, a.logger)
	a.timetable = NewTimetable(db, a.campus, a.emit)
	a.todo = NewTodo(db, a.emit)
	a.registerCore()
	a.applyInitialWindowSize()
	go func() {
		time.Sleep(500 * time.Millisecond)
		a.applyInitialWindowSize()
	}()
	a.campus.Register(a.router)
	a.timetable.Register(a.router)
	a.todo.Register(a.router)
	a.registerServices()
	a.startLocalBackupLoop()
	a.startSchoolCalendarLoop()
	a.startPortalMessageLoop()
	a.startExamLoop()
	a.startTray()
	a.logger.Info("app started", "version", Version, "data", paths.DataDir, "resource", media.BaseURL())
}

func (a *App) startTray() {
	executable, err := os.Executable()
	if err != nil {
		a.logger.Warn("resolve executable failed", "error", err)
		return
	}
	tray, err := NewTray(executable, AppName, a.logger, a.ShowMainWindow, a.QuitApp)
	if err != nil {
		a.logger.Warn("system tray unavailable", "error", err)
		return
	}
	a.tray = tray
	a.logger.Info("system tray ready")
}

// ShowMainWindow restores the window after it was hidden to the tray.
func (a *App) ShowMainWindow() {
	if a.ctx == nil {
		return
	}
	wruntime.WindowUnminimise(a.ctx)
	wruntime.WindowShow(a.ctx)
}

// QuitApp exits even when the close button is configured to hide to the tray.
func (a *App) QuitApp() {
	a.quitRequested.Store(true)
	wruntime.Quit(a.ctx)
}

// PreventClose implements Wails' OnBeforeClose hook.
func (a *App) PreventClose() bool {
	if a.quitRequested.Load() {
		return false
	}
	if a.settings.State().CloseAction != "tray" {
		return false
	}
	if a.ctx == nil {
		return false
	}
	wruntime.WindowHide(a.ctx)
	if a.tray != nil && !a.trayHintShown.Swap(true) {
		_ = systemToast(AppName+" 已在后台运行", "点击托盘图标恢复窗口，右键托盘图标可退出。")
	}
	return true
}

func (a *App) startCanvasBackground() {
	go func() {
		run := func() {
			if !a.canvas.Connected() {
				return
			}
			ctx, cancel := context.WithTimeout(context.Background(), 12*time.Minute)
			_, err := a.canvas.SyncCourses(ctx)
			cancel()
			if err != nil && a.logger != nil {
				a.logger.Warn("background canvas sync", "error", err)
			}
			_, _ = a.todo.Refresh()
		}
		run()
		for {
			minutes := a.settings.GetInt("autoSyncMinutes", 30)
			if minutes < 5 {
				minutes = 5
			}
			select {
			case <-a.ctx.Done():
				return
			case <-time.After(time.Duration(minutes) * time.Minute):
				run()
			}
		}
	}()
}

func (a *App) registerServices() {
	a.workspace = NewWorkspace(a.db, a.paths, a.media, a.emit)
	a.workspace.Register(a.router)
	_ = a.workspace.EnsureDefaultProject()
	a.canvas = NewCanvas(a.db, a.paths, a.secrets, a.media, a.emit)
	a.canvas.Register(a.router)
	a.mail = NewMail(a.db, a.paths, a.secrets, a.media, a.emit)
	a.mail.Register(a.router)
	a.mail.CleanupTrash(30)
	a.mail.StartBackground(a.ctx, func() time.Duration {
		minutes := a.settings.GetInt("mailPollMinutes", 5)
		if minutes < 1 {
			minutes = 5
		}
		return time.Duration(minutes) * time.Minute
	})
	a.classroom = NewClassroom(a.db, a.campus, a.media, a.emit)
	a.classroom.Register(a.router)
	a.shuiyuan = NewShuiyuan(a.secrets, a.media, a.emit)
	a.shuiyuan.Register(a.router)
	a.schoolCalendar = NewSchoolCalendar(a.db, a.campus, a.emit)
	a.schoolCalendar.Register(a.router)
	a.search = NewSearch(a.db)
	a.search.Register(a.router)
	a.notifications = NewNotifications(a.db, a.settings, a.shuiyuan, a.logger, a.emit)
	a.notifications.SetToaster(systemToast)
	a.portalMessages = NewPortalMessages(a.db, a.campus, a.notifications, a.schoolCalendar.Account, a.emit)
	a.portalMessages.Register(a.router)
	a.cloudPan = NewCloudPan(a.db, a.secrets, a.campus, a.media, a.emit)
	a.cloudPan.Register(a.router)
	a.exams = NewExams(a.db, a.campus, a.notifications, a.logger, a.emit)
	a.exams.Register(a.router)
	a.notifications.Register(a.router)
	a.notifications.StartBackground(a.ctx)
	a.update = NewUpdate(a.settings)
	a.update.Register(a.router)
	a.router.Handle("app.exportBackup", func([]json.RawMessage) (any, error) {
		return a.ExportBackup()
	})
	a.startCanvasBackground()
	a.router.Handle("classroom.exportTranscript", func(args []json.RawMessage) (any, error) {
		videoID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		title, _, _ := DecodeOptionalArg[string](args, 1)
		filename := sanitizeFilename(title, fmt.Sprintf("课堂视频-%d", videoID)) + ".txt"
		target, err := wruntime.SaveFileDialog(a.ctx, wruntime.SaveDialogOptions{
			Title:            "导出课堂文稿",
			DefaultFilename:  filename,
			DefaultDirectory: a.paths.DownloadsDir,
			Filters:          []wruntime.FileFilter{{DisplayName: "文本文件 (*.txt)", Pattern: "*.txt"}},
		})
		if err != nil {
			return nil, err
		}
		if target == "" {
			return map[string]any{"saved": false}, nil
		}
		ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer cancel()
		ok, err := a.classroom.ExportTranscript(ctx, videoID, title, target)
		if err != nil {
			return nil, err
		}
		return map[string]any{"saved": ok, "path": target}, nil
	})
	a.router.Handle("classroom.download", func(args []json.RawMessage) (any, error) {
		videoID, err := DecodeArg[int64](args, 0)
		if err != nil {
			return nil, err
		}
		view, err := DecodeArg[int](args, 1)
		if err != nil {
			return nil, err
		}
		name, _, _ := DecodeOptionalArg[string](args, 2)
		if !a.settings.State().Experiments.VideoDownload {
			return nil, fmt.Errorf("请先在设置 → 通用与通知 → 试验功能中开启「课堂视频下载」")
		}
		directory := strings.TrimSpace(a.settings.GetString("videoDownloadDir", ""))
		if directory == "" {
			directory = a.paths.DownloadsDir
		}
		suggested := videoFileName(name, view)
		target, err := wruntime.SaveFileDialog(a.ctx, wruntime.SaveDialogOptions{
			Title:            "下载课堂视频",
			DefaultFilename:  suggested,
			DefaultDirectory: directory,
			Filters:          []wruntime.FileFilter{{DisplayName: "MP4 视频 (*.mp4)", Pattern: "*.mp4"}},
		})
		if err != nil {
			return nil, err
		}
		if target == "" {
			return map[string]any{"started": false}, nil
		}
		id, err := a.classroom.StartDownload(videoID, view, suggested, target)
		if err != nil {
			return nil, err
		}
		return map[string]any{"started": true, "id": id}, nil
	})
	a.router.Handle("classroom.getDownloadDirectory", func([]json.RawMessage) (any, error) {
		return a.settings.GetString("videoDownloadDir", ""), nil
	})
	a.router.Handle("classroom.chooseDownloadDirectory", func([]json.RawMessage) (any, error) {
		current := strings.TrimSpace(a.settings.GetString("videoDownloadDir", ""))
		selected, err := wruntime.OpenDirectoryDialog(a.ctx, wruntime.OpenDialogOptions{
			Title:            "选择课堂视频保存位置",
			DefaultDirectory: current,
		})
		if err != nil {
			return nil, err
		}
		if selected == "" {
			return current, nil
		}
		if err := a.settings.Set("videoDownloadDir", selected); err != nil {
			return nil, err
		}
		return selected, nil
	})
	a.router.Handle("canvas.openLink", func(args []json.RawMessage) (any, error) {
		raw, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		if err := validateExternalURL(raw); err != nil {
			return nil, err
		}
		wruntime.BrowserOpenURL(a.ctx, raw)
		return true, nil
	})

	a.router.Handle("workspace.addProject", func([]json.RawMessage) (any, error) {
		selected, err := wruntime.OpenDirectoryDialog(a.ctx, wruntime.OpenDialogOptions{
			Title:            "选择项目文件夹",
			DefaultDirectory: a.paths.DownloadsDir,
		})
		if err != nil {
			return nil, err
		}
		if selected == "" {
			return nil, nil
		}
		return a.workspace.RegisterProject(selected, "")
	})
	a.router.Handle("workspace.openProjectDirectory", func(args []json.RawMessage) (any, error) {
		id, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		target, _, _ := DecodeOptionalArg[string](args, 1)
		project, err := a.workspace.Project(id)
		if err != nil {
			return nil, err
		}
		if target == "vscode" {
			command := exec.Command("code", project.Path)
			hideConsoleWindow(command)
			return true, command.Start()
		}
		return true, exec.Command("explorer.exe", project.Path).Start()
	})
	a.router.Handle("workspace.openFile", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		if _, err := os.Stat(pathValue); err != nil {
			return nil, fmt.Errorf("文件不存在")
		}
		return true, exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", pathValue).Start()
	})
	a.router.Handle("workspace.showInFolder", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		info, err := os.Stat(pathValue)
		if err != nil {
			return nil, fmt.Errorf("路径不存在")
		}
		if info.IsDir() {
			return true, exec.Command("explorer.exe", pathValue).Start()
		}
		return true, exec.Command("explorer.exe", "/select,"+pathValue).Start()
	})
}

func (a *App) Shutdown(context.Context) {
	if a.tray != nil {
		a.tray.Close()
	}
	if a.cancel != nil {
		a.cancel()
	}
	a.snapshotLocalBackup()
	if a.workspace != nil {
		a.workspace.CloseTerminals()
	}
	if a.media != nil {
		a.media.Stop()
	}
	if a.db != nil {
		_ = a.db.Close()
	}
	if a.closeLog != nil {
		a.closeLog()
	}
}

func (a *App) emit(name string, data any) {
	if a.ctx == nil {
		return
	}
	wruntime.EventsEmit(a.ctx, name, data)
}

func (a *App) Call(channel, argsJSON string) (string, error) {
	if a.startErr != nil {
		return "", a.startErr
	}
	args := make([]json.RawMessage, 0)
	if strings.TrimSpace(argsJSON) != "" && argsJSON != "null" {
		if err := json.Unmarshal([]byte(argsJSON), &args); err != nil {
			return "", fmt.Errorf("参数格式错误：%w", err)
		}
	}
	result, err := a.router.Dispatch(channel, args)
	if err != nil {
		if a.logger != nil {
			a.logger.Warn("channel failed", "channel", channel, "error", err)
		}
		return "", err
	}
	encoded, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

func (a *App) registerCore() {
	a.router.Handle("app.frontendReady", func([]json.RawMessage) (any, error) {
		if a.logger != nil {
			a.logger.Info("frontend ready")
		}
		return true, nil
	})
	a.router.Handle("app.info", func([]json.RawMessage) (any, error) {
		return AppInfo{
			Version:      Version,
			Name:         AppName,
			Platform:     goruntime.GOOS,
			Arch:         goruntime.GOARCH,
			GoVersion:    goruntime.Version(),
			WailsVersion: "v2.16.0",
			DataDir:      a.paths.DataDir,
			CacheDir:     a.paths.CacheDir,
			LogDir:       a.paths.LogDir,
			DownloadsDir: a.paths.DownloadsDir,
			ResourceBase: a.media.BaseURL(),
			StartedAt:    a.started.Format(time.RFC3339),
		}, nil
	})
	a.router.Handle("app.openExternal", func(args []json.RawMessage) (any, error) {
		raw, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		if err := validateExternalURL(raw); err != nil {
			return nil, err
		}
		wruntime.BrowserOpenURL(a.ctx, raw)
		return true, nil
	})
	a.router.Handle("app.openPath", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		if _, err := os.Stat(pathValue); err != nil {
			return nil, fmt.Errorf("路径不存在")
		}
		if info, err := os.Stat(pathValue); err == nil && info.IsDir() {
			return true, exec.Command("explorer.exe", pathValue).Start()
		}
		return true, exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", pathValue).Start()
	})
	a.router.Handle("app.showInFolder", func(args []json.RawMessage) (any, error) {
		pathValue, err := DecodeArg[string](args, 0)
		if err != nil {
			return nil, err
		}
		info, err := os.Stat(pathValue)
		if err != nil {
			return nil, fmt.Errorf("路径不存在")
		}
		if info.IsDir() {
			return true, exec.Command("explorer.exe", pathValue).Start()
		}
		return true, exec.Command("explorer.exe", "/select,"+pathValue).Start()
	})
	a.router.Handle("app.chooseDirectory", func(args []json.RawMessage) (any, error) {
		title, _, _ := DecodeOptionalArg[string](args, 0)
		options := wruntime.OpenDialogOptions{Title: title}
		if options.Title == "" {
			options.Title = "选择文件夹"
		}
		return wruntime.OpenDirectoryDialog(a.ctx, options)
	})
	a.router.Handle("app.chooseFiles", func(args []json.RawMessage) (any, error) {
		title, _, _ := DecodeOptionalArg[string](args, 0)
		options := wruntime.OpenDialogOptions{Title: title, DefaultFilename: ""}
		if options.Title == "" {
			options.Title = "选择文件"
		}
		return wruntime.OpenFileDialog(a.ctx, options)
	})
	a.router.Handle("app.chooseSaveFile", func(args []json.RawMessage) (any, error) {
		input, _, err := DecodeOptionalArg[map[string]string](args, 0)
		if err != nil {
			return nil, err
		}
		options := wruntime.SaveDialogOptions{
			Title:            input["title"],
			DefaultFilename:  input["filename"],
			DefaultDirectory: input["directory"],
		}
		if options.Title == "" {
			options.Title = "保存文件"
		}
		return wruntime.SaveFileDialog(a.ctx, options)
	})
	a.router.Handle("app.openLogs", func([]json.RawMessage) (any, error) {
		return true, exec.Command("explorer.exe", a.paths.LogDir).Start()
	})
	a.router.Handle("app.openData", func([]json.RawMessage) (any, error) {
		return true, exec.Command("explorer.exe", a.paths.DataDir).Start()
	})
	a.router.Handle("app.quit", func([]json.RawMessage) (any, error) {
		a.QuitApp()
		return true, nil
	})
	a.router.Handle("app.windowClose", func([]json.RawMessage) (any, error) {
		if a.PreventClose() {
			return "tray", nil
		}
		a.quitRequested.Store(true)
		wruntime.Quit(a.ctx)
		return "quit", nil
	})
	a.router.Handle("app.testNotification", func([]json.RawMessage) (any, error) {
		if err := systemToast("SJTU 通知测试", "看到这条说明系统通知已接通"); err != nil {
			a.logger.Warn("test notification failed", "error", err)
			return false, err
		}
		return true, nil
	})
	a.router.Handle("app.cacheSize", func([]json.RawMessage) (any, error) {
		return directorySize(a.paths.CacheDir), nil
	})
	a.router.Handle("app.clearCache", func([]json.RawMessage) (any, error) {
		freed, err := clearDirectory(a.paths.CacheDir)
		return map[string]any{"freed": freed}, err
	})
	a.router.Handle("app.openAtLogin", func([]json.RawMessage) (any, error) {
		value, err := openAtLoginValue()
		return value, err
	})
}

func validateExternalURL(raw string) error {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed.Host == "" {
		return fmt.Errorf("链接格式无效")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return fmt.Errorf("只允许打开 http/https 链接")
	}
	return nil
}

func directorySize(root string) int64 {
	var total int64
	_ = filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil || entry == nil || entry.IsDir() {
			return nil
		}
		if info, err := entry.Info(); err == nil {
			total += info.Size()
		}
		return nil
	})
	return total
}

func clearDirectory(root string) (int64, error) {
	freed := directorySize(root)
	entries, err := os.ReadDir(root)
	if err != nil {
		return 0, err
	}
	for _, entry := range entries {
		if err := os.RemoveAll(filepath.Join(root, entry.Name())); err != nil {
			return freed, err
		}
	}
	return freed, nil
}
