import { init } from 'pptx-preview';
import { useEffect, useRef, useState } from 'react';
import { Spinner } from '@/components/ui/Misc';

const BASE_WIDTH = 1120;

export function PptxPreview({ url }: { url: string }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [zoom, setZoom] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const bufferRef = useRef<{ url: string; data: ArrayBuffer } | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let frame = 0;
    const measure = () => {
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const available = host.parentElement?.clientWidth ?? host.clientWidth;
        // 用缩放适配窗口宽度，避免全屏/拉伸时重建整份幻灯片。
        setZoom(Math.min(1, available / (BASE_WIDTH + 32)));
      });
    };
    measure();
    const observer = new ResizeObserver(measure);
    if (host.parentElement) observer.observe(host.parentElement);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [url]);

  useEffect(() => {
    let alive = true;
    const controller = new AbortController();
    setLoading(true);
    setError(null);

    void (async () => {
      try {
        let buffer = bufferRef.current?.url === url ? bufferRef.current.data : null;
        if (!buffer) {
          const response = await fetch(url, { signal: controller.signal });
          if (!response.ok) throw new Error(`读取演示文稿失败（HTTP ${response.status}）`);
          buffer = await response.arrayBuffer();
          bufferRef.current = { url, data: buffer };
        }
        if (!alive || !hostRef.current) return;
        const host = hostRef.current;
        host.innerHTML = '';
        const viewer = init(host, { width: BASE_WIDTH, mode: 'list' });
        const reveal = () => {
          if (host.querySelector('.pptx-preview-slide-wrapper')) setLoading(false);
        };
        const observer = new MutationObserver(reveal);
        observer.observe(host, { childList: true, subtree: true });
        try {
          await viewer.preview(buffer);
        } finally {
          observer.disconnect();
          reveal();
        }
      } catch (cause) {
        if (!alive || controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : String(cause));
      } finally {
        if (alive) setLoading(false);
      }
    })();

    return () => {
      alive = false;
      controller.abort();
    };
  }, [url]);

  return (
    <div className="relative h-full overflow-auto bg-surface-3 p-4 [scrollbar-gutter:stable]">
      {loading ? (
        <div className="pointer-events-none absolute left-0 right-0 top-0 z-10 flex justify-center py-2">
          <span className="flex items-center gap-2 rounded-full border border-line bg-surface/95 px-3 py-1.5 text-[0.66rem] text-ink-3 shadow-[var(--shadow-card)]">
            <Spinner /> 正在渲染幻灯片…
          </span>
        </div>
      ) : null}
      {error ? (
        <div className="mx-auto mt-10 max-w-md rounded-lg border border-danger/30 bg-danger/10 p-4 text-xs text-danger">
          {error}
        </div>
      ) : null}
      <div ref={hostRef} className="pptx-preview-host mx-auto" style={{ width: BASE_WIDTH, zoom }} />
    </div>
  );
}
