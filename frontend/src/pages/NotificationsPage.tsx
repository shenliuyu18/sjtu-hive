import {
  ArrowRight,
  Bell,
  CheckCheck,
  ChevronRight,
  ClipboardList,
  ExternalLink,
  GraduationCap,
  Inbox,
  Mail,
  MessageSquare,
  RefreshCw,
  Settings2,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { call, on } from '@/lib/bridge';
import type { NotificationItem } from '@/lib/models';
import { formatDate, relativeTime } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { useNotifications } from '@/stores/notifications';
import { Button } from '@/components/ui/Button';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';

function kindIcon(kind: string) {
  if (kind === 'mail') return Mail;
  if (kind === 'ddl') return ClipboardList;
  if (kind === 'shuiyuan') return MessageSquare;
  if (kind === 'portal') return Inbox;
  if (kind === 'exam') return GraduationCap;
  return Bell;
}

const kindLabels: Record<string, string> = {
  mail: '邮件',
  ddl: '作业',
  todo: '待办',
  shuiyuan: '水源',
  portal: '交我办',
  exam: '考试',
};

function kindLabel(kind: string) {
  return kindLabels[kind] ?? '通知';
}

export function NotificationsPage() {
  const navigate = useApp((state) => state.navigate);
  const refreshUnread = useNotifications((state) => state.refresh);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [onlyUnread, setOnlyUnread] = useState(false);
  const [detail, setDetail] = useState<NotificationItem | null>(null);

  const load = () => {
    setLoading(true);
    void call<NotificationItem[]>('notifications.list')
      .then((value) => setItems(value ?? []))
      .catch(() => setItems([]))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    return on('notifications.changed', () => load());
  }, []);

  const markAll = () => {
    void call('notifications.markAllRead')
      .then(() => {
        load();
        void refreshUnread();
        toast.success('已全部标为已读');
      })
      .catch(() => {});
  };

  const openItem = (item: NotificationItem) => {
    setDetail(item);
    if (item.readAt) return;
    void call('notifications.markRead', item.id)
      .then(() => {
        setItems((current) =>
          current.map((entry) =>
            entry.id === item.id ? { ...entry, readAt: new Date().toISOString() } : entry,
          ),
        );
        setDetail((current) =>
          current && current.id === item.id ? { ...current, readAt: new Date().toISOString() } : current,
        );
        void refreshUnread();
      })
      .catch(() => {});
  };

  const detailRoute = detail ? (detail.route?.view as string | undefined) : undefined;
  const canOpenRoute = Boolean(detailRoute && detailRoute !== 'notifications');

  const unread = items.filter((item) => !item.readAt).length;
  const visible = onlyUnread ? items.filter((item) => !item.readAt) : items;

  const goToRoute = () => {
    if (!detail) return;
    const view = (detail.route?.view as ReturnType<typeof useApp.getState>['view']) ?? 'dashboard';
    setDetail(null);
    navigate(view, { ...(detail.route ?? {}) });
  };

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        eyebrow="NOTIFICATIONS"
        title="通知"
        description="新邮件、Canvas DDL、交我办消息与 TODO 提醒都在这里。"
        actions={
          <>
            <Button size="sm" variant="ghost" onClick={load} disabled={loading}>
              <RefreshCw className={cn('size-3.5', loading && 'animate-spin')} /> 刷新
            </Button>
            <Button size="sm" variant="ghost" onClick={markAll} disabled={unread === 0}>
              <CheckCheck className="size-3.5" /> 全部已读
            </Button>
            <Button
              size="sm"
              variant="secondary"
              onClick={() => navigate('settings', { settingsSection: 'general' })}
            >
              <Settings2 className="size-3.5" /> 通知设置
            </Button>
          </>
        }
      />

      <div className="flex shrink-0 items-center gap-2 px-6 pb-3">
        <div className="inline-flex rounded-lg border border-line bg-surface p-0.5">
          {(
            [
              ['all', `全部 ${items.length}`],
              ['unread', `未读 ${unread}`],
            ] as const
          ).map(([key, label]) => (
            <button
              key={key}
              type="button"
              onClick={() => setOnlyUnread(key === 'unread')}
              className={cn(
                'rounded-md px-2.5 py-1 text-[0.66rem] font-medium transition-colors',
                (key === 'unread') === onlyUnread
                  ? 'bg-accent-soft text-accent'
                  : 'text-ink-3 hover:text-ink',
              )}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-6 pb-6">
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center gap-2 py-16 text-xs text-ink-3">
            <Spinner /> 正在加载…
          </div>
        ) : visible.length === 0 ? (
          <Card className="p-2">
            <EmptyState
              icon={Bell}
              title={onlyUnread ? '没有未读通知' : '暂无通知'}
              description="新邮件、Canvas DDL 与 TODO 到期提醒会出现在这里。"
            />
          </Card>
        ) : (
          <Card className="divide-y divide-line overflow-hidden p-0">
            {visible.map((item) => {
              const Icon = kindIcon(item.kind);
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => openItem(item)}
                  className={cn(
                    'flex w-full items-start gap-3 px-4 py-3.5 text-left transition-colors hover:bg-hover',
                    !item.readAt && 'bg-accent-soft/40',
                  )}
                >
                  <div className="mt-0.5 grid size-8 shrink-0 place-items-center rounded-lg bg-surface-3 text-ink-2">
                    <Icon className="size-4" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="line-clamp-1 flex-1 text-xs font-medium leading-relaxed text-ink">
                        {item.title}
                      </span>
                      {!item.readAt ? (
                        <span className="size-1.5 shrink-0 rounded-full bg-accent" />
                      ) : null}
                    </div>
                    {item.body ? (
                      <div className="mt-1 line-clamp-2 text-[0.68rem] leading-relaxed text-ink-3">
                        {item.body}
                      </div>
                    ) : null}
                    <div className="mt-1.5 flex items-center gap-2 text-[0.62rem] text-ink-3">
                      <span className="rounded-full bg-surface-3 px-1.5 py-0.5">{kindLabel(item.kind)}</span>
                      <span>{relativeTime(item.createdAt)}</span>
                    </div>
                  </div>
                  <ChevronRight className="mt-1 size-3.5 shrink-0 text-ink-3" />
                </button>
              );
            })}
          </Card>
        )}
      </div>

      <Dialog open={Boolean(detail)} onOpenChange={(open) => (open ? null : setDetail(null))}>
        {detail ? (
          <DialogContent
            title={detail.title}
            description={`${kindLabel(detail.kind)} · ${formatDate(detail.createdAt, 'M月d日 HH:mm')} · ${relativeTime(detail.createdAt)}`}
            footer={
              <>
                {detail.kind === 'portal' ? (
                  <Button
                    size="sm"
                    variant="secondary"
                    onClick={() => void call('app.openExternal', 'https://my.sjtu.edu.cn/ui/message/')}
                  >
                    <ExternalLink className="size-3.5" /> 在交我办查看
                  </Button>
                ) : null}
                <Button size="sm" variant="ghost" onClick={() => setDetail(null)}>
                  关闭
                </Button>
                {canOpenRoute ? (
                  <Button size="sm" onClick={goToRoute}>
                    前往相关页面 <ArrowRight className="size-3.5" />
                  </Button>
                ) : null}
              </>
            }
          >
            <div className="selectable whitespace-pre-wrap break-words text-sm leading-relaxed text-ink-2">
              {detail.body || '（无正文）'}
            </div>
          </DialogContent>
        ) : null}
      </Dialog>
    </div>
  );
}
