import { Download, LogOut, Search, Share2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/format';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';

type JsonObject = Record<string, unknown>;
type ShareState = { loggedIn: boolean };

function asArray(value: unknown): JsonObject[] {
  if (Array.isArray(value)) return value.filter((item): item is JsonObject => Boolean(item) && typeof item === 'object');
  if (!value || typeof value !== 'object') return [];
  const record = value as JsonObject;
  for (const key of ['items', 'list', 'courses', 'materials', 'data']) {
    if (Array.isArray(record[key])) return asArray(record[key]);
  }
  return typeof record.id !== 'undefined' ? [record] : [];
}

function text(value: unknown, ...keys: string[]): string {
  if (!value || typeof value !== 'object') return '';
  const record = value as JsonObject;
  for (const key of keys) {
    const field = record[key];
    if (typeof field === 'string' && field.trim()) return field.trim();
    if (typeof field === 'number') return String(field);
    if (field && typeof field === 'object') {
      const nested = text(field, 'name', 'title', 'label', 'file_name');
      if (nested) return nested;
    }
  }
  return '';
}

function number(value: unknown, ...keys: string[]): number | null {
  if (!value || typeof value !== 'object') return null;
  const record = value as JsonObject;
  for (const key of keys) {
    const field = record[key];
    if (typeof field === 'number' && Number.isFinite(field)) return field;
    if (typeof field === 'string' && field.trim() && Number.isFinite(Number(field))) return Number(field);
  }
  return null;
}

function idOf(value: unknown): number | null {
  return number(value, 'id', 'material_id', 'course_id');
}

function titleOf(value: unknown) {
  return text(value, 'name', 'file_name', 'title') || '未命名';
}

function subtitleOf(value: unknown) {
  return [text(value, 'code'), text(value, 'course_name', 'course'), text(value, 'teacher_name')]
    .filter(Boolean)
    .join(' · ');
}

function materialMeta(value: unknown) {
  return [
    text(value, 'material_type', 'material_type_name', 'type'),
    text(value, 'course_name', 'course'),
    number(value, 'points') !== null ? `${number(value, 'points')} 积分` : '',
    text(value, 'created_at') ? formatDate(text(value, 'created_at'), 'yyyy-MM-dd') : '',
  ]
    .filter(Boolean)
    .join(' · ');
}

function downloadURL(value: unknown): string {
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object') {
    return text(value, 'url', 'download_url', 'link', 'file_url');
  }
  return '';
}

export function SharePage() {
  const [state, setState] = useState<ShareState | null>(null);
  const [loginBusy, setLoginBusy] = useState(false);
  const [banners, setBanners] = useState<JsonObject[]>([]);
  const [keyword, setKeyword] = useState('');
  const [courses, setCourses] = useState<JsonObject[]>([]);
  const [selectedCourse, setSelectedCourse] = useState<JsonObject | null>(null);
  const [materials, setMaterials] = useState<JsonObject[]>([]);
  const [selectedMaterial, setSelectedMaterial] = useState<JsonObject | null>(null);
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadBanners = useCallback(async () => {
    try {
      setBanners(asArray(await call<unknown>('share.banners')));
    } catch {
      setBanners([]);
    }
  }, []);

  useEffect(() => {
    void call<ShareState>('share.state')
      .then((value) => {
        setState(value);
        if (value?.loggedIn) void loadBanners();
      })
      .catch(() => setState({ loggedIn: false }));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const login = async () => {
    setLoginBusy(true);
    const toastId = toast.loading('请在打开的 Edge 窗口中完成 jAccount 登录…');
    try {
      const next = await call<ShareState>('share.login');
      setState(next);
      toast.success('已登录传承交大', { id: toastId });
      void loadBanners();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '登录失败', { id: toastId });
    } finally {
      setLoginBusy(false);
    }
  };

  const logout = async () => {
    try {
      setState(await call<ShareState>('share.logout'));
      setCourses([]);
      setMaterials([]);
      setSelectedCourse(null);
      setSelectedMaterial(null);
      toast.success('已退出传承交大');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '退出失败');
    }
  };

  const search = async () => {
    if (!keyword.trim()) return;
    setLoading(true);
    setSelectedCourse(null);
    setMaterials([]);
    setSelectedMaterial(null);
    try {
      setCourses(asArray(await call<unknown>('share.searchCourses', keyword.trim())));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '搜索课程失败');
    } finally {
      setLoading(false);
    }
  };

  const openCourse = async (course: JsonObject) => {
    const id = idOf(course);
    if (id === null) return;
    setSelectedCourse(course);
    setSelectedMaterial(null);
    setLoading(true);
    try {
      setMaterials(asArray(await call<unknown>('share.materials', id)));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取资料失败');
    } finally {
      setLoading(false);
    }
  };

  const openMaterial = async (material: JsonObject) => {
    const id = idOf(material);
    if (id === null) return;
    setDetailLoading(true);
    try {
      setSelectedMaterial(asArray(await call<unknown>('share.material', id))[0] ?? material);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取资料详情失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const download = async () => {
    const id = selectedMaterial ? idOf(selectedMaterial) : null;
    if (id === null) return;
    try {
      const result = await call<unknown>('share.download', id);
      const url = downloadURL(result);
      if (!url) {
        toast.error('没有获取到下载链接');
        return;
      }
      await call('app.openExternal', url);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '下载失败');
    }
  };

  if (!state) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在读取传承交大…
      </div>
    );
  }

  if (!state.loggedIn) {
    return (
      <div className="flex h-full items-center justify-center p-6">
        <Card className="w-full max-w-md p-6">
          <div className="mb-1 flex items-center gap-2 text-sm font-semibold text-ink">
            <Share2 className="size-4 text-accent" /> 登录传承交大
          </div>
          <p className="mb-5 text-[0.7rem] leading-relaxed text-ink-3">
            使用 jAccount 登录 share.dyweb.sjtu.cn。点击登录后会打开独立 Edge 窗口，完成登录后应用会保存站点令牌。
          </p>
          <Button className="w-full" onClick={() => void login()} disabled={loginBusy}>
            {loginBusy ? <Spinner /> : null}
            {loginBusy ? '等待 jAccount 登录…' : '使用 jAccount 登录'}
          </Button>
        </Card>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        eyebrow="SJTU SHARE"
        title="传承交大"
        description="课程资料检索与只读浏览"
        actions={
          <Button size="sm" variant="ghost" onClick={() => void logout()}>
            <LogOut className="size-3.5" /> 退出
          </Button>
        }
      />
      <div className="flex min-h-0 flex-1 gap-3 p-4 pt-0">
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          {banners.length ? (
            <div className="flex gap-2 overflow-x-auto">
              {banners.slice(0, 3).map((banner, index) => (
                <div
                  key={String(banner.id ?? index)}
                  className="min-w-[16rem] rounded-xl border border-line bg-surface px-4 py-3"
                >
                  <div className="text-xs font-medium text-ink">{text(banner, 'title', 'name') || '公告'}</div>
                  <div className="mt-1 line-clamp-2 text-[0.66rem] leading-relaxed text-ink-3">
                    {text(banner, 'content', 'description', 'body')}
                  </div>
                </div>
              ))}
            </div>
          ) : null}
          <Card className="p-3">
            <div className="flex items-center gap-2">
              <Input
                value={keyword}
                onChange={(event) => setKeyword(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') void search();
                }}
                placeholder="搜索课程名或课程代码"
                className="flex-1"
              />
              <Button size="sm" variant="secondary" onClick={() => void search()}>
                <Search className="size-3.5" /> 搜索
              </Button>
            </div>
          </Card>
          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto pr-1">
            {loading ? (
              <div className="flex h-32 items-center justify-center gap-2 text-xs text-ink-3">
                <Spinner /> 正在读取资料…
              </div>
            ) : selectedCourse ? (
              <div className="space-y-2">
                <div className="flex items-center justify-between gap-2">
                  <div className="min-w-0 text-[0.68rem] font-medium text-ink">
                    <span className="truncate">{titleOf(selectedCourse)}</span>
                    <span className="text-ink-3"> · {materials.length} 份资料</span>
                  </div>
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      setSelectedCourse(null);
                      setSelectedMaterial(null);
                      setMaterials([]);
                    }}
                  >
                    返回搜索结果
                  </Button>
                </div>
                {materials.length ? materials.map((material, index) => (
                  <button
                    type="button"
                    key={String(material.id ?? index)}
                    onClick={() => void openMaterial(material)}
                    className={cn(
                      'w-full rounded-xl border border-line bg-surface px-4 py-3 text-left transition-colors hover:border-line-strong',
                      idOf(selectedMaterial) === idOf(material) && 'border-accent/50 bg-accent/5',
                    )}
                  >
                    <div className="truncate text-sm font-medium text-ink">{titleOf(material)}</div>
                    <div className="mt-0.5 truncate text-[0.64rem] text-ink-3">{materialMeta(material)}</div>
                  </button>
                )) : (
                  <EmptyState icon={Share2} title="暂无资料" description="这门课程下还没有共享资料。" />
                )}
              </div>
            ) : courses.length ? (
              <div className="space-y-2">
                {courses.map((course, index) => (
                  <button
                    type="button"
                    key={String(course.id ?? index)}
                    onClick={() => void openCourse(course)}
                    className="w-full rounded-xl border border-line bg-surface px-4 py-3 text-left transition-colors hover:border-line-strong"
                  >
                    <div className="truncate text-sm font-semibold text-ink">{titleOf(course)}</div>
                    <div className="mt-0.5 truncate text-[0.66rem] text-ink-3">
                      {subtitleOf(course) || '课程'}
                    </div>
                  </button>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={Share2}
                title="搜索课程开始"
                description="找到课程后即可浏览该课程下的共享资料。"
              />
            )}
          </div>
        </div>

        <div className="scroll-thin w-[24rem] shrink-0 overflow-y-auto rounded-xl border border-line bg-surface-2 p-4">
          {detailLoading ? (
            <div className="flex h-40 items-center justify-center gap-2 text-xs text-ink-3">
              <Spinner /> 正在读取资料详情…
            </div>
          ) : selectedMaterial ? (
            <>
              <div className="text-base font-semibold text-ink">{titleOf(selectedMaterial)}</div>
              <div className="mt-1 text-[0.66rem] text-ink-3">{materialMeta(selectedMaterial)}</div>
              <div className="mt-3 whitespace-pre-wrap text-[0.7rem] leading-relaxed text-ink-2">
                {text(selectedMaterial, 'description', 'content') || '暂无描述。'}
              </div>
              <div className="mt-4 flex flex-wrap gap-2">
                <Button size="sm" onClick={() => void download()}>
                  <Download className="size-3.5" /> 下载 / 打开
                </Button>
                {number(selectedMaterial, 'points') !== null ? (
                  <Badge variant="warning">{number(selectedMaterial, 'points')} 积分</Badge>
                ) : null}
              </div>
            </>
          ) : (
            <EmptyState icon={Share2} title="选择一份资料" description="详情与下载入口会显示在这里。" />
          )}
        </div>
      </div>
    </div>
  );
}
