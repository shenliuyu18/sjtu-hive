import { Terminal as XTerm } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { useEffect, useRef } from 'react';
import { call, copyText, on, readClipboard } from '@/lib/bridge';
import { useSettings } from '@/stores/settings';

function themeFor(dark: boolean) {
  return dark
    ? {
        background: '#101215',
        foreground: '#e8eaed',
        cursor: '#8db2ff',
        selectionBackground: 'rgba(109,155,255,.28)',
        black: '#1c1f24',
        red: '#f87171',
        green: '#4ade80',
        yellow: '#fbbf24',
        blue: '#6d9bff',
        magenta: '#c084fc',
        cyan: '#5eead4',
        white: '#e8eaed',
        brightBlack: '#6b7280',
        brightRed: '#fca5a5',
        brightGreen: '#86efac',
        brightYellow: '#fde047',
        brightBlue: '#93b4ff',
        brightMagenta: '#d8b4fe',
        brightCyan: '#99f6e4',
        brightWhite: '#ffffff',
      }
    : {
        background: '#fafbfc',
        foreground: '#1f2328',
        cursor: '#2563eb',
        selectionBackground: 'rgba(37,99,235,.18)',
        black: '#1f2328',
        red: '#dc2626',
        green: '#15803d',
        yellow: '#b45309',
        blue: '#2563eb',
        magenta: '#7c3aed',
        cyan: '#0f766e',
        white: '#e5e7eb',
        brightBlack: '#6b7280',
        brightRed: '#ef4444',
        brightGreen: '#16a34a',
        brightYellow: '#d97706',
        brightBlue: '#3b82f6',
        brightMagenta: '#8b5cf6',
        brightCyan: '#14b8a6',
        brightWhite: '#ffffff',
      };
}

export function TerminalPanel({
  id,
  active,
  onExit,
}: {
  id: string;
  active: boolean;
  onExit?: (code: number) => void;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const terminalRef = useRef<XTerm | null>(null);
  const fitRef = useRef<FitAddon | null>(null);
  const theme = useSettings((state) => state.state.theme);
  const dark =
    theme === 'dark' ||
    (theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);

  useEffect(() => {
    if (!containerRef.current) return;
    const terminal = new XTerm({
      fontFamily: '"Cascadia Code", "Cascadia Mono", Consolas, monospace',
      fontSize: 13,
      lineHeight: 1.25,
      cursorBlink: true,
      scrollback: 5000,
      allowTransparency: false,
      theme: themeFor(dark),
      convertEol: false,
    });
    const fit = new FitAddon();
    terminal.loadAddon(fit);
    terminal.open(containerRef.current);
    terminalRef.current = terminal;
    fitRef.current = fit;
    terminal.focus();

    const resize = () => {
      try {
        fit.fit();
        void call('terminal.resize', id, terminal.cols, terminal.rows).catch(() => {});
      } catch {
        // container hidden
      }
    };
    resize();
    const observer = new ResizeObserver(() => resize());
    observer.observe(containerRef.current);
    window.setTimeout(resize, 90);

    const dataListener = terminal.onData((data) => {
      void call('terminal.write', id, data).catch(() => {});
    });
    const resizeListener = terminal.onResize((size) => {
      void call('terminal.resize', id, size.cols, size.rows).catch(() => {});
    });
    terminal.attachCustomKeyEventHandler((event) => {
      if (event.type !== 'keydown') return true;
      if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'c') {
        const selection = terminal.getSelection();
        if (selection) void copyText(selection);
        return false;
      }
      if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'v') {
        void readClipboard().then((text) => {
          if (text) void call('terminal.write', id, text).catch(() => {});
        });
        return false;
      }
      return true;
    });

    const offData = on('terminal.data', (payload) => {
      const value = payload as { id?: string; data?: string };
      if (value?.id !== id || !value.data) return;
      const binary = atob(value.data);
      const bytes = new Uint8Array(binary.length);
      for (let index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
      terminal.write(bytes);
    });
    const offExit = on('terminal.exit', (payload) => {
      const value = payload as { id?: string; code?: number };
      if (value?.id !== id) return;
      terminal.write(`\r\n\x1b[90m[进程已退出，退出码 ${value.code ?? -1}]\x1b[0m\r\n`);
      onExit?.(value.code ?? -1);
    });

    return () => {
      offData();
      offExit();
      observer.disconnect();
      dataListener.dispose();
      resizeListener.dispose();
      terminal.dispose();
      terminalRef.current = null;
      fitRef.current = null;
    };
  }, [id]);

  useEffect(() => {
    const terminal = terminalRef.current;
    if (!terminal) return;
    terminal.options.theme = themeFor(dark);
  }, [dark]);

  useEffect(() => {
    if (!active) return;
    const timer = window.setTimeout(() => {
      try {
        fitRef.current?.fit();
        terminalRef.current?.focus();
      } catch {
        // ignore
      }
    }, 60);
    return () => window.clearTimeout(timer);
  }, [active]);

  return <div ref={containerRef} className="h-full w-full overflow-hidden bg-[#fafbfc] p-2 dark:bg-[#101215]" />;
}
