import {
  differenceInCalendarDays,
  format,
  formatDistanceToNowStrict,
  isToday,
  isTomorrow,
  isYesterday,
  parseISO,
} from 'date-fns';
import { zhCN } from 'date-fns/locale';

export const classPeriods: ReadonlyArray<readonly [number, number]> = [
  [8 * 60, 8 * 60 + 45],
  [8 * 60 + 55, 9 * 60 + 40],
  [10 * 60, 10 * 60 + 45],
  [10 * 60 + 55, 11 * 60 + 40],
  [12 * 60, 12 * 60 + 45],
  [12 * 60 + 55, 13 * 60 + 40],
  [14 * 60, 14 * 60 + 45],
  [14 * 60 + 55, 15 * 60 + 40],
  [16 * 60, 16 * 60 + 45],
  [16 * 60 + 55, 17 * 60 + 40],
  [18 * 60, 18 * 60 + 45],
  [18 * 60 + 55, 19 * 60 + 40],
  [19 * 60 + 50, 20 * 60 + 20],
];

export const weekdayNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];

export function parseDate(value: string | null | undefined): Date | null {
  if (!value) return null;
  const date = parseISO(value);
  return Number.isFinite(date.getTime()) ? date : null;
}

export function formatDate(value: string | Date | null | undefined, pattern = 'yyyy-MM-dd') {
  if (!value) return '—';
  const date = typeof value === 'string' ? parseDate(value) : value;
  if (!date) return '—';
  return format(date, pattern, { locale: zhCN });
}

export function formatDateTime(value: string | Date | null | undefined) {
  return formatDate(value, 'yyyy-MM-dd HH:mm');
}

export function formatTime(value: string | Date | null | undefined) {
  return formatDate(value, 'HH:mm');
}

export function relativeTime(value: string | Date | null | undefined) {
  const date = typeof value === 'string' ? parseDate(value) : value;
  if (!date) return '';
  return formatDistanceToNowStrict(date, { addSuffix: true, locale: zhCN });
}

export function dayLabel(value: string | Date | null | undefined) {
  const date = typeof value === 'string' ? parseDate(value) : value;
  if (!date) return '';
  if (isToday(date)) return '今天';
  if (isTomorrow(date)) return '明天';
  if (isYesterday(date)) return '昨天';
  const days = differenceInCalendarDays(date, new Date());
  if (days > 1 && days < 7) return `${days} 天后`;
  return formatDate(date, 'M月d日');
}

export function formatBytes(bytes: number | null | undefined) {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes)) return '—';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value >= 10 ? value.toFixed(0) : value.toFixed(1)} ${units[unit]}`;
}

export function formatDuration(seconds: number | null | undefined) {
  if (!seconds || !Number.isFinite(seconds)) return '00:00';
  const total = Math.max(0, Math.floor(seconds));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const rest = total % 60;
  return hours > 0
    ? `${hours}:${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
}

export function periodRange(startPeriod: number, endPeriod: number) {
  const start = classPeriods[Math.max(0, startPeriod - 1)];
  const end = classPeriods[Math.min(classPeriods.length - 1, endPeriod - 1)];
  if (!start || !end) return null;
  return { startMinutes: start[0], endMinutes: end[1] };
}

export function semesterWeek(semesterStart: string | null, date = new Date()) {
  const start = parseDate(semesterStart);
  if (!start) return null;
  start.setHours(0, 0, 0, 0);
  const diff = differenceInCalendarDays(date, start);
  if (diff < 0) return null;
  return Math.floor(diff / 7) + 1;
}

export function monthMatrix(year: number, month: number): Date[] {
  const first = new Date(year, month, 1);
  const offset = (first.getDay() + 6) % 7;
  const start = new Date(year, month, 1 - offset);
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(start);
    date.setDate(start.getDate() + index);
    return date;
  });
}

export function sameDay(a: Date, b: Date) {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

export function isoDate(date: Date) {
  return format(date, 'yyyy-MM-dd');
}

export function sanitizeFilename(name: string, fallback = 'file') {
  const cleaned = name.replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_').trim();
  return cleaned || fallback;
}

export function escapeHtml(value: string) {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

export function percent(value: number, total: number) {
  if (!total) return 0;
  return Math.max(0, Math.min(100, Math.round((value / total) * 100)));
}

export function todayRange() {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const end = new Date(start);
  end.setDate(end.getDate() + 1);
  return { start: start.toISOString(), end: end.toISOString() };
}
