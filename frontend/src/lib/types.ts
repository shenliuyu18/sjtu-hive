export type ThemePreference = 'system' | 'light' | 'dark';

export interface Experiments {
  videoDownload: boolean;
  shuiyuan: boolean;
}

export interface SettingsState {
  theme: ThemePreference;
  fontScale: number;
  fontBasePx: number;
  zoom: number;
  openAtLogin: boolean;
  campusNotifications: boolean;
  taskNotifications: boolean;
  experiments: Experiments;
  navHidden: string[];
  onboardingCompleted: boolean;
  consentVersion: string;
  videoDownloadDir: string;
  mailPollMinutes: number;
  autoSyncMinutes: number;
  updateManifestUrl: string;
  closeAction: 'tray' | 'quit';
  sidebarCollapsed: boolean;
  lastView: AppView;
}

export interface AppInfo {
  version: string;
  name: string;
  platform: string;
  arch: string;
  goVersion: string;
  wailsVersion: string;
  dataDir: string;
  cacheDir: string;
  logDir: string;
  downloadsDir: string;
  resourceBase: string;
  startedAt: string;
}

export type AppView =
  | 'dashboard'
  | 'canvas'
  | 'mail'
  | 'timetable'
  | 'classroom'
  | 'shuiyuan'
  | 'course'
  | 'share'
  | 'cloudpan'
  | 'todo'
  | 'workspace'
  | 'settings'
  | 'notifications';

export interface NavEntry {
  id: AppView;
  label: string;
  description: string;
}

export interface ToastMessage {
  id: string;
  title: string;
  description?: string;
  variant?: 'default' | 'success' | 'error';
}

export interface SearchResultItem {
  id: string;
  title: string;
  subtitle?: string;
  route: SearchRoute;
}

export interface SearchRoute {
  view: AppView;
  [key: string]: unknown;
}

export interface SearchGroup {
  kind: string;
  label: string;
  items: SearchResultItem[];
}

export interface SearchResponse {
  groups: SearchGroup[];
}
