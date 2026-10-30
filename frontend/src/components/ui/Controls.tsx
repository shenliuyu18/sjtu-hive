import * as SwitchPrimitive from '@radix-ui/react-switch';
import * as CheckboxPrimitive from '@radix-ui/react-checkbox';
import * as TooltipPrimitive from '@radix-ui/react-tooltip';
import { Check } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export function Switch({
  checked,
  onCheckedChange,
  disabled,
  className,
}: {
  checked: boolean;
  onCheckedChange: (value: boolean) => void;
  disabled?: boolean;
  className?: string;
}) {
  return (
    <SwitchPrimitive.Root
      checked={checked}
      onCheckedChange={onCheckedChange}
      disabled={disabled}
      className={cn(
        'relative h-5 w-9 shrink-0 rounded-full border border-transparent bg-line-strong transition-colors data-[state=checked]:bg-accent disabled:opacity-40',
        className,
      )}
    >
      <SwitchPrimitive.Thumb className="block size-4 translate-x-0.5 rounded-full bg-white shadow-sm transition-transform data-[state=checked]:translate-x-[1.1rem]" />
    </SwitchPrimitive.Root>
  );
}

export function Checkbox({
  checked,
  onCheckedChange,
  disabled,
  label,
  className,
}: {
  checked: boolean;
  onCheckedChange: (value: boolean) => void;
  disabled?: boolean;
  label?: ReactNode;
  className?: string;
}) {
  return (
    <label className={cn('flex select-none items-center gap-2 text-sm text-ink-2', disabled && 'opacity-50', className)}>
      <CheckboxPrimitive.Root
        checked={checked}
        onCheckedChange={(value) => onCheckedChange(value === true)}
        disabled={disabled}
        className="grid size-4 shrink-0 place-items-center rounded border border-line-strong bg-surface transition-colors data-[state=checked]:border-accent data-[state=checked]:bg-accent"
      >
        <CheckboxPrimitive.Indicator>
          <Check className="size-3 text-white" strokeWidth={3} />
        </CheckboxPrimitive.Indicator>
      </CheckboxPrimitive.Root>
      {label}
    </label>
  );
}

export function TooltipProvider({ children }: { children: ReactNode }) {
  return (
    <TooltipPrimitive.Provider delayDuration={350} skipDelayDuration={100}>
      {children}
    </TooltipPrimitive.Provider>
  );
}

export function Tooltip({
  content,
  children,
  side = 'bottom',
}: {
  content: ReactNode;
  children: ReactNode;
  side?: 'top' | 'bottom' | 'left' | 'right';
}) {
  return (
    <TooltipPrimitive.Root>
      <TooltipPrimitive.Trigger asChild>{children}</TooltipPrimitive.Trigger>
      <TooltipPrimitive.Portal>
        <TooltipPrimitive.Content
          side={side}
          sideOffset={6}
          className="z-[60] max-w-64 rounded-md border border-line bg-surface px-2 py-1 text-xs text-ink shadow-[var(--shadow-pop)] animate-fade-in"
        >
          {content}
        </TooltipPrimitive.Content>
      </TooltipPrimitive.Portal>
    </TooltipPrimitive.Root>
  );
}
