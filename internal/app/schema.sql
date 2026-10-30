CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS projects (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  path TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL,
  last_opened_at TEXT
);

CREATE TABLE IF NOT EXISTS recent_files (
  path TEXT PRIMARY KEY,
  project_id TEXT,
  opened_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS canvas_courses (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  term TEXT NOT NULL DEFAULT '',
  code TEXT NOT NULL DEFAULT '',
  synced_at TEXT,
  error TEXT,
  archived INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS canvas_sources (
  id TEXT PRIMARY KEY,
  course_id INTEGER NOT NULL,
  parent_id TEXT,
  name TEXT NOT NULL,
  kind TEXT NOT NULL,
  source_id TEXT,
  due_at TEXT,
  position INTEGER NOT NULL DEFAULT 0,
  data TEXT NOT NULL DEFAULT '{}'
);
CREATE INDEX IF NOT EXISTS idx_canvas_sources_parent ON canvas_sources(course_id, parent_id, position);
CREATE INDEX IF NOT EXISTS idx_canvas_sources_source ON canvas_sources(source_id);
CREATE INDEX IF NOT EXISTS idx_canvas_sources_kind ON canvas_sources(kind);

CREATE TABLE IF NOT EXISTS canvas_cache (
  source_id TEXT PRIMARY KEY,
  path TEXT NOT NULL,
  size INTEGER NOT NULL,
  cached_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS mail_accounts (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  address TEXT NOT NULL,
  display_name TEXT,
  imap_host TEXT NOT NULL,
  imap_port INTEGER NOT NULL,
  smtp_host TEXT NOT NULL,
  smtp_port INTEGER NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS mail_folders (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  path TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  special_use TEXT,
  uid_validity INTEGER,
  last_synced_at TEXT,
  total INTEGER,
  unread INTEGER
);

CREATE TABLE IF NOT EXISTS mail_messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  folder_id INTEGER NOT NULL,
  uid INTEGER NOT NULL,
  message_id TEXT,
  subject TEXT,
  from_name TEXT,
  from_addr TEXT,
  to_json TEXT NOT NULL DEFAULT '[]',
  cc_json TEXT NOT NULL DEFAULT '[]',
  date TEXT,
  seen INTEGER NOT NULL DEFAULT 0,
  flagged INTEGER NOT NULL DEFAULT 0,
  has_attachments INTEGER NOT NULL DEFAULT 0,
  snippet TEXT,
  size INTEGER,
  body_loaded INTEGER NOT NULL DEFAULT 0,
  body_text TEXT,
  body_html TEXT,
  attachments_json TEXT NOT NULL DEFAULT '[]',
  deleted_at TEXT,
  original_folder_id INTEGER,
  UNIQUE(folder_id, uid)
);
CREATE INDEX IF NOT EXISTS idx_mail_messages_folder_date ON mail_messages(folder_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_mail_messages_deleted ON mail_messages(deleted_at);
CREATE INDEX IF NOT EXISTS idx_mail_messages_message_id ON mail_messages(message_id);

CREATE TABLE IF NOT EXISTS mail_drafts (
  id TEXT PRIMARY KEY,
  to_json TEXT NOT NULL DEFAULT '[]',
  cc_json TEXT NOT NULL DEFAULT '[]',
  bcc_json TEXT NOT NULL DEFAULT '[]',
  subject TEXT NOT NULL DEFAULT '',
  text TEXT NOT NULL DEFAULT '',
  html TEXT NOT NULL DEFAULT '',
  attachments_json TEXT NOT NULL DEFAULT '[]',
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS timetable_sync (
  semester TEXT PRIMARY KEY,
  last_synced_at TEXT,
  semester_start TEXT
);

CREATE TABLE IF NOT EXISTS timetable_lessons (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  semester TEXT NOT NULL,
  source_id TEXT NOT NULL,
  course_code TEXT NOT NULL DEFAULT '',
  course_name TEXT NOT NULL,
  class_name TEXT,
  teacher TEXT,
  campus TEXT,
  classroom TEXT,
  weekday INTEGER NOT NULL,
  start_period INTEGER NOT NULL,
  end_period INTEGER NOT NULL,
  periods_text TEXT NOT NULL DEFAULT '',
  weeks_text TEXT NOT NULL DEFAULT '',
  week_numbers TEXT NOT NULL DEFAULT '[]',
  credits REAL,
  note TEXT
);
CREATE INDEX IF NOT EXISTS idx_timetable_lessons_semester ON timetable_lessons(semester, weekday, start_period);

CREATE TABLE IF NOT EXISTS calendar_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  title TEXT NOT NULL,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL,
  location TEXT,
  color TEXT,
  todo_id TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_calendar_events_time ON calendar_events(start_at, end_at);
CREATE INDEX IF NOT EXISTS idx_calendar_events_todo ON calendar_events(todo_id);

CREATE TABLE IF NOT EXISTS todo_items (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  notes TEXT NOT NULL DEFAULT '',
  due_at TEXT,
  done INTEGER NOT NULL DEFAULT 0,
  done_at TEXT,
  manual_done INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL DEFAULT 'local',
  source_id TEXT,
  course_id INTEGER,
  course_name TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  read_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_todo_items_due ON todo_items(done, due_at);
CREATE UNIQUE INDEX IF NOT EXISTS idx_todo_items_source ON todo_items(source, source_id) WHERE source_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS video_progress (
  video_id INTEGER PRIMARY KEY,
  position REAL NOT NULL DEFAULT 0,
  duration REAL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS video_downloads (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  path TEXT NOT NULL,
  state TEXT NOT NULL,
  received INTEGER NOT NULL DEFAULT 0,
  total INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS notifications (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  title TEXT NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  route TEXT NOT NULL DEFAULT '{}',
  dedupe_key TEXT UNIQUE,
  created_at TEXT NOT NULL,
  read_at TEXT,
  snoozed_until TEXT
);
CREATE INDEX IF NOT EXISTS idx_notifications_created ON notifications(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_read ON notifications(read_at);

CREATE TABLE IF NOT EXISTS workspace_tabs (
  project_id TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS shuiyuan_sources (
  topic_id INTEGER PRIMARY KEY,
  title TEXT NOT NULL,
  url TEXT NOT NULL,
  excerpt TEXT,
  category_name TEXT,
  author TEXT,
  posts_count INTEGER,
  added_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS school_events (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  start_at TEXT NOT NULL,
  end_at TEXT NOT NULL,
  location TEXT,
  calendar_id TEXT,
  calendar_name TEXT,
  color TEXT,
  all_day INTEGER NOT NULL DEFAULT 0,
  status TEXT,
  recurrence INTEGER NOT NULL DEFAULT 0,
  synced_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_school_events_start ON school_events(start_at);
CREATE TABLE IF NOT EXISTS school_calendar_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS portal_messages (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT '',
  body TEXT NOT NULL DEFAULT '',
  kind TEXT NOT NULL DEFAULT '',
  source_name TEXT NOT NULL DEFAULT '',
  url TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT '',
  is_read INTEGER NOT NULL DEFAULT 0,
  fetched_at TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_portal_messages_created ON portal_messages(created_at DESC);
CREATE TABLE IF NOT EXISTS portal_message_meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS exam_items (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  semester TEXT NOT NULL,
  source_id TEXT NOT NULL,
  course_code TEXT NOT NULL DEFAULT '',
  course_name TEXT NOT NULL,
  exam_name TEXT NOT NULL DEFAULT '',
  exam_date TEXT NOT NULL DEFAULT '',
  time_text TEXT NOT NULL DEFAULT '',
  start_at TEXT NOT NULL DEFAULT '',
  end_at TEXT NOT NULL DEFAULT '',
  campus TEXT NOT NULL DEFAULT '',
  classroom TEXT NOT NULL DEFAULT '',
  seat TEXT NOT NULL DEFAULT '',
  form TEXT NOT NULL DEFAULT '',
  class_name TEXT NOT NULL DEFAULT '',
  teacher TEXT NOT NULL DEFAULT '',
  credits REAL NOT NULL DEFAULT 0,
  synced_at TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_exam_items_key ON exam_items(semester, source_id);
CREATE INDEX IF NOT EXISTS idx_exam_items_start ON exam_items(start_at);
