import {
  AlertCircle,
  BookOpen,
  CalendarDays,
  CalendarRange,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  Circle,
  Clock,
  LayoutList,
  Loader2,
  Plus,
  RefreshCw,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { format } from 'date-fns';
import { zhCN } from 'date-fns/locale';
import { call, on } from '@/lib/bridge';
import type { TodoItem, TodoState } from '@/lib/models';
import {
  dayLabel,
  formatDate,
  formatTime,
  isoDate,
  monthMatrix,
  sameDay,
  weekdayNames,
} from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { Button } from '@/components/ui/Button';
import { Input, Textarea } from '@/components/ui/Input';
import { Checkbox } from '@/components/ui/Controls';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';

type Filter = 'all' | 'today' | 'week' | 'canvas' | 'completed';
type View = 'list' | 'month';

interface TodoForm {
  id?: string;
  title: string;
  notes: string;
  due: string;
  courseName: string;
  addToCalendar: boolean;
  done: boolean;
  source?: string;
}

function toLocalInput(value: string | null | undefined) {
  if (!value) return '';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '';
  const pad = (number: number) => String(number).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function toISO(value: string) {
  if (!value) return null;
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return null;
  return date.toISOString();
}

function endOfDayInput(day: Date) {
  const value = new Date(day);
  value.setHours(23, 59, 0, 0);
  return toLocalInput(value.toISOString());
}

function isWithinWeek(dueAt: string | null) {
  if (!dueAt) return false;
  const due = new Date(dueAt);
  const now = new Date();
  const weekEnd = new Date(now);
  weekEnd.setDate(weekEnd.getDate() + 7);
  return due >= startOfDay(now) && due < weekEnd;
}

function startOfDay(value: Date) {
  const date = new Date(value);
  date.setHours(0, 0, 0, 0);
  return date;
}

function TodoRow({
  item,
  onToggle,
  onOpen,
  onDelete,
}: {
  item: TodoItem;
  onToggle: () => void;
  onOpen: () => void;
  onDelete: () => void;
}) {
  const overdue = !item.done && item.dueAt && new Date(item.dueAt) < new Date();
  return (
    <div
      className={cn(
        'group flex items-start gap-3 border-b border-line px-4 py-3 transition-colors hover:bg-hover',
        item.done && 'opacity-55',
      )}
    >
      <button type="button" onClick={onToggle} className="mt-0.5 shrink-0" aria-label={item.done ? '标为未完成' : '完成'}>
        {item.done ? (
          <CheckCircle2 className="size-4.5 text-success" />
        ) : (
          <Circle className="size-4.5 text-ink-3 transition-colors group-hover:text-accent" />
        )}
      </button>
      <button type="button" onClick={onOpen} className="min-w-0 flex-1 text-left">
        <div className="flex items-center gap-2">
          <span className={cn('truncate text-xs font-medium text-ink', item.done && 'line-through')}>
            {item.title}
          </span>
          {item.source === 'canvas' ? <Badge variant="accent">Canvas</Badge> : null}
          {item.calendarEventId ? <Badge variant="warning">日历</Badge> : null}
          {overdue ? <Badge variant="danger">逾期</Badge> : null}
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-2 text-[0.64rem] text-ink-3">
          {item.courseName ? (
            <span className="inline-flex items-center gap-1">
              <BookOpen className="size-3" /> {item.courseName}
            </span>
          ) : null}
          {item.dueAt ? (
            <span className={cn('inline-flex items-center gap-1', overdue && 'text-danger')}>
              <Clock className="size-3" /> {formatDate(item.dueAt, 'M月d日 HH:mm')}
            </span>
          ) : (
            <span>无截止时间</span>
          )}
          {item.notes ? <span className="line-clamp-1 max-w-[24rem]">{item.notes}</span> : null}
        </div>
      </button>
      <button
        type="button"
        onClick={onDelete}
        className="mt-0.5 grid size-7 shrink-0 place-items-center rounded-md text-ink-3 opacity-0 transition-opacity hover:bg-danger/10 hover:text-danger group-hover:opacity-100"
        aria-label="删除待办"
      >
        <Trash2 className="size-3.5" />
      </button>
    </div>
  );
}

export function TodoPage() {
  const navigate = useApp((state) => state.navigate);
  const [state, setState] = useState<TodoState | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [filter, setFilter] = useState<Filter>('all');
  const [view, setView] = useState<View>('list');
  const [monthCursor, setMonthCursor] = useState(() => {
    const date = new Date();
    date.setDate(1);
    date.setHours(0, 0, 0, 0);
    return date;
  });
  const [selectedDay, setSelectedDay] = useState(new Date());
  const [dialogOpen, setDialogOpen] = useState(false);
  const [editing, setEditing] = useState<TodoItem | null>(null);
  const [form, setForm] = useState<TodoForm>({
    title: '',
    notes: '',
    due: '',
    courseName: '',
    addToCalendar: true,
    done: false,
  });
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    void call<TodoState>('todo.refresh')
      .catch(() => call<TodoState>('todo.state'))
      .then((value) => {
        if (alive) setState(value);
      })
      .catch((cause) => {
        if (alive) toast.error(cause instanceof Error ? cause.message : '读取 TODO 失败');
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    const off = on('todo.state', (value) => setState(value as TodoState));
    return () => {
      alive = false;
      off();
    };
  }, []);

  const items = state?.items ?? [];
  const filtered = useMemo(() => {
    const now = new Date();
    return items.filter((item) => {
      if (filter === 'completed') return item.done;
      if (filter === 'today')
        return !item.done && item.dueAt && new Date(item.dueAt).toDateString() === now.toDateString();
      if (filter === 'week') return !item.done && isWithinWeek(item.dueAt);
      if (filter === 'canvas') return item.source === 'canvas';
      return true;
    });
  }, [items, filter]);

  const groups = useMemo(() => {
    if (filter === 'completed') {
      return [{ label: '已完成', items: filtered }];
    }
    const now = new Date();
    const todayStart = startOfDay(now);
    const tomorrow = new Date(todayStart);
    tomorrow.setDate(tomorrow.getDate() + 1);
    const weekEnd = new Date(todayStart);
    weekEnd.setDate(weekEnd.getDate() + 7);
    const overdue: TodoItem[] = [];
    const today: TodoItem[] = [];
    const week: TodoItem[] = [];
    const later: TodoItem[] = [];
    const noDue: TodoItem[] = [];
    const done: TodoItem[] = [];
    for (const item of filtered) {
      if (item.done) {
        done.push(item);
        continue;
      }
      if (!item.dueAt) {
        noDue.push(item);
        continue;
      }
      const due = new Date(item.dueAt);
      if (due < now) overdue.push(item);
      else if (due < tomorrow) today.push(item);
      else if (due < weekEnd) week.push(item);
      else later.push(item);
    }
    return [
      { label: '已逾期', items: overdue },
      { label: '今天', items: today },
      { label: '未来 7 天', items: week },
      { label: '以后', items: later },
      { label: '无截止日期', items: noDue },
      { label: '已完成', items: done },
    ].filter((group) => group.items.length > 0);
  }, [filtered, filter]);

  const monthDays = useMemo(() => monthMatrix(monthCursor.getFullYear(), monthCursor.getMonth()), [monthCursor]);
  const itemsByDay = useMemo(() => {
    const map = new Map<string, TodoItem[]>();
    for (const item of items) {
      if (!item.dueAt) continue;
      const key = formatDate(item.dueAt, 'yyyy-MM-dd');
      const list = map.get(key) ?? [];
      list.push(item);
      map.set(key, list);
    }
    for (const list of map.values()) {
      list.sort((a, b) => (a.dueAt ?? '').localeCompare(b.dueAt ?? ''));
    }
    return map;
  }, [items]);

  const openCreate = (day?: Date) => {
    setEditing(null);
    setForm({
      title: '',
      notes: '',
      due: day ? endOfDayInput(day) : '',
      courseName: '',
      addToCalendar: Boolean(day),
      done: false,
    });
    setDialogOpen(true);
  };

  const openEdit = (item: TodoItem) => {
    setEditing(item);
    setForm({
      id: item.id,
      title: item.title,
      notes: item.notes,
      due: toLocalInput(item.dueAt),
      courseName: item.courseName ?? '',
      addToCalendar: Boolean(item.calendarEventId),
      done: item.done,
      source: item.source,
    });
    setDialogOpen(true);
    void call('todo.markRead', item.id).catch(() => {});
  };

  const save = async () => {
    if (!form.title.trim()) {
      toast.error('请填写待办标题');
      return;
    }
    setSaving(true);
    try {
      await call('todo.save', {
        id: form.id,
        title: form.title.trim(),
        notes: form.notes,
        dueAt: toISO(form.due),
        courseName: form.courseName.trim(),
        addToCalendar: Boolean(form.due) && form.addToCalendar,
        done: form.done,
        source: form.source,
      });
      toast.success(editing ? '待办已更新' : '待办已创建');
      setDialogOpen(false);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const remove = (item: TodoItem) => {
    if (!window.confirm(`删除「${item.title}」？`)) return;
    void call('todo.remove', item.id)
      .then(() => toast.success('已删除待办'))
      .catch((cause) => toast.error(cause instanceof Error ? cause.message : '删除失败'));
  };

  const toggle = (item: TodoItem) => {
    void call('todo.setDone', item.id, !item.done).catch((cause) =>
      toast.error(cause instanceof Error ? cause.message : '操作失败'),
    );
  };

  const refresh = async () => {
    setRefreshing(true);
    try {
      const value = await call<TodoState>('todo.refresh');
      setState(value);
      toast.success('已刷新待办与 Canvas 作业');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '刷新失败');
    } finally {
      setRefreshing(false);
    }
  };

  const changeMonth = (offset: number) => {
    const next = new Date(monthCursor);
    next.setMonth(next.getMonth() + offset);
    setMonthCursor(next);
  };

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="TODO"
        description={`今天 ${state?.today ?? 0} 项 · 逾期 ${state?.overdue ?? 0} 项 · 已完成 ${state?.completed ?? 0} 项`}
        eyebrow="Tasks"
        actions={
          <>
            <Button variant="secondary" onClick={() => void refresh()} disabled={refreshing}>
              <RefreshCw className={cn('size-4', refreshing && 'animate-spin')} /> 汇总 Canvas
            </Button>
            <Button variant="primary" onClick={() => openCreate()}>
              <Plus className="size-4" /> 新建待办
            </Button>
          </>
        }
      />
      <div className="flex min-h-0 flex-1 flex-col px-6 pb-6">
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <div className="flex rounded-lg border border-line bg-surface p-1">
            {(
              [
                ['all', '全部'],
                ['today', '今天'],
                ['week', '本周'],
                ['canvas', 'Canvas'],
                ['completed', '已完成'],
              ] as [Filter, string][]
            ).map(([value, label]) => (
              <button
                key={value}
                type="button"
                onClick={() => setFilter(value)}
                className={cn(
                  'rounded-md px-3 py-1 text-xs transition-colors',
                  filter === value ? 'bg-selected text-accent' : 'text-ink-3 hover:text-ink',
                )}
              >
                {label}
              </button>
            ))}
          </div>
          <div className="ml-auto flex rounded-lg border border-line bg-surface p-1">
            <button
              type="button"
              onClick={() => setView('list')}
              className={cn(
                'flex items-center gap-1 rounded-md px-2.5 py-1 text-xs transition-colors',
                view === 'list' ? 'bg-selected text-accent' : 'text-ink-3 hover:text-ink',
              )}
            >
              <LayoutList className="size-3.5" /> 列表
            </button>
            <button
              type="button"
              onClick={() => setView('month')}
              className={cn(
                'flex items-center gap-1 rounded-md px-2.5 py-1 text-xs transition-colors',
                view === 'month' ? 'bg-selected text-accent' : 'text-ink-3 hover:text-ink',
              )}
            >
              <CalendarRange className="size-3.5" /> 月历
            </button>
          </div>
        </div>

        {loading ? (
          <div className="flex flex-1 items-center justify-center gap-2 text-xs text-ink-3">
            <Spinner /> 正在汇总待办与 Canvas 作业…
          </div>
        ) : view === 'list' ? (
          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto rounded-card border border-line bg-surface">
            {groups.length === 0 ? (
              <EmptyState
                icon={Sparkles}
                title={filter === 'completed' ? '还没有完成的待办' : '没有待办'}
                description="新建本地待办，或刷新 Canvas 汇总课程作业。"
                action={
                  <Button size="sm" onClick={() => openCreate()}>
                    <Plus className="size-3.5" /> 新建待办
                  </Button>
                }
              />
            ) : (
              groups.map((group) => (
                <section key={group.label}>
                  <div className="sticky top-0 z-10 flex items-center justify-between border-b border-line bg-surface-2 px-4 py-1.5 text-[0.64rem] font-semibold uppercase tracking-[0.12em] text-ink-3">
                    <span>{group.label}</span>
                    <span>{group.items.length}</span>
                  </div>
                  {group.items.map((item) => (
                    <TodoRow
                      key={item.id}
                      item={item}
                      onToggle={() => toggle(item)}
                      onOpen={() => openEdit(item)}
                      onDelete={() => remove(item)}
                    />
                  ))}
                </section>
              ))
            )}
          </div>
        ) : (
          <Card className="flex min-h-0 flex-1 flex-col overflow-hidden">
            <div className="flex shrink-0 items-center justify-between border-b border-line px-4 py-2.5">
              <div className="flex items-center gap-2">
                <Button size="icon-sm" variant="ghost" onClick={() => changeMonth(-1)} aria-label="上个月">
                  <ChevronLeft className="size-4" />
                </Button>
                <div className="min-w-[8rem] text-center text-sm font-semibold text-ink">
                  {format(monthCursor, 'yyyy年M月', { locale: zhCN })}
                </div>
                <Button size="icon-sm" variant="ghost" onClick={() => changeMonth(1)} aria-label="下个月">
                  <ChevronRight className="size-4" />
                </Button>
              </div>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => {
                  const today = new Date();
                  setSelectedDay(today);
                  const first = new Date(today);
                  first.setDate(1);
                  first.setHours(0, 0, 0, 0);
                  setMonthCursor(first);
                }}
              >
                今天
              </Button>
            </div>
            <div className="grid shrink-0 grid-cols-7 border-b border-line bg-surface-2">
              {weekdayNames.map((name) => (
                <div key={name} className="py-1.5 text-center text-[0.64rem] text-ink-3">
                  {name}
                </div>
              ))}
            </div>
            <div className="grid min-h-0 flex-1 grid-cols-7 auto-rows-fr">
              {monthDays.map((day) => {
                const key = isoDate(day);
                const dayItems = itemsByDay.get(key) ?? [];
                const inMonth = day.getMonth() === monthCursor.getMonth();
                const today = sameDay(day, new Date());
                return (
                  <div
                    key={key}
                    onClick={() => {
                      setSelectedDay(day);
                      openCreate(day);
                    }}
                    className={cn(
                      'min-h-[5.5rem] border-b border-r border-line p-1.5 transition-colors hover:bg-hover',
                      !inMonth && 'bg-surface-2/50 text-ink-3',
                      sameDay(day, selectedDay) && 'bg-selected/30',
                    )}
                  >
                    <div className="flex items-center justify-between">
                      <span
                        className={cn(
                          'grid size-5 place-items-center rounded-full text-[0.64rem]',
                          today ? 'bg-accent font-semibold text-white' : 'text-ink-2',
                        )}
                      >
                        {day.getDate()}
                      </span>
                      {dayItems.length > 0 ? (
                        <span className="text-[0.58rem] text-ink-3">{dayItems.length}</span>
                      ) : null}
                    </div>
                    <div className="mt-1 space-y-0.5">
                      {dayItems.slice(0, 2).map((item) => (
                        <button
                          key={item.id}
                          type="button"
                          onClick={(event) => {
                            event.stopPropagation();
                            openEdit(item);
                          }}
                          className={cn(
                            'w-full truncate rounded px-1 py-0.5 text-left text-[0.6rem]',
                            item.done
                              ? 'bg-surface-3 text-ink-3 line-through'
                              : item.source === 'canvas'
                                ? 'bg-accent-soft text-accent'
                                : 'bg-warning/10 text-warning',
                          )}
                          title={item.title}
                        >
                          {item.title}
                        </button>
                      ))}
                      {dayItems.length > 2 ? (
                        <div className="px-1 text-[0.56rem] text-ink-3">+{dayItems.length - 2}</div>
                      ) : null}
                    </div>
                  </div>
                );
              })}
            </div>
          </Card>
        )}
      </div>

      <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
        <DialogContent
          title={editing ? '编辑待办' : '新建待办'}
          description={
            editing?.source === 'canvas'
              ? '这条待办来自 Canvas；标题与截止时间会随课程同步更新，完成状态以你的操作为准。'
              : '待办保存在本机，可同时写入月历。'
          }
          footer={
            <>
              {editing ? (
                <Button variant="danger" onClick={() => { setDialogOpen(false); remove(editing); }}>
                  <Trash2 className="size-4" /> 删除
                </Button>
              ) : null}
              <Button variant="ghost" onClick={() => setDialogOpen(false)}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void save()} disabled={saving}>
                {saving ? <Loader2 className="size-4 animate-spin" /> : null}
                保存
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            <div>
              <label className="text-xs font-medium text-ink-2">标题</label>
              <Input
                className="mt-1.5"
                value={form.title}
                onChange={(event) => setForm({ ...form, title: event.target.value })}
                placeholder="例如：完成机器学习作业 3"
                autoFocus
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <label className="text-xs font-medium text-ink-2">截止时间</label>
                <Input
                  type="datetime-local"
                  className="mt-1.5"
                  value={form.due}
                  onChange={(event) =>
                    setForm({
                      ...form,
                      due: event.target.value,
                      addToCalendar: event.target.value ? form.addToCalendar : false,
                    })
                  }
                />
              </div>
              <div>
                <label className="text-xs font-medium text-ink-2">课程 / 分类</label>
                <Input
                  className="mt-1.5"
                  value={form.courseName}
                  onChange={(event) => setForm({ ...form, courseName: event.target.value })}
                  placeholder="可选"
                />
              </div>
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">备注</label>
              <Textarea
                className="mt-1.5 h-28"
                value={form.notes}
                onChange={(event) => setForm({ ...form, notes: event.target.value })}
                placeholder="补充说明、链接或步骤…"
              />
            </div>
            <div className="flex flex-wrap items-center gap-5">
              <Checkbox
                checked={form.addToCalendar}
                onCheckedChange={(value) => setForm({ ...form, addToCalendar: value })}
                label="写入月历"
                disabled={!form.due}
              />
              <Checkbox
                checked={form.done}
                onCheckedChange={(value) => setForm({ ...form, done: value })}
                label="已完成"
              />
            </div>
            <div className="flex items-center gap-2 rounded-lg border border-line bg-surface-2 px-3 py-2 text-[0.66rem] text-ink-3">
              <CalendarDays className="size-3.5" />
              写入月历后，会在截止时间前 30 分钟生成一条日程；取消勾选会删除该日程。
            </div>
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
