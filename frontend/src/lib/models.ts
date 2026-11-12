export interface CanvasCourse {
  id: number;
  name: string;
  term: string;
  code: string;
  syncedAt: string | null;
  error: string | null;
  archived?: boolean;
}

export interface CanvasItem {
  id: string;
  parentId: string | null;
  courseId: number;
  name: string;
  kind: 'folder' | 'module' | 'file' | 'assignment' | 'page' | 'discussion' | 'link';
  sourceId: string | null;
  dueAt?: string | null;
  position?: number;
  data?: Record<string, unknown>;
}

export interface CanvasState {
  connected: boolean;
  token: boolean;
  syncedAt: string | null;
  courses: CanvasCourse[];
  items: CanvasItem[];
}

export interface MailFolder {
  id: number;
  path: string;
  name: string;
  specialUse: string | null;
  total: number | null;
  unread: number | null;
  lastSyncedAt: string | null;
}

export interface MailAddress {
  name: string | null;
  address: string | null;
}

export interface MailMessageSummary {
  id: number;
  uid: number;
  subject: string | null;
  sender: MailAddress | null;
  date: string | null;
  seen: boolean;
  flagged: boolean;
  hasAttachments: boolean;
  snippet: string | null;
  size: number | null;
}

export interface MailMessageDetail extends MailMessageSummary {
  folderId?: number;
  to: MailAddress[];
  cc: MailAddress[];
  bodyText: string | null;
  bodyHtml: string | null;
  blockedImageHosts?: string[];
  attachments: MailAttachmentInfo[];
}

export interface MailAttachmentInfo {
  filename: string;
  size: number | null;
  part: string | null;
  contentId?: string | null;
  inline?: boolean;
  contentType?: string;
}

export interface MailState {
  account: { address: string; lastSyncedAt: string | null } | null;
  sync: {
    phase: 'idle' | 'connecting' | 'syncing' | 'error';
    folderPath: string | null;
    processed: number;
    total: number | null;
    error: string | null;
    startedAt: string | null;
    finishedAt: string | null;
  };
}

export interface SchoolCalendarEvent {
  id: string;
  title: string;
  startAt: string;
  endAt: string;
  location: string | null;
  calendarId: string;
  calendarName: string;
  color: string | null;
  allDay: boolean;
  status?: string | null;
  recurrence?: number;
}

export interface SchoolCalendarState {
  connected: boolean;
  account?: string;
  lastSyncedAt?: string | null;
  error?: string;
}

export interface TimetableItem {
  source: 'course' | 'event' | 'todo';
  lessonId?: number;
  eventId?: number;
  todoId?: string;
  title: string;
  startAt: string;
  endAt: string;
  location: string | null;
  color?: string | null;
  teacher?: string | null;
}

export interface TimetableQuery {
  semester: string;
  items: TimetableItem[];
  warnings: string[];
}

export interface TodoItem {
  id: string;
  title: string;
  notes: string;
  dueAt: string | null;
  done: boolean;
  source: 'local' | 'canvas' | string;
  sourceId?: string | null;
  courseName: string | null;
  courseId: number | null;
  createdAt: string;
  updatedAt: string;
  readAt?: string | null;
  calendarEventId?: number | null;
}

export interface RecentFile {
  path: string;
  name: string;
  projectId: string | null;
  openedAt: string;
}

export interface CampusStatus {
  connected: boolean;
  cookies: number;
  updatedAt: string | null;
}

export interface NotificationItem {
  id: number;
  kind: 'mail' | 'ddl' | 'todo' | 'shuiyuan' | 'system' | string;
  title: string;
  body: string;
  route: Record<string, unknown>;
  createdAt: string;
  readAt: string | null;
  snoozedUntil: string | null;
}


export interface DocumentContent {
  path: string;
  title: string;
  format: string;
  text?: string;
  resourceUrl?: string;
  assetBase?: string;
  revision?: string;
  readOnly?: boolean;
  size: number;
  modifiedAt: string;
  message?: string;
  meta?: Record<string, unknown>;
}

export interface CanvasCourseFile {
  path: string;
  title: string;
}

export interface WorkspaceRecent {
  path: string;
  name: string;
  projectId: string | null;
  openedAt: string;
}

export interface WorkspaceProject {
  id: string;
  name: string;
  path: string;
  createdAt: string;
  lastOpenedAt?: string | null;
}

export interface FileEntry {
  path: string;
  name: string;
  isDir: boolean;
  size: number;
  modifiedAt: string;
  ext: string;
}

export interface MailDraft {
  id: string;
  to: string[];
  cc: string[];
  bcc: string[];
  subject: string;
  text: string;
  html: string;
  attachments: string[];
  updatedAt: string;
}

export interface MailDraftSummary {
  id: string;
  to: string[];
  subject: string;
  updatedAt: string;
  attachments: number;
}

export interface MailTrashItem extends MailMessageSummary {
  folderName: string;
  deletedAt: string;
}

export interface MailSendResult {
  ok: boolean;
  warning?: string;
  error?: string;
}

export interface TimetableLesson {
  id: number;
  semester: string;
  courseCode: string;
  courseName: string;
  className: string;
  teacher: string;
  campus: string;
  classroom: string;
  weekday: number;
  startPeriod: number;
  endPeriod: number;
  periodsText: string;
  weeksText: string;
  weekNumbers: number[];
  credits: number | null;
  note: string;
}

export interface TimetableState {
  semester: string;
  semesters: string[];
  lastSyncedAt: string;
  semesterStart: string;
  connected: boolean;
}

export interface CalendarEvent {
  id: number | string;
  title: string;
  startAt: string;
  endAt: string;
  location: string | null;
  color: string | null;
  todoId?: string | null;
}

export interface TimetableResult {
  ok: boolean;
  error?: string;
  reason?: string;
}

export interface TodoState {
  items: TodoItem[];
  today: number;
  overdue: number;
  completed: number;
}

export interface WorkspaceStateResponse {
  projects: WorkspaceProject[];
  recent: WorkspaceRecent[];
}

export interface VideoCourse {
  id: number;
  code: string;
  name: string;
}

export interface ClassroomVideo {
  id: number;
  start: string;
  end: string;
  room: string;
  period: string;
}

export interface VideoStream {
  view: number;
  duration: number;
  playbackUrl: string;
}

export interface TranscriptSegment {
  startMs: number;
  endMs: number;
  text: string;
}

export interface VideoTranscript {
  videoId: number;
  segments: TranscriptSegment[];
}

export interface VideoDownload {
  id: string;
  name: string;
  received: number;
  total: number;
  state: 'progressing' | 'completed' | 'cancelled' | 'interrupted' | string;
}

export interface ShuiyuanState {
  connected: boolean;
  username: string | null;
  boundAt: string | null;
}

export interface ShuiyuanCategory {
  id: number;
  name: string;
  slug: string;
  color: string;
  parentId: number | null;
  topicCount: number | null;
}

export interface ShuiyuanTag {
  name: string;
  topicCount: number | null;
}

export interface ShuiyuanCustomEmoji {
  name: string;
  url: string;
}

export interface ShuiyuanSiteStructure {
  categories: ShuiyuanCategory[];
  tags: ShuiyuanTag[];
  customEmoji: ShuiyuanCustomEmoji[];
}

export interface ShuiyuanTopicSummary {
  id: number;
  title: string;
  excerpt: string | null;
  categoryId: number | null;
  tags: string[];
  postsCount: number;
  views: number | null;
  likeCount: number | null;
  createdAt: string;
  lastPostedAt: string | null;
  pinned: boolean;
  closed: boolean;
  bookmarked: boolean;
  archetype: string;
  lastPosterUsername: string | null;
  authorUsername: string | null;
  authorAvatarTemplate: string | null;
}

export interface ShuiyuanTopicPage {
  topics: ShuiyuanTopicSummary[];
  moreUrl: string | null;
}

export interface ShuiyuanPost {
  id: number;
  postNumber: number;
  username: string;
  name: string | null;
  avatarTemplate: string | null;
  createdAt: string;
  cooked: string;
  replyToPostNumber: number | null;
  likeCount: number | null;
  liked: boolean;
  yours: boolean;
}

export interface ShuiyuanTopicDetail {
  id: number;
  title: string;
  categoryId: number | null;
  tags: string[];
  postsCount: number;
  likeCount: number | null;
  views: number | null;
  createdAt: string;
  lastPostedAt: string | null;
  closed: boolean;
  archetype: string;
  bookmarked: boolean;
  stream: number[];
  posts: ShuiyuanPost[];
}

export interface ShuiyuanBookmarkItem {
  id: number;
  topicId: number;
  title: string;
  excerpt: string | null;
  createdAt: string;
  linkedPostNumber: number | null;
}

export interface ShuiyuanNotificationItem {
  id: number;
  type: number;
  read: boolean;
  highPriority: boolean;
  createdAt: string;
  topicId: number | null;
  postNumber: number | null;
  fancyTitle: string | null;
  displayUsername: string | null;
}

export interface ShuiyuanCounts {
  notifications: number;
  messages: number;
}

export interface ShuiyuanUpload {
  shortUrl: string;
  url: string | null;
  originalFilename: string | null;
}

export interface ShuiyuanProfileBadge {
  id: number;
  name: string;
  description: string;
  icon: string;
  imageUrl: string | null;
  grantedAt: string;
  count: number;
  badgeType: string;
}

export interface ShuiyuanProfile {
  id: number;
  username: string;
  name: string;
  avatarTemplate: string;
  title: string;
  bioRaw: string;
  bioCooked: string;
  trustLevel: number;
  admin: boolean;
  moderator: boolean;
  profileHidden: boolean;
  createdAt: string;
  cakedate: string;
  lastSeenAt: string;
  lastPostedAt: string;
  profileViewCount: number;
  badgeCount: number;
  acceptedAnswers: number;
  primaryGroup: string;
  groups: string[];
  isSelf: boolean;
  badges: ShuiyuanProfileBadge[];
}

export interface ShuiyuanActivityItem {
  id: number;
  kind: string;
  topicId: number;
  topicTitle: string;
  postNumber: number;
  blurb: string;
  createdAt: string;
}

export interface CloudPanState {
  connected: boolean;
  account: string;
  usedBytes: number;
  totalBytes: number;
  lastSyncedAt: string;
  error: string;
}

export interface CloudPanFile {
  name: string;
  path: string;
  directory: boolean;
  size: number;
  contentType: string;
  modifiedAt: string;
  canPreview: boolean;
  canDownload: boolean;
}

export interface CloudPanList {
  path: string;
  parent: string;
  files: CloudPanFile[];
  total: number;
  spaceId: string;
  spaceName: string;
}

export interface PortalMessage {
  id: string;
  title: string;
  body: string;
  kind: string;
  sourceName: string;
  url: string;
  createdAt: string;
  read: boolean;
}

export interface PortalMessageState {
  connected: boolean;
  account: string;
  unread: number;
  unreadNotifications: number;
  lastSyncedAt: string;
  error: string;
}

export interface ExamItem {
  id: number;
  semester: string;
  sourceId: string;
  courseCode: string;
  courseName: string;
  examName: string;
  examDate: string;
  timeText: string;
  startAt: string;
  endAt: string;
  campus: string;
  classroom: string;
  seat: string;
  form: string;
  className: string;
  teacher: string;
  credits: number;
  syncedAt: string;
}

export interface ExamState {
  connected: boolean;
  semester: string;
  count: number;
  lastSyncedAt: string;
  error: string;
}
