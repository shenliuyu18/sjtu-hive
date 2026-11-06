import type { LucideIcon } from 'lucide-react';
import {
  Database,
  Download,
  ExternalLink,
  FolderCog,
  Info,
  KeyRound,
  Laptop,
  LogOut,
  Mail,
  Palette,
  RefreshCw,
  Server,
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
import type { CampusStatus, CanvasState, MailState } from '@/lib/models';
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
  const [canvasState, setCanvasState] = useState<CanvasState | null>(null);
  const [mailState, setMailState] = useState<MailState | null>(null);
  const [campus, setCampus] = useState<CampusStatus | null>(null);
  const [shuiyuanState, setShuiyuanState] = useState<{ connected: boolean; username: string | null } | null>(null);
  const [cacheSize, setCacheSize] = useState<number | null>(null);
  const [canvasOpen, setCanvasOpen] = useState(false);
  const [canvasToken, setCanvasToken] = useState('');
  const [mailOpen, setMailOpen] = useState(false);
  const [campusOpen, setCampusOpen] = useState(false);
  const [cookieText, setCookieText] = useState('');
  const [termsOpen, setTermsOpen] = useState<'terms' | 'privacy' | null>(null);
  const [updateInfo, setUpdateInfo] = useState<string | null>(null);
  const [mailForm, setMailForm] = useState({
    address: '',
    password: '',
    custom: false,
    imapHost: 'mail.sjtu.edu.cn',
    imapPort: '993',
    smtpHost: 'mail.sjtu.edu.cn',
    smtpPort: '465',
  });

  const refreshAccounts = () => {
    void Promise.allSettled([
      call<CanvasState>('canvas.state'),
      call<MailState>('mail.state'),
      call<CampusStatus>('campus.status'),
      call<{ connected: boolean; username: string | null }>('shuiyuan.state'),
    ]).then((results) => {
      setCanvasState(results[0].status === 'fulfilled' ? results[0].value : null);
      setMailState(results[1].status === 'fulfilled' ? results[1].value : null);
      setCampus(results[2].status === 'fulfilled' ? results[2].value : null);
      setShuiyuanState(results[3].status === 'fulfilled' ? results[3].value : null);
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

  const saveCanvas = async () => {
    if (!canvasToken.trim()) return;
    try {
      await toast
        .promise(call('canvas.configure', canvasToken.trim()), {
          loading: '正在验证 Canvas Token…',
          success: 'Canvas 已连接',
          error: (error) => (error instanceof Error ? error.message : '连接失败'),
        })
        .unwrap();
      setCanvasOpen(false);
      setCanvasToken('');
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

  const checkUpdate = async () => {
    try {
      const result = await call<{
        available: boolean;
        latest?: string;
        message?: string;
        url?: string;
      }>('update.check');
      if (!result) {
        setUpdateInfo('当前构建不支持在线更新检查。');
      } else if (result.available) {
        setUpdateInfo(`发现新版本 ${result.latest ?? ''}${result.message ? `：${result.message}` : ''}`);
      } else {
        setUpdateInfo(result.message ?? '当前已是最新版本。');
      }
    } catch (error) {
      setUpdateInfo(error instanceof Error ? error.message : '检查失败');
    }
  };

  const navOptions: { id: string; label: string }[] = [
    { id: 'canvas', label: '资料' },
    { id: 'mail', label: '邮箱' },
    { id: 'timetable', label: '日程' },
    { id: 'classroom', label: '课堂视频' },
    { id: 'shuiyuan', label: '水源' },
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
                <Section title="账户与连接" description="每个账户都需要手动配置；不读取其他应用的登录数据。">
                  <AccountCard
                    icon={KeyRound}
                    title="Canvas"
                    subtitle="oc.sjtu.edu.cn · 个人 Access Token"
                    connected={Boolean(canvasState?.connected)}
                  >
                    <Button size="sm" variant="primary" onClick={() => setCanvasOpen(true)}>
                      {canvasState?.connected ? '重新配置' : '连接 Canvas'}
                    </Button>
                    {canvasState?.connected ? (
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() =>
                          void call('canvas.disconnect').then(() => {
                            toast.success('已断开 Canvas');
                            refreshAccounts();
                          })
                        }
                      >
                        <LogOut className="size-3.5" /> 断开
                      </Button>
                    ) : null}
                    {canvasState?.courses?.length ? (
                      <span className="text-[0.66rem] text-ink-3">
                        {canvasState.courses.length} 门课程
                      </span>
                    ) : null}
                  </AccountCard>
                  <AccountCard
                    icon={Mail}
                    title="校园邮箱"
                    subtitle="交大邮箱或任意 IMAP / SMTP 邮箱"
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
                    icon={Wifi}
                    title="教学信息服务网"
                    subtitle="i.sjtu.edu.cn · 日程与课堂视频共用登录"
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
                    icon={Server}
                    title="水源社区"
                    subtitle="shuiyuan.sjtu.edu.cn · 只读 + 回复 + 发帖"
                    connected={Boolean(shuiyuanState?.connected)}
                  >
                    <Button size="sm" variant="primary" onClick={() => navigate('shuiyuan')}>
                      前往水源
                    </Button>
                    {shuiyuanState?.connected ? (
                      <span className="text-[0.66rem] text-ink-3">@{shuiyuanState.username}</span>
                    ) : null}
                  </AccountCard>
                </Section>
                <div className="rounded-card border border-line bg-surface-2 p-4 text-[0.68rem] leading-relaxed text-ink-3">
                  <ShieldCheck className="mb-1.5 size-4 text-success" />
                  Token、邮箱密码与校园 Cookie 使用 Windows DPAPI 加密保存在本机，不会上传到任何服务器。
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
                    description="在侧栏显示水源入口；关闭后立即隐藏。"
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
                <Section title="备份" description="自动备份数据库与登录凭据；导出的压缩包用于迁移。">
                  <Row
                    label="自动备份"
                    description="每 15 分钟保存到本机；数据目录缺失时，应用启动会自动恢复最近一次备份。"
                    control={<Badge variant="success">已启用</Badge>}
                  />
                  <Row
                    label="导出本地数据"
                    description="生成一个压缩包，方便迁移到另一台电脑后重新登录。"
                    last
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
                </Section>
              </>
            ) : null}

            {section === 'about' ? (
              <>
                <Section title="SJTU Hive" description="本地优先的上海交大校园桌面。">
                  <Row
                    label="版本"
                    description={`${info?.name ?? 'SJTU Hive'} v${info?.version ?? '0.1.0'} · Wails ${info?.wailsVersion ?? 'v2.16.0'}`}
                    control={<Badge variant="accent">独立实现</Badge>}
                  />
                  <Row
                    label="检查更新"
                    description={updateInfo ?? '从配置的更新源手动检查；不会自动下载或安装。'}
                    control={
                      <Button size="sm" onClick={() => void checkUpdate()}>
                        <RefreshCw className="size-3.5" /> 检查
                      </Button>
                    }
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
                    description="本应用与上海交通大学无隶属关系，功能按现状提供。"
                    control={
                      <Button size="sm" onClick={() => setTermsOpen('terms')}>
                        查看
                      </Button>
                    }
                  />
                  <Row
                    label="隐私说明"
                    description="数据只保存在本机；联网仅用于你主动配置的校园服务。"
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
                <div className="rounded-card border border-line bg-surface-2 p-4 text-[0.68rem] leading-relaxed text-ink-3">
                  <Info className="mb-1.5 size-4 text-ink-3" />
                  本应用是独立实现，未复制第三方客户端的代码或界面素材。校园接口可能随学校系统调整而变化，请以官方页面为准。
                </div>
              </>
            ) : null}
          </div>
        </div>
      </div>

      <Dialog open={canvasOpen} onOpenChange={setCanvasOpen}>
        <DialogContent
          title="连接 Canvas"
          description="在 Canvas 的「账户 → 设置 → 新建访问令牌」创建 Personal Access Token，然后粘贴到这里。"
          footer={
            <>
              <Button variant="ghost" onClick={() => setCanvasOpen(false)}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void saveCanvas()} disabled={!canvasToken.trim()}>
                连接
              </Button>
            </>
          }
        >
          <label className="text-xs font-medium text-ink-2">Access Token</label>
          <Input
            type="password"
            className="mt-1.5"
            value={canvasToken}
            onChange={(event) => setCanvasToken(event.target.value)}
            placeholder="粘贴 Canvas Access Token"
            autoFocus
          />
          <p className="mt-3 text-[0.68rem] leading-relaxed text-ink-3">
            Token 使用 Windows DPAPI 加密，仅保存在本机。同步会读取课程、课件、作业与公告；不会提交作业或修改课程内容。
          </p>
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

      <Dialog open={termsOpen !== null} onOpenChange={(open) => !open && setTermsOpen(null)}>
        <DialogContent title={termsOpen === 'privacy' ? '隐私说明' : '使用条款'} wide>
          <div className="selectable space-y-3 text-xs leading-relaxed text-ink-2">
            {termsOpen === 'privacy' ? (
              <>
                <p>本应用是本地优先的桌面工具。账号 Token、邮箱密码、校园 Cookie 使用 Windows DPAPI 加密保存在本机，不会上传到任何服务器。</p>
                <p>联网仅发生在你主动使用校园服务时：Canvas API、交大邮箱 IMAP/SMTP、教学信息服务网、课堂视频与水源社区。请求由本机直接发往对应服务，不经过第三方中转。</p>
                <p>缓存文件、邮件索引与笔记保存在本机的数据目录中。你可以在设置中清理缓存或导出数据；卸载应用不会自动删除数据目录。</p>
                <p>邮件与水源正文在沙箱中渲染；外部链接会交给系统浏览器打开。</p>
              </>
            ) : (
              <>
                <p>本应用是独立实现，与上海交通大学无隶属关系。校园接口可能随学校系统调整而变化。</p>
                <p>本应用不会绕过学校官方流程，不提供自动选课、场馆预约、批量抢资源等功能。Canvas 功能只读取课程资料与作业信息，不会替你提交作业。</p>
                <p>课堂视频与水源社区属于试验功能，可能因学校服务调整而暂时不可用。请遵守学校网络与社区使用规范。</p>
                <p>软件按现状提供，不对因使用本应用造成的成绩、账号或数据损失承担责任。请定期备份重要笔记。</p>
              </>
            )}
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
