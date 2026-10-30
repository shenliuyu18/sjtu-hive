import * as TabsPrimitive from '@radix-ui/react-tabs';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export const Tabs = TabsPrimitive.Root;
export const TabsContent = TabsPrimitive.Content;

export function TabsList({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <TabsPrimitive.List
      className={cn(
        'inline-flex h-9 items-center gap-1 rounded-lg bg-surface-3 p-1 text-ink-3',
        className,
      )}
    >
      {children}
    </TabsPrimitive.List>
  );
}

export function TabsTrigger({
  value,
  children,
  className,
}: {
  value: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <TabsPrimitive.Trigger
      value={value}
      className={cn(
        'inline-flex h-7 items-center gap-1.5 rounded-md px-3 text-xs font-medium text-ink-3 transition-colors data-[state=active]:bg-surface data-[state=active]:text-ink data-[state=active]:shadow-[0_1px_2px_rgba(16,24,40,.08)]',
        className,
      )}
    >
      {children}
    </TabsPrimitive.Trigger>
  );
}
