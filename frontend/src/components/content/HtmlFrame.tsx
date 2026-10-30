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
html.dark body{color:#e8eaed}
a{color:#2563eb;text-decoration:none}
a:hover{text-decoration:underline}
img{max-width:100%;height:auto;border-radius:4px}
table{border-collapse:collapse;max-width:100%;font-size:13px}
th,td{border:1px solid #d8dde5;padding:5px 8px;text-align:left;vertical-align:top}
th{background:#f5f7fa}
html.dark th,html.dark td{border-color:#3a414b}
html.dark th{background:#232830}
pre{white-space:pre-wrap;background:#f5f7fa;border-radius:8px;padding:10px;overflow:auto}
html.dark pre{background:#232830}
blockquote{margin:8px 0;padding:2px 0 2px 12px;border-left:3px solid #d8dde5;color:#5b6472}
html.dark blockquote{border-color:#3a414b;color:#b4bac4}
hr{border:none;border-top:1px solid #e4e7ec;margin:18px 0}
html.dark hr{border-color:#292e36}
html.dark.invert-mail{background:#fff;filter:invert(1) hue-rotate(180deg)}
html.dark.invert-mail body{background:#fff;color:#1f2328}
html.dark.invert-mail img,html.dark.invert-mail video,html.dark.invert-mail svg{filter:invert(1) hue-rotate(180deg)}
.center,center{text-align:center}
`;

export function HtmlFrame({
  html,
  title,
  className,
  invertInDark = false,
}: {
  html: string;
  title?: string;
  className?: string;
  invertInDark?: boolean;
}) {
  const frameRef = useRef<HTMLIFrameElement>(null);
  const theme = useSettings((state) => state.state.theme);
  const dark =
    theme === 'dark' ||
    (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);

  const documentHtml = useMemo(() => {
    const clean = DOMPurify.sanitize(html || '', {
      FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select', 'link', 'meta', 'base'],
      FORBID_ATTR: ['srcdoc', 'onerror', 'onload', 'onclick', 'onmouseover'],
      ADD_TAGS: ['style'],
      ADD_ATTR: ['target', 'rel'],
    });
    const csp = `default-src 'none'; img-src http: https: data:; style-src 'unsafe-inline'; font-src data:; media-src http: https:; script-src '${SCRIPT_HASH}'; base-uri 'none'; form-action 'none'`;
    const htmlClass = `${dark ? 'dark' : ''}${dark && invertInDark ? ' invert-mail' : ''}`;
    return `<!doctype html><html class="${htmlClass}"><head><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${csp}"><style>${baseCSS}</style></head><body>${clean}<script>${LINK_SCRIPT}</script></body></html>`;
  }, [html, dark, invertInDark]);

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
