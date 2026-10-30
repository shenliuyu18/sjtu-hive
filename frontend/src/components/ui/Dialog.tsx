import * as DialogPrimitive from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export const Dialog = DialogPrimitive.Root;
export const DialogTrigger = DialogPrimitive.Trigger;
export const DialogClose = DialogPrimitive.Close;

export function DialogContent({
  title,
  description,
  children,
  footer,
  className,
  wide,
}: {
  title: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  className?: string;
  wide?: boolean;
}) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/35 backdrop-blur-[2px] animate-fade-in dark:bg-black/55" />
      <div className="pointer-events-none fixed inset-0 z-50 flex items-center justify-center p-4">
        <DialogPrimitive.Content
          aria-describedby={undefined}
          className={cn(
            'pointer-events-auto flex max-h-[85vh] w-[min(92vw,32rem)] flex-col overflow-hidden rounded-panel border border-line bg-surface shadow-[var(--shadow-pop)] animate-fade-in',
            wide && 'w-[min(94vw,52rem)]',
            className,
          )}
        >
        <div className="flex shrink-0 items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div className="min-w-0">
            <DialogPrimitive.Title className="text-[1.05rem] font-semibold text-ink">
              {title}
            </DialogPrimitive.Title>
            {description ? (
              <DialogPrimitive.Description className="mt-1 text-xs leading-relaxed text-ink-3">
                {description}
              </DialogPrimitive.Description>
            ) : null}
          </div>
          <DialogPrimitive.Close asChild>
            <button
              type="button"
              aria-label="关闭"
              className="grid size-8 shrink-0 place-items-center rounded-lg text-ink-3 transition-colors hover:bg-hover hover:text-ink"
            >
              <X className="size-4" />
            </button>
          </DialogPrimitive.Close>
        </div>
        <div className="scroll-thin min-h-0 flex-1 overflow-y-auto px-5 py-4">
          {children}
        </div>
        {footer ? (
          <div className="flex shrink-0 items-center justify-end gap-2 border-t border-line bg-surface-2 px-5 py-3">
            {footer}
          </div>
        ) : null}
        </DialogPrimitive.Content>
      </div>
    </DialogPrimitive.Portal>
  );
}
