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
  Minus,
  Music,
  Plus,
  Video,
} from 'lucide-react';
import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';
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
import { LegacyDocPreview } from './LegacyDocPreview';
import { LegacyPptPreview } from './LegacyPptPreview';

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
    doc: 'Word（旧版）',
    ppt: 'PowerPoint（旧版）',
    video: '视频',
    audio: '音频',
    link: '链接',
    binary: '二进制文件',
  };
  return labels[format] ?? format.toUpperCase();
}

const ZOOM_FORMATS = new Set(['html', 'markdown', 'pdf', 'image', 'docx', 'xlsx', 'pptx', 'doc', 'ppt', 'text']);
const ZOOM_MIN = 0.5;
const ZOOM_MAX = 4;

function clampZoom(value: number) {
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.round(value * 100) / 100));
}

function ImagePreview({
  url,
  title,
  zoom,
  onZoom,
}: {
  url: string;
  title: string;
  zoom: number;
  onZoom: (value: number) => void;
}) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; left: number; top: number } | null>(null);
  const stopDrag = () => {
    drag.current = null;
  };
  const onPointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (zoom <= 1) return;
    const host = scrollRef.current;
    if (!host) return;
    drag.current = { x: event.clientX, y: event.clientY, left: host.scrollLeft, top: host.scrollTop };
    host.setPointerCapture(event.pointerId);
  };
  const onPointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const host = scrollRef.current;
    const start = drag.current;
    if (!host || !start) return;
    host.scrollLeft = start.left - (event.clientX - start.x);
    host.scrollTop = start.top - (event.clientY - start.y);
  };
  return (
    <div
      ref={scrollRef}
      className={cn(
        'scroll-thin h-full overflow-auto bg-surface-3',
        zoom > 1 && 'cursor-grab active:cursor-grabbing',
      )}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={stopDrag}
      onPointerCancel={stopDrag}
      onDoubleClick={() => onZoom(zoom > 1 ? 1 : 2)}
      title="双击切换 100% / 200%，放大后可拖动"
    >
      <div className="flex min-h-full min-w-full items-center justify-center p-4" style={{ zoom }}>
        <img
          src={url}
          alt={title}
          draggable={false}
          className="max-h-full max-w-full rounded-lg object-contain shadow-[var(--shadow-card)]"
        />
      </div>
    </div>
  );
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

  const [zoom, setZoom] = useState(1);
  const bodyRef = useRef<HTMLDivElement>(null);
  const zoomable = !!doc && ZOOM_FORMATS.has(doc.format);

  useEffect(() => {
    setZoom(1);
  }, [doc?.format, doc?.resourceUrl, doc?.title]);

  useEffect(() => {
    if (!zoomable || loading || error) return;
    const host = bodyRef.current;
    if (!host) return;
    const onWheel = (event: WheelEvent) => {
      if (!event.ctrlKey) return;
      event.preventDefault();
      setZoom((value) => clampZoom(value - event.deltaY * 0.002));
    };
    host.addEventListener('wheel', onWheel, { passive: false });
    return () => host.removeEventListener('wheel', onWheel);
  }, [zoomable, loading, error, doc]);

  useEffect(() => {
    if (!zoomable) return;
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target?.tagName === 'INPUT' || target?.tagName === 'TEXTAREA' || target?.isContentEditable) {
        return;
      }
      if (!(event.ctrlKey || event.metaKey)) return;
      if (event.key === '=' || event.key === '+') {
        event.preventDefault();
        setZoom((value) => clampZoom(value + 0.25));
      } else if (event.key === '-') {
        event.preventDefault();
        setZoom((value) => clampZoom(value - 0.25));
      } else if (event.key === '0') {
        event.preventDefault();
        setZoom(1);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [zoomable]);

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
        return (
          <div className="scroll-thin h-full overflow-auto">
            <HtmlFrame html={doc.text ?? ''} title={doc.title} zoom={zoom} />
          </div>
        );
      case 'markdown':
        return (
          <div className="scroll-thin h-full overflow-y-auto">
            <div className="p-6" style={{ zoom }}>
              <MarkdownView text={doc.text ?? ''} assetBase={doc.assetBase} />
            </div>
          </div>
        );
      case 'pdf':
        return doc.resourceUrl ? (
          <div className="scroll-thin h-full overflow-auto bg-surface-3">
            <iframe
              title={doc.title}
              src={doc.resourceUrl}
              style={{ width: '100%', height: '100%', border: 0, zoom }}
            />
          </div>
        ) : (
          <div className="p-6 text-xs text-ink-3">没有可用的 PDF 地址</div>
        );
      case 'image':
        return doc.resourceUrl ? (
          <ImagePreview url={doc.resourceUrl} title={doc.title} zoom={zoom} onZoom={(value) => setZoom(clampZoom(value))} />
        ) : (
          <div className="flex h-full items-center justify-center p-4">
            <ImageIcon className="size-8 text-ink-3" />
          </div>
        );
      case 'docx':
        return doc.resourceUrl ? <DocxPreview url={doc.resourceUrl} zoom={zoom} /> : null;
      case 'xlsx':
        return doc.resourceUrl ? <SheetPreview url={doc.resourceUrl} zoom={zoom} /> : null;
      case 'pptx':
        return doc.resourceUrl ? <PptxPreview url={doc.resourceUrl} zoom={zoom} /> : null;
      case 'doc':
        return doc.resourceUrl ? <LegacyDocPreview url={doc.resourceUrl} zoom={zoom} /> : null;
      case 'ppt':
        return doc.resourceUrl ? <LegacyPptPreview url={doc.resourceUrl} zoom={zoom} /> : null;
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
          <div className="scroll-thin h-full overflow-auto">
            <pre
              className="selectable whitespace-pre-wrap break-words p-6 font-mono text-[0.78rem] leading-relaxed text-ink-2"
              style={{ zoom }}
            >
              {doc.text ?? ''}
            </pre>
          </div>
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
  const framed = ['html', 'pdf', 'docx', 'xlsx', 'pptx', 'doc', 'ppt', 'video', 'audio'].includes(doc.format);

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
      <div ref={bodyRef} className={cn('relative min-h-0 flex-1', !framed && 'overflow-hidden')}>
        {body()}
        {zoomable && !loading && !error ? (
          <div className="absolute bottom-4 right-4 z-20 flex items-center gap-0.5 rounded-full border border-line bg-surface/95 p-1 text-xs text-ink-2 shadow-[var(--shadow-pop)] backdrop-blur">
            <button
              type="button"
              title="缩小（Ctrl+-）"
              onClick={() => setZoom((value) => clampZoom(value - 0.25))}
              className="rounded-full p-1.5 transition-colors hover:bg-hover hover:text-ink disabled:opacity-40"
              disabled={zoom <= ZOOM_MIN}
            >
              <Minus className="size-3.5" />
            </button>
            <button
              type="button"
              title="重置为 100%（Ctrl+0）"
              onClick={() => setZoom(1)}
              className="min-w-[3.25rem] rounded-full px-1.5 py-1 text-center tabular-nums transition-colors hover:bg-hover hover:text-ink"
            >
              {Math.round(zoom * 100)}%
            </button>
            <button
              type="button"
              title="放大（Ctrl+=）"
              onClick={() => setZoom((value) => clampZoom(value + 0.25))}
              className="rounded-full p-1.5 transition-colors hover:bg-hover hover:text-ink disabled:opacity-40"
              disabled={zoom >= ZOOM_MAX}
            >
              <Plus className="size-3.5" />
            </button>
            <span className="mx-1 h-4 w-px bg-line" />
            <button
              type="button"
              title="适应窗口"
              onClick={() => setZoom(1)}
              className="rounded-full px-2 py-1 transition-colors hover:bg-hover hover:text-ink"
            >
              适应
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
  return pane;
}
