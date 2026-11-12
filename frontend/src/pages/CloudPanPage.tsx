import {
  ChevronRight,
  Cloud,
  Download,
  FileText,
  Folder,
  FolderUp,
  HardDrive,
  ListChecks,
  LogIn,
  LogOut,
  RefreshCw,
  Share2,
  Trash2,
  Upload,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import type { CloudPanFile, CloudPanList, CloudPanState, DocumentContent } from '@/lib/models';
import { formatBytes, formatDate } from '@/lib/format';
import { cn } from '@/lib/cn';
import { Button } from '@/components/ui/Button';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { PreviewPane } from '@/components/content/PreviewPane';
import { TransferPanel, useCloudTransfers } from '@/components/CloudPanTransfers';
import { CreateShareDialog, SharesDialog, type ShareTarget } from '@/components/CloudPanShares';
import { RecycleBinDialog } from '@/components/CloudPanRecycle';

function parentOf(path: string) {
  const index = path.lastIndexOf('/');
  return index > 0 ? path.slice(0, index) : '';
}

export function CloudPanPage() {
  const [state, setState] = useState<CloudPanState | null>(null);
  const [path, setPath] = useState('');
  const [listing, setListing] = useState<CloudPanList | null>(null);
  const [listError, setListError] = useState('');
  const [loading, setLoading] = useState(true);
  const [loggingIn, setLoggingIn] = useState(false);
  const [doc, setDoc] = useState<DocumentContent | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [previewFile, setPreviewFile] = useState<CloudPanFile | null>(null);
  const [transfersOpen, setTransfersOpen] = useState(false);
  const [shareTarget, setShareTarget] = useState<ShareTarget | null>(null);
  const [sharesOpen, setSharesOpen] = useState(false);
  const [sharesVersion, setSharesVersion] = useState(0);
  const [recycleOpen, setRecycleOpen] = useState(false);
  const { items: transfers, refresh: refreshTransfers } = useCloudTransfers();

  const activeTransfers = transfers.filter((item) => item.status === 'running' || item.status === 'queued').length;

  const breadcrumbs = useMemo(() => {
    if (!path) return [];
    const segments = path.split('/');
    return segments.map((segment, index) => ({
      name: segment,
      path: segments.slice(0, index + 1).join('/'),
    }));
  }, [path]);

  const load = useCallback(async (target: string) => {
    setLoading(true);
    try {
      const value = await call<CloudPanList>('cloudPan.list', target, '');
      setListing(value);
      setPath(target);
      setListError('');
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : '读取云盘失败';
      setListError(message);
      toast.error(message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    void call<CloudPanState>('cloudPan.state')
      .then(async (value) => {
        if (!alive) return;
        setState(value);
        if (value?.connected) await load('');
      })
      .catch(() => undefined)
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [load]);

  const login = async () => {
    setLoggingIn(true);
    try {
      const value = await call<CloudPanState>('cloudPan.login');
      setState(value);
      toast.success('交大云盘已连接');
      await load('');
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : '登录失败';
      setState((current) => ({
        connected: false,
        account: '',
        usedBytes: 0,
        totalBytes: 0,
        lastSyncedAt: '',
        error: message,
        ...(current ?? {}),
      }));
      toast.error(message);
    } finally {
      setLoggingIn(false);
    }
  };

  const logout = async () => {
    try {
      setState(await call<CloudPanState>('cloudPan.logout'));
      setListing(null);
      setDoc(null);
      toast.success('已断开交大云盘');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '操作失败');
    }
  };

  const preview = async (file: CloudPanFile) => {
    setPreviewFile(file);
    setPreviewLoading(true);
    setPreviewError(null);
    try {
      setDoc(await call<DocumentContent>('cloudPan.preview', file.path, ''));
    } catch (cause) {
      setDoc(null);
      setPreviewError(cause instanceof Error ? cause.message : '预览失败');
    } finally {
      setPreviewLoading(false);
    }
  };

  const download = async (file: CloudPanFile) => {
    try {
      const dir = (await call<string>('cloudPan.transfer.pickDir')) ?? '';
      if (!dir) return;
      await call('cloudPan.transfer.download', [{ path: file.path, name: file.name }], dir, '');
      setTransfersOpen(true);
      toast.success('已加入下载队列');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '下载失败');
    }
  };

  const upload = async () => {
    let picked = '';
    try {
      picked = (await call<string>('app.chooseFiles', '选择要上传到云盘的文件')) ?? '';
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '无法打开文件选择器');
      return;
    }
    if (!picked) return;
    try {
      await call('cloudPan.transfer.upload', [picked], path, '');
      setTransfersOpen(true);
      toast.success('已加入上传队列');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '上传失败');
    }
  };

  const remove = async (file: CloudPanFile) => {
    if (!window.confirm(`删除「${file.name}」？文件会移入云盘回收站。`)) return;
    try {
      await call('cloudPan.delete', file.path, '', file.directory, false);
      toast.success('已移入回收站');
      await load(path);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '删除失败');
    }
  };

  const uploadFolder = async () => {
    try {
      const dir = (await call<string>('cloudPan.transfer.pickFolder')) ?? '';
      if (!dir) return;
      const result = await call<{ count: number }>('cloudPan.transfer.uploadFolder', dir, path, '');
      setTransfersOpen(true);
      toast.success(`已加入 ${result?.count ?? 0} 个文件`);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '上传文件夹失败');
    }
  };

  const usage = state && state.totalBytes > 0
    ? `${formatBytes(state.usedBytes)} / ${formatBytes(state.totalBytes)}`
    : '';

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="交大云盘"
        description="pan.sjtu.edu.cn 个人空间；使用教学网同一套 jAccount 会话登录。"
        eyebrow="Cloud Drive"
        actions={
          <>
            {state?.connected ? (
              <>
                <Button variant="secondary" onClick={() => void upload()}>
                  <Upload className="size-4" /> 上传
                </Button>
                <Button variant="secondary" onClick={() => void uploadFolder()}>
                  <FolderUp className="size-4" /> 文件夹
                </Button>
                <Button variant="secondary" onClick={() => setSharesOpen(true)}>
                  <Share2 className="size-4" /> 分享
                </Button>
                <Button variant="secondary" onClick={() => setRecycleOpen(true)}>
                  <Trash2 className="size-4" /> 回收站
                </Button>
                <Button variant={transfersOpen ? 'primary' : 'secondary'} onClick={() => setTransfersOpen((value) => !value)}>
                  <ListChecks className="size-4" /> 传输{activeTransfers > 0 ? ` ${activeTransfers}` : ''}
                </Button>
                <Button variant="secondary" onClick={() => void load(path)} disabled={loading}>
                  <RefreshCw className={cn('size-4', loading && 'animate-spin')} /> 刷新
                </Button>
                <Button variant="ghost" onClick={() => void logout()}>
                  <LogOut className="size-4" /> 断开
                </Button>
              </>
            ) : (
              <Button variant="primary" onClick={() => void login()} disabled={loggingIn}>
                {loggingIn ? <RefreshCw className="size-4 animate-spin" /> : <LogIn className="size-4" />} 连接云盘
              </Button>
            )}
          </>
        }
      />
      {state && !state.connected && state.error ? (
        <div className="mx-6 mb-3 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-[0.68rem] text-warning">
          {state.error}
        </div>
      ) : null}
      <div className="flex min-h-0 flex-1 gap-4 px-6 pb-6">
        <Card className="flex min-w-0 flex-1 flex-col overflow-hidden">
          <div className="flex shrink-0 items-center gap-1 border-b border-line px-3 py-2 text-[0.68rem]">
            <button
              type="button"
              onClick={() => void load('')}
              className="rounded px-1.5 py-0.5 text-ink-2 hover:bg-hover hover:text-ink"
            >
              <HardDrive className="mr-1 inline size-3.5" />
              个人空间
            </button>
            {breadcrumbs.map((item) => (
              <span key={item.path} className="flex items-center gap-1">
                <ChevronRight className="size-3 text-ink-3" />
                <button
                  type="button"
                  onClick={() => void load(item.path)}
                  className="max-w-[12rem] truncate rounded px-1.5 py-0.5 text-ink-2 hover:bg-hover hover:text-ink"
                >
                  {item.name}
                </button>
              </span>
            ))}
            {usage ? <span className="ml-auto shrink-0 text-ink-3">{usage}</span> : null}
          </div>
          {!state?.connected ? (
            <EmptyState
              icon={Cloud}
              title="尚未连接交大云盘"
              description="先在教学网登录（设置 → 账户与连接），再点击右上角「连接云盘」。"
            />
          ) : loading && !listing ? (
            <div className="flex items-center justify-center gap-2 py-16 text-xs text-ink-3">
              <Spinner /> 读取云盘目录…
            </div>
          ) : listError ? (
            <EmptyState compact icon={Cloud} title="读取云盘目录失败" description={listError} />
          ) : (listing?.files.length ?? 0) === 0 ? (
            <EmptyState compact icon={Folder} title="这个目录是空的" description="换个目录看看。" />
          ) : (
            <div className="scroll-thin min-h-0 flex-1 overflow-auto">
              <table className="w-full text-left text-[0.72rem]">
                <thead className="sticky top-0 bg-surface-2 text-[0.62rem] text-ink-3">
                  <tr>
                    <th className="px-3 py-2 font-medium">名称</th>
                    <th className="w-24 px-3 py-2 text-right font-medium">大小</th>
                    <th className="w-40 px-3 py-2 font-medium">修改时间</th>
                    <th className="w-28 px-3 py-2 text-right font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {path ? (
                    <tr
                      className="cursor-pointer border-b border-line hover:bg-hover"
                      onClick={() => void load(parentOf(path))}
                    >
                      <td className="px-3 py-2 text-ink-2" colSpan={4}>
                        … 返回上级
                      </td>
                    </tr>
                  ) : null}
                  {listing?.files.map((file) => (
                    <tr
                      key={file.path}
                      className={cn(
                        'border-b border-line hover:bg-hover',
                        !file.directory && doc?.title === file.name && 'bg-selected/40',
                      )}
                    >
                      <td
                        className="max-w-0 cursor-pointer px-3 py-2"
                        onClick={() => (file.directory ? void load(file.path) : void preview(file))}
                      >
                        <span className="flex items-center gap-2">
                          {file.directory ? (
                            <Folder className="size-4 shrink-0 text-accent" />
                          ) : (
                            <FileText className="size-4 shrink-0 text-ink-3" />
                          )}
                          <span className="truncate text-ink">{file.name}</span>
                        </span>
                      </td>
                      <td className="px-3 py-2 text-right tabular-nums text-ink-3">
                        {file.directory ? '-' : formatBytes(file.size)}
                      </td>
                      <td className="px-3 py-2 text-ink-3">
                        {file.modifiedAt ? formatDate(file.modifiedAt, 'yyyy-MM-dd HH:mm') : '-'}
                      </td>
                      <td className="px-3 py-2 text-right">
                        <span className="flex items-center justify-end gap-1">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => setShareTarget({ path: file.path, name: file.name, isFolder: file.directory })}
                            title="创建分享"
                          >
                            <Share2 className="size-3.5" />
                          </Button>
                          {file.directory ? null : (
                            <Button size="sm" variant="ghost" onClick={() => void download(file)} title="下载">
                              <Download className="size-3.5" />
                            </Button>
                          )}
                          <Button size="sm" variant="ghost" onClick={() => void remove(file)} title="移入回收站">
                            <Trash2 className="size-3.5" />
                          </Button>
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="flex shrink-0 items-center gap-2 border-t border-line px-3 py-2 text-[0.62rem] text-ink-3">
            {state?.connected ? <Badge variant="success">已连接</Badge> : <Badge>未连接</Badge>}
            <span>{state?.account ? `@${state.account}` : '未登录'}</span>
            {state?.lastSyncedAt ? <span>· 会话更新于 {state.lastSyncedAt.slice(0, 16)}</span> : null}
          </div>
        </Card>
        <div className="flex w-[30rem] shrink-0 flex-col overflow-hidden">
          <PreviewPane
            doc={doc}
            loading={previewLoading}
            error={previewError}
            emptyText="点击文件名即可在线预览（PDF / 图片 / 音视频走流式代理）。"
            actions={
              doc?.format === 'binary' && previewFile ? (
                <Button size="sm" variant="secondary" onClick={() => void download(previewFile)}>
                  <Download className="size-3.5" /> 下载到本机
                </Button>
              ) : undefined
            }
          />
        </div>
      </div>
      {transfersOpen ? (
        <TransferPanel items={transfers} onClose={() => setTransfersOpen(false)} onRefresh={refreshTransfers} />
      ) : null}
      <CreateShareDialog
        target={shareTarget}
        onClose={() => setShareTarget(null)}
        onCreated={() => setSharesVersion((value) => value + 1)}
      />
      <SharesDialog open={sharesOpen} onClose={() => setSharesOpen(false)} refreshKey={sharesVersion} />
      <RecycleBinDialog open={recycleOpen} onClose={() => setRecycleOpen(false)} onChanged={() => void load(path)} />
    </div>
  );
}
