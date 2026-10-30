import type { AppInfo, SettingsState } from './types';
import type {
  CanvasCourse,
  CanvasItem,
  CanvasState,
  ClassroomVideo,
  DocumentContent,
  FileEntry,
  MailAddress,
  MailDraft,
  MailDraftSummary,
  MailFolder,
  MailMessageDetail,
  MailMessageSummary,
  MailState,
  MailTrashItem,
  ShuiyuanBookmarkItem,
  ShuiyuanCategory,
  ShuiyuanCounts,
  ShuiyuanNotificationItem,
  ShuiyuanPost,
  ShuiyuanSiteStructure,
  ShuiyuanState,
  ShuiyuanTopicDetail,
  ShuiyuanTopicPage,
  ShuiyuanTopicSummary,
  TimetableItem,
  TimetableLesson,
  TimetableQuery,
  TimetableState,
  TodoItem,
  TodoState,
  VideoCourse,
  VideoDownload,
  VideoStream,
  VideoTranscript,
  WorkspaceProject,
  WorkspaceRecent,
  WorkspaceStateResponse,
} from './models';
import { classPeriods, formatDate, isoDate } from './format';

const storageKey = 'sjtu.mock.settings';

export const defaultSettings: SettingsState = {
  theme: 'system',
  fontScale: 100,
  fontBasePx: 16,
  zoom: 1,
  openAtLogin: false,
  campusNotifications: true,
  taskNotifications: true,
  experiments: { videoDownload: true, shuiyuan: true },
  navHidden: [],
  onboardingCompleted: true,
  consentVersion: '2026-10-29',
  videoDownloadDir: 'C:\\Users\\student\\Videos\\SJTU',
  mailPollMinutes: 5,
  autoSyncMinutes: 30,
  updateManifestUrl: '',
  closeAction: 'tray',
  sidebarCollapsed: false,
  lastView: 'dashboard',
};

export function loadMockSettings(): SettingsState {
  try {
    const raw = localStorage.getItem(storageKey);
    if (!raw) return { ...defaultSettings };
    return { ...defaultSettings, ...(JSON.parse(raw) as Partial<SettingsState>) };
  } catch {
    return { ...defaultSettings };
  }
}

function saveMockSettings(state: SettingsState) {
  localStorage.setItem(storageKey, JSON.stringify(state));
}

let settings = loadMockSettings();

const mockInfo: AppInfo = {
  version: '0.1.0',
  name: 'SJTU Hive',
  platform: 'windows',
  arch: 'amd64',
  goVersion: 'go1.25.3',
  wailsVersion: 'v2.16.0',
  dataDir: 'C:\\Users\\student\\AppData\\Roaming\\SJTU',
  cacheDir: 'C:\\Users\\student\\AppData\\Local\\SJTU\\cache',
  logDir: 'C:\\Users\\student\\AppData\\Roaming\\SJTU\\logs',
  downloadsDir: 'C:\\Users\\student\\Downloads',
  resourceBase: 'http://127.0.0.1:5387',
  startedAt: new Date().toISOString(),
};

function isoAt(days: number, hour = 9, minute = 0) {
  const date = new Date();
  date.setDate(date.getDate() + days);
  date.setHours(hour, minute, 0, 0);
  return date.toISOString();
}

function dayKey(offset: number) {
  const date = new Date();
  date.setDate(date.getDate() + offset);
  return isoDate(date);
}

const svgPreview = `data:image/svg+xml,${encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" width="900" height="560"><rect width="900" height="560" fill="#eef2f8"/><rect x="60" y="50" width="780" height="460" rx="18" fill="#ffffff" stroke="#d4dbe6"/><text x="100" y="130" font-family="Segoe UI" font-size="38" fill="#1f2937">Lecture 06 · Decision Trees</text><text x="100" y="190" font-family="Segoe UI" font-size="22" fill="#4b5563">Machine Learning · Fall 2026</text><g fill="#e5edff"><rect x="100" y="250" width="320" height="180" rx="12"/></g><g fill="#dff3e8"><rect x="460" y="250" width="320" height="180" rx="12"/></g><text x="130" y="300" font-family="Segoe UI" font-size="20" fill="#2563eb">Entropy &amp; Information Gain</text><text x="490" y="300" font-family="Segoe UI" font-size="20" fill="#15803d">Random Forest</text></svg>',
)}`;

// ---------------------------------------------------------------------------
// Canvas
// ---------------------------------------------------------------------------

const canvasCourses: CanvasCourse[] = [
  { id: 1, name: '机器学习', term: '2026-2027 Fall', code: 'CS3308-01', syncedAt: isoAt(0, 8), error: null },
  { id: 2, name: '操作系统', term: '2026-2027 Fall', code: 'CS3560-02', syncedAt: isoAt(0, 8), error: null },
  { id: 3, name: '大学物理（下）', term: '2026-2027 Fall', code: 'PHY1202-05', syncedAt: isoAt(0, 8), error: null },
  { id: 4, name: '数据结构', term: '2025-2026 Spring', code: 'CS2210-01', syncedAt: isoAt(-120, 8), error: null, archived: true },
  { id: 5, name: '学术写作', term: '2026-2027 Fall', code: 'ENGL1500-03', syncedAt: null, error: null },
];

const canvasItems: CanvasItem[] = [
  { id: 'c1:folder:11', parentId: null, courseId: 1, name: 'Slides', kind: 'folder', sourceId: '11', data: {} },
  { id: 'c1:folder:12', parentId: null, courseId: 1, name: 'Readings', kind: 'folder', sourceId: '12', data: {} },
  { id: 'c1:file:101', parentId: 'c1:folder:11', courseId: 1, name: 'lecture-06-决策树.pdf', kind: 'file', sourceId: '101', data: { size: 2_486_000, contentType: 'application/pdf', folderPath: 'Slides' } },
  { id: 'c1:file:102', parentId: 'c1:folder:11', courseId: 1, name: 'lecture-07-集成学习.pptx', kind: 'file', sourceId: '102', data: { size: 8_120_000, contentType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation', folderPath: 'Slides' } },
  { id: 'c1:file:103', parentId: 'c1:folder:12', courseId: 1, name: 'notes-week6.md', kind: 'file', sourceId: '103', data: { size: 8_240, contentType: 'text/markdown', folderPath: 'Readings' } },
  { id: 'c1:file:104', parentId: 'c1:folder:12', courseId: 1, name: '数据集结构.png', kind: 'file', sourceId: '104', data: { size: 386_000, contentType: 'image/png', folderPath: 'Readings' } },
  { id: 'c1:module:21', parentId: null, courseId: 1, name: 'Week 6 · Tree Models', kind: 'module', sourceId: '21', data: {} },
  { id: 'c1:module:21:item:1', parentId: 'c1:module:21', courseId: 1, name: '决策树课件', kind: 'file', sourceId: '101', data: { moduleItem: true, targetType: 'file', fileId: 101 } },
  { id: 'c1:module:21:item:2', parentId: 'c1:module:21', courseId: 1, name: '作业 3：决策树与集成学习', kind: 'assignment', sourceId: '301', data: { moduleItem: true, targetType: 'assignment', contentId: 301 } },
  { id: 'c1:assignment:301', parentId: null, courseId: 1, name: '作业 3：决策树与集成学习', kind: 'assignment', sourceId: '301', dueAt: isoAt(0, 23, 59), data: { points: 100, submissionState: 'unsubmitted', htmlUrl: 'https://oc.sjtu.edu.cn/courses/1/assignments/301', description: '<h2>作业要求</h2><p>实现 CART 与随机森林，比较在给定数据集上的表现。</p><ul><li>提交 <code>tree.py</code> 与实验报告 PDF</li><li>报告中包含混淆矩阵与特征重要性</li></ul><p>允许使用 NumPy 与 scikit-learn，不得直接调用现成的随机森林实现。</p>' } },
  { id: 'c1:assignment:302', parentId: null, courseId: 1, name: '作业 2：线性回归', kind: 'assignment', sourceId: '302', dueAt: isoAt(-4, 23, 59), data: { points: 100, submissionState: 'graded', score: 92, htmlUrl: 'https://oc.sjtu.edu.cn/courses/1/assignments/302', description: '<h2>作业 2</h2><p>已完成并评分。得分 92 / 100。</p>' } },
  { id: 'c1:page:1', parentId: null, courseId: 1, name: '课程大纲与评分标准', kind: 'page', sourceId: 'syllabus', data: { url: 'syllabus' } },
  { id: 'c1:discussion:41', parentId: null, courseId: 1, name: '第 6 周讨论：偏差与方差', kind: 'discussion', sourceId: '41', data: { isAnnouncement: false, htmlUrl: 'https://oc.sjtu.edu.cn/courses/1/discussion_topics/41' } },
  { id: 'c2:folder:31', parentId: null, courseId: 2, name: 'Labs', kind: 'folder', sourceId: '31', data: {} },
  { id: 'c2:file:201', parentId: 'c2:folder:31', courseId: 2, name: 'lab2-进程调度.pdf', kind: 'file', sourceId: '201', data: { size: 1_204_000, contentType: 'application/pdf', folderPath: 'Labs' } },
  { id: 'c2:file:202', parentId: null, courseId: 2, name: 'os-notes.md', kind: 'file', sourceId: '202', data: { size: 5_400, contentType: 'text/markdown' } },
  { id: 'c2:assignment:401', parentId: null, courseId: 2, name: '实验二：进程调度报告', kind: 'assignment', sourceId: '401', dueAt: isoAt(2, 23, 59), data: { points: 100, submissionState: 'unsubmitted', htmlUrl: 'https://oc.sjtu.edu.cn/courses/2/assignments/401', description: '<h2>实验二</h2><p>实现多级反馈队列调度器，并分析平均等待时间。</p>' } },
  { id: 'c2:page:2', parentId: null, courseId: 2, name: '实验环境说明', kind: 'page', sourceId: 'lab-env', data: { url: 'lab-env' } },
  { id: 'c3:file:301', parentId: null, courseId: 3, name: 'chapter-12-电磁感应.pdf', kind: 'file', sourceId: '301', data: { size: 5_120_000, contentType: 'application/pdf' } },
  { id: 'c3:assignment:501', parentId: null, courseId: 3, name: '物理作业 12', kind: 'assignment', sourceId: '501', dueAt: isoAt(4, 23, 59), data: { points: 50, submissionState: 'unsubmitted', htmlUrl: 'https://oc.sjtu.edu.cn/courses/3/assignments/501', description: '<p>完成第 12 章课后习题 1、3、5、7。</p>' } },
  { id: 'c3:discussion:51', parentId: null, courseId: 3, name: '期中考试范围公告', kind: 'discussion', sourceId: '51', data: { isAnnouncement: true, htmlUrl: 'https://oc.sjtu.edu.cn/courses/3/discussion_topics/51' } },
];

function canvasState(): CanvasState {
  return {
    connected: true,
    syncedAt: isoAt(0, 8),
    courses: canvasCourses,
    items: canvasItems,
  };
}

function canvasDoc(item: CanvasItem): DocumentContent {
  const ext = item.name.split('.').pop()?.toLowerCase() ?? '';
  if (item.kind === 'assignment') {
    return {
      path: item.id,
      title: item.name,
      format: 'html',
      text: String(item.data?.description ?? '<p>暂无作业说明。</p>'),
      size: 0,
      modifiedAt: item.dueAt ?? new Date().toISOString(),
      readOnly: true,
      meta: { kind: 'assignment', dueAt: item.dueAt, points: item.data?.points, submissionState: item.data?.submissionState, score: item.data?.score, htmlUrl: item.data?.htmlUrl },
    };
  }
  if (item.kind === 'page' || item.kind === 'discussion') {
    return {
      path: item.id,
      title: item.name,
      format: 'html',
      text:
        item.kind === 'discussion'
          ? '<h2>讨论主题</h2><p>请结合本周课件，谈谈你对偏差—方差权衡的理解，并给出一个实际例子。</p><blockquote>提示：可以从模型的复杂度与训练集规模两个角度展开。</blockquote>'
          : '<h2>课程大纲</h2><p>本课程介绍监督学习、无监督学习与模型评估方法。成绩构成：作业 40%、项目 30%、期末 30%。</p><ul><li>作业允许迟交 48 小时，扣 10%</li><li>项目需要提交代码与报告</li></ul>',
      size: 0,
      modifiedAt: isoAt(-10, 10),
      readOnly: true,
      meta: { kind: item.kind, htmlUrl: item.data?.htmlUrl },
    };
  }
  if (ext === 'pdf') {
    return {
      path: item.id,
      title: item.name,
      format: 'html',
      text: `<div style="padding:32px 44px;background:#fff;border:1px solid #d8dde5;border-radius:12px;max-width:760px;margin:0 auto"><div style="font-size:12px;color:#6b7280;letter-spacing:.12em">SJTU · COURSE MATERIAL</div><h1 style="font-size:28px;margin:18px 0 8px">${item.name.replace(/\.pdf$/i, '')}</h1><p style="color:#4b5563">本节介绍决策树的基本结构、划分准则与剪枝策略。</p><h2>1. 信息增益</h2><p>使用熵衡量集合的不确定性，选择使信息增益最大的特征进行划分。</p><pre>H(D) = -Σ p_i log2(p_i)</pre><h2>2. 剪枝</h2><p>预剪枝与后剪枝；通过验证集控制模型复杂度。</p><div style="margin-top:28px;padding:14px;background:#eef4ff;border-radius:8px;color:#1d4ed8">课件预览为本地示例数据。</div></div>`,
      size: Number(item.data?.size ?? 0),
      modifiedAt: isoAt(-2, 15),
      readOnly: true,
    };
  }
  if (ext === 'pptx') {
    return {
      path: item.id,
      title: item.name,
      format: 'pptx',
      size: Number(item.data?.size ?? 0),
      modifiedAt: isoAt(-2, 15),
      readOnly: true,
      message: 'PPTX 需要系统 PowerPoint 或 WPS 打开。',
    };
  }
  if (ext === 'png' || ext === 'jpg' || ext === 'jpeg') {
    return {
      path: item.id,
      title: item.name,
      format: 'image',
      resourceUrl: svgPreview,
      size: Number(item.data?.size ?? 0),
      modifiedAt: isoAt(-3, 12),
      readOnly: true,
    };
  }
  if (ext === 'md') {
    return {
      path: item.id,
      title: item.name,
      format: 'markdown',
      text:
        '# 第 6 周阅读笔记\n\n## 决策树\n\n- 信息增益：$IG(D, a) = H(D) - H(D|a)$\n- 基尼指数适合分类问题\n- 剪枝可以缓解过拟合\n\n> 本周作业：实现 CART。\n\n```python\ndef entropy(labels):\n    ...\n```\n\n```mermaid\ngraph TD\n  A[根节点] --> B[左子树]\n  A --> C[右子树]\n```\n',
      size: Number(item.data?.size ?? 0),
      modifiedAt: isoAt(-1, 20),
      readOnly: true,
    };
  }
  return {
    path: item.id,
    title: item.name,
    format: 'text',
    text: '课程资料示例内容。\n\n这是一个本地演示文件，用于展示预览与下载交互。',
    size: Number(item.data?.size ?? 0),
    modifiedAt: isoAt(-1, 20),
    readOnly: true,
  };
}

// ---------------------------------------------------------------------------
// Mail
// ---------------------------------------------------------------------------

const inboxFolder: MailFolder = { id: 1, path: 'INBOX', name: '收件箱', specialUse: 'inbox', total: 8, unread: 3, lastSyncedAt: isoAt(0, 9) };
const sentFolder: MailFolder = { id: 2, path: 'Sent', name: '已发送', specialUse: 'sent', total: 5, unread: 0, lastSyncedAt: isoAt(0, 9) };
const archiveFolder: MailFolder = { id: 3, path: 'Archive', name: '归档', specialUse: 'archive', total: 12, unread: 0, lastSyncedAt: isoAt(0, 9) };

const mailFolders = [inboxFolder, sentFolder, archiveFolder];

function address(name: string, value: string): MailAddress {
  return { name, address: value };
}

const mailMessages: MailMessageDetail[] = [
  {
    id: 1, uid: 101, subject: '【机器学习】作业 3 已发布', sender: address('课程助教', 'ta-ml@sjtu.edu.cn'), date: isoAt(0, 8, 42), seen: false, flagged: false, hasAttachments: true, snippet: '作业 3 已发布，截止时间为今晚 23:59。', size: 84_000, folderId: 1,
    to: [address('我', 'student@sjtu.edu.cn')], cc: [],
    bodyText: '作业 3 已发布，截止时间为今晚 23:59。请在 Canvas 提交 PDF 与代码压缩包。',
    bodyHtml: '<p>同学你好：</p><p>作业 3 已发布，截止时间为<strong>今晚 23:59</strong>。请在 Canvas 提交 PDF 与代码压缩包。</p><ul><li>实现 CART 与随机森林</li><li>报告不超过 8 页</li></ul><p>祝好，<br/>课程助教</p>',
    attachments: [{ filename: 'assignment-3.pdf', size: 84_000, part: 'part-2', inline: false, contentType: 'application/pdf' }],
  },
  {
    id: 2, uid: 102, subject: '关于本周实验课安排', sender: address('张老师', 'zhang@sjtu.edu.cn'), date: isoAt(0, 7, 10), seen: false, flagged: true, hasAttachments: false, snippet: '本周实验课改到周四下午，地点不变。', size: 12_000, folderId: 1,
    to: [address('我', 'student@sjtu.edu.cn')], cc: [address('助教', 'ta-os@sjtu.edu.cn')],
    bodyText: '本周实验课改到周四下午 14:00，地点东中院 4-201。',
    bodyHtml: '<p>各位同学：</p><p>本周实验课改到<strong>周四下午 14:00</strong>，地点不变（东中院 4-201）。</p><p>请提前完成实验二的环境配置。</p>',
    attachments: [],
  },
  {
    id: 3, uid: 103, subject: '图书馆逾期提醒', sender: address('图书馆', 'library@sjtu.edu.cn'), date: isoAt(-1, 16, 5), seen: false, flagged: false, hasAttachments: false, snippet: '您借阅的《操作系统概念》将于 3 天后到期。', size: 9_000, folderId: 1,
    to: [address('我', 'student@sjtu.edu.cn')], cc: [],
    bodyText: '您借阅的《操作系统概念》将于 3 天后到期，请及时续借或归还。',
    bodyHtml: '<p>您借阅的《操作系统概念》将于 <strong>3 天后</strong>到期，请及时续借或归还。</p>',
    attachments: [],
  },
  {
    id: 4, uid: 104, subject: 'Re: 组队参加课程项目', sender: address('李明', 'liming@sjtu.edu.cn'), date: isoAt(-1, 12, 30), seen: true, flagged: false, hasAttachments: false, snippet: '可以，我们周三晚上讨论一下分工。', size: 7_000, folderId: 1,
    to: [address('我', 'student@sjtu.edu.cn')], cc: [], bodyText: '可以，我们周三晚上讨论一下分工。', bodyHtml: '<p>可以，我们周三晚上讨论一下分工。</p>', attachments: [],
  },
  {
    id: 5, uid: 105, subject: '2026 秋季学期选课通知', sender: address('教务处', 'jwc@sjtu.edu.cn'), date: isoAt(-2, 9, 0), seen: true, flagged: false, hasAttachments: true, snippet: '第二轮选课将于下周一开始。', size: 120_000, folderId: 1,
    to: [address('我', 'student@sjtu.edu.cn')], cc: [], bodyText: '第二轮选课将于下周一开始，请留意选课系统开放时间。', bodyHtml: '<p>第二轮选课将于<strong>下周一</strong>开始，请留意选课系统开放时间。</p>', attachments: [{ filename: '选课手册.pdf', size: 120_000, part: 'part-2', inline: false, contentType: 'application/pdf' }],
  },
  {
    id: 6, uid: 201, subject: 'Re: 作业 2 反馈', sender: address('我', 'student@sjtu.edu.cn'), date: isoAt(-3, 18, 20), seen: true, flagged: false, hasAttachments: false, snippet: '谢谢老师，已修改。', size: 5_000, folderId: 2,
    to: [address('课程助教', 'ta-ml@sjtu.edu.cn')], cc: [], bodyText: '谢谢老师，已修改。', bodyHtml: '<p>谢谢老师，已修改。</p>', attachments: [],
  },
  {
    id: 7, uid: 202, subject: '实习面试安排', sender: address('HR', 'hr@example.com'), date: isoAt(-3, 11, 0), seen: true, flagged: false, hasAttachments: false, snippet: '请于周五下午参加线上面试。', size: 6_000, folderId: 3,
    to: [address('我', 'student@sjtu.edu.cn')], cc: [], bodyText: '请于周五下午参加线上面试。', bodyHtml: '<p>请于周五下午参加线上面试。</p>', attachments: [],
  },
];

const trash: MailTrashItem[] = [
  {
    id: 90, uid: 900, subject: '校园活动推广', sender: address('活动中心', 'activity@sjtu.edu.cn'), date: isoAt(-5, 10), seen: true, flagged: false, hasAttachments: false, snippet: '周末社团招新。', size: 4_000, folderName: '收件箱', deletedAt: isoAt(-1, 9),
  },
];

const mailsDrafts: MailDraft[] = [
  { id: 'draft-1', to: ['ta-ml@sjtu.edu.cn'], cc: [], bcc: [], subject: '关于作业 3 的问题', text: '老师您好，\n\n请问随机森林的特征重要性应该使用哪种计算方式？\n\n谢谢！', html: '', attachments: [], updatedAt: isoAt(0, 10, 5) },
];

function summaryOf(message: MailMessageDetail): MailMessageSummary {
  return {
    id: message.id, uid: message.uid, subject: message.subject, sender: message.sender,
    date: message.date, seen: message.seen, flagged: message.flagged,
    hasAttachments: message.hasAttachments, snippet: message.snippet, size: message.size,
  };
}

function mailState(): MailState {
  return {
    account: { address: 'student@sjtu.edu.cn', lastSyncedAt: isoAt(0, 9) },
    sync: { phase: 'idle', folderPath: null, processed: 0, total: null, error: null, startedAt: isoAt(0, 9), finishedAt: isoAt(0, 9) },
  };
}

function foldersWithCounts(): MailFolder[] {
  return mailFolders.map((folder) => ({
    ...folder,
    unread: mailMessages.filter((message) => message.folderId === folder.id && !message.seen).length,
    total: mailMessages.filter((message) => message.folderId === folder.id).length,
  }));
}

// ---------------------------------------------------------------------------
// Timetable / TODO
// ---------------------------------------------------------------------------

function mondayWeeksAgo(weeks: number) {
  const date = new Date();
  date.setHours(0, 0, 0, 0);
  const offset = (date.getDay() + 6) % 7;
  date.setDate(date.getDate() - offset - weeks * 7);
  return date;
}

const semesterStartDate = mondayWeeksAgo(8);
const semesterStart = isoDate(semesterStartDate);

const timetableLessons: TimetableLesson[] = [
  { id: 1, semester: '2026-2027-1', courseCode: 'CS3308', courseName: '机器学习', className: '01', teacher: '王老师', campus: '闵行', classroom: '东上院 101', weekday: 1, startPeriod: 1, endPeriod: 2, periodsText: '1-2节', weeksText: '1-16周', weekNumbers: Array.from({ length: 16 }, (_, i) => i + 1), credits: 3, note: '' },
  { id: 2, semester: '2026-2027-1', courseCode: 'CS3560', courseName: '操作系统', className: '02', teacher: '张老师', campus: '闵行', classroom: '东中院 4-201', weekday: 1, startPeriod: 3, endPeriod: 4, periodsText: '3-4节', weeksText: '1-16周', weekNumbers: Array.from({ length: 16 }, (_, i) => i + 1), credits: 4, note: '' },
  { id: 3, semester: '2026-2027-1', courseCode: 'PHY1202', courseName: '大学物理（下）', className: '05', teacher: '李老师', campus: '闵行', classroom: '上院 205', weekday: 2, startPeriod: 7, endPeriod: 8, periodsText: '7-8节', weeksText: '1-16周', weekNumbers: Array.from({ length: 16 }, (_, i) => i + 1), credits: 3, note: '' },
  { id: 4, semester: '2026-2027-1', courseCode: 'CS3308', courseName: '机器学习', className: '01', teacher: '王老师', campus: '闵行', classroom: '东上院 101', weekday: 3, startPeriod: 3, endPeriod: 4, periodsText: '3-4节', weeksText: '1-16周', weekNumbers: Array.from({ length: 16 }, (_, i) => i + 1), credits: 3, note: '' },
  { id: 5, semester: '2026-2027-1', courseCode: 'ENGL1500', courseName: '学术写作', className: '03', teacher: 'Smith', campus: '闵行', classroom: '下院 312', weekday: 4, startPeriod: 5, endPeriod: 6, periodsText: '5-6节', weeksText: '1-16周', weekNumbers: Array.from({ length: 16 }, (_, i) => i + 1), credits: 2, note: '' },
  { id: 6, semester: '2026-2027-1', courseCode: 'CS3560', courseName: '操作系统', className: '02', teacher: '张老师', campus: '闵行', classroom: '东中院 4-201', weekday: 4, startPeriod: 7, endPeriod: 8, periodsText: '7-8节', weeksText: '1-16周', weekNumbers: Array.from({ length: 16 }, (_, i) => i + 1), credits: 4, note: '' },
  { id: 7, semester: '2026-2027-1', courseCode: 'PHY1202', courseName: '大学物理（下）', className: '05', teacher: '李老师', campus: '闵行', classroom: '上院 205', weekday: 5, startPeriod: 1, endPeriod: 2, periodsText: '1-2节', weeksText: '1-16周', weekNumbers: Array.from({ length: 16 }, (_, i) => i + 1), credits: 3, note: '' },
];

interface MockEvent {
  id: number;
  title: string;
  startAt: string;
  endAt: string;
  location: string | null;
  color: string | null;
  todoId: string | null;
}

const calendarEvents: MockEvent[] = [
  { id: 1, title: '小组讨论：课程项目', startAt: isoAt(1, 19, 0), endAt: isoAt(1, 20, 30), location: '图书馆研讨室 3', color: 'teal', todoId: null },
  { id: 2, title: '期中考试（机器学习）', startAt: isoAt(6, 10, 0), endAt: isoAt(6, 11, 30), location: '东上院 101', color: 'rose', todoId: null },
  { id: 3, title: '提交实验二报告', startAt: isoAt(2, 23, 29), endAt: isoAt(2, 23, 59), location: null, color: 'sand', todoId: 'todo-2' },
];

let todoItems: TodoItem[] = [
  { id: 'todo-1', title: '机器学习作业 3：决策树与集成学习', notes: '提交 PDF 与代码压缩包', dueAt: isoAt(0, 23, 59), done: false, source: 'canvas', sourceId: '301', courseName: '机器学习', courseId: 1, createdAt: isoAt(-2, 9), updatedAt: isoAt(-1, 9), calendarEventId: null },
  { id: 'todo-2', title: '操作系统实验二报告', notes: '分析平均等待时间', dueAt: isoAt(2, 23, 59), done: false, source: 'canvas', sourceId: '401', courseName: '操作系统', courseId: 2, createdAt: isoAt(-3, 9), updatedAt: isoAt(-1, 9), calendarEventId: 3 },
  { id: 'todo-3', title: '整理课堂视频笔记', notes: '', dueAt: isoAt(4, 20, 0), done: false, source: 'local', courseName: null, courseId: null, createdAt: isoAt(-1, 9), updatedAt: isoAt(-1, 9), calendarEventId: null },
  { id: 'todo-4', title: '物理作业 12', notes: '', dueAt: isoAt(4, 23, 59), done: false, source: 'canvas', sourceId: '501', courseName: '大学物理（下）', courseId: 3, createdAt: isoAt(-2, 9), updatedAt: isoAt(-2, 9), calendarEventId: null },
  { id: 'todo-5', title: '归还图书馆书籍', notes: '《操作系统概念》', dueAt: isoAt(-1, 18, 0), done: false, source: 'local', courseName: null, courseId: null, createdAt: isoAt(-4, 9), updatedAt: isoAt(-4, 9), calendarEventId: null },
  { id: 'todo-6', title: '完成线性回归作业', notes: '', dueAt: isoAt(-4, 23, 59), done: true, source: 'canvas', sourceId: '302', courseName: '机器学习', courseId: 1, createdAt: isoAt(-8, 9), updatedAt: isoAt(-4, 9), calendarEventId: null },
];

function todoState(): TodoState {
  const now = new Date();
  const today = new Date(now);
  today.setHours(0, 0, 0, 0);
  const tomorrow = new Date(today);
  tomorrow.setDate(tomorrow.getDate() + 1);
  let todayCount = 0;
  let overdue = 0;
  let completed = 0;
  for (const item of todoItems) {
    if (item.done) {
      completed += 1;
      continue;
    }
    if (!item.dueAt) continue;
    const due = new Date(item.dueAt);
    if (due < now) overdue += 1;
    if (due >= today && due < tomorrow) todayCount += 1;
  }
  return { items: todoItems, today: todayCount, overdue, completed };
}

const timetableState: TimetableState = {
  semester: '2026-2027-1',
  semesters: ['2026-2027-1', '2025-2026-2'],
  lastSyncedAt: isoAt(0, 8),
  semesterStart,
  connected: true,
};

function timetableQuery(startISO: string, endISO: string): TimetableQuery {
  const start = new Date(startISO);
  const end = new Date(endISO);
  const items: TimetableItem[] = [];
  for (const lesson of timetableLessons) {
    const startMinutes = classPeriods[lesson.startPeriod - 1]?.[0] ?? 8 * 60;
    const endMinutes = classPeriods[lesson.endPeriod - 1]?.[1] ?? 9 * 60;
    for (const week of lesson.weekNumbers) {
      const date = new Date(semesterStartDate);
      date.setDate(date.getDate() + (week - 1) * 7 + lesson.weekday - 1);
      const begin = new Date(date);
      begin.setHours(0, startMinutes, 0, 0);
      const finish = new Date(date);
      finish.setHours(0, endMinutes, 0, 0);
      if (finish > start && begin < end) {
        items.push({
          source: 'course',
          lessonId: lesson.id,
          title: lesson.courseName,
          startAt: begin.toISOString(),
          endAt: finish.toISOString(),
          location: lesson.classroom,
          color: lesson.courseCode === 'CS3308' ? 'blue' : lesson.courseCode === 'CS3560' ? 'teal' : 'sage',
          teacher: lesson.teacher,
        });
      }
    }
  }
  for (const event of calendarEvents) {
    const begin = new Date(event.startAt);
    const finish = new Date(event.endAt);
    if (finish > start && begin < end) {
      items.push({
        source: 'event', eventId: event.id, title: event.title,
        startAt: event.startAt, endAt: event.endAt, location: event.location,
        color: event.color, todoId: event.todoId ?? undefined,
      });
    }
  }
  for (const todo of todoItems) {
    if (!todo.dueAt || !todo.calendarEventId) continue;
    if (calendarEvents.some((event) => event.id === todo.calendarEventId)) continue;
    const due = new Date(todo.dueAt);
    const begin = new Date(due.getTime() - 30 * 60 * 1000);
    if (due > start && begin < end) {
      items.push({
        source: 'event', eventId: todo.calendarEventId, title: todo.title,
        startAt: begin.toISOString(), endAt: due.toISOString(),
        location: null, color: 'sand', todoId: todo.id,
      });
    }
  }
  items.sort((a, b) => a.startAt.localeCompare(b.startAt));
  return { semester: timetableState.semester, items, warnings: [] };
}

// ---------------------------------------------------------------------------
// Classroom
// ---------------------------------------------------------------------------

const videoCourses: VideoCourse[] = [
  { id: 1, code: 'CS3308-01', name: '机器学习' },
  { id: 2, code: 'CS3560-02', name: '操作系统' },
  { id: 3, code: 'PHY1202-05', name: '大学物理（下）' },
];

const classroomVideos: Record<number, ClassroomVideo[]> = {
  1: [
    { id: 101, start: isoAt(-2, 8, 0), end: isoAt(-2, 9, 40), room: '东上院 101', period: '1-2' },
    { id: 102, start: isoAt(-4, 10, 0), end: isoAt(-4, 11, 40), room: '东上院 101', period: '3-4' },
    { id: 103, start: isoAt(-9, 8, 0), end: isoAt(-9, 9, 40), room: '东上院 101', period: '1-2' },
  ],
  2: [{ id: 201, start: isoAt(-2, 14, 0), end: isoAt(-2, 15, 40), room: '东中院 4-201', period: '7-8' }],
  3: [{ id: 301, start: isoAt(-3, 8, 0), end: isoAt(-3, 9, 40), room: '上院 205', period: '1-2' }],
};

const classroomTranscript: VideoTranscript = {
  videoId: 101,
  segments: [
    { startMs: 0, endMs: 18000, text: '同学们好，今天我们继续讲决策树模型。' },
    { startMs: 18000, endMs: 42000, text: '上一次课我们介绍了信息熵，以及如何用信息增益来选择划分特征。' },
    { startMs: 42000, endMs: 70000, text: '今天我们会进一步讨论基尼指数，以及决策树容易过拟合的问题。' },
    { startMs: 70000, endMs: 102000, text: '先来看一个简单的例子：根据天气、湿度和风速判断是否适合去打网球。' },
    { startMs: 102000, endMs: 140000, text: '在这个数据集上，信息增益会选择天气作为根节点的划分特征。' },
    { startMs: 140000, endMs: 178000, text: '接下来我们比较信息增益和基尼指数的差异。两者都倾向于选择类别更纯的划分。' },
    { startMs: 178000, endMs: 215000, text: '当树的深度过大时，模型会记住训练集中的噪声，这就是过拟合。' },
    { startMs: 215000, endMs: 250000, text: '常见的解决方法是预剪枝和后剪枝。预剪枝在分裂前判断收益，后剪枝在树长成后再剪。' },
    { startMs: 250000, endMs: 290000, text: '最后我们简单介绍随机森林：通过样本和特征的随机采样，降低单棵树的方差。' },
  ],
};

const classroomDownloads: VideoDownload[] = [
  { id: '103/1', name: '课堂视频-103-画面1.mp4', received: 42_000_000, total: 42_000_000, state: 'completed' },
];

// ---------------------------------------------------------------------------
// Shuiyuan
// ---------------------------------------------------------------------------

const shuiyuanCategories: ShuiyuanCategory[] = [
  { id: 1, name: '课程交流', slug: 'courses', color: '2563eb', parentId: null, topicCount: 128 },
  { id: 2, name: '校园生活', slug: 'campus', color: '15803d', parentId: null, topicCount: 96 },
  { id: 3, name: '求职实习', slug: 'jobs', color: 'b45309', parentId: null, topicCount: 64 },
  { id: 4, name: '技术讨论', slug: 'tech', color: '7c3aed', parentId: 1, topicCount: 52 },
];

const shuiyuanTags = [
  { name: '选课', topicCount: 31 },
  { name: '机器学习', topicCount: 24 },
  { name: '实习', topicCount: 18 },
  { name: '考研', topicCount: 15 },
  { name: '社团', topicCount: 12 },
];

const shuiyuanTopics: ShuiyuanTopicSummary[] = [
  { id: 1, title: '机器学习课程项目组队', excerpt: '想找一个会写 Python 的同学一起做课程项目，方向是推荐系统。', categoryId: 1, tags: ['机器学习'], postsCount: 8, views: 320, likeCount: 12, createdAt: isoAt(-1, 10), lastPostedAt: isoAt(0, 9), pinned: true, closed: false, bookmarked: false, archetype: 'regular', lastPosterUsername: 'liming', authorUsername: 'student', authorAvatarTemplate: '/user_avatar/shuiyuan.sjtu.edu.cn/student/{size}/1_2.png' },
  { id: 2, title: '操作系统实验二踩坑记录', excerpt: '记录一下多级反馈队列调度器实现中遇到的几个问题。', categoryId: 1, tags: ['操作系统'], postsCount: 12, views: 540, likeCount: 21, createdAt: isoAt(-2, 16), lastPostedAt: isoAt(-1, 21), pinned: false, closed: false, bookmarked: true, archetype: 'regular', lastPosterUsername: 'zhang', authorUsername: 'zhang', authorAvatarTemplate: null },
  { id: 3, title: '2026 秋招时间线整理', excerpt: '整理互联网与制造业的秋招时间线，持续更新。', categoryId: 3, tags: ['实习'], postsCount: 25, views: 1280, likeCount: 48, createdAt: isoAt(-4, 9), lastPostedAt: isoAt(0, 8), pinned: false, closed: false, bookmarked: false, archetype: 'regular', lastPosterUsername: 'hr', authorUsername: 'hr', authorAvatarTemplate: null },
  { id: 4, title: '东区食堂新窗口测评', excerpt: '二楼新开的牛肉面窗口，量大实惠。', categoryId: 2, tags: [], postsCount: 6, views: 210, likeCount: 9, createdAt: isoAt(-1, 12), lastPostedAt: isoAt(-1, 18), pinned: false, closed: false, bookmarked: false, archetype: 'regular', lastPosterUsername: 'foodie', authorUsername: 'foodie', authorAvatarTemplate: null },
  { id: 5, title: '求推荐适合入门的编译器课程', excerpt: '想系统学习编译器，有没有推荐的公开课？', categoryId: 4, tags: ['课程'], postsCount: 4, views: 160, likeCount: 3, createdAt: isoAt(-3, 19), lastPostedAt: isoAt(-2, 10), pinned: false, closed: false, bookmarked: false, archetype: 'regular', lastPosterUsername: 'cs', authorUsername: 'cs', authorAvatarTemplate: null },
  { id: 6, title: '图书馆研讨室预约经验', excerpt: '分享几个预约研讨室的小技巧。', categoryId: 2, tags: [], postsCount: 3, views: 98, likeCount: 2, createdAt: isoAt(-5, 9), lastPostedAt: isoAt(-4, 16), pinned: false, closed: false, bookmarked: false, archetype: 'regular', lastPosterUsername: 'library', authorUsername: 'library', authorAvatarTemplate: null },
];

const shuiyuanPosts: ShuiyuanPost[] = [
  { id: 11, postNumber: 1, username: 'student', name: '同学 A', avatarTemplate: '/user_avatar/shuiyuan.sjtu.edu.cn/student/{size}/1_2.png', createdAt: isoAt(-1, 10), cooked: '<p>想找一个会写 Python 的同学一起做课程项目，方向是推荐系统。</p><p>目前已经完成了数据清洗，计划使用协同过滤 + 轻量排序模型。</p><ul><li>熟悉 pandas / NumPy</li><li>每周能投入 6 小时以上</li></ul><p>有兴趣的同学可以私信我。</p>', replyToPostNumber: null, likeCount: 12, liked: false, yours: false },
  { id: 12, postNumber: 2, username: 'liming', name: '李明', avatarTemplate: null, createdAt: isoAt(-1, 11), cooked: '<p>我做过一个类似的电影推荐项目，可以一起讨论。</p><blockquote><p>方向是推荐系统</p></blockquote>', replyToPostNumber: 1, likeCount: 5, liked: false, yours: false },
  { id: 13, postNumber: 3, username: 'zhang', name: '张同学', avatarTemplate: null, createdAt: isoAt(0, 9), cooked: '<p>我也想参加，主要熟悉后端和部署，可以负责接口和 Docker。</p>', replyToPostNumber: 1, likeCount: 3, liked: false, yours: false },
];

const shuiyuanDetail: ShuiyuanTopicDetail = {
  id: 1,
  title: '机器学习课程项目组队',
  categoryId: 1,
  tags: ['机器学习'],
  postsCount: 8,
  likeCount: 12,
  views: 320,
  createdAt: isoAt(-1, 10),
  lastPostedAt: isoAt(0, 9),
  closed: false,
  archetype: 'regular',
  bookmarked: false,
  stream: [11, 12, 13, 14, 15, 16, 17, 18],
  posts: shuiyuanPosts,
};

const shuiyuanState: ShuiyuanState = { connected: true, username: 'student', boundAt: isoAt(-30, 9) };

const shuiyuanCounts: ShuiyuanCounts = { notifications: 2, messages: 1 };

const shuiyuanBookmarks: ShuiyuanBookmarkItem[] = [
  { id: 1, topicId: 2, title: '操作系统实验二踩坑记录', excerpt: '记录一下多级反馈队列调度器实现中遇到的几个问题。', createdAt: isoAt(-2, 16), linkedPostNumber: null },
];

const shuiyuanNotifications: ShuiyuanNotificationItem[] = [
  { id: 1, type: 1, read: false, highPriority: false, createdAt: isoAt(0, 9), topicId: 1, postNumber: 3, fancyTitle: '机器学习课程项目组队', displayUsername: 'zhang' },
  { id: 2, type: 2, read: false, highPriority: true, createdAt: isoAt(-1, 21), topicId: 2, postNumber: 8, fancyTitle: '操作系统实验二踩坑记录', displayUsername: 'liming' },
  { id: 3, type: 1, read: true, highPriority: false, createdAt: isoAt(-2, 12), topicId: 3, postNumber: 4, fancyTitle: '2026 秋招时间线整理', displayUsername: 'hr' },
];

// ---------------------------------------------------------------------------
// Workspace
// ---------------------------------------------------------------------------

const workspaceProjects: WorkspaceProject[] = [
  { id: 'project-1', name: '我的笔记', path: 'C:\\Users\\student\\Documents\\SJTU Notes', createdAt: isoAt(-60, 9), lastOpenedAt: isoAt(0, 9) },
  { id: 'project-2', name: '机器学习课程', path: 'C:\\Users\\student\\Documents\\SJTU\\机器学习', createdAt: isoAt(-30, 9), lastOpenedAt: isoAt(-1, 20) },
];

const workspaceRecent: WorkspaceRecent[] = [
  { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\机器学习笔记.md', name: '机器学习笔记.md', projectId: 'project-1', openedAt: isoAt(0, 9) },
  { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\操作系统\\lab2.md', name: 'lab2.md', projectId: 'project-1', openedAt: isoAt(0, 8) },
  { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\课程资料\\lecture-06.pdf', name: 'lecture-06.pdf', projectId: 'project-1', openedAt: isoAt(-1, 20) },
];

const workspaceRoot: FileEntry[] = [
  { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\机器学习笔记.md', name: '机器学习笔记.md', isDir: false, size: 4_820, modifiedAt: isoAt(0, 9), ext: 'md' },
  { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\课程计划.md', name: '课程计划.md', isDir: false, size: 1_240, modifiedAt: isoAt(-1, 18), ext: 'md' },
  { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\操作系统', name: '操作系统', isDir: true, size: 0, modifiedAt: isoAt(0, 8), ext: '' },
  { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\课程资料', name: '课程资料', isDir: true, size: 0, modifiedAt: isoAt(-1, 20), ext: '' },
];

const workspaceChildren: Record<string, FileEntry[]> = {
  'C:\\Users\\student\\Documents\\SJTU Notes\\操作系统': [
    { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\操作系统\\lab2.md', name: 'lab2.md', isDir: false, size: 3_100, modifiedAt: isoAt(0, 8), ext: 'md' },
    { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\操作系统\\调度器.py', name: '调度器.py', isDir: false, size: 1_800, modifiedAt: isoAt(-2, 21), ext: 'py' },
  ],
  'C:\\Users\\student\\Documents\\SJTU Notes\\课程资料': [
    { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\课程资料\\lecture-06.pdf', name: 'lecture-06.pdf', isDir: false, size: 2_400_000, modifiedAt: isoAt(-1, 20), ext: 'pdf' },
    { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\课程资料\\trend.png', name: 'trend.png', isDir: false, size: 286_000, modifiedAt: isoAt(-1, 19), ext: 'png' },
    { path: 'C:\\Users\\student\\Documents\\SJTU Notes\\课程资料\\实验数据.xlsx', name: '实验数据.xlsx', isDir: false, size: 48_000, modifiedAt: isoAt(-3, 15), ext: 'xlsx' },
  ],
};

const workspaceTabs: Record<string, string> = {};

const markdownSample = `# 机器学习笔记

## 第 6 周 · 决策树与集成学习

### 信息增益

信息熵：

$$
H(D) = -\\sum_{i=1}^{n} p_i \\log_2 p_i
$$

信息增益选择使划分后熵下降最多的特征。

### CART

- 使用基尼指数
- 支持分类与回归
- 通过剪枝控制复杂度

> 作业 3 截止时间为今晚 23:59。

\`\`\`python
def gini(labels):
    total = len(labels)
    return 1 - sum((labels.count(label) / total) ** 2 for label in set(labels))
\`\`\`

\`\`\`mermaid
graph TD
  A[根节点] --> B[左子树]
  A --> C[右子树]
\`\`\`

| 模型 | 训练误差 | 验证误差 |
| --- | --- | --- |
| 决策树 | 0.02 | 0.18 |
| 随机森林 | 0.04 | 0.11 |
`;

function workspaceDoc(path: string): DocumentContent {
  const name = path.split(/[\\/]/).pop() ?? path;
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  if (ext === 'md') {
    return {
      path, title: name, format: 'markdown', text: markdownSample,
      revision: 'mock-rev-1', size: markdownSample.length, modifiedAt: isoAt(0, 9),
      assetBase: undefined, readOnly: false,
    };
  }
  if (ext === 'pdf') {
    return {
      path, title: name, format: 'html',
      text: `<div style="padding:30px;background:#fff;border:1px solid #d8dde5;border-radius:12px"><h1>${name}</h1><p>这是本地 PDF 的示例预览。真实应用中会通过 WebView2 的 PDF 查看器打开。</p><p>决策树、信息增益、剪枝与随机森林。</p></div>`,
      size: 2_400_000, modifiedAt: isoAt(-1, 20), readOnly: true,
    };
  }
  if (ext === 'png') {
    return { path, title: name, format: 'image', resourceUrl: svgPreview, size: 286_000, modifiedAt: isoAt(-1, 19), readOnly: true };
  }
  if (ext === 'xlsx') {
    return {
      path, title: name, format: 'html',
      text: '<table style="border-collapse:collapse"><tr><th style="border:1px solid #ddd;padding:6px">模型</th><th style="border:1px solid #ddd;padding:6px">准确率</th></tr><tr><td style="border:1px solid #ddd;padding:6px">决策树</td><td style="border:1px solid #ddd;padding:6px">0.82</td></tr><tr><td style="border:1px solid #ddd;padding:6px">随机森林</td><td style="border:1px solid #ddd;padding:6px">0.89</td></tr></table>',
      size: 48_000, modifiedAt: isoAt(-3, 15), readOnly: true,
    };
  }
  return {
    path, title: name, format: 'text', text: '# 示例文件\n\n这是本地文本文件预览。',
    revision: 'mock-rev-1', size: 1200, modifiedAt: isoAt(-2, 10), readOnly: false,
  };
}

// ---------------------------------------------------------------------------
// Mock dispatcher
// ---------------------------------------------------------------------------

interface MockNotification {
  id: number;
  kind: string;
  title: string;
  body: string;
  route: Record<string, unknown>;
  createdAt: string;
  readAt: string | null;
  snoozedUntil: string | null;
}

let notifications: MockNotification[] = [
  { id: 1, kind: 'ddl', title: 'DDL 临近：机器学习作业 3', body: '机器学习 · 今天 23:59', route: { view: 'canvas', canvasSourceId: 'c1:assignment:301' }, createdAt: isoAt(0, 8), readAt: null, snoozedUntil: null },
  { id: 2, kind: 'mail', title: '新邮件：【机器学习】作业 3 已发布', body: '课程助教', route: { view: 'mail', mailMessageId: 1 }, createdAt: isoAt(0, 8), readAt: null, snoozedUntil: null },
  { id: 3, kind: 'todo', title: '待办即将到期：整理课堂视频笔记', body: '4 天后', route: { view: 'todo', todoId: 'todo-3' }, createdAt: isoAt(-1, 9), readAt: isoAt(0, 9), snoozedUntil: null },
  { id: 4, kind: 'shuiyuan', title: '水源新回复：机器学习课程项目组队', body: 'zhang', route: { view: 'shuiyuan', shuiyuanTopicId: 1 }, createdAt: isoAt(0, 9), readAt: null, snoozedUntil: null },
];

function emitOptional(emit: ((name: string, ...data: unknown[]) => void) | undefined, name: string, payload: unknown) {
  emit?.(name, payload);
}

export async function mockCall<T>(
  channel: string,
  args: unknown[],
  emit?: (name: string, ...data: unknown[]) => void,
): Promise<T> {
  switch (channel) {
    case 'app.info':
      return mockInfo as T;
    case 'app.cacheSize':
      return 18_432_000 as T;
    case 'app.clearCache':
      return { freed: 18_432_000 } as T;
    case 'app.chooseDirectory':
      return 'C:\\Users\\student\\Documents\\SJTU Notes' as T;
    case 'app.chooseFiles':
      return 'C:\\Users\\student\\Documents\\SJTU Notes\\机器学习笔记.md' as T;
    case 'app.chooseSaveFile':
      return 'C:\\Users\\student\\Downloads\\export.zip' as T;
    case 'app.exportBackup':
      return 'C:\\Users\\student\\Downloads\\SJTU-backup-20261029.zip' as T;
    case 'app.openExternal':
    case 'app.openPath':
    case 'app.showInFolder':
    case 'app.openLogs':
    case 'app.openData':
      return true as T;
    case 'app.openAtLogin':
      return settings.openAtLogin as T;
    case 'campus.status':
      return { connected: true, cookies: 18, updatedAt: isoAt(0, 8) } as T;
    case 'settings.state':
      return settings as T;
    case 'settings.set': {
      const [key, value] = args as [keyof SettingsState, unknown];
      settings = { ...settings, [key]: value };
      saveMockSettings(settings);
      emitOptional(emit, 'settings.changed', settings);
      return settings as T;
    }
    case 'settings.setMany': {
      const [values] = args as [Partial<SettingsState>];
      settings = { ...settings, ...values };
      saveMockSettings(settings);
      emitOptional(emit, 'settings.changed', settings);
      return settings as T;
    }
    case 'canvas.state':
      return canvasState() as T;
    case 'canvas.configure':
      return canvasState() as T;
    case 'canvas.disconnect':
      return { connected: false, syncedAt: null, courses: [], items: [] } as T;
    case 'canvas.syncCourses':
    case 'canvas.syncCourse': {
      const next = canvasState();
      emitOptional(emit, 'canvas.state', next);
      emitOptional(emit, 'canvas.sync.finished', { syncedAt: new Date().toISOString() });
      return next as T;
    }
    case 'canvas.archiveCourse': {
      const [id, archived] = args as [number, boolean];
      const course = canvasCourses.find((item) => item.id === id);
      if (course) course.archived = archived;
      const next = canvasState();
      emitOptional(emit, 'canvas.state', next);
      return next as T;
    }
    case 'canvas.read': {
      const item = canvasItems.find((entry) => entry.id === args[0]);
      if (!item) throw new Error('资料不存在');
      return canvasDoc(item) as T;
    }
    case 'canvas.download':
      return { path: 'C:\\Users\\student\\AppData\\Local\\SJTU\\cache\\canvas\\mock-file.pdf' } as T;
    case 'canvas.addToProject':
      return 'C:\\Users\\student\\Documents\\SJTU Notes\\Canvas\\机器学习\\Slides\\lecture-06.pdf' as T;
    case 'canvas.exportCourseFiles':
      return { dir: String(args[1] ?? ''), exported: 18, failed: [] } as T;
    case 'canvas.cachedFiles':
      return [] as T;

    case 'mail.state':
      return mailState() as T;
    case 'mail.configure':
      return mailState() as T;
    case 'mail.disconnect':
      return { account: null, sync: mailState().sync } as T;
    case 'mail.sync': {
      const state = mailState();
      emitOptional(emit, 'mail.sync', state.sync);
      emitOptional(emit, 'mail.state', state);
      return state as T;
    }
    case 'mail.listFolders':
      return foldersWithCounts() as T;
    case 'mail.listMessages': {
      const folderId = Number(args[0]);
      const query = String(args[1] ?? '').toLowerCase();
      return mailMessages
        .filter((message) => message.folderId === folderId)
        .filter(
          (message) =>
            !query ||
            (message.subject ?? '').toLowerCase().includes(query) ||
            (message.sender?.name ?? message.sender?.address ?? '').toLowerCase().includes(query) ||
            (message.snippet ?? '').toLowerCase().includes(query),
        )
        .map(summaryOf) as T;
    }
    case 'mail.getMessage': {
      const id = Number(args[0]);
      const found = mailMessages.find((message) => message.id === id) ?? trash.find((message) => message.id === id);
      if (!found) throw new Error('邮件不存在');
      return found as T;
    }
    case 'mail.markSeen': {
      const id = Number(args[0]);
      const seen = Boolean(args[1]);
      const message = mailMessages.find((item) => item.id === id);
      if (message) message.seen = seen;
      emitOptional(emit, 'mail.state', mailState());
      return true as T;
    }
    case 'mail.markFlagged':
      return true as T;
    case 'mail.deleteMessage': {
      const id = Number(args[0]);
      const index = mailMessages.findIndex((message) => message.id === id);
      if (index >= 0) {
        const [message] = mailMessages.splice(index, 1);
        trash.unshift({
          ...summaryOf(message), folderName: '收件箱', deletedAt: new Date().toISOString(),
        });
      }
      emitOptional(emit, 'mail.state', mailState());
      return true as T;
    }
    case 'mail.restoreMessage': {
      const id = Number(args[0]);
      const item = trash.find((entry) => entry.id === id);
      if (item) {
        trash.splice(trash.indexOf(item), 1);
        mailMessages.push({
          ...item, folderId: 1, to: [], cc: [], bodyText: item.snippet ?? '', bodyHtml: `<p>${item.snippet ?? ''}</p>`, attachments: [],
        });
      }
      emitOptional(emit, 'mail.state', mailState());
      return true as T;
    }
    case 'mail.deleteForever': {
      const id = Number(args[0]);
      const index = trash.findIndex((entry) => entry.id === id);
      if (index >= 0) trash.splice(index, 1);
      return true as T;
    }
    case 'mail.emptyTrash':
      trash.length = 0;
      return 1 as T;
    case 'mail.trashList':
      return trash as T;
    case 'mail.attachmentUrl':
      return { url: svgPreview, filename: 'attachment.pdf' } as T;
    case 'mail.send':
      return { ok: true } as T;
    case 'mail.saveDraft': {
      const input = args[0] as Partial<MailDraft>;
      const draft: MailDraft = {
        id: input.id || `draft-${Date.now()}`, to: input.to ?? [], cc: input.cc ?? [], bcc: input.bcc ?? [],
        subject: input.subject ?? '', text: input.text ?? '', html: input.html ?? '', attachments: input.attachments ?? [],
        updatedAt: new Date().toISOString(),
      };
      const index = mailsDrafts.findIndex((item) => item.id === draft.id);
      if (index >= 0) mailsDrafts[index] = draft; else mailsDrafts.unshift(draft);
      return draft as T;
    }
    case 'mail.listDrafts':
      return mailsDrafts.map((draft) => ({ id: draft.id, to: draft.to, subject: draft.subject, updatedAt: draft.updatedAt, attachments: draft.attachments.length })) as T;
    case 'mail.getDraft': {
      const found = mailsDrafts.find((draft) => draft.id === args[0]);
      if (!found) throw new Error('草稿不存在');
      return found as T;
    }
    case 'mail.deleteDraft': {
      const index = mailsDrafts.findIndex((draft) => draft.id === args[0]);
      if (index >= 0) mailsDrafts.splice(index, 1);
      return true as T;
    }

    case 'timetable.state':
      return timetableState as T;
    case 'timetable.listLessons':
      return timetableLessons as T;
    case 'timetable.query':
      return timetableQuery(String(args[0]), String(args[1])) as T;
    case 'timetable.sync': {
      emitOptional(emit, 'timetable.changed', { semester: timetableState.semester });
      return { ok: true } as T;
    }
    case 'timetable.connect':
      return { ok: true } as T;
    case 'timetable.disconnect':
      return { ok: true } as T;
    case 'timetable.setSemesterStart': {
      timetableState.semesterStart = String(args[1]);
      emitOptional(emit, 'timetable.changed', { semester: timetableState.semester });
      return timetableState as T;
    }
    case 'timetable.createEvent': {
      const input = args[0] as Record<string, unknown>;
      const event: MockEvent = {
        id: Math.max(0, ...calendarEvents.map((item) => item.id)) + 1,
        title: String(input.title ?? '新日程'),
        startAt: String(input.startAt ?? new Date().toISOString()),
        endAt: String(input.endAt ?? new Date().toISOString()),
        location: input.location ? String(input.location) : null,
        color: input.color ? String(input.color) : 'blue',
        todoId: null,
      };
      calendarEvents.push(event);
      emitOptional(emit, 'timetable.changed', { eventId: event.id });
      return event as T;
    }
    case 'timetable.updateEvent': {
      const id = Number(args[0]);
      const input = args[1] as Record<string, unknown>;
      const event = calendarEvents.find((item) => item.id === id);
      if (!event) throw new Error('日程不存在');
      event.title = String(input.title ?? event.title);
      event.startAt = String(input.startAt ?? event.startAt);
      event.endAt = String(input.endAt ?? event.endAt);
      event.location = input.location ? String(input.location) : null;
      event.color = input.color ? String(input.color) : event.color;
      emitOptional(emit, 'timetable.changed', { eventId: id });
      return event as T;
    }
    case 'timetable.deleteEvent': {
      const id = Number(args[0]);
      const index = calendarEvents.findIndex((item) => item.id === id);
      if (index >= 0) calendarEvents.splice(index, 1);
      emitOptional(emit, 'timetable.changed', { deleted: id });
      return true as T;
    }

    case 'todo.state':
      return todoState() as T;
    case 'todo.list':
      return todoItems as T;
    case 'todo.refresh':
      return todoState() as T;
    case 'todo.save': {
      const input = args[0] as Record<string, unknown>;
      const id = String(input.id ?? '') || `todo-${Date.now()}`;
      const index = todoItems.findIndex((item) => item.id === id);
      const next: TodoItem = {
        id,
        title: String(input.title ?? ''),
        notes: String(input.notes ?? ''),
        dueAt: input.dueAt ? String(input.dueAt) : null,
        done: Boolean(input.done),
        source: index >= 0 ? todoItems[index].source : 'local',
        courseName: input.courseName ? String(input.courseName) : index >= 0 ? todoItems[index].courseName : null,
        courseId: index >= 0 ? todoItems[index].courseId : null,
        createdAt: index >= 0 ? todoItems[index].createdAt : new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        calendarEventId: input.addToCalendar && input.dueAt ? Math.floor(Math.random() * 1000 + 100) : null,
      };
      if (index >= 0) todoItems[index] = next; else todoItems.unshift(next);
      emitOptional(emit, 'todo.state', todoState());
      return next as T;
    }
    case 'todo.setDone': {
      const id = String(args[0]);
      const item = todoItems.find((entry) => entry.id === id);
      if (item) item.done = Boolean(args[1]);
      emitOptional(emit, 'todo.state', todoState());
      return true as T;
    }
    case 'todo.remove': {
      const id = String(args[0]);
      todoItems = todoItems.filter((item) => item.id !== id);
      emitOptional(emit, 'todo.state', todoState());
      return true as T;
    }
    case 'todo.markRead':
      return true as T;

    case 'classroom.state':
      return { connected: true } as T;
    case 'classroom.connect':
      return { ok: true } as T;
    case 'classroom.courses':
      return videoCourses as T;
    case 'classroom.videos':
      return (classroomVideos[Number(args[0])] ?? []) as T;
    case 'classroom.streams': {
      const streams: VideoStream[] = [
        { view: 1, duration: 290, playbackUrl: '' },
        { view: 2, duration: 290, playbackUrl: '' },
      ];
      return streams as T;
    }
    case 'classroom.transcript':
      return classroomTranscript as T;
    case 'classroom.progress':
      return { position: 42, duration: 290, updatedAt: isoAt(-1, 9) } as T;
    case 'classroom.saveProgress':
      return true as T;
    case 'classroom.downloads':
      return classroomDownloads as T;
    case 'classroom.download': {
      const [videoId, view] = args as [number, number];
      const id = `${videoId}/${view}`;
      const item: VideoDownload = {
        id, name: `课堂视频-${videoId}-画面${view}.mp4`, received: 0, total: 42_000_000, state: 'progressing',
      };
      classroomDownloads.push(item);
      emitOptional(emit, 'classroom.download', item);
      window.setTimeout(() => {
        item.received = item.total;
        item.state = 'completed';
        emitOptional(emit, 'classroom.download', item);
      }, 1800);
      return { started: true, id } as T;
    }
    case 'classroom.cancelDownload':
      return true as T;
    case 'classroom.revealDownload':
      return true as T;
    case 'classroom.exportTranscript':
      return { saved: true, path: 'C:\\Users\\student\\Downloads\\课堂视频-101-文稿.txt' } as T;
    case 'classroom.getDownloadDirectory':
      return settings.videoDownloadDir as T;
    case 'classroom.chooseDownloadDirectory': {
      settings = { ...settings, videoDownloadDir: 'C:\\Users\\student\\Videos\\SJTU' };
      saveMockSettings(settings);
      return settings.videoDownloadDir as T;
    }

    case 'shuiyuan.state':
      return shuiyuanState as T;
    case 'shuiyuan.startAuth':
      return { url: 'https://shuiyuan.sjtu.edu.cn/user-api-key/new?application_name=SJTU' } as T;
    case 'shuiyuan.bind':
      return shuiyuanState as T;
    case 'shuiyuan.unbind':
      return { connected: false, username: null, boundAt: null } as T;
    case 'shuiyuan.site':
      return {
        categories: shuiyuanCategories,
        tags: shuiyuanTags,
        customEmoji: [],
      } as T;
    case 'shuiyuan.topics': {
      const feed = args[0] as Record<string, unknown>;
      const kind = String(feed.kind ?? 'latest');
      let list = [...shuiyuanTopics];
      if (kind === 'category') list = list.filter((topic) => topic.categoryId === Number(feed.categoryId));
      if (kind === 'tag') list = list.filter((topic) => topic.tags.includes(String(feed.tag)));
      if (kind === 'search') {
        const text = String(feed.query ?? '').toLowerCase();
        list = list.filter((topic) => topic.title.toLowerCase().includes(text) || (topic.excerpt ?? '').toLowerCase().includes(text));
      }
      if (kind === 'featured') list = list.filter((topic) => topic.pinned || (topic.likeCount ?? 0) > 10);
      const page: ShuiyuanTopicPage = { topics: list, moreUrl: null };
      return page as T;
    }
    case 'shuiyuan.topic': {
      const id = Number(args[0]);
      const topic = shuiyuanTopics.find((item) => item.id === id);
      if (!topic) throw new Error('主题不存在');
      const base = id === 1 ? shuiyuanDetail : { ...shuiyuanDetail, id, title: topic.title, categoryId: topic.categoryId, tags: topic.tags, bookmarked: topic.bookmarked };
      return base as T;
    }
    case 'shuiyuan.loadPosts':
      return shuiyuanPosts as T;
    case 'shuiyuan.reply': {
      const topicId = Number(args[0]);
      const raw = String(args[1] ?? '');
      const post: ShuiyuanPost = {
        id: Date.now(), postNumber: shuiyuanDetail.postsCount + 1, username: 'student', name: '同学 A',
        avatarTemplate: null, createdAt: new Date().toISOString(), cooked: `<p>${raw}</p>`,
        replyToPostNumber: args[2] ? Number(args[2]) : null, likeCount: 0, liked: false, yours: true,
      };
      if (topicId === 1) shuiyuanDetail.posts.push(post);
      return post as T;
    }
    case 'shuiyuan.createTopic':
      return { topicId: 6 } as T;
    case 'shuiyuan.sendMessage':
      return 99 as T;
    case 'shuiyuan.likePost':
      return true as T;
    case 'shuiyuan.bookmarks':
      return shuiyuanBookmarks as T;
    case 'shuiyuan.toggleBookmark':
      return Boolean(args[1]) as T;
    case 'shuiyuan.notifications':
      return shuiyuanNotifications as T;
    case 'shuiyuan.markNotificationsRead':
      return true as T;
    case 'shuiyuan.counts':
      return shuiyuanCounts as T;
    case 'shuiyuan.uploadPath':
    case 'shuiyuan.upload':
      return { shortUrl: 'https://shuiyuan.sjtu.edu.cn/uploads/default/original/mock.png', url: 'https://shuiyuan.sjtu.edu.cn/uploads/default/original/mock.png', originalFilename: 'mock.png' } as T;

    case 'workspace.state':
      return { projects: workspaceProjects, recent: workspaceRecent } as WorkspaceStateResponse as T;
    case 'workspace.tree': {
      const projectId = String(args[0]);
      const path = String(args[1] ?? '');
      if (projectId !== 'project-1') return [] as T;
      if (!path) return workspaceRoot as T;
      return (workspaceChildren[path] ?? []) as T;
    }
    case 'workspace.read':
      return workspaceDoc(String(args[0])) as T;
    case 'workspace.save':
      return 'mock-rev-2' as T;
    case 'workspace.saveNoteImage':
      return 'assets/mock.png' as T;
    case 'workspace.create': {
      const [, parent, name, kind] = args as [string, string, string, string];
      const path = `${parent}\\${name}`;
      const entry: FileEntry = { path, name, isDir: kind === 'folder', size: 0, modifiedAt: new Date().toISOString(), ext: name.split('.').pop() ?? '' };
      if (parent === 'C:\\Users\\student\\Documents\\SJTU Notes') workspaceRoot.push(entry);
      else (workspaceChildren[parent] ??= []).push(entry);
      emitOptional(emit, 'workspace.changed', { path: parent });
      return entry as T;
    }
    case 'workspace.rename': {
      const path = String(args[0]);
      const name = String(args[1]);
      const newPath = `${path.slice(0, Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/')) + 1)}${name}`;
      for (const list of [workspaceRoot, ...Object.values(workspaceChildren)]) {
        const entry = list.find((item) => item.path === path);
        if (entry) {
          entry.path = newPath;
          entry.name = name;
        }
      }
      emitOptional(emit, 'workspace.changed', { path: newPath });
      return newPath as T;
    }
    case 'workspace.delete': {
      const path = String(args[0]);
      for (const [key, list] of Object.entries(workspaceChildren)) {
        workspaceChildren[key] = list.filter((item) => item.path !== path);
      }
      const index = workspaceRoot.findIndex((item) => item.path === path);
      if (index >= 0) workspaceRoot.splice(index, 1);
      emitOptional(emit, 'workspace.changed', { path });
      return true as T;
    }
    case 'workspace.recent':
      return workspaceRecent as T;
    case 'workspace.tabs':
      return (workspaceTabs[String(args[0])] ?? '') as T;
    case 'workspace.saveTabs':
      workspaceTabs[String(args[0])] = String(args[1]);
      return true as T;
    case 'workspace.assetBase':
      return 'http://127.0.0.1:5387/d/mock/' as T;
    case 'workspace.gitBranch':
      return 'main' as T;
    case 'workspace.gitBranches':
      return ['main', 'dev'] as T;
    case 'workspace.gitSwitch':
      return true as T;
    case 'workspace.addProject':
      return workspaceProjects[0] as T;
    case 'workspace.removeProject':
      return true as T;
    case 'workspace.renameProject':
      return workspaceProjects[0] as T;
    case 'workspace.openProjectDirectory':
    case 'workspace.openFile':
    case 'workspace.showInFolder':
      return true as T;
    case 'terminal.shells':
      return [{ name: 'Git Bash', path: 'C:\\Program Files\\Git\\bin\\bash.exe' }] as T;
    case 'terminal.start':
      throw new Error('浏览器预览不支持终端；请在 Wails 应用中打开。');
    case 'terminal.write':
    case 'terminal.resize':
    case 'terminal.close':
      return true as T;

    case 'search.query': {
      const text = String(args[0] ?? '').toLowerCase().trim();
      if (!text) return { groups: [] } as T;
      const groups = [] as Array<{ kind: string; label: string; items: Array<{ id: string; title: string; subtitle: string; route: Record<string, unknown> }> }>;
      const canvasMatches = canvasItems.filter((item) => item.name.toLowerCase().includes(text)).slice(0, 6);
      if (canvasMatches.length) {
        groups.push({
          kind: 'source', label: '课程资料',
          items: canvasMatches.map((item) => ({ id: item.id, title: item.name, subtitle: canvasCourses.find((course) => course.id === item.courseId)?.name ?? '', route: { view: 'canvas', canvasSourceId: item.id } })),
        });
      }
      const mailMatches = mailMessages.filter((item) => (item.subject ?? '').toLowerCase().includes(text)).slice(0, 5);
      if (mailMatches.length) {
        groups.push({
          kind: 'mail', label: '邮件',
          items: mailMatches.map((item) => ({ id: String(item.id), title: item.subject ?? '', subtitle: item.sender?.name ?? '', route: { view: 'mail', mailMessageId: item.id } })),
        });
      }
      const todoMatches = todoItems.filter((item) => item.title.toLowerCase().includes(text)).slice(0, 5);
      if (todoMatches.length) {
        groups.push({
          kind: 'todo', label: 'TODO',
          items: todoMatches.map((item) => ({ id: item.id, title: item.title, subtitle: item.courseName ?? '', route: { view: 'todo', todoId: item.id } })),
        });
      }
      const topicMatches = shuiyuanTopics.filter((item) => item.title.toLowerCase().includes(text)).slice(0, 5);
      if (topicMatches.length) {
        groups.push({
          kind: 'shuiyuan', label: '水源',
          items: topicMatches.map((item) => ({ id: String(item.id), title: item.title, subtitle: '水源主题', route: { view: 'shuiyuan', shuiyuanTopicId: item.id } })),
        });
      }
      return { groups } as T;
    }

    case 'notifications.list':
      return [...notifications] as T;
    case 'notifications.unread':
      return { unread: notifications.filter((item) => !item.readAt).length } as T;
    case 'notifications.refresh':
      return [...notifications] as T;
    case 'notifications.markRead': {
      const found = notifications.find((item) => item.id === Number(args[0]));
      if (found) found.readAt = new Date().toISOString();
      emitOptional(emit, 'notifications.changed', { unread: notifications.filter((item) => !item.readAt).length });
      return true as T;
    }
    case 'notifications.markAllRead':
      notifications = notifications.map((item) => ({ ...item, readAt: item.readAt ?? new Date().toISOString() }));
      emitOptional(emit, 'notifications.changed', { unread: 0 });
      return true as T;
    case 'notifications.snooze':
    case 'notifications.delete':
      return true as T;
    case 'update.check':
      return { available: false, current: '0.1.0', message: '浏览器预览模式未配置更新源。' } as T;

    default:
      return null as T;
  }
}
