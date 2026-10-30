import { create } from 'zustand';
import { call } from '@/lib/bridge';
import type { AppInfo, SettingsState } from '@/lib/types';
import { defaultSettings } from '@/lib/mock';

const preferences = [
  'theme',
  'fontScale',
  'zoom',
  'openAtLogin',
  'campusNotifications',
  'taskNotifications',
  'experiments',
  'navHidden',
  'onboardingCompleted',
  'consentVersion',
  'videoDownloadDir',
  'mailPollMinutes',
  'autoSyncMinutes',
  'updateManifestUrl',
  'closeAction',
  'sidebarCollapsed',
  'lastView',
] as const;

type Preferences = Pick<SettingsState, (typeof preferences)[number]>;

function applyAppearance(state: SettingsState) {
  const root = document.documentElement;
  const media = window.matchMedia('(prefers-color-scheme: dark)');
  const dark = state.theme === 'dark' || (state.theme === 'system' && media.matches);
  root.classList.toggle('dark', dark);
  root.classList.toggle('light', !dark);
  root.style.fontSize = `${(state.fontBasePx * state.fontScale) / 100}px`;
  root.style.zoom = String(state.zoom);
}

interface SettingsStore {
  loaded: boolean;
  state: SettingsState;
  info: AppInfo | null;
  error: string | null;
  load: () => Promise<void>;
  set: <K extends keyof Preferences>(key: K, value: SettingsState[K]) => Promise<void>;
  setMany: (values: Partial<Preferences>) => Promise<void>;
  patchLocal: (values: Partial<SettingsState>) => void;
}

export const useSettings = create<SettingsStore>((set, get) => ({
  loaded: false,
  state: { ...defaultSettings },
  info: null,
  error: null,
  load: async () => {
    if (get().loaded) return;
    try {
      const [state, info] = await Promise.all([
        call<SettingsState>('settings.state'),
        call<AppInfo>('app.info'),
      ]);
      set({ state, info, loaded: true, error: null });
      applyAppearance(state);
      window
        .matchMedia('(prefers-color-scheme: dark)')
        .addEventListener('change', () => applyAppearance(useSettings.getState().state));
    } catch (error) {
      set({ error: error instanceof Error ? error.message : String(error), loaded: true });
    }
  },
  set: async (key, value) => {
    const optimistic = { ...get().state, [key]: value } as SettingsState;
    set({ state: optimistic });
    applyAppearance(optimistic);
    try {
      const state = await call<SettingsState>('settings.set', key, value);
      set({ state, error: null });
      applyAppearance(state);
    } catch (error) {
      set({ error: error instanceof Error ? error.message : String(error) });
      throw error;
    }
  },
  setMany: async (values) => {
    const optimistic = { ...get().state, ...values } as SettingsState;
    set({ state: optimistic });
    applyAppearance(optimistic);
    try {
      const state = await call<SettingsState>('settings.setMany', values);
      set({ state, error: null });
      applyAppearance(state);
    } catch (error) {
      set({ error: error instanceof Error ? error.message : String(error) });
      throw error;
    }
  },
  patchLocal: (values) => set({ state: { ...get().state, ...values } }),
}));
