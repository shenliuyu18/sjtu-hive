import { useEffect, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import rehypeKatex from 'rehype-katex';
import { call } from '@/lib/bridge';
import { useSettings } from '@/stores/settings';

function Mermaid({ code, dark }: { code: string; dark: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    void import('mermaid')
      .then(async (module) => {
        module.default.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'default' });
        const { svg } = await module.default.render(`mermaid-${Math.random().toString(36).slice(2)}`, code);
        if (alive && ref.current) {
          ref.current.innerHTML = svg;
          setError(null);
        }
      })
      .catch((cause) => {
        if (alive) setError(cause instanceof Error ? cause.message : String(cause));
      });
    return () => {
      alive = false;
    };
  }, [code, dark]);

  if (error) {
    return <pre className="mermaid-block text-xs text-danger">{code}</pre>;
  }
  return <div ref={ref} className="mermaid-block" />;
}

function resolveImage(src: string, assetBase?: string) {
  if (!src) return src;
  if (/^(https?:|data:|blob:)/i.test(src)) return src;
  if (!assetBase) return src;
  const base = assetBase.endsWith('/') ? assetBase : `${assetBase}/`;
  if (src.startsWith('/')) return `${base}${src.replace(/^\/+/, '')}`;
  return `${base}${src.replace(/^\.\//, '')}`;
}

export function MarkdownView({
  text,
  assetBase,
  className,
}: {
  text: string;
  assetBase?: string;
  className?: string;
}) {
  const theme = useSettings((state) => state.state.theme);
  const dark =
    theme === 'dark' ||
    (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  return (
    <div className={className ?? 'markdown-body selectable'}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, remarkMath]}
        rehypePlugins={[rehypeKatex]}
        components={{
          a({ href, children }) {
            return (
              <a
                href={href}
                onClick={(event) => {
                  event.preventDefault();
                  if (!href) return;
                  if (href.startsWith('#')) return;
                  try {
                    const target = new URL(href, safeBase(href));
                    if (target.protocol === 'http:' || target.protocol === 'https:') {
                      void call('app.openExternal', target.href);
                    }
                  } catch {
                    // ignore
                  }
                }}
              >
                {children}
              </a>
            );
          },
          img({ src, alt }) {
            return <img src={resolveImage(src ?? '', assetBase)} alt={alt ?? ''} loading="lazy" />;
          },
          code({ className, children }) {
            const language = /language-(\w+)/.exec(className ?? '')?.[1];
            if (language === 'mermaid') {
              return <Mermaid code={String(children)} dark={dark} />;
            }
            return <code className={className}>{children}</code>;
          },
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}

function safeBase(href: string) {
  if (/^https?:/i.test(href)) return href;
  return 'https://shuiyuan.sjtu.edu.cn';
}
