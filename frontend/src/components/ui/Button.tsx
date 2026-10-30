import { Slot } from '@radix-ui/react-slot';
import type { ButtonHTMLAttributes } from 'react';
import { forwardRef } from 'react';
import { cn } from '@/lib/cn';

type Variant = 'primary' | 'secondary' | 'ghost' | 'danger' | 'soft';
type Size = 'sm' | 'md' | 'lg' | 'icon' | 'icon-sm';

const variants: Record<Variant, string> = {
  primary:
    'bg-accent text-white shadow-[0_1px_2px_rgba(16,24,40,.12)] hover:bg-accent-2 active:translate-y-px',
  secondary:
    'border border-line bg-surface text-ink shadow-[0_1px_2px_rgba(16,24,40,.04)] hover:bg-surface-2 active:translate-y-px',
  ghost: 'text-ink-2 hover:bg-hover hover:text-ink',
  danger: 'bg-danger text-white hover:bg-danger/90 active:translate-y-px',
  soft: 'bg-accent-soft text-accent hover:bg-accent/15',
};

const sizes: Record<Size, string> = {
  sm: 'h-8 gap-1.5 rounded-lg px-2.5 text-xs',
  md: 'h-9 gap-2 rounded-lg px-3.5 text-sm',
  lg: 'h-10 gap-2 rounded-xl px-4 text-sm',
  icon: 'size-9 rounded-lg',
  'icon-sm': 'size-8 rounded-lg',
};

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  asChild?: boolean;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = 'secondary', size = 'md', asChild, type, ...props }, ref) => {
    const Comp = asChild ? Slot : 'button';
    return (
      <Comp
        ref={ref}
        type={asChild ? undefined : (type ?? 'button')}
        className={cn(
          'inline-flex select-none items-center justify-center whitespace-nowrap font-medium transition-colors duration-150 disabled:pointer-events-none disabled:opacity-50',
          variants[variant],
          sizes[size],
          className,
        )}
        {...props}
      />
    );
  },
);
Button.displayName = 'Button';
