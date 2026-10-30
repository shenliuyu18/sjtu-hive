import { renderAsync } from 'docx-preview';
import { useEffect, useRef, useState } from 'react';
import { Spinner } from '@/components/ui/Misc';

export function DocxPreview({ url }: { url: string }) {
  const ref = useRef<HTMLDivElement>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    void fetch(url)
      .then((response) => {
        if (!response.ok) throw new Error(`读取文档失败（HTTP ${response.status}）`);
        return response.arrayBuffer();
      })
      .then((buffer) => {
        if (!alive || !ref.current) return;
        ref.current.innerHTML = '';
        return renderAsync(buffer, ref.current, undefined, {
          className: 'docx',
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false,
          breakPages: true,
          renderHeaders: true,
          renderFooters: true,
          renderFootnotes: true,
        });
      })
      .catch((cause) => {
        if (alive) setError(cause instanceof Error ? cause.message : String(cause));
      })
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, [url]);

  return (
    <div className="relative h-full overflow-auto bg-surface-3 p-4">
      {loading ? (
        <div className="absolute inset-0 z-10 flex items-center justify-center gap-2 bg-surface-3/80 text-xs text-ink-3">
          <Spinner /> 正在渲染 Word 文档…
        </div>
      ) : null}
      {error ? (
        <div className="mx-auto mt-10 max-w-md rounded-lg border border-danger/30 bg-danger/10 p-4 text-xs text-danger">
          {error}
        </div>
      ) : null}
      <div ref={ref} className="docx-preview-host mx-auto" />
    </div>
  );
}
