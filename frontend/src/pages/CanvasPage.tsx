import {
  Archive,
  BookOpen,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  CloudDownload,
  Download,
  ExternalLink,
  File as FileIcon,
  FileImage,
  FileText,
  Folder,
  FolderOpen,
  FolderPlus,
  GraduationCap,
  Link2,
  Loader2,
  MoreHorizontal,
  RefreshCw,
  Search,
  Settings2,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { call, on } from '@/lib/bridge';
import type {
  CanvasCourse,
  CanvasItem,
  CanvasState,
  DocumentContent,
  WorkspaceProject,
} from '@/lib/models';
import { compareCanvasTerms, groupCanvasCourses, latestTermOf, submissionLabel } from '@/lib/canvas';
import { dayLabel, formatBytes, formatDate, relativeTime } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/Tabs';
import { PreviewPane } from '@/components/content/PreviewPane';

type Tab = 'files' | 'assignments' | 'pages';

function itemData(item: CanvasItem, key: string): unknown {
  return item.data?.[key];
}

function itemText(item: CanvasItem, key: string): string {
  const value = itemData(item, key);
  return typeof value === 'string' ? value : '';
}

function itemNumber(item: CanvasItem, key: string): number {
  const value = itemData(item, key);
  return typeof value === 'number' ? value : 0;
}

function extensionOf(name: string) {
  const index = name.lastIndexOf('.');
  return index >= 0 ? name.slice(index + 1).toLowerCase() : '';
}

function FileGlyph({ item, className }: { item: CanvasItem; className?: string }) {
  const ext = extensionOf(item.name);
  if (item.kind === 'folder') return <Folder className={cn('size-4 text-warning', className)} />;
  if (item.kind === 'module') return <BookOpen className={cn('size-4 text-accent', className)} />;
  if (item.kind === 'link') return <Link2 className={cn('size-4 text-ink-3', className)} />;
  if (item.kind === 'assignment') return <CheckCircle2 className={cn('size-4 text-success', className)} />;
  if (item.kind === 'page' || item.kind === 'discussion')
    return <FileText className={cn('size-4 text-ink-3', className)} />;
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(ext))
    return <FileImage className={cn('size-4 text-accent', className)} />;
  if (ext === 'pdf') return <FileText className={cn('size-4 text-danger', className)} />;
  return <FileIcon className={cn('size-4 text-ink-3', className)} />;
}

function sortTree(left: CanvasItem, right: CanvasItem) {
  const leftContainer = left.kind === 'folder' || left.kind === 'module';
  const rightContainer = right.kind === 'folder' || right.kind === 'module';
  if (leftContainer !== rightContainer) return leftContainer ? -1 : 1;
  return left.name.localeCompare(right.name, 'zh-CN');
}

function TreeRow({
  item,
  depth,
  allItems,
  expanded,
  onToggle,
  onOpen,
  selectedId,
}: {
  item: CanvasItem;
  depth: number;
  allItems: CanvasItem[];
  expanded: Set<string>;
  onToggle: (id: string) => void;
  onOpen: (item: CanvasItem) => void;
  selectedId: string | null;
}) {
  const isContainer = item.kind === 'folder' || item.kind === 'module';
  const children = allItems.filter((child) => child.parentId === item.id).sort(sortTree);
  const open = expanded.has(item.id);
  const size = itemNumber(item, 'size');
  return (
    <div>
      <button
        type="button"
        onClick={() => (isContainer ? onToggle(item.id) : onOpen(item))}
        className={cn(
          'group flex h-8 w-full items-center gap-1.5 rounded-md pr-2 text-left text-xs transition-colors',
          selectedId === item.id ? 'bg-selected text-accent' : 'text-ink-2 hover:bg-hover hover:text-ink',
        )}
        style={{ paddingLeft: `${depth * 14 + 6}px` }}
      >
        <span className="grid size-4 shrink-0 place-items-center text-ink-3">
          {isContainer ? (
            open ? (
              <ChevronDown className="size-3.5" />
            ) : (
              <ChevronRight className="size-3.5" />
            )
          ) : null}
        </span>
        <FileGlyph item={item} />
        <span className="min-w-0 flex-1 truncate">{item.name}</span>
        {size > 0 ? <span className="shrink-0 text-[0.62rem] text-ink-3">{formatBytes(size)}</span> : null}
      </button>
      {isContainer && open ? (
        <div>
          {children.map((child) => (
            <TreeRow
              key={child.id}
              item={child}
              depth={depth + 1}
              allItems={allItems}
              expanded={expanded}
              onToggle={onToggle}
              onOpen={onOpen}
              selectedId={selectedId}
            />
          ))}
          {children.length === 0 ? (
            <div
              className="py-1 text-[0.66rem] text-ink-3"
              style={{ paddingLeft: `${(depth + 1) * 14 + 30}px` }}
            >
              空目录
            </div>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

export function CanvasPage() {
  const navigate = useApp((state) => state.navigate);
  const params = useApp((state) => state.params);
  const canvasRouteRef = useRef<string | null>(null);
  const [state, setState] = useState<CanvasState | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedCourseId, setSelectedCourseId] = useState<number | null>(null);
  const [tab, setTab] = useState<Tab>('files');
  const [query, setQuery] = useState('');
  const [showArchived, setShowArchived] = useState(false);
  const [termOpen, setTermOpen] = useState<Record<string, boolean>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selectedItemId, setSelectedItemId] = useState<string | null>(null);
  const [preview, setPreview] = useState<DocumentContent | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);
  const [syncingAll, setSyncingAll] = useState(false);
  const [syncingCourse, setSyncingCourse] = useState<number | null>(null);
  const [exporting, setExporting] = useState(false);
  const [exportProgress, setExportProgress] = useState<{ done: number; total: number; current: string } | null>(null);
  const [tokenOpen, setTokenOpen] = useState(false);
  const [token, setToken] = useState('');
  const [projects, setProjects] = useState<WorkspaceProject[]>([]);
  const [projectPickerOpen, setProjectPickerOpen] = useState(false);
  const [adding, setAdding] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    void call<CanvasState>('canvas.state')
      .then((value) => {
        setState(value);
        setError(null);
      })
      .catch((cause) => setError(cause instanceof Error ? cause.message : String(cause)))
      .finally(() => setLoading(false));
    void call<{ projects: WorkspaceProject[] }>('workspace.state')
      .then((value) => setProjects(value?.projects ?? []))
      .catch(() => {});
  }, []);

  useEffect(() => {
    load();
    const offState = on('canvas.state', (value) => setState(value as CanvasState));
    const offExport = on('canvas.export.progress', (value) =>
      setExportProgress(value as { done: number; total: number; current: string }),
    );
    const offFinished = on('canvas.sync.finished', () => setSyncingAll(false));
    return () => {
      offState();
      offExport();
      offFinished();
    };
  }, [load]);

  useEffect(() => {
    if (!state) return;
    const active = state.courses.filter((course) => !course.archived);
    if (selectedCourseId && state.courses.some((course) => course.id === selectedCourseId)) return;
    const latest = latestTermOf(active);
    const course = active.find((item) => item.term === latest) ?? active[0];
    if (course) setSelectedCourseId(course.id);
  }, [state, selectedCourseId]);

  useEffect(() => {
    if (!state) return;
    const latest = latestTermOf(state.courses);
    if (latest) setTermOpen((value) => (value[latest] ? value : { ...value, [latest]: true }));
  }, [state?.courses.length]);

  const course = state?.courses.find((item) => item.id === selectedCourseId) ?? null;
  const courseItems = useMemo(
    () => (state?.items ?? []).filter((item) => item.courseId === selectedCourseId),
    [state?.items, selectedCourseId],
  );
  const selectedItem = courseItems.find((item) => item.id === selectedItemId) ?? null;

  const visibleCourses = useMemo(() => {
    if (!state) return [];
    return state.courses.filter((item) =>
      showArchived ? item.archived : !item.archived,
    );
  }, [state, showArchived]);

  const grouped = useMemo(() => groupCanvasCourses(visibleCourses), [visibleCourses]);
  const filteredGroups = useMemo(() => {
    const text = query.trim().toLowerCase();
    if (!text) return grouped;
    return grouped
      .map((group) => ({
        term: group.term,
        courses: group.courses.filter(
          (item) =>
            item.name.toLowerCase().includes(text) ||
            item.code.toLowerCase().includes(text) ||
            termMatches(item.term, text),
        ),
      }))
      .filter((group) => group.courses.length > 0);
  }, [grouped, query]);

  const fileItems = useMemo(() => {
    const items = courseItems.filter(
      (item) => item.kind === 'folder' || item.kind === 'module' || item.kind === 'file' || item.kind === 'link',
    );
    if (!query.trim()) return items;
    const text = query.trim().toLowerCase();
    return items.filter((item) => item.name.toLowerCase().includes(text));
  }, [courseItems, query]);
  const rootFiles = fileItems.filter((item) => item.parentId === null).sort(sortTree);
  useEffect(() => {
    if (!selectedCourseId) return;
    const containers = courseItems.filter(
      (item) => item.parentId === null && (item.kind === 'folder' || item.kind === 'module'),
    );
    setExpanded(new Set(containers.map((item) => item.id)));
  }, [selectedCourseId, courseItems.length]);
  const assignments = useMemo(
    () =>
      courseItems
        .filter((item) => item.kind === 'assignment')
        .sort((a, b) => (a.dueAt ?? '9999').localeCompare(b.dueAt ?? '9999')),
    [courseItems],
  );
  const pages = useMemo(
    () =>
      courseItems
        .filter((item) => item.kind === 'page' || item.kind === 'discussion')
        .sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')),
    [courseItems],
  );

  const openPreview = async (item: CanvasItem) => {
    if (item.kind === 'folder' || item.kind === 'module') {
      toggleExpanded(item.id);
      return;
    }
    if (item.kind === 'link') {
      const url = itemText(item, 'externalUrl') || itemText(item, 'htmlUrl');
      if (url) void call('app.openExternal', url);
      return;
    }
    setSelectedItemId(item.id);
    setPreview(null);
    setPreviewError(null);
    setPreviewLoading(true);
    try {
      const doc = await call<DocumentContent>('canvas.read', item.id);
      setPreview(doc);
    } catch (cause) {
      setPreviewError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setPreviewLoading(false);
    }
  };

  useEffect(() => {
    const raw = params.canvasSourceId;
    if (raw === undefined || raw === null || raw === '') return;
    const id = String(raw);
    if (canvasRouteRef.current === id || !state) return;
    const item = state.items.find((entry) => entry.id === id);
    if (!item) return;
    canvasRouteRef.current = id;
    setSelectedCourseId(item.courseId);
    setTab(
      item.kind === 'assignment'
        ? 'assignments'
        : item.kind === 'page' || item.kind === 'discussion'
          ? 'pages'
          : 'files',
    );
    navigate('canvas', {});
    void openPreview(item);
  }, [navigate, params, state, openPreview]);

  const toggleExpanded = (id: string) => {
    setExpanded((value) => {
      const next = new Set(value);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const syncAll = async () => {
    setSyncingAll(true);
    try {
      const value = await toast
        .promise(call<CanvasState>('canvas.syncCourses'), {
          loading: '正在同步全部课程，文件较多时可能需要几分钟…',
          success: 'Canvas 同步完成',
          error: (cause) => (cause instanceof Error ? cause.message : '同步失败'),
        })
        .unwrap();
      setState(value);
    } catch {
      // toast already shown
    } finally {
      setSyncingAll(false);
    }
  };

  const syncCourse = async (courseId: number) => {
    if (syncingCourse !== null) return;
    setSyncingCourse(courseId);
    try {
      const value = await toast
        .promise(call<CanvasState>('canvas.syncCourse', courseId), {
          loading: '正在同步课程资料…',
          success: '课程资料已更新',
          error: (cause) => (cause instanceof Error ? cause.message : '同步失败'),
        })
        .unwrap();
      setState(value);
    } catch {
      // toast already shown
    } finally {
      setSyncingCourse(null);
    }
  };

  const archiveCourse = async (course: CanvasCourse) => {
    try {
      const value = await call<CanvasState>('canvas.archiveCourse', course.id, !course.archived);
      setState(value);
      toast.success(course.archived ? '已恢复课程' : '已归档课程');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '操作失败');
    }
  };

  const exportCourse = async () => {
    if (!course || exporting) return;
    const dir = await call<string | null>('app.chooseDirectory', '选择课件导出目录');
    if (!dir) return;
    setExporting(true);
    setExportProgress({ done: 0, total: 0, current: '' });
    try {
      const result = await call<{ dir: string; exported: number; failed: string[] }>(
        'canvas.exportCourseFiles',
        [course.id],
        dir,
      );
      if (result.failed?.length) {
        toast.warning(`已导出 ${result.exported} 个文件，${result.failed.length} 个失败`, {
          description: result.failed.slice(0, 3).join('\n'),
        });
      } else {
        toast.success(`已导出 ${result.exported} 个文件`, { description: result.dir });
      }
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '导出失败');
    } finally {
      setExporting(false);
      setExportProgress(null);
    }
  };

  const connect = async () => {
    if (!token.trim()) return;
    try {
      const value = await toast
        .promise(call<CanvasState>('canvas.configure', token.trim()), {
          loading: '正在验证 Canvas Token…',
          success: 'Canvas 已连接',
          error: (cause) => (cause instanceof Error ? cause.message : '连接失败'),
        })
        .unwrap();
      setState(value);
      setToken('');
      setTokenOpen(false);
    } catch {
      // toast already shown
    }
  };

  const downloadSelected = () => {
    if (!selectedItem) return;
    void toast
      .promise(call<{ path: string }>('canvas.download', selectedItem.id), {
        loading: '正在下载…',
        success: (value) => `已下载到 ${value?.path ?? ''}`,
        error: (cause) => (cause instanceof Error ? cause.message : '下载失败'),
      })
      .unwrap()
      .then((value) => {
        if (value?.path) {
          toast.success('文件已缓存', {
            description: value.path,
            action: {
              label: '打开文件夹',
              onClick: () => void call('app.showInFolder', value.path),
            },
          });
        }
      })
      .catch(() => {});
  };

  const addSelectedToProject = async (project: WorkspaceProject) => {
    if (!selectedItem || adding) return;
    setAdding(true);
    try {
      const path = await call<string>('canvas.addToProject', selectedItem.id, project.path);
      setProjectPickerOpen(false);
      toast.success('已添加到项目', {
        description: path,
        action: {
          label: '打开文件夹',
          onClick: () => void call('app.showInFolder', path),
        },
      });
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '添加失败');
    } finally {
      setAdding(false);
    }
  };

  const openInCanvas = (item?: CanvasItem | null) => {
    const url = item ? itemText(item, 'htmlUrl') : '';
    if (url) {
      void call('app.openExternal', url);
      return;
    }
    if (course) void call('app.openExternal', `https://oc.sjtu.edu.cn/courses/${course.id}`);
  };

  const renderBody = () => {
    if (!course) {
      return (
        <EmptyState
          icon={GraduationCap}
          title="选择一门课程"
          description="从左侧课程列表选择课程，查看课件、作业与课程页面。"
        />
      );
    }
    if (course.error) {
      return (
        <div className="m-4 rounded-lg border border-warning/30 bg-warning/10 px-4 py-3 text-xs text-warning">
          上次同步失败：{course.error}
        </div>
      );
    }
    if (tab === 'files') {
      if (rootFiles.length === 0) {
        return (
          <EmptyState
            icon={FolderOpen}
            title={query.trim() ? '没有匹配的文件' : '还没有同步课程资料'}
            description={query.trim() ? '换个关键词试试。' : '首次同步会读取文件夹、模块与课件列表。'}
            action={
              !query.trim() ? (
                <Button size="sm" onClick={() => void syncCourse(course.id)} disabled={syncingCourse !== null}>
                  {syncingCourse === course.id ? <Loader2 className="size-3.5 animate-spin" /> : <RefreshCw className="size-3.5" />}
                  同步本课程
                </Button>
              ) : null
            }
          />
        );
      }
      return (
        <div className="scroll-thin h-full overflow-y-auto py-2">
          {query.trim() ? (
            fileItems.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => void openPreview(item)}
                className="flex h-8 w-full items-center gap-2 rounded-md px-2 text-left text-xs text-ink-2 hover:bg-hover hover:text-ink"
                style={{ paddingLeft: `${(item.parentId ? 2 : 1) * 14 + 6}px` }}
              >
                <FileGlyph item={item} />
                <span className="min-w-0 flex-1 truncate">{item.name}</span>
              </button>
            ))
          ) : (
            rootFiles.map((item) => (
              <TreeRow
                key={item.id}
                item={item}
                depth={0}
                allItems={fileItems}
                expanded={expanded}
                onToggle={toggleExpanded}
                onOpen={(value) => void openPreview(value)}
                selectedId={selectedItemId}
              />
            ))
          )}
        </div>
      );
    }
    if (tab === 'assignments') {
      if (assignments.length === 0) {
        return (
          <EmptyState
            icon={CheckCircle2}
            title="暂无作业"
            description="同步课程后，Canvas 作业与截止时间会出现在这里。"
          />
        );
      }
      return (
        <div className="scroll-thin h-full overflow-y-auto">
          {assignments.map((item) => {
            const submissionState = itemText(item, 'submissionState');
            const points = itemNumber(item, 'points');
            const done = submissionState === 'submitted' || submissionState === 'graded' || submissionState === 'pending_review';
            return (
              <button
                key={item.id}
                type="button"
                onClick={() => void openPreview(item)}
                className={cn(
                  'flex w-full items-start gap-3 border-b border-line px-4 py-3 text-left transition-colors hover:bg-hover',
                  selectedItemId === item.id && 'bg-selected/60',
                )}
              >
                <div className="mt-0.5">
                  {done ? (
                    <CheckCircle2 className="size-4 text-success" />
                  ) : (
                    <CheckCircle2 className="size-4 text-ink-3" />
                  )}
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="truncate text-xs font-medium text-ink">{item.name}</span>
                    <Badge variant={done ? 'success' : submissionState === 'unsubmitted' ? 'warning' : 'neutral'}>
                      {submissionLabel(submissionState)}
                    </Badge>
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-2 text-[0.66rem] text-ink-3">
                    {item.dueAt ? (
                      <span className={cn(!done && new Date(item.dueAt) < new Date() && 'text-danger')}>
                        截止 {formatDate(item.dueAt, 'M月d日 HH:mm')}
                      </span>
                    ) : (
                      <span>无截止时间</span>
                    )}
                    {points > 0 ? <span>满分 {points}</span> : null}
                  </div>
                </div>
                <ChevronRight className="mt-1 size-3.5 shrink-0 text-ink-3" />
              </button>
            );
          })}
        </div>
      );
    }
    if (pages.length === 0) {
      return (
        <EmptyState
          icon={FileText}
          title="暂无页面或讨论"
          description="课程页面、公告与讨论会出现在这里。"
        />
      );
    }
    return (
      <div className="scroll-thin h-full overflow-y-auto">
        {pages.map((item) => (
          <button
            key={item.id}
            type="button"
            onClick={() => void openPreview(item)}
            className={cn(
              'flex w-full items-center gap-3 border-b border-line px-4 py-3 text-left transition-colors hover:bg-hover',
              selectedItemId === item.id && 'bg-selected/60',
            )}
          >
            <FileGlyph item={item} />
            <div className="min-w-0 flex-1">
              <div className="truncate text-xs font-medium text-ink">{item.name}</div>
              <div className="mt-0.5 text-[0.66rem] text-ink-3">
                {item.kind === 'discussion'
                  ? itemData(item, 'isAnnouncement')
                    ? '公告'
                    : '讨论'
                  : '课程页面'}
              </div>
            </div>
            <ChevronRight className="size-3.5 shrink-0 text-ink-3" />
          </button>
        ))}
      </div>
    );
  };

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="资料"
        description="Canvas 课程资料、作业与课程页面。预览、下载或加入本地项目。"
        eyebrow="Canvas"
        actions={
          <>
            <Button variant="secondary" onClick={() => setTokenOpen(true)}>
              <Settings2 className="size-4" /> 连接
            </Button>
            <Button
              variant="primary"
              onClick={() => void syncAll()}
              disabled={!state?.connected || syncingAll}
            >
              {syncingAll ? <Loader2 className="size-4 animate-spin" /> : <RefreshCw className="size-4" />}
              同步全部
            </Button>
          </>
        }
      />
      {loading ? (
        <div className="flex flex-1 items-center justify-center gap-2 text-xs text-ink-3">
          <Spinner /> 正在读取 Canvas 数据…
        </div>
      ) : error ? (
        <div className="m-6 rounded-lg border border-danger/30 bg-danger/10 p-4 text-xs text-danger">{error}</div>
      ) : !state?.connected ? (
        <div className="flex flex-1 items-center justify-center p-6">
          <Card className="max-w-xl overflow-hidden">
            <div className="flex items-center gap-3 border-b border-line bg-surface-2 px-5 py-4">
              <div className="grid size-11 place-items-center rounded-xl bg-accent-soft text-accent">
                <GraduationCap className="size-5" />
              </div>
              <div>
                <div className="text-sm font-semibold text-ink">连接 Canvas</div>
                <div className="mt-0.5 text-xs text-ink-3">同步课程资料、作业与课程页面。</div>
              </div>
            </div>
            <div className="space-y-3 p-5 text-xs leading-relaxed text-ink-2">
              <p>在 Canvas 的「账户 → 设置 → 新建访问令牌」创建一个 Personal Access Token，然后粘贴到应用里。</p>
              <div className="rounded-lg border border-line bg-surface-2 p-3 text-[0.68rem] text-ink-3">
                <div>1. 打开 oc.sjtu.edu.cn，登录后进入账户设置。</div>
                <div>2. 新建访问令牌，复制生成的字符串。</div>
                <div>3. 粘贴到本应用；Token 会用 Windows DPAPI 加密保存在本机。</div>
              </div>
              <div className="flex items-center gap-2 pt-1">
                <Button variant="primary" onClick={() => setTokenOpen(true)}>
                  连接 Canvas
                </Button>
                <Button
                  variant="ghost"
                  onClick={() => void call('app.openExternal', 'https://oc.sjtu.edu.cn/profile/settings')}
                >
                  <ExternalLink className="size-3.5" /> 打开 Canvas 设置
                </Button>
              </div>
            </div>
          </Card>
        </div>
      ) : (
        <div className="flex min-h-0 flex-1">
          <aside className="flex w-[17rem] shrink-0 flex-col border-r border-line bg-surface">
            <div className="border-b border-line p-2">
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
                <Input
                  value={query}
                  onChange={(event) => setQuery(event.target.value)}
                  placeholder="搜索课程或资料"
                  className="h-8 pl-8 text-xs"
                />
              </div>
            </div>
            <div className="scroll-thin min-h-0 flex-1 overflow-y-auto p-2">
              {filteredGroups.length === 0 ? (
                <div className="px-3 py-8 text-center text-[0.68rem] text-ink-3">
                  {showArchived ? '没有归档课程' : '还没有同步到课程'}
                </div>
              ) : (
                filteredGroups.map((group) => {
                  const open = termOpen[group.term] ?? false;
                  return (
                    <div key={group.term} className="mb-1">
                      <button
                        type="button"
                        onClick={() => setTermOpen((value) => ({ ...value, [group.term]: !open }))}
                        className="flex h-7 w-full items-center gap-1.5 rounded-md px-2 text-left text-[0.66rem] font-semibold uppercase tracking-[0.08em] text-ink-3 hover:bg-hover"
                      >
                        {open ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />}
                        <span className="min-w-0 flex-1 truncate">{group.term}</span>
                        <span className="text-[0.62rem] font-normal text-ink-3">{group.courses.length}</span>
                      </button>
                      {open
                        ? group.courses.map((item) => {
                            const active = item.id === selectedCourseId;
                            return (
                              <button
                                key={item.id}
                                type="button"
                                onClick={() => {
                                  setSelectedCourseId(item.id);
                                  setSelectedItemId(null);
                                  setPreview(null);
                                  setTab('files');
                                }}
                                className={cn(
                                  'flex w-full items-start gap-2 rounded-md px-2 py-1.5 text-left transition-colors',
                                  active ? 'bg-selected text-accent' : 'text-ink-2 hover:bg-hover hover:text-ink',
                                )}
                              >
                                <span className="mt-1 size-1.5 shrink-0 rounded-full bg-current opacity-50" />
                                <span className="min-w-0 flex-1">
                                  <span className="block truncate text-xs font-medium">{item.name}</span>
                                  <span className="mt-0.5 block truncate text-[0.62rem] text-ink-3">
                                    {item.code || item.term}
                                  </span>
                                </span>
                                {item.error ? (
                                  <span className="mt-1 size-1.5 shrink-0 rounded-full bg-warning" title={item.error} />
                                ) : null}
                                {syncingCourse === item.id ? (
                                  <Loader2 className="mt-1 size-3 shrink-0 animate-spin text-accent" />
                                ) : null}
                              </button>
                            );
                          })
                        : null}
                    </div>
                  );
                })
              )}
            </div>
            <label className="flex shrink-0 items-center gap-2 border-t border-line px-3 py-2 text-[0.66rem] text-ink-3">
              <input
                type="checkbox"
                checked={showArchived}
                onChange={(event) => setShowArchived(event.target.checked)}
                className="accent-[var(--accent)]"
              />
              显示归档课程
            </label>
          </aside>

          <section className="flex min-w-0 flex-1 flex-col bg-bg">
            {course ? (
              <div className="flex shrink-0 flex-wrap items-center justify-between gap-2 border-b border-line bg-surface px-4 py-3">
                <div className="min-w-0">
                  <div className="flex items-center gap-2">
                    <h2 className="truncate text-sm font-semibold text-ink">{course.name}</h2>
                    {course.archived ? <Badge variant="warning">已归档</Badge> : null}
                    {syncingCourse === course.id ? <Spinner className="size-3" /> : null}
                  </div>
                  <div className="mt-0.5 flex flex-wrap items-center gap-2 text-[0.66rem] text-ink-3">
                    <span>{course.term}</span>
                    {course.code ? <span>· {course.code}</span> : null}
                    {course.syncedAt ? <span>· 同步于 {relativeTime(course.syncedAt)}</span> : null}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-1.5">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => void syncCourse(course.id)}
                    disabled={syncingCourse !== null}
                  >
                    <RefreshCw className="size-3.5" /> 同步
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => void exportCourse()} disabled={exporting}>
                    {exporting ? <Loader2 className="size-3.5 animate-spin" /> : <Download className="size-3.5" />}
                    导出
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => void archiveCourse(course)}>
                    <Archive className="size-3.5" /> {course.archived ? '恢复' : '归档'}
                  </Button>
                  <Button size="sm" variant="ghost" onClick={() => openInCanvas(null)}>
                    <ExternalLink className="size-3.5" /> 打开
                  </Button>
                </div>
              </div>
            ) : null}
            {exportProgress ? (
              <div className="border-b border-line bg-accent-soft px-4 py-2 text-[0.66rem] text-accent">
                正在导出 {exportProgress.done}/{exportProgress.total}
                {exportProgress.current ? ` · ${exportProgress.current}` : ''}
              </div>
            ) : null}
            <div className="shrink-0 border-b border-line bg-surface px-3 pt-2">
              <Tabs value={tab} onValueChange={(value) => setTab(value as Tab)}>
                <TabsList>
                  <TabsTrigger value="files">文件 {fileItems.length ? `(${fileItems.length})` : ''}</TabsTrigger>
                  <TabsTrigger value="assignments">作业 {assignments.length ? `(${assignments.length})` : ''}</TabsTrigger>
                  <TabsTrigger value="pages">页面/讨论 {pages.length ? `(${pages.length})` : ''}</TabsTrigger>
                </TabsList>
              </Tabs>
            </div>
            <div className="min-h-0 flex-1">{renderBody()}</div>
          </section>

          {selectedItemId ? (
            <aside className="flex w-[min(38%,34rem)] min-w-[24rem] shrink-0 flex-col border-l border-line bg-surface">
              <PreviewPane
                doc={preview}
                loading={previewLoading}
                error={previewError}
                actions={
                  <>
                    {selectedItem?.kind === 'file' ? (
                      <>
                        <Button size="sm" variant="ghost" onClick={downloadSelected} title="下载">
                          <CloudDownload className="size-3.5" />
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => setProjectPickerOpen(true)}
                          title="添加到项目"
                        >
                          <FolderPlus className="size-3.5" />
                        </Button>
                      </>
                    ) : null}
                    {selectedItem && ['assignment', 'page', 'discussion'].includes(selectedItem.kind) ? (
                      <Button size="sm" variant="ghost" onClick={() => openInCanvas(selectedItem)} title="在 Canvas 打开">
                        <ExternalLink className="size-3.5" />
                      </Button>
                    ) : null}
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => {
                        setSelectedItemId(null);
                        setPreview(null);
                      }}
                      title="关闭预览"
                    >
                      <X className="size-3.5" />
                    </Button>
                  </>
                }
              />
            </aside>
          ) : null}
        </div>
      )}

      <Dialog open={tokenOpen} onOpenChange={setTokenOpen}>
        <DialogContent
          title="连接 Canvas"
          description="在 Canvas 的账户设置中创建 Personal Access Token，然后粘贴到这里。"
          footer={
            <>
              <Button variant="ghost" onClick={() => setTokenOpen(false)}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void connect()} disabled={!token.trim()}>
                连接
              </Button>
            </>
          }
        >
          <label className="text-xs font-medium text-ink-2">Access Token</label>
          <Input
            type="password"
            value={token}
            onChange={(event) => setToken(event.target.value)}
            placeholder="粘贴 Canvas Access Token"
            className="mt-1.5"
            autoFocus
          />
          <p className="mt-3 text-[0.68rem] leading-relaxed text-ink-3">
            Token 使用 Windows DPAPI 加密，仅保存在本机。同步只读取课程资料、作业与页面，不会修改课程内容或提交作业。
          </p>
        </DialogContent>
      </Dialog>

      <Dialog open={projectPickerOpen} onOpenChange={setProjectPickerOpen}>
        <DialogContent
          title="添加到项目"
          description="文件会复制到所选项目的 Canvas 目录中。"
          wide
          footer={
            <Button variant="ghost" onClick={() => setProjectPickerOpen(false)}>
              取消
            </Button>
          }
        >
          {projects.length === 0 ? (
            <EmptyState
              icon={FolderPlus}
              title="还没有项目"
              description="先在工作台添加一个项目文件夹，再回来添加课件。"
              action={
                <Button
                  size="sm"
                  onClick={() => {
                    setProjectPickerOpen(false);
                    navigate('workspace');
                  }}
                >
                  打开工作台
                </Button>
              }
            />
          ) : (
            <div className="space-y-1">
              {projects.map((project) => (
                <button
                  key={project.id}
                  type="button"
                  disabled={adding}
                  onClick={() => void addSelectedToProject(project)}
                  className="flex w-full items-center gap-3 rounded-lg border border-line bg-surface-2 px-3 py-2.5 text-left transition-colors hover:border-line-strong hover:bg-surface-3 disabled:opacity-50"
                >
                  <FolderOpen className="size-4 text-accent" />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-xs font-medium text-ink">{project.name}</div>
                    <div className="truncate text-[0.62rem] text-ink-3">{project.path}</div>
                  </div>
                  {adding ? <Loader2 className="size-3.5 animate-spin text-accent" /> : null}
                </button>
              ))}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function termMatches(term: string, query: string) {
  return term.toLowerCase().includes(query);
}
