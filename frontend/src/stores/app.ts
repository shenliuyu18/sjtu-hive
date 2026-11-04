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
    await useSettings.getState().load();
    const settings = useSettings.getState().state;
    set({
      view: 'dashboard',
      sidebarCollapsed: settings.sidebarCollapsed,
      onboardingOpen: !settings.onboardingCompleted,
      ready: true,
    });
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
