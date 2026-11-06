import { Download, ExternalLink, LogOut, Search, Share2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/format';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { PreviewPane } from '@/components/content/PreviewPane';
import type { DocumentContent } from '@/lib/models';

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

function flag(value: unknown, ...keys: string[]): boolean {
  if (!value || typeof value !== 'object') return false;
  const record = value as JsonObject;
  return keys.some((key) => record[key] === true);
}

function materialPoints(value: unknown): number {
  return number(value, 'points') ?? 0;
}

function materialPurchased(value: unknown): boolean {
  return flag(value, 'has_purchased', 'purchased') || flag(value, 'is_mine');
}

function fileNameOf(value: unknown): string {
  return text(value, 'file_name', 'name', 'title') || '传承交大资料';
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
  const [actionBusy, setActionBusy] = useState(false);
  const [preview, setPreview] = useState<DocumentContent | null>(null);

  const handleError = useCallback((cause: unknown, fallback: string) => {
    const message = cause instanceof Error ? cause.message : fallback;
    if (message.includes('登录已失效')) {
      setState({ loggedIn: false });
      setCourses([]);
      setMaterials([]);
      setSelectedCourse(null);
      setSelectedMaterial(null);
    }
    toast.error(message);
  }, []);

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
      handleError(cause, '搜索课程失败');
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
      handleError(cause, '读取资料失败');
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
      handleError(cause, '读取资料详情失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const downloadURLFor = async () => {
    const id = selectedMaterial ? idOf(selectedMaterial) : null;
    if (id === null) return '';
    const result = await call<unknown>('share.download', id);
    return downloadURL(result);
  };

  const purchase = async () => {
    const id = selectedMaterial ? idOf(selectedMaterial) : null;
    if (id === null || !selectedMaterial) return;
    const points = materialPoints(selectedMaterial);
    if (points > 0 && !window.confirm(`该资料需要 ${points} 积分兑换，确定继续吗？`)) return;
    setActionBusy(true);
    try {
      await call('share.purchase', id);
      const updated = { ...selectedMaterial, has_purchased: true };
      setSelectedMaterial(updated);
      setMaterials((current) => current.map((item) => (idOf(item) === id ? updated : item)));
      toast.success(points > 0 ? '兑换成功，可以打开或下载' : '已免费领取，可以打开或下载');
    } catch (cause) {
      handleError(cause, '领取资料失败');
    } finally {
      setActionBusy(false);
    }
  };

  const openMaterialFile = async () => {
    const id = selectedMaterial ? idOf(selectedMaterial) : null;
    if (id === null || !selectedMaterial) return;
    setActionBusy(true);
    try {
      const doc = await call<DocumentContent>('share.preview', id, fileNameOf(selectedMaterial));
      setPreview(doc);
    } catch (cause) {
      handleError(cause, '打开资料失败');
    } finally {
      setActionBusy(false);
    }
  };

  const openInBrowser = async () => {
    try {
      const url = await downloadURLFor();
      if (!url) {
        toast.error('没有获取到文件地址');
        return;
      }
      await call('app.openExternal', url);
    } catch (cause) {
      handleError(cause, '打开资料失败');
    }
  };

  const download = async () => {
    const id = selectedMaterial ? idOf(selectedMaterial) : null;
    if (id === null || !selectedMaterial) return;
    setActionBusy(true);
    try {
      const result = await call<{ saved?: boolean; path?: string }>(
        'share.save',
        id,
        fileNameOf(selectedMaterial),
      );
      if (result?.saved) {
        toast.success(result.path ? `已保存到 ${result.path}` : '资料已保存');
      }
    } catch (cause) {
      handleError(cause, '下载失败');
    } finally {
      setActionBusy(false);
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
    <>
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
              <div className="mt-4 flex flex-wrap items-center gap-2">
                {materialPurchased(selectedMaterial) ? (
                  <>
                    <Button size="sm" variant="secondary" onClick={() => void openMaterialFile()} disabled={actionBusy}>
                      <ExternalLink className="size-3.5" /> 打开
                    </Button>
                    <Button size="sm" onClick={() => void download()} disabled={actionBusy}>
                      <Download className="size-3.5" /> 下载
                    </Button>
                  </>
                ) : (
                  <Button size="sm" onClick={() => void purchase()} disabled={actionBusy}>
                    {actionBusy ? <Spinner /> : null}
                    {materialPoints(selectedMaterial) > 0
                      ? `${materialPoints(selectedMaterial)} 积分兑换`
                      : '免费领取'}
                  </Button>
                )}
                {number(selectedMaterial, 'points') !== null ? (
                  <Badge variant="warning">
                    {materialPurchased(selectedMaterial) ? '已领取 · ' : ''}
                    {number(selectedMaterial, 'points')} 积分
                  </Badge>
                ) : null}
              </div>
              {!materialPurchased(selectedMaterial) ? (
                <p className="mt-2 text-[0.62rem] leading-relaxed text-ink-3">
                  领取后即可打开或下载；0 积分资料免费领取。
                </p>
              ) : null}
            </>
          ) : (
            <EmptyState icon={Share2} title="选择一份资料" description="详情与下载入口会显示在这里。" />
          )}
        </div>
      </div>
      </div>
      {preview ? (
        <div className="fixed inset-0 z-[80] flex flex-col bg-surface">
          <div className="flex items-center justify-between gap-3 border-b border-line px-4 py-2">
            <div className="min-w-0 truncate text-sm font-semibold text-ink">{preview.title}</div>
            <div className="flex shrink-0 items-center gap-2">
              <Button size="sm" variant="ghost" onClick={() => void openInBrowser()}>
                <ExternalLink className="size-3.5" /> 在浏览器打开
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setPreview(null)}>
                关闭
              </Button>
            </div>
          </div>
          <div className="min-h-0 flex-1">
            <PreviewPane doc={preview} />
          </div>
        </div>
      ) : null}
    </>
  );
}
