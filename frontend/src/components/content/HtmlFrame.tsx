import DOMPurify from 'dompurify';
import { useEffect, useMemo, useRef } from 'react';
import { call } from '@/lib/bridge';
import { useSettings } from '@/stores/settings';

const LINK_SCRIPT = `document.addEventListener("click",function(e){var a=e.target.closest("a[href]");if(!a)return;var h=a.getAttribute("href");if(!h||h.charAt(0)==="#")return;e.preventDefault();try{parent.postMessage({type:"sjtu-open-url",url:new URL(h,document.baseURI).href},"*")}catch(_){}});`;
const SCRIPT_HASH = 'sha256-L4vj395ZWP19sjQhUmDDLZdHsZBvqE+3CqW9wjWhvB8=';

const baseCSS = `
*{box-sizing:border-box}
html,body{margin:0;padding:0;background:transparent}
body{padding:18px 20px 26px;font:14px/1.7 "Segoe UI Variable Text","Segoe UI","Microsoft YaHei UI","PingFang SC",sans-serif;color:#1f2328;word-break:break-word;overflow-wrap:anywhere}
html.dark{background:#15181e}
html.dark body{background:#15181e !important;color:#e8eaed !important}
a{color:#2563eb;text-decoration:none}
html.dark a{color:#7aa7ff}
a:hover{text-decoration:underline}
img{max-width:100%;height:auto}
table{border-collapse:collapse;max-width:100%}
th,td{vertical-align:top}
table[border]:not([border="0"]) th,table[border]:not([border="0"]) td{border:1px solid #d8dde5;padding:5px 8px}
html.dark table[border]:not([border="0"]) th,html.dark table[border]:not([border="0"]) td{border-color:#39414c}
html.dark table,html.dark thead,html.dark tbody,html.dark tr,html.dark td,html.dark th,html.dark div,html.dark p,html.dark span,html.dark center,html.dark section,html.dark article{background-color:transparent !important}
html.dark [style*="background"]:not(img):not(video){background-color:transparent !important}
html.dark [style*="color"]:not(a){color:inherit !important}
html.dark [style*="border"]{border-color:#39414c !important}
html.dark table[bgcolor],html.dark tr[bgcolor],html.dark td[bgcolor],html.dark th[bgcolor]{background-color:transparent !important}
html.dark th{background-color:#232830 !important}
html.dark font[color]{color:inherit !important}
html.dark a[style*="color"]{color:#7aa7ff !important}
pre{white-space:pre-wrap;background:#f5f7fa;border-radius:8px;padding:10px;overflow:auto}
html.dark pre{background:#232830}
blockquote{margin:8px 0;padding:2px 0 2px 12px;border-left:3px solid #d8dde5;color:#5b6472}
html.dark blockquote{border-color:#39414c;color:#b4bac4}
hr{border:none;border-top:1px solid #e4e7ec;margin:18px 0}
html.dark hr{border-color:#292e36}
.center,center{text-align:center}
`;

export function HtmlFrame({
  html,
  title,
  className,
}: {
  html: string;
  title?: string;
  className?: string;
}) {
  const frameRef = useRef<HTMLIFrameElement>(null);
  const theme = useSettings((state) => state.state.theme);
  const dark =
    theme === 'dark' ||
    (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);

  const documentHtml = useMemo(() => {
    const clean = DOMPurify.sanitize(html || '', {
      FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select', 'link', 'meta', 'base', 'html', 'head', 'body'],
      FORBID_ATTR: ['srcdoc', 'onerror', 'onload', 'onclick', 'onmouseover'],
      ADD_TAGS: ['style'],
      ADD_ATTR: ['target', 'rel'],
    });
    const csp = `default-src 'none'; img-src http: https: data:; style-src 'unsafe-inline'; font-src data:; media-src http: https:; script-src '${SCRIPT_HASH}'; base-uri 'none'; form-action 'none'`;
    const htmlClass = dark ? 'dark' : '';
    return `<!doctype html><html class="${htmlClass}"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${csp}"><style>${baseCSS}</style></head><body>${clean}<script>${LINK_SCRIPT}</script></body></html>`;
  }, [html, dark]);

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      if (event.source !== frameRef.current?.contentWindow) return;
      const payload = event.data as { type?: string; url?: string } | null;
      if (payload?.type !== 'sjtu-open-url' || typeof payload.url !== 'string') return;
      try {
        const target = new URL(payload.url);
        if (target.protocol === 'http:' || target.protocol === 'https:') {
          void call('app.openExternal', target.href);
        }
      } catch {
        // ignore malformed links
      }
    };
    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, []);

  return (
    <iframe
      ref={frameRef}
      title={title ?? 'HTML 内容'}
      sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox"
      referrerPolicy="no-referrer"
      srcDoc={documentHtml}
      className={className}
      style={{ width: '100%', height: '100%', border: 0, display: 'block', background: 'transparent' }}
    />
  );
}
