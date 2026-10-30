import * as DropdownPrimitive from '@radix-ui/react-dropdown-menu';
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export const Dropdown = DropdownPrimitive.Root;
export const DropdownTrigger = DropdownPrimitive.Trigger;
export const DropdownSeparator = () => (
  <DropdownPrimitive.Separator className="my-1 h-px bg-line" />
);

export function DropdownContent({
  children,
  align = 'end',
  className,
}: {
  children: ReactNode;
  align?: 'start' | 'center' | 'end';
  className?: string;
}) {
  return (
    <DropdownPrimitive.Portal>
      <DropdownPrimitive.Content
        align={align}
        sideOffset={6}
        className={cn(
          'z-50 min-w-44 overflow-hidden rounded-xl border border-line bg-surface p-1 shadow-[var(--shadow-pop)] animate-scale-in',
          className,
        )}
      >
        {children}
      </DropdownPrimitive.Content>
    </DropdownPrimitive.Portal>
  );
}

export function DropdownItem({
  children,
  icon: Icon,
  danger,
  disabled,
  onSelect,
}: {
  children: ReactNode;
  icon?: LucideIcon;
  danger?: boolean;
  disabled?: boolean;
  onSelect?: (event: Event) => void;
}) {
  return (
    <DropdownPrimitive.Item
      disabled={disabled}
      onSelect={onSelect}
      className={cn(
        'flex cursor-default select-none items-center gap-2 rounded-lg px-2.5 py-1.5 text-sm outline-none transition-colors data-[highlighted]:bg-hover data-[disabled]:opacity-40',
        danger ? 'text-danger data-[highlighted]:bg-danger/10' : 'text-ink-2 data-[highlighted]:text-ink',
      )}
    >
      {Icon ? <Icon className="size-4 shrink-0" /> : null}
      <span className="min-w-0 flex-1 truncate">{children}</span>
    </DropdownPrimitive.Item>
  );
}

export function DropdownLabel({ children }: { children: ReactNode }) {
  return (
    <div className="px-2.5 pb-1 pt-1.5 text-[0.7rem] font-semibold uppercase tracking-[0.1em] text-ink-3">
      {children}
    </div>
  );
}
