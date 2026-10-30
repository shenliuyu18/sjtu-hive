import { useEffect, useState } from 'react';
import { Copy, Minus, PanelLeft, Search, Square, X } from 'lucide-react';
import { cn } from '@/lib/cn';
import {
  isWindowMaximised,
  minimizeWindow,
  quitApp,
  toggleMaximizeWindow,
} from '@/lib/bridge';
import { viewLabel } from '@/lib/nav';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';
import { Kbd } from '@/components/ui/Misc';
import { Tooltip } from '@/components/ui/Controls';

export function TitleBar() {
  const view = useApp((state) => state.view);
  const toggleSidebar = useApp((state) => state.toggleSidebar);
  const setCommandOpen = useApp((state) => state.setCommandOpen);
  const info = useSettings((state) => state.info);
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    const refresh = () => void isWindowMaximised().then(setMaximized);
    refresh();
    window.addEventListener('resize', refresh);
    return () => window.removeEventListener('resize', refresh);
  }, []);

  return (
    <header className="drag relative z-40 flex h-[var(--titlebar-h)] shrink-0 items-center border-b border-line bg-surface/95 backdrop-blur">
      <div className="flex h-full min-w-0 items-center gap-1 pl-2">
        <Tooltip content="收起/展开侧栏 (Ctrl+B)" side="bottom">
          <button
            type="button"
            aria-label="切换侧栏"
            onClick={toggleSidebar}
            className="no-drag grid size-8 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink"
          >
            <PanelLeft className="size-4" strokeWidth={1.8} />
          </button>
        </Tooltip>
        <div className="ml-1 flex min-w-0 items-center gap-2">
          <span className="truncate text-xs font-medium text-ink-2">{viewLabel(view)}</span>
          <span className="text-ink-3">/</span>
          <span className="truncate text-xs text-ink-3">SJTU Hive</span>
        </div>
      </div>

      <div
        className="drag h-full flex-1"
        onDoubleClick={() => {
          toggleMaximizeWindow();
          window.setTimeout(() => void isWindowMaximised().then(setMaximized), 180);
        }}
      />

      <div className="no-drag flex h-full items-center gap-1 pr-1">
        <button
          type="button"
          onClick={() => setCommandOpen(true)}
          className="mr-1 flex h-7 items-center gap-2 rounded-lg border border-line bg-surface-2 px-2.5 text-xs text-ink-3 transition-colors hover:border-line-strong hover:text-ink-2"
        >
          <Search className="size-3.5" />
          <span className="hidden sm:inline">搜索</span>
          <Kbd>Ctrl K</Kbd>
        </button>
        <div className="mx-1 h-4 w-px bg-line" />
        <button
          type="button"
          aria-label="最小化"
          onClick={minimizeWindow}
          className="grid h-full w-11 place-items-center text-ink-3 transition-colors hover:bg-hover hover:text-ink"
        >
          <Minus className="size-4" />
        </button>
        <button
          type="button"
          aria-label={maximized ? '还原' : '最大化'}
          onClick={() => {
            toggleMaximizeWindow();
            window.setTimeout(() => void isWindowMaximised().then(setMaximized), 180);
          }}
          className="grid h-full w-11 place-items-center text-ink-3 transition-colors hover:bg-hover hover:text-ink"
        >
          {maximized ? <Copy className="size-3.5" /> : <Square className="size-3" />}
        </button>
        <button
          type="button"
          aria-label="关闭"
          onClick={() => void quitApp()}
          className={cn(
            'grid h-full w-11 place-items-center text-ink-3 transition-colors hover:bg-[#e81123] hover:text-white',
          )}
        >
          <X className="size-4" />
        </button>
      </div>
      <div className="pointer-events-none absolute inset-x-0 bottom-0 h-px bg-line" />
      {info ? <span className="sr-only">{info.name}</span> : null}
    </header>
  );
}
