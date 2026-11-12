import { Download, Pause, Play, RotateCcw, Trash2, Upload, X } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { call, on } from '@/lib/bridge';
import { cn } from '@/lib/cn';
import { formatBytes } from '@/lib/format';
import { Card } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';

export interface CloudTransfer {
  id: string;
  kind: 'upload' | 'download' | string;
  name: string;
  localPath: string;
  remotePath: string;
  size: number;
  sent: number;
  status: string;
  error: string;
}

const statusText: Record<string, string> = {
  queued: '排队中',
  running: '进行中',
  paused: '已暂停',
  failed: '失败',
  done: '已完成',
  canceled: '已取消',
};

export function useCloudTransfers() {
  const [items, setItems] = useState<CloudTransfer[]>([]);

  const refresh = useCallback(async () => {
    try {
      setItems((await call<CloudTransfer[]>('cloudPan.transfers')) ?? []);
    } catch {
      // 未连接或后端暂不可用时保留现有列表
    }
  }, []);

  useEffect(() => {
    void refresh();
    const offChanged = on('cloudPan.transfer.changed', (value) => {
      const transfer = value as CloudTransfer;
      if (!transfer?.id) return;
      setItems((current) => {
        const index = current.findIndex((item) => item.id === transfer.id);
        if (index < 0) return [transfer, ...current];
        const copy = [...current];
        copy[index] = { ...copy[index], ...transfer };
        return copy;
      });
    });
    const offRemoved = on('cloudPan.transfer.removed', (value) => {
      const id = String(value ?? '');
      setItems((current) => current.filter((item) => item.id !== id));
    });
    const offAll = on('cloudPan.transfers', (value) => {
      setItems((value as CloudTransfer[]) ?? []);
    });
    return () => {
      offChanged();
      offRemoved();
      offAll();
    };
  }, [refresh]);

  return { items, refresh };
}

function percentOf(transfer: CloudTransfer) {
  if (transfer.size <= 0) return transfer.status === 'done' ? 100 : 0;
  return Math.min(100, Math.round((transfer.sent / transfer.size) * 100));
}

export function TransferPanel({
  items,
  onClose,
  onRefresh,
}: {
  items: CloudTransfer[];
  onClose: () => void;
  onRefresh?: () => Promise<void> | void;
}) {
  const run = async (channel: string, id: string, ...rest: unknown[]) => {
    try {
      await call(channel, id, ...rest);
    } catch {
      // 单条操作失败时列表会在下一轮事件里刷新
    }
  };

  return (
    <Card className="fixed bottom-5 right-5 z-40 flex max-h-[26rem] w-[23rem] flex-col overflow-hidden shadow-[var(--shadow-pop)]">
      <div className="flex shrink-0 items-center justify-between border-b border-line px-3 py-2">
        <div className="flex items-center gap-2 text-[0.74rem] font-medium text-ink">
          传输列表
          <span className="text-[0.62rem] font-normal text-ink-3">{items.length} 项</span>
        </div>
        <div className="flex items-center gap-1">
          <Button
            size="sm"
            variant="ghost"
            disabled={!items.some((item) => item.status === 'running' || item.status === 'queued')}
            onClick={() =>
              void call('cloudPan.transfer.pauseAll')
                .then(() => onRefresh?.())
                .catch(() => undefined)
            }
          >
            全部暂停
          </Button>
          <Button
            size="sm"
            variant="ghost"
            disabled={!items.some((item) => item.status === 'paused' || item.status === 'failed')}
            onClick={() =>
              void call('cloudPan.transfer.resumeAll')
                .then(() => onRefresh?.())
                .catch(() => undefined)
            }
          >
            全部继续
          </Button>
          <Button
            size="sm"
            variant="ghost"
            disabled={items.length === 0}
            onClick={() => void call('cloudPan.transfer.clearFinished').then(() => onRefresh?.()).catch(() => undefined)}
          >
            清除已完成
          </Button>
          <Button size="icon-sm" variant="ghost" onClick={onClose} title="收起">
            <X className="size-3.5" />
          </Button>
        </div>
      </div>
      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto">
        {items.length === 0 ? (
          <div className="px-3 py-8 text-center text-[0.68rem] text-ink-3">暂无传输任务</div>
        ) : (
          items.map((transfer) => {
            const percent = percentOf(transfer);
            return (
              <div key={transfer.id} className="border-b border-line px-3 py-2 last:border-b-0">
                <div className="flex items-center gap-2">
                  {transfer.kind === 'upload' ? (
                    <Upload className="size-3.5 shrink-0 text-ink-3" />
                  ) : (
                    <Download className="size-3.5 shrink-0 text-ink-3" />
                  )}
                  <span className="min-w-0 flex-1 truncate text-[0.7rem] text-ink">{transfer.name}</span>
                  <span
                    className={cn(
                      'shrink-0 text-[0.62rem]',
                      transfer.status === 'failed'
                        ? 'text-danger'
                        : transfer.status === 'done'
                          ? 'text-success'
                          : 'text-ink-3',
                    )}
                  >
                    {statusText[transfer.status] ?? transfer.status}
                  </span>
                </div>
                {transfer.status !== 'done' && transfer.status !== 'canceled' ? (
                  <div className="mt-1.5 h-1 overflow-hidden rounded-full bg-line">
                    <div
                      className={cn('h-full rounded-full transition-[width]', transfer.status === 'failed' ? 'bg-danger' : 'bg-accent')}
                      style={{ width: `${percent}%` }}
                    />
                  </div>
                ) : null}
                <div className="mt-1 flex items-center gap-2 text-[0.6rem] text-ink-3">
                  <span className="min-w-0 flex-1 truncate">
                    {formatBytes(transfer.sent)} / {formatBytes(transfer.size)}
                    {transfer.error ? ` · ${transfer.error}` : ''}
                  </span>
                  {transfer.status === 'running' || transfer.status === 'queued' ? (
                    <button type="button" className="hover:text-ink" title="暂停" onClick={() => void run('cloudPan.transfer.pause', transfer.id)}>
                      <Pause className="size-3.5" />
                    </button>
                  ) : null}
                  {transfer.status === 'paused' ? (
                    <button type="button" className="hover:text-ink" title="继续" onClick={() => void run('cloudPan.transfer.resume', transfer.id)}>
                      <Play className="size-3.5" />
                    </button>
                  ) : null}
                  {transfer.status === 'failed' ? (
                    <button type="button" className="hover:text-ink" title="重试" onClick={() => void run('cloudPan.transfer.retry', transfer.id)}>
                      <RotateCcw className="size-3.5" />
                    </button>
                  ) : null}
                  {transfer.status === 'done' || transfer.status === 'canceled' ? (
                    <button
                      type="button"
                      className="hover:text-ink"
                      title="移除记录"
                      onClick={() => void run('cloudPan.transfer.clear', transfer.id, false)}
                    >
                      <Trash2 className="size-3.5" />
                    </button>
                  ) : (
                    <button
                      type="button"
                      className="hover:text-danger"
                      title="取消"
                      onClick={() => void run('cloudPan.transfer.cancel', transfer.id, true)}
                    >
                      <X className="size-3.5" />
                    </button>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>
    </Card>
  );
}
