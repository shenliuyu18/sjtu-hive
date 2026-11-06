import DOMPurify from 'dompurify';
import {
  Award,
  Bell,
  Bookmark,
  ExternalLink,
  Hash,
  Loader2,
  MessageSquare,
  Plus,
  RefreshCw,
  Search,
  Send,
  Settings2,
  ThumbsUp,
  Upload,
  User,
  Users,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { call } from '@/lib/bridge';
import type {
  ShuiyuanActivityItem,
  ShuiyuanBookmarkItem,
  ShuiyuanCounts,
  ShuiyuanNotificationItem,
  ShuiyuanPost,
  ShuiyuanProfile,
  ShuiyuanSiteStructure,
  ShuiyuanState,
  ShuiyuanTopicDetail,
  ShuiyuanTopicPage,
  ShuiyuanTopicSummary,
  ShuiyuanUpload,
} from '@/lib/models';
import { formatDate, relativeTime } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { Button } from '@/components/ui/Button';
import { Input, Textarea } from '@/components/ui/Input';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';

type Feed =
  | { kind: 'latest' }
  | { kind: 'featured' }
  | { kind: 'category'; categoryId: number }
  | { kind: 'tag'; tag: string }
  | { kind: 'search'; query: string };

const origin = 'https://shuiyuan.sjtu.edu.cn';

function avatarURL(template: string | null | undefined) {
  if (!template) return null;
  const path = template.replace('{size}', '96');
  if (/^https?:/i.test(path)) return path;
  return origin + path;
}

const activityLabels: Record<string, string> = {
  topic: '发帖',
  reply: '回复',
  like: '点赞',
  mention: '提及',
  message: '私信',
};

function activityKindLabel(kind: string) {
  return activityLabels[kind] ?? '动态';
}

function categoryColor(color: string | null | undefined) {  if (!color) return '#6b7280';
  return color.startsWith('#') ? color : `#${color}`;
}

function PostBody({ html }: { html: string }) {
  const clean = useMemo(
    () =>
      DOMPurify.sanitize(html, {
        FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'button', 'textarea', 'select', 'style', 'link', 'meta', 'base'],
        FORBID_ATTR: ['srcdoc', 'onerror', 'onload', 'onclick', 'onmouseover'],
        ADD_ATTR: ['target', 'rel', 'class'],
      }),
    [html],
  );
  return (
    <div
      className="markdown-body shuiyuan-post selectable"
      onClick={(event) => {
        const target = event.target as HTMLElement;
        const anchor = target.closest('a[href]') as HTMLAnchorElement | null;
        if (!anchor) return;
        const href = anchor.getAttribute('href');
        if (!href || href.startsWith('#')) return;
        event.preventDefault();
        try {
          const url = new URL(href, origin);
          if (url.protocol === 'http:' || url.protocol === 'https:') void call('app.openExternal', url.href);
        } catch {
          // ignore
        }
      }}
      dangerouslySetInnerHTML={{ __html: clean }}
    />
  );
}

export function ShuiyuanPage() {
  const navigate = useApp((state) => state.navigate);
  const params = useApp((state) => state.params);
  const [state, setState] = useState<ShuiyuanState | null>(null);
  const [structure, setStructure] = useState<ShuiyuanSiteStructure | null>(null);
  const [feed, setFeed] = useState<Feed>({ kind: 'latest' });
  const [topics, setTopics] = useState<ShuiyuanTopicSummary[]>([]);
  const [moreUrl, setMoreUrl] = useState<string | null>(null);
  const [topicsLoading, setTopicsLoading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [topicId, setTopicId] = useState<number | null>(null);
  const [detail, setDetail] = useState<ShuiyuanTopicDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [loadingMorePosts, setLoadingMorePosts] = useState(false);
  const [replyText, setReplyText] = useState('');
  const [replyTo, setReplyTo] = useState<{ postNumber: number; username: string } | null>(null);
  const [sendingReply, setSendingReply] = useState(false);
  const [searchText, setSearchText] = useState('');
  const [authOpen, setAuthOpen] = useState(false);
  const [authUrl, setAuthUrl] = useState('');
  const [payload, setPayload] = useState('');
  const [binding, setBinding] = useState(false);
  const [startingAuth, setStartingAuth] = useState(false);
  const [composeOpen, setComposeOpen] = useState(false);
  const [compose, setCompose] = useState({ title: '', body: '', categoryId: '', tags: '' });
  const [postingTopic, setPostingTopic] = useState(false);
  const [messageOpen, setMessageOpen] = useState(false);
  const [message, setMessage] = useState({ to: '', title: '', body: '' });
  const [sendingMessage, setSendingMessage] = useState(false);
  const [bookmarksOpen, setBookmarksOpen] = useState(false);
  const [bookmarks, setBookmarks] = useState<ShuiyuanBookmarkItem[]>([]);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [notifications, setNotifications] = useState<ShuiyuanNotificationItem[]>([]);
  const [counts, setCounts] = useState<ShuiyuanCounts>({ notifications: 0, messages: 0 });
  const [uploading, setUploading] = useState(false);
  const [profileUsername, setProfileUsername] = useState<string | null>(null);
  const [profile, setProfile] = useState<ShuiyuanProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(false);
  const [profileTab, setProfileTab] = useState<'info' | 'badges' | 'activity'>('info');
  const [activity, setActivity] = useState<ShuiyuanActivityItem[] | null>(null);
  const readerRef = useRef<HTMLDivElement>(null);
  const topicListRef = useRef<HTMLDivElement>(null);
  const loadMoreRef = useRef(false);
  const postLoadRef = useRef(false);

  const categoryName = (id: number | null) =>
    id === null ? '' : structure?.categories.find((category) => category.id === id)?.name ?? '';

  const loadFeed = async (nextFeed: Feed = feed, more?: string) => {
    setTopicsLoading(true);
    try {
      const result = await call<ShuiyuanTopicPage>(
        'shuiyuan.topics',
        feedPayload(nextFeed),
        more ?? '',
      );
      const list = result?.topics ?? [];
      setTopics((current) => (more ? [...current, ...list] : list));
      setMoreUrl(result?.moreUrl ?? null);
      if (!more) setFeed(nextFeed);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取水源社区主题失败');
    } finally {
      setTopicsLoading(false);
    }
  };

  const handleTopicScroll = () => {
    const element = topicListRef.current;
    if (!element || !moreUrl || topicsLoading || loadMoreRef.current) return;
    if (element.scrollTop + element.clientHeight < element.scrollHeight - 240) return;
    loadMoreRef.current = true;
    void loadFeed(feed, moreUrl).finally(() => {
      loadMoreRef.current = false;
    });
  };

  const loadTopic = async (id: number, postNumber?: number | null) => {
    setTopicId(id);
    setDetailLoading(true);
    setReplyTo(null);
    try {
      const value = await call<ShuiyuanTopicDetail>('shuiyuan.topic', id, postNumber ?? undefined);
      setDetail(value);
      window.setTimeout(() => {
        if (readerRef.current) readerRef.current.scrollTop = 0;
      }, 30);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取主题失败');
    } finally {
      setDetailLoading(false);
    }
  };

  const openProfile = async (username: string) => {
    const name = username.trim();
    if (!name) return;
    setProfileUsername(name);
    setProfileTab('info');
    setProfile(null);
    setActivity(null);
    setProfileLoading(true);
    try {
      setProfile(await call<ShuiyuanProfile>('shuiyuan.profile', name));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取用户主页失败');
      setProfileUsername(null);
    } finally {
      setProfileLoading(false);
    }
  };

  const loadActivity = async (username: string) => {
    try {
      setActivity(await call<ShuiyuanActivityItem[]>('shuiyuan.activity', username));
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取用户动态失败');
      setActivity([]);
    }
  };

  const loadStructureAndCounts = async () => {
    const [structureResult, countsResult] = await Promise.allSettled([
      call<ShuiyuanSiteStructure>('shuiyuan.site'),
      call<ShuiyuanCounts>('shuiyuan.counts'),
    ]);
    if (structureResult.status === 'fulfilled') setStructure(structureResult.value);
    if (countsResult.status === 'fulfilled') setCounts(countsResult.value ?? { notifications: 0, messages: 0 });
  };

  useEffect(() => {
    let alive = true;
    void call<ShuiyuanState>('shuiyuan.state')
      .then(async (value) => {
        if (!alive) return;
        setState(value);
        if (value?.connected) {
          await loadStructureAndCounts();
          await loadFeed({ kind: 'latest' });
        }
      })
      .catch((cause) => toast.error(cause instanceof Error ? cause.message : '读取水源社区状态失败'))
      .finally(() => {
        if (alive) setLoading(false);
      });
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    const username = params.shuiyuanUsername;
    if (typeof username === 'string' && username) void openProfile(username);
  }, [params.shuiyuanUsername]);

  const startAuth = async () => {
    setStartingAuth(true);
    try {
      const result = await call<{ url: string }>('shuiyuan.startAuth');
      if (!result?.url) throw new Error('没有获取到授权地址');
      setAuthUrl(result.url);
      setAuthOpen(true);
      void call('app.openExternal', result.url);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '发起授权失败');
    } finally {
      setStartingAuth(false);
    }
  };

  const bind = async () => {
    if (!payload.trim()) return;
    setBinding(true);
    try {
      const value = await call<ShuiyuanState>('shuiyuan.bind', payload);
      setState(value);
      setAuthOpen(false);
      setPayload('');
      toast.success('水源社区已绑定');
      await loadStructureAndCounts();
      await loadFeed({ kind: 'latest' });
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '绑定失败');
    } finally {
      setBinding(false);
    }
  };

  const openBookmarks = async () => {
    try {
      const value = await call<ShuiyuanBookmarkItem[]>('shuiyuan.bookmarks');
      setBookmarks(value ?? []);
      setBookmarksOpen(true);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取收藏失败');
    }
  };

  const openNotifications = async () => {
    try {
      const value = await call<ShuiyuanNotificationItem[]>('shuiyuan.notifications');
      setNotifications(value ?? []);
      setNotificationsOpen(true);
      if ((value ?? []).some((item) => !item.read)) {
        await call('shuiyuan.markNotificationsRead').catch(() => {});
        setCounts((current) => ({ ...current, notifications: 0 }));
      }
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '读取通知失败');
    }
  };

  const toggleBookmark = async () => {
    if (!detail) return;
    try {
      const next = await call<boolean>('shuiyuan.toggleBookmark', detail.id, !detail.bookmarked);
      setDetail({ ...detail, bookmarked: Boolean(next) });
      toast.success(next ? '已收藏' : '已取消收藏');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '收藏失败');
    }
  };

  const likePost = async (post: ShuiyuanPost) => {
    if (!detail) return;
    try {
      await call('shuiyuan.likePost', post.id, !post.liked);
      setDetail({
        ...detail,
        posts: detail.posts.map((item) =>
          item.id === post.id
            ? {
                ...item,
                liked: !post.liked,
                likeCount: Math.max(0, (item.likeCount ?? 0) + (post.liked ? -1 : 1)),
              }
            : item,
        ),
      });
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '点赞失败');
    }
  };

  const sendReply = async () => {
    if (!detail || !replyText.trim() || sendingReply) return;
    setSendingReply(true);
    try {
      const post = await call<ShuiyuanPost>(
        'shuiyuan.reply',
        detail.id,
        replyText,
        replyTo?.postNumber,
      );
      setDetail({
        ...detail,
        posts: [...detail.posts, post].sort((a, b) => a.postNumber - b.postNumber),
        postsCount: detail.postsCount + 1,
      });
      setReplyText('');
      setReplyTo(null);
      toast.success('回复已发送');
      window.setTimeout(() => {
        if (readerRef.current) readerRef.current.scrollTop = readerRef.current.scrollHeight;
      }, 60);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '回复失败');
    } finally {
      setSendingReply(false);
    }
  };

  const loadMorePosts = async () => {
    if (!detail || loadingMorePosts) return;
    const loaded = new Set(detail.posts.map((post) => post.id));
    const remaining = detail.stream.filter((id) => !loaded.has(id)).slice(0, 20);
    if (remaining.length === 0) return;
    setLoadingMorePosts(true);
    try {
      const posts = await call<ShuiyuanPost[]>('shuiyuan.loadPosts', detail.id, remaining);
      setDetail({
        ...detail,
        posts: [...detail.posts, ...posts].sort((a, b) => a.postNumber - b.postNumber),
      });
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '加载楼层失败');
    } finally {
      setLoadingMorePosts(false);
    }
  };

  // Scrolling the reader to the bottom keeps fetching the remaining floors.
  const handleReaderScroll = () => {
    const element = readerRef.current;
    if (!element || !detail || loadingMorePosts || postLoadRef.current) return;
    if (element.scrollTop + element.clientHeight < element.scrollHeight - 360) return;
    postLoadRef.current = true;
    void loadMorePosts().finally(() => {
      postLoadRef.current = false;
    });
  };

  const uploadReplyImage = async () => {
    if (!detail || uploading) return;
    try {
      const path = await call<string | null>('app.chooseFiles', '选择要上传的图片');
      if (!path) return;
      setUploading(true);
      const upload = await call<ShuiyuanUpload>('shuiyuan.uploadPath', path);
      if (upload?.shortUrl) {
        const name = path.split(/[\\/]/).pop() ?? 'image';
        setReplyText((current) => `${current}${current ? '\n' : ''}![${name}](${upload.shortUrl})`);
        toast.success('图片已上传');
      }
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '图片上传失败');
    } finally {
      setUploading(false);
    }
  };

  const openCompose = () => {
    setCompose({
      title: '',
      body: '',
      categoryId: structure?.categories[0]?.id ? String(structure.categories[0].id) : '',
      tags: '',
    });
    setComposeOpen(true);
  };

  const createTopic = async () => {
    if (!compose.title.trim() || !compose.body.trim()) {
      toast.error('标题和正文不能为空');
      return;
    }
    setPostingTopic(true);
    try {
      const result = await call<{ topicId: number }>(
        'shuiyuan.createTopic',
        compose.title,
        compose.body,
        Number(compose.categoryId) || 0,
        compose.tags
          .split(/[,，\s]+/)
          .map((tag) => tag.trim())
          .filter(Boolean),
      );
      setComposeOpen(false);
      toast.success('主题已发布');
      if (result?.topicId) await loadTopic(result.topicId);
      await loadFeed(feed);
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '发布失败');
    } finally {
      setPostingTopic(false);
    }
  };

  const sendMessage = async () => {
    if (!message.to.trim() || !message.title.trim() || !message.body.trim()) {
      toast.error('请填写收件人、标题和正文');
      return;
    }
    setSendingMessage(true);
    try {
      await call(
        'shuiyuan.sendMessage',
        message.to
          .split(/[,，\s]+/)
          .map((value) => value.trim())
          .filter(Boolean),
        message.title,
        message.body,
      );
      setMessageOpen(false);
      setMessage({ to: '', title: '', body: '' });
      toast.success('私信已发送');
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '私信发送失败');
    } finally {
      setSendingMessage(false);
    }
  };

  const filteredCategories = structure?.categories ?? [];

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center gap-2 text-xs text-ink-3">
        <Spinner /> 正在读取水源社区…
      </div>
    );
  }

  if (!state?.connected) {
    return (
      <div className="flex h-full flex-col">
        <PageHeader title="水源社区" description="上海交大水源社区阅读、搜索与回复。" eyebrow="Shuiyuan" />
        <div className="flex flex-1 items-center justify-center p-6">
          <Card className="max-w-2xl overflow-hidden">
            <div className="flex items-center gap-3 border-b border-line bg-surface-2 px-5 py-4">
              <div className="grid size-11 place-items-center rounded-xl bg-accent-soft text-accent">
                <MessageSquare className="size-5" />
              </div>
              <div>
                <div className="text-sm font-semibold text-ink">绑定水源社区</div>
                <div className="mt-0.5 text-xs text-ink-3">使用 Discourse User API Key，只读 + 回复 + 发帖。</div>
              </div>
            </div>
            <div className="space-y-3 p-5 text-xs leading-relaxed text-ink-2">
              <div className="rounded-lg border border-line bg-surface-2 p-3 text-[0.68rem] text-ink-3">
                <div>1. 点击「登录并授权」，浏览器会打开水源社区授权页。</div>
                <div>2. 登录并确认授权后，页面会展示一段加密密文。</div>
                <div>3. 复制密文粘贴到下方，点击「绑定」。</div>
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <Button variant="primary" onClick={() => void startAuth()} disabled={startingAuth}>
                  {startingAuth ? <Loader2 className="size-4 animate-spin" /> : <ExternalLink className="size-4" />}
                  登录并授权
                </Button>
                {authUrl ? (
                  <Button variant="ghost" onClick={() => void call('app.openExternal', authUrl)}>
                    重新打开授权页
                  </Button>
                ) : null}
              </div>
              {authOpen ? (
                <div className="space-y-2 border-t border-line pt-3">
                  <label className="text-xs font-medium text-ink-2">授权密文</label>
                  <Textarea
                    value={payload}
                    onChange={(event) => setPayload(event.target.value)}
                    placeholder="粘贴水源社区授权页显示的整段密文"
                    className="h-28 font-mono text-[0.7rem]"
                  />
                  <div className="flex justify-end">
                    <Button variant="primary" onClick={() => void bind()} disabled={binding || !payload.trim()}>
                      {binding ? <Loader2 className="size-4 animate-spin" /> : null}
                      绑定
                    </Button>
                  </div>
                </div>
              ) : null}
              <p className="text-[0.66rem] text-ink-3">
                密钥使用 Windows DPAPI 加密保存，仅在本机使用；可以随时在设置中断开。
              </p>
            </div>
          </Card>
        </div>
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="水源社区"
        description={`@${state.username ?? ''} · 上海交大水源社区`}
        eyebrow="Shuiyuan"
        actions={
          <>
            <Button variant="secondary" onClick={openNotifications}>
              <Bell className="size-4" /> 通知 {counts.notifications ? `(${counts.notifications})` : ''}
            </Button>
            <Button variant="secondary" onClick={() => void openBookmarks()}>
              <Bookmark className="size-4" /> 收藏
            </Button>
            <Button variant="secondary" onClick={() => setMessageOpen(true)}>
              <Send className="size-4" /> 私信
            </Button>
            <Button variant="primary" onClick={openCompose}>
              <Plus className="size-4" /> 发主题
            </Button>
          </>
        }
      />
      <div className="flex min-h-0 flex-1 border-t border-line bg-surface">
        <aside className="flex w-56 shrink-0 flex-col border-r border-line">
          <div className="border-b border-line p-2">
            <div className="relative">
              <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
              <Input
                value={searchText}
                onChange={(event) => setSearchText(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && searchText.trim()) {
                    void loadFeed({ kind: 'search', query: searchText.trim() });
                  }
                }}
                placeholder="搜索水源社区"
                className="h-8 pl-8 text-xs"
              />
            </div>
          </div>
          <div className="scroll-thin min-h-0 flex-1 overflow-y-auto p-2">
            <FeedButton
              active={feed.kind === 'latest'}
              label="最新"
              onClick={() => void loadFeed({ kind: 'latest' })}
            />
            <FeedButton
              active={feed.kind === 'featured'}
              label="精品"
              onClick={() => void loadFeed({ kind: 'featured' })}
            />
            {feed.kind === 'search' ? (
              <FeedButton active label={`搜索：${feed.query}`} onClick={() => void loadFeed(feed)} />
            ) : null}
            <div className="px-2 pb-1 pt-3 text-[0.6rem] font-semibold uppercase tracking-[0.12em] text-ink-3">
              分类
            </div>
            {filteredCategories.map((category) => (
              <button
                key={category.id}
                type="button"
                onClick={() => void loadFeed({ kind: 'category', categoryId: category.id })}
                className={cn(
                  'flex h-7 w-full items-center gap-1.5 rounded-md px-2 text-left text-[0.68rem] transition-colors',
                  feed.kind === 'category' && feed.categoryId === category.id
                    ? 'bg-selected text-accent'
                    : 'text-ink-2 hover:bg-hover hover:text-ink',
                  category.parentId ? 'pl-5' : '',
                )}
              >
                <span
                  className="size-2 shrink-0 rounded-full"
                  style={{ backgroundColor: categoryColor(category.color) }}
                />
                <span className="min-w-0 flex-1 truncate">{category.name}</span>
              </button>
            ))}
            <div className="px-2 pb-1 pt-3 text-[0.6rem] font-semibold uppercase tracking-[0.12em] text-ink-3">
              标签
            </div>
            <div className="flex flex-wrap gap-1 px-1">
              {(structure?.tags ?? []).slice(0, 24).map((tag) => (
                <button
                  key={tag.name}
                  type="button"
                  onClick={() => void loadFeed({ kind: 'tag', tag: tag.name })}
                  className={cn(
                    'rounded-md px-1.5 py-0.5 text-[0.66rem] transition-colors',
                    feed.kind === 'tag' && feed.tag === tag.name
                      ? 'bg-accent-soft text-accent'
                      : 'bg-surface-3 text-ink-3 hover:text-ink',
                  )}
                >
                  #{tag.name}
                </button>
              ))}
            </div>
          </div>
          <div className="flex items-center gap-1 border-t border-line px-2 py-1.5">
            <button
              type="button"
              onClick={() => state?.username && void openProfile(state.username)}
              className="flex min-w-0 flex-1 items-center gap-2 rounded-md px-1 py-1 text-left text-[0.66rem] text-ink-3 transition-colors hover:bg-hover hover:text-ink"
            >
              <User className="size-3.5 shrink-0" />
              <span className="min-w-0 truncate">@{state.username}</span>
              <span className="shrink-0 text-[0.6rem]">我的主页</span>
            </button>
            <Button
              size="icon-sm"
              variant="ghost"
              onClick={() => navigate('settings', { settingsSection: 'accounts' })}
              title="账户设置"
            >
              <Settings2 className="size-3.5" />
            </Button>
          </div>
        </aside>

        <section className="flex w-80 shrink-0 flex-col border-r border-line">
          <div className="flex shrink-0 items-center justify-between border-b border-line px-3 py-2">
            <div className="min-w-0">
              <div className="truncate text-xs font-semibold text-ink">{feedLabel(feed, categoryName)}</div>
              <div className="mt-0.5 text-[0.62rem] text-ink-3">{topics.length} 个主题</div>
            </div>
            <Button size="icon-sm" variant="ghost" onClick={() => void loadFeed(feed)} title="刷新">
              <RefreshCw className={cn('size-3.5', topicsLoading && 'animate-spin')} />
            </Button>
          </div>
          <div ref={topicListRef} onScroll={handleTopicScroll} className="scroll-thin min-h-0 flex-1 overflow-y-auto">
            {topicsLoading && topics.length === 0 ? (
              <div className="flex items-center justify-center gap-2 py-10 text-xs text-ink-3">
                <Spinner /> 读取主题…
              </div>
            ) : topics.length === 0 ? (
              <EmptyState compact icon={MessageSquare} title="没有主题" description="换个分类或关键词试试。" />
            ) : (
              <>
                {topics.map((topic) => (
                  <button
                    key={topic.id}
                    type="button"
                    onClick={() => void loadTopic(topic.id)}
                    className={cn(
                      'flex w-full flex-col gap-1 border-b border-line px-3 py-2.5 text-left transition-colors',
                      topic.id === topicId ? 'bg-selected/70' : 'hover:bg-hover',
                    )}
                  >
                    <div className="flex items-start gap-2">
                      <span
                        title={topic.authorUsername ? `@${topic.authorUsername} 的主页` : undefined}
                        onClick={(event) => {
                          event.stopPropagation();
                          if (topic.authorUsername) void openProfile(topic.authorUsername);
                        }}
                        className="mt-0.5 shrink-0 cursor-pointer rounded-full transition-opacity hover:opacity-80"
                      >
                        {avatarURL(topic.authorAvatarTemplate) ? (
                          <img
                            src={avatarURL(topic.authorAvatarTemplate) ?? ''}
                            alt=""
                            className="size-5 rounded-full bg-surface-3"
                          />
                        ) : (
                          <User className="size-4 text-ink-3" />
                        )}
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="line-clamp-2 text-xs font-medium leading-relaxed text-ink">
                          {topic.pinned ? <span className="mr-1 text-accent">置顶</span> : null}
                          {topic.title}
                        </div>
                        {topic.excerpt ? (
                          <div className="mt-0.5 line-clamp-2 text-[0.64rem] leading-relaxed text-ink-3">
                            {topic.excerpt}
                          </div>
                        ) : null}
                        <div className="mt-1 flex flex-wrap items-center gap-2 text-[0.6rem] text-ink-3">
                          {topic.categoryId ? (
                            <span
                              className="rounded px-1 py-0.5"
                              style={{
                                backgroundColor: `${categoryColor(
                                  structure?.categories.find((category) => category.id === topic.categoryId)?.color,
                                )}1a`,
                                color: categoryColor(
                                  structure?.categories.find((category) => category.id === topic.categoryId)?.color,
                                ),
                              }}
                            >
                              {categoryName(topic.categoryId)}
                            </span>
                          ) : null}
                          {topic.tags.slice(0, 3).map((tag) => (
                            <span key={tag} className="inline-flex items-center gap-0.5">
                              <Hash className="size-2.5" /> {tag}
                            </span>
                          ))}
                          <span>{topic.postsCount} 回复</span>
                          <span>{relativeTime(topic.lastPostedAt ?? topic.createdAt)}</span>
                        </div>
                      </div>
                    </div>
                  </button>
                ))}
                {topicsLoading && topics.length > 0 ? (
                  <div className="flex items-center justify-center gap-2 py-4 text-[0.66rem] text-ink-3">
                    <Loader2 className="size-3.5 animate-spin" /> 正在加载更多…
                  </div>
                ) : moreUrl ? (
                  <Button
                    className="m-2 w-[calc(100%-1rem)]"
                    variant="secondary"
                    onClick={() => void loadFeed(feed, moreUrl)}
                    disabled={topicsLoading}
                  >
                    加载更多
                  </Button>
                ) : null}
              </>
            )}
          </div>
        </section>

        <section className="flex min-w-0 flex-1 flex-col">
          {detailLoading ? (
            <div className="flex flex-1 items-center justify-center gap-2 text-xs text-ink-3">
              <Spinner /> 读取主题…
            </div>
          ) : !detail ? (
            <EmptyState icon={MessageSquare} title="选择一个主题" description="从中间列表打开水源社区主题。" />
          ) : (
            <>
              <div className="shrink-0 border-b border-line bg-surface px-4 py-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <h2 className="text-sm font-semibold leading-relaxed text-ink">{detail.title}</h2>
                    <div className="mt-1 flex flex-wrap items-center gap-2 text-[0.64rem] text-ink-3">
                      {detail.categoryId ? <span>{categoryName(detail.categoryId)}</span> : null}
                      {detail.tags.map((tag) => (
                        <span key={tag} className="rounded bg-surface-3 px-1.5 py-0.5">
                          #{tag}
                        </span>
                      ))}
                      <span>{detail.postsCount} 回复</span>
                      {detail.views !== null ? <span>{detail.views} 浏览</span> : null}
                      <span>{formatDate(detail.createdAt, 'yyyy-MM-dd')}</span>
                    </div>
                  </div>
                  <div className="flex shrink-0 items-center gap-1">
                    <Button size="sm" variant="ghost" onClick={() => void toggleBookmark()} title="收藏">
                      <Bookmark className={cn('size-3.5', detail.bookmarked && 'fill-current text-accent')} />
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void call('app.openExternal', `${origin}/t/${detail.id}`)}
                      title="在浏览器打开"
                    >
                      <ExternalLink className="size-3.5" />
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => void loadTopic(detail.id)} title="刷新">
                      <RefreshCw className="size-3.5" />
                    </Button>
                  </div>
                </div>
              </div>
              <div
                ref={readerRef}
                onScroll={handleReaderScroll}
                className="scroll-thin min-h-0 flex-1 overflow-y-auto px-4 py-3"
              >
                {detail.posts.map((post) => (
                  <article key={post.id} className="mb-4 rounded-lg border border-line bg-surface p-3">
                    <div className="flex items-center gap-2">
                      <button
                        type="button"
                        onClick={() => void openProfile(post.username)}
                        title={`@${post.username} 的主页`}
                        className="shrink-0 rounded-full transition-opacity hover:opacity-80"
                      >
                        {avatarURL(post.avatarTemplate) ? (
                          <img
                            src={avatarURL(post.avatarTemplate) ?? ''}
                            alt=""
                            className="size-7 rounded-full bg-surface-3"
                          />
                        ) : (
                          <div className="grid size-7 place-items-center rounded-full bg-surface-3 text-ink-3">
                            <User className="size-4" />
                          </div>
                        )}
                      </button>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2 text-[0.68rem]">
                          <button
                            type="button"
                            onClick={() => void openProfile(post.username)}
                            className="rounded font-medium text-ink hover:text-accent hover:underline"
                          >
                            {post.name || post.username}
                          </button>
                          <button
                            type="button"
                            onClick={() => void openProfile(post.username)}
                            className="rounded text-ink-3 hover:text-accent hover:underline"
                          >
                            @{post.username}
                          </button>
                          {post.yours ? <Badge variant="accent">我</Badge> : null}
                          <span className="text-[0.6rem] text-ink-3">
                            #{post.postNumber} · {relativeTime(post.createdAt)}
                          </span>
                        </div>
                        {post.replyToPostNumber ? (
                          <div className="mt-0.5 text-[0.6rem] text-ink-3">
                            回复 #{post.replyToPostNumber}
                          </div>
                        ) : null}
                      </div>
                      <button
                        type="button"
                        onClick={() => void likePost(post)}
                        className={cn(
                          'flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[0.62rem] transition-colors',
                          post.liked ? 'text-accent' : 'text-ink-3 hover:text-ink',
                        )}
                      >
                        <ThumbsUp className={cn('size-3', post.liked && 'fill-current')} />
                        {post.likeCount ?? 0}
                      </button>
                      <button
                        type="button"
                        onClick={() => setReplyTo({ postNumber: post.postNumber, username: post.username })}
                        className="rounded-md px-1.5 py-0.5 text-[0.62rem] text-ink-3 hover:text-ink"
                      >
                        回复
                      </button>
                    </div>
                    <PostBody html={post.cooked} />
                  </article>
                ))}
                {loadingMorePosts ? (
                  <div className="mb-4 flex items-center justify-center gap-2 py-2 text-[0.66rem] text-ink-3">
                    <Loader2 className="size-3.5 animate-spin" /> 正在加载更多楼层（{detail.posts.length}/{detail.stream.length}）
                  </div>
                ) : null}
                {detail.stream.length > detail.posts.length ? (
                  <Button
                    className="mb-4 w-full"
                    variant="secondary"
                    onClick={() => void loadMorePosts()}
                    disabled={loadingMorePosts}
                  >
                    {loadingMorePosts ? <Loader2 className="size-3.5 animate-spin" /> : null}
                    加载更多楼层（{detail.posts.length}/{detail.stream.length}）
                  </Button>
                ) : null}
              </div>
              <div className="shrink-0 border-t border-line bg-surface p-3">
                {replyTo ? (
                  <div className="mb-2 flex items-center gap-2 text-[0.66rem] text-ink-3">
                    回复 #{replyTo.postNumber} @{replyTo.username}
                    <button type="button" onClick={() => setReplyTo(null)} className="text-ink-3 hover:text-ink">
                      <X className="size-3" />
                    </button>
                  </div>
                ) : null}
                <Textarea
                  value={replyText}
                  onChange={(event) => setReplyText(event.target.value)}
                  placeholder="写下你的回复…支持 Markdown"
                  className="h-24"
                />
                <div className="mt-2 flex items-center justify-between">
                  <Button size="sm" variant="secondary" onClick={() => void uploadReplyImage()} disabled={uploading}>
                    {uploading ? <Loader2 className="size-3.5 animate-spin" /> : <Upload className="size-3.5" />}
                    上传图片
                  </Button>
                  <Button variant="primary" onClick={() => void sendReply()} disabled={sendingReply || !replyText.trim()}>
                    {sendingReply ? <Loader2 className="size-4 animate-spin" /> : <Send className="size-4" />}
                    回复
                  </Button>
                </div>
              </div>
            </>
          )}
        </section>
      </div>

      <Dialog
        open={Boolean(profileUsername)}
        onOpenChange={(open) => {
          if (open) return;
          setProfileUsername(null);
          setProfile(null);
          setActivity(null);
        }}
      >
        <DialogContent
          title={profile ? profile.name || `@${profile.username}` : `@${profileUsername ?? ''}`}
          description={profile ? `@${profile.username}` : '水源社区个人主页'}
          wide
        >
          {profileLoading ? (
            <div className="flex items-center gap-2 py-10 text-xs text-ink-3">
              <Spinner /> 读取用户主页…
            </div>
          ) : !profile ? (
            <EmptyState compact icon={User} title="没有该用户" description="用户名可能已注销或被隐藏。" />
          ) : (
            <div className="flex flex-col gap-3">
              <div className="flex items-start gap-3">
                {avatarURL(profile.avatarTemplate) ? (
                  <img
                    src={avatarURL(profile.avatarTemplate) ?? ''}
                    alt=""
                    className="size-14 shrink-0 rounded-full border border-line bg-surface-3"
                  />
                ) : (
                  <div className="grid size-14 shrink-0 place-items-center rounded-full border border-line bg-surface-3 text-ink-3">
                    <User className="size-6" />
                  </div>
                )}
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="truncate text-sm font-semibold text-ink">{profile.name || profile.username}</span>
                    {profile.isSelf ? <Badge variant="accent">我</Badge> : null}
                    {profile.admin ? <Badge variant="warning">管理员</Badge> : null}
                    {profile.moderator ? <Badge variant="warning">版主</Badge> : null}
                    <Badge>信任等级 {profile.trustLevel}</Badge>
                  </div>
                  {profile.title ? <div className="mt-0.5 text-[0.68rem] text-ink-3">{profile.title}</div> : null}
                  <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-[0.64rem] text-ink-3">
                    <span>注册 {profile.cakedate ? formatDate(profile.cakedate, 'yyyy-M-d') : formatDate(profile.createdAt, 'yyyy-M-d')}</span>
                    <span>最后活跃 {profile.lastSeenAt ? relativeTime(profile.lastSeenAt) : '未知'}</span>
                    <span>资料浏览 {profile.profileViewCount}</span>
                    <span>采纳回答 {profile.acceptedAnswers}</span>
                    <span>徽章 {profile.badgeCount}</span>
                  </div>
                </div>
                <div className="flex shrink-0 flex-col gap-1.5">
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => void call('app.openExternal', `${origin}/u/${encodeURIComponent(profile.username)}`)}
                  >
                    <ExternalLink className="size-3.5" /> 浏览器打开
                  </Button>
                  {profile.isSelf ? null : (
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => {
                        setMessage({ to: profile.username, title: '', body: '' });
                        setMessageOpen(true);
                      }}
                    >
                      <Send className="size-3.5" /> 私信
                    </Button>
                  )}
                </div>
              </div>

              {profile.profileHidden ? (
                <div className="rounded-lg border border-warning/30 bg-warning/10 px-3 py-2 text-[0.66rem] text-warning">
                  该用户隐藏了个人主页，仅显示公开信息。
                </div>
              ) : null}

              <div className="flex gap-1 border-b border-line text-[0.7rem]">
                {(
                  [
                    { id: 'info', label: '资料' },
                    { id: 'badges', label: `徽章 ${profile.badges.length}` },
                    { id: 'activity', label: '动态' },
                  ] as const
                ).map((tab) => (
                  <button
                    key={tab.id}
                    type="button"
                    onClick={() => {
                      setProfileTab(tab.id);
                      if (tab.id === 'activity' && activity === null) void loadActivity(profile.username);
                    }}
                    className={cn(
                      '-mb-px border-b-2 px-3 py-1.5 transition-colors',
                      profileTab === tab.id
                        ? 'border-accent text-accent'
                        : 'border-transparent text-ink-3 hover:text-ink',
                    )}
                  >
                    {tab.label}
                  </button>
                ))}
              </div>

              {profileTab === 'info' ? (
                <div className="flex flex-col gap-3">
                  {profile.bioCooked ? (
                    <div className="rounded-lg border border-line bg-surface-2 p-3">
                      <PostBody html={profile.bioCooked} />
                    </div>
                  ) : (
                    <div className="text-[0.7rem] text-ink-3">这位用户还没有填写个人简介。</div>
                  )}
                  {profile.groups.length > 0 ? (
                    <div className="flex flex-wrap items-center gap-1.5 text-[0.66rem]">
                      <Users className="size-3.5 text-ink-3" />
                      {profile.groups.map((group) => (
                        <span key={group} className="rounded-md bg-surface-3 px-1.5 py-0.5 text-ink-2">
                          {group}
                        </span>
                      ))}
                    </div>
                  ) : null}
                </div>
              ) : null}

              {profileTab === 'badges' ? (
                profile.badges.length === 0 ? (
                  <EmptyState compact icon={Award} title="还没有徽章" description="在社区活跃会解锁更多徽章。" />
                ) : (
                  <div className="grid grid-cols-2 gap-2">
                    {profile.badges.map((badge, index) => (
                      <div key={`${badge.id}-${index}`} className="rounded-lg border border-line bg-surface-2 p-2.5">
                        <div className="flex items-center gap-2">
                          <Award className="size-4 shrink-0 text-accent" />
                          <span className="min-w-0 flex-1 truncate text-[0.72rem] font-medium text-ink">
                            {badge.name}
                            {badge.count > 1 ? <span className="ml-1 text-ink-3">×{badge.count}</span> : null}
                          </span>
                          {badge.badgeType ? <span className="shrink-0 text-[0.6rem] text-ink-3">{badge.badgeType}</span> : null}
                        </div>
                        {badge.description ? (
                          <div className="mt-1 line-clamp-2 text-[0.62rem] leading-relaxed text-ink-3">
                            {badge.description}
                          </div>
                        ) : null}
                        {badge.grantedAt ? (
                          <div className="mt-1 text-[0.58rem] text-ink-3">获得于 {formatDate(badge.grantedAt, 'yyyy-M-d')}</div>
                        ) : null}
                      </div>
                    ))}
                  </div>
                )
              ) : null}

              {profileTab === 'activity' ? (
                activity === null ? (
                  <div className="flex items-center gap-2 py-6 text-xs text-ink-3">
                    <Spinner /> 读取动态…
                  </div>
                ) : activity.length === 0 ? (
                  <EmptyState
                    compact
                    icon={MessageSquare}
                    title="没有公开动态"
                    description="该用户可能隐藏了活动记录，或还没有发帖。"
                  />
                ) : (
                  <div className="divide-y divide-line">
                    {activity.map((item) => (
                      <button
                        key={item.id}
                        type="button"
                        onClick={() => {
                          setProfileUsername(null);
                          void loadTopic(item.topicId, item.postNumber > 1 ? item.postNumber : null);
                        }}
                        className="flex w-full flex-col gap-1 py-2.5 text-left transition-colors hover:bg-hover"
                      >
                        <div className="flex items-center gap-2 text-[0.66rem]">
                          <Badge variant={item.kind === 'topic' ? 'accent' : undefined}>
                            {activityKindLabel(item.kind)}
                          </Badge>
                          <span className="min-w-0 flex-1 truncate text-ink">{item.topicTitle || `主题 #${item.topicId}`}</span>
                          <span className="shrink-0 text-ink-3">{item.createdAt ? relativeTime(item.createdAt) : ''}</span>
                        </div>
                        {item.blurb ? (
                          <div className="line-clamp-2 text-[0.64rem] leading-relaxed text-ink-3">{item.blurb}</div>
                        ) : null}
                      </button>
                    ))}
                  </div>
                )
              ) : null}
            </div>
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={composeOpen} onOpenChange={setComposeOpen}>
        <DialogContent
          title="发布新主题"
          description="发帖会直接写入水源社区，请遵守社区规范。"
          wide
          footer={
            <>
              <Button variant="ghost" onClick={() => setComposeOpen(false)}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void createTopic()} disabled={postingTopic}>
                {postingTopic ? <Loader2 className="size-4 animate-spin" /> : null}
                发布
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            <div className="grid grid-cols-[12rem_1fr] gap-3">
              <div>
                <label className="text-xs font-medium text-ink-2">分类</label>
                <select
                  value={compose.categoryId}
                  onChange={(event) => setCompose({ ...compose, categoryId: event.target.value })}
                  className="mt-1.5 h-9 w-full rounded-lg border border-line bg-surface px-2 text-xs text-ink focus:border-accent/60 focus:outline-none"
                >
                  {(structure?.categories ?? []).map((category) => (
                    <option key={category.id} value={category.id}>
                      {category.name}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="text-xs font-medium text-ink-2">标题</label>
                <Input
                  className="mt-1.5"
                  value={compose.title}
                  onChange={(event) => setCompose({ ...compose, title: event.target.value })}
                  placeholder="主题标题"
                />
              </div>
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">正文</label>
              <Textarea
                className="mt-1.5 h-56 font-mono text-[0.75rem]"
                value={compose.body}
                onChange={(event) => setCompose({ ...compose, body: event.target.value })}
                placeholder="支持 Markdown"
              />
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">标签</label>
              <Input
                className="mt-1.5"
                value={compose.tags}
                onChange={(event) => setCompose({ ...compose, tags: event.target.value })}
                placeholder="用逗号分隔，例如：课程, 选课"
              />
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={messageOpen} onOpenChange={setMessageOpen}>
        <DialogContent
          title="发送私信"
          description="私信会通过水源社区站内消息发送。"
          wide
          footer={
            <>
              <Button variant="ghost" onClick={() => setMessageOpen(false)}>
                取消
              </Button>
              <Button variant="primary" onClick={() => void sendMessage()} disabled={sendingMessage}>
                {sendingMessage ? <Loader2 className="size-4 animate-spin" /> : <Send className="size-4" />}
                发送
              </Button>
            </>
          }
        >
          <div className="space-y-3">
            <div>
              <label className="text-xs font-medium text-ink-2">收件人</label>
              <Input
                className="mt-1.5"
                value={message.to}
                onChange={(event) => setMessage({ ...message, to: event.target.value })}
                placeholder="用户名，多个用逗号分隔"
              />
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">标题</label>
              <Input
                className="mt-1.5"
                value={message.title}
                onChange={(event) => setMessage({ ...message, title: event.target.value })}
              />
            </div>
            <div>
              <label className="text-xs font-medium text-ink-2">正文</label>
              <Textarea
                className="mt-1.5 h-40"
                value={message.body}
                onChange={(event) => setMessage({ ...message, body: event.target.value })}
                placeholder="支持 Markdown"
              />
            </div>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={bookmarksOpen} onOpenChange={setBookmarksOpen}>
        <DialogContent title="我的收藏" description="水源社区主题收藏。" wide>
          {bookmarks.length === 0 ? (
            <EmptyState compact icon={Bookmark} title="没有收藏" description="在主题右上角点击收藏按钮。" />
          ) : (
            <div className="space-y-1">
              {bookmarks.map((bookmark) => (
                <button
                  key={bookmark.id}
                  type="button"
                  onClick={() => {
                    setBookmarksOpen(false);
                    void loadTopic(bookmark.topicId, bookmark.linkedPostNumber);
                  }}
                  className="flex w-full flex-col rounded-lg border border-line bg-surface-2 px-3 py-2 text-left hover:border-line-strong"
                >
                  <span className="truncate text-xs font-medium text-ink">{bookmark.title}</span>
                  {bookmark.excerpt ? (
                    <span className="mt-0.5 line-clamp-2 text-[0.66rem] text-ink-3">{bookmark.excerpt}</span>
                  ) : null}
                  <span className="mt-1 text-[0.6rem] text-ink-3">{relativeTime(bookmark.createdAt)}</span>
                </button>
              ))}
            </div>
          )}
        </DialogContent>
      </Dialog>

      <Dialog open={notificationsOpen} onOpenChange={setNotificationsOpen}>
        <DialogContent title="水源社区通知" description="最近的水源社区站内通知。" wide>
          {notifications.length === 0 ? (
            <EmptyState compact icon={Bell} title="没有通知" description="水源社区有新回复或提及时会出现在这里。" />
          ) : (
            <div className="space-y-1">
              {notifications.map((notification) => (
                <button
                  key={notification.id}
                  type="button"
                  onClick={() => {
                    setNotificationsOpen(false);
                    if (notification.topicId)
                      void loadTopic(notification.topicId, notification.postNumber ?? undefined);
                  }}
                  className={cn(
                    'flex w-full items-start gap-2 rounded-lg border border-line px-3 py-2 text-left hover:border-line-strong',
                    notification.read ? 'bg-surface' : 'bg-accent-soft/40',
                  )}
                >
                  <Bell className="mt-0.5 size-3.5 shrink-0 text-ink-3" />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-xs font-medium text-ink">
                      {notification.fancyTitle ?? '水源社区通知'}
                    </div>
                    <div className="mt-0.5 text-[0.62rem] text-ink-3">
                      {notification.displayUsername ?? ''} · {relativeTime(notification.createdAt)}
                    </div>
                  </div>
                </button>
              ))}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}

function feedPayload(feed: Feed): Record<string, unknown> {
  switch (feed.kind) {
    case 'category':
      return { kind: 'category', categoryId: feed.categoryId };
    case 'tag':
      return { kind: 'tag', tag: feed.tag };
    case 'search':
      return { kind: 'search', query: feed.query };
    case 'featured':
      return { kind: 'featured' };
    default:
      return { kind: 'latest' };
  }
}

function feedLabel(feed: Feed, categoryName: (id: number | null) => string) {
  switch (feed.kind) {
    case 'featured':
      return '精品';
    case 'category':
      return categoryName(feed.categoryId) || '分类';
    case 'tag':
      return `#${feed.tag}`;
    case 'search':
      return `搜索：${feed.query}`;
    default:
      return '最新';
  }
}

function FeedButton({
  active,
  label,
  onClick,
}: {
  active: boolean;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'mb-0.5 flex h-7 w-full items-center rounded-md px-2 text-left text-[0.68rem] transition-colors',
        active ? 'bg-selected text-accent' : 'text-ink-2 hover:bg-hover hover:text-ink',
      )}
    >
      {label}
    </button>
  );
}
