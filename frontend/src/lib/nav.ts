import type { LucideIcon } from 'lucide-react';
import {
  Bell,
  BookOpen,
  CalendarDays,
  Cloud,
  FolderOpen,
  LayoutDashboard,
  Landmark,
  Library,
  ListTodo,
  Mail,
  MessagesSquare,
  MonitorPlay,
  PanelsTopLeft,
  Settings,
} from 'lucide-react';
import type { AppView } from './types';

export interface NavItem {
  id: AppView;
  label: string;
  short: string;
  icon: LucideIcon;
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const navGroups: NavGroup[] = [
  {
    label: '概览',
    items: [{ id: 'dashboard', label: '概览', short: '概览', icon: LayoutDashboard }],
  },
  {
    label: '消息',
    items: [{ id: 'notifications', label: '通知', short: '通知', icon: Bell }],
  },
  {
    label: '校园',
    items: [
      { id: 'canvas', label: '资料', short: '资料', icon: FolderOpen },
      { id: 'mail', label: '邮箱', short: '邮箱', icon: Mail },
      { id: 'timetable', label: '日程', short: '日程', icon: CalendarDays },
      { id: 'services', label: '办事', short: '办事', icon: Landmark },
      { id: 'classroom', label: '课堂视频', short: '视频', icon: MonitorPlay },
      { id: 'cloudpan', label: '云盘', short: '云盘', icon: Cloud },
    ],
  },
  {
    label: '社区',
    items: [
      { id: 'shuiyuan', label: '水源社区', short: '水源社区', icon: MessagesSquare },
      { id: 'course', label: '选课社区', short: '选课', icon: BookOpen },
      { id: 'share', label: '传承交大', short: '传承', icon: Library },
    ],
  },
  {
    label: '学习',
    items: [
      { id: 'todo', label: 'TODO', short: 'TODO', icon: ListTodo },
      { id: 'workspace', label: '工作台', short: '工作台', icon: PanelsTopLeft },
    ],
  },
];

export const settingsNav: NavItem = {
  id: 'settings',
  label: '设置',
  short: '设置',
  icon: Settings,
};

export const allNavItems = [...navGroups.flatMap((group) => group.items), settingsNav];

export function viewLabel(view: AppView) {
  if (view === 'notifications') return '通知';
  return allNavItems.find((item) => item.id === view)?.label ?? 'SJTU Hive';
}
