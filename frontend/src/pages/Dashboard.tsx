import {
  ArrowRight,
  Bell,
  BookOpen,
  CalendarDays,
  CheckCircle2,
  Clock,
  FolderOpen,
  Inbox,
  ListTodo,
  Mail,
  MapPin,
  Plus,
  RefreshCw,
  Search,
  Settings2,
  Sparkles,
  Wifi,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { format } from 'date-fns';
import { zhCN } from 'date-fns/locale';
import { toast } from 'sonner';
import { call, hasBackend, on } from '@/lib/bridge';
import type {
  PortalMessage,
  PortalMessageState,
  TimetableItem,
  TodoItem,
} from '@/lib/models';
import { dayLabel, formatTime, relativeTime } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';
import { Button } from '@/components/ui/Button';
import { Card, EmptyState, Skeleton } from '@/components/ui/Misc';
import { useDashboard } from './dashboard-data';

function nextLessonHint(item: TimetableItem) {
  const start = new Date(item.startAt);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const day = new Date(start);
  day.setHours(0, 0, 0, 0);
  const days = Math.round((day.getTime() - today.getTime()) / 86_400_000);
  const prefix = days <= 0 ? '今天' : days === 1 ? '明天' : format(start, 'M月d日', { locale: zhCN });
  return `下一节 ${prefix} ${formatTime(item.startAt)} ${item.title}`;
}

function greeting() {
  const hour = new Date().getHours();
  if (hour < 6) return '夜深了';
  if (hour < 11) return '早上好';
  if (hour < 14) return '中午好';
  if (hour < 18) return '下午好';
  return '晚上好';
}

function atTime(hour: number, minute: number) {
  const date = new Date();
  date.setHours(hour, minute, 0, 0);
  return date.toISOString();
}

function demoLessons(): TimetableItem[] {
  return [
    {
      source: 'course',
      title: '机器学习',
      startAt: atTime(8, 0),
      endAt: atTime(9, 40),
      location: '东上院 101',
      color: 'blue',
    },
    {
      source: 'course',
      title: '操作系统',
      startAt: atTime(10, 0),
      endAt: atTime(11, 40),
      location: '东中院 4-201',
      color: 'teal',
    },
    {
      source: 'course',
      title: '大学物理（下）',
      startAt: atTime(14, 0),
      endAt: atTime(15, 40),
      location: '上院 205',
      color: 'sage',
    },
  ];
}

function demoTodos(): TodoItem[] {
  return [
    {
      id: 'demo-1',
      title: '机器学习作业 3：决策树与集成学习',
      notes: '',
      dueAt: atTime(23, 59),
      done: false,
      source: 'canvas',
      courseName: '机器学习',
      courseId: 1,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
    {
      id: 'demo-2',
      title: '操作系统实验二报告',
      notes: '',
      dueAt: new Date(Date.now() + 86400000 * 2).toISOString(),
      done: false,
      source: 'canvas',
      courseName: '操作系统',
      courseId: 2,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
    {
      id: 'demo-3',
      title: '整理课堂视频笔记',
      notes: '',
      dueAt: new Date(Date.now() + 86400000 * 4).toISOString(),
      done: false,
      source: 'local',
      courseName: null,
      courseId: null,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
    },
  ];
}

function demoRecent() {
  return [
    {
      path: 'C:\\Users\\student\\Documents\\SJTU\\机器学习\\notes.md',
      name: 'notes.md',
      projectId: 'demo',
      openedAt: new Date(Date.now() - 1800000).toISOString(),
    },
    {
      path: 'C:\\Users\\student\\Documents\\SJTU\\操作系统\\lab2.md',
      name: 'lab2.md',
      projectId: 'demo',
      openedAt: new Date(Date.now() - 7200000).toISOString(),
    },
    {
      path: 'C:\\Users\\student\\Documents\\SJTU\\课程资料\\mgmt.pdf',
      name: 'mgmt.pdf',
      projectId: 'demo',
      openedAt: new Date(Date.now() - 86400000).toISOString(),
    },
  ];
}

function StatCard({
  icon: Icon,
  label,
  value,
  hint,
  tone = 'neutral',
  onClick,
}: {
  icon: typeof Mail;
  label: string;
  value: string | number;
  hint: string;
  tone?: 'neutral' | 'accent' | 'success' | 'warning';
  onClick?: () => void;
}) {
  const tones = {
    neutral: 'bg-surface-3 text-ink-3',
    accent: 'bg-accent-soft text-accent',
    success: 'bg-success/10 text-success',
    warning: 'bg-warning/10 text-warning',
  };
  return (
    <button
      type="button"
      onClick={onClick}
      className="group flex items-center gap-3 rounded-card border border-line bg-surface p-4 text-left shadow-[var(--shadow-card)] transition-colors hover:border-line-strong"
    >
      <div className={cn('grid size-10 shrink-0 place-items-center rounded-xl', tones[tone])}>
        <Icon className="size-5" strokeWidth={1.8} />
      </div>
      <div className="min-w-0">
        <div className="text-[0.68rem] font-medium uppercase tracking-[0.1em] text-ink-3">{label}</div>
        <div className="mt-0.5 text-[1.35rem] font-semibold leading-none text-ink">{value}</div>
        <div className="mt-1 truncate text-[0.66rem] text-ink-3">{hint}</div>
      </div>
      <ArrowRight className="ml-auto size-4 shrink-0 text-ink-3 opacity-0 transition-opacity group-hover:opacity-100" />
    </button>
  );
}

function ConnectionRow({
  label,
  connected,
  detail,
  onConfigure,
}: {
  label: string;
  connected: boolean;
  detail: string;
  onConfigure: () => void;
}) {
  return (
    <div className="flex items-center gap-2.5 px-4 py-2.5">
      <span
        className={cn(
          'size-2 shrink-0 rounded-full',
          connected ? 'bg-success shadow-[0_0_0_3px_color-mix(in_oklab,var(--success)_18%,transparent)]' : 'bg-line-strong',
        )}
      />
      <div className="min-w-0 flex-1">
        <div className="text-xs font-medium text-ink">{label}</div>
        <div className="truncate text-[0.66rem] text-ink-3">{detail}</div>
      </div>
      <button
        type="button"
        onClick={onConfigure}
        className="rounded-md px-2 py-1 text-[0.66rem] font-medium text-accent transition-colors hover:bg-accent-soft"
      >
        {connected ? '管理' : '配置'}
      </button>
    </div>
  );
}

export function Dashboard() {
  const data = useDashboard();
  const navigate = useApp((state) => state.navigate);
  const setCommandOpen = useApp((state) => state.setCommandOpen);
  const appInfo = useSettings((state) => state.info);
  const mock = !hasBackend();

  const lessons = useMemo(
    () => (data.lessons.length ? data.lessons : mock ? demoLessons() : []),
    [data.lessons, mock],
  );
  const nextLesson = useMemo(() => {
    if (data.upcoming.length) return data.upcoming[0];
    return mock && lessons.length ? lessons.find((item) => new Date(item.endAt).getTime() > Date.now()) ?? null : null;
  }, [data.upcoming, lessons, mock]);
  const todos = useMemo(
    () => (data.todos.length ? data.todos : mock ? demoTodos() : []),
    [data.todos, mock],
  );
  const recent = useMemo(
    () => (data.recent.length ? data.recent : mock ? demoRecent() : []),
    [data.recent, mock],
  );

  const courses = data.canvas?.courses ?? [];
  const activeCourses = courses.filter((course) => !course.archived);
  const unread = data.folders.reduce((sum, folder) => sum + (folder.unread ?? 0), 0) || (mock ? 3 : 0);
  const dueTodos = todos
    .filter((todo) => !todo.done && todo.dueAt)
    .sort((a, b) => (a.dueAt ?? '').localeCompare(b.dueAt ?? ''));
  const todayTodos = dueTodos.filter((todo) => {
    if (!todo.dueAt) return false;
    const due = new Date(todo.dueAt);
    const today = new Date();
    return due.toDateString() === today.toDateString();
  });

  const canvasConnected = Boolean(data.canvas?.connected) || (mock && courses.length > 0);
  const mailConnected = Boolean(data.mail?.account) || mock;
  const campusConnected = Boolean(data.campus?.connected) || mock;
  const shuiyuanEnabled = useSettings((state) => state.state.experiments.shuiyuan);
  const shuiyuanConnected = Boolean(data.shuiyuan?.connected) || mock;

  const syncCanvas = async () => {
    toast.promise(call('canvas.syncCourses'), {
      loading: '正在同步 Canvas…',
      success: 'Canvas 同步完成',
      error: (error) => (error instanceof Error ? error.message : '同步失败'),
    });
  };

  const syncMail = async () => {
    toast.promise(call('mail.sync'), {
      loading: '正在收取邮件…',
      success: '邮件同步完成',
      error: (error) => (error instanceof Error ? error.message : '同步失败'),
    });
  };

  return (
    <div className="scroll-thin h-full overflow-y-auto">
      <div className="mx-auto max-w-[92rem] px-6 pb-10 pt-6">
        <section className="relative overflow-hidden rounded-panel border border-line bg-surface p-6 shadow-[var(--shadow-card)]">
          <div className="pointer-events-none absolute -right-20 -top-24 size-72 rounded-full bg-accent/10 blur-3xl" />
          <div className="pointer-events-none absolute -bottom-28 left-1/3 size-64 rounded-full bg-success/10 blur-3xl" />
          <div className="relative flex flex-wrap items-start justify-between gap-5">
            <div className="min-w-0">
              <div className="text-[0.68rem] font-semibold uppercase tracking-[0.16em] text-accent">
                {format(new Date(), 'M月d日 EEEE', { locale: zhCN })}
              </div>
              <h1 className="mt-2 text-[1.65rem] font-semibold tracking-[-0.02em] text-ink">
                {greeting()}，同学
              </h1>
              <p className="mt-1.5 max-w-xl text-xs leading-relaxed text-ink-2">
                {lessons.length > 0
                  ? `今天有 ${lessons.length} 节课`
                  : '今天没有课程安排'}
                {todayTodos.length > 0 ? `，${todayTodos.length} 项任务今天到期` : ''}
                {unread > 0 ? `，${unread} 封未读邮件` : ''}。
              </p>
              <div className="mt-4 flex flex-wrap items-center gap-2">
                <StatusChip label="Canvas" ok={canvasConnected} />
                <StatusChip label="邮箱" ok={mailConnected} />
                <StatusChip label="教学网" ok={campusConnected} />
                {shuiyuanEnabled ? <StatusChip label="水源" ok={shuiyuanConnected} /> : null}
              </div>
            </div>
            <div className="flex shrink-0 items-center gap-2">
              <Button variant="secondary" onClick={() => setCommandOpen(true)}>
                <Search className="size-4" /> 全局搜索
              </Button>
              <Button variant="primary" onClick={() => navigate('workspace')}>
                <Plus className="size-4" /> 打开工作台
              </Button>
            </div>
          </div>
        </section>

        <section className="mt-4 grid grid-cols-2 gap-3 lg:grid-cols-4">
          <StatCard
            icon={CalendarDays}
            label="今日课程"
            value={data.loading ? '…' : lessons.length}
            hint={nextLesson ? nextLessonHint(nextLesson) : lessons.length ? '今天的课已结束' : '近期没有课程'}
            tone="accent"
            onClick={() => navigate('timetable')}
          />
          <StatCard
            icon={ListTodo}
            label="临近 DDL"
            value={data.loading ? '…' : dueTodos.length}
            hint={dueTodos[0] ? `${dayLabel(dueTodos[0].dueAt)} · ${dueTodos[0].title}` : '暂无待办'}
            tone="warning"
            onClick={() => navigate('todo')}
          />
          <StatCard
            icon={Mail}
            label="未读邮件"
            value={data.loading && !mock ? '…' : unread}
            hint={data.mail?.account?.address ?? (mock ? 'student@sjtu.edu.cn' : '尚未配置邮箱')}
            tone="success"
            onClick={() => navigate('mail')}
          />
          <StatCard
            icon={BookOpen}
            label="在学课程"
            value={data.loading && !mock ? '…' : activeCourses.length || (mock ? 6 : 0)}
            hint={
              data.canvas?.syncedAt
                ? `上次同步 ${relativeTime(data.canvas.syncedAt)}`
                : '连接 Canvas 后自动汇总'
            }
            onClick={() => navigate('canvas')}
          />
        </section>

        <div className="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
          <div className="space-y-4 xl:col-span-2">
            <Card>
              <div className="flex items-center justify-between border-b border-line px-4 py-3">
                <div className="flex items-center gap-2">
                  <CalendarDays className="size-4 text-accent" />
                  <h2 className="text-sm font-semibold text-ink">今日安排</h2>
                </div>
                <Button size="sm" variant="ghost" onClick={() => navigate('timetable')}>
                  打开日程 <ArrowRight className="size-3.5" />
                </Button>
              </div>
              {data.loading && !mock ? (
                <div className="space-y-2 p-4">
                  <Skeleton className="h-12 w-full" />
                  <Skeleton className="h-12 w-full" />
                </div>
              ) : lessons.length === 0 ? (
                <EmptyState
                  compact
                  icon={CalendarDays}
                  title="今天没有课程"
                  description="可以去日程看看本周安排，或者新建一个日程。"
                  action={<Button size="sm" onClick={() => navigate('timetable')}>查看日程</Button>}
                />
              ) : (
                <div className="divide-y divide-line">
                  {lessons.map((lesson, index) => (
                    <button
                      key={`${lesson.title}-${lesson.startAt}-${index}`}
                      type="button"
                      onClick={() => navigate('timetable', { timetableDate: lesson.startAt })}
                      className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-hover"
                    >
                      <div className="w-12 shrink-0 text-right">
                        <div className="text-xs font-semibold text-ink">{formatTime(lesson.startAt)}</div>
                        <div className="mt-0.5 text-[0.62rem] text-ink-3">{formatTime(lesson.endAt)}</div>
                      </div>
                      <div
                        className={cn(
                          'h-9 w-1 shrink-0 rounded-full',
                          lesson.source === 'event'
                            ? 'bg-warning'
                            : lesson.source === 'todo'
                              ? 'bg-success'
                              : 'bg-accent',
                        )}
                      />
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium text-ink">{lesson.title}</div>
                        <div className="mt-0.5 flex items-center gap-3 text-[0.66rem] text-ink-3">
                          {lesson.location ? (
                            <span className="inline-flex items-center gap-1">
                              <MapPin className="size-3" /> {lesson.location}
                            </span>
                          ) : null}
                          <span className="inline-flex items-center gap-1">
                            <Clock className="size-3" />
                            {relativeTime(lesson.startAt)}
                          </span>
                        </div>
                      </div>
                      <ArrowRight className="size-4 shrink-0 text-ink-3" />
                    </button>
                  ))}
                </div>
              )}
            </Card>

            <Card>
              <div className="flex items-center justify-between border-b border-line px-4 py-3">
                <div className="flex items-center gap-2">
                  <FolderOpen className="size-4 text-accent" />
                  <h2 className="text-sm font-semibold text-ink">最近打开</h2>
                </div>
                <Button size="sm" variant="ghost" onClick={() => navigate('workspace')}>
                  工作台 <ArrowRight className="size-3.5" />
                </Button>
              </div>
              {recent.length === 0 ? (
                <EmptyState
                  compact
                  icon={FolderOpen}
                  title="还没有最近文件"
                  description="在工作台打开笔记或课件后，会在这里快速回到它们。"
                />
              ) : (
                <div className="grid gap-2 p-3 sm:grid-cols-2">
                  {recent.slice(0, 6).map((file) => (
                    <button
                      key={file.path}
                      type="button"
                      onClick={() => navigate('workspace', { workspacePath: file.path })}
                      className="flex min-w-0 items-center gap-2.5 rounded-lg border border-line bg-surface-2 px-3 py-2.5 text-left transition-colors hover:border-line-strong hover:bg-surface-3"
                    >
                      <FileGlyph name={file.name} />
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-xs font-medium text-ink">{file.name}</div>
                        <div className="truncate text-[0.62rem] text-ink-3">{file.path}</div>
                      </div>
                      <span className="shrink-0 text-[0.62rem] text-ink-3">
                        {relativeTime(file.openedAt)}
                      </span>
                    </button>
                  ))}
                </div>
              )}
            </Card>
          </div>

          <div className="space-y-4">
            <Card>
              <div className="flex items-center justify-between border-b border-line px-4 py-3">
                <div className="flex items-center gap-2">
                  <ListTodo className="size-4 text-warning" />
                  <h2 className="text-sm font-semibold text-ink">临近 DDL</h2>
                </div>
                <Button size="sm" variant="ghost" onClick={() => navigate('todo')}>
                  全部 <ArrowRight className="size-3.5" />
                </Button>
              </div>
              {dueTodos.length === 0 ? (
                <EmptyState
                  compact
                  icon={CheckCircle2}
                  title="没有临近的 DDL"
                  description="Canvas 作业截止时间和本地待办会汇总在这里。"
                />
              ) : (
                <div className="divide-y divide-line">
                  {dueTodos.slice(0, 4).map((todo) => (
                    <button
                      key={todo.id}
                      type="button"
                      onClick={() => navigate('todo', { todoId: todo.id })}
                      className="flex w-full items-start gap-2.5 px-4 py-3 text-left transition-colors hover:bg-hover"
                    >
                      <span className="mt-1 size-2 shrink-0 rounded-full bg-warning" />
                      <div className="min-w-0 flex-1">
                        <div className="line-clamp-2 text-xs font-medium leading-relaxed text-ink">
                          {todo.title}
                        </div>
                        <div className="mt-1 flex items-center gap-2 text-[0.62rem] text-ink-3">
                          {todo.courseName ? <span>{todo.courseName}</span> : null}
                          <span>{dayLabel(todo.dueAt)}</span>
                          {todo.source === 'canvas' ? (
                            <span className="rounded bg-accent-soft px-1 py-0.5 text-accent">Canvas</span>
                          ) : null}
                        </div>
                      </div>
                    </button>
                  ))}
                </div>
              )}
            </Card>

            <PortalMessageCard />

            <Card>
              <div className="flex items-center gap-2 border-b border-line px-4 py-3">
                <Wifi className="size-4 text-success" />
                <h2 className="text-sm font-semibold text-ink">账号连接</h2>
              </div>
              <div className="divide-y divide-line">
                <ConnectionRow
                  label="Canvas"
                  connected={canvasConnected}
                  detail={canvasConnected ? `${activeCourses.length} 门课程` : '使用 Access Token 连接'}
                  onConfigure={() => navigate('settings', { settingsSection: 'accounts' })}
                />
                <ConnectionRow
                  label="校园邮箱"
                  connected={mailConnected}
                  detail={
                    data.mail?.account?.address ??
                    (mock ? 'student@sjtu.edu.cn' : 'IMAP / SMTP 只保存在本机')
                  }
                  onConfigure={() => navigate('settings', { settingsSection: 'accounts' })}
                />
                <ConnectionRow
                  label="教学信息服务网"
                  connected={campusConnected}
                  detail={campusConnected ? '日程与课堂视频可用' : '登录后同步日程'}
                  onConfigure={() => navigate('settings', { settingsSection: 'accounts' })}
                />
                {shuiyuanEnabled ? (
                  <ConnectionRow
                    label="水源社区"
                    connected={shuiyuanConnected}
                    detail={
                      data.shuiyuan?.username
                        ? `@${data.shuiyuan.username} · Discourse User API Key`
                        : 'Discourse User API Key'
                    }
                    onConfigure={() => navigate('shuiyuan')}
                  />
                ) : null}
              </div>
            </Card>

            <Card>
              <div className="flex items-center gap-2 border-b border-line px-4 py-3">
                <Sparkles className="size-4 text-accent" />
                <h2 className="text-sm font-semibold text-ink">快捷操作</h2>
              </div>
              <div className="grid grid-cols-2 gap-2 p-3">
                <QuickAction
                  icon={FolderOpen}
                  label="新建笔记"
                  onClick={() => navigate('workspace', { newNote: true })}
                />
                <QuickAction icon={CalendarDays} label="查看日程" onClick={() => navigate('timetable')} />
                <QuickAction icon={RefreshCw} label="同步 Canvas" onClick={() => void syncCanvas()} />
                <QuickAction icon={Inbox} label="收取邮件" onClick={() => void syncMail()} />
                <QuickAction
                  icon={Settings2}
                  label="全部设置"
                  onClick={() => navigate('settings')}
                />
                <QuickAction
                  icon={Bell}
                  label="通知中心"
                  onClick={() => useApp.getState().navigate('notifications')}
                />
              </div>
            </Card>
          </div>
        </div>

        <div className="mt-6 text-center text-[0.62rem] text-ink-3">
          SJTU · 本地优先的校园桌面 · v{appInfo?.version ?? '0.1.0'}
        </div>
      </div>
    </div>
  );
}

function StatusChip({ label, ok }: { label: string; ok: boolean }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[0.66rem] font-medium',
        ok
          ? 'border-success/25 bg-success/10 text-success'
          : 'border-line bg-surface-2 text-ink-3',
      )}
    >
      <span className={cn('size-1.5 rounded-full', ok ? 'bg-success' : 'bg-line-strong')} />
      {label}
    </span>
  );
}

function FileGlyph({ name }: { name: string }) {
  const extension = name.split('.').pop()?.toLowerCase();
  const tone =
    extension === 'pdf'
      ? 'bg-danger/10 text-danger'
      : extension === 'md'
        ? 'bg-accent-soft text-accent'
        : 'bg-surface-3 text-ink-3';
  return (
    <span className={cn('grid size-8 shrink-0 place-items-center rounded-lg text-[0.6rem] font-semibold uppercase', tone)}>
      {extension?.slice(0, 4) ?? 'file'}
    </span>
  );
}

function QuickAction({
  icon: Icon,
  label,
  onClick,
}: {
  icon: typeof Mail;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex items-center gap-2 rounded-lg border border-line bg-surface-2 px-2.5 py-2 text-left text-xs font-medium text-ink-2 transition-colors hover:border-line-strong hover:bg-surface-3 hover:text-ink"
    >
      <Icon className="size-3.5 text-ink-3" />
      <span className="truncate">{label}</span>
    </button>
  );
}


function PortalMessageCard() {
  const [state, setState] = useState<PortalMessageState | null>(null);
  const [messages, setMessages] = useState<PortalMessage[]>([]);
  const [busy, setBusy] = useState(false);
  const navigate = useApp((state2) => state2.navigate);

  const apply = (list: PortalMessage[], next: PortalMessageState) => {
    setMessages(list.slice(0, 5));
    setState(next);
  };

  const load = useCallback(() => {
    setBusy(true);
    Promise.allSettled([
      call<PortalMessage[]>('portalMessage.list', 5),
      call<PortalMessageState>('portalMessage.state'),
    ])
      .then(([listResult, stateResult]) => {
        if (stateResult.status === 'fulfilled') {
          apply(listResult.status === 'fulfilled' ? listResult.value ?? [] : [], stateResult.value);
        }
      })
      .finally(() => setBusy(false));
  }, []);

  useEffect(() => {
    load();
    const off = on('portalMessage.changed', () => load());
    return () => off();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refresh = () => {
    setBusy(true);
    void call<PortalMessageState>('portalMessage.refresh')
      .then((next) => setState(next))
      .catch((cause) => toast.error(cause instanceof Error ? cause.message : '读取交我办消息失败'))
      .finally(() => load());
  };

  return (
    <Card>
      <div className="flex items-center gap-2 border-b border-line px-4 py-3">
        <Inbox className="size-4 text-accent" />
        <h2 className="text-sm font-semibold text-ink">交我办消息</h2>
        {state?.unread ? (
          <span className="rounded-full bg-danger/10 px-1.5 py-0.5 text-[0.6rem] font-medium text-danger">
            {state.unread} 未读
          </span>
        ) : null}
        <Button size="sm" variant="ghost" className="ml-auto" onClick={refresh} disabled={busy}>
          <RefreshCw className={cn('size-3.5', busy && 'animate-spin')} /> 刷新
        </Button>
      </div>
      {messages.length === 0 ? (
        <div className="px-4 py-4 text-[0.68rem] leading-relaxed text-ink-3">
          {state?.error || '暂无未读消息。登录后每 10 分钟自动同步，新消息会进入通知中心并弹出系统通知。'}
        </div>
      ) : (
        <div className="divide-y divide-line">
          {messages.map((message) => (
            <button
              key={message.id}
              type="button"
              onClick={() =>
                message.url
                  ? void call('app.openExternal', message.url)
                  : navigate('notifications')
              }
              className="block w-full px-4 py-2.5 text-left transition-colors hover:bg-hover"
            >
              <div className="flex items-baseline gap-2">
                <span className="min-w-0 flex-1 truncate text-xs font-medium text-ink">
                  {message.title}
                </span>
                <span className="shrink-0 text-[0.6rem] text-ink-3">
                  {message.createdAt ? message.createdAt.slice(5, 16) : ''}
                </span>
              </div>
              {message.body ? (
                <div className="mt-0.5 line-clamp-2 text-[0.66rem] leading-relaxed text-ink-3">
                  {message.body}
                </div>
              ) : null}
            </button>
          ))}
        </div>
      )}
    </Card>
  );
}
