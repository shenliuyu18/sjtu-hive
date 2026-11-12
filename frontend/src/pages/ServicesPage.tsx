import { ArrowRight, Landmark, RefreshCw, Search, Star } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import { cn } from '@/lib/cn';
import { formatDateTime } from '@/lib/format';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { useApp } from '@/stores/app';

interface ServiceApp {
  id: string;
  code: string;
  name: string;
  nameEn: string;
  department: string;
  uri: string;
  categoryName: string;
  topCategoryName: string;
  iconId: number;
  ready: boolean;
  favorite: boolean;
}

interface ServiceCategory {
  id: number;
  title: string;
  count: number;
}

interface ServiceRecent {
  itemId: string;
  itemName: string;
  url: string;
  updateTimestamp: string;
}

interface ServiceCatalog {
  apps: ServiceApp[];
  categories: ServiceCategory[];
  favorites: string[];
  recent: ServiceRecent[];
}

interface ServiceProcess {
  id: string;
  name: string;
  appName: string;
  status: string;
  entry: string;
  stepName: string;
  percent: number;
  updateAt: string;
}

interface ProcessPage {
  items: ServiceProcess[];
  total: number;
}

type ProcessKind = 'todo' | 'completed' | 'cc';

const processTabs: { id: ProcessKind; label: string }[] = [
  { id: 'todo', label: '在办' },
  { id: 'completed', label: '已办' },
  { id: 'cc', label: '抄送' },
];

export function ServicesPage() {
  const navigate = useApp((state) => state.navigate);
  const [catalog, setCatalog] = useState<ServiceCatalog | null>(null);
  const [icons, setIcons] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState('');
  const [kind, setKind] = useState<ProcessKind>('todo');
  const [processes, setProcesses] = useState<ServiceProcess[]>([]);
  const [processTotal, setProcessTotal] = useState(0);
  const [processLoading, setProcessLoading] = useState(false);
  const [processError, setProcessError] = useState('');

  const loadCatalog = useCallback(async (force = false) => {
    setLoading(true);
    try {
      const value = await call<ServiceCatalog>('services.catalog', force);
      setCatalog(value);
      setError('');
      const ids = [...new Set(value.apps.map((app) => app.iconId).filter((id) => id > 0))];
      if (ids.length > 0) {
        void call<Record<string, string>>('services.icons', ids)
          .then((map) => setIcons(map ?? {}))
          .catch(() => undefined);
      }
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : '读取办事目录失败';
      setError(message);
    } finally {
      setLoading(false);
    }
  }, []);

  const loadProcesses = useCallback(async (next: ProcessKind, start = 0) => {
    setProcessLoading(true);
    try {
      const page = await call<ProcessPage>('services.processes', next, 20, start);
      setProcesses((current) => (start > 0 ? [...current, ...(page?.items ?? [])] : page?.items ?? []));
      setProcessTotal(page?.total ?? 0);
      setProcessError('');
    } catch (cause) {
      setProcessError(cause instanceof Error ? cause.message : '读取流程失败');
    } finally {
      setProcessLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadCatalog();
    void loadProcesses('todo');
  }, [loadCatalog, loadProcesses]);

  const switchKind = (next: ProcessKind) => {
    if (next === kind) return;
    setKind(next);
    setProcesses([]);
    void loadProcesses(next);
  };

  const openService = async (uri: string, name: string, id?: string) => {
    if (!uri) {
      toast.error('这个条目没有可打开的办理地址');
      return;
    }
    try {
      if (id) {
        void call('services.touch', id, name, uri).catch(() => undefined);
      }
      await call('services.open', uri, name);
      toast.success('已在应用内打开办理窗口');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '打开办理页失败');
    }
  };

  const toggleFavorite = async (app: ServiceApp) => {
    const next = !app.favorite;
    setCatalog((current) =>
      current
        ? {
            ...current,
            apps: current.apps.map((item) => (item.id === app.id ? { ...item, favorite: next } : item)),
            favorites: next
              ? [...current.favorites, app.id]
              : current.favorites.filter((id) => id !== app.id),
          }
        : current,
    );
    try {
      await call('services.favorite', app.id, next);
    } catch (cause) {
      setCatalog((current) =>
        current
          ? {
              ...current,
              apps: current.apps.map((item) => (item.id === app.id ? { ...item, favorite: !next } : item)),
            }
          : current,
      );
      toast.error(cause instanceof Error ? cause.message : '收藏失败');
    }
  };

  const apps = useMemo(() => {
    const keyword = query.trim().toLowerCase();
    let list = catalog?.apps ?? [];
    if (category) {
      list = list.filter(
        (app) => app.topCategoryName === category || app.categoryName === category,
      );
    }
    if (keyword) {
      list = list.filter((app) =>
        [app.name, app.nameEn, app.department, app.categoryName, app.topCategoryName]
          .join(' ')
          .toLowerCase()
          .includes(keyword),
      );
    }
    return [...list].sort((a, b) => {
      if (a.favorite !== b.favorite) return a.favorite ? -1 : 1;
      return a.name.localeCompare(b.name, 'zh-Hans-CN');
    });
  }, [catalog, category, query]);

  const recent = catalog?.recent ?? [];
  const categories = (catalog?.categories ?? []).filter((item) => item.count > 0);

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="办事大厅"
        description="交我办的应用目录与流程；点开即在应用内打开办理页，复用校园 jAccount 登录态。"
        eyebrow="Services"
        actions={
          <Button
            variant="secondary"
            onClick={() => {
              void loadCatalog(true);
              void loadProcesses(kind);
            }}
            disabled={loading}
          >
            <RefreshCw className={cn('size-4', loading && 'animate-spin')} /> 刷新
          </Button>
        }
      />
      {error ? (
        <div className="mx-6 mb-3 rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-[0.68rem] text-warning">
          {error}
          <button
            type="button"
            className="ml-2 underline underline-offset-2"
            onClick={() => navigate('settings')}
          >
            去账户与连接检查登录
          </button>
        </div>
      ) : null}
      <div className="scroll-thin min-h-0 flex-1 overflow-auto px-6 pb-6">
        {loading && !catalog ? (
          <div className="flex items-center justify-center gap-2 py-20 text-xs text-ink-3">
            <Spinner /> 读取办事目录…
          </div>
        ) : (
          <>
            {recent.length > 0 ? (
              <div className="mb-4 flex flex-wrap items-center gap-2">
                <span className="text-[0.66rem] text-ink-3">最近使用</span>
                {recent.slice(0, 6).map((item) => (
                  <button
                    key={`${item.itemId}-${item.updateTimestamp ?? ''}`}
                    type="button"
                    onClick={() => void openService(item.url, item.itemName, item.itemId)}
                    className="rounded-full border border-line bg-surface px-3 py-1 text-[0.68rem] text-ink-2 transition-colors hover:border-line-strong hover:text-ink"
                  >
                    {item.itemName}
                  </button>
                ))}
              </div>
            ) : null}
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <div className="relative w-64">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
                <Input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="搜索办事应用"
                  className="pl-8"
                />
              </div>
              <button
                type="button"
                onClick={() => setCategory('')}
                className={cn(
                  'rounded-full border px-3 py-1 text-[0.68rem] transition-colors',
                  category === ''
                    ? 'border-accent bg-accent-soft text-accent'
                    : 'border-line text-ink-2 hover:border-line-strong',
                )}
              >
                全部
              </button>
              {categories.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setCategory(category === item.title ? '' : item.title)}
                  className={cn(
                    'rounded-full border px-3 py-1 text-[0.68rem] transition-colors',
                    category === item.title
                      ? 'border-accent bg-accent-soft text-accent'
                      : 'border-line text-ink-2 hover:border-line-strong',
                  )}
                >
                  {item.title}
                  <span className="ml-1 text-ink-3">{item.count}</span>
                </button>
              ))}
            </div>
            {apps.length === 0 ? (
              <EmptyState
                compact
                icon={Landmark}
                title="没有匹配的办事应用"
                description="换个关键词或分类试试。"
              />
            ) : (
              <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 xl:grid-cols-4">
                {apps.map((app) => (
                  <button
                    key={app.id}
                    type="button"
                    onClick={() => void openService(app.uri, app.name, app.id)}
                    className="group flex items-start gap-2.5 rounded-xl border border-line bg-surface px-3 py-2.5 text-left transition-colors hover:border-line-strong hover:bg-hover"
                  >
                    <span className="grid size-9 shrink-0 place-items-center overflow-hidden rounded-lg bg-surface-2 text-xs font-semibold text-ink-2">
                      {icons[String(app.iconId)] ? (
                        <img src={icons[String(app.iconId)]} alt="" className="size-9 object-contain" />
                      ) : (
                        app.name.slice(0, 1)
                      )}
                    </span>
                    <span className="min-w-0 flex-1">
                      <span className="flex items-center gap-1">
                        <span className="truncate text-[0.74rem] font-medium text-ink">{app.name}</span>
                        {!app.ready ? <Badge variant="warning">暂停</Badge> : null}
                      </span>
                      <span className="mt-0.5 block truncate text-[0.62rem] text-ink-3">
                        {app.topCategoryName || app.categoryName || app.department || '校园服务'}
                      </span>
                    </span>
                    <span
                      role="button"
                      tabIndex={0}
                      aria-label={app.favorite ? '取消收藏' : '收藏'}
                      onClick={(event) => {
                        event.stopPropagation();
                        void toggleFavorite(app);
                      }}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter' || event.key === ' ') {
                          event.preventDefault();
                          event.stopPropagation();
                          void toggleFavorite(app);
                        }
                      }}
                      className="shrink-0 rounded p-1 text-ink-3 opacity-0 transition-opacity hover:text-accent group-hover:opacity-100"
                    >
                      <Star className={cn('size-3.5', app.favorite && 'fill-current text-accent')} />
                    </span>
                  </button>
                ))}
              </div>
            )}

            <Card className="mt-4 overflow-hidden">
              <div className="flex items-center justify-between border-b border-line px-3 py-2">
                <div className="flex items-center gap-1">
                  {processTabs.map((tab) => (
                    <button
                      key={tab.id}
                      type="button"
                      onClick={() => switchKind(tab.id)}
                      className={cn(
                        'rounded-md px-2.5 py-1 text-[0.7rem] transition-colors',
                        kind === tab.id ? 'bg-selected font-medium text-accent' : 'text-ink-2 hover:bg-hover',
                      )}
                    >
                      {tab.label}
                    </button>
                  ))}
                </div>
                <span className="text-[0.62rem] text-ink-3">
                  {processTotal > 0 ? `共 ${processTotal} 条` : '交我办流程'}
                </span>
              </div>
              {processLoading && processes.length === 0 ? (
                <div className="flex items-center justify-center gap-2 py-10 text-xs text-ink-3">
                  <Spinner /> 读取流程…
                </div>
              ) : processError ? (
                <EmptyState compact icon={Landmark} title="读取流程失败" description={processError} />
              ) : processes.length === 0 ? (
                <EmptyState
                  compact
                  icon={Landmark}
                  title={kind === 'todo' ? '没有待办流程' : kind === 'completed' ? '还没有已办记录' : '没有抄送给你的流程'}
                  description="办事数据来自交我办，打开应用办理后会自动出现在这里。"
                />
              ) : (
                <div>
                  {processes.map((process) => (
                    <button
                      key={process.id}
                      type="button"
                      onClick={() => void openService(process.entry, process.name)}
                      className="flex w-full items-center gap-3 border-b border-line px-3 py-2.5 text-left transition-colors hover:bg-hover last:border-b-0"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="flex items-center gap-2">
                          <span className="truncate text-[0.74rem] font-medium text-ink">{process.name}</span>
                          {process.appName ? <Badge>{process.appName}</Badge> : null}
                        </span>
                        <span className="mt-0.5 flex items-center gap-2 text-[0.62rem] text-ink-3">
                          {process.stepName ? <span>{process.stepName}</span> : null}
                          {process.percent > 0 ? <span>{Math.round(process.percent)}%</span> : null}
                          {process.updateAt ? <span>{formatDateTime(process.updateAt)}</span> : null}
                        </span>
                      </span>
                      <ArrowRight className="size-4 shrink-0 text-ink-3" />
                    </button>
                  ))}
                  {(kind === 'completed' || kind === 'cc') && processes.length < processTotal ? (
                    <div className="flex justify-center py-2">
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => void loadProcesses(kind, processes.length)}
                        disabled={processLoading}
                      >
                        {processLoading ? '加载中…' : '加载更多'}
                      </Button>
                    </div>
                  ) : null}
                </div>
              )}
            </Card>
          </>
        )}
      </div>
    </div>
  );
}
