import { mountMsDoc, parseMsDocToHtml } from '@file-viewer/doc';
import { useEffect, useRef, useState } from 'react';
import { Spinner } from '@/components/ui/Misc';

export function LegacyDocPreview({ url }: { url: string }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    void (async () => {
      try {
        const response = await fetch(url, { signal: controller.signal });
        if (!response.ok) throw new Error(`读取 Word 文档失败（HTTP ${response.status}）`);
        const buffer = await response.arrayBuffer();
        const rendered = await parseMsDocToHtml(buffer);
        if (!alive || !hostRef.current) return;
        mountMsDoc(hostRef.current, rendered);
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
    <div className="relative h-full overflow-auto bg-surface-3 p-4">
      {loading ? (
        <div className="pointer-events-none absolute left-0 right-0 top-0 z-10 flex justify-center py-2">
          <span className="flex items-center gap-2 rounded-full border border-line bg-surface/95 px-3 py-1.5 text-[0.66rem] text-ink-3 shadow-[var(--shadow-card)]">
            <Spinner /> 正在渲染 Word 文档…
          </span>
        </div>
      ) : null}
      {error ? (
        <div className="mx-auto mt-10 max-w-md rounded-lg border border-danger/30 bg-danger/10 p-4 text-xs text-danger">
          {error}
        </div>
      ) : null}
      <div
        ref={hostRef}
        className="msdoc-preview-host mx-auto w-full max-w-[54rem] bg-white shadow-[var(--shadow-card)]"
      />
    </div>
  );
}
