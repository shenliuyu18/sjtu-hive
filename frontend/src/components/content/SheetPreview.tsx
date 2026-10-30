import * as XLSX from 'xlsx';
import { useEffect, useMemo, useState } from 'react';
import { Spinner } from '@/components/ui/Misc';
import { cn } from '@/lib/cn';

export function SheetPreview({ url }: { url: string }) {
  const [workbook, setWorkbook] = useState<XLSX.WorkBook | null>(null);
  const [sheet, setSheet] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    void fetch(url)
      .then((response) => {
        if (!response.ok) throw new Error(`读取表格失败（HTTP ${response.status}）`);
        return response.arrayBuffer();
      })
      .then((buffer) => {
        if (!alive) return;
        const next = XLSX.read(new Uint8Array(buffer), { type: 'array' });
        setWorkbook(next);
        setSheet(next.SheetNames[0] ?? '');
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

  const rows = useMemo(() => {
    if (!workbook || !sheet) return [];
    const data = XLSX.utils.sheet_to_json<unknown[]>(workbook.Sheets[sheet], {
      header: 1,
      defval: '',
      blankrows: false,
    });
    return data.slice(0, 200).map((row) => row.slice(0, 40));
  }, [workbook, sheet]);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在解析表格…
      </div>
    );
  }
  if (error) {
    return <div className="m-4 rounded-lg border border-danger/30 bg-danger/10 p-4 text-xs text-danger">{error}</div>;
  }
  if (!workbook) return null;

  return (
    <div className="flex h-full flex-col">
      {workbook.SheetNames.length > 1 ? (
        <div className="scroll-thin flex shrink-0 gap-1 overflow-x-auto border-b border-line bg-surface-2 px-2 py-1.5">
          {workbook.SheetNames.map((name) => (
            <button
              key={name}
              type="button"
              onClick={() => setSheet(name)}
              className={cn(
                'shrink-0 rounded-md px-2.5 py-1 text-[0.68rem] transition-colors',
                sheet === name ? 'bg-accent-soft text-accent' : 'text-ink-3 hover:bg-hover hover:text-ink',
              )}
            >
              {name}
            </button>
          ))}
        </div>
      ) : null}
      <div className="scroll-thin min-h-0 flex-1 overflow-auto bg-surface p-3">
        <table className="w-full border-collapse text-[0.72rem]">
          <tbody>
            {rows.map((row, rowIndex) => (
              <tr key={rowIndex} className={rowIndex === 0 ? 'bg-surface-2 font-medium' : ''}>
                {(row as unknown[]).map((cell, cellIndex) => (
                  <td
                    key={cellIndex}
                    className="max-w-[18rem] truncate border border-line px-2 py-1 text-ink-2"
                    title={String(cell ?? '')}
                  >
                    {String(cell ?? '')}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
        {rows.length >= 200 ? (
          <div className="mt-2 text-[0.66rem] text-ink-3">只显示前 200 行 × 40 列；完整内容请下载后用 WPS/Excel 打开。</div>
        ) : null}
      </div>
    </div>
  );
}
