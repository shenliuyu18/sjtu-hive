import { CheckCircle2, GraduationCap, KeyRound, Mail, ShieldCheck, Wifi } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/Button';
import { Checkbox } from '@/components/ui/Controls';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';

export function Onboarding() {
  const open = useApp((state) => state.onboardingOpen);
  const setOpen = useApp((state) => state.setOnboardingOpen);
  const navigate = useApp((state) => state.navigate);
  const setMany = useSettings((state) => state.setMany);
  const [step, setStep] = useState(0);
  const [accepted, setAccepted] = useState(false);
  const [busy, setBusy] = useState(false);

  const finish = async () => {
    if (!accepted) return;
    setBusy(true);
    try {
      await setMany({
        onboardingCompleted: true,
        consentVersion: '2026-10-29',
      });
      setOpen(false);
      setStep(0);
      toast.success('欢迎使用 SJTU Hive');
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '保存失败');
    } finally {
      setBusy(false);
    }
  };

  // Dismissing without accepting the terms still closes the guide for good:
  // otherwise it reopens on every launch, and the terms stay readable in 设置 → 关于.
  const dismiss = async () => {
    setOpen(false);
    setStep(0);
    try {
      await setMany({ onboardingCompleted: true });
    } catch {
      // the guide simply reappears next launch
    }
  };

  return (
    <Dialog
      open={open}
      onOpenChange={(value) => {
        if (!value) void dismiss();
        else setOpen(value);
      }}
    >
      <DialogContent
        title={step === 0 ? '欢迎使用 SJTU Hive' : '配置你的校园账户'}
        description={
          step === 0
            ? '本地优先的上海交大校园桌面：资料、邮箱、日程、课堂视频、水源社区与工作台。'
            : '所有账户都在应用内手动配置；不会读取其他应用的登录数据。'
        }
        wide
        footer={
          step === 0 ? (
            <>
              <Button variant="ghost" onClick={() => void dismiss()}>
                稍后再说
              </Button>
              <Button variant="primary" disabled={!accepted} onClick={() => setStep(1)}>
                继续
              </Button>
            </>
          ) : (
            <>
              <Button variant="ghost" onClick={() => setStep(0)}>
                返回
              </Button>
              <Button
                variant="secondary"
                onClick={() => {
                  navigate('settings', { settingsSection: 'accounts' });
                  void dismiss();
                }}
              >
                去配置账户
              </Button>
              <Button variant="primary" disabled={busy} onClick={() => void finish()}>
                开始使用
              </Button>
            </>
          )
        }
      >
        {step === 0 ? (
          <div className="space-y-4">
            <div className="flex items-center gap-3 rounded-xl border border-line bg-surface-2 p-4">
              <div className="grid size-11 place-items-center rounded-xl bg-accent text-white">
                <GraduationCap className="size-5" />
              </div>
              <div>
                <div className="text-sm font-semibold text-ink">一个应用，收好校园日常</div>
                <div className="mt-0.5 text-xs leading-relaxed text-ink-3">
                  Canvas 课件、校园邮箱、日程与 DDL、课堂回放、水源社区阅读，以及本地的笔记与文件工作台。
                </div>
              </div>
            </div>
            <div className="grid gap-2 sm:grid-cols-3">
              {[
                { icon: ShieldCheck, title: '凭据只留本机', text: 'DPAPI 加密，不上传服务器' },
                { icon: CheckCircle2, title: '不替代你操作', text: '只读资料与通知，不自动交作业' },
                { icon: Wifi, title: '真实校园服务', text: '请求直达学校官方接口' },
              ].map((item) => {
                const Icon = item.icon;
                return (
                  <div key={item.title} className="rounded-lg border border-line bg-surface p-3">
                    <Icon className="size-4 text-accent" />
                    <div className="mt-2 text-xs font-medium text-ink">{item.title}</div>
                    <div className="mt-0.5 text-[0.66rem] leading-relaxed text-ink-3">{item.text}</div>
                  </div>
                );
              })}
            </div>
            <div className="selectable max-h-40 overflow-y-auto rounded-lg border border-line bg-surface-2 p-3 text-[0.68rem] leading-relaxed text-ink-3">
              使用本应用即表示你同意：本应用与上海交通大学无隶属关系；校园接口可能随学校系统调整；
              不提供自动选课、场馆预约或批量抢资源功能；重要笔记请自行备份。账号 Token、邮箱密码与 Cookie
              使用 Windows DPAPI 加密保存在本机，不会上传到任何服务器。
            </div>
            <Checkbox
              checked={accepted}
              onCheckedChange={setAccepted}
              label="我已阅读并同意使用条款与隐私说明"
            />
          </div>
        ) : (
          <div className="space-y-3">
            <div className="rounded-lg border border-line bg-surface-2 p-3 text-[0.68rem] leading-relaxed text-ink-3">
              这些步骤都可以跳过，之后在「设置 → 账户与连接」里随时配置。应用不会读取其他客户端的凭据或数据。
            </div>
            {[
              { icon: KeyRound, title: 'Canvas', text: '粘贴个人 Access Token，同步课程资料与作业。' },
              { icon: Mail, title: '校园邮箱', text: '填写邮箱地址与密码，支持第三方 IMAP / SMTP。' },
              { icon: Wifi, title: '教学信息服务网', text: '打开独立登录窗口，登录后自动保存校园会话。' },
            ].map((item) => {
              const Icon = item.icon;
              return (
                <div key={item.title} className="flex items-start gap-3 rounded-lg border border-line bg-surface p-3">
                  <div className="grid size-8 shrink-0 place-items-center rounded-lg bg-surface-3 text-ink-2">
                    <Icon className="size-4" />
                  </div>
                  <div>
                    <div className="text-xs font-medium text-ink">{item.title}</div>
                    <div className="mt-0.5 text-[0.66rem] leading-relaxed text-ink-3">{item.text}</div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
