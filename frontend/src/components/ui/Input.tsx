import type { InputHTMLAttributes, SelectHTMLAttributes, TextareaHTMLAttributes } from 'react';
import { forwardRef } from 'react';
import { cn } from '@/lib/cn';

export const Input = forwardRef<HTMLInputElement, InputHTMLAttributes<HTMLInputElement>>(
  ({ className, ...props }, ref) => (
    <input
      ref={ref}
      className={cn(
        'h-9 w-full rounded-lg border border-line bg-surface px-3 text-sm text-ink shadow-[inset_0_1px_1px_rgba(16,24,40,.02)] transition-colors placeholder:text-ink-3 focus:border-accent/60 focus:outline-none disabled:opacity-60',
        className,
      )}
      {...props}
    />
  ),
);
Input.displayName = 'Input';

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) => (
    <textarea
      ref={ref}
      className={cn(
        'w-full resize-none rounded-lg border border-line bg-surface px-3 py-2 text-sm leading-relaxed text-ink transition-colors placeholder:text-ink-3 focus:border-accent/60 focus:outline-none disabled:opacity-60',
        className,
      )}
      {...props}
    />
  ),
);
Textarea.displayName = 'Textarea';

export function Select({
  className,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select
      className={cn(
        'h-9 rounded-lg border border-line bg-surface px-2.5 text-sm text-ink transition-colors focus:border-accent/60 focus:outline-none',
        className,
      )}
      {...props}
    >
      {children}
    </select>
  );
}
