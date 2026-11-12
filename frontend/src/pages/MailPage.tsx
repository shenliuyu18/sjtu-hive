import {
  Archive,
  AlertOctagon,
  ChevronDown,
  Download,
  ExternalLink,
  FileEdit,
  FileText,
  Flag,
  Forward,
  Inbox,
  Loader2,
  Mail,
  MailOpen,
  Paperclip,
  PenLine,
  RefreshCw,
  Reply,
  Search,
  Send,
  Settings2,
  Trash2,
  Undo2,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { call, on } from '@/lib/bridge';
import type {
  DocumentContent,
  MailDraft,
  MailDraftSummary,
  MailFolder,
  MailMessageDetail,
  MailMessageSummary,
  MailSendResult,
  MailState,
  MailTrashItem,
} from '@/lib/models';
import { escapeHtml, formatBytes, formatDate, relativeTime } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { Button } from '@/components/ui/Button';
import { Input, Textarea } from '@/components/ui/Input';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { PreviewPane } from '@/components/content/PreviewPane';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { HtmlFrame } from '@/components/content/HtmlFrame';

type FolderSelection =
  | { kind: 'server'; folder: MailFolder }
  | { kind: 'drafts' }
  | { kind: 'trash' };

interface ComposerState {
  draftId?: string;
  to: string;
  cc: string;
  bcc: string;
  subject: string;
  text: string;
  attachments: string[];
  showCc: boolean;
  showBcc: boolean;
}

function splitRecipients(value: string) {
  return value
    .split(/[,;，；\n]/)
    .map((item) => item.trim())
    .filter(Boolean);
}

const folderLabels: Record<string, string> = {
  inbox: '收件箱',
  sent: '已发送',
  '[smile]/sent': '已发送',
  drafts: '草稿',
  '[gmail]/sent': '已发送',
  '[gmail]/important': '重要邮件',
  '[gmail]/starred': '星标邮件',
  junk: '垃圾邮件',
  spam: '垃圾邮件',
  trash: '回收站',
  '[gmail]/trash': '回收站',
  archive: '归档',
  '[gmail]/all': '全部邮件',
};

export function folderLabel(folder: Pick<MailFolder, 'name' | 'path' | 'specialUse'>) {
  const bySpecial = folder.specialUse ? folderLabels[folder.specialUse.toLowerCase()] : undefined;
  if (bySpecial) return bySpecial;
  const keys = [folder.path, folder.name].filter(Boolean).map((value) => value.toLowerCase());
  for (const key of keys) {
    const mapped = folderLabels[key];
    if (mapped) return mapped;
  }
  for (const key of keys) {
    if (key.includes('sent')) return '已发送';
    if (key.includes('draft')) return '草稿';
    if (key.includes('junk') || key.includes('spam')) return '垃圾邮件';
    if (key.includes('trash') || key.includes('deleted')) return '回收站';
    if (key.includes('archive')) return '归档';
  }
  return folder.name;
}

function folderIcon(folder: MailFolder) {
  switch (folder.specialUse) {
    case 'sent':
      return Send;
    case 'archive':
      return Archive;
    case 'junk':
      return AlertOctagon;
    default:
      return Inbox;
  }
}

function MailListItem({
  summary,
  active,
  onClick,
  meta,
}: {
  summary: MailMessageSummary;
  active: boolean;
  onClick: () => void;
  meta?: string;
}) {
  const sender = summary.sender?.name || summary.sender?.address || '(未知发件人)';
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'flex w-full flex-col gap-1 border-b border-line px-3 py-2.5 text-left transition-colors',
        active ? 'bg-selected/70' : 'hover:bg-hover',
        !summary.seen && 'bg-accent-soft/20',
      )}
    >
      <div className="flex items-center gap-2">
        {!summary.seen ? <span className="size-1.5 shrink-0 rounded-full bg-accent" /> : null}
        <span className={cn('min-w-0 flex-1 truncate text-xs', summary.seen ? 'text-ink-2' : 'font-semibold text-ink')}>
          {sender}
        </span>
        <span className="shrink-0 text-[0.62rem] text-ink-3">
          {summary.date ? relativeTime(summary.date) : ''}
        </span>
      </div>
      <div className={cn('truncate text-xs', summary.seen ? 'text-ink-2' : 'font-medium text-ink')}>
        {summary.subject || '(无主题)'}
      </div>
      <div className="flex items-center gap-2 text-[0.64rem] text-ink-3">
        <span className="line-clamp-1 flex-1">{meta ?? summary.snippet ?? ''}</span>
        {summary.hasAttachments ? <Paperclip className="size-3 shrink-0" /> : null}
        {summary.flagged ? <Flag className="size-3 shrink-0 text-warning" /> : null}
      </div>
    </button>
  );
}

export function MailPage() {
  const navigate = useApp((state) => state.navigate);
  const params = useApp((state) => state.params);
  const [state, setState] = useState<MailState | null>(null);
  const [folders, setFolders] = useState<MailFolder[]>([]);
  const [drafts, setDrafts] = useState<MailDraftSummary[]>([]);
  const [trash, setTrash] = useState<MailTrashItem[]>([]);
  const [selection, setSelection] = useState<FolderSelection | null>(null);
  const [messages, setMessages] = useState<MailMessageSummary[]>([]);
  const [query, setQuery] = useState('');
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [detail, setDetail] = useState<MailMessageDetail | null>(null);
  const [mailView, setMailView] = useState<'html' | 'text'>('html');
  useEffect(() => {
    if (!detail) return;
    const blocked = (detail.blockedImageHosts?.length ?? 0) > 0;
    setMailView(blocked && detail.bodyText ? 'text' : 'html');
  }, [detail?.id]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [listLoading, setListLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [tick, setTick] = useState(0);
  const [composer, setComposer] = useState<ComposerState | null>(null);
  const [sending, setSending] = useState(false);
  const autoSyncRef = useRef(false);
  const savingDraftRef = useRef(false);
  const mailRouteRef = useRef<number | null>(null);
  const [previewDoc, setPreviewDoc] = useState<DocumentContent | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewPart, setPreviewPart] = useState<string | null>(null);

  const refreshBase = useCallback(async () => {
    const [stateResult, folderResult, draftResult, trashResult] = await Promise.allSettled([
      call<MailState>('mail.state'),
      call<MailFolder[]>('mail.listFolders'),
      call<MailDraftSummary[]>('mail.listDrafts'),
      call<MailTrashItem[]>('mail.trashList'),
    ]);
    const nextState = stateResult.status === 'fulfilled' ? stateResult.value : null;
    const nextFolders = folderResult.status === 'fulfilled' ? folderResult.value ?? [] : [];
    const nextDrafts = draftResult.status === 'fulfilled' ? draftResult.value ?? [] : [];
    const nextTrash = trashResult.status === 'fulfilled' ? trashResult.value ?? [] : [];
    setState(nextState);
    setFolders(nextFolders);
    setDrafts(nextDrafts);
    setTrash(nextTrash);
    setSelection((current) => {
      if (current) {
        if (current.kind === 'server') {
          const refreshed = nextFolders.find((folder) => folder.id === current.folder.id);
          if (refreshed) return { kind: 'server', folder: refreshed };
        } else return current;
      }
      const inbox = nextFolders.find((folder) => folder.specialUse === 'inbox' || folder.path.toLowerCase() === 'inbox');
      const first = inbox ?? nextFolders[0];
      return first ? { kind: 'server', folder: first } : { kind: 'drafts' };
    });
    setLoading(false);
  }, []);

  useEffect(() => {
    void refreshBase();
    const offSync = on('mail.sync', (value) => {
      setState((current) => (current ? { ...current, sync: value as MailState['sync'] } : current));
      const phase = (value as MailState['sync'])?.phase;
      if (phase === 'idle') {
        setSyncing(false);
        void refreshBase();
        setTick((number) => number + 1);
      }
      if (phase === 'error') setSyncing(false);
    });
    const offState = on('mail.state', (value) => setState(value as MailState));
    return () => {
      offSync();
      offState();
    };
  }, [refreshBase]);

  useEffect(() => {
    if (!state?.account || autoSyncRef.current) return;
    autoSyncRef.current = true;
    if (!state.account.lastSyncedAt && state.sync.phase === 'idle') {
      void syncNow(true);
    }
  }, [state?.account?.address]);

  useEffect(() => {
    if (!state?.account || !selection) {
      setMessages([]);
      return;
    }
    if (selection.kind !== 'server') {
      setMessages([]);
      setListLoading(false);
      return;
    }
    let alive = true;
    setListLoading(true);
    void call<MailMessageSummary[]>('mail.listMessages', selection.folder.id, query.trim(), 200)
      .then((value) => {
        if (alive) setMessages(value ?? []);
      })
      .catch((cause) => {
        if (alive) toast.error(cause instanceof Error ? cause.message : '读取邮件列表失败');
      })
      .finally(() => {
        if (alive) setListLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [state?.account?.address, selection, query, tick]);

  useEffect(() => {
    const raw = params.mailMessageId;
    const id = typeof raw === 'number' ? raw : Number(raw);
    if (!Number.isFinite(id) || id <= 0 || mailRouteRef.current === id || folders.length === 0) return;
    mailRouteRef.current = id;
    const folderPath = typeof params.mailFolder === 'string' ? params.mailFolder : '';
    const folder =
      folders.find((item) => item.path === folderPath) ??
      folders.find((item) => item.specialUse === 'inbox' || item.path.toLowerCase() === 'inbox') ??
      folders[0];
    if (folder) setSelection({ kind: 'server', folder });
    setSelectedId(id);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    void call<MailMessageDetail>('mail.getMessage', id)
      .then((value) => {
        setDetail(value);
        void call('mail.markSeen', id, true)
          .then(() => {
            setDetail((current) => (current ? { ...current, seen: true } : current));
            void refreshBase();
          })
          .catch(() => {});
      })
      .catch((cause) => setDetailError(cause instanceof Error ? cause.message : String(cause)))
      .finally(() => setDetailLoading(false));
    navigate('mail', {});
  }, [folders, navigate, params, refreshBase]);

  const serverFolders = useMemo(
    () => folders.filter((folder) => folder.specialUse !== 'drafts' && folder.specialUse !== 'trash'),
    [folders],
  );
  const visibleDrafts = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) return drafts;
    return drafts.filter(
      (draft) =>
        draft.subject.toLowerCase().includes(text) ||
        draft.to.join(' ').toLowerCase().includes(text),
    );
  }, [drafts, query]);
  const visibleTrash = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) return trash;
    return trash.filter(
      (item) =>
        (item.subject ?? '').toLowerCase().includes(text) ||
        (item.sender?.name ?? item.sender?.address ?? '').toLowerCase().includes(text),
    );
  }, [trash, query]);
  const unreadTotal = serverFolders.reduce((sum, folder) => sum + (folder.unread ?? 0), 0);

  const syncNow = async (silent = false) => {
    if (syncing) return;
    setSyncing(true);
    try {
      if (silent) {
        await call('mail.sync');
      } else {
        await toast
          .promise(call('mail.sync'), {
            loading: '正在收取邮件…',
            success: '邮件同步完成',
            error: (cause) => (cause instanceof Error ? cause.message : '同步失败'),
          })
          .unwrap();
      }
    } catch {
      // toast already shown
    } finally {
      setSyncing(false);
      setTick((number) => number + 1);
    }
  };

  const selectServerFolder = (folder: MailFolder) => {
    setSelection({ kind: 'server', folder });
    setSelectedId(null);
    setDetail(null);
    setQuery('');
  };

  const openMessage = async (summary: MailMessageSummary, source: 'server' | 'trash') => {
    setSelectedId(summary.id);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      const value = await call<MailMessageDetail>('mail.getMessage', summary.id);
      setDetail(value);
      if (source === 'server') {
        void call('mail.markSeen', summary.id, true)
          .then(() => {
            setMessages((current) =>
              current.map((item) => (item.id === summary.id ? { ...item, seen: true } : item)),
            );
            const activeFolderId = selection?.kind === 'server' ? selection.folder.id : null;
            setFolders((current) =>
              current.map((folder) =>
                folder.id === activeFolderId
                  ? { ...folder, unread: Math.max(0, (folder.unread ?? 0) - 1) }
                  : folder,
              ),
            );
          })
          .catch(() => {});
      }
    } catch (cause) {
      setDetailError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setDetailLoading(false);
    }
  };

  const openDraft = async (summary: MailDraftSummary) => {
    try {
      const draft = await call<MailDraft>('mail.getDraft', summary.id);
      setComposer({
        draftId: draft.id,
        to: draft.to.join(', '),
        cc: draft.cc.join(', '),
        bcc: draft.bcc.join(', '),
        subject: draft.subject,
        text: draft.text,
        attachments: draft.attachments ?? [],
        showCc: draft.cc.length > 0,
        showBcc: draft.bcc.length > 0,
      });
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '打开草稿失败');
    }
  };

  const closeComposer = () => {
    if (composer) void saveDraft(composer);
    setComposer(null);
  };

  const newComposer = () => {
    setComposer({
      to: '',
      cc: '',
      bcc: '',
      subject: '',
      text: '',
      attachments: [],
      showCc: false,
      showBcc: false,
    });
  };

  const reply = (mode: 'reply' | 'forward') => {
    if (!detail) return;
    const sender = detail.sender?.address ?? '';
    const subject = detail.subject ?? '';
    const quoted = detail.bodyText ?? '';
    setComposer({
      to: mode === 'reply' ? sender : '',
      cc: '',
      bcc: '',
      subject: `${mode === 'reply' ? 'Re:' : 'Fwd:'} ${subject}`.trim(),
      text:
        mode === 'reply'
          ? `\n\n--- 原邮件 ---\n发件人：${detail.sender?.name ?? sender}\n${quoted}`
          : `\n\n--- 转发邮件 ---\n发件人：${detail.sender?.name ?? sender}\n收件人：${detail.to
              .map((item) => item.address)
              .filter(Boolean)
              .join(', ')}\n\n${quoted}`,
      attachments: mode === 'forward' ? [] : [],
      showCc: false,
      showBcc: false,
    });
  };

  const saveDraft = async (value: ComposerState) => {
    if (savingDraftRef.current) return;
    if (!value.to.trim() && !value.subject.trim() && !value.text.trim()) return;
    savingDraftRef.current = true;
    try {
      const draft = await call<MailDraft>('mail.saveDraft', {
        id: value.draftId,
        to: splitRecipients(value.to),
        cc: splitRecipients(value.cc),
        bcc: splitRecipients(value.bcc),
        subject: value.subject,
        text: value.text,
        html: `<div>${escapeHtml(value.text).replaceAll('\n', '<br>')}</div>`,
        attachments: value.attachments,
      });
      if (draft?.id) {
        setComposer((current) =>
          current && current.draftId !== draft.id ? { ...current, draftId: draft.id } : current,
        );
      }
      setTick((number) => number + 1);
    } catch {
      // autosave failures are silent
    } finally {
      savingDraftRef.current = false;
    }
  };

  useEffect(() => {
    if (!composer) return;
    const timer = window.setTimeout(() => void saveDraft(composer), 1200);
    return () => window.clearTimeout(timer);
  }, [composer]);

  const sendMessage = async () => {
    if (!composer || sending) return;
    const to = splitRecipients(composer.to);
    if (to.length === 0) {
      toast.error('请至少填写一个收件人');
      return;
    }
    setSending(true);
    try {
      const result = await call<MailSendResult>('mail.send', {
        to,
        cc: splitRecipients(composer.cc),
        bcc: splitRecipients(composer.bcc),
        subject: composer.subject,
        text: composer.text,
        html: `<div>${escapeHtml(composer.text).replaceAll('\n', '<br>')}</div>`,
        attachments: composer.attachments,
      });
      if (result?.warning) toast.warning('邮件已发送', { description: result.warning });
      else toast.success('邮件已发送');
      if (composer.draftId) {
        void call('mail.deleteDraft', composer.draftId).catch(() => {});
      }
      setComposer(null);
      setTick((number) => number + 1);
      void refreshBase();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '发送失败');
    } finally {
      setSending(false);
    }
  };

  const addAttachment = async () => {
    const path = await call<string | null>('app.chooseFiles', '选择附件');
    if (path) setComposer((current) => (current ? { ...current, attachments: [...current.attachments, path] } : current));
  };

  const removeAttachment = (path: string) => {
    setComposer((current) =>
      current ? { ...current, attachments: current.attachments.filter((item) => item !== path) } : current,
    );
  };

  const deleteSelected = async () => {
    if (!selectedId) return;
    try {
      await call('mail.deleteMessage', selectedId);
      toast.success('已移入回收站');
      setSelectedId(null);
      setDetail(null);
      setTick((number) => number + 1);
      void refreshBase();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '删除失败');
    }
  };

  const restoreSelected = async () => {
    if (!selectedId) return;
    try {
      await call('mail.restoreMessage', selectedId);
      toast.success('已恢复到原文件夹');
      setSelectedId(null);
      setDetail(null);
      void refreshBase();
      setTick((number) => number + 1);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '恢复失败');
    }
  };

  const deleteForever = async () => {
    if (!selectedId) return;
    try {
      await call('mail.deleteForever', selectedId);
      toast.success('已永久删除');
      setSelectedId(null);
      setDetail(null);
      void refreshBase();
      setTick((number) => number + 1);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '删除失败');
    }
  };

  const downloadAttachment = async (part: string) => {
    if (!detail) return;
    try {
      const result = await call<{ url: string; filename: string }>('mail.attachmentUrl', detail.id, part);
      if (result?.url) void call('app.openExternal', result.url);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '附件不可用');
    }
  };

  const previewAttachment = async (part: string) => {
    if (!detail) return;
    setPreviewPart(part);
    setPreviewLoading(true);
    try {
      setPreviewDoc(await call<DocumentContent>('mail.previewAttachment', detail.id, part));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '附件预览失败');
    } finally {
      setPreviewLoading(false);
      setPreviewPart(null);
    }
  };

  const markUnread = async () => {
    if (!detail) return;
    try {
      await call('mail.markSeen', detail.id, false);
      setMessages((current) => current.map((item) => (item.id === detail.id ? { ...item, seen: false } : item)));
      toast.success('已标为未读');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '操作失败');
    }
  };

  const selectedFolder = selection?.kind === 'server' ? selection.folder : null;
  const listCount =
    selection?.kind === 'server'
      ? messages.length
      : selection?.kind === 'drafts'
        ? visibleDrafts.length
        : visibleTrash.length;

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在读取邮箱…
      </div>
    );
  }

  if (!state?.account) {
    return (
      <div className="flex h-full flex-col">
        <PageHeader title="邮箱" description="交大邮箱或任意 IMAP / SMTP 邮箱。" eyebrow="Mail" />
        <div className="flex flex-1 items-center justify-center p-6">
          <Card className="max-w-xl overflow-hidden">
            <div className="flex items-center gap-3 border-b border-line bg-surface-2 px-5 py-4">
              <div className="grid size-11 place-items-center rounded-xl bg-accent-soft text-accent">
                <Mail className="size-5" />
              </div>
              <div>
                <div className="text-sm font-semibold text-ink">配置校园邮箱</div>
                <div className="mt-0.5 text-xs text-ink-3">收信、写信、草稿与附件都在本机处理。</div>
              </div>
            </div>
            <div className="space-y-3 p-5 text-xs leading-relaxed text-ink-2">
              <p>支持交大邮箱（mail.sjtu.edu.cn）与任意第三方 IMAP / SMTP 邮箱，SSL/TLS 固定开启。</p>
              <div className="rounded-lg border border-line bg-surface-2 p-3 text-[0.68rem] text-ink-3">
                密码使用 Windows DPAPI 加密保存在本机；首次同步会建立本地邮件索引，打开邮件时再按需获取正文与附件。
              </div>
              <div className="flex items-center gap-2">
                <Button variant="primary" onClick={() => navigate('settings', { settingsSection: 'accounts' })}>
                  配置邮箱
                </Button>
                <Button variant="ghost" onClick={() => navigate('settings', { settingsSection: 'accounts' })}>
                  <Settings2 className="size-3.5" /> 账户设置
                </Button>
              </div>
            </div>
          </Card>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="邮箱"
        description={state.account.address}
        eyebrow="Mail"
        actions={
          <>
            {state.sync.phase === 'syncing' || syncing ? (
              <span className="flex items-center gap-1.5 text-[0.68rem] text-ink-3">
                <Loader2 className="size-3.5 animate-spin" /> 同步中
              </span>
            ) : (
              <span className="text-[0.68rem] text-ink-3">
                {state.account.lastSyncedAt ? `上次同步 ${relativeTime(state.account.lastSyncedAt)}` : '尚未同步'}
              </span>
            )}
            <Button variant="secondary" onClick={() => void syncNow()} disabled={syncing}>
              <RefreshCw className={cn('size-4', syncing && 'animate-spin')} /> 收取
            </Button>
            <Button variant="primary" onClick={newComposer}>
              <PenLine className="size-4" /> 写信
            </Button>
          </>
        }
      />
      <div className="flex min-h-0 flex-1 border-t border-line bg-surface">
        <aside className="flex w-[13rem] shrink-0 flex-col border-r border-line bg-surface">
          <div className="border-b border-line px-3 py-2 text-[0.62rem] font-semibold uppercase tracking-[0.12em] text-ink-3">
            {unreadTotal > 0 ? `${unreadTotal} 封未读` : '文件夹'}
          </div>
          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto p-2">
            {serverFolders.map((folder) => {
              const Icon = folderIcon(folder);
              const active = selection?.kind === 'server' && selection.folder.id === folder.id;
              return (
                <button
                  key={folder.id}
                  type="button"
                  onClick={() => selectServerFolder(folder)}
                  className={cn(
                    'flex h-8 w-full items-center gap-2 rounded-md px-2 text-left text-xs transition-colors',
                    active ? 'bg-selected text-accent' : 'text-ink-2 hover:bg-hover hover:text-ink',
                  )}
                >
                  <Icon className="size-3.5 shrink-0" />
                  <span className="min-w-0 flex-1 truncate">{folderLabel(folder)}</span>
                  {folder.unread && folder.unread > 0 ? (
                    <span className="rounded-full bg-accent px-1 text-[0.6rem] font-semibold leading-4 text-white">
                      {folder.unread > 99 ? '99+' : folder.unread}
                    </span>
                  ) : null}
                </button>
              );
            })}
            <div className="my-2 h-px bg-line" />
            <button
              type="button"
              onClick={() => {
                setSelection({ kind: 'drafts' });
                setSelectedId(null);
                setDetail(null);
              }}
              className={cn(
                'flex h-8 w-full items-center gap-2 rounded-md px-2 text-left text-xs transition-colors',
                selection?.kind === 'drafts' ? 'bg-selected text-accent' : 'text-ink-2 hover:bg-hover hover:text-ink',
              )}
            >
              <FileEdit className="size-3.5" />
              <span className="min-w-0 flex-1 truncate">草稿</span>
              {drafts.length ? <span className="text-[0.62rem] text-ink-3">{drafts.length}</span> : null}
            </button>
            <button
              type="button"
              onClick={() => {
                setSelection({ kind: 'trash' });
                setSelectedId(null);
                setDetail(null);
              }}
              className={cn(
                'flex h-8 w-full items-center gap-2 rounded-md px-2 text-left text-xs transition-colors',
                selection?.kind === 'trash' ? 'bg-selected text-accent' : 'text-ink-2 hover:bg-hover hover:text-ink',
              )}
            >
              <Trash2 className="size-3.5" />
              <span className="min-w-0 flex-1 truncate">回收站</span>
              {trash.length ? <span className="text-[0.62rem] text-ink-3">{trash.length}</span> : null}
            </button>
          </div>
          <div className="border-t border-line px-3 py-2 text-[0.62rem] leading-relaxed text-ink-3">
            删除的邮件只进入本地回收站，不会从服务器删除。
          </div>
        </aside>

        <section className="flex w-[21rem] shrink-0 flex-col border-r border-line bg-surface">
          <div className="flex items-center justify-between gap-2 border-b border-line px-3 py-2">
            <div className="min-w-0">
              <div className="truncate text-xs font-semibold text-ink">
                {selection?.kind === 'server'
                  ? (selectedFolder ? folderLabel(selectedFolder) : '')
                  : selection?.kind === 'drafts'
                    ? '草稿'
                    : '回收站'}
              </div>
              <div className="mt-0.5 text-[0.62rem] text-ink-3">{listCount} 封</div>
            </div>
            {selection?.kind === 'trash' && trash.length > 0 ? (
              <Button
                size="sm"
                variant="ghost"
                onClick={() =>
                  void call('mail.emptyTrash').then(() => {
                    toast.success('回收站已清空');
                    void refreshBase();
                    setSelectedId(null);
                    setDetail(null);
                  })
                }
              >
                清空
              </Button>
            ) : null}
          </div>
          <div className="border-b border-line p-2">
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索邮件"
                className="h-8 pl-8 text-xs"
              />
            </div>
          </div>
          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto">
            {listLoading ? (
              <div className="flex items-center justify-center gap-2 py-10 text-xs text-ink-3">
                <Spinner /> 加载中…
              </div>
            ) : selection?.kind === 'server' ? (
              messages.length === 0 ? (
                <EmptyState
                  compact
                  icon={MailOpen}
                  title="没有邮件"
                  description={query ? '没有匹配的邮件。' : '点击「收取」同步服务器邮件。'}
                />
              ) : (
                messages.map((message) => (
                  <MailListItem
                    key={message.id}
                    summary={message}
                    active={selectedId === message.id}
                    onClick={() => void openMessage(message, 'server')}
                  />
                ))
              )
            ) : selection?.kind === 'drafts' ? (
              visibleDrafts.length === 0 ? (
                <EmptyState compact icon={FileEdit} title="没有草稿" description="写信后会自动保存到本地草稿。" />
              ) : (
                visibleDrafts.map((draft) => (
                  <button
                    key={draft.id}
                    type="button"
                    onClick={() => void openDraft(draft)}
                    className="flex w-full flex-col gap-1 border-b border-line px-3 py-2.5 text-left hover:bg-hover"
                  >
                    <div className="flex items-center gap-2">
                      <span className="min-w-0 flex-1 truncate text-xs font-medium text-ink">
                        {draft.subject || '(无主题)'}
                      </span>
                      <span className="shrink-0 text-[0.62rem] text-ink-3">
                        {relativeTime(draft.updatedAt)}
                      </span>
                    </div>
                    <div className="truncate text-[0.66rem] text-ink-3">
                      收件人：{draft.to.join(', ') || '（未填写）'}
                    </div>
                    {draft.attachments > 0 ? (
                      <div className="flex items-center gap-1 text-[0.62rem] text-ink-3">
                        <Paperclip className="size-3" /> {draft.attachments} 个附件
                      </div>
                    ) : null}
                  </button>
                ))
              )
            ) : visibleTrash.length === 0 ? (
              <EmptyState compact icon={Trash2} title="回收站为空" description="删除的邮件会先进入这里。" />
            ) : (
              visibleTrash.map((item) => (
                <MailListItem
                  key={item.id}
                  summary={item}
                  active={selectedId === item.id}
                  onClick={() => void openMessage(item, 'trash')}
                  meta={`${item.folderName} · ${relativeTime(item.deletedAt)}`}
                />
              ))
            )}
          </div>
        </section>

        <section className="flex min-w-0 flex-1 flex-col bg-surface">
          {detailLoading ? (
            <div className="flex flex-1 items-center justify-center gap-2 text-xs text-ink-3">
              <Spinner /> 正在读取邮件正文…
            </div>
          ) : detailError ? (
            <div className="m-6 rounded-lg border border-danger/30 bg-danger/10 p-4 text-xs text-danger">
              {detailError}
            </div>
          ) : !detail ? (
            <EmptyState
              icon={MailOpen}
              title="选择一封邮件"
              description="从中间列表打开邮件；也可以直接写一封新邮件。"
              action={
                <Button size="sm" variant="primary" onClick={newComposer}>
                  <PenLine className="size-3.5" /> 写信
                </Button>
              }
            />
          ) : (
            <>
              <div className="shrink-0 border-b border-line px-4 py-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h2 className="text-sm font-semibold leading-relaxed text-ink">
                      {detail.subject || '(无主题)'}
                    </h2>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-[0.68rem] text-ink-3">
                      <span className="font-medium text-ink-2">
                        {detail.sender?.name || detail.sender?.address || '(未知发件人)'}
                      </span>
                      {detail.sender?.name && detail.sender?.address ? (
                        <span>&lt;{detail.sender.address}&gt;</span>
                      ) : null}
                      <span>· {formatDate(detail.date, 'yyyy-MM-dd HH:mm')}</span>
                      {detail.attachments.length > 0 ? (
                        <span className="inline-flex items-center gap-1">
                          <Paperclip className="size-3" /> {detail.attachments.length}
                        </span>
                      ) : null}
                    </div>
                    {detail.to.length > 0 ? (
                      <div className="mt-1 truncate text-[0.64rem] text-ink-3">
                        收件人：{detail.to.map((item) => item.name || item.address).filter(Boolean).join(', ')}
                      </div>
                    ) : null}
                  </div>
                  <div className="flex shrink-0 items-center gap-1">
                    {selection?.kind !== 'trash' ? (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => reply('reply')} title="回复">
                          <Reply className="size-3.5" />
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => reply('forward')} title="转发">
                          <Forward className="size-3.5" />
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => void markUnread()} title="标为未读">
                          <Mail className="size-3.5" />
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => void deleteSelected()} title="删除">
                          <Trash2 className="size-3.5" />
                        </Button>
                      </>
                    ) : (
                      <>
                        <Button size="sm" variant="ghost" onClick={() => void restoreSelected()}>
                          <Undo2 className="size-3.5" /> 恢复
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => void deleteForever()}>
                          <Trash2 className="size-3.5" /> 永久删除
                        </Button>
                      </>
                    )}
                  </div>
                </div>
              </div>
              {selection?.kind === 'trash' ? (
                <div className="border-b border-line bg-warning/10 px-4 py-2 text-[0.66rem] text-warning">
                  这封邮件在本地回收站中，服务器上的原邮件未删除。
                </div>
              ) : null}
              {detail.bodyHtml && detail.bodyText ? (
                <div className="flex shrink-0 items-center justify-between gap-3 border-b border-line bg-surface-2 px-4 py-1.5 text-[0.64rem] text-ink-3">
                  <span className="min-w-0 truncate">
                    {(detail.blockedImageHosts?.length ?? 0) > 0
                      ? `已隐藏 ${detail.blockedImageHosts?.join('、')} 上无法访问的图片，默认显示纯文本。`
                      : '这封邮件同时包含 HTML 与纯文本正文。'}
                  </span>
                  <span className="flex shrink-0 items-center gap-1">
                    <button
                      type="button"
                      onClick={() => setMailView('text')}
                      className={mailView === 'text' ? 'rounded px-1.5 py-0.5 text-accent' : 'rounded px-1.5 py-0.5 hover:bg-hover hover:text-ink-2'}
                    >
                      纯文本
                    </button>
                    <button
                      type="button"
                      onClick={() => setMailView('html')}
                      className={mailView === 'html' ? 'rounded px-1.5 py-0.5 text-accent' : 'rounded px-1.5 py-0.5 hover:bg-hover hover:text-ink-2'}
                    >
                      原始排版
                    </button>
                  </span>
                </div>
              ) : null}
              <div className="min-h-0 flex-1">
                {mailView === 'html' && detail.bodyHtml ? (
                  <HtmlFrame html={detail.bodyHtml} title={detail.subject ?? '邮件正文'} />
                ) : detail.bodyText ? (
                  <pre className="scroll-thin selectable h-full overflow-auto whitespace-pre-wrap break-words p-5 font-sans text-[0.82rem] leading-relaxed text-ink-2">
                    {detail.bodyText}
                  </pre>
                ) : detail.bodyHtml ? (
                  <HtmlFrame html={detail.bodyHtml} title={detail.subject ?? '邮件正文'} />
                ) : (
                  <EmptyState icon={MailOpen} title="正文为空" description="这封邮件没有可显示的正文。" />
                )}
              </div>
              {detail.attachments.length > 0 ? (
                <div className="shrink-0 border-t border-line bg-surface-2 px-4 py-3">
                  <div className="mb-2 flex items-center gap-1.5 text-[0.66rem] font-medium text-ink-2">
                    <Paperclip className="size-3.5" /> 附件
                  </div>
                  <div className="flex flex-wrap gap-2">
                    {detail.attachments.map((attachment) => (
                      <div
                        key={attachment.part}
                        className="flex max-w-[18rem] items-center gap-1 rounded-lg border border-line bg-surface pl-2.5 transition-colors hover:border-line-strong"
                      >
                        <button
                          type="button"
                          disabled={!attachment.part || previewLoading}
                          onClick={() => attachment.part && void previewAttachment(attachment.part)}
                          className="flex min-w-0 flex-1 items-center gap-2 py-1.5 text-left disabled:opacity-60"
                          title="内嵌预览"
                        >
                          <FileText className="size-3.5 shrink-0 text-ink-3" />
                          <span className="min-w-0 flex-1 truncate text-[0.66rem] text-ink-2">
                            {attachment.filename}
                          </span>
                          <span className="shrink-0 text-[0.6rem] text-ink-3">{formatBytes(attachment.size)}</span>
                          {previewPart === attachment.part ? <Spinner className="size-3 shrink-0" /> : null}
                        </button>
                        <button
                          type="button"
                          onClick={() => attachment.part && void downloadAttachment(attachment.part)}
                          className="rounded p-1.5 text-ink-3 hover:bg-hover hover:text-ink"
                          title="下载附件"
                          aria-label="下载附件"
                        >
                          <Download className="size-3" />
                        </button>
                      </div>
                    ))}
                  </div>
                </div>
              ) : null}
            </>
          )}
        </section>
      </div>

      {previewDoc ? (
        <div className="fixed inset-0 z-[70] flex bg-black/45 p-4">
          <div className="mx-auto h-full w-full max-w-6xl overflow-hidden rounded-xl border border-line bg-surface shadow-[var(--shadow-pop)]">
            <PreviewPane
              doc={previewDoc}
              loading={previewLoading}
              actions={
                <Button size="sm" variant="secondary" onClick={() => setPreviewDoc(null)}>
                  关闭
                </Button>
              }
            />
          </div>
        </div>
      ) : null}

      <Dialog open={composer !== null} onOpenChange={(open) => !open && closeComposer()}>
        <DialogContent
          title={composer?.draftId ? '编辑草稿' : '写邮件'}
          description="邮件会通过已配置的 SMTP 服务器发送；发送前请确认收件人。"
          wide
          footer={
            <>
              <Button variant="ghost" onClick={closeComposer} disabled={sending}>
                取消
              </Button>
              <Button variant="secondary" onClick={() => composer && void saveDraft(composer)} disabled={sending}>
                存草稿
              </Button>
              <Button variant="primary" onClick={() => void sendMessage()} disabled={sending}>
                {sending ? <Loader2 className="size-4 animate-spin" /> : <Send className="size-4" />}
                发送
              </Button>
            </>
          }
        >
          {composer ? (
            <div className="space-y-3">
              <div className="flex items-center gap-2">
                <label className="w-12 shrink-0 text-xs text-ink-3">收件人</label>
                <Input
                  value={composer.to}
                  onChange={(event) => setComposer({ ...composer, to: event.target.value })}
                  placeholder="多个地址用逗号分隔"
                  autoFocus
                />
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setComposer({ ...composer, showCc: !composer.showCc })}
                >
                  Cc
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => setComposer({ ...composer, showBcc: !composer.showBcc })}
                >
                  Bcc
                </Button>
              </div>
              {composer.showCc ? (
                <div className="flex items-center gap-2">
                  <label className="w-12 shrink-0 text-xs text-ink-3">抄送</label>
                  <Input
                    value={composer.cc}
                    onChange={(event) => setComposer({ ...composer, cc: event.target.value })}
                    placeholder="抄送地址"
                  />
                </div>
              ) : null}
              {composer.showBcc ? (
                <div className="flex items-center gap-2">
                  <label className="w-12 shrink-0 text-xs text-ink-3">密送</label>
                  <Input
                    value={composer.bcc}
                    onChange={(event) => setComposer({ ...composer, bcc: event.target.value })}
                    placeholder="密送地址"
                  />
                </div>
              ) : null}
              <div className="flex items-center gap-2">
                <label className="w-12 shrink-0 text-xs text-ink-3">主题</label>
                <Input
                  value={composer.subject}
                  onChange={(event) => setComposer({ ...composer, subject: event.target.value })}
                  placeholder="邮件主题"
                />
              </div>
              <Textarea
                value={composer.text}
                onChange={(event) => setComposer({ ...composer, text: event.target.value })}
                placeholder="正文…"
                className="h-64"
              />
              <div className="flex flex-wrap items-center gap-2">
                <Button size="sm" variant="secondary" onClick={() => void addAttachment()}>
                  <Paperclip className="size-3.5" /> 添加附件
                </Button>
                {composer.attachments.map((path) => (
                  <span
                    key={path}
                    className="flex max-w-[20rem] items-center gap-1.5 rounded-md border border-line bg-surface-2 px-2 py-1 text-[0.66rem] text-ink-2"
                  >
                    <FileText className="size-3 shrink-0" />
                    <span className="min-w-0 truncate">{path.split(/[\\/]/).pop()}</span>
                    <button
                      type="button"
                      onClick={() => removeAttachment(path)}
                      className="rounded p-0.5 text-ink-3 hover:bg-hover hover:text-ink"
                      aria-label="移除附件"
                    >
                      <X className="size-3" />
                    </button>
                  </span>
                ))}
              </div>
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}
