import type { LucideIcon } from 'lucide-react';
import { ArrowRight, Settings2 } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { Card, PageHeader } from '@/components/ui/Misc';
import { useApp } from '@/stores/app';

export function Placeholder({
  title,
  description,
  icon: Icon,
  features,
}: {
  title: string;
  description: string;
  icon: LucideIcon;
  features: string[];
}) {
  const navigate = useApp((state) => state.navigate);
  return (
    <div className="scroll-thin h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl">
        <PageHeader
          title={title}
          description={description}
          actions={
            <Button variant="secondary" onClick={() => navigate('settings')}>
              <Settings2 className="size-4" /> 设置
            </Button>
          }
        />
        <div className="px-6 pb-10">
          <Card className="overflow-hidden">
            <div className="flex items-center gap-3 border-b border-line bg-surface-2 px-5 py-4">
              <div className="grid size-10 place-items-center rounded-xl bg-accent-soft text-accent">
                <Icon className="size-5" />
              </div>
              <div>
                <div className="text-sm font-semibold text-ink">模块正在接入</div>
                <div className="mt-0.5 text-xs text-ink-3">
                  界面框架已就位，后端与交互会在这个版本内补齐。
                </div>
              </div>
            </div>
            <ul className="grid gap-2 p-5 sm:grid-cols-2">
              {features.map((feature) => (
                <li
                  key={feature}
                  className="flex items-center gap-2 rounded-lg border border-line bg-surface-2 px-3 py-2 text-xs text-ink-2"
                >
                  <ArrowRight className="size-3.5 shrink-0 text-accent" />
                  {feature}
                </li>
              ))}
            </ul>
          </Card>
        </div>
      </div>
    </div>
  );
}
