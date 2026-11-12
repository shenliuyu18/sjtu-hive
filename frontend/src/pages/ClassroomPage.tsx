import {
  Captions,
  Download,
  FileText,
  FolderDown,
  Loader2,
  LogIn,
  MapPin,
  MonitorPlay,
  RefreshCw,
  Search,
  Settings2,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { toast } from 'sonner';
import { call, on } from '@/lib/bridge';
import type {
  ClassroomVideo,
  VideoCourse,
  VideoDownload,
  VideoStream,
  VideoTranscript,
} from '@/lib/models';
import { formatBytes, formatDate, formatDuration, formatTime } from '@/lib/format';
import { cn } from '@/lib/cn';
import { useApp } from '@/stores/app';
import { useSettings } from '@/stores/settings';
import { Button } from '@/components/ui/Button';
import { Input } from '@/components/ui/Input';
import { Dialog, DialogContent } from '@/components/ui/Dialog';
import { Badge, Card, EmptyState, PageHeader, Spinner } from '@/components/ui/Misc';

export function ClassroomPage() {
  const navigate = useApp((state) => state.navigate);
  const settings = useSettings((state) => state.state);
  const [connected, setConnected] = useState(false);
  const [courses, setCourses] = useState<VideoCourse[]>([]);
  const [courseQuery, setCourseQuery] = useState('');
  const [selectedCourseId, setSelectedCourseId] = useState<number | null>(null);
  const [videos, setVideos] = useState<ClassroomVideo[]>([]);
  const [videoQuery, setVideoQuery] = useState('');
  const [selectedVideoId, setSelectedVideoId] = useState<number | null>(null);
  const [streams, setStreams] = useState<VideoStream[]>([]);
  const [selectedView, setSelectedView] = useState<number | null>(null);
  const [dualView, setDualView] = useState(false);
  const [transcript, setTranscript] = useState<VideoTranscript | null>(null);
  const [transcriptQuery, setTranscriptQuery] = useState('');
  const [currentTime, setCurrentTime] = useState(0);
  const [resumePosition, setResumePosition] = useState(0);
  const [loadingCourses, setLoadingCourses] = useState(true);
  const [loadingVideos, setLoadingVideos] = useState(false);
  const [loadingPlayer, setLoadingPlayer] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloads, setDownloads] = useState<VideoDownload[]>([]);
  const [downloadsOpen, setDownloadsOpen] = useState(false);
  const [downloadDir, setDownloadDir] = useState('');
  const [connecting, setConnecting] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const secondaryVideoRef = useRef<HTMLVideoElement>(null);
  const lastSaveRef = useRef(0);
  const resumeKeyRef = useRef('');
  const segmentRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const selectedCourse = courses.find((course) => course.id === selectedCourseId) ?? null;
  const selectedVideo = videos.find((video) => video.id === selectedVideoId) ?? null;
  const selectedStream = streams.find((stream) => stream.view === selectedView) ?? streams[0] ?? null;
  const secondaryStream = dualView
    ? streams.find((stream) => stream.view !== selectedStream?.view) ?? null
    : null;

  const loadCourses = async () => {
    setLoadingCourses(true);
    setError(null);
    try {
      const value = await call<VideoCourse[]>('classroom.courses');
      const list = value ?? [];
      setCourses(list);
      if (list.length > 0) {
        setSelectedCourseId((current) =>
          current && list.some((course) => course.id === current) ? current : list[0].id,
        );
      } else {
        setSelectedCourseId(null);
        setVideos([]);
        setSelectedVideoId(null);
      }
    } catch (cause) {
      const message = cause instanceof Error ? cause.message : '读取课堂视频失败';
      setError(message);
      setConnected(false);
      toast.error(message);
    } finally {
      setLoadingCourses(false);
    }
  };

  useEffect(() => {
    let alive = true;
    void call<{ connected: boolean }>('classroom.state')
      .then((value) => {
        if (!alive) return;
        setConnected(Boolean(value?.connected));
        if (value?.connected) void loadCourses();
        else setLoadingCourses(false);
      })
      .catch(() => setLoadingCourses(false));
    void call<string>('classroom.getDownloadDirectory')
      .then((value) => alive && setDownloadDir(value ?? ''))
      .catch(() => {});
    void call<VideoDownload[]>('classroom.downloads')
      .then((value) => alive && setDownloads(value ?? []))
      .catch(() => {});
    const offDownload = on('classroom.download', (value) => {
      const item = value as VideoDownload;
      if (!item?.id) return;
      setDownloads((current) => {
        const exists = current.some((entry) => entry.id === item.id);
        return exists
          ? current.map((entry) => (entry.id === item.id ? item : entry))
          : [...current, item];
      });
      if (item.state === 'completed') toast.success(`已下载 ${item.name}`);
    });
    const offError = on('classroom.download.error', (value) => {
      const payload = value as { error?: string };
      toast.error(payload?.error ?? '视频下载失败');
    });
    return () => {
      alive = false;
      offDownload();
      offError();
    };
  }, []);

  useEffect(() => {
    if (!selectedCourseId) return;
    let alive = true;
    setLoadingVideos(true);
    void call<ClassroomVideo[]>('classroom.videos', selectedCourseId)
      .then((value) => {
        if (!alive) return;
        const list = value ?? [];
        setVideos(list);
        setSelectedVideoId(list.length ? list[0].id : null);
      })
      .catch((cause) => {
        if (alive) toast.error(cause instanceof Error ? cause.message : '读取录像列表失败');
      })
      .finally(() => {
        if (alive) setLoadingVideos(false);
      });
    return () => {
      alive = false;
    };
  }, [selectedCourseId]);

  useEffect(() => {
    if (!selectedVideoId) {
      setStreams([]);
      setTranscript(null);
      setSelectedView(null);
      return;
    }
    let alive = true;
    setLoadingPlayer(true);
    setStreams([]);
    setDualView(false);
    setTranscript(null);
    setCurrentTime(0);
    setResumePosition(0);
    resumeKeyRef.current = '';
    lastSaveRef.current = 0;
    void Promise.allSettled([
      call<VideoStream[]>('classroom.streams', selectedVideoId),
      call<VideoTranscript | null>('classroom.transcript', selectedVideoId),
      call<{ position: number }>('classroom.progress', selectedVideoId),
    ]).then((results) => {
      if (!alive) return;
      const streamResult = results[0];
      const transcriptResult = results[1];
      const progressResult = results[2];
      if (streamResult.status === 'fulfilled') {
        const list = streamResult.value ?? [];
        setStreams(list);
        setSelectedView(list[0]?.view ?? null);
      } else {
        toast.error(
          streamResult.reason instanceof Error ? streamResult.reason.message : '读取播放地址失败',
        );
      }
      if (transcriptResult.status === 'fulfilled') setTranscript(transcriptResult.value ?? null);
      if (progressResult.status === 'fulfilled') {
        setResumePosition(progressResult.value?.position ?? 0);
      }
      setLoadingPlayer(false);
    });
    return () => {
      alive = false;
    };
  }, [selectedVideoId]);

  const activeIndex = useMemo(() => {
    if (!transcript) return -1;
    const ms = currentTime * 1000;
    let index = -1;
    for (let i = 0; i < transcript.segments.length; i += 1) {
      const segment = transcript.segments[i];
      if (ms >= segment.startMs && ms <= segment.endMs) return i;
      if (segment.startMs <= ms) index = i;
    }
    return index;
  }, [transcript, currentTime]);

  useEffect(() => {
    if (activeIndex < 0) return;
    segmentRefs.current[activeIndex]?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex]);

  const applyResume = () => {
    const video = videoRef.current;
    if (!video || !selectedVideoId || !selectedView) return;
    const key = `${selectedVideoId}:${selectedView}`;
    if (resumeKeyRef.current === key) return;
    if (resumePosition > 5 && video.duration > 0 && resumePosition < video.duration - 3) {
      video.currentTime = resumePosition;
      toast.info(`已从上次观看位置 ${formatDuration(resumePosition)} 继续`, { duration: 2200 });
    }
    resumeKeyRef.current = key;
  };

  useEffect(() => {
    applyResume();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resumePosition, selectedView, selectedVideoId]);

  const filteredCourses = useMemo(() => {
    const text = courseQuery.trim().toLowerCase();
    if (!text) return courses;
    return courses.filter(
      (course) =>
        course.name.toLowerCase().includes(text) || course.code.toLowerCase().includes(text),
    );
  }, [courses, courseQuery]);

  const filteredVideos = useMemo(() => {
    const text = videoQuery.trim().toLowerCase();
    if (!text) return videos;
    return videos.filter(
      (video) =>
        formatDate(video.start, 'M月d日').toLowerCase().includes(text) ||
        video.room.toLowerCase().includes(text) ||
        video.period.toLowerCase().includes(text),
    );
  }, [videos, videoQuery]);

  const filteredSegments = useMemo(() => {
    if (!transcript) return [];
    const text = transcriptQuery.trim().toLowerCase();
    if (!text) return transcript.segments;
    return transcript.segments.filter((segment) => segment.text.toLowerCase().includes(text));
  }, [transcript, transcriptQuery]);

  const seekTo = (seconds: number) => {
    const video = videoRef.current;
    if (!video) return;
    video.currentTime = seconds;
    void video.play().catch(() => {});
  };

  const saveProgress = () => {
    const video = videoRef.current;
    if (!video || !selectedVideo) return;
    void call('classroom.saveProgress', selectedVideo.id, video.currentTime, video.duration || 0).catch(
      () => {},
    );
  };

  const onTimeUpdate = () => {
    const video = videoRef.current;
    if (!video || !selectedVideo) return;
    setCurrentTime(video.currentTime);
    const now = Date.now();
    if (now - lastSaveRef.current > 5000) {
      lastSaveRef.current = now;
      void call('classroom.saveProgress', selectedVideo.id, video.currentTime, video.duration || 0).catch(
        () => {},
      );
    }
  };

  const syncSecondary = () => {
    const primary = videoRef.current;
    const secondary = secondaryVideoRef.current;
    if (!primary || !secondary) return;
    if (Math.abs(primary.currentTime - secondary.currentTime) > 1.5) {
      secondary.currentTime = primary.currentTime;
    }
    if (primary.paused && !secondary.paused) secondary.pause();
    if (!primary.paused && secondary.paused) void secondary.play().catch(() => {});
  };

  const switchView = (view: number) => {
    const video = videoRef.current;
    if (video) setResumePosition(video.currentTime);
    resumeKeyRef.current = '';
    setSelectedView(view);
  };

  const connect = async () => {
    if (connecting) return;
    setConnecting(true);
    try {
      const result = await call<{ ok: boolean; error?: string }>('classroom.connect');
      if (!result?.ok) throw new Error(result?.error ?? '登录失败');
      setConnected(true);
      toast.success('交大视频登录成功');
      await loadCourses();
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '登录失败');
    } finally {
      setConnecting(false);
    }
  };

  const downloadSelected = async () => {
    if (!selectedVideo || !selectedStream) return;
    const title = selectedCourse
      ? `${selectedCourse.name} ${formatDate(selectedVideo.start, 'M月d日')}`
      : `课堂视频 ${selectedVideo.id}`;
    try {
      const result = await call<{ started: boolean; id?: string }>(
        'classroom.download',
        selectedVideo.id,
        selectedStream.view,
        `${title}-画面${selectedStream.view}`,
      );
      if (result?.started) {
        toast.success('已开始下载，可在下载列表中查看');
        setDownloadsOpen(true);
      }
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '下载失败');
    }
  };

  const exportTranscript = async () => {
    if (!selectedVideo) return;
    const title = selectedCourse
      ? `${selectedCourse.name} ${formatDate(selectedVideo.start, 'M月d日')} 文稿`
      : `课堂视频 ${selectedVideo.id} 文稿`;
    try {
      const result = await call<{ saved: boolean; path?: string }>(
        'classroom.exportTranscript',
        selectedVideo.id,
        title,
      );
      if (result?.saved) toast.success('文稿已导出', { description: result.path });
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '导出失败');
    }
  };

  const chooseDownloadDir = async () => {
    try {
      const value = await call<string>('classroom.chooseDownloadDirectory');
      setDownloadDir(value ?? '');
      if (value) toast.success('已设置默认保存位置', { description: value });
    } catch (cause) {
      toast.error(cause instanceof Error ? cause.message : '设置保存位置失败');
    }
  };

  return (
    <div className="flex h-full flex-col">
      <PageHeader
        title="课堂视频"
        description="课堂录像回看、语音文稿与本地下载。"
        eyebrow="Classroom"
        actions={
          <>
            <Button variant="ghost" size="sm" onClick={() => void chooseDownloadDir()}>
              <FolderDown className="size-3.5" /> {downloadDir ? '保存位置' : '设置位置'}
            </Button>
            <Button variant="secondary" onClick={() => setDownloadsOpen(true)}>
              <Download className="size-4" /> 下载 {downloads.length ? `(${downloads.length})` : ''}
            </Button>
            {connected ? (
              <Button variant="secondary" onClick={() => void loadCourses()} disabled={loadingCourses}>
                <RefreshCw className={cn('size-4', loadingCourses && 'animate-spin')} /> 刷新
              </Button>
            ) : (
              <Button variant="primary" onClick={() => void connect()} disabled={connecting}>
                {connecting ? <Loader2 className="size-4 animate-spin" /> : <LogIn className="size-4" />}
                登录交大视频
              </Button>
            )}
          </>
        }
      />
      {!connected ? (
        <div className="flex flex-1 items-center justify-center p-6">
          <Card className="max-w-xl overflow-hidden">
            <div className="flex items-center gap-3 border-b border-line bg-surface-2 px-5 py-4">
              <div className="grid size-11 place-items-center rounded-xl bg-accent-soft text-accent">
                <MonitorPlay className="size-5" />
              </div>
              <div>
                <div className="text-sm font-semibold text-ink">登录交大视频</div>
                <div className="mt-0.5 text-xs text-ink-3">使用与日程相同的交大登录窗口。</div>
              </div>
            </div>
            <div className="space-y-3 p-5 text-xs leading-relaxed text-ink-2">
              <p>
                点击登录后打开独立 Edge 窗口，在交大官方页面完成 jAccount 登录；应用会自动读取
                <span className="font-mono"> .sjtu.edu.cn </span>
                Cookie 并加密保存。
              </p>
              <div className="rounded-lg border border-line bg-surface-2 p-3 text-[0.68rem] text-ink-3">
                登录成功后窗口会自动关闭；如果视频站仍未授权，请在登录窗口中打开一次 v.sjtu.edu.cn。
              </div>
              <div className="flex items-center gap-2">
                <Button variant="primary" onClick={() => void connect()} disabled={connecting}>
                  {connecting ? <Loader2 className="size-4 animate-spin" /> : <LogIn className="size-4" />}
                  登录交大视频
                </Button>
                <Button variant="ghost" onClick={() => navigate('settings', { settingsSection: 'accounts' })}>
                  <Settings2 className="size-3.5" /> 账户设置
                </Button>
              </div>
              {error ? <div className="text-[0.68rem] text-danger">{error}</div> : null}
            </div>
          </Card>
        </div>
      ) : (
        <div className="flex min-h-0 flex-1 border-t border-line">
          <aside className="flex w-56 shrink-0 flex-col border-r border-line bg-surface">
            <div className="border-b border-line p-2">
              <div className="relative">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
                <Input
                  value={courseQuery}
                  onChange={(event) => setCourseQuery(event.target.value)}
                  placeholder="搜索课程"
                  className="h-8 pl-8 text-xs"
                />
              </div>
            </div>
            <div className="scroll-thin min-h-0 flex-1 overflow-y-auto p-2">
              {loadingCourses ? (
                <div className="flex items-center justify-center gap-2 py-8 text-xs text-ink-3">
                  <Spinner /> 读取课程…
                </div>
              ) : filteredCourses.length === 0 ? (
                <div className="px-3 py-8 text-center text-[0.66rem] text-ink-3">
                  本学期没有课堂录像，或登录已失效。
                </div>
              ) : (
                filteredCourses.map((course) => (
                  <button
                    key={course.id}
                    type="button"
                    onClick={() => {
                      setSelectedCourseId(course.id);
                      setSelectedVideoId(null);
                    }}
                    className={cn(
                      'mb-0.5 w-full rounded-md px-2 py-2 text-left transition-colors',
                      course.id === selectedCourseId
                        ? 'bg-selected text-accent'
                        : 'text-ink-2 hover:bg-hover hover:text-ink',
                    )}
                  >
                    <div className="truncate text-xs font-medium">{course.name}</div>
                    <div className="mt-0.5 truncate text-[0.62rem] text-ink-3">{course.code}</div>
                  </button>
                ))
              )}
            </div>
          </aside>

          <aside className="flex w-72 shrink-0 flex-col border-r border-line bg-surface">
            <div className="border-b border-line px-3 py-2">
              <div className="text-xs font-semibold text-ink">录像列表</div>
              <div className="mt-0.5 text-[0.62rem] text-ink-3">{selectedCourse?.name ?? '选择课程'}</div>
              <div className="relative mt-2">
                <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
                <Input
                  value={videoQuery}
                  onChange={(event) => setVideoQuery(event.target.value)}
                  placeholder="搜索日期或教室"
                  className="h-8 pl-8 text-xs"
                />
              </div>
            </div>
            <div className="scroll-thin min-h-0 flex-1 overflow-y-auto">
              {loadingVideos ? (
                <div className="flex items-center justify-center gap-2 py-10 text-xs text-ink-3">
                  <Spinner /> 读取录像…
                </div>
              ) : filteredVideos.length === 0 ? (
                <EmptyState compact icon={MonitorPlay} title="没有录像" description="这门课暂时没有可回看的录像。" />
              ) : (
                filteredVideos.map((video) => (
                  <button
                    key={video.id}
                    type="button"
                    onClick={() => setSelectedVideoId(video.id)}
                    className={cn(
                      'flex w-full flex-col gap-1 border-b border-line px-3 py-2.5 text-left transition-colors',
                      video.id === selectedVideoId ? 'bg-selected/70' : 'hover:bg-hover',
                    )}
                  >
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-medium text-ink">
                        {formatDate(video.start, 'M月d日 EEEE')}
                      </span>
                      {video.period ? <Badge variant="neutral">第 {video.period} 节</Badge> : null}
                    </div>
                    <div className="flex items-center gap-2 text-[0.62rem] text-ink-3">
                      <span>
                        {formatTime(video.start)} – {formatTime(video.end)}
                      </span>
                      {video.room ? (
                        <span className="inline-flex items-center gap-1">
                          <MapPin className="size-3" /> {video.room}
                        </span>
                      ) : null}
                    </div>
                  </button>
                ))
              )}
            </div>
          </aside>

          <section className={cn('flex min-w-0 flex-1 flex-col bg-bg', dualView && 'scroll-thin overflow-y-auto')}>
            {loadingPlayer ? (
              <div className="flex flex-1 items-center justify-center gap-2 text-xs text-ink-3">
                <Spinner /> 正在获取播放地址与文稿…
              </div>
            ) : !selectedVideo ? (
              <EmptyState icon={MonitorPlay} title="选择一段录像" description="从左侧课程与录像列表选择课堂回放。" />
            ) : (
              <>
                <div className="shrink-0 border-b border-line bg-surface px-4 py-2.5">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="min-w-0">
                      <div className="truncate text-sm font-semibold text-ink">
                        {selectedCourse?.name} · {formatDate(selectedVideo.start, 'M月d日')}
                      </div>
                      <div className="mt-0.5 text-[0.66rem] text-ink-3">
                        {formatTime(selectedVideo.start)} – {formatTime(selectedVideo.end)}
                        {selectedVideo.room ? ` · ${selectedVideo.room}` : ''}
                        {selectedVideo.period ? ` · 第 ${selectedVideo.period} 节` : ''}
                      </div>
                    </div>
                    <div className="flex flex-wrap items-center gap-1.5">
                      {streams.map((stream) => (
                        <button
                          key={stream.view}
                          type="button"
                          onClick={() => switchView(stream.view)}
                          className={cn(
                            'rounded-md px-2.5 py-1 text-[0.66rem] font-medium transition-colors',
                            selectedView === stream.view
                              ? 'bg-accent-soft text-accent'
                              : 'text-ink-3 hover:bg-hover hover:text-ink',
                          )}
                        >
                          画面 {stream.view}
                        </button>
                      ))}
                      {streams.length > 1 ? (
                        <button
                          type="button"
                          onClick={() => setDualView((value) => !value)}
                          className={cn(
                            'rounded-md px-2.5 py-1 text-[0.66rem] font-medium transition-colors',
                            dualView
                              ? 'bg-accent-soft text-accent'
                              : 'text-ink-3 hover:bg-hover hover:text-ink',
                          )}
                        >
                          {dualView ? '单画面' : '双画面'}
                        </button>
                      ) : null}
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void downloadSelected()}
                        title="下载当前画面"
                      >
                        <Download className="size-3.5" /> 下载
                      </Button>
                      <Button size="sm" variant="ghost" onClick={() => void exportTranscript()} title="导出文稿">
                        <FileText className="size-3.5" /> 导出文稿
                      </Button>
                    </div>
                  </div>
                </div>
                <div className={cn(dualView ? 'flex flex-col' : 'shrink-0 bg-black')}>
                  {selectedStream ? (
                    <div
                      className={cn(
                        'flex w-full',
                        dualView
                          ? 'min-h-0 flex-1 flex-col gap-1'
                          : 'max-h-[46vh] items-center gap-0.5 bg-black',
                      )}
                    >
                      <div className={cn('flex min-h-0 min-w-0 w-full items-center justify-center', !dualView && 'flex-1')}>
                        <video
                          key={`${selectedVideo.id}-${selectedStream.view}`}
                          ref={videoRef}
                          src={selectedStream.playbackUrl}
                          controls
                          playsInline
                          preload="metadata"
                          className={cn(
                            dualView ? 'aspect-video w-full rounded-xl object-contain ring-1 ring-line' : 'max-h-[46vh] w-full bg-black',
                          )}
                          onLoadedMetadata={() => {
                            applyResume();
                          }}
                          onTimeUpdate={() => {
                            onTimeUpdate();
                            syncSecondary();
                          }}
                          onPlay={syncSecondary}
                          onSeeking={syncSecondary}
                          onPause={() => {
                            saveProgress();
                            syncSecondary();
                          }}
                        />
                      </div>
                      {secondaryStream ? (
                        <div className={cn('flex min-h-0 min-w-0 w-full items-center justify-center', !dualView && 'flex-1')}>
                          <video
                            key={`${selectedVideo.id}-${secondaryStream.view}`}
                            ref={secondaryVideoRef}
                            src={secondaryStream.playbackUrl}
                            muted
                            playsInline
                            preload="metadata"
                            className={cn(
                              dualView ? 'aspect-video w-full rounded-xl object-contain ring-1 ring-line' : 'max-h-[46vh] w-full bg-black',
                            )}
                            onLoadedMetadata={() => {
                              const primary = videoRef.current;
                              const secondary = secondaryVideoRef.current;
                              if (!primary || !secondary) return;
                              const target = primary.readyState > 0 ? primary.currentTime : resumePosition;
                              if (target > 0) secondary.currentTime = target;
                              if (!primary.paused) void secondary.play().catch(() => {});
                            }}
                          />
                        </div>
                      ) : null}
                    </div>
                  ) : (
                    <div className="flex h-56 items-center justify-center text-xs text-white/50">暂无可用画面</div>
                  )}
                </div>
                <div
                  className={cn(
                    'flex min-h-0 flex-col border-t border-line bg-surface',
                    dualView ? 'h-[52vh] shrink-0' : 'flex-1',
                  )}
                >
                  <div className="flex shrink-0 items-center justify-between gap-2 border-b border-line px-4 py-2">
                    <div className="flex items-center gap-2">
                      <Captions className="size-4 text-accent" />
                      <div className="text-xs font-semibold text-ink">语音文稿</div>
                      {transcript ? (
                        <span className="text-[0.62rem] text-ink-3">{transcript.segments.length} 段</span>
                      ) : null}
                    </div>
                    <div className="relative">
                      <Search className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-3" />
                      <Input
                        value={transcriptQuery}
                        onChange={(event) => setTranscriptQuery(event.target.value)}
                        placeholder="搜索文稿"
                        className="h-7 w-40 pl-8 text-[0.68rem]"
                      />
                    </div>
                  </div>
                  <div className="scroll-thin min-h-0 flex-1 overflow-y-auto p-2">
                    {!transcript ? (
                      <EmptyState compact icon={Captions} title="暂无语音文稿" description="这门录像还没有可用的语音识别文稿。" />
                    ) : filteredSegments.length === 0 ? (
                      <div className="px-4 py-8 text-center text-xs text-ink-3">没有匹配的文稿片段</div>
                    ) : (
                      filteredSegments.map((segment, index) => {
                        const originalIndex = transcript.segments.indexOf(segment);
                        const active = originalIndex === activeIndex;
                        return (
                          <button
                            key={`${segment.startMs}-${index}`}
                            ref={(element) => {
                              segmentRefs.current[originalIndex] = element;
                            }}
                            type="button"
                            onClick={() => seekTo(segment.startMs / 1000)}
                            className={cn(
                              'flex w-full items-start gap-2 rounded-md px-2 py-1.5 text-left text-[0.72rem] leading-relaxed transition-colors',
                              active ? 'bg-accent-soft text-accent' : 'text-ink-2 hover:bg-hover',
                            )}
                          >
                            <span className="w-12 shrink-0 font-mono text-[0.62rem] text-ink-3">
                              {formatDuration(segment.startMs / 1000)}
                            </span>
                            <span className="min-w-0 flex-1">{segment.text}</span>
                          </button>
                        );
                      })
                    )}
                  </div>
                </div>
              </>
            )}
          </section>
        </div>
      )}

      <Dialog open={downloadsOpen} onOpenChange={setDownloadsOpen}>
        <DialogContent
          title="视频下载"
          description={downloadDir ? `默认保存位置：${downloadDir}` : '下载时会选择保存位置。'}
          wide
          footer={
            <>
              <Button variant="ghost" onClick={() => setDownloadsOpen(false)}>
                关闭
              </Button>
              <Button variant="secondary" onClick={() => void chooseDownloadDir()}>
                <FolderDown className="size-4" /> 设置默认位置
              </Button>
            </>
          }
        >
          {downloads.length === 0 ? (
            <EmptyState compact icon={Download} title="没有下载任务" description="在播放器上点击「下载」保存当前画面。" />
          ) : (
            <div className="space-y-2">
              {downloads.map((item) => {
                const percent = item.total > 0 ? Math.round((item.received / item.total) * 100) : 0;
                return (
                  <div key={item.id} className="rounded-lg border border-line bg-surface-2 p-3">
                    <div className="flex items-center gap-2">
                      <div className="min-w-0 flex-1 truncate text-xs font-medium text-ink">{item.name}</div>
                      <Badge
                        variant={
                          item.state === 'completed'
                            ? 'success'
                            : item.state === 'progressing'
                              ? 'accent'
                              : 'neutral'
                        }
                      >
                        {item.state === 'completed'
                          ? '已完成'
                          : item.state === 'progressing'
                            ? '下载中'
                            : item.state === 'cancelled'
                              ? '已取消'
                              : '已中断'}
                      </Badge>
                    </div>
                    <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-line">
                      <div
                        className="h-full rounded-full bg-accent transition-[width]"
                        style={{ width: `${item.state === 'completed' ? 100 : percent}%` }}
                      />
                    </div>
                    <div className="mt-1 flex items-center justify-between text-[0.62rem] text-ink-3">
                      <span>
                        {formatBytes(item.received)} / {item.total ? formatBytes(item.total) : '—'}
                      </span>
                      <div className="flex items-center gap-1">
                        {item.state === 'progressing' ? (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => void call('classroom.cancelDownload', item.id)}
                          >
                            取消
                          </Button>
                        ) : null}
                        {item.state === 'completed' ? (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => void call('classroom.revealDownload', item.id)}
                          >
                            打开文件夹
                          </Button>
                        ) : null}
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  );
}
