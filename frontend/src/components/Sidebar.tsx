import { LockKeyhole, Sparkles } from 'lucide-react';
import { cn } from '@/lib/cn';
import { navGroups, settingsNav, type NavItem } from '@/lib/nav';
import type { AppView } from '@/lib/types';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';
import { Tooltip } from '@/components/ui/Controls';

function LogoMark({ compact }: { compact?: boolean }) {
  return (
    <div className="flex items-center gap-2.5 overflow-hidden">
      <img
        src="/logo.png"
        alt=""
        className="size-8 shrink-0 rounded-xl object-cover shadow-[0_2px_8px_rgba(0,0,0,.18)]"
      />
      {!compact ? (
        <div className="min-w-0">
          <div className="truncate text-[0.95rem] font-semibold tracking-[0.02em] text-ink">SJTU Hive</div>
          <div className="truncate text-[0.66rem] text-ink-3">校园桌面</div>
        </div>
      ) : null}
    </div>
  );
}

function NavButton({
  item,
  active,
  collapsed,
  badge,
  onClick,
}: {
  item: NavItem;
  active: boolean;
  collapsed: boolean;
  badge?: number;
  onClick: () => void;
}) {
  const Icon = item.icon;
  const button = (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'group relative flex h-9 w-full items-center gap-2.5 rounded-lg px-2.5 text-sm transition-colors',
        active
          ? 'bg-selected font-medium text-accent'
          : 'text-ink-2 hover:bg-hover hover:text-ink',
        collapsed && 'justify-center px-0',
      )}
    >
      {active ? (
        <span className="absolute left-0 top-1/2 h-4 w-0.5 -translate-y-1/2 rounded-full bg-accent" />
      ) : null}
      <Icon
        className={cn('size-4 shrink-0', active ? 'text-accent' : 'text-ink-3 group-hover:text-ink-2')}
        strokeWidth={1.8}
      />
      {!collapsed ? <span className="min-w-0 flex-1 truncate text-left">{item.label}</span> : null}
      {badge && badge > 0 ? (
        <span
          className={cn(
            'grid min-w-4 place-items-center rounded-full bg-accent px-1 text-[0.62rem] font-semibold leading-4 text-white',
            collapsed && 'absolute right-1 top-1 min-w-3 px-0.5 text-[0.55rem]',
          )}
        >
          {badge > 99 ? '99+' : badge}
        </span>
      ) : null}
    </button>
  );
  if (!collapsed) return button;
  return (
    <Tooltip content={item.label} side="right">
      {button}
    </Tooltip>
  );
}

export function Sidebar({ badges }: { badges: Partial<Record<AppView, number>> }) {
  const view = useApp((state) => state.view);
  const collapsed = useApp((state) => state.sidebarCollapsed);
  const navigate = useApp((state) => state.navigate);
  const settings = useSettings((state) => state.state);
  const info = useSettings((state) => state.info);

  const hidden = new Set(settings.navHidden);
  const experimentOK = (id: AppView) => id !== 'shuiyuan' || settings.experiments.shuiyuan;

  return (
    <aside
      className={cn(
        'flex h-full shrink-0 flex-col border-r border-line bg-surface transition-[width] duration-200 ease-out',
        collapsed ? 'w-[var(--sidebar-collapsed-w)]' : 'w-[var(--sidebar-w)]',
      )}
    >
      <div className={cn('flex h-14 items-center px-3', collapsed && 'justify-center px-0')}>
        <button
          type="button"
          onClick={() => navigate('dashboard')}
          className="rounded-xl text-left"
          aria-label="回到概览"
        >
          <LogoMark compact={collapsed} />
        </button>
      </div>

      <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-2 pb-3">
        {navGroups.map((group) => {
          const items = group.items.filter(
            (item) => !hidden.has(item.id) && experimentOK(item.id),
          );
          if (items.length === 0) return null;
          return (
            <div key={group.label} className="mb-3">
              {!collapsed ? (
                <div className="px-2 pb-1.5 pt-1 text-[0.62rem] font-semibold uppercase tracking-[0.14em] text-ink-3">
                  {group.label}
                </div>
              ) : (
                <div className="mx-2 mb-2 h-px bg-line first:hidden" />
              )}
              <div className="space-y-0.5">
                {items.map((item) => (
                  <NavButton
                    key={item.id}
                    item={item}
                    active={view === item.id}
                    collapsed={collapsed}
                    badge={badges[item.id]}
                    onClick={() => navigate(item.id)}
                  />
                ))}
              </div>
            </div>
          );
        })}
      </div>

      <div className="border-t border-line px-2 py-2">
        <NavButton
          item={settingsNav}
          active={view === settingsNav.id}
          collapsed={collapsed}
          onClick={() => navigate('settings')}
        />
        {!collapsed ? (
          <div className="mt-2 rounded-lg border border-line bg-surface-2 px-2.5 py-2">
            <div className="flex items-center gap-1.5 text-[0.66rem] font-medium text-ink-2">
              <LockKeyhole className="size-3 text-success" />
              本地优先
            </div>
            <div className="mt-1 text-[0.62rem] leading-relaxed text-ink-3">
              数据与登录凭据只保存在这台电脑
            </div>
          </div>
        ) : null}
        {!collapsed ? (
          <div className="mt-2 flex items-center justify-between px-2 text-[0.62rem] text-ink-3">
            <span className="inline-flex items-center gap-1">
              <Sparkles className="size-3" />
              v{info?.version ?? '0.1.0'}
            </span>
            <span>{settings.experiments.shuiyuan ? '水源已启用' : '水源已关闭'}</span>
          </div>
        ) : null}
      </div>
    </aside>
  );
}
