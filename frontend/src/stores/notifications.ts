import { create } from 'zustand';
import { call, on } from '@/lib/bridge';

interface NotificationStore {
  unread: number;
  initialized: boolean;
  init: () => void;
  refresh: () => void;
}

export const useNotifications = create<NotificationStore>((set, get) => ({
  unread: 0,
  initialized: false,
  init: () => {
    if (get().initialized) return;
    set({ initialized: true });
    void call<{ unread: number }>('notifications.unread')
      .then((value) => set({ unread: value?.unread ?? 0 }))
      .catch(() => {});
    on('notifications.changed', (payload) => {
      const value = payload as { unread?: number };
      if (typeof value?.unread === 'number') set({ unread: value.unread });
      else get().refresh();
    });
  },
  refresh: () => {
    void call<{ unread: number }>('notifications.unread')
      .then((value) => set({ unread: value?.unread ?? 0 }))
      .catch(() => {});
  },
}));
