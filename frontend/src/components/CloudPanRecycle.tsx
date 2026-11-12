import { RotateCcw, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import { formatBytes, formatDate } from '@/lib/format';
import { EmptyState, Spinner } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';
import { Dialog, DialogContent } from '@/components/ui/Dialog';

interface CloudRecycled {
  id: number;
  name: string;
  path: string;
  spaceId: string;
  size: number;
  removalTime: string;
  remainingDays: number;
  directory: boolean;
  canRestore: boolean;
  canDelete: boolean;
  restoreExpired: boolean;
}

export function RecycleBinDialog({
  open,
  onClose,
  onChanged,
}: {
  open: boolean;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [items, setItems] = useState<CloudRecycled[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setItems((await call<CloudRecycled[]>('cloudPan.recycled')) ?? []);
      setError('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取回收站失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  const restore = async (item: CloudRecycled) => {
    setBusy(true);
    try {
      await call('cloudPan.recycled.restore', [{ spaceId: item.spaceId, id: item.id }]);
      setItems((current) => current.filter((entry) => entry.id !== item.id));
      toast.success(`已恢复「${item.name}」`);
      onChanged();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '恢复失败');
    } finally {
      setBusy(false);
    }
  };

  const purge = async (item: CloudRecycled) => {
    if (!window.confirm(`永久删除「${item.name}」？删除后无法恢复。`)) return;
    setBusy(true);
    try {
      await call('cloudPan.recycled.delete', [{ spaceId: item.spaceId, id: item.id }]);
      setItems((current) => current.filter((entry) => entry.id !== item.id));
      toast.success('已永久删除');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '删除失败');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(value) => (value ? undefined : onClose())}>
      <DialogContent
        title="云盘回收站"
        description="删除的文件会在这里保留一段时间，可恢复或永久删除。"
        wide
        footer={
          <>
            <Button variant="secondary" onClick={() => void load()} disabled={loading || busy}>
              刷新
            </Button>
            <Button variant="secondary" onClick={onClose}>
              关闭
            </Button>
          </>
        }
      >
        {loading && items.length === 0 ? (
          <div className="flex items-center justify-center gap-2 py-12 text-xs text-ink-3">
            <Spinner /> 读取回收站…
          </div>
        ) : error ? (
          <EmptyState compact icon={Trash2} title="读取回收站失败" description={error} />
        ) : items.length === 0 ? (
          <EmptyState compact icon={Trash2} title="回收站是空的" description="删除的文件会先到这里。" />
        ) : (
          <table className="w-full table-fixed text-left text-[0.72rem]">
            <thead className="text-[0.62rem] text-ink-3">
              <tr>
                <th className="pb-2 font-medium">名称</th>
                <th className="w-20 pb-2 pr-2 text-right font-medium">大小</th>
                <th className="w-36 pb-2 pr-2 font-medium">删除时间</th>
                <th className="w-20 pb-2 pr-2 text-right font-medium">剩余</th>
                <th className="w-24 pb-2 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item) => (
                <tr key={`${item.spaceId}-${item.id}`} className="border-t border-line">
                  <td className="max-w-0 py-2 pr-2">
                    <div className="truncate text-ink">{item.name}</div>
                    <div className="truncate text-[0.6rem] text-ink-3">{item.path || '个人空间'}</div>
                  </td>
                  <td className="py-2 pr-2 text-right tabular-nums text-ink-3">
                    {item.directory ? '-' : formatBytes(item.size)}
                  </td>
                  <td className="py-2 pr-2 whitespace-nowrap text-ink-3">
                    {item.removalTime ? formatDate(item.removalTime, 'yyyy-MM-dd HH:mm') : '-'}
                  </td>
                  <td className="py-2 pr-2 text-right whitespace-nowrap text-ink-3">
                    {item.restoreExpired ? '已过期' : `${item.remainingDays} 天`}
                  </td>
                  <td className="py-2 text-right">
                    <span className="flex items-center justify-end gap-1">
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy || !item.canRestore || item.restoreExpired}
                        onClick={() => void restore(item)}
                        title="恢复"
                      >
                        <RotateCcw className="size-3.5" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy || !item.canDelete}
                        onClick={() => void purge(item)}
                        title="永久删除"
                      >
                        <Trash2 className="size-3.5" />
                      </Button>
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </DialogContent>
    </Dialog>
  );
}
