import { GraduationCap } from 'lucide-react';
import { useEffect } from 'react';
import { Toaster } from 'sonner';
import { CommandPalette } from '@/components/CommandPalette';
import { ErrorBoundary } from '@/components/ErrorBoundary';
import { Onboarding } from '@/components/Onboarding';
import { Sidebar } from '@/components/Sidebar';
import { TitleBar } from '@/components/TitleBar';
import { TooltipProvider } from '@/components/ui/Controls';
import { Spinner } from '@/components/ui/Misc';
import { Dashboard } from '@/pages/Dashboard';
import { CanvasPage } from '@/pages/CanvasPage';
import { MailPage } from '@/pages/MailPage';
import { TimetablePage } from '@/pages/TimetablePage';
import { TodoPage } from '@/pages/TodoPage';
import { ClassroomPage } from '@/pages/ClassroomPage';
import { ShuiyuanPage } from '@/pages/ShuiyuanPage';
import { CourseCommunityPage } from '@/pages/CourseCommunityPage';
import { SharePage } from '@/pages/SharePage';
import { CloudPanPage } from '@/pages/CloudPanPage';
import { WorkspacePage } from '@/pages/WorkspacePage';
import { NotificationsPage } from '@/pages/NotificationsPage';
import { Settings } from '@/pages/Settings';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';
import { useNotifications } from '@/stores/notifications';
import { call } from '@/lib/bridge';

function Splash() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-4 bg-bg">
      <div className="grid size-12 place-items-center rounded-2xl bg-accent text-white shadow-[0_8px_30px_rgba(37,99,235,.28)]">
        <GraduationCap className="size-6" />
      </div>
      <div className="flex items-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在启动 SJTU…
      </div>
    </div>
  );
}

function pageFor(view: ReturnType<typeof useApp.getState>['view']) {
  switch (view) {
    case 'dashboard':
      return <Dashboard />;
    case 'settings':
      return <Settings />;
    case 'canvas':
      return <CanvasPage />;
    case 'mail':
      return <MailPage />;
    case 'timetable':
      return <TimetablePage />;
    case 'classroom':
      return <ClassroomPage />;
    case 'shuiyuan':
      return <ShuiyuanPage />;
    case 'course':
      return <CourseCommunityPage />;
    case 'share':
      return <SharePage />;
    case 'cloudpan':
      return <CloudPanPage />;
    case 'todo':
      return <TodoPage />;
    case 'workspace':
      return <WorkspacePage />;
    case 'notifications':
      return <NotificationsPage />;
    default:
      return <Dashboard />;
  }
}

export default function App() {
  const ready = useApp((state) => state.ready);
  const view = useApp((state) => state.view);
  const loaded = useSettings((state) => state.loaded);
  const error = useSettings((state) => state.error);
  const unreadNotifications = useNotifications((state) => state.unread);

  useEffect(() => {
    void useApp.getState().init();
    useNotifications.getState().init();
    void call('app.frontendReady').catch(() => {});
    document.title = 'SJTU Hive';
  }, []);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const typing =
        target?.tagName === 'INPUT' ||
        target?.tagName === 'TEXTAREA' ||
        target?.isContentEditable;
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        useApp.getState().setCommandOpen(true);
        return;
      }
      if ((event.ctrlKey || event.metaKey) && event.key === ',') {
        event.preventDefault();
        useApp.getState().navigate('settings');
        return;
      }
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'b' && !typing) {
        event.preventDefault();
        useApp.getState().toggleSidebar();
        return;
      }
      if (event.key === 'Escape' && useApp.getState().commandOpen) {
        useApp.getState().setCommandOpen(false);
      }
      if ((event.ctrlKey || event.metaKey) && !typing) {
        const index = Number(event.key);
        if (index >= 1 && index <= 8) {
          const order: ReturnType<typeof useApp.getState>['view'][] = [
            'dashboard',
            'canvas',
            'mail',
            'timetable',
            'classroom',
            'shuiyuan',
            'todo',
            'workspace',
          ];
          const next = order[index - 1];
          if (next) {
            event.preventDefault();
            useApp.getState().navigate(next);
          }
        }
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  if (!ready || !loaded) return <Splash />;

  return (
    <TooltipProvider>
      <ErrorBoundary>
        <div className="flex h-full flex-col bg-bg text-ink">
          <TitleBar />
          <div className="flex min-h-0 flex-1">
            <Sidebar badges={{ notifications: unreadNotifications }} />
            <main className="relative min-w-0 flex-1 overflow-hidden bg-bg">
              {error ? (
                <div className="m-4 rounded-lg border border-danger/30 bg-danger/10 px-4 py-3 text-xs text-danger">
                  {error}
                </div>
              ) : null}
              <div key={view} className="h-full">
                {pageFor(view)}
              </div>
            </main>
          </div>
          <CommandPalette />
          <Onboarding />
          <Toaster
            position="bottom-right"
            theme="system"
            richColors
            closeButton
            toastOptions={{ className: 'selectable' }}
          />
        </div>
      </ErrorBoundary>
    </TooltipProvider>
  );
}
