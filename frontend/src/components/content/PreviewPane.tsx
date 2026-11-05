import {
  AlertCircle,
  Download,
  ExternalLink,
  FileQuestion,
  FolderOpen,
  Image as ImageIcon,
  Link2,
  Maximize2,
  Minimize2,
  Music,
  Video,
} from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import type { DocumentContent } from '@/lib/models';
import { formatBytes, formatDate } from '@/lib/format';
import { call } from '@/lib/bridge';
import { cn } from '@/lib/cn';
import { Badge, EmptyState, Spinner } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';
import { HtmlFrame } from './HtmlFrame';
import { MarkdownView } from './MarkdownView';
import { DocxPreview } from './DocxPreview';
import { SheetPreview } from './SheetPreview';
import { PptxPreview } from './PptxPreview';

function formatLabel(format: string) {
  const labels: Record<string, string> = {
    markdown: 'Markdown',
    text: '文本',
    html: '网页 / HTML',
    pdf: 'PDF',
    image: '图片',
    docx: 'Word',
    xlsx: 'Excel',
    pptx: 'PowerPoint',
    video: '视频',
    audio: '音频',
    link: '链接',
    binary: '二进制文件',
  };
  return labels[format] ?? format.toUpperCase();
}

function MetaBar({ doc }: { doc: DocumentContent }) {
  const meta = doc.meta ?? {};
  if (meta.kind !== 'assignment' && meta.kind !== 'page' && meta.kind !== 'discussion') return null;
  const dueAt = typeof meta.dueAt === 'string' ? meta.dueAt : null;
  const points = meta.points;
  const state = typeof meta.submissionState === 'string' ? meta.submissionState : null;
  return (
    <div className="flex flex-wrap items-center gap-2 border-b border-line bg-surface-2 px-4 py-2 text-[0.68rem] text-ink-3">
      {meta.kind === 'assignment' ? (
        <>
          <Badge variant={state === 'submitted' || state === 'graded' ? 'success' : 'warning'}>
            {state === 'graded' ? '已评分' : state === 'submitted' ? '已提交' : '未提交'}
          </Badge>
          {dueAt ? <span>截止 {formatDate(dueAt, 'M月d日 HH:mm')}</span> : <span>无截止时间</span>}
          {points !== null && points !== undefined ? <span>满分 {String(points)}</span> : null}
        </>
      ) : null}
      {meta.kind === 'discussion' ? <Badge variant="accent">讨论</Badge> : null}
      {meta.kind === 'page' ? <Badge variant="neutral">课程页面</Badge> : null}
    </div>
  );
}

export function PreviewPane({
  doc,
  loading,
  error,
  actions,
  emptyText = '从左侧选择一个文件、作业或页面',
}: {
  doc: DocumentContent | null;
  loading?: boolean;
  error?: string | null;
  actions?: ReactNode;
  emptyText?: string;
}) {
  const [fullscreen, setFullscreen] = useState(false);
  useEffect(() => {
    if (!fullscreen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setFullscreen(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [fullscreen]);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在加载内容…
      </div>
    );
  }
  if (error) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <div className="max-w-md rounded-xl border border-danger/30 bg-danger/10 p-4 text-xs leading-relaxed text-danger">
          <AlertCircle className="mb-1.5 size-4" />
          {error}
        </div>
      </div>
    );
  }
  if (!doc) {
    return (
      <EmptyState
        icon={FileQuestion}
        title="还没有打开内容"
        description={emptyText}
      />
    );
  }
  const openLocal = () => {
    if (doc.path) void call('app.openPath', doc.path);
  };
  const body = () => {
    switch (doc.format) {
      case 'html':
        return <HtmlFrame html={doc.text ?? ''} title={doc.title} />;
      case 'markdown':
        return (
          <div className="scroll-thin h-full overflow-y-auto p-6">
            <MarkdownView text={doc.text ?? ''} assetBase={doc.assetBase} />
          </div>
        );
      case 'pdf':
        return doc.resourceUrl ? (
          <iframe title={doc.title} src={doc.resourceUrl} className="h-full w-full border-0 bg-surface-3" />
        ) : (
          <div className="p-6 text-xs text-ink-3">没有可用的 PDF 地址</div>
        );
      case 'image':
        return (
          <div className="flex h-full items-center justify-center overflow-auto bg-surface-3 p-4">
            {doc.resourceUrl ? (
              <img
                src={doc.resourceUrl}
                alt={doc.title}
                className="max-h-full max-w-full rounded-lg object-contain shadow-[var(--shadow-card)]"
              />
            ) : (
              <ImageIcon className="size-8 text-ink-3" />
            )}
          </div>
        );
      case 'docx':
        return doc.resourceUrl ? <DocxPreview url={doc.resourceUrl} /> : null;
      case 'xlsx':
        return doc.resourceUrl ? <SheetPreview url={doc.resourceUrl} /> : null;
      case 'pptx':
        return doc.resourceUrl ? <PptxPreview url={doc.resourceUrl} /> : null;
      case 'video':
        return (
          <div className="flex h-full items-center justify-center bg-black/90 p-4">
            {doc.resourceUrl ? (
              <video src={doc.resourceUrl} controls className="max-h-full max-w-full rounded-lg" />
            ) : (
              <Video className="size-8 text-white/40" />
            )}
          </div>
        );
      case 'audio':
        return (
          <div className="flex h-full flex-col items-center justify-center gap-4 bg-surface-3 p-6">
            <Music className="size-10 text-ink-3" />
            {doc.resourceUrl ? <audio src={doc.resourceUrl} controls className="w-full max-w-lg" /> : null}
          </div>
        );
      case 'text':
        return (
          <pre className="scroll-thin selectable h-full overflow-auto whitespace-pre-wrap break-words p-6 font-mono text-[0.78rem] leading-relaxed text-ink-2">
            {doc.text ?? ''}
          </pre>
        );
      case 'link':
        return (
          <EmptyState
            icon={Link2}
            title={doc.title}
            description={doc.text ?? ''}
            action={
              doc.text ? (
                <Button size="sm" onClick={() => void call('app.openExternal', doc.text)}>
                  <ExternalLink className="size-3.5" /> 在浏览器打开
                </Button>
              ) : null
            }
          />
        );
      case 'binary':
        return (
          <EmptyState
            icon={FileQuestion}
            title={doc.title}
            description={doc.message ?? '该格式暂不支持内嵌预览。'}
            action={
              doc.path ? (
                <Button size="sm" onClick={openLocal}>
                  <FolderOpen className="size-3.5" /> 用默认应用打开
                </Button>
              ) : null
            }
          />
        );
      default:
        return (
          <EmptyState
            icon={FileQuestion}
            title="暂不支持预览"
            description={doc.message ?? `格式：${doc.format}`}
          />
        );
    }
  };
  const framed = ['html', 'pdf', 'docx', 'xlsx', 'pptx', 'video', 'audio'].includes(doc.format);

  const pane = (
    <div
      className={cn(
        'flex h-full min-h-0 flex-col bg-surface',
        fullscreen && 'fixed inset-0 z-[80] h-auto shadow-[var(--shadow-pop)]',
      )}
    >
      <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-b border-line bg-surface px-4 py-3">
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-ink" title={doc.title}>
            {doc.title}
          </div>
          <div className="mt-0.5 flex items-center gap-2 text-[0.66rem] text-ink-3">
            <span>{formatLabel(doc.format)}</span>
            {doc.size > 0 ? <span>· {formatBytes(doc.size)}</span> : null}
            {doc.readOnly ? <span>· 只读</span> : null}
            {doc.modifiedAt ? <span>· {formatDate(doc.modifiedAt, 'M月d日 HH:mm')}</span> : null}
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-1.5">
          {actions}
          <Button
            size="sm"
            variant={fullscreen ? 'secondary' : 'ghost'}
            onClick={() => setFullscreen((value) => !value)}
            title={fullscreen ? '退出全屏（Esc）' : '全屏预览'}
          >
            {fullscreen ? <Minimize2 className="size-3.5" /> : <Maximize2 className="size-3.5" />}
            {fullscreen ? '退出全屏' : '全屏'}
          </Button>
          {fullscreen ? <span className="text-[0.62rem] text-ink-3">Esc</span> : null}
        </div>
      </div>
      <MetaBar doc={doc} />
      <div className={cn('min-h-0 flex-1', !framed && 'overflow-hidden')}>{body()}</div>
    </div>
  );
  return pane;
}
