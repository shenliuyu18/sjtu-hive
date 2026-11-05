import { loadPptViewer, type MountedPptViewer, type PptViewerRuntime } from '@file-viewer/ppt';
import { useEffect, useRef, useState } from 'react';
import { Spinner } from '@/components/ui/Misc';

let runtimePromise: Promise<PptViewerRuntime> | null = null;
function viewerRuntime(): Promise<PptViewerRuntime> {
  runtimePromise ??= loadPptViewer({ worker: false });
  return runtimePromise;
}

export function LegacyPptPreview({ url }: { url: string }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const mountedRef = useRef<MountedPptViewer | null>(null);
  const bufferRef = useRef<{ url: string; data: ArrayBuffer } | null>(null);
  const [width, setWidth] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let frame = 0;
    const measure = () => {
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const available = host.parentElement?.clientWidth ?? host.clientWidth;
        if (available <= 0) return;
        setWidth((current) => (Math.abs(current - available) >= 48 ? available : current));
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
    if (width <= 0 || !hostRef.current) return;
    let alive = true;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void (async () => {
      try {
        let buffer = bufferRef.current?.url === url ? bufferRef.current.data : null;
        if (!buffer) {
          const response = await fetch(url, { signal: controller.signal });
          if (!response.ok) throw new Error(`读取 PowerPoint 文件失败（HTTP ${response.status}）`);
          buffer = await response.arrayBuffer();
          bufferRef.current = { url, data: buffer };
        }
        const runtime = await viewerRuntime();
        const opened = (await runtime.open(buffer)) as unknown as { width?: number };
        if (!alive || !hostRef.current) return;
        const scale = Math.min(1, (width - 32) / (opened.width || 960));
        hostRef.current.innerHTML = '';
        const mounted = await runtime.mount(hostRef.current, buffer, { scale, virtualize: true });
        if (!alive) {
          void mounted.close();
          return;
        }
        mountedRef.current = mounted;
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
      const mounted = mountedRef.current;
      mountedRef.current = null;
      if (mounted) void mounted.close();
    };
  }, [url, width]);

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
      <div ref={hostRef} className="legacy-ppt-host mx-auto" />
    </div>
  );
}
