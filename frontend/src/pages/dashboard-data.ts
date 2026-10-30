import { useEffect, useState } from 'react';
import { call } from '@/lib/bridge';
import type {
  CampusStatus,
  CanvasState,
  MailFolder,
  MailState,
  RecentFile,
  ShuiyuanState,
  TimetableItem,
  TimetableQuery,
  TodoItem,
} from '@/lib/models';
import { todayRange } from '@/lib/format';

export interface DashboardData {
  canvas: CanvasState | null;
  mail: MailState | null;
  folders: MailFolder[];
  lessons: TimetableItem[];
  upcoming: TimetableItem[];
  todos: TodoItem[];
  recent: RecentFile[];
  campus: CampusStatus | null;
  shuiyuan: ShuiyuanState | null;
  loading: boolean;
  refresh: () => void;
}

const empty: DashboardData = {
  canvas: null,
  mail: null,
  folders: [],
  lessons: [],
  upcoming: [],
  todos: [],
  recent: [],
  campus: null,
  shuiyuan: null,
  loading: true,
  refresh: () => {},
};

export function useDashboard(): DashboardData {
  const [data, setData] = useState<DashboardData>(empty);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let alive = true;
    const range = todayRange();
    const horizon = new Date();
    horizon.setHours(0, 0, 0, 0);
    horizon.setDate(horizon.getDate() + 8);
    setData((value) => ({ ...value, loading: true }));
    void Promise.allSettled([
      call<CanvasState>('canvas.state'),
      call<MailState>('mail.state'),
      call<MailFolder[]>('mail.folders'),
      call<TimetableQuery>('timetable.query', range.start, horizon.toISOString()),
      call<TodoItem[]>('todo.list'),
      call<RecentFile[]>('workspace.recent'),
      call<CampusStatus>('campus.status'),
      call<ShuiyuanState>('shuiyuan.state'),
    ]).then((results) => {
      if (!alive) return;
      const value = <T,>(index: number): T | null => {
        const result = results[index];
        if (result.status !== 'fulfilled') return null;
        return (result.value as T) ?? null;
      };
      const canvas = value<CanvasState>(0);
      const mail = value<MailState>(1);
      const folders = value<MailFolder[]>(2) ?? [];
      const timetable = value<TimetableQuery>(3);
      const items = timetable?.items ?? [];
      const todayStart = new Date(range.start).getTime();
      const todayEnd = new Date(range.end).getTime();
      const now = Date.now();
      const lessons = items.filter((item) => {
        const at = new Date(item.startAt).getTime();
        return at >= todayStart && at < todayEnd;
      });
      const upcoming = items
        .filter((item) => new Date(item.endAt).getTime() > now)
        .sort((a, b) => a.startAt.localeCompare(b.startAt));
      const todos = value<TodoItem[]>(4) ?? [];
      const recent = value<RecentFile[]>(5) ?? [];
      const campus = value<CampusStatus>(6);
      const shuiyuan = value<ShuiyuanState>(7);
      setData({
        canvas,
        mail,
        folders,
        lessons,
        upcoming,
        todos,
        recent,
        campus,
        shuiyuan,
        loading: false,
        refresh: () => setTick((n) => n + 1),
      });
    });
    return () => {
      alive = false;
    };
  }, [tick]);

  return { ...data, refresh: () => setTick((n) => n + 1) };
}
