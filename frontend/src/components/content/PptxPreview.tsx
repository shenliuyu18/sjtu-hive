import JSZip from 'jszip';
import { init } from 'pptx-preview';
import { useEffect, useRef, useState } from 'react';
import { Spinner } from '@/components/ui/Misc';
import { findSlideVideos, type SlideVideo } from './pptxVideo';

const BASE_WIDTH = 1120;

export function PptxPreview({ url }: { url: string }) {
  const hostRef = useRef<HTMLDivElement>(null);
  const zipRef = useRef<{ url: string; zip: JSZip } | null>(null);
  const videosRef = useRef<SlideVideo[]>([]);
  const openVideoRef = useRef<(part: string) => void>(() => {});
  const playingURLRef = useRef<string | null>(null);
  const [zoom, setZoom] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [playing, setPlaying] = useState<{ url: string; name: string } | null>(null);
  const bufferRef = useRef<{ url: string; data: ArrayBuffer } | null>(null);

  openVideoRef.current = (part) => {
    void (async () => {
      const entry = zipRef.current?.zip.file(part);
      if (!entry) return;
      const blob = await entry.async('blob');
      const next = URL.createObjectURL(blob);
      if (playingURLRef.current) URL.revokeObjectURL(playingURLRef.current);
      playingURLRef.current = next;
      setPlaying({ url: next, name: part.split('/').pop() ?? '视频' });
    })();
  };

  const closeVideo = () => {
    if (playingURLRef.current) URL.revokeObjectURL(playingURLRef.current);
    playingURLRef.current = null;
    setPlaying(null);
  };

  useEffect(() => {
    return () => {
      if (playingURLRef.current) URL.revokeObjectURL(playingURLRef.current);
    };
  }, []);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;
    let frame = 0;
    const measure = () => {
      if (frame) cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        const available = host.parentElement?.clientWidth ?? host.clientWidth;
        setZoom(Math.min(1, available / (BASE_WIDTH + 32)));
      });
    };
    measure();
    const observer = new ResizeObserver(measure);
    if (host.parentElement) observer.observe(host.parentElement);
    return () => {
      if (frame) cancelAnimationFrame(frame);
      observer.disconnect();
    };
  }, [url]);

  useEffect(() => {
    let alive = true;
    const controller = new AbortController();
    setLoading(true);
    setError(null);

    const decorate = (host: HTMLElement) => {
      const wrappers = Array.from(host.querySelectorAll<HTMLElement>('.pptx-preview-slide-wrapper'));
      wrappers.forEach((wrapper, index) => {
        if (wrapper.dataset.videoReady === '1') return;
        const video = videosRef.current.find((item) => item.index === index);
        if (!video) return;
        wrapper.dataset.videoReady = '1';
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'pptx-video-play';
        button.style.left = `${(video.x + video.width / 2) * 100}%`;
        button.style.top = `${(video.y + video.height / 2) * 100}%`;
        button.textContent = '▶ 播放视频';
        button.addEventListener('click', (event) => {
          event.preventDefault();
          event.stopPropagation();
          openVideoRef.current(video.part);
        });
        wrapper.appendChild(button);
      });
    };

    void (async () => {
      try {
        let buffer = bufferRef.current?.url === url ? bufferRef.current.data : null;
        if (!buffer) {
          const response = await fetch(url, { signal: controller.signal });
          if (!response.ok) throw new Error(`读取演示文稿失败（HTTP ${response.status}）`);
          buffer = await response.arrayBuffer();
          bufferRef.current = { url, data: buffer };
        }
        if (!alive || !hostRef.current) return;
        let zip = zipRef.current?.url === url ? zipRef.current.zip : null;
        if (!zip) {
          zip = await JSZip.loadAsync(buffer);
          zipRef.current = { url, zip };
        }
        videosRef.current = await findSlideVideos(zip);
        if (!alive || !hostRef.current) return;
        const host = hostRef.current;
        host.innerHTML = '';
        const viewer = init(host, { width: BASE_WIDTH, mode: 'list' });
        const reveal = () => {
          if (host.querySelector('.pptx-preview-slide-wrapper')) setLoading(false);
          decorate(host);
        };
        const observer = new MutationObserver(reveal);
        observer.observe(host, { childList: true, subtree: true });
        try {
          await viewer.preview(buffer);
        } finally {
          observer.disconnect();
          reveal();
        }
      } catch (cause) {
        if (!alive || controller.signal.aborted) return;
        setError(cause instanceof Error ? cause.message : String(cause));
      } finally {
        if (alive) setLoading(false);
      }
    })();

    return () => {
      alive = false;
      controller.abort();
    };
  }, [url]);

  return (
    <div className="relative h-full overflow-auto bg-surface-3 p-4 [scrollbar-gutter:stable]">
      {loading ? (
        <div className="pointer-events-none absolute left-0 right-0 top-0 z-10 flex justify-center py-2">
          <span className="flex items-center gap-2 rounded-full border border-line bg-surface/95 px-3 py-1.5 text-[0.66rem] text-ink-3 shadow-[var(--shadow-card)]">
            <Spinner /> 正在渲染幻灯片…
          </span>
        </div>
      ) : null}
      {error ? (
        <div className="mx-auto mt-10 max-w-md rounded-lg border border-danger/30 bg-danger/10 p-4 text-xs text-danger">
          {error}
        </div>
      ) : null}
      <div ref={hostRef} className="pptx-preview-host mx-auto" style={{ width: BASE_WIDTH, zoom }} />
      {playing ? (
        <div className="fixed inset-0 z-[90] flex flex-col bg-black">
          <div className="flex shrink-0 items-center justify-between gap-3 border-b border-white/10 px-4 py-2 text-xs text-white/80">
            <span className="truncate">{playing.name}</span>
            <button
              type="button"
              onClick={closeVideo}
              className="shrink-0 rounded-md border border-white/30 px-3 py-1 text-xs text-white transition-colors hover:bg-white/10"
            >
              关闭
            </button>
          </div>
          <video key={playing.url} src={playing.url} controls autoPlay className="min-h-0 flex-1 bg-black" />
        </div>
      ) : null}
    </div>
  );
}
