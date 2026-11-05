import { mountMsDoc, parseMsDocToHtml } from '@file-viewer/doc';
import { loadPptViewer, type PptViewerRuntime } from '@file-viewer/ppt';
import * as XLSX from 'xlsx';

let runtimePromise: Promise<PptViewerRuntime> | null = null;
function pptRuntime(): Promise<PptViewerRuntime> {
  runtimePromise ??= loadPptViewer({ worker: false });
  return runtimePromise;
}

function fit(host: HTMLElement) {
  const apply = () => {
    const width = host.scrollWidth || host.clientWidth;
    const available = document.documentElement.clientWidth - 16;
    host.style.zoom = width > 0 ? String(Math.min(1, available / (width + 0.5))) : '1';
  };
  requestAnimationFrame(apply);
  window.addEventListener('resize', apply);
}

function renderSheet(host: HTMLElement, buffer: ArrayBuffer) {
  const workbook = XLSX.read(new Uint8Array(buffer), { type: 'array' });
  const name = workbook.SheetNames[0];
  if (!name) throw new Error('工作簿为空');
  const rows = XLSX.utils
    .sheet_to_json<unknown[]>(workbook.Sheets[name], { header: 1, defval: '', blankrows: false })
    .slice(0, 500);
  const table = document.createElement('table');
  table.className = 'xls';
  rows.forEach((row, rowIndex) => {
    const tr = document.createElement('tr');
    (row as unknown[]).slice(0, 60).forEach((cell) => {
      const td = document.createElement(rowIndex === 0 ? 'th' : 'td');
      td.textContent = String(cell ?? '');
      tr.appendChild(td);
    });
    table.appendChild(tr);
  });
  const wrap = document.createElement('div');
  wrap.className = 'sheet';
  wrap.appendChild(table);
  host.replaceChildren(wrap);
}

export async function render(host: HTMLElement, kind: string, buffer: ArrayBuffer) {
  host.replaceChildren();
  if (kind === 'doc') {
    const rendered = await parseMsDocToHtml(buffer);
    mountMsDoc(host, rendered);
    fit(host);
    return;
  }
  if (kind === 'ppt') {
    const runtime = await pptRuntime();
    const opened = (await runtime.open(buffer)) as unknown as { width?: number };
    const available = document.documentElement.clientWidth - 16;
    const scale = Math.min(1, available / (opened.width || 960));
    await runtime.mount(host, buffer, { scale, virtualize: true });
    return;
  }
  if (kind === 'xls') {
    renderSheet(host, buffer);
    return;
  }
  throw new Error(`不支持的旧格式：${kind}`);
}
