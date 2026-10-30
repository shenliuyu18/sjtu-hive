import CodeMirror from '@uiw/react-codemirror';
import { markdown, markdownLanguage } from '@codemirror/lang-markdown';
import { languages } from '@codemirror/language-data';
import { oneDark } from '@codemirror/theme-one-dark';
import {
  ChevronDown,
  ChevronRight,
  Code2,
  Command,
  File as FileIcon,
  FileImage,
  FilePlus2,
  FileText,
  Folder,
  FolderOpen,
  FolderPlus,
  GitBranch,
  Loader2,
  MoreHorizontal,
  PanelLeftClose,
  Plus,
  RefreshCw,
  Save,
  Search,
  SquareTerminal,
  X,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { call, on } from '@/lib/bridge';
import type {
  DocumentContent,
  FileEntry,
  WorkspaceProject,
  WorkspaceRecent,
  WorkspaceStateResponse,
} from '@/lib/models';
import { formatDate, formatTime, relativeTime } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Dropdown, DropdownContent, DropdownItem, DropdownSeparator, DropdownTrigger } from '@/components/ui/Dropdown';
import { Badge, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';
import { MarkdownView } from '@/components/content/MarkdownView';
import { PreviewPane } from '@/components/content/PreviewPane';
import { TerminalPanel } from '@/components/content/TerminalPanel';

type FileTab = {
  id: string;
  kind: 'file';
  path: string;
  title: string;
  text: string;
  original: string;
  revision: string;
  readOnly: boolean;
  format: string;
  assetBase?: string;
  mode: 'edit' | 'preview' | 'split';
};

type PreviewTab = {
  id: string;
  kind: 'preview';
  path: string;
  title: string;
  doc: DocumentContent;
};

type TerminalTab = {
  id: string;
  kind: 'terminal';
  title: string;
  terminalId: string;
  shell: string;
  projectId: string;
};

type Tab = FileTab | PreviewTab | TerminalTab;

type EntryDialog =
  | { mode: 'file' | 'folder'; parent: string; value: string; title: string }
  | { mode: 'rename'; path: string; value: string; title: string };

interface StoredTab {
  path: string;
  active: boolean;
}

function extensionOf(name: string) {
  const index = name.lastIndexOf('.');
  return index >= 0 ? name.slice(index + 1).toLowerCase() : '';
}

function fileIcon(entry: FileEntry | Tab) {
  if ('kind' in entry && entry.kind === 'terminal') return SquareTerminal;
  const name = 'name' in entry ? entry.name : entry.title;
  const ext = extensionOf(name);
  if ('isDir' in entry && entry.isDir) return Folder;
  if (ext === 'md' || ext === 'markdown') return FileText;
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(ext)) return FileImage;
  return FileIcon;
}

function tabIcon(tab: Tab) {
  if (tab.kind === 'terminal') return SquareTerminal;
  if (tab.kind === 'preview') return FileIcon;
  if (extensionOf(tab.title) === 'md') return FileText;
  return FileIcon;
}

function toTextTab(doc: DocumentContent): FileTab {
  return {
    id: doc.path,
    kind: 'file',
    path: doc.path,
    title: doc.title,
    text: doc.text ?? '',
    original: doc.text ?? '',
    revision: doc.revision ?? '',
    readOnly: Boolean(doc.readOnly),
    format: doc.format,
    assetBase: doc.assetBase,
    mode: extensionOf(doc.title) === 'md' ? 'edit' : 'edit',
  };
}

function toPreviewTab(doc: DocumentContent): PreviewTab {
  return { id: doc.path, kind: 'preview', path: doc.path, title: doc.title, doc };
}

export function WorkspacePage() {
  const params = useApp((state) => state.params);
  const navigate = useApp((state) => state.navigate);
  const settings = useSettings((state) => state.state);
  const dark =
    settings.theme === 'dark' ||
    (settings.theme === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches);
  const [projects, setProjects] = useState<WorkspaceProject[]>([]);
  const [recent, setRecent] = useState<WorkspaceRecent[]>([]);
  const [projectId, setProjectId] = useState<string>('');
  const [rootEntries, setRootEntries] = useState<FileEntry[]>([]);
  const [children, setChildren] = useState<Record<string, FileEntry[]>>({});
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [tabs, setTabs] = useState<Tab[]>([]);
  const [activeTabId, setActiveTabId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [treeLoading, setTreeLoading] = useState(false);
  const [entryDialog, setEntryDialog] = useState<EntryDialog | null>(null);
  const [entryBusy, setEntryBusy] = useState(false);
  const [gitBranch, setGitBranch] = useState<string | null>(null);
  const [branches, setBranches] = useState<string[]>([]);
  const [terminalBusy, setTerminalBusy] = useState(false);
  const [pendingOpen, setPendingOpen] = useState<string | null>(null);
  const restoredRef = useRef<string>('');
  const tabsSaveTimer = useRef<number | null>(null);
  const tabsRef = useRef<Tab[]>([]);

  const project = projects.find((item) => item.id === projectId) ?? null;
  const activeTab = tabs.find((tab) => tab.id === activeTabId) ?? null;
  const selectedFolder = selectedPath && rootOrChildDirectory(selectedPath, rootEntries, children)
    ? selectedPath
    : project?.path ?? '';

  const loadProjects = useCallback(async () => {
    try {
      const value = await call<WorkspaceStateResponse>('workspace.state');
      setProjects(value?.projects ?? []);
      setRecent(value?.recent ?? []);
      return value?.projects ?? [];
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取工作区失败');
      return [];
    } finally {
      setLoading(false);
    }
  }, []);

  const loadTree = useCallback(async (projectValue: WorkspaceProject, path?: string) => {
    setTreeLoading(true);
    try {
      const entries = await call<FileEntry[]>('workspace.tree', projectValue.id, path ?? '');
      if (path) setChildren((current) => ({ ...current, [path]: entries ?? [] }));
      else setRootEntries(entries ?? []);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取目录失败');
    } finally {
      setTreeLoading(false);
    }
  }, []);

  const refreshExpanded = useCallback(
    async (projectValue: WorkspaceProject) => {
      await loadTree(projectValue);
      const paths = [...expanded];
      await Promise.all(paths.map((path) => loadTree(projectValue, path)));
    },
    [expanded, loadTree],
  );

  useEffect(() => {
    void loadProjects().then((items) => {
      if (items.length === 0) return;
      const preferred =
        (typeof params.workspaceProjectId === 'string' ? params.workspaceProjectId : '') ||
        localStorage.getItem('sjtu.workspace.project') ||
        items[0].id;
      const projectValue = items.find((item) => item.id === preferred) ?? items[0];
      setProjectId(projectValue.id);
      if (typeof params.workspacePath === 'string') setPendingOpen(params.workspacePath);
    });
  }, [loadProjects]);

  useEffect(() => {
    tabsRef.current = tabs;
  }, [tabs]);

  useEffect(() => {
    if (!projectId) return;
    const projectValue = projects.find((item) => item.id === projectId);
    if (!projectValue) return;
    localStorage.setItem('sjtu.workspace.project', projectId);
    for (const tab of tabsRef.current) {
      if (tab.kind === 'terminal') void call('terminal.close', tab.terminalId).catch(() => {});
    }
    setTabs([]);
    setActiveTabId(null);
    setExpanded(new Set());
    setChildren({});
    setSelectedPath(projectValue.path);
    void loadTree(projectValue);
    void call<string | null>('workspace.gitBranch', projectId)
      .then(setGitBranch)
      .catch(() => setGitBranch(null));
    void call<string[]>('workspace.gitBranches', projectId)
      .then((value) => setBranches(value ?? []))
      .catch(() => setBranches([]));
    if (restoredRef.current !== projectId) {
      restoredRef.current = projectId;
      void restoreTabs(projectValue);
    }
  }, [projectId, loadTree]);

  useEffect(() => {
    if (!projectId) return;
    const off = on('workspace.changed', () => {
      const projectValue = projects.find((item) => item.id === projectId);
      if (projectValue) void refreshExpanded(projectValue);
    });
    return () => off();
  }, [projectId, projects, refreshExpanded]);

  useEffect(() => {
    if (!params.newNote || !projectId) return;
    setEntryDialog({
      mode: 'file',
      parent: project?.path ?? '',
      value: '未命名笔记.md',
      title: '新建笔记',
    });
  }, [params.newNote, projectId, project?.path]);

  useEffect(() => {
    if (!pendingOpen || !projectId) return;
    const projectValue = projects.find((item) => item.id === projectId);
    if (!projectValue) return;
    if (!pendingOpen.startsWith(projectValue.path)) return;
    setPendingOpen(null);
    void openFile(pendingOpen);
  }, [pendingOpen, projectId, projects]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 's') {
        event.preventDefault();
        void saveActive();
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeTab]);

  useEffect(() => {
    if (!projectId) return;
    if (tabsSaveTimer.current) window.clearTimeout(tabsSaveTimer.current);
    tabsSaveTimer.current = window.setTimeout(() => {
      const payload = JSON.stringify(
        tabs
          .filter((tab) => tab.kind !== 'terminal')
          .map((tab) => ({ path: tab.path, active: tab.id === activeTabId })),
      );
      void call('workspace.saveTabs', projectId, payload).catch(() => {});
    }, 500);
    return () => {
      if (tabsSaveTimer.current) window.clearTimeout(tabsSaveTimer.current);
    };
  }, [tabs, activeTabId, projectId]);

  async function restoreTabs(projectValue: WorkspaceProject) {
    try {
      const payload = await call<string>('workspace.tabs', projectValue.id);
      if (!payload) return;
      const stored = JSON.parse(payload) as StoredTab[];
      for (const item of stored) {
        if (!item?.path) continue;
        await openFile(item.path, false);
        if (item.active) setActiveTabId(item.path);
      }
    } catch {
      // ignore corrupt tab state
    }
  }

  const addTab = (tab: Tab) => {
    setTabs((current) => {
      if (current.some((item) => item.id === tab.id)) return current;
      return [...current, tab];
    });
    setActiveTabId(tab.id);
  };

  const openFile = async (path: string, activate = true) => {
    const existing = tabs.find((tab) => tab.id === path);
    if (existing) {
      if (activate) setActiveTabId(existing.id);
      return;
    }
    try {
      const doc = await call<DocumentContent>('workspace.read', path);
      const tab =
        doc.format === 'text' || doc.format === 'markdown'
          ? toTextTab(doc)
          : toPreviewTab(doc);
      addTab(tab);
      if (activate) setActiveTabId(tab.id);
      setSelectedPath(path);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '打开文件失败');
    }
  };

  const saveActive = async () => {
    if (!activeTab || activeTab.kind !== 'file') return;
    if (activeTab.readOnly) {
      toast.warning('该文件当前为只读预览');
      return;
    }
    try {
      const revision = await call<string>('workspace.save', activeTab.path, activeTab.text, activeTab.revision);
      setTabs((current) =>
        current.map((tab) =>
          tab.id === activeTab.id && tab.kind === 'file'
            ? { ...tab, revision, original: tab.text, readOnly: false }
            : tab,
        ),
      );
      toast.success('已保存');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '保存失败');
    }
  };

  const updateTabText = (id: string, text: string) => {
    setTabs((current) =>
      current.map((tab) => (tab.id === id && tab.kind === 'file' ? { ...tab, text } : tab)),
    );
  };

  const updateTabMode = (id: string, mode: FileTab['mode']) => {
    setTabs((current) =>
      current.map((tab) => (tab.id === id && tab.kind === 'file' ? { ...tab, mode } : tab)),
    );
  };

  const closeTab = (tab: Tab) => {
    if (tab.kind === 'terminal') {
      void call('terminal.close', tab.terminalId).catch(() => {});
    }
    setTabs((current) => {
      const index = current.findIndex((item) => item.id === tab.id);
      const next = current.filter((item) => item.id !== tab.id);
      if (activeTabId === tab.id) {
        const fallback = next[Math.min(index, next.length - 1)];
        setActiveTabId(fallback?.id ?? null);
      }
      return next;
    });
  };

  const requestClose = (tab: Tab) => {
    if (tab.kind === 'file' && tab.text !== tab.original) {
      if (!window.confirm('文件尚未保存，确定关闭？')) return;
    }
    closeTab(tab);
  };

  const toggleFolder = async (entry: FileEntry) => {
    if (!project) return;
    setSelectedPath(entry.path);
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(entry.path)) next.delete(entry.path);
      else next.add(entry.path);
      return next;
    });
    if (!children[entry.path]) await loadTree(project, entry.path);
  };

  const createEntry = async () => {
    if (!entryDialog || !project) return;
    setEntryBusy(true);
    try {
      if (entryDialog.mode === 'rename') {
        const target = await call<string>('workspace.rename', entryDialog.path, entryDialog.value);
        toast.success('已重命名');
        setTabs((current) => current.filter((tab) => tab.kind === 'terminal' || tab.path !== entryDialog.path));
        await refreshExpanded(project);
        setSelectedPath(target);
      } else {
        const entry = await call<FileEntry>(
          'workspace.create',
          project.id,
          entryDialog.parent,
          entryDialog.value,
          entryDialog.mode,
          '',
        );
        toast.success(entryDialog.mode === 'folder' ? '文件夹已创建' : '文件已创建');
        await refreshExpanded(project);
        if (!entry.isDir) await openFile(entry.path);
        setSelectedPath(entry.path);
      }
      setEntryDialog(null);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '操作失败');
    } finally {
      setEntryBusy(false);
    }
  };

  const deleteEntry = async (entry: FileEntry) => {
    if (!project) return;
    if (!window.confirm(`删除「${entry.name}」？文件会移入 Windows 回收站。`)) return;
    try {
      await call('workspace.delete', entry.path);
      setTabs((current) =>
        current.filter(
          (tab) => tab.kind === 'terminal' || !(tab.path === entry.path || tab.path.startsWith(`${entry.path}\\`) || tab.path.startsWith(`${entry.path}/`)),
        ),
      );
      toast.success('已移入回收站');
      await refreshExpanded(project);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '删除失败');
    }
  };

  const addProject = async () => {
    try {
      const value = await call<WorkspaceProject | null>('workspace.addProject');
      if (!value) return;
      const items = await loadProjects();
      setProjectId(value.id);
      if (!items.some((item) => item.id === value.id)) setProjects([...items, value]);
      toast.success('项目已添加');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '添加项目失败');
    }
  };

  const removeProject = async () => {
    if (!project) return;
    if (!window.confirm(`移除项目「${project.name}」？只会移除登记，不会删除磁盘上的文件。`)) return;
    try {
      await call('workspace.removeProject', project.id);
      const items = await loadProjects();
      setProjectId(items[0]?.id ?? '');
      toast.success('项目已移除');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '移除失败');
    }
  };

  const switchBranch = async (branch: string) => {
    if (!project || branch === gitBranch) return;
    if (!window.confirm(`切换到分支「${branch}」？未提交的改动可能阻止切换。`)) return;
    try {
      await call('workspace.gitSwitch', project.id, branch);
      toast.success(`已切换到 ${branch}`);
      setTabs([]);
      setActiveTabId(null);
      setGitBranch(branch);
      await refreshExpanded(project);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '切换分支失败');
    }
  };

  const openTerminal = async () => {
    if (!project || terminalBusy) return;
    const existing = tabs.find((tab) => tab.kind === 'terminal' && tab.projectId === project.id);
    if (existing) {
      setActiveTabId(existing.id);
      return;
    }
    setTerminalBusy(true);
    try {
      const result = await call<{ id: string; shell: string; cwd: string }>(
        'terminal.start',
        project.id,
        100,
        30,
      );
      const tab: TerminalTab = {
        id: `terminal:${result.id}`,
        kind: 'terminal',
        title: `终端 · ${result.shell}`,
        terminalId: result.id,
        shell: result.shell,
        projectId: project.id,
      };
      addTab(tab);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '无法启动终端');
    } finally {
      setTerminalBusy(false);
    }
  };

  const openRecent = async (item: WorkspaceRecent) => {
    const projectValue = projects.find((projectItem) => item.path.startsWith(projectItem.path));
    if (projectValue && projectValue.id !== projectId) {
      setProjectId(projectValue.id);
      setPendingOpen(item.path);
      return;
    }
    await openFile(item.path);
  };

  const completion = useMemo(() => {
    if (!activeTab || activeTab.kind !== 'file') return '';
    return `${activeTab.text.length} 字符`;
  }, [activeTab]);

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在读取工作区…
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="工作台"
        description={project ? `${project.name} · ${project.path}` : '本地项目、笔记、预览与终端。'}
        eyebrow="Workspace"
        actions={
          <>
            {project ? (
              <>
                <select
                  value={projectId}
                  onChange={(event) => setProjectId(event.target.value)}
                  className="h-9 max-w-[13rem] rounded-lg border border-line bg-surface px-2 text-xs text-ink focus:border-accent/60 focus:outline-none"
                >
                  {projects.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name}
                    </option>
                  ))}
                </select>
                {gitBranch ? (
                  <Dropdown>
                    <DropdownTrigger asChild>
                      <Button variant="secondary" size="sm">
                        <GitBranch className="size-3.5" /> {gitBranch}
                        <ChevronDown className="size-3" />
                      </Button>
                    </DropdownTrigger>
                    <DropdownContent>
                      {branches.length === 0 ? <DropdownItem disabled>没有分支</DropdownItem> : null}
                      {branches.map((branch) => (
                        <DropdownItem key={branch} onSelect={() => void switchBranch(branch)}>
                          {branch === gitBranch ? `✓ ${branch}` : branch}
                        </DropdownItem>
                      ))}
                      <DropdownSeparator />
                      <DropdownItem
                        icon={FolderOpen}
                        onSelect={() =>
                          void call('workspace.openProjectDirectory', project.id, 'explorer')
                        }
                      >
                        在资源管理器中打开
                      </DropdownItem>
                      <DropdownItem icon={X} danger onSelect={() => void removeProject()}>
                        移除项目
                      </DropdownItem>
                    </DropdownContent>
                  </Dropdown>
                ) : null}
                <Button variant="secondary" onClick={() => void openTerminal()} disabled={terminalBusy}>
                  {terminalBusy ? <Loader2 className="size-4 animate-spin" /> : <SquareTerminal className="size-4" />}
                  终端
                </Button>
              </>
            ) : null}
            <Button variant="primary" onClick={() => void addProject()}>
              <FolderPlus className="size-4" /> 添加项目
            </Button>
          </>
        }
      />
      <div className="flex min-h-0 flex-1 border-t border-line">
        <aside className="flex w-[18rem] shrink-0 flex-col border-r border-line bg-surface">
          <div className="flex items-center justify-between border-b border-line px-3 py-2">
            <div className="flex items-center gap-2 text-[0.66rem] font-semibold uppercase tracking-[0.12em] text-ink-3">
              <FolderOpen className="size-3.5" /> 文件
            </div>
            <div className="flex items-center gap-0.5">
              <Button
                size="icon-sm"
                variant="ghost"
                title="新建笔记"
                onClick={() =>
                  setEntryDialog({
                    mode: 'file',
                    parent: selectedFolder,
                    value: '未命名笔记.md',
                    title: '新建笔记',
                  })
                }
                disabled={!project}
              >
                <FilePlus2 className="size-3.5" />
              </Button>
              <Button
                size="icon-sm"
                variant="ghost"
                title="新建文件夹"
                onClick={() =>
                  setEntryDialog({
                    mode: 'folder',
                    parent: selectedFolder,
                    value: '新建文件夹',
                    title: '新建文件夹',
                  })
                }
                disabled={!project}
              >
                <FolderPlus className="size-3.5" />
              </Button>
              <Button
                size="icon-sm"
                variant="ghost"
                title="刷新"
                onClick={() => project && void refreshExpanded(project)}
                disabled={!project}
              >
                <RefreshCw className={cn('size-3.5', treeLoading && 'animate-spin')} />
              </Button>
            </div>
          </div>
          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto py-1">
            {!project ? (
              <div className="px-4 py-8 text-center text-[0.68rem] text-ink-3">选择一个项目开始。</div>
            ) : rootEntries.length === 0 && !treeLoading ? (
              <div className="px-4 py-8 text-center text-[0.68rem] text-ink-3">
                项目目录为空；新建一个笔记或文件夹。
              </div>
            ) : (
              rootEntries.map((entry) => (
                <TreeEntry
                  key={entry.path}
                  entry={entry}
                  depth={0}
                  childrenMap={children}
                  expanded={expanded}
                  selectedPath={selectedPath}
                  onToggle={toggleFolder}
                  onOpen={(value) => void openFile(value.path)}
                  onSelect={setSelectedPath}
                  onCreateFile={(folder) =>
                    setEntryDialog({ mode: 'file', parent: folder.path, value: '未命名.md', title: '新建文件' })
                  }
                  onCreateFolder={(folder) =>
                    setEntryDialog({ mode: 'folder', parent: folder.path, value: '新建文件夹', title: '新建文件夹' })
                  }
                  onRename={(value) =>
                    setEntryDialog({ mode: 'rename', path: value.path, value: value.name, title: '重命名' })
                  }
                  onDelete={(value) => void deleteEntry(value)}
                  onReveal={(value) => void call('app.showInFolder', value.path)}
                />
              ))
            )}
          </div>
          <div className="border-t border-line px-3 py-2 text-[0.62rem] text-ink-3">
            {project ? `${project.path}` : '未选择项目'}
          </div>
        </aside>

        <section className="flex min-w-0 flex-1 flex-col bg-bg">
          <div className="flex h-10 shrink-0 items-stretch border-b border-line bg-surface">
            <div className="scroll-thin flex min-w-0 flex-1 items-stretch overflow-x-auto">
              {tabs.map((tab) => {
                const Icon = tabIcon(tab);
                const active = tab.id === activeTabId;
                const dirty = tab.kind === 'file' && tab.text !== tab.original;
                return (
                  <div
                    key={tab.id}
                    className={cn(
                      'group flex h-full min-w-[8rem] max-w-[14rem] shrink-0 items-center gap-1.5 border-r border-line px-2.5 text-xs transition-colors',
                      active ? 'bg-bg text-ink' : 'text-ink-3 hover:bg-surface-2 hover:text-ink-2',
                    )}
                  >
                    <button
                      type="button"
                      onClick={() => setActiveTabId(tab.id)}
                      className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
                    >
                      <Icon className="size-3.5 shrink-0" />
                      <span className="min-w-0 truncate">{tab.title}</span>
                      {dirty ? <span className="size-1.5 shrink-0 rounded-full bg-accent" /> : null}
                    </button>
                    <button
                      type="button"
                      onClick={() => requestClose(tab)}
                      className="grid size-4 shrink-0 place-items-center rounded text-ink-3 opacity-0 transition-opacity hover:bg-hover hover:text-ink group-hover:opacity-100"
                      aria-label="关闭标签"
                    >
                      <X className="size-3" />
                    </button>
                  </div>
                );
              })}
              {tabs.length === 0 ? (
                <div className="flex items-center px-3 text-[0.66rem] text-ink-3">没有打开的标签</div>
              ) : null}
            </div>
            {activeTab?.kind === 'file' ? (
              <div className="flex shrink-0 items-center gap-1 border-l border-line px-2">
                {activeTab.format === 'markdown' ? (
                  <>
                    <Button
                      size="sm"
                      variant={activeTab.mode === 'edit' ? 'soft' : 'ghost'}
                      onClick={() => updateTabMode(activeTab.id, 'edit')}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant={activeTab.mode === 'split' ? 'soft' : 'ghost'}
                      onClick={() => updateTabMode(activeTab.id, 'split')}
                    >
                      分栏
                    </Button>
                    <Button
                      size="sm"
                      variant={activeTab.mode === 'preview' ? 'soft' : 'ghost'}
                      onClick={() => updateTabMode(activeTab.id, 'preview')}
                    >
                      预览
                    </Button>
                  </>
                ) : null}
                <span className="mx-1 text-[0.62rem] text-ink-3">{completion}</span>
                <Button size="sm" variant="primary" onClick={() => void saveActive()} disabled={activeTab.readOnly}>
                  <Save className="size-3.5" /> 保存
                </Button>
              </div>
            ) : null}
          </div>

          <div className="relative min-h-0 flex-1">
            {activeTab?.kind === 'file' ? (
              <div className="flex h-full min-h-0 flex-col">
                {activeTab.readOnly ? (
                  <div className="border-b border-warning/30 bg-warning/10 px-4 py-1.5 text-[0.66rem] text-warning">
                    文件大于 1 MB 或格式不支持编辑，当前为只读预览。
                  </div>
                ) : null}
                <div className="min-h-0 flex-1">
                  {activeTab.mode === 'preview' ? (
                    <div className="scroll-thin h-full overflow-y-auto p-6">
                      <MarkdownView text={activeTab.text} assetBase={activeTab.assetBase} />
                    </div>
                  ) : activeTab.mode === 'split' ? (
                    <div className="grid h-full min-h-0 grid-cols-2 divide-x divide-line">
                      <div className="min-h-0 overflow-hidden">
                        <CodeMirror
                          value={activeTab.text}
                          height="100%"
                          theme={dark ? oneDark : 'light'}
                          extensions={
                            activeTab.format === 'markdown'
                              ? [markdown({ base: markdownLanguage, codeLanguages: languages })]
                              : []
                          }
                          editable={!activeTab.readOnly}
                          onChange={(value) => updateTabText(activeTab.id, value)}
                          basicSetup={{
                            lineNumbers: true,
                            foldGutter: false,
                            highlightActiveLine: true,
                            highlightActiveLineGutter: true,
                            autocompletion: false,
                          }}
                          className="h-full text-[0.82rem]"
                        />
                      </div>
                      <div className="scroll-thin min-h-0 overflow-y-auto p-6">
                        <MarkdownView text={activeTab.text} assetBase={activeTab.assetBase} />
                      </div>
                    </div>
                  ) : (
                    <CodeMirror
                      value={activeTab.text}
                      height="100%"
                      theme={dark ? oneDark : 'light'}
                      extensions={
                        activeTab.format === 'markdown'
                          ? [markdown({ base: markdownLanguage, codeLanguages: languages })]
                          : []
                      }
                      editable={!activeTab.readOnly}
                      onChange={(value) => updateTabText(activeTab.id, value)}
                      basicSetup={{
                        lineNumbers: true,
                        foldGutter: false,
                        highlightActiveLine: true,
                        highlightActiveLineGutter: true,
                        autocompletion: false,
                      }}
                      className="h-full text-[0.82rem]"
                    />
                  )}
                </div>
                <div className="flex shrink-0 items-center justify-between border-t border-line bg-surface px-3 py-1.5 text-[0.62rem] text-ink-3">
                  <span>{activeTab.path}</span>
                  <span>
                    {activeTab.text !== activeTab.original ? '未保存' : '已保存'} · {activeTab.format}
                  </span>
                </div>
              </div>
            ) : null}
            {activeTab?.kind === 'preview' ? (
              <div className="h-full">
                <PreviewPane
                  doc={activeTab.doc}
                  actions={
                    <>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void call('workspace.openFile', activeTab.doc.path)}
                        title="用默认应用打开"
                      >
                        <FolderOpen className="size-3.5" />
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void call('app.showInFolder', activeTab.doc.path)}
                        title="在文件夹中显示"
                      >
                        <Search className="size-3.5" />
                      </Button>
                    </>
                  }
                />
              </div>
            ) : null}
            {tabs
              .filter((tab): tab is TerminalTab => tab.kind === 'terminal')
              .map((tab) => (
                <div
                  key={tab.id}
                  className={cn('absolute inset-0', activeTabId === tab.id ? 'block' : 'hidden')}
                >
                  <TerminalPanel id={tab.terminalId} active={activeTabId === tab.id} />
                </div>
              ))}
            {!activeTab ? (
              <WorkspaceWelcome
                project={project}
                recent={recent}
                onCreateNote={() =>
                  setEntryDialog({
                    mode: 'file',
                    parent: project?.path ?? '',
                    value: '未命名笔记.md',
                    title: '新建笔记',
                  })
                }
                onOpenRecent={(item) => void openRecent(item)}
                onOpenTerminal={() => void openTerminal()}
                onShowRecent={() => setRecent((current) => current)}
              />
            ) : null}
          </div>
        </section>
      </div>

      <Dialog open={entryDialog !== null} onOpenChange={(open) => !open && setEntryDialog(null)}>
        <DialogContent
          title={entryDialog?.title ?? '新建'}
          description={
            entryDialog?.mode === 'rename'
              ? '只会修改名称，不会移动文件位置。'
              : '名称不能包含 \\ / : * ? " < > |。'
          }
          footer={
            <>
              <Button variant="ghost" onClick={() => setEntryDialog(null)}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void createEntry()} disabled={entryBusy || !entryDialog?.value.trim()}>
                {entryBusy ? <Loader2 className="size-4 animate-spin" /> : null}
                确定
              </Button>
            </>
          }
        >
          <Input
            value={entryDialog?.value ?? ''}
            onChange={(event) =>
              setEntryDialog((current) => (current ? { ...current, value: event.target.value } : current))
            }
            onKeyDown={(event) => {
              if (event.key === 'Enter') void createEntry();
            }}
            autoFocus
          />
        </DialogContent>
      </Dialog>
    </div>
  );
}

function rootOrChildDirectory(path: string, root: FileEntry[], children: Record<string, FileEntry[]>) {
  const folder = root.find((entry) => entry.path === path)
    ?? Object.values(children).flat().find((entry) => entry.path === path);
  return folder?.isDir ? path : null;
}

function TreeEntry({
  entry,
  depth,
  childrenMap,
  expanded,
  selectedPath,
  onToggle,
  onOpen,
  onSelect,
  onCreateFile,
  onCreateFolder,
  onRename,
  onDelete,
  onReveal,
}: {
  entry: FileEntry;
  depth: number;
  childrenMap: Record<string, FileEntry[]>;
  expanded: Set<string>;
  selectedPath: string | null;
  onToggle: (entry: FileEntry) => void;
  onOpen: (entry: FileEntry) => void;
  onSelect: (path: string) => void;
  onCreateFile: (entry: FileEntry) => void;
  onCreateFolder: (entry: FileEntry) => void;
  onRename: (entry: FileEntry) => void;
  onDelete: (entry: FileEntry) => void;
  onReveal: (entry: FileEntry) => void;
}) {
  const Icon = entry.isDir ? (expanded.has(entry.path) ? FolderOpen : Folder) : fileIcon(entry);
  const open = expanded.has(entry.path);
  const children = childrenMap[entry.path] ?? [];
  return (
    <div>
      <div
        className={cn(
          'group flex h-8 items-center gap-1 rounded-md pr-1.5 text-xs transition-colors',
          selectedPath === entry.path ? 'bg-selected text-accent' : 'text-ink-2 hover:bg-hover hover:text-ink',
        )}
        style={{ paddingLeft: `${depth * 13 + 6}px` }}
      >
        <button
          type="button"
          className="flex min-w-0 flex-1 items-center gap-1.5 text-left"
          onClick={() => {
            onSelect(entry.path);
            if (entry.isDir) void onToggle(entry);
            else onOpen(entry);
          }}
        >
          <span className="grid size-4 shrink-0 place-items-center">
            {entry.isDir ? (
              open ? <ChevronDown className="size-3.5" /> : <ChevronRight className="size-3.5" />
            ) : null}
          </span>
          <Icon className="size-4 shrink-0 text-ink-3" />
          <span className="min-w-0 truncate">{entry.name}</span>
        </button>
        <Dropdown>
          <DropdownTrigger asChild>
            <button
              type="button"
              className="grid size-6 shrink-0 place-items-center rounded text-ink-3 opacity-0 hover:bg-hover hover:text-ink group-hover:opacity-100"
              aria-label={`${entry.name} 的操作`}
            >
              <MoreHorizontal className="size-3.5" />
            </button>
          </DropdownTrigger>
          <DropdownContent>
            {entry.isDir ? (
              <>
                <DropdownItem icon={FilePlus2} onSelect={() => onCreateFile(entry)}>
                  新建文件
                </DropdownItem>
                <DropdownItem icon={FolderPlus} onSelect={() => onCreateFolder(entry)}>
                  新建文件夹
                </DropdownItem>
                <DropdownSeparator />
              </>
            ) : null}
            <DropdownItem icon={Code2} onSelect={() => onRename(entry)}>
              重命名
            </DropdownItem>
            <DropdownItem icon={FolderOpen} onSelect={() => onReveal(entry)}>
              在文件夹中显示
            </DropdownItem>
            <DropdownSeparator />
            <DropdownItem icon={X} danger onSelect={() => onDelete(entry)}>
              删除
            </DropdownItem>
          </DropdownContent>
        </Dropdown>
      </div>
      {entry.isDir && open
        ? children.map((child) => (
            <TreeEntry
              key={child.path}
              entry={child}
              depth={depth + 1}
              childrenMap={childrenMap}
              expanded={expanded}
              selectedPath={selectedPath}
              onToggle={onToggle}
              onOpen={onOpen}
              onSelect={onSelect}
              onCreateFile={onCreateFile}
              onCreateFolder={onCreateFolder}
              onRename={onRename}
              onDelete={onDelete}
              onReveal={onReveal}
            />
          ))
        : null}
    </div>
  );
}

function WorkspaceWelcome({
  project,
  recent,
  onCreateNote,
  onOpenRecent,
  onOpenTerminal,
  onShowRecent,
}: {
  project: WorkspaceProject | null;
  recent: WorkspaceRecent[];
  onCreateNote: () => void;
  onOpenRecent: (item: WorkspaceRecent) => void;
  onOpenTerminal: () => void;
  onShowRecent: () => void;
}) {
  return (
    <div className="scroll-thin h-full overflow-y-auto">
      <div className="mx-auto max-w-4xl px-8 py-10">
        <div className="flex items-center gap-3">
          <div className="grid size-12 place-items-center rounded-2xl bg-accent-soft text-accent">
            <Command className="size-6" />
          </div>
          <div>
            <h1 className="text-lg font-semibold text-ink">本地工作台</h1>
            <p className="mt-0.5 text-xs text-ink-3">
              {project ? `${project.name} · ${project.path}` : '添加一个项目文件夹开始。'}
            </p>
          </div>
        </div>
        <div className="mt-6 grid grid-cols-2 gap-3">
          <button
            type="button"
            onClick={onCreateNote}
            className="flex items-center gap-3 rounded-card border border-line bg-surface p-4 text-left shadow-[var(--shadow-card)] transition-colors hover:border-accent/40"
          >
            <div className="grid size-10 place-items-center rounded-xl bg-accent-soft text-accent">
              <FilePlus2 className="size-5" />
            </div>
            <div>
              <div className="text-sm font-medium text-ink">新建笔记</div>
              <div className="mt-0.5 text-[0.66rem] text-ink-3">创建 Markdown 文件，支持公式与 mermaid。</div>
            </div>
          </button>
          <button
            type="button"
            onClick={onOpenTerminal}
            className="flex items-center gap-3 rounded-card border border-line bg-surface p-4 text-left shadow-[var(--shadow-card)] transition-colors hover:border-accent/40"
          >
            <div className="grid size-10 place-items-center rounded-xl bg-surface-3 text-ink-2">
              <SquareTerminal className="size-5" />
            </div>
            <div>
              <div className="text-sm font-medium text-ink">打开终端</div>
              <div className="mt-0.5 text-[0.66rem] text-ink-3">在项目目录启动 Git Bash / PowerShell。</div>
            </div>
          </button>
        </div>
        <div className="mt-8">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-ink">最近打开</h2>
            <Button size="sm" variant="ghost" onClick={onShowRecent}>
              <RefreshCw className="size-3.5" /> 刷新
            </Button>
          </div>
          <div className="mt-2 overflow-hidden rounded-card border border-line bg-surface">
            {recent.length === 0 ? (
              <EmptyState compact icon={FileText} title="还没有最近文件" description="打开笔记或课件后会出现在这里。" />
            ) : (
              <div className="divide-y divide-line">
                {recent.slice(0, 8).map((item) => (
                  <button
                    key={item.path}
                    type="button"
                    onClick={() => onOpenRecent(item)}
                    className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-hover"
                  >
                    <FileIcon className="size-4 shrink-0 text-ink-3" />
                    <div className="min-w-0 flex-1">
                      <div className="truncate text-xs font-medium text-ink">{item.name}</div>
                      <div className="truncate text-[0.62rem] text-ink-3">{item.path}</div>
                    </div>
                    <span className="shrink-0 text-[0.62rem] text-ink-3">{relativeTime(item.openedAt)}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
