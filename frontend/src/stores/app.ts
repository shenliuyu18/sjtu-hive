import { create } from 'zustand';
import type { AppView } from '@/lib/types';
import { useSettings } from './settings';

export interface RouteParams {
  canvasSourceId?: string;
  canvasCourseId?: number;
  mailMessageId?: number;
  mailFolder?: string;
  todoId?: string;
  timetableDate?: string;
  workspacePath?: string;
  workspaceProjectId?: string;
  classroomId?: number;
  shuiyuanTopicId?: number;
  shuiyuanUsername?: string;
  settingsSection?: string;
  query?: string;
  [key: string]: unknown;
}

interface AppStore {
  view: AppView;
  params: RouteParams;
  ready: boolean;
  sidebarCollapsed: boolean;
  commandOpen: boolean;
  onboardingOpen: boolean;
  init: () => Promise<void>;
  navigate: (view: AppView, params?: RouteParams) => void;
  toggleSidebar: () => void;
  setCommandOpen: (open: boolean) => void;
  setOnboardingOpen: (open: boolean) => void;
  setReady: (ready: boolean) => void;
}

export const useApp = create<AppStore>((set, get) => ({
  view: 'dashboard',
  params: {},
  ready: false,
  sidebarCollapsed: false,
  commandOpen: false,
  onboardingOpen: false,
  init: async () => {
    if (get().ready) return;
    // 开机自启时后端可能还没准备好，settings/app.info 的调用会一直挂着。
    // 这里加超时兜底：宁可先进界面并提示错误，也不要永远停在启动画面。
    try {
      await Promise.race([
        useSettings.getState().load(),
        new Promise<void>((resolve) => window.setTimeout(resolve, 8000)),
      ]);
    } finally {
      const settings = useSettings.getState();
      if (!settings.loaded) {
        useSettings.setState({
          loaded: true,
          error: '初始化超时：未能读取本机设置，已按默认值进入。可以重试或查看日志。',
        });
      }
      const state = useSettings.getState().state;
      set({
        view: 'dashboard',
        sidebarCollapsed: state.sidebarCollapsed,
        onboardingOpen: !state.onboardingCompleted,
        ready: true,
      });
    }
  },
  navigate: (view, params = {}) => {
    set({ view, params, commandOpen: false });
  },
  toggleSidebar: () => {
    const next = !get().sidebarCollapsed;
    set({ sidebarCollapsed: next });
    void useSettings.getState().set('sidebarCollapsed', next).catch(() => {});
  },
  setCommandOpen: (open) => set({ commandOpen: open }),
  setOnboardingOpen: (open) => set({ onboardingOpen: open }),
  setReady: (ready) => set({ ready }),
}));
