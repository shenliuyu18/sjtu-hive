package com.sjtuhive.app.ui.video

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.delay

/** 播放源：本地文件或带请求头的远端地址（课堂视频 CDN 需要 Referer/Origin）。 */
sealed class HiveVideoSource {
    data class Local(val file: java.io.File) : HiveVideoSource()
    data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : HiveVideoSource()
}

/** 统一的播放控制句柄：课堂视频的双画面同步、进度保存都通过它操作。 */
interface HiveVideoHandle {
    fun play()
    fun pause()
    fun seekTo(ms: Long)
    fun release()
    val positionMs: Long
    val durationMs: Long
    val isPlaying: Boolean
    var volume: Float
}

/** 播放控制器。 */
class HiveVideoController : HiveVideoHandle {
    internal var player: Player? = null
    internal var released = false

    override fun play() {
        runCatching {
            val p = player ?: return
            // 播完（ENDED）后按播放要从头开始，ExoPlayer 停在末尾直接 play() 无效
            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
            p.play()
        }
    }

    override fun pause() {
        runCatching { player?.pause() }
    }

    override fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms.coerceAtLeast(0L)) }
    }

    override fun release() {
        runCatching { player?.release() }
        player = null
        released = true
    }

    override val positionMs: Long
        get() = runCatching { player?.currentPosition ?: 0L }.getOrDefault(0L)

    override val durationMs: Long
        get() = runCatching { (player?.duration ?: 0L).coerceAtLeast(0L) }.getOrDefault(0L)

    override val isPlaying: Boolean
        get() = runCatching { player?.isPlaying ?: false }.getOrDefault(false)

    override var volume: Float = 1f
        set(value) {
            field = value
            runCatching { player?.volume = value }
        }
}

/**
 * 统一的视频播放组件（ExoPlayer 引擎 + 自绘 Compose 控制条）。
 *
 * 所有出现视频的界面都走这里：文件预览、课件内嵌视频、课堂视频主/副画面。
 * - 远端流支持自定义请求头（课堂视频 CDN 需要 Referer/Origin）；
 * - ExoPlayer 自带网络重试与缓冲恢复，缓解偶发 connection abort；
 * - RESIZE_MODE_FIT 保证任何宽高比都不拉伸变形；
 * - 控制条：点击画面显隐，播放中 3 秒自动隐藏，底部渐变 + 居中大播放键。
 */
@Composable
fun HiveVideoPlayer(
    source: HiveVideoSource,
    modifier: Modifier = Modifier,
    autoStart: Boolean = true,
    startPositionMs: Long = 0,
    muted: Boolean = false,
    showControls: Boolean = true,
    onReady: ((width: Int, height: Int, duration: Int) -> Unit)? = null,
    onProgress: ((positionMs: Long) -> Unit)? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    onPlayerCreated: ((HiveVideoHandle) -> Unit)? = null,
    onSurfaceTap: (() -> Unit)? = null,
    /** 播放态变化回调（外部用于控制条自动隐藏等）。 */
    onPlayingChange: ((Boolean) -> Unit)? = null,
    /** 受控模式：控制条显隐由外部状态决定（与父级其他控制条同步）。 */
    controlsVisibleOverride: Boolean? = null,
    /** 受控模式：用户点击画面时回调，由外部翻转显隐状态。 */
    onControlsToggle: (() -> Unit)? = null,
    /** 用户交互（点播放/拖进度）回调，外部用于重置自动隐藏计时。 */
    onUserInteraction: (() -> Unit)? = null,
    /** 变化时整个播放器重建（加载失败重试）。 */
    restartKey: Any? = null,
): HiveVideoController {
    val context = LocalContext.current
    val controller = remember { HiveVideoController() }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var internalControlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val controlsShown = controlsVisibleOverride ?: internalControlsVisible
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }
    var videoAspect by remember { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf(android.util.Size(1, 1)) }

    // 引擎生命周期：随组合创建与释放
    DisposableEffect(source, restartKey) {
        // 本地文件用默认工厂（file:// 走 FileDataSource）；
        // 远端流注入带请求头的 HTTP 工厂（课堂视频 CDN 需要 Referer/Origin）。
        val mediaSourceFactory = when (source) {
            is HiveVideoSource.Local -> DefaultMediaSourceFactory(context)
            is HiveVideoSource.Remote -> DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(15_000)
                    .setUserAgent("Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 HiveVideoPlayer")
                    .setDefaultRequestProperties(
                        source.headers + mapOf("Accept" to "*/*"),
                    ),
            )
        }
        val media = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
        controller.player = media
        onPlayerCreated?.invoke(controller)
        textureView?.let { runCatching { media.setVideoTextureView(it) } }
        var cancelled = false

        media.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ false,
        )
        media.volume = if (muted) 0f else 1f

        val item = when (source) {
            is HiveVideoSource.Local -> MediaItem.fromUri(
                Uri.fromFile(source.file).toString(),
            )
            is HiveVideoSource.Remote -> MediaItem.fromUri(source.url)
        }
        media.setMediaItem(item)
        if (startPositionMs > 1000) media.seekTo(startPositionMs)
        media.playWhenReady = autoStart
        media.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (cancelled) return
                if (playbackState == Player.STATE_READY) {
                    ready = true
                    duration = media.duration.coerceAtLeast(0L)
                    val size = media.videoSize
                    if (size.width > 0 && size.height > 0) {
                        videoAspect = size.width.toFloat() / size.height.toFloat()
                    }
                    onReady?.invoke(size.width, size.height, media.duration.toInt())
                }
                if (playbackState == Player.STATE_ENDED) {
                    playing = false
                    onCompleted?.invoke()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                onPlayingChange?.invoke(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("HiveVideo", "player error code=${error.errorCode}", error)
                onError?.invoke(
                    when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
                        -> "视频源读取失败（${error.errorCode}）"
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                        -> "视频编码不受支持（${error.errorCode}）"
                        else -> error.message ?: "播放失败（${error.errorCode}）"
                    }
                )
            }
        })
        media.prepare()

        onDispose {
            cancelled = true
            runCatching { media.release() }
            controller.player = null
        }
    }

    // 控制条自动隐藏（仅自主模式）：播放中 3 秒无操作收起
    if (showControls && controlsVisibleOverride == null) {
        LaunchedEffect(playing, interactionTick, ready) {
            if (ready && playing) {
                delay(3000)
                internalControlsVisible = false
            }
        }
    }

    // 连接超时：长时间不到 READY 就明确报错，而不是无限转圈
    if (onError != null) {
        LaunchedEffect(source, restartKey, ready) {
            if (!ready) {
                delay(20_000)
                if (!ready && !controller.released) {
                    runCatching { controller.player?.stop() }
                    onError("视频源连接超时，请检查网络后重试")
                }
            }
        }
    }

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged {
                containerSize = android.util.Size(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1))
            },
    ) {
        AndroidView(
            // TextureView 会把画面拉伸到自身边界：按视频宽高比做 FIT 缩放，
            // 任何容器任何视频比例都不变形，黑边由容器背景补上。
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (videoAspect > 0f) {
                        // FIT（contain）：完整显示画面，不足处由容器黑底补齐
                        val containerAspect = containerSize.width.toFloat() / containerSize.height
                        if (videoAspect > containerAspect) {
                            // 视频更宽：占满宽度，高度缩放
                            scaleX = 1f
                            scaleY = containerAspect / videoAspect
                        } else {
                            // 视频更高：占满高度，宽度缩放
                            scaleX = videoAspect / containerAspect
                            scaleY = 1f
                        }
                    }
                },
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            textureView = this@apply
                            runCatching { controller.player?.setVideoTextureView(this@apply) }
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            runCatching { controller.player?.setVideoSurface(null) }
                            return true
                        }

                        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            },
        )

        if (showControls || onSurfaceTap != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = MutableInteractionSource(),
                        indication = null,
                    ) {
                        onUserInteraction?.invoke()
                        if (onControlsToggle != null) {
                            onControlsToggle()
                        } else {
                            interactionTick++
                            internalControlsVisible = !internalControlsVisible
                        }
                        onSurfaceTap?.invoke()
                    },
            )

            AnimatedVisibility(
                visible = controlsShown && !playing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable {
                            interactionTick++
                            // 播完后点击 = 从头重播（play() 内部处理 ENDED→seekTo(0)）
                            controller.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsShown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!ready) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.5f),
                        )
                        Text(
                            "缓冲中…",
                            color = Color.White,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                        )
                    } else {
                    IconButton(onClick = {
                        onUserInteraction?.invoke()
                        if (playing) controller.pause() else controller.play()
                    }) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                        )
                    }}
                    Slider(
                        value = if (duration > 0) {
                            (if (scrubbing) scrubPosition else position).toFloat() / duration
                        } else {
                            0f
                        },
                        onValueChange = { fraction ->
                            onUserInteraction?.invoke()
                            interactionTick++
                            scrubbing = true
                            scrubPosition = (fraction * duration).toLong()
                        },
                        onValueChangeFinished = {
                            controller.seekTo(scrubPosition)
                            position = scrubPosition
                            scrubbing = false
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                    Text(
                        "${formatClock(if (scrubbing) scrubPosition else position)} / ${formatClock(duration)}",
                        color = Color.White,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }
    }

    // 进度轮询：有回调时上报，否则仅维护内部时间显示
    LaunchedEffect(ready, showControls) {
        while (ready) {
            if (controller.isPlaying) {
                position = controller.positionMs
                onProgress?.invoke(position)
            }
            delay(500)
        }
    }

    return controller
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * MediaPlayer 降级播放器：ExoPlayer 的 MP4 解析器对个别文件（如 SJTU 录像的
 * 特殊 moov 结构）会误报 malformed，系统解析器能正常处理时自动切到这里。
 * 控制条样式与 ExoPlayer 版本一致。
 */
@Composable
fun MediaPlayerVideo(
    file: java.io.File,
    modifier: Modifier = Modifier,
    autoStart: Boolean = true,
    startPositionMs: Long = 0,
    showControls: Boolean = true,
    onReady: ((width: Int, height: Int, duration: Int) -> Unit)? = null,
    onCompleted: (() -> Unit)? = null,
    onError: ((String) -> Unit)? = null,
    onPlayingChange: ((Boolean) -> Unit)? = null,
    controlsVisibleOverride: Boolean? = null,
    onControlsToggle: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var internalControlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var surfaceReady by remember { mutableStateOf<android.view.Surface?>(null) }
    val controlsShown = controlsVisibleOverride ?: internalControlsVisible
    val player = remember { android.media.MediaPlayer() }
    var videoAspect by remember { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf(android.util.Size(1, 1)) }

    DisposableEffect(file) {
        var cancelled = false
        runCatching {
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            player.setDataSource(file.absolutePath)
            player.setOnPreparedListener { mp ->
                if (cancelled) return@setOnPreparedListener
                prepared = true
                duration = mp.duration.toLong().coerceAtLeast(0L)
                if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                    videoAspect = mp.videoWidth.toFloat() / mp.videoHeight.toFloat()
                }
                val resume = startPositionMs.takeIf { it > 1000 && it < mp.duration - 3000 } ?: 0L
                if (resume > 0) mp.seekTo(resume.toInt())
                if (autoStart) mp.start()
                playing = autoStart
                onPlayingChange?.invoke(autoStart)
                onReady?.invoke(mp.videoWidth, mp.videoHeight, mp.duration)
            }
            player.setOnErrorListener { _, what, extra ->
                onError?.invoke("系统播放器错误（$what/$extra）")
                true
            }
            if (onCompleted != null) {
                player.setOnCompletionListener { onCompleted() }
            }
            surfaceReady?.let { runCatching { player.setSurface(it) } }
            player.prepareAsync()
        }.onFailure { failure -> onError?.invoke(failure.message ?: "无法播放") }

        onDispose {
            cancelled = true
            runCatching { player.release() }
        }
    }

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged {
                containerSize = android.util.Size(it.width.coerceAtLeast(1), it.height.coerceAtLeast(1))
            },
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (videoAspect > 0f) {
                        // FIT（contain）：完整显示画面，不足处由容器黑底补齐
                        val containerAspect = containerSize.width.toFloat() / containerSize.height
                        if (videoAspect > containerAspect) {
                            // 视频更宽：占满宽度，高度缩放
                            scaleX = 1f
                            scaleY = containerAspect / videoAspect
                        } else {
                            // 视频更高：占满高度，宽度缩放
                            scaleX = videoAspect / containerAspect
                            scaleY = 1f
                        }
                    }
                },
            factory = { viewContext ->
                TextureView(viewContext).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val surface = Surface(surfaceTexture)
                            surfaceReady = surface
                            runCatching { player.setSurface(surface) }
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            runCatching { player.setSurface(null) }
                            return true
                        }

                        override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = Unit

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            },
        )

        if (showControls && prepared) {
            if (controlsVisibleOverride == null) {
                LaunchedEffect(playing, interactionTick) {
                    if (playing) {
                        delay(3000)
                        internalControlsVisible = false
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = MutableInteractionSource(),
                        indication = null,
                    ) {
                        if (onControlsToggle != null) {
                            onControlsToggle()
                        } else {
                            interactionTick++
                            internalControlsVisible = !internalControlsVisible
                        }
                    },
            )
            AnimatedVisibility(
                visible = controlsShown && !playing,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Box(
                    Modifier
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable {
                            interactionTick++
                            // MediaPlayer 播完后停在末尾，需 seek 回开头
                            if (duration > 0 && position >= duration - 100) {
                                runCatching { player.seekTo(0) }
                            }
                            runCatching { player.start() }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = controlsShown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { interactionTick++; if (playing) player.pause() else player.start() }) {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "暂停" else "播放",
                            tint = Color.White,
                        )
                    }
                    var scrubbing by remember { mutableStateOf(false) }
                    var scrubPosition by remember { mutableStateOf(0L) }
                    Slider(
                        value = if (duration > 0) {
                            (if (scrubbing) scrubPosition else position).toFloat() / duration
                        } else {
                            0f
                        },
                        onValueChange = { fraction ->
                            interactionTick++
                            scrubbing = true
                            scrubPosition = (fraction * duration).toLong()
                        },
                        onValueChangeFinished = {
                            runCatching { player.seekTo(scrubPosition.toInt()) }
                            position = scrubPosition
                            scrubbing = false
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                    )
                    Text(
                        "${formatClock(if (scrubbing) scrubPosition else position)} / ${formatClock(duration)}",
                        color = Color.White,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        }

        LaunchedEffect(prepared) {
            while (prepared) {
                if (runCatching { player.isPlaying }.getOrDefault(false)) {
                    position = runCatching { player.currentPosition.toLong() }.getOrDefault(position)
                }
                delay(500)
            }
        }
    }
}