import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Badge({
  children,
  variant = 'neutral',
  className,
}: {
  children: ReactNode;
  variant?: 'neutral' | 'accent' | 'success' | 'warning' | 'danger';
  className?: string;
}) {
  const variants = {
    neutral: 'bg-surface-3 text-ink-3',
    accent: 'bg-accent-soft text-accent',
    success: 'bg-success/10 text-success',
    warning: 'bg-warning/10 text-warning',
    danger: 'bg-danger/10 text-danger',
  };
  return (
    <span
      className={cn(
        'inline-flex h-5 items-center rounded-md px-1.5 text-[0.68rem] font-medium leading-none',
        variants[variant],
        className,
      )}
    >
      {children}
    </span>
  );
}

export function Spinner({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        'inline-block size-4 shrink-0 animate-[spin_0.8s_linear_infinite] rounded-full border-2 border-line-strong border-t-accent',
        className,
      )}
    />
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-lg bg-surface-3', className)} />;
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  compact,
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  action?: ReactNode;
  compact?: boolean;
}) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center text-center',
        compact ? 'gap-2 px-6 py-8' : 'gap-3 px-8 py-14',
      )}
    >
      <div className="grid size-11 place-items-center rounded-xl border border-line bg-surface-2 text-ink-3">
        <Icon className="size-5" strokeWidth={1.7} />
      </div>
      <div>
        <div className="text-sm font-medium text-ink">{title}</div>
        {description ? (
          <div className="mx-auto mt-1 max-w-sm text-xs leading-relaxed text-ink-3">{description}</div>
        ) : null}
      </div>
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  );
}

export function Kbd({ children }: { children: ReactNode }) {
  return (
    <kbd className="inline-flex h-5 min-w-5 items-center justify-center rounded border border-line bg-surface-2 px-1 font-mono text-[0.68rem] text-ink-3 shadow-[0_1px_0_var(--line)]">
      {children}
    </kbd>
  );
}

export function PageHeader({
  title,
  description,
  actions,
  eyebrow,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
  eyebrow?: string;
}) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-3 px-6 pb-4 pt-5">
      <div className="min-w-0">
        {eyebrow ? (
          <div className="mb-1 text-[0.68rem] font-semibold uppercase tracking-[0.14em] text-ink-3">
            {eyebrow}
          </div>
        ) : null}
        <h1 className="truncate text-[1.35rem] font-semibold tracking-[-0.01em] text-ink">{title}</h1>
        {description ? (
          <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-ink-3">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function Card({
  children,
  className,
  onClick,
}: {
  children: ReactNode;
  className?: string;
  onClick?: () => void;
}) {
  return (
    <div
      onClick={onClick}
      className={cn(
        'rounded-card border border-line bg-surface shadow-[var(--shadow-card)]',
        onClick && 'cursor-pointer transition-colors hover:border-line-strong',
        className,
      )}
    >
      {children}
    </div>
  );
}

export function Divider({ className }: { className?: string }) {
  return <div className={cn('h-px bg-line', className)} />;
}
