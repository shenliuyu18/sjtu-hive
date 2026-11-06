import type { LucideIcon } from 'lucide-react';
import {
  AlertTriangle,
  CloudDownload,
  CloudUpload,
  Database,
  Download,
  ExternalLink,
  FolderCog,
  GraduationCap,
  Import,
  Info,
  Laptop,
  LogOut,
  Mail,
  Palette,
  RefreshCw,
  RotateCcw,
  Settings2,
  ShieldCheck,
  Sparkles,
  Trash2,
  Users,
  Wifi,
} from 'lucide-react';
import { useEffect, useState, type ReactNode } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import { formatBytes } from '@/lib/format';
import type { CampusStatus, MailState } from '@/lib/models';
import type { SettingsState } from '@/lib/types';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';
import { Button } from '@/components/ui/Button';
import { Input, Select, Textarea } from '@/components/ui/Input';
import { Switch, Checkbox } from '@/components/ui/Controls';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Badge, PageHeader } from '@/components/ui/Misc';

type SectionId = 'accounts' | 'appearance' | 'general' | 'storage' | 'about';

type WebdavBackup = { name: string; size: number; modifiedAt: string };

type PendingImport =
  | { kind: 'zip'; path: string }
  | { kind: 'encrypted'; path: string }
  | { kind: 'webdav'; name: string };

const sections: { id: SectionId; label: string; icon: LucideIcon }[] = [
  { id: 'accounts', label: '账户与连接', icon: Users },
  { id: 'appearance', label: '外观', icon: Palette },
  { id: 'general', label: '通用与通知', icon: Settings2 },
  { id: 'storage', label: '存储与备份', icon: Database },
  { id: 'about', label: '关于', icon: Info },
];

function Section({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: ReactNode;
}) {
  return (
    <section className="space-y-3">
      <div>
        <h2 className="text-sm font-semibold text-ink">{title}</h2>
        {description ? <p className="mt-1 text-xs leading-relaxed text-ink-3">{description}</p> : null}
      </div>
      <div className="overflow-hidden rounded-card border border-line bg-surface shadow-[var(--shadow-card)]">
        {children}
      </div>
    </section>
  );
}

function Row({
  label,
  description,
  control,
  last,
}: {
  label: string;
  description?: string;
  control: ReactNode;
  last?: boolean;
}) {
  return (
    <div className={cn('flex items-center gap-4 px-4 py-3.5', !last && 'border-b border-line')}>
      <div className="min-w-0 flex-1">
        <div className="text-xs font-medium text-ink">{label}</div>
        {description ? <div className="mt-0.5 text-[0.68rem] leading-relaxed text-ink-3">{description}</div> : null}
      </div>
      <div className="shrink-0">{control}</div>
    </div>
  );
}

function AccountCard({
  icon: Icon,
  title,
  subtitle,
  connected,
  children,
}: {
  icon: LucideIcon;
  title: string;
  subtitle: string;
  connected?: boolean;
  children: ReactNode;
}) {
  return (
    <div className="border-b border-line p-4 last:border-b-0">
      <div className="flex items-start gap-3">
        <div className="grid size-9 shrink-0 place-items-center rounded-lg bg-surface-3 text-ink-2">
          <Icon className="size-4" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <div className="text-xs font-semibold text-ink">{title}</div>
            <span
              className={cn(
                'size-1.5 rounded-full',
                connected ? 'bg-success' : 'bg-line-strong',
              )}
            />
            <span className="text-[0.62rem] text-ink-3">{connected ? '已连接' : '未连接'}</span>
          </div>
          <div className="mt-0.5 text-[0.66rem] text-ink-3">{subtitle}</div>
          <div className="mt-2 flex flex-wrap items-center gap-2">{children}</div>
        </div>
      </div>
    </div>
  );
}

export function Settings() {
  const settings = useSettings((state) => state.state);
  const info = useSettings((state) => state.info);
  const setSetting = useSettings((state) => state.set);
  const setMany = useSettings((state) => state.setMany);
  const navigate = useApp((state) => state.navigate);
  const params = useApp((state) => state.params);
  const setOnboardingOpen = useApp((state) => state.setOnboardingOpen);
  const [section, setSection] = useState<SectionId>(
    (params.settingsSection as SectionId) || 'accounts',
  );
  const [mailState, setMailState] = useState<MailState | null>(null);
  const [campus, setCampus] = useState<CampusStatus | null>(null);
  const [courseState, setCourseState] = useState<{
    loggedIn: boolean;
    user: { username: string; email: string } | null;
  } | null>(null);
  const [cacheSize, setCacheSize] = useState<number | null>(null);
  const [courseOpen, setCourseOpen] = useState(false);
  const [courseEmail, setCourseEmail] = useState('');
  const [coursePassword, setCoursePassword] = useState('');
  const [mailOpen, setMailOpen] = useState(false);
  const [campusOpen, setCampusOpen] = useState(false);
  const [cookieText, setCookieText] = useState('');
  const [termsOpen, setTermsOpen] = useState<'terms' | 'privacy' | null>(null);
  const [mailForm, setMailForm] = useState({
    address: '',
    password: '',
    custom: false,
    imapHost: 'mail.sjtu.edu.cn',
    imapPort: '993',
    smtpHost: 'mail.sjtu.edu.cn',
    smtpPort: '465',
  });
  const [webdav, setWebdav] = useState<{
    configured: boolean;
    url?: string;
    directory?: string;
    username?: string;
  } | null>(null);
  const [webdavUrl, setWebdavUrl] = useState('');
  const [webdavDir, setWebdavDir] = useState('');
  const [webdavUser, setWebdavUser] = useState('');
  const [webdavPass, setWebdavPass] = useState('');
  const [webdavPassphrase, setWebdavPassphrase] = useState('');
  const [localPassphrase, setLocalPassphrase] = useState('');
  const [restorePassphrase, setRestorePassphrase] = useState('');
  const [webdavBackups, setWebdavBackups] = useState<WebdavBackup[]>([]);
  const [webdavListBusy, setWebdavListBusy] = useState(false);
  const [webdavListError, setWebdavListError] = useState<string | null>(null);
  const [pendingImport, setPendingImport] = useState<PendingImport | null>(null);
  const [importing, setImporting] = useState(false);
  const [restoredName, setRestoredName] = useState<string | null>(null);

  const refreshAccounts = () => {
    void Promise.allSettled([
      call<CampusStatus>('campus.status'),
      call<MailState>('mail.state'),
      call<{ loggedIn: boolean; user: { username: string; email: string } | null }>('course.state'),
      call<{ configured: boolean; url?: string; directory?: string; username?: string }>(
        'backup.webdav.state',
      ),
    ]).then((results) => {
      setCampus(results[0].status === 'fulfilled' ? results[0].value : null);
      setMailState(results[1].status === 'fulfilled' ? results[1].value : null);
      setCourseState(results[2].status === 'fulfilled' ? results[2].value : null);
      if (results[3].status === 'fulfilled') {
        const value = results[3].value;
        setWebdav(value);
        setWebdavUrl(value.url ?? '');
        setWebdavDir(value.directory ?? '');
        setWebdavUser(value.username ?? '');
      }
    });
    void call<number>('app.cacheSize')
      .then(setCacheSize)
      .catch(() => setCacheSize(null));
  };

  useEffect(() => {
    refreshAccounts();
  }, []);

  useEffect(() => {
    if (params.settingsSection) setSection(params.settingsSection as SectionId);
  }, [params.settingsSection]);

  useEffect(() => {
    if (section !== 'storage' || !webdav?.configured) return;
    let active = true;
    setWebdavListBusy(true);
    call<WebdavBackup[]>('backup.webdav.list')
      .then((items) => {
        if (active) {
          setWebdavBackups(items);
          setWebdavListError(null);
        }
      })
      .catch((error) => {
        if (active) {
          setWebdavBackups([]);
          setWebdavListError(error instanceof Error ? error.message : '读取 WebDAV 备份列表失败');
        }
      })
      .finally(() => {
        if (active) setWebdavListBusy(false);
      });
    return () => {
      active = false;
    };
  }, [section, webdav?.configured]);

  const saveWebdav = async () => {
    if (!webdavUrl.trim()) {
      toast.error('请填写 WebDAV 地址');
      return;
    }
    try {
      const next = await call<{
        configured: boolean;
        url?: string;
        directory?: string;
        username?: string;
      }>('backup.webdav.save', webdavUrl.trim(), webdavDir.trim(), webdavUser.trim(), webdavPass);
      setWebdav(next);
      setWebdavPass('');
      toast.success('WebDAV 备份配置已保存');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '保存失败');
    }
  };

  const uploadWebdav = async () => {
    if ([...webdavPassphrase].length < 8) {
      toast.error('加密口令至少 8 个字符');
      return;
    }
    try {
      const result = await toast
        .promise(call<{ name: string; size: number }>('backup.webdav.upload', webdavPassphrase), {
          loading: '正在加密并上传…',
          success: (value) => `已上传 ${value.name}（${formatBytes(value.size)}）`,
          error: (error) => (error instanceof Error ? error.message : '上传失败'),
        })
        .unwrap();
      setWebdavPassphrase('');
      return result;
    } catch {
      // toast already shown
    }
  };

  const exportEncrypted = async () => {
    if ([...localPassphrase].length < 8) {
      toast.error('加密口令至少 8 个字符');
      return;
    }
    try {
      const target = await toast
        .promise(call<string | null>('app.exportBackupEncrypted', localPassphrase), {
          loading: '正在加密导出…',
          success: (value) => (value ? `已导出到 ${value}` : '已取消导出'),
          error: (error) => (error instanceof Error ? error.message : '导出失败'),
        })
        .unwrap();
      if (target) setLocalPassphrase('');
    } catch {
      // toast already shown
    }
  };

  const pickLocalBackup = async (encrypted: boolean) => {
    try {
      const path = await call<string>('app.chooseBackup', encrypted);
      if (path) setPendingImport(encrypted ? { kind: 'encrypted', path } : { kind: 'zip', path });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '无法选择备份文件');
    }
  };

  const loadWebdavBackups = async () => {
    setWebdavListBusy(true);
    setWebdavListError(null);
    try {
      setWebdavBackups(await call<WebdavBackup[]>('backup.webdav.list'));
    } catch (error) {
      setWebdavBackups([]);
      setWebdavListError(error instanceof Error ? error.message : '读取 WebDAV 备份列表失败');
    } finally {
      setWebdavListBusy(false);
    }
  };

  const runImport = async () => {
    const target = pendingImport;
    if (!target) return;
    if (target.kind === 'webdav' && [...restorePassphrase].length < 8) {
      toast.error('请输入 WebDAV 备份的加密口令（至少 8 个字符）');
      return;
    }
    if (target.kind === 'encrypted' && [...localPassphrase].length < 8) {
      toast.error('请输入该加密备份的口令（至少 8 个字符）');
      return;
    }
    setImporting(true);
    try {
      const summary =
        target.kind === 'webdav'
          ? await call<{ name: string }>('backup.webdav.restore', target.name, restorePassphrase)
          : await call<{ name: string }>(
              'app.importBackup',
              target.path,
              target.kind === 'encrypted' ? localPassphrase : '',
              target.kind === 'encrypted',
            );
      setPendingImport(null);
      setRestoredName(summary?.name ?? (target.kind === 'webdav' ? target.name : '本地备份'));
      setLocalPassphrase('');
      setRestorePassphrase('');
      refreshAccounts();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '导入失败');
    } finally {
      setImporting(false);
    }
  };

  const saveCourse = async () => {
    if (!courseEmail.trim() || !coursePassword) {
      toast.error('请填写选课社区邮箱与密码');
      return;
    }
    try {
      await toast
        .promise(call('course.login', courseEmail.trim(), coursePassword), {
          loading: '正在登录选课社区…',
          success: '选课社区已登录',
          error: (error) => (error instanceof Error ? error.message : '登录失败'),
        })
        .unwrap();
      setCourseOpen(false);
      setCoursePassword('');
      refreshAccounts();
    } catch {
      // toast already shown
    }
  };

  const saveMail = async () => {
    try {
      await toast
        .promise(
          call('mail.configure', {
            address: mailForm.address.trim(),
            password: mailForm.password,
            imapHost: mailForm.custom ? mailForm.imapHost.trim() : 'mail.sjtu.edu.cn',
            imapPort: Number(mailForm.custom ? mailForm.imapPort : 993),
            smtpHost: mailForm.custom ? mailForm.smtpHost.trim() : 'mail.sjtu.edu.cn',
            smtpPort: Number(mailForm.custom ? mailForm.smtpPort : 465),
          }),
          {
            loading: '正在连接邮箱…',
            success: '邮箱配置成功',
            error: (error) => (error instanceof Error ? error.message : '连接失败'),
          },
        )
        .unwrap();
      setMailOpen(false);
      setMailForm((value) => ({ ...value, password: '' }));
      refreshAccounts();
    } catch {
      // toast already shown
    }
  };

  const loginCampus = async () => {
    try {
      await toast
        .promise(call('campus.login'), {
          loading: '请在登录窗口完成交大登录，成功后会自动关闭…',
          success: '教学网登录成功',
          error: (error) => (error instanceof Error ? error.message : '登录失败'),
        })
        .unwrap();
      setCampusOpen(false);
      refreshAccounts();
    } catch {
      // toast already shown
    }
  };

  const importCookies = async () => {
    if (!cookieText.trim()) return;
    try {
      await toast
        .promise(call('campus.importCookies', cookieText.trim()), {
          loading: '正在保存 Cookie…',
          success: '校园会话已保存',
          error: (error) => (error instanceof Error ? error.message : '保存失败'),
        })
        .unwrap();
      setCookieText('');
      setCampusOpen(false);
      refreshAccounts();
    } catch {
      // toast already shown
    }
  };

  const clearCache = async () => {
    try {
      const result = await call<{ freed: number }>('app.clearCache');
      toast.success(`已清理缓存，释放 ${formatBytes(result?.freed ?? 0)}`);
      refreshAccounts();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '清理失败');
    }
  };

  const navOptions: { id: string; label: string }[] = [
    { id: 'canvas', label: '资料' },
    { id: 'mail', label: '邮箱' },
    { id: 'timetable', label: '日程' },
    { id: 'classroom', label: '课堂视频' },
    { id: 'shuiyuan', label: '水源社区' },
    { id: 'todo', label: 'TODO' },
    { id: 'workspace', label: '工作台' },
  ];

  const toggleNav = (id: string, visible: boolean) => {
    const hidden = new Set(settings.navHidden);
    if (visible) hidden.delete(id);
    else hidden.add(id);
    void setSetting('navHidden', [...hidden]);
  };

  const update = (key: keyof SettingsState, value: SettingsState[keyof SettingsState]) => {
    void setSetting(
      key as keyof Pick<
        SettingsState,
        | 'theme'
        | 'fontScale'
        | 'zoom'
        | 'openAtLogin'
        | 'campusNotifications'
        | 'taskNotifications'
        | 'experiments'
        | 'navHidden'
        | 'mailPollMinutes'
        | 'autoSyncMinutes'
        | 'sidebarCollapsed'
      >,
      value as never,
    ).catch((error) => toast.error(error instanceof Error ? error.message : '保存设置失败'));
  };

  return (
    <div className="scroll-thin h-full overflow-y-auto">
      <div className="mx-auto max-w-[78rem]">
        <PageHeader
          title="设置"
          description="账户、外观、通知与本地数据都在这里管理。所有凭据只保存在这台电脑。"
          actions={
            <Button variant="secondary" onClick={() => navigate('dashboard')}>
              返回概览
            </Button>
          }
        />
        <div className="grid grid-cols-1 gap-5 px-6 pb-10 lg:grid-cols-[13rem_1fr]">
          <nav className="h-fit space-y-1 lg:sticky lg:top-4">
            {sections.map((item) => {
              const Icon = item.icon;
              return (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => setSection(item.id)}
                  className={cn(
                    'flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-xs font-medium transition-colors',
                    section === item.id
                      ? 'bg-selected text-accent'
                      : 'text-ink-2 hover:bg-hover hover:text-ink',
                  )}
                >
                  <Icon className="size-4" />
                  {item.label}
                </button>
              );
            })}
          </nav>

          <div className="min-w-0 space-y-6">
            {section === 'accounts' ? (
              <>
                <Section title="账户与连接" description="校园 jAccount 只需登录一次；Canvas、水源社区、云盘、课堂视频与传承交大都会自动复用。邮箱与选课社区使用各自的账号。">
                  <AccountCard
                    icon={Wifi}
                    title="校园 jAccount（教学信息服务网）"
                    subtitle="i.sjtu.edu.cn · Canvas / 水源社区 / 云盘 / 课堂视频 / 传承交大共用"
                    connected={Boolean(campus?.connected)}
                  >
                    <Button size="sm" variant="primary" onClick={() => setCampusOpen(true)}>
                      {campus?.connected ? '重新登录' : '登录教学网'}
                    </Button>
                    {campus?.connected ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() =>
                          void call('campus.logout').then(() => {
                            toast.success('已清除校园会话');
                            refreshAccounts();
                          })
                        }
                      >
                        <LogOut className="size-3.5" /> 退出
                      </Button>
                    ) : null}
                    {campus?.connected ? (
                      <span className="text-[0.66rem] text-ink-3">{campus.cookies} 个 Cookie</span>
                    ) : null}
                  </AccountCard>
                  <AccountCard
                    icon={Mail}
                    title="校园邮箱"
                    subtitle="IMAP / SMTP 邮箱密码；邮件协议不支持 jAccount SSO"
                    connected={Boolean(mailState?.account)}
                  >
                    <Button size="sm" variant="primary" onClick={() => setMailOpen(true)}>
                      {mailState?.account ? '重新配置' : '配置邮箱'}
                    </Button>
                    {mailState?.account ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() =>
                          void call('mail.disconnect').then(() => {
                            toast.success('已断开邮箱');
                            refreshAccounts();
                          })
                        }
                      >
                        <LogOut className="size-3.5" /> 断开
                      </Button>
                    ) : null}
                    {mailState?.account ? (
                      <span className="text-[0.66rem] text-ink-3">{mailState.account.address}</span>
                    ) : null}
                  </AccountCard>
                  <AccountCard
                    icon={GraduationCap}
                    title="选课社区"
                    subtitle="course.sjtu.plus · 使用社区账号（邮箱 / 密码），与 jAccount 相互独立"
                    connected={Boolean(courseState?.loggedIn)}
                  >
                    <Button size="sm" variant="primary" onClick={() => setCourseOpen(true)}>
                      {courseState?.loggedIn ? '切换账号' : '登录选课社区'}
                    </Button>
                    {courseState?.loggedIn ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() =>
                          void call('course.logout').then(() => {
                            toast.success('已退出选课社区');
                            refreshAccounts();
                          })
                        }
                      >
                        <LogOut className="size-3.5" /> 退出
                      </Button>
                    ) : null}
                    {courseState?.user ? (
                      <span className="text-[0.66rem] text-ink-3">
                        {courseState.user.username || courseState.user.email}
                      </span>
                    ) : null}
                  </AccountCard>
                </Section>
                <div className="rounded-card border border-line bg-surface-2 p-4 text-[0.68rem] leading-relaxed text-ink-3">
                  <ShieldCheck className="mb-1.5 size-4 text-success" />
                  jAccount Cookie、邮箱密码与选课社区登录凭据均使用 Windows DPAPI 加密保存在本机，不会上传到任何服务器。
                </div>
              </>
            ) : null}

            {section === 'appearance' ? (
              <>
                <Section title="主题" description="跟随系统或固定为浅色 / 深色。">
                  <div className="grid grid-cols-3 gap-3 p-4">
                    {[
                      { id: 'system', label: '跟随系统', icon: Laptop },
                      { id: 'light', label: '浅色', icon: Sparkles },
                      { id: 'dark', label: '深色', icon: Palette },
                    ].map((item) => {
                      const Icon = item.icon;
                      return (
                        <button
                          key={item.id}
                          type="button"
                          onClick={() => update('theme', item.id as SettingsState['theme'])}
                          className={cn(
                            'flex flex-col items-start gap-2 rounded-xl border p-3 text-left transition-colors',
                            settings.theme === item.id
                              ? 'border-accent bg-accent-soft'
                              : 'border-line bg-surface-2 hover:border-line-strong',
                          )}
                        >
                          <Icon className={cn('size-4', settings.theme === item.id ? 'text-accent' : 'text-ink-3')} />
                          <span className="text-xs font-medium text-ink">{item.label}</span>
                          <span
                            className={cn(
                              'h-8 w-full rounded-md border',
                              item.id === 'dark'
                                ? 'border-neutral-700 bg-neutral-900'
                                : item.id === 'light'
                                  ? 'border-neutral-200 bg-white'
                                  : 'border-neutral-300 bg-gradient-to-r from-white to-neutral-900',
                            )}
                          />
                        </button>
                      );
                    })}
                  </div>
                </Section>
                <Section title="文字与缩放">
                  <Row
                    label="字号"
                    description={`默认跟随系统字号（当前基准 ${settings.fontBasePx.toFixed(1)}px），此处按基准增减。`}
                    control={
                      <div className="flex items-center gap-2">
                        <input
                          type="range"
                          min={70}
                          max={180}
                          step={5}
                          value={settings.fontScale}
                          onChange={(event) => update('fontScale', Number(event.target.value))}
                          className="w-32 accent-[var(--accent)]"
                        />
                        <span className="w-16 text-right text-xs tabular-nums text-ink-2">
                          {settings.fontScale}% · {((settings.fontBasePx * settings.fontScale) / 100).toFixed(1)}px
                        </span>
                        <Button
                          size="sm"
                          variant={settings.fontScale === 100 ? 'secondary' : 'ghost'}
                          disabled={settings.fontScale === 100}
                          onClick={() => update('fontScale', 100)}
                        >
                          跟随系统
                        </Button>
                      </div>
                    }
                  />
                  <Row
                    label="界面缩放"
                    description="适合高分辨率屏幕；不影响系统其他应用。"
                    last
                    control={
                      <div className="flex items-center gap-2">
                        <input
                          type="range"
                          min={0.8}
                          max={1.5}
                          step={0.05}
                          value={settings.zoom}
                          onChange={(event) => update('zoom', Number(event.target.value))}
                          className="w-32 accent-[var(--accent)]"
                        />
                        <span className="w-10 text-right text-xs tabular-nums text-ink-2">
                          {Math.round(settings.zoom * 100)}%
                        </span>
                      </div>
                    }
                  />
                </Section>
              </>
            ) : null}

            {section === 'general' ? (
              <>
                <Section title="启动与同步">
                  <Row
                    label="开机自动启动"
                    description="登录 Windows 后自动打开 SJTU。"
                    control={
                      <Switch
                        checked={settings.openAtLogin}
                        onCheckedChange={(value) => update('openAtLogin', value)}
                      />
                    }
                  />
                  <Row
                    label="点击关闭按钮时"
                    description="最小化到系统托盘后可从托盘恢复，右键托盘图标可退出。"
                    control={
                      <Select
                        value={settings.closeAction}
                        onChange={(event) => update('closeAction', event.target.value)}
                      >
                        <option value="tray">最小化到托盘</option>
                        <option value="quit">直接退出应用</option>
                      </Select>
                    }
                  />
                  <Row
                    label="邮件自动同步"
                    description="应用运行时按间隔检查新邮件。"
                    control={
                      <Select
                        value={String(settings.mailPollMinutes)}
                        onChange={(event) => update('mailPollMinutes', Number(event.target.value))}
                      >
                        {[1, 5, 10, 15, 30].map((value) => (
                          <option key={value} value={value}>
                            每 {value} 分钟
                          </option>
                        ))}
                      </Select>
                    }
                  />
                  <Row
                    label="Canvas 自动同步"
                    description="后台刷新课程与作业状态。"
                    last
                    control={
                      <Select
                        value={String(settings.autoSyncMinutes)}
                        onChange={(event) => update('autoSyncMinutes', Number(event.target.value))}
                      >
                        {[15, 30, 60, 120].map((value) => (
                          <option key={value} value={value}>
                            每 {value} 分钟
                          </option>
                        ))}
                      </Select>
                    }
                  />
                </Section>
                <Section title="通知">
                  <Row
                    label="校园通知"
                    description="新邮件、Canvas 作业与新公告。"
                    control={
                      <Switch
                        checked={settings.campusNotifications}
                        onCheckedChange={(value) => update('campusNotifications', value)}
                      />
                    }
                  />
                  <Row
                    label="任务提醒"
                    description="TODO 到期前 24 小时与 2 小时提醒。"
                    control={
                      <Switch
                        checked={settings.taskNotifications}
                        onCheckedChange={(value) => update('taskNotifications', value)}
                      />
                    }
                  />
                  <Row
                    label="系统通知"
                    description="通过任务栏托盘图标投递 Windows 通知。"
                    last
                    control={
                      <Button
                        size="sm"
                        onClick={() =>
                          void call('app.testNotification')
                            .then(() => toast.success('已发送系统通知，请查看屏幕右下角'))
                            .catch((error) =>
                              toast.error(error instanceof Error ? error.message : '发送失败'),
                            )
                        }
                      >
                        发送测试通知
                      </Button>
                    }
                  />
                </Section>
                <Section title="试验功能" description="功能仍在完善，默认关闭；可以随时开关。">
                  <Row
                    label="水源社区"
                    description="在侧栏显示水源社区入口；关闭后立即隐藏。"
                    control={
                      <Switch
                        checked={settings.experiments.shuiyuan}
                        onCheckedChange={(value) =>
                          update('experiments', { ...settings.experiments, shuiyuan: value })
                        }
                      />
                    }
                  />
                  <Row
                    label="课堂视频下载"
                    description="允许把课堂录像保存到本地。"
                    last
                    control={
                      <Switch
                        checked={settings.experiments.videoDownload}
                        onCheckedChange={(value) =>
                          update('experiments', { ...settings.experiments, videoDownload: value })
                        }
                      />
                    }
                  />
                </Section>
                <Section title="导航入口" description="隐藏不常用的侧栏入口，能力不会关闭。">
                  <div className="grid grid-cols-2 gap-3 p-4">
                    {navOptions.map((item) => (
                      <Checkbox
                        key={item.id}
                        checked={!settings.navHidden.includes(item.id)}
                        onCheckedChange={(value) => toggleNav(item.id, value)}
                        label={item.label}
                      />
                    ))}
                  </div>
                </Section>
              </>
            ) : null}

            {section === 'storage' ? (
              <>
                <Section title="缓存" description="课件、邮件正文、附件与预览产生的本地缓存。">
                  <Row
                    label="当前缓存占用"
                    description="清理不会删除账号、笔记、项目文件或已下载的视频。"
                    control={
                      <div className="flex items-center gap-2">
                        <span className="text-xs tabular-nums text-ink-2">
                          {cacheSize === null ? '—' : formatBytes(cacheSize)}
                        </span>
                        <Button size="sm" onClick={() => void clearCache()}>
                          <Trash2 className="size-3.5" /> 清理
                        </Button>
                      </div>
                    }
                  />
                  <Row
                    label="数据目录"
                    description={info?.dataDir ?? ''}
                    control={
                      <Button size="sm" onClick={() => void call('app.openData')}>
                        <FolderCog className="size-3.5" /> 打开
                      </Button>
                    }
                  />
                  <Row
                    label="日志目录"
                    description={info?.logDir ?? ''}
                    last
                    control={
                      <Button size="sm" onClick={() => void call('app.openLogs')}>
                        <FolderCog className="size-3.5" /> 打开
                      </Button>
                    }
                  />
                </Section>
                <Section title="备份" description="自动备份数据库与登录凭据；加密包可在换机后完整恢复。">
                  <Row
                    label="自动备份"
                    description="每 15 分钟保存到本机；数据目录缺失时，应用启动会自动恢复最近一次备份。"
                    control={<Badge variant="success">已启用</Badge>}
                  />
                  <Row
                    label="导出本地数据"
                    description="生成不含登录凭据的 .zip，换机后需要重新登录各服务。"
                    control={
                      <Button
                        size="sm"
                        onClick={() =>
                          toast.promise(call('app.exportBackup'), {
                            loading: '正在导出…',
                            success: (value) =>
                              value ? `已导出到 ${String(value)}` : '已取消导出',
                            error: (error) => (error instanceof Error ? error.message : '导出失败'),
                          })
                        }
                      >
                        <Download className="size-3.5" /> 导出
                      </Button>
                    }
                  />
                  <Row
                    label="导入本地数据"
                    description="从 .zip 恢复数据库、设置与项目；不含登录凭据，导入前会自动保存本机快照。"
                    last
                    control={
                      <Button size="sm" onClick={() => void pickLocalBackup(false)}>
                        <Import className="size-3.5" /> 导入
                      </Button>
                    }
                  />
                  <div className="border-t border-line p-4">
                    <div className="text-xs font-medium text-ink">加密备份</div>
                    <p className="mt-1 text-[0.68rem] leading-relaxed text-ink-3">
                      加密导出生成包含登录凭据的 *.hivebackup；导入加密备份时必须输入导出时的同一口令。
                      恢复会覆盖当前数据库、设置与登录凭据。
                    </p>
                    <div className="mt-3 flex flex-wrap items-center gap-2">
                      <Input
                        type="password"
                        value={localPassphrase}
                        onChange={(event) => setLocalPassphrase(event.target.value)}
                        placeholder="加密口令（至少 8 位，不保存）"
                        className="min-w-[14rem] flex-1"
                      />
                      <Button
                        size="sm"
                        variant="secondary"
                        onClick={() => void exportEncrypted()}
                        disabled={localPassphrase.length < 8}
                      >
                        <Download className="size-3.5" /> 加密导出
                      </Button>
                      <Button size="sm" onClick={() => void pickLocalBackup(true)}>
                        <Import className="size-3.5" /> 导入加密备份
                      </Button>
                    </div>
                  </div>
                </Section>
                <Section
                  title="WebDAV 备份"
                  description="把包含登录凭据的完整备份加密后上传到你自己的 WebDAV；加密口令不会保存。"
                >
                  <div className="m-4 rounded-xl border border-warn/30 bg-warn/10 p-4 text-[0.68rem] leading-relaxed text-ink-2">
                    <div className="mb-1.5 flex items-center gap-2 font-semibold text-warn">
                      <AlertTriangle className="size-4" /> 风险提醒
                    </div>
                    <ul className="list-disc space-y-1 pl-4">
                      <li>WebDAV 服务器由你自行提供，其可用性、隐私与访问控制由你负责。</li>
                      <li>
                        备份会先用 AES-256-GCM 和口令加密再上传；口令不保存在应用或服务器，丢失后无法恢复。
                      </li>
                      <li>不要把口令与备份文件放在同一位置；公共或共享 WebDAV 仍可能被删除、覆盖或篡改。</li>
                      <li>
                        备份包含 jAccount Cookie、邮箱密码、Canvas Token、水源社区 API Key 等登录凭据，请像保管密码一样保管加密口令。
                      </li>
                      <li>备份不包含 WebDAV 自身的登录密码；换机恢复后需重新配置 WebDAV。</li>
                    </ul>
                  </div>
                  <div className="space-y-3 border-t border-line p-4">
                    <div className="space-y-2">
                      <Input
                        value={webdavUrl}
                        onChange={(event) => setWebdavUrl(event.target.value)}
                        placeholder="WebDAV 地址，例如 https://dav.example.com/remote.php/dav/files/me"
                      />
                      <div className="grid gap-2 md:grid-cols-3">
                        <Input
                          value={webdavDir}
                          onChange={(event) => setWebdavDir(event.target.value)}
                          placeholder="远程目录（可选，需已存在）"
                        />
                        <Input
                          value={webdavUser}
                          onChange={(event) => setWebdavUser(event.target.value)}
                          placeholder="用户名"
                          autoComplete="username"
                        />
                        <Input
                          type="password"
                          value={webdavPass}
                          onChange={(event) => setWebdavPass(event.target.value)}
                          placeholder={webdav?.configured ? '密码已保存（留空保持不变）' : '密码'}
                          autoComplete="current-password"
                        />
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-2">
                      <Button size="sm" variant="secondary" onClick={() => void saveWebdav()}>
                        保存配置
                      </Button>
                      {webdav?.configured ? (
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() =>
                            void call('backup.webdav.clear').then(() => {
                              setWebdav({ configured: false });
                              setWebdavUrl('');
                              setWebdavDir('');
                              setWebdavUser('');
                              setWebdavPass('');
                              toast.success('已清除 WebDAV 配置');
                            })
                          }
                        >
                          <Trash2 className="size-3.5" /> 清除
                        </Button>
                      ) : null}
                      {webdav?.configured ? (
                        <span className="text-[0.64rem] text-ink-3">
                          已配置 {webdav.url}
                          {webdav.directory ? ` / ${webdav.directory}` : ''}
                        </span>
                      ) : null}
                    </div>
                    <div className="border-t border-line pt-3">
                      <div className="text-xs font-medium text-ink">加密并上传</div>
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        <Input
                          type="password"
                          value={webdavPassphrase}
                          onChange={(event) => setWebdavPassphrase(event.target.value)}
                          placeholder="加密口令（至少 8 个字符，不保存）"
                          className="min-w-[16rem] flex-1"
                        />
                        <Button
                          size="sm"
                          onClick={() => void uploadWebdav()}
                          disabled={!webdav?.configured || webdavPassphrase.length < 8}
                        >
                          <CloudUpload className="size-3.5" /> 加密上传
                        </Button>
                      </div>
                      <p className="mt-2 text-[0.64rem] leading-relaxed text-ink-3">
                        生成 `*.hivebackup` 文件后通过 WebDAV PUT 上传；包内包含数据库、设置、项目列表与登录凭据（jAccount
                        Cookie、邮箱密码、Canvas Token、水源社区 API Key 等），整个文件已用口令加密。
                      </p>
                    </div>
                    <div className="border-t border-line pt-3">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="text-xs font-medium text-ink">从 WebDAV 恢复</div>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => void loadWebdavBackups()}
                          disabled={webdavListBusy}
                        >
                          <RefreshCw className={cn('size-3.5', webdavListBusy && 'animate-spin')} /> 刷新列表
                        </Button>
                      </div>
                      <div className="mt-2 flex flex-wrap items-center gap-2">
                        <Input
                          type="password"
                          value={restorePassphrase}
                          onChange={(event) => setRestorePassphrase(event.target.value)}
                          placeholder="该备份的加密口令（至少 8 个字符）"
                          className="min-w-[16rem] flex-1"
                        />
                      </div>
                      <div className="mt-2 space-y-1.5">
                        {webdavListError ? (
                          <p className="text-[0.64rem] leading-relaxed text-warn">{webdavListError}</p>
                        ) : null}
                        {webdavBackups.length === 0 && !webdavListError ? (
                          <p className="text-[0.64rem] leading-relaxed text-ink-3">
                            {webdavListBusy ? '正在读取远程备份列表…' : '远程目录中没有找到 *.hivebackup 备份。'}
                          </p>
                        ) : null}
                        {webdavBackups.length > 0 ? (
                          webdavBackups.map((item) => (
                            <div
                              key={item.name}
                              className="flex items-center gap-3 rounded-lg border border-line bg-surface-2 px-3 py-2"
                            >
                              <div className="min-w-0 flex-1">
                                <div className="truncate text-[0.7rem] font-medium text-ink">{item.name}</div>
                                <div className="text-[0.62rem] text-ink-3">
                                  {formatBytes(item.size)}
                                  {item.modifiedAt ? ` · ${item.modifiedAt}` : ''}
                                </div>
                              </div>
                              <Button
                                size="sm"
                                variant="secondary"
                                disabled={restorePassphrase.length < 8}
                                onClick={() => setPendingImport({ kind: 'webdav', name: item.name })}
                              >
                                <CloudDownload className="size-3.5" /> 恢复
                              </Button>
                            </div>
                          ))
                        ) : null}
                      </div>
                    </div>
                  </div>
                </Section>
              </>
            ) : null}

            {section === 'about' ? (
              <>
                <Section title="SJTU Hive" description="本地优先的上海交大校园桌面。">
                  <Row
                    label="版本"
                    description={`${info?.name ?? 'SJTU Hive'} · Wails ${info?.wailsVersion ?? 'v2.16.0'}`}
                    control={<Badge variant="accent">v{info?.version ?? '0.1.0'}</Badge>}
                  />
                  <Row
                    label="本地数据"
                    description={info?.dataDir ?? ''}
                    last
                    control={
                      <Button size="sm" onClick={() => void call('app.openData')}>
                        <ExternalLink className="size-3.5" /> 打开
                      </Button>
                    }
                  />
                </Section>
                <Section title="条款与隐私">
                  <Row
                    label="使用条款"
                    description="独立第三方工具，与上海交通大学无隶属关系；功能按现状提供。"
                    control={
                      <Button size="sm" onClick={() => setTermsOpen('terms')}>
                        查看
                      </Button>
                    }
                  />
                  <Row
                    label="隐私说明"
                    description="无遥测、无云端中转；数据只保存在本机，联网仅直达你主动使用的服务。"
                    control={
                      <Button size="sm" onClick={() => setTermsOpen('privacy')}>
                        查看
                      </Button>
                    }
                  />
                  <Row
                    label="新手引导"
                    description="关闭后不会再自动弹出；这里可以随时重新打开。"
                    last
                    control={
                      <Button
                        size="sm"
                        onClick={() => {
                          update('onboardingCompleted', false);
                          setOnboardingOpen(true);
                        }}
                      >
                        重新打开
                      </Button>
                    }
                  />
                </Section>
              </>
            ) : null}
          </div>
        </div>
      </div>

      <Dialog open={courseOpen} onOpenChange={setCourseOpen}>
        <DialogContent
          title="登录选课社区"
          description="选课社区使用自己的账号，与校园 jAccount 无关；凭据使用 Windows DPAPI 加密保存在本机。"
          footer={
            <>
              <Button variant="ghost" onClick={() => setCourseOpen(false)}>
                取消
              </Button>
              <Button
                variant="primary"
                onClick={() => void saveCourse()}
                disabled={!courseEmail.trim() || !coursePassword}
              >
                登录
              </Button>
            </>
          }
        >
          <label className="text-xs font-medium text-ink-2">邮箱</label>
          <Input
            className="mt-1.5"
            value={courseEmail}
            onChange={(event) => setCourseEmail(event.target.value)}
            placeholder="you@example.com"
            autoComplete="username"
            autoFocus
          />
          <label className="mt-3 block text-xs font-medium text-ink-2">密码</label>
          <Input
            className="mt-1.5"
            type="password"
            value={coursePassword}
            onChange={(event) => setCoursePassword(event.target.value)}
            placeholder="选课社区密码"
            autoComplete="current-password"
          />
        </DialogContent>
      </Dialog>

      <Dialog open={mailOpen} onOpenChange={setMailOpen}>
        <DialogContent
          title="配置邮箱"
          description="支持交大邮箱与任意第三方 IMAP / SMTP 邮箱，SSL/TLS 固定开启。"
          wide
          footer={
            <>
              <Button variant="ghost" onClick={() => setMailOpen(false)}>
                取消
              </Button>
              <Button
                variant="primary"
                onClick={() => void saveMail()}
                disabled={!mailForm.address.trim() || !mailForm.password}
              >
                保存并连接
              </Button>
            </>
          }
        >
          <div className="grid gap-3 sm:grid-cols-2">
            <div>
              <label className="text-xs font-medium text-ink-2">邮箱地址</label>
              <Input
                className="mt-1.5"
                value={mailForm.address}
                onChange={(event) => setMailForm((value) => ({ ...value, address: event.target.value }))}
                placeholder="you@sjtu.edu.cn"
                autoFocus
              />
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">密码 / 授权码</label>
              <Input
                className="mt-1.5"
                type="password"
                value={mailForm.password}
                onChange={(event) => setMailForm((value) => ({ ...value, password: event.target.value }))}
                placeholder="邮箱密码或授权码"
              />
            </div>
          </div>
          <Checkbox
            className="mt-4"
            checked={mailForm.custom}
            onCheckedChange={(value) => setMailForm((form) => ({ ...form, custom: value }))}
            label="使用第三方邮箱服务器"
          />
          {mailForm.custom ? (
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              <div>
                <label className="text-xs font-medium text-ink-2">IMAP 服务器</label>
                <Input
                  className="mt-1.5"
                  value={mailForm.imapHost}
                  onChange={(event) => setMailForm((value) => ({ ...value, imapHost: event.target.value }))}
                />
              </div>
              <div>
                <label className="text-xs font-medium text-ink-2">IMAP 端口</label>
                <Input
                  className="mt-1.5"
                  value={mailForm.imapPort}
                  onChange={(event) => setMailForm((value) => ({ ...value, imapPort: event.target.value }))}
                />
              </div>
              <div>
                <label className="text-xs font-medium text-ink-2">SMTP 服务器</label>
                <Input
                  className="mt-1.5"
                  value={mailForm.smtpHost}
                  onChange={(event) => setMailForm((value) => ({ ...value, smtpHost: event.target.value }))}
                />
              </div>
              <div>
                <label className="text-xs font-medium text-ink-2">SMTP 端口</label>
                <Input
                  className="mt-1.5"
                  value={mailForm.smtpPort}
                  onChange={(event) => setMailForm((value) => ({ ...value, smtpPort: event.target.value }))}
                />
              </div>
            </div>
          ) : null}
          <p className="mt-4 text-[0.68rem] leading-relaxed text-ink-3">
            密码使用 Windows DPAPI 加密保存在本机。首次同步会建立本地邮件索引，之后打开邮件时再按需获取正文与附件。
          </p>
        </DialogContent>
      </Dialog>

      <Dialog open={campusOpen} onOpenChange={setCampusOpen}>
        <DialogContent
          title="登录教学信息服务网"
          description="登录窗口使用独立的 Edge 配置文件；成功后自动读取 .sjtu.edu.cn Cookie，加密保存在本机。"
          wide
          footer={
            <>
              <Button variant="ghost" onClick={() => setCampusOpen(false)}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void loginCampus()}>
                <Wifi className="size-4" /> 打开登录窗口
              </Button>
            </>
          }
        >
          <div className="flex items-start gap-3 rounded-lg border border-line bg-surface-2 p-3 text-[0.68rem] leading-relaxed text-ink-2">
            <ShieldCheck className="mt-0.5 size-4 shrink-0 text-success" />
            <div>
              登录过程由交大官方页面完成，应用不会接触你的 jAccount 密码。登录成功后请回到本应用；如果窗口没有自动关闭，直接关闭即可。
            </div>
          </div>
          <div className="mt-4">
            <div className="text-xs font-medium text-ink-2">登录窗口打不开？粘贴 Cookie</div>
            <p className="mt-1 text-[0.66rem] leading-relaxed text-ink-3">
              在浏览器登录 i.sjtu.edu.cn 后，从开发者工具复制 Cookie 请求头（name=value; …）粘贴到下面。
            </p>
            <Textarea
              className="mt-2 h-24 font-mono text-[0.7rem]"
              value={cookieText}
              onChange={(event) => setCookieText(event.target.value)}
              placeholder="JAAuthCookie=...; JSESSIONID=..."
            />
            <div className="mt-2 flex justify-end">
              <Button size="sm" onClick={() => void importCookies()} disabled={!cookieText.trim()}>
                保存 Cookie
              </Button>
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={pendingImport !== null} onOpenChange={(open) => !open && !importing && setPendingImport(null)}>
        <DialogContent
          title="确认导入备份"
          description="导入会覆盖当前数据库、设置和登录凭据。导入前会自动保存一份本机快照，可在数据目录的 backups 文件夹找回。"
          footer={
            <>
              <Button variant="ghost" onClick={() => setPendingImport(null)} disabled={importing}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void runImport()} disabled={importing}>
                <Import className="size-4" /> {importing ? '正在导入…' : '开始导入'}
              </Button>
            </>
          }
        >
          <div className="break-all rounded-lg border border-line bg-surface-2 p-3 text-xs text-ink-2">
            {pendingImport?.kind === 'webdav' ? pendingImport.name : pendingImport?.path}
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={restoredName !== null} onOpenChange={(open) => !open && setRestoredName(null)}>
        <DialogContent
          title="备份已恢复"
          description="数据已写入本机。重启应用后，所有模块会使用恢复后的登录凭据与设置重新连接。"
          footer={
            <>
              <Button variant="ghost" onClick={() => setRestoredName(null)}>
                稍后重启
              </Button>
              <Button variant="primary" onClick={() => void call('app.restart')}>
                <RotateCcw className="size-4" /> 立即重启
              </Button>
            </>
          }
        >
          <div className="break-all rounded-lg border border-line bg-surface-2 p-3 text-xs text-ink-2">
            {restoredName}
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={termsOpen !== null} onOpenChange={(open) => !open && setTermsOpen(null)}>
        <DialogContent title={termsOpen === 'privacy' ? '隐私说明' : '使用条款'} wide>
          <div className="selectable space-y-4 text-xs leading-relaxed text-ink-2">
            {termsOpen === 'privacy' ? (
              <>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">本地优先</h3>
                  <p>
                    本应用没有账号系统、不接入遥测或统计服务，也不设云端中转。开发者不收集、不上传你的个人信息、账号凭据、邮件、笔记或使用记录。所有功能均在本机运行。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">本机保存的数据</h3>
                  <p>
                    为让你无需重复登录，应用会把以下内容加密保存在本机：jAccount Cookie、校园邮箱密码、Canvas Token（回退）、水源社区 API Key（回退）、传承交大令牌、选课社区会话，以及数据库、设置、笔记与项目索引、缓存文件。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">加密与备份</h3>
                  <p>
                    上述凭据使用 Windows DPAPI 加密，只能由当前 Windows 用户解密。普通导出备份不含登录凭据；加密备份（*.hivebackup）使用 scrypt 派生密钥并以 AES-256-GCM 加密，口令不会被保存。WebDAV 备份上传到你自行配置的服务器，应用不保留副本。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">网络请求范围</h3>
                  <p>
                    联网只发生在你主动使用对应功能时，由本机直接连接相应服务，例如 i.sjtu.edu.cn、oc.sjtu.edu.cn、mail.sjtu.edu.cn（IMAP/SMTP）、my.sjtu.edu.cn、calendar.sjtu.edu.cn、v.sjtu.edu.cn、shuiyuan.sjtu.edu.cn、pan.sjtu.edu.cn、course.sjtu.plus、传承交大站点，以及你填写的 WebDAV 地址。请求不经过开发者服务器，也不会发送到上述服务之外的地址。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">日志与缓存</h3>
                  <p>
                    本地日志仅用于排查运行错误，可能包含请求地址、状态码与错误信息，不包含邮件正文、帖子内容或明文凭据；日志不会上传。缓存与日志可在设置中随时清理。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">卸载与删除</h3>
                  <p>
                    卸载应用不会自动删除数据目录。如需彻底清除，请手动删除 %APPDATA%\SJTU Hive、%LOCALAPPDATA%\SJTU Hive 与你自己创建的备份文件。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">第三方内容</h3>
                  <p>
                    邮件与社区正文在隔离环境中渲染，外部链接交由系统浏览器打开。第三方站点的数据处理遵循其各自的隐私政策。
                  </p>
                </section>
              </>
            ) : (
              <>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">性质与关系</h3>
                  <p>
                    本应用是独立开发的第三方工具，与上海交通大学及其附属单位不存在隶属、合作或授权关系。应用名称与文案中的校名仅用于说明适用场景。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">功能范围</h3>
                  <p>
                    应用用于在本机聚合课程资料、校园邮箱、日程、课堂录像、水源社区、选课社区与传承交大等信息。不提供自动选课、抢课、场馆预约、刷课、代考等违反学校规定的功能，也不会绕过任何官方流程或权限控制。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">账号与凭据</h3>
                  <p>
                    登录由你在官方页面完成，凭据仅保存在本机。你应妥善保管设备、Windows 账户与备份口令；因设备丢失、共享账户或口令泄露造成的损失由使用者自行承担。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">内容与版权</h3>
                  <p>
                    课程资料、课堂录像、邮件与社区内容的权利归原权利人所有，仅供个人学习参考，不得传播、转载或用于商业用途。应用自身的代码与界面归开发者所有。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">第三方服务</h3>
                  <p>
                    校园系统与第三方站点可能随时调整接口、限流或停止服务。应用按“现状”提供，不保证功能持续可用、数据完整或结果准确；遇到接口变化时可能暂时无法使用。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">使用规范</h3>
                  <p>
                    你应遵守学校网络与信息安全规定、社区规范及适用法律，不得利用本应用进行批量抓取、攻击、骚扰、绕过权限或其他不当行为。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">免责声明</h3>
                  <p>
                    在法律允许的范围内，开发者不对因使用或无法使用本应用造成的成绩、账号、数据或设备损失承担责任。请自行定期备份重要笔记与资料。
                  </p>
                </section>
                <section>
                  <h3 className="mb-1 font-semibold text-ink">变更与终止</h3>
                  <p>
                    条款可能随功能调整而更新，更新后继续使用视为接受新条款。你可以随时停止使用并删除本应用及其数据。本条款适用中华人民共和国法律。
                  </p>
                </section>
              </>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
