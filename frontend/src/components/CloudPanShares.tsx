import { Copy, Link2, Trash2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import { formatDate } from '@/lib/format';
import { Badge, EmptyState, Spinner } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Input } from '@/components/ui/Input';

export interface CloudShare {
  id: string;
  name: string;
  code: string;
  url: string;
  size: number;
  expireTime: string;
  creationTime: string;
  isPermanent: boolean;
  extractionCode: string;
}

export interface ShareTarget {
  path: string;
  name: string;
  isFolder: boolean;
}

async function copyText(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    toast.success('链接已复制');
  } catch {
    const runtime = window.runtime;
    if (runtime?.ClipboardSetText) {
      await runtime.ClipboardSetText(value);
      toast.success('链接已复制');
      return;
    }
    toast.error('复制失败，请手动选择链接');
  }
}

export function CreateShareDialog({
  target,
  onClose,
  onCreated,
}: {
  target: ShareTarget | null;
  onClose: () => void;
  onCreated: () => void;
}) {
  const [expireDays, setExpireDays] = useState(30);
  const [code, setCode] = useState('');
  const [canPreview, setCanPreview] = useState(true);
  const [canDownload, setCanDownload] = useState(true);
  const [canSave, setCanSave] = useState(false);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    if (!target) return;
    setExpireDays(30);
    setCode('');
    setCanPreview(true);
    setCanDownload(true);
    setCanSave(false);
  }, [target]);

  const create = async () => {
    if (!target) return;
    setCreating(true);
    try {
      const share = await call<CloudShare>('cloudPan.share.create', [{ path: target.path, isFolder: target.isFolder }], {
        name: target.name,
        permanent: expireDays === 0,
        expireDays,
        extractionCode: code,
        canPreview,
        canDownload,
        canSaveToNetDisc: canSave,
      });
      toast.success('分享已创建');
      await copyText(share.url);
      onCreated();
      onClose();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '创建分享失败');
    } finally {
      setCreating(false);
    }
  };

  return (
    <Dialog open={Boolean(target)} onOpenChange={(open) => !open && onClose()}>
      <DialogContent
        title="创建分享"
        description={target ? `${target.name} · 生成链接后可设置提取码与有效期` : undefined}
        footer={
          <>
            <Button variant="ghost" onClick={onClose}>
              取消
            </Button>
            <Button variant="primary" onClick={() => void create()} disabled={creating}>
              {creating ? '创建中…' : '创建并复制链接'}
            </Button>
          </>
        }
      >
        <div className="space-y-4 text-[0.72rem]">
          <div>
            <div className="mb-1.5 text-ink-2">有效期</div>
            <div className="flex gap-2">
              {[
                { days: 7, label: '7 天' },
                { days: 30, label: '30 天' },
                { days: 0, label: '永久' },
              ].map((option) => (
                <button
                  key={option.days}
                  type="button"
                  onClick={() => setExpireDays(option.days)}
                  className={
                    expireDays === option.days
                      ? 'rounded-lg border border-accent bg-accent-soft px-3 py-1.5 text-accent'
                      : 'rounded-lg border border-line px-3 py-1.5 text-ink-2 hover:border-line-strong'
                  }
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
          <label className="block">
            <span className="mb-1.5 block text-ink-2">提取码（可留空）</span>
            <Input value={code} onChange={(event) => setCode(event.target.value.slice(0, 8))} placeholder="留空表示无需提取码" />
          </label>
          <div className="space-y-2">
            {[
              { label: '允许在线预览', checked: canPreview, set: setCanPreview },
              { label: '允许下载', checked: canDownload, set: setCanDownload },
              { label: '允许转存到他人网盘', checked: canSave, set: setCanSave },
            ].map((item) => (
              <label key={item.label} className="flex items-center gap-2 text-ink-2">
                <input type="checkbox" checked={item.checked} onChange={(event) => item.set(event.target.checked)} />
                {item.label}
              </label>
            ))}
          </div>
        </div>
      </DialogContent>
    </Dialog>
  );
}

export function SharesDialog({
  open,
  onClose,
  refreshKey,
}: {
  open: boolean;
  onClose: () => void;
  refreshKey: number;
}) {
  const [shares, setShares] = useState<CloudShare[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const page = await call<{ items: CloudShare[]; total: number }>('cloudPan.share.list', 1, 50);
      setShares(page?.items ?? []);
      setError('');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '读取分享失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (open) void load();
  }, [open, refreshKey, load]);

  const remove = async (share: CloudShare) => {
    try {
      await call('cloudPan.share.delete', [share.id]);
      setShares((current) => current.filter((item) => item.id !== share.id));
      toast.success('分享已取消');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '取消分享失败');
    }
  };

  return (
    <Dialog open={open} onOpenChange={(value) => !value && onClose()}>
      <DialogContent
        title="我的分享"
        description="交大云盘中的分享链接；删除后原链接立即失效。"
        wide
        footer={
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        }
      >
        {loading && shares.length === 0 ? (
          <div className="flex items-center justify-center gap-2 py-10 text-xs text-ink-3">
            <Spinner /> 读取分享…
          </div>
        ) : error ? (
          <EmptyState compact icon={Link2} title="读取分享失败" description={error} />
        ) : shares.length === 0 ? (
          <EmptyState compact icon={Link2} title="还没有分享" description="在文件行的分享按钮里创建第一个链接。" />
        ) : (
          <div className="space-y-2">
            {shares.map((share) => (
              <div key={share.id} className="flex items-center gap-3 rounded-lg border border-line px-3 py-2">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-[0.74rem] font-medium text-ink">{share.name}</span>
                    {share.isPermanent ? <Badge variant="accent">永久</Badge> : null}
                    {share.extractionCode ? <Badge>提取码 {share.extractionCode}</Badge> : null}
                  </div>
                  <div className="mt-0.5 flex items-center gap-2 text-[0.62rem] text-ink-3">
                    <span>{share.expireTime ? `到期 ${formatDate(share.expireTime, 'yyyy-MM-dd')}` : '无到期时间'}</span>
                    {share.creationTime ? <span>· 创建于 {formatDate(share.creationTime, 'yyyy-MM-dd')}</span> : null}
                  </div>
                </div>
                <Button size="sm" variant="secondary" onClick={() => void copyText(share.url)}>
                  <Copy className="size-3.5" /> 复制
                </Button>
                <Button size="icon-sm" variant="ghost" title="取消分享" onClick={() => void remove(share)}>
                  <Trash2 className="size-3.5" />
                </Button>
              </div>
            ))}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
