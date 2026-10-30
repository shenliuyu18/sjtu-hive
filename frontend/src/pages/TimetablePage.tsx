import {
  CalendarDays,
  GraduationCap,
  ChevronLeft,
  ChevronRight,
  Clock,
  Loader2,
  MapPin,
  Plus,
  RefreshCw,
  Trash2,
  Wifi,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  addDays,
  addMonths,
  addWeeks,
  endOfDay,
  endOfWeek,
  format,
  isSameMonth,
  startOfDay,
  startOfWeek,
} from 'date-fns';
import { zhCN } from 'date-fns/locale';
import { call, on } from '@/lib/bridge';
import type {
  CalendarEvent,
  ExamItem,
  ExamState,
  SchoolCalendarEvent,
  SchoolCalendarState,
} from '@/lib/models';
import { formatDate, formatTime, isoDate, monthMatrix, sameDay } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';

type ViewMode = 'week' | 'month';

interface EventForm {
  title: string;
  start: string;
  end: string;
  location: string;
  color: string;
}

interface ScheduleItem {
  key: string;
  title: string;
  startAt: string;
  endAt: string;
  location: string | null;
  color: string | null;
  allDay: boolean;
  source: 'school' | 'event' | 'todo' | 'exam';
  school?: SchoolCalendarEvent;
  local?: CalendarEvent;
  exam?: ExamItem;
}

const colorKeys = ['rose', 'peach', 'sand', 'sage', 'teal', 'blue', 'lilac', 'slate'];
const START_HOUR = 7;
const END_HOUR = 23;
const HOUR_HEIGHT = 52;
const weekDayLabels = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

function toLocalInput(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  const pad = (number: number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function toISO(value: string) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  return date.toISOString();
}

function defaultRange(day: Date) {
  const start = new Date(day);
  start.setHours(9, 0, 0, 0);
  const end = new Date(start);
  end.setHours(10, 0, 0, 0);
  return { start: toLocalInput(start.toISOString()), end: toLocalInput(end.toISOString()) };
}

function schoolStyle(event?: SchoolCalendarEvent) {
  if (!event?.color) return undefined;
  return {
    backgroundColor: `${event.color}22`,
    borderColor: event.color,
  };
}

export function TimetablePage() {
  const navigate = useApp((state) => state.navigate);
  const [view, setView] = useState<ViewMode>('week');
  const [calendarState, setCalendarState] = useState<SchoolCalendarState | null>(null);
  const [schoolEvents, setSchoolEvents] = useState<SchoolCalendarEvent[]>([]);
  const [localEvents, setLocalEvents] = useState<CalendarEvent[]>([]);
  const [calendarError, setCalendarError] = useState('');
  const [exams, setExams] = useState<ExamItem[]>([]);
  const [examState, setExamState] = useState<ExamState | null>(null);
  const [cursor, setCursor] = useState(() => new Date());
  const [selectedDay, setSelectedDay] = useState(() => new Date());
  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [tick, setTick] = useState(0);
  const [dialogOpen, setDialogOpen] = useState(false);
  const [detailSchool, setDetailSchool] = useState<SchoolCalendarEvent | null>(null);
  const [editing, setEditing] = useState<{ id: number; todoId?: string | null } | null>(null);
  const [form, setForm] = useState<EventForm>({ title: '', start: '', end: '', location: '', color: 'blue' });
  const [saving, setSaving] = useState(false);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  const weekStart = useMemo(() => startOfDay(startOfWeek(cursor, { weekStartsOn: 1 })), [cursor]);
  const weekEnd = useMemo(() => endOfDay(endOfWeek(cursor, { weekStartsOn: 1 })), [cursor]);
  const monthDays = useMemo(() => monthMatrix(cursor.getFullYear(), cursor.getMonth()), [cursor]);

  const range = useMemo(() => {
    if (view === 'week') {
      return { start: weekStart, end: addDays(weekEnd, 1) };
    }
    return { start: monthDays[0], end: addDays(monthDays[monthDays.length - 1], 1) };
  }, [view, weekStart, weekEnd, monthDays]);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    Promise.allSettled([
      call<SchoolCalendarState>('schoolCalendar.state'),
      call<SchoolCalendarEvent[]>(
        'schoolCalendar.query',
        range.start.toISOString(),
        range.end.toISOString(),
      ),
      call<CalendarEvent[]>('timetable.listEvents', range.start.toISOString(), range.end.toISOString()),
      call<ExamItem[]>('exam.list'),
      call<ExamState>('exam.state'),
    ]).then(([stateResult, schoolResult, localResult, examResult, examStateResult]) => {
      if (!alive) return;
      if (stateResult.status === 'fulfilled') {
        setCalendarState(stateResult.value);
        setCalendarError(stateResult.value?.error ?? '');
      }
      if (schoolResult.status === 'fulfilled') {
        setSchoolEvents(schoolResult.value ?? []);
        setCalendarError('');
      } else {
        setCalendarError(
          schoolResult.reason instanceof Error ? schoolResult.reason.message : String(schoolResult.reason),
        );
      }
      if (localResult.status === 'fulfilled') {
        setLocalEvents(localResult.value ?? []);
      }
      if (examResult.status === 'fulfilled') setExams(examResult.value ?? []);
      if (examStateResult.status === 'fulfilled') setExamState(examStateResult.value);
    }).finally(() => {
      if (alive) setLoading(false);
    });
    return () => {
      alive = false;
    };
  }, [range.start, range.end, tick]);

  useEffect(() => {
    void call('schoolCalendar.sync').catch(() => {});
  }, []);

  useEffect(() => {
    const offCalendar = on('schoolCalendar.changed', () => setTick((value) => value + 1));
    const offTimetable = on('timetable.changed', () => setTick((value) => value + 1));
    return () => {
      offCalendar();
      offTimetable();
    };
  }, []);

  const allItems = useMemo<ScheduleItem[]>(() => {
    const items: ScheduleItem[] = schoolEvents.map((event) => ({
      key: `school-${event.id}`,
      title: event.title,
      startAt: event.startAt,
      endAt: event.endAt,
      location: event.location,
      color: event.color,
      allDay: event.allDay,
      source: 'school',
      school: event,
    }));
    for (const event of localEvents) {
      items.push({
        key: `${event.todoId ? 'todo' : 'event'}-${event.id}`,
        title: event.title,
        startAt: event.startAt,
        endAt: event.endAt,
        location: event.location,
        color: event.color,
        allDay: false,
        source: event.todoId ? 'todo' : 'event',
        local: event,
      });
    }
    for (const exam of exams) {
      items.push({
        key: `exam-${exam.id}`,
        title: exam.courseName,
        startAt: exam.startAt || `${exam.examDate}T00:00:00`,
        endAt: exam.endAt || `${exam.examDate}T23:59:59`,
        location: exam.classroom,
        color: null,
        allDay: !exam.startAt,
        source: 'exam',
        exam,
      });
    }
    return items.sort((a, b) => a.startAt.localeCompare(b.startAt));
  }, [schoolEvents, localEvents, exams]);

  const itemsByDay = useMemo(() => {
    const map = new Map<string, ScheduleItem[]>();
    for (const item of allItems) {
      const key = formatDate(item.startAt, 'yyyy-MM-dd');
      const list = map.get(key) ?? [];
      list.push(item);
      map.set(key, list);
    }
    for (const list of map.values()) {
      list.sort((a, b) => a.startAt.localeCompare(b.startAt));
    }
    return map;
  }, [allItems]);

  const selectedItems = itemsByDay.get(isoDate(selectedDay)) ?? [];
  const upcomingExams = useMemo(
    () =>
      exams
        .filter((exam) => !exam.startAt || new Date(exam.startAt).getTime() >= Date.now() - 3 * 3600_000)
        .slice(0, 6),
    [exams],
  );

  const openCreate = (day: Date) => {
    const next = defaultRange(day);
    setEditing(null);
    setForm({ title: '', start: next.start, end: next.end, location: '', color: 'blue' });
    setDialogOpen(true);
  };

  const openItem = (item: ScheduleItem) => {
    const day = new Date(item.startAt);
    setSelectedDay(day);
    setCursor(day);
    if (item.source === 'school' && item.school) {
      setDetailSchool(item.school);
      return;
    }
    if (item.source === 'exam') return;
    if (!item.local) return;
    if (item.source === 'todo') {
      navigate('todo', { todoId: item.local.todoId ?? undefined });
      return;
    }
    setEditing({ id: Number(item.local.id), todoId: item.local.todoId ?? null });
    setForm({
      title: item.local.title,
      start: toLocalInput(item.local.startAt),
      end: toLocalInput(item.local.endAt),
      location: item.local.location ?? '',
      color: item.local.color ?? 'blue',
    });
    setDialogOpen(true);
  };

  const saveEvent = async () => {
    if (!form.title.trim()) {
      toast.error('请填写事件标题');
      return;
    }
    const startAt = toISO(form.start);
    const endAt = toISO(form.end);
    if (!startAt || !endAt) {
      toast.error('请选择开始和结束时间');
      return;
    }
    if (new Date(endAt) <= new Date(startAt)) {
      toast.error('结束时间必须晚于开始时间');
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await call<CalendarEvent>('timetable.updateEvent', editing.id, {
          title: form.title.trim(),
          startAt,
          endAt,
          location: form.location.trim(),
          color: form.color,
        });
        toast.success('日程已更新');
      } else {
        await call<CalendarEvent>('timetable.createEvent', {
          title: form.title.trim(),
          startAt,
          endAt,
          location: form.location.trim(),
          color: form.color,
        });
        toast.success('日程已创建');
      }
      setDialogOpen(false);
      setTick((value) => value + 1);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const deleteEvent = async () => {
    if (!editing) return;
    try {
      await call('timetable.deleteEvent', editing.id);
      toast.success('日程已删除');
      setDialogOpen(false);
      setTick((value) => value + 1);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '删除失败');
    }
  };

  const sync = async () => {
    if (syncing) return;
    setSyncing(true);
    try {
      const state = await call<SchoolCalendarState>('schoolCalendar.sync');
      setCalendarState(state);
      setCalendarError('');
      toast.success('交我办日程已更新');
      setTick((value) => value + 1);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '同步日程失败');
    } finally {
      setSyncing(false);
    }
  };

  const login = async () => {
    try {
      const state = await toast
        .promise(call<SchoolCalendarState>('schoolCalendar.connect'), {
          loading: '请完成教学网登录，随后自动同步日程…',
          success: '登录成功，日程已同步',
          error: (cause) => (cause instanceof Error ? cause.message : '登录失败'),
        })
        .unwrap();
      setCalendarState(state);
      setTick((value) => value + 1);
    } catch {
      // toast already shown
    }
  };

  const move = (offset: number) => {
    setCursor((value) => (view === 'week' ? addWeeks(value, offset) : addMonths(value, offset)));
  };

  const goToday = () => {
    const today = new Date();
    setCursor(today);
    setSelectedDay(today);
  };

  const selectDay = (day: Date) => {
    setSelectedDay(day);
    setCursor(day);
  };

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="日程"
        description="来自交我办日历的课程、考试、会议与个人日程；每小时自动更新，默认显示本周。"
        eyebrow="Calendar"
        actions={
          <>
            {calendarState?.connected ? (
              <Button variant="secondary" onClick={() => void sync()} disabled={syncing}>
                <RefreshCw className={cn('size-4', syncing && 'animate-spin')} /> 同步日程
              </Button>
            ) : (
              <Button variant="primary" onClick={() => void login()}>
                <Wifi className="size-4" /> 登录教学网
              </Button>
            )}
            <Button variant="primary" onClick={() => openCreate(selectedDay)}>
              <Plus className="size-4" /> 新建日程
            </Button>
          </>
        }
      />

      {calendarError && !calendarState?.connected ? (
        <div className="mx-6 mb-3 flex items-center justify-between gap-3 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-[0.68rem] leading-relaxed text-warning">
          <span>{calendarError}</span>
          <Button size="sm" variant="secondary" onClick={() => void login()}>
            登录
          </Button>
        </div>
      ) : null}

      <div className="flex min-h-0 flex-1 gap-4 px-6 pb-6">
        <Card className="flex min-w-0 flex-1 flex-col overflow-hidden">
          <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-2.5">
            <div className="flex items-center gap-2">
              <Button size="icon-sm" variant="ghost" onClick={() => move(-1)} aria-label="上一段">
                <ChevronLeft className="size-4" />
              </Button>
              <div className="min-w-[10.5rem] text-center text-sm font-semibold text-ink">
                {view === 'week'
                  ? `${format(weekStart, 'M月d日', { locale: zhCN })} – ${format(weekEnd, 'M月d日', { locale: zhCN })}`
                  : format(cursor, 'yyyy年M月', { locale: zhCN })}
              </div>
              <Button size="icon-sm" variant="ghost" onClick={() => move(1)} aria-label="下一段">
                <ChevronRight className="size-4" />
              </Button>
              <Button size="sm" variant="secondary" onClick={goToday}>
                今天
              </Button>
            </div>
            <div className="flex items-center gap-1 rounded-lg border border-line bg-surface-2 p-0.5">
              {(['week', 'month'] as ViewMode[]).map((mode) => (
                <button
                  key={mode}
                  type="button"
                  onClick={() => setView(mode)}
                  className={cn(
                    'rounded-md px-3 py-1 text-[0.68rem] transition-colors',
                    view === mode ? 'bg-surface text-ink shadow-sm' : 'text-ink-3 hover:text-ink',
                  )}
                >
                  {mode === 'week' ? '周' : '月'}
                </button>
              ))}
            </div>
            <div className="flex items-center gap-2 text-[0.66rem] text-ink-3">
              {loading ? <Spinner className="size-3" /> : null}
              <span>
                {calendarState?.lastSyncedAt
                  ? `更新于 ${formatDate(calendarState.lastSyncedAt, 'M月d日 HH:mm')}`
                  : '尚未同步交我办日程'}
              </span>
            </div>
          </div>

          {view === 'week' ? (
            <WeekView
              weekStart={weekStart}
              selectedDay={selectedDay}
              itemsByDay={itemsByDay}
              now={now}
              onSelectDay={selectDay}
              onOpen={openItem}
              onCreate={openCreate}
            />
          ) : (
            <MonthView
              cursor={cursor}
              days={monthDays}
              itemsByDay={itemsByDay}
              selectedDay={selectedDay}
              onSelectDay={selectDay}
              onOpen={openItem}
              onCreate={openCreate}
            />
          )}

          <div className="flex shrink-0 items-center gap-3 border-t border-line px-4 py-2 text-[0.62rem] text-ink-3">
            <span className="inline-flex items-center gap-1">
              <span className="size-2 rounded-full bg-accent" /> 交我办日程
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="size-2 rounded-full bg-warning" /> 本地日程
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="size-2 rounded-full bg-success" /> TODO
            </span>
            <span className="inline-flex items-center gap-1">
              <span className="size-2 rounded-full bg-danger" /> 考试
            </span>
            <span className="ml-auto">点击日程查看详情，双击空白日期创建本地日程</span>
          </div>
        </Card>

        <aside className="flex w-[19rem] shrink-0 flex-col gap-3 overflow-y-auto">
          <Card>
            <div className="flex items-center justify-between border-b border-line px-4 py-3">
              <div>
                <div className="text-sm font-semibold text-ink">
                  {format(selectedDay, 'M月d日 EEEE', { locale: zhCN })}
                </div>
                <div className="mt-0.5 text-[0.66rem] text-ink-3">{selectedItems.length} 项安排</div>
              </div>
              <Button size="sm" variant="ghost" onClick={() => openCreate(selectedDay)}>
                <Plus className="size-3.5" /> 添加
              </Button>
            </div>
            {selectedItems.length === 0 ? (
              <EmptyState compact icon={CalendarDays} title="这一天没有安排" description="可以新建本地日程，或等待交我办同步。" />
            ) : (
              <div className="divide-y divide-line">
                {selectedItems.map((item) => (
                  <button
                    key={item.key}
                    type="button"
                    onClick={() => openItem(item)}
                    className="flex w-full items-start gap-2.5 px-4 py-3 text-left transition-colors hover:bg-hover"
                  >
                    <div className="w-10 shrink-0 text-right text-[0.66rem] text-ink-3">
                      <div className="font-medium text-ink">{item.allDay ? '全天' : formatTime(item.startAt)}</div>
                      {item.allDay ? null : <div>{formatTime(item.endAt)}</div>}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-1.5">
                        <span className="truncate text-xs font-medium text-ink">{item.title}</span>
                        {item.source === 'school' ? <Badge variant="accent">交我办</Badge> : null}
                        {item.source === 'event' ? <Badge variant="warning">本地</Badge> : null}
                        {item.source === 'todo' ? <Badge variant="success">TODO</Badge> : null}
                      </div>
                      {item.location ? (
                        <div className="mt-1 flex items-center gap-1 text-[0.62rem] text-ink-3">
                          <MapPin className="size-3" /> {item.location}
                        </div>
                      ) : null}
                      {item.school?.calendarName ? (
                        <div className="mt-0.5 text-[0.62rem] text-ink-3">{item.school.calendarName}</div>
                      ) : null}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </Card>

          <Card>
            <div className="flex items-center justify-between border-b border-line px-4 py-3">
              <div className="flex items-center gap-2">
                <Clock className="size-4 text-accent" />
                <div className="text-sm font-semibold text-ink">交我办日程</div>
              </div>
              <span className="text-[0.62rem] text-ink-3">每小时</span>
            </div>
            <div className="space-y-2 p-4 text-[0.66rem] leading-relaxed text-ink-3">
              <div>{calendarState?.connected ? `已连接${calendarState.account ? ` · ${calendarState.account}` : ''}` : '尚未连接教学网'}</div>
              <div>课程、考试、会议、讲座与个人日程来自 my.sjtu.edu.cn。</div>
              <Button size="sm" variant="secondary" onClick={() => void sync()} disabled={syncing}>
                <RefreshCw className={cn('size-3.5', syncing && 'animate-spin')} /> 立即同步
              </Button>
            </div>
          </Card>

          <Card>
            <div className="flex items-center justify-between border-b border-line px-4 py-3">
              <div className="flex items-center gap-2">
                <GraduationCap className="size-4 text-danger" />
                <div className="text-sm font-semibold text-ink">考试安排</div>
              </div>
              <span className="text-[0.62rem] text-ink-3">
                {examState?.count ? `${examState.count} 项` : '教学网'}
              </span>
            </div>
            <div className="p-2">
              {upcomingExams.length === 0 ? (
                <div className="px-2 py-3 text-[0.66rem] leading-relaxed text-ink-3">
                  {examState?.error || '近期没有考试安排。同步教学网后自动显示，并在考前 36 小时内提醒。'}
                </div>
              ) : (
                upcomingExams.map((exam) => (
                  <div key={exam.id} className="rounded-lg px-2 py-2 hover:bg-hover">
                    <div className="flex items-baseline gap-2">
                      <span className="min-w-0 flex-1 truncate text-xs font-medium text-ink">{exam.courseName}</span>
                      <span className="shrink-0 text-[0.62rem] tabular-nums text-danger">
                        {exam.examDate || exam.timeText}
                      </span>
                    </div>
                    <div className="mt-0.5 truncate text-[0.62rem] text-ink-3">
                      {[exam.timeText, exam.campus, exam.classroom, exam.form].filter(Boolean).join(' · ') || '待补充'}
                    </div>
                  </div>
                ))
              )}
            </div>
          </Card>
        </aside>
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent
          title={editing?.todoId ? '待办日程' : editing ? '编辑本地日程' : '新建本地日程'}
          description={editing?.todoId ? '该日程由 TODO 生成，请在 TODO 中修改。' : '本地日程只保存在本机，不会写入交我办。'}
          footer={
            editing?.todoId ? (
              <>
                <Button variant="ghost" onClick={() => setDialogOpen(false)}>关闭</Button>
                <Button
                  variant="primary"
                  onClick={() => {
                    setDialogOpen(false);
                    navigate('todo', { todoId: editing.todoId ?? undefined });
                  }}
                >
                  打开 TODO
                </Button>
              </>
            ) : (
              <>
                {editing ? (
                  <Button variant="danger" onClick={() => void deleteEvent()}>
                    <Trash2 className="size-4" /> 删除
                  </Button>
                ) : null}
                <Button variant="ghost" onClick={() => setDialogOpen(false)}>取消</Button>
                <Button variant="primary" onClick={() => void saveEvent()} disabled={saving}>
                  {saving ? <Loader2 className="size-4 animate-spin" /> : null}
                  保存
                </Button>
              </>
            )
          }
        >
          <div className="space-y-3">
            <div>
              <label className="text-xs font-medium text-ink-2">标题</label>
              <Input
                className="mt-1.5"
                value={form.title}
                onChange={(event) => setForm({ ...form, title: event.target.value })}
                placeholder="例如：机器学习作业 3"
                disabled={Boolean(editing?.todoId)}
                autoFocus
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs font-medium text-ink-2">开始</label>
                <Input
                  type="datetime-local"
                  className="mt-1.5"
                  value={form.start}
                  onChange={(event) => setForm({ ...form, start: event.target.value })}
                  disabled={Boolean(editing?.todoId)}
                />
              </div>
              <div>
                <label className="text-xs font-medium text-ink-2">结束</label>
                <Input
                  type="datetime-local"
                  className="mt-1.5"
                  value={form.end}
                  onChange={(event) => setForm({ ...form, end: event.target.value })}
                  disabled={Boolean(editing?.todoId)}
                />
              </div>
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">地点</label>
              <Input
                className="mt-1.5"
                value={form.location}
                onChange={(event) => setForm({ ...form, location: event.target.value })}
                placeholder="可选"
                disabled={Boolean(editing?.todoId)}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">颜色</label>
              <div className="mt-1.5 flex flex-wrap gap-2">
                {colorKeys.map((color) => (
                  <button
                    key={color}
                    type="button"
                    onClick={() => setForm({ ...form, color })}
                    className={cn(
                      'size-6 rounded-full border-2 transition-transform',
                      `cal-${color}`,
                      form.color === color ? 'scale-110 border-ink/50' : 'border-transparent',
                    )}
                    aria-label={color}
                  />
                ))}
              </div>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(detailSchool)} onOpenChange={(open) => !open && setDetailSchool(null)}>
        <DialogContent
          title={detailSchool?.title ?? '日程详情'}
          description={detailSchool ? `${detailSchool.calendarName} · 来自交我办` : undefined}
          footer={
            <>
              <Button variant="ghost" onClick={() => setDetailSchool(null)}>关闭</Button>
              <Button
                variant="primary"
                onClick={() => {
                  setDetailSchool(null);
                  void call('app.openExternal', 'https://calendar.sjtu.edu.cn/');
                }}
              >
                在交我办打开
              </Button>
            </>
          }
        >
          {detailSchool ? (
            <div className="space-y-3 text-xs text-ink-2">
              <div className="flex items-center gap-2">
                <Clock className="size-4 text-ink-3" />
                {formatDate(detailSchool.startAt, 'yyyy年M月d日 HH:mm')} – {formatTime(detailSchool.endAt)}
              </div>
              {detailSchool.location ? (
                <div className="flex items-center gap-2">
                  <MapPin className="size-4 text-ink-3" /> {detailSchool.location}
                </div>
              ) : null}
              {detailSchool.status ? <div className="text-ink-3">状态：{detailSchool.status}</div> : null}
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function scheduleKindLabel(item: ScheduleItem) {
  if (item.source === 'exam') return '考试';
  if (item.source === 'todo') return 'TODO';
  if (item.source === 'event') return '个人日程';
  return item.school?.calendarName?.trim() || '校园日程';
}

interface PositionedEvent {
  item: ScheduleItem;
  lane: number;
  lanes: number;
}

// Overlapping events share the column instead of covering each other.
function packDayLanes(items: ScheduleItem[]): PositionedEvent[] {
  const timed = items
    .filter((item) => !item.allDay)
    .sort((a, b) => a.startAt.localeCompare(b.startAt) || b.endAt.localeCompare(a.endAt));
  const placed: PositionedEvent[] = [];
  let cluster: { item: ScheduleItem; lane: number }[] = [];
  let clusterEnd = 0;

  const flush = () => {
    if (!cluster.length) return;
    const lanes = cluster.reduce((max, entry) => Math.max(max, entry.lane), 0) + 1;
    for (const entry of cluster) placed.push({ item: entry.item, lane: entry.lane, lanes });
    cluster = [];
    clusterEnd = 0;
  };

  for (const item of timed) {
    const start = new Date(item.startAt).getTime();
    if (cluster.length && start >= clusterEnd) flush();
    const taken = new Set(
      cluster
        .filter((entry) => new Date(entry.item.endAt).getTime() > start)
        .map((entry) => entry.lane),
    );
    let lane = 0;
    while (taken.has(lane)) lane += 1;
    cluster.push({ item, lane });
    clusterEnd = Math.max(clusterEnd, new Date(item.endAt).getTime());
  }
  flush();
  return placed;
}

function WeekView({
  weekStart,
  selectedDay,
  itemsByDay,
  now,
  onSelectDay,
  onOpen,
  onCreate,
}: {
  weekStart: Date;
  selectedDay: Date;
  itemsByDay: Map<string, ScheduleItem[]>;
  now: Date;
  onSelectDay: (day: Date) => void;
  onOpen: (item: ScheduleItem) => void;
  onCreate: (day: Date) => void;
}) {
  const days = Array.from({ length: 7 }, (_, index) => addDays(weekStart, index));
  const hours = Array.from({ length: END_HOUR - START_HOUR }, (_, index) => START_HOUR + index);
  const hasAllDay = days.some((day) =>
    (itemsByDay.get(isoDate(day)) ?? []).some((item) => item.allDay),
  );
  const nowTop =
    ((now.getHours() + now.getMinutes() / 60 - START_HOUR) * HOUR_HEIGHT);

  return (
    <div className="scroll-thin min-h-0 flex-1 overflow-auto">
      <div className="min-w-[52rem]">
        <div className="sticky top-0 z-20 grid grid-cols-[3.75rem_repeat(7,minmax(0,1fr))] border-b border-line bg-surface">
          <div />
          {days.map((day, index) => {
            const count = (itemsByDay.get(isoDate(day)) ?? []).length;
            const isToday = sameDay(day, now);
            return (
              <button
                key={isoDate(day)}
                type="button"
                onClick={() => onSelectDay(day)}
                className={cn(
                  'border-l border-line px-2 py-2 text-center transition-colors hover:bg-hover',
                  sameDay(day, selectedDay) && 'bg-selected/50',
                )}
              >
                <div className="flex items-center justify-center gap-1.5">
                  <span className="text-[0.6rem] text-ink-3">{weekDayLabels[index]}</span>
                  <span
                    className={cn(
                      'grid size-5 place-items-center rounded-full text-[0.72rem] font-semibold',
                      isToday ? 'bg-accent text-white' : 'text-ink',
                    )}
                  >
                    {format(day, 'd')}
                  </span>
                </div>
                <div className="mt-0.5 text-[0.56rem] text-ink-3">
                  {isToday ? '今天' : count > 0 ? `${count} 项` : '无安排'}
                </div>
              </button>
            );
          })}
        </div>

        {hasAllDay ? (
          <div className="grid grid-cols-[3.75rem_repeat(7,minmax(0,1fr))] border-b border-line bg-surface-2/40">
            <div className="px-2 py-1.5 text-right text-[0.58rem] text-ink-3">全天</div>
            {days.map((day) => (
              <div key={`allday-${isoDate(day)}`} className="min-h-8 border-l border-line p-1">
                {(itemsByDay.get(isoDate(day)) ?? [])
                  .filter((item) => item.allDay)
                  .map((item) => (
                    <button
                      key={item.key}
                      type="button"
                      onClick={() => onOpen(item)}
                      className="mb-0.5 block w-full rounded border-l-2 bg-surface px-1.5 py-0.5 text-left text-[0.62rem] leading-snug text-ink hover:opacity-85"
                      style={schoolStyle(item.school)}
                    >
                      <span className="line-clamp-2">{item.title}</span>
                    </button>
                  ))}
              </div>
            ))}
          </div>
        ) : null}

        <div className="grid grid-cols-[3.75rem_repeat(7,minmax(0,1fr))]">
          <div className="relative border-r border-line bg-surface-2/20">
            {hours.map((hour) => (
              <div key={hour} className="relative border-b border-line/60 pr-1.5 text-right" style={{ height: HOUR_HEIGHT }}>
                <span className="absolute -top-1.5 right-1.5 bg-surface px-0.5 text-[0.58rem] tabular-nums text-ink-3">
                  {String(hour).padStart(2, '0')}:00
                </span>
              </div>
            ))}
            {nowTop >= 0 && nowTop <= hours.length * HOUR_HEIGHT ? (
              <span
                className="absolute right-1 z-30 rounded bg-danger px-1 text-[0.55rem] font-medium tabular-nums text-white"
                style={{ top: nowTop - 7 }}
              >
                {format(now, 'HH:mm')}
              </span>
            ) : null}
          </div>
          {days.map((day) => (
            <div
              key={`column-${isoDate(day)}`}
              onDoubleClick={() => onCreate(day)}
              className={cn(
                'relative border-l border-line',
                sameDay(day, selectedDay) && 'bg-selected/20',
                sameDay(day, now) && 'bg-accent-soft/25',
              )}
              style={{ height: hours.length * HOUR_HEIGHT }}
            >
              {hours.map((hour) => (
                <div key={hour} className="border-b border-line/60" style={{ height: HOUR_HEIGHT }} />
              ))}
              {packDayLanes(itemsByDay.get(isoDate(day)) ?? []).map((entry) => (
                <WeekEventBlock
                  key={entry.item.key}
                  item={entry.item}
                  day={day}
                  lane={entry.lane}
                  lanes={entry.lanes}
                  onClick={() => onOpen(entry.item)}
                />
              ))}
              {sameDay(day, now) && nowTop >= 0 && nowTop <= hours.length * HOUR_HEIGHT ? (
                <div className="pointer-events-none absolute left-0 right-0 z-[5]" style={{ top: nowTop }}>
                  <div className="h-0.5 bg-danger/80" />
                </div>
              ) : null}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function WeekEventBlock({
  item,
  day,
  lane,
  lanes,
  onClick,
}: {
  item: ScheduleItem;
  day: Date;
  lane: number;
  lanes: number;
  onClick: () => void;
}) {
  const start = new Date(item.startAt);
  const end = new Date(item.endAt);
  const dayStart = new Date(day);
  dayStart.setHours(START_HOUR, 0, 0, 0);
  const startMinutes = Math.max(0, (start.getTime() - dayStart.getTime()) / 60000);
  const endMinutes = Math.min((END_HOUR - START_HOUR) * 60, (end.getTime() - dayStart.getTime()) / 60000);
  const top = (startMinutes / 60) * HOUR_HEIGHT;
  const height = Math.max(26, ((endMinutes - startMinutes) / 60) * HOUR_HEIGHT);
  const width = 100 / lanes;
  const kind = scheduleKindLabel(item);
  const roomy = height >= 54;
  const roomyEnough = height >= 76;

  return (
    <button
      type="button"
      onClick={(event) => {
        event.stopPropagation();
        onClick();
      }}
      title={`${formatTime(item.startAt)}–${formatTime(item.endAt)} ${item.title}${item.location ? ` · ${item.location}` : ''} · ${kind}`}
      className={cn(
        'absolute z-[1] flex flex-col overflow-hidden rounded-r border-l-2 px-1.5 py-1 text-left shadow-sm transition-opacity hover:opacity-90',
        item.source === 'event' && `cal-${item.color ?? 'blue'}`,
        item.source === 'todo' && 'cal-success',
        item.source === 'exam' && 'cal-danger',
        item.source === 'school' && 'bg-surface',
      )}
      style={{
        top,
        height,
        left: `calc(${lane * width}% + 2px)`,
        width: `calc(${width}% - 4px)`,
        ...schoolStyle(item.school),
      }}
    >
      <div className={cn('font-medium leading-snug text-ink', roomy ? 'line-clamp-2 text-[0.64rem]' : 'truncate text-[0.62rem]')}>
        {item.title}
      </div>
      {roomy ? (
        <div className="mt-0.5 shrink-0 text-[0.56rem] tabular-nums text-ink-3">
          {formatTime(item.startAt)}–{formatTime(item.endAt)}
          <span className="ml-1 text-ink-3/80">· {kind}</span>
        </div>
      ) : null}
      {roomyEnough && item.location ? (
        <div className="mt-0.5 line-clamp-2 text-[0.56rem] leading-snug text-ink-3">{item.location}</div>
      ) : null}
    </button>
  );
}

function MonthView({
  cursor,
  days,
  itemsByDay,
  selectedDay,
  onSelectDay,
  onOpen,
  onCreate,
}: {
  cursor: Date;
  days: Date[];
  itemsByDay: Map<string, ScheduleItem[]>;
  selectedDay: Date;
  onSelectDay: (day: Date) => void;
  onOpen: (item: ScheduleItem) => void;
  onCreate: (day: Date) => void;
}) {
  return (
    <div className="scroll-thin min-h-0 flex-1 overflow-auto">
      <div className="grid min-w-[40rem] grid-cols-7">
        {weekDayLabels.map((name) => (
          <div key={name} className="border-b border-line bg-surface-2 px-2 py-1.5 text-center text-[0.66rem] font-medium text-ink-3">
            {name}
          </div>
        ))}
        {days.map((day) => {
          const items = itemsByDay.get(isoDate(day)) ?? [];
          const inMonth = isSameMonth(day, cursor);
          return (
            <div
              key={isoDate(day)}
              onDoubleClick={() => onCreate(day)}
              onClick={() => onSelectDay(day)}
              className={cn(
                'min-h-[9.5rem] cursor-pointer border-b border-r border-line p-1.5 transition-colors hover:bg-hover/40',
                !inMonth && 'bg-surface-2/50 text-ink-3',
                sameDay(day, selectedDay) && 'bg-selected/40',
              )}
            >
              <div className="mb-1 flex items-center justify-between">
                <span className={cn('grid size-5 place-items-center rounded-full text-[0.66rem]', sameDay(day, new Date()) ? 'bg-accent font-semibold text-white' : 'text-ink-2')}>
                  {day.getDate()}
                </span>
                {sameDay(day, selectedDay) ? <span className="text-[0.58rem] text-accent">已选</span> : null}
              </div>
              <div className="space-y-1">
                {items.slice(0, 3).map((item) => (
                  <button
                    key={item.key}
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      onOpen(item);
                    }}
                    className={cn(
                      'block w-full rounded border-l-2 px-1.5 py-1 text-left text-[0.62rem] text-ink hover:opacity-85',
                      item.source === 'event' && `cal-${item.color ?? 'blue'}`,
                      item.source === 'todo' && 'cal-success',
                      item.source === 'school' && 'bg-surface-2',
                    )}
                    style={schoolStyle(item.school)}
                    title={`${formatTime(item.startAt)}–${formatTime(item.endAt)} ${item.title}${item.location ? ` · ${item.location}` : ''} · ${scheduleKindLabel(item)}`}
                  >
                    <span className="block font-medium tabular-nums text-ink-2">
                      {item.allDay ? '全天' : `${formatTime(item.startAt)}–${formatTime(item.endAt)}`}
                    </span>
                    <span className="line-clamp-2 leading-snug">{item.title}</span>
                  </button>
                ))}
                {items.length > 3 ? (
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      onSelectDay(day);
                    }}
                    className="w-full rounded px-1.5 py-0.5 text-left text-[0.6rem] text-ink-3 hover:bg-hover"
                  >
                    +{items.length - 3} 更多，点击查看当天全部
                  </button>
                ) : null}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
