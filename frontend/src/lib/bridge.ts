import { mockCall } from './mock';

interface WailsRuntime {
  EventsOn(name: string, callback: (...data: unknown[]) => void): () => void;
  EventsOff(name: string): void;
  WindowMinimise(): void;
  WindowToggleMaximise(): void;
  WindowIsMaximised(): Promise<boolean>;
  Quit(): void;
  OnFileDrop(callback: (x: number, y: number, paths: string[]) => void, useDropTarget: boolean): void;
  OnFileDropOff(): void;
  ClipboardGetText(): Promise<string>;
  ClipboardSetText(text: string): Promise<boolean>;
}

declare global {
  interface Window {
    go?: {
      main?: {
        App?: {
          Call?: (channel: string, argsJSON: string) => Promise<string>;
        };
      };
      app?: {
        App?: {
          Call?: (channel: string, argsJSON: string) => Promise<string>;
        };
      };
    };
    runtime?: WailsRuntime;
  }
}

type Listener = (...data: unknown[]) => void;
const localListeners = new Map<string, Set<Listener>>();

function localOn(name: string, callback: Listener) {
  const set = localListeners.get(name) ?? new Set<Listener>();
  set.add(callback);
  localListeners.set(name, set);
  return () => {
    set.delete(callback);
  };
}

function backendCall() {
  return window.go?.main?.App?.Call ?? window.go?.app?.App?.Call;
}

export function hasBackend() {
  return Boolean(backendCall());
}

function normalizeError(error: unknown) {
  const text = error instanceof Error ? error.message : String(error ?? '未知错误');
  return text.replace(/^Error:\s*/, '').replace(/^Error invoking remote method '[^']+':\s*/, '');
}

export async function call<T>(channel: string, ...args: unknown[]): Promise<T> {
  const binding = backendCall();
  if (!binding) {
    return mockCall<T>(channel, args, emitLocal);
  }
  try {
    const raw = await binding(channel, JSON.stringify(args));
    if (!raw) return undefined as T;
    return JSON.parse(raw) as T;
  } catch (error) {
    throw new Error(normalizeError(error));
  }
}

export function on(name: string, callback: Listener) {
  if (window.runtime?.EventsOn) {
    return window.runtime.EventsOn(name, callback);
  }
  return localOn(name, callback);
}

export function emitLocal(name: string, ...data: unknown[]) {
  for (const listener of localListeners.get(name) ?? []) listener(...data);
}

export function minimizeWindow() {
  window.runtime?.WindowMinimise?.();
}

export function toggleMaximizeWindow() {
  window.runtime?.WindowToggleMaximise?.();
}

export async function isWindowMaximised() {
  if (!window.runtime?.WindowIsMaximised) return false;
  try {
    return await window.runtime.WindowIsMaximised();
  } catch {
    return false;
  }
}

// The ✕ button asks the backend, which either hides to the tray or really quits
// according to the user's 关闭行为 setting.
export async function quitApp(): Promise<'tray' | 'quit'> {
  try {
    return await call<'tray' | 'quit'>('app.windowClose');
  } catch {
    window.runtime?.Quit?.();
    return 'quit';
  }
}

export function registerFileDrop(callback: (paths: string[]) => void) {
  if (!window.runtime?.OnFileDrop) return () => {};
  window.runtime.OnFileDrop((_x, _y, paths) => callback(paths), false);
  return () => window.runtime?.OnFileDropOff?.();
}

export async function copyText(text: string) {
  if (window.runtime?.ClipboardSetText) {
    await window.runtime.ClipboardSetText(text);
    return;
  }
  await navigator.clipboard.writeText(text);
}

export async function readClipboard() {
  if (window.runtime?.ClipboardGetText) {
    return window.runtime.ClipboardGetText();
  }
  return navigator.clipboard.readText();
}
