import * as DialogPrimitive from '@radix-ui/react-dialog';
import {
  ArrowRight,
  CornerDownLeft,
  Search,
  type LucideIcon,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { call } from '@/lib/bridge';
import { allNavItems } from '@/lib/nav';
import type { SearchGroup, SearchResponse, SearchRoute } from '@/lib/types';
import { useApp } from '@/stores/app';
import { cn } from '@/lib/cn';

interface CommandItem {
  id: string;
  label: string;
  hint?: string;
  icon: LucideIcon;
  group: string;
  route: SearchRoute;
}

export function CommandPalette() {
  const open = useApp((state) => state.commandOpen);
  const setOpen = useApp((state) => state.setCommandOpen);
  const navigate = useApp((state) => state.navigate);
  const [query, setQuery] = useState('');
  const [groups, setGroups] = useState<SearchGroup[]>([]);
  const [active, setActive] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setQuery('');
      setGroups([]);
      setActive(0);
      setError(null);
      window.setTimeout(() => inputRef.current?.focus(), 20);
    }
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const text = query.trim();
    if (!text) {
      setGroups([]);
      setLoading(false);
      return;
    }
    let alive = true;
    setLoading(true);
    const timer = window.setTimeout(() => {
      void call<SearchResponse>('search.query', text)
        .then((response) => {
          if (!alive) return;
          setGroups(response?.groups ?? []);
          setActive(0);
          setError(null);
        })
        .catch((cause) => {
          if (!alive) return;
          setGroups([]);
          setError(cause instanceof Error ? cause.message : String(cause));
        })
        .finally(() => {
          if (alive) setLoading(false);
        });
    }, 180);
    return () => {
      alive = false;
      window.clearTimeout(timer);
    };
  }, [open, query]);

  const commands = useMemo<CommandItem[]>(() => {
    const nav = allNavItems.map((item) => ({
      id: `nav:${item.id}`,
      label: item.label,
      hint: '前往',
      icon: item.icon,
      group: '导航',
      route: { view: item.id },
    }));
    const text = query.trim().toLowerCase();
    return text
      ? nav.filter((item) => item.label.toLowerCase().includes(text))
      : nav;
  }, [query]);

  const items = useMemo<CommandItem[]>(() => {
    const searchItems: CommandItem[] = [];
    for (const group of groups) {
      for (const item of group.items) {
        searchItems.push({
          id: `${group.kind}:${item.id}`,
          label: item.title,
          hint: item.subtitle,
          icon: Search,
          group: group.label,
          route: item.route,
        });
      }
    }
    return [...commands, ...searchItems];
  }, [commands, groups]);

  useEffect(() => {
    if (active >= items.length) setActive(Math.max(0, items.length - 1));
  }, [active, items.length]);

  const run = (item: CommandItem) => {
    setOpen(false);
    navigate(item.route.view, { ...item.route });
  };

  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((value) => Math.min(items.length - 1, value + 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((value) => Math.max(0, value - 1));
    } else if (event.key === 'Enter') {
      event.preventDefault();
      const item = items[active];
      if (item) run(item);
    }
  };

  let lastGroup = '';

  return (
    <DialogPrimitive.Root open={open} onOpenChange={setOpen}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/30 backdrop-blur-[2px] animate-fade-in dark:bg-black/50" />
        <div className="pointer-events-none fixed inset-0 z-50 flex items-start justify-center p-4 pt-[12vh]">
        <DialogPrimitive.Content
          aria-describedby={undefined}
          onKeyDown={onKeyDown}
          className="pointer-events-auto w-[min(92vw,42rem)] overflow-hidden rounded-xl border border-line bg-surface shadow-[var(--shadow-pop)] animate-fade-in"
        >
          <DialogPrimitive.Title className="sr-only">全局搜索</DialogPrimitive.Title>
          <div className="flex items-center gap-2.5 border-b border-line px-4 py-3">
            <Search className="size-4 shrink-0 text-ink-3" />
            <input
              ref={inputRef}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索课程资料、邮件、日程、TODO、文件…"
              className="h-6 min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-3"
            />
            {loading ? <span className="text-[0.66rem] text-ink-3">搜索中…</span> : null}
          </div>
          <div className="scroll-thin max-h-[52vh] overflow-y-auto p-2">
            {items.length === 0 ? (
              <div className="px-4 py-10 text-center text-xs text-ink-3">
                {error ? `搜索失败：${error}` : query.trim() ? '没有找到匹配结果' : '输入关键词开始搜索'}
              </div>
            ) : (
              items.map((item, index) => {
                const showGroup = item.group !== lastGroup;
                lastGroup = item.group;
                const Icon = item.icon;
                return (
                  <div key={item.id}>
                    {showGroup ? (
                      <div className="px-2 pb-1 pt-2 text-[0.62rem] font-semibold uppercase tracking-[0.12em] text-ink-3">
                        {item.group}
                      </div>
                    ) : null}
                    <button
                      type="button"
                      onMouseEnter={() => setActive(index)}
                      onClick={() => run(item)}
                      className={cn(
                        'flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors',
                        index === active ? 'bg-selected text-ink' : 'text-ink-2 hover:bg-hover',
                      )}
                    >
                      <Icon className={cn('size-4 shrink-0', index === active ? 'text-accent' : 'text-ink-3')} />
                      <span className="min-w-0 flex-1 truncate text-sm">{item.label}</span>
                      {item.hint ? (
                        <span className="max-w-48 truncate text-[0.66rem] text-ink-3">{item.hint}</span>
                      ) : null}
                      <ArrowRight className="size-3.5 shrink-0 text-ink-3" />
                    </button>
                  </div>
                );
              })
            )}
          </div>
          <div className="flex items-center justify-between border-t border-line bg-surface-2 px-4 py-2 text-[0.66rem] text-ink-3">
            <span>↑↓ 选择 · Enter 打开 · Esc 关闭</span>
            <span className="inline-flex items-center gap-1">
              <CornerDownLeft className="size-3" /> 全局搜索
            </span>
          </div>
        </DialogPrimitive.Content>
        </div>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}
