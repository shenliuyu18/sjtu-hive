/**
 * 应用内链接路由（桌面端）：把校园站点的 URL 映射到应用内的适配页面。
 *
 * 与 Android 端 HiveLinkRouter 保持同一套规则——水源帖子/用户进水源模块，
 * Canvas 课程进资料页，云盘/课堂/选课社区/交我办进各自模块，
 * 识别不了的外部链接交给系统浏览器。
 */
import type { AppView } from '@/lib/types';
import type { RouteParams } from '@/stores/app';

export type RouteIntent =
  | { kind: 'shuiyuan-topic'; id: number }
  | { kind: 'shuiyuan-user'; username: string }
  | { kind: 'shuiyuan-category'; slug: string }
  | { kind: 'canvas-course'; id: number }
  | { kind: 'cloudpan' }
  | { kind: 'classroom' }
  | { kind: 'course' }
  | { kind: 'services' }
  | { kind: 'external'; url: string }
  | { kind: 'ignore' };

const SHUIYUAN_HOST = 'shuiyuan.sjtu.edu.cn';
const CANVAS_HOST = 'oc.sjtu.edu.cn';

export function resolveLink(href: string, base?: string): RouteIntent {
  const text = (href ?? '').trim();
  if (!text) return { kind: 'ignore' };
  if (/^mailto:/i.test(text)) return { kind: 'external', url: text };

  let url: URL;
  try {
    url = new URL(text, base);
  } catch {
    return { kind: 'ignore' };
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return { kind: 'external', url: url.href };
  }
  const host = url.hostname.toLowerCase();
  const segments = url.pathname.split('/').filter(Boolean);

  if (host === SHUIYUAN_HOST || host.endsWith(`.${SHUIYUAN_HOST}`)) {
    switch (segments[0]) {
      case 't': {
        // /t/<slug>/<id> 或 /t/<id>
        const last = segments[segments.length - 1];
        const id = Number.parseInt(last ?? '', 10);
        return Number.isFinite(id) ? { kind: 'shuiyuan-topic', id } : { kind: 'ignore' };
      }
      case 'u':
        return segments[1]
          ? { kind: 'shuiyuan-user', username: decodeURIComponent(segments[1]) }
          : { kind: 'ignore' };
      case 'c':
        return segments[segments.length - 1]
          ? { kind: 'shuiyuan-category', slug: segments[segments.length - 1] }
          : { kind: 'ignore' };
      case 'search':
        // 搜索链接先留在水源首页（应用内没有对应查询入口时不做跳转）
        return { kind: 'ignore' };
      default:
        return { kind: 'external', url: url.href };
    }
  }

  if (host === CANVAS_HOST || host.endsWith(`.${CANVAS_HOST}`)) {
    const idx = segments.indexOf('courses');
    const raw = idx >= 0 ? segments[idx + 1] : undefined;
    const id = Number.parseInt(raw ?? '', 10);
    return Number.isFinite(id) ? { kind: 'canvas-course', id } : { kind: 'ignore' };
  }

  if (host === 'pan.sjtu.edu.cn' || host.endsWith('.pan.sjtu.edu.cn')) return { kind: 'cloudpan' };
  if (host === 'v.sjtu.edu.cn' || host.endsWith('.v.sjtu.edu.cn')) return { kind: 'classroom' };
  if (host === 'course.sjtu.plus' || host.endsWith('.course.sjtu.plus')) return { kind: 'course' };
  if (host === 'my.sjtu.edu.cn') return { kind: 'services' };

  return { kind: 'external', url: url.href };
}

/** 把解析结果分派到 zustand 路由；外部链接交给后端打开浏览器。 */
export function openIntent(
  intent: RouteIntent,
  navigate: (view: AppView, params?: RouteParams) => void,
  openExternal: (url: string) => void,
): void {
  switch (intent.kind) {
    case 'shuiyuan-topic':
      navigate('shuiyuan', { shuiyuanTopicId: intent.id });
      return;
    case 'shuiyuan-user':
      navigate('shuiyuan', { shuiyuanUsername: intent.username });
      return;
    case 'shuiyuan-category':
      navigate('shuiyuan');
      return;
    case 'canvas-course':
      navigate('canvas', { canvasCourseId: intent.id });
      return;
    case 'cloudpan':
      navigate('cloudpan');
      return;
    case 'classroom':
      navigate('classroom');
      return;
    case 'course':
      navigate('course');
      return;
    case 'services':
      navigate('services');
      return;
    case 'external':
      openExternal(intent.url);
      return;
    default:
      return;
  }
}
