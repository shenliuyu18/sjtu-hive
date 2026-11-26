import { ExternalLink, Minus, Plus, RotateCcw, X } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { call } from '@/lib/bridge';
import { cn } from '@/lib/cn';

const ZOOM_MIN = 0.5;
const ZOOM_MAX = 4;

function clampZoom(value: number) {
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.round(value * 100) / 100));
}

export function ImageLightbox({
  src,
  alt,
  onClose,
}: {
  src: string;
  alt?: string;
  onClose: () => void;
}) {
  const [zoom, setZoom] = useState(1);
  const scrollRef = useRef<HTMLDivElement>(null);
  const drag = useRef<{ x: number; y: number; left: number; top: number } | null>(null);

  const zoomBy = useCallback((delta: number) => {
    setZoom((value) => clampZoom(value + delta));
  }, []);

  useEffect(() => {
    const element = scrollRef.current;
    if (!element) return;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      zoomBy(event.deltaY > 0 ? -0.25 : 0.25);
    };
    element.addEventListener('wheel', onWheel, { passive: false });
    return () => element.removeEventListener('wheel', onWheel);
  }, [zoomBy]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
      else if (event.key === '+' || event.key === '=') zoomBy(0.25);
      else if (event.key === '-' || event.key === '_') zoomBy(-0.25);
      else if (event.key === '0') setZoom(1);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose, zoomBy]);

  const stopDrag = () => {
    drag.current = null;
  };

  return createPortal(
    <div
      className="fixed inset-0 z-[80] flex flex-col bg-black/90 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="flex shrink-0 items-center gap-1 px-3 py-2 text-white/80">
        <span className="min-w-0 flex-1 truncate text-xs">{alt || '图片预览'}</span>
        <button
          type="button"
          aria-label="缩小"
          className="grid size-8 place-items-center rounded-lg hover:bg-white/10"
          onClick={() => zoomBy(-0.25)}
        >
          <Minus className="size-4" />
        </button>
        <span className="w-12 text-center text-xs tabular-nums">{Math.round(zoom * 100)}%</span>
        <button
          type="button"
          aria-label="放大"
          className="grid size-8 place-items-center rounded-lg hover:bg-white/10"
          onClick={() => zoomBy(0.25)}
        >
          <Plus className="size-4" />
        </button>
        <button
          type="button"
          aria-label="重置缩放"
          className="grid size-8 place-items-center rounded-lg hover:bg-white/10"
          onClick={() => setZoom(1)}
        >
          <RotateCcw className="size-4" />
        </button>
        <button
          type="button"
          aria-label="在浏览器打开原图"
          className="grid size-8 place-items-center rounded-lg hover:bg-white/10"
          onClick={() => void call('app.openExternal', src)}
        >
          <ExternalLink className="size-4" />
        </button>
        <button
          type="button"
          aria-label="关闭"
          className="grid size-8 place-items-center rounded-lg hover:bg-white/10"
          onClick={onClose}
        >
          <X className="size-4" />
        </button>
      </div>
      <div
        ref={scrollRef}
        className={cn(
          'scroll-thin min-h-0 flex-1 overflow-auto',
          zoom > 1 && 'cursor-grab active:cursor-grabbing',
        )}
        onPointerDown={(event) => {
          if (zoom <= 1) return;
          const host = scrollRef.current;
          if (!host) return;
          drag.current = { x: event.clientX, y: event.clientY, left: host.scrollLeft, top: host.scrollTop };
          host.setPointerCapture(event.pointerId);
        }}
        onPointerMove={(event) => {
          const host = scrollRef.current;
          const start = drag.current;
          if (!host || !start) return;
          host.scrollLeft = start.left - (event.clientX - start.x);
          host.scrollTop = start.top - (event.clientY - start.y);
        }}
        onPointerUp={stopDrag}
        onPointerCancel={stopDrag}
        onDoubleClick={() => setZoom((value) => (value > 1 ? 1 : 2))}
      >
        <div className="flex min-h-full min-w-full items-center justify-center p-4" style={{ zoom }}>
          <img
            src={src}
            alt={alt ?? ''}
            draggable={false}
            className="max-h-[calc(100vh-6rem)] max-w-full select-none object-contain"
          />
        </div>
      </div>
    </div>,
    document.body,
  );
}