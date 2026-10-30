package com.sjtuhive.app.ui

import android.media.MediaPlayer
import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import com.sjtuhive.app.data.ClassroomApi
import io.legado.app.ui.widget.components.GlassDefaults
import kotlin.math.roundToInt
import com.sjtuhive.app.data.CampusSession
import com.sjtuhive.app.data.ClassroomStream
import com.sjtuhive.app.data.ClassroomVideo
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.TranscriptSegment
import com.sjtuhive.app.data.VideoCourse
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val VIDEO_HOME = "https://v.sjtu.edu.cn/jy-application-resourcemanage-ui/"
private const val VIDEO_LOGIN =
    "https://v.sjtu.edu.cn/jy-application-resourcemanage/oauth2/authorize?json=0" +
        "&returnUri=https%3A%2F%2Fv.sjtu.edu.cn%2Fjy-application-resourcemanage-ui%2F%3Ftype%3Dcas"
private const val VIDEO_ORIGIN = "https://v.sjtu.edu.cn"

private const val VIDEO_JWT_JS = """
(function(){
  try {
    var stores = [sessionStorage, localStorage];
    for (var s = 0; s < stores.length; s++) {
      var store = stores[s];
      for (var i = 0; i < store.length; i++) {
        var key = store.key(i);
        if (!key) continue;
        var value = store.getItem(key);
        if (!value) continue;
        if (key.indexOf('UserState') >= 0) {
          try {
            var state = JSON.parse(value);
            if (state && state.token) return state.token;
          } catch (e) {}
        }
        if (key.indexOf('JWT_TOKEN') >= 0) return value;
      }
    }
  } catch (e) {}
  return '';
})();
"""

private suspend fun readVideoJwt(view: WebView): String? =
    suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(VIDEO_JWT_JS) { raw ->
            if (!continuation.isActive) return@evaluateJavascript
            val token = raw.orEmpty().trim().trim('"')
            continuation.resume(token.takeIf { it.isNotEmpty() && it != "null" })
        }
    }

@Composable
fun ClassroomScreen(context: android.content.Context, onClose: () -> Unit) {
    val api = remember { ClassroomApi(context) }
    var checking by remember { mutableStateOf(true) }
    var connected by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    var courses by remember { mutableStateOf<List<VideoCourse>>(emptyList()) }
    var course by remember { mutableStateOf<VideoCourse?>(null) }
    var videos by remember { mutableStateOf<List<ClassroomVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<ClassroomVideo?>(null) }
    var loginWebView by remember { mutableStateOf<WebView?>(null) }

    var autoRefreshTried by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        checking = true
        connected = api.connected()
        // 会话过期时先静默走一次 SSO：jAccount 还有效就会自动续上，不用重新登录。
        if (!connected && !autoRefreshTried) {
            autoRefreshTried = true
            CampusSession.refresh(context, "v.sjtu.edu.cn")
            connected = api.connected()
        }
        checking = false
        if (!connected) showLogin = true
    }

    LaunchedEffect(showLogin) {
        if (!showLogin) return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 25_000
        while (showLogin && System.currentTimeMillis() < deadline) {
            delay(1200)
            val view = loginWebView ?: continue
            val token = readVideoJwt(view) ?: continue
            api.saveJwt(token)
            HiveSession.refreshFromWebView(context)
            connected = api.connected()
            showLogin = false
            reload++
            break
        }
    }

    LaunchedEffect(connected, course?.id, reload) {
        if (!connected) return@LaunchedEffect
        loading = true
        error = null
        val current = course
        runCatching {
            if (current == null) {
                courses = api.courses()
                videos = emptyList()
            } else {
                videos = api.videos(current.id)
            }
        }.onFailure { error = it.message ?: "课堂视频读取失败" }
        loading = false
    }

    BackHandler {
        when {
            playing != null -> playing = null
            course != null -> course = null
            else -> onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (course != null) course = null else onClose() }) {
                    Text("← 返回")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        course?.name ?: "课堂视频",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
                    Text(
                        when {
                            checking -> "正在检查登录…"
                            !connected -> "需要登录"
                            loading -> "正在读取…"
                            error != null -> error ?: ""
                            course == null -> "${courses.size} 门课程"
                            else -> "${videos.size} 个录像"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
            }

            when {
                checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                !connected -> Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "课堂视频使用 jAccount 单点登录；首次使用请在登录窗口里进入一次课堂视频首页完成授权。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { showLogin = true }) { Text("登录交大视频") }
                }
                loading && courses.isEmpty() && videos.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (error != null) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "读取失败：$error",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { reload++ }) { Text("重试") }
                            }
                        }
                    }
                    if (course == null) {
                        items(courses, key = { it.id }) { item ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { course = item }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(item.name, style = MaterialTheme.typography.titleSmall)
                                if (item.code.isNotBlank()) {
                                    Text(
                                        item.code,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (courses.isEmpty() && error == null) {
                            item { Text("本学期没有录像课程。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    } else {
                        items(videos, key = { it.id }) { video ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        error = null
                                        playing = video
                                    }
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Text(
                                    listOf(video.start, video.end)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" – ")
                                        .ifBlank { "录像 ${video.id}" },
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    listOf(video.room, if (video.period.isNotBlank()) "第 ${video.period} 节" else "")
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (videos.isEmpty() && error == null) {
                            item { Text("这门课程没有录像。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }

        if (showLogin) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                LoginWebView(startUrl = VIDEO_LOGIN, onWebView = { loginWebView = it })
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            val view = loginWebView
                            if (view == null) {
                                Toast.makeText(context, "登录窗口未就绪", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            view.evaluateJavascript(VIDEO_JWT_JS) { raw ->
                                val token = raw.orEmpty().trim().trim('"')
                                if (token.isNotEmpty() && token != "null") {
                                    api.saveJwt(token)
                                    Toast.makeText(context, "已获取课堂视频凭证", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "未获取到凭证，请先在页面内完成登录",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                                HiveSession.refreshFromWebView(context)
                                showLogin = false
                                reload++
                            }
                        },
                    ) { Text("完成登录") }
                    Button(onClick = { showLogin = false }) { Text("取消") }
                }
            }
        }

        playing?.let { video ->
            VideoPlayerScreen(
                context = context,
                api = api,
                video = video,
                onBack = { playing = null },
            )
        }
    }
}

@Composable
private fun VideoPlayerScreen(
    context: android.content.Context,
    api: ClassroomApi,
    video: ClassroomVideo,
    onBack: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var streams by remember { mutableStateOf<List<ClassroomStream>>(emptyList()) }
    var streamIndex by remember { mutableIntStateOf(0) }
    var dual by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var primaryPlaying by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var showTranscript by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf<List<TranscriptSegment>>(emptyList()) }
    var transcriptLoading by remember { mutableStateOf(false) }
    var transcriptError by remember { mutableStateOf<String?>(null) }

    val activity = context.findActivity()
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            // 退出播放器后固定回竖屏：自动旋转关闭时 UNSPECIFIED 会一直停在横屏。
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    LaunchedEffect(video.id) {
        loading = true
        error = null
        runCatching { api.streams(video.id) }
            .onSuccess { streams = it }
            .onFailure { error = it.message ?: "播放地址读取失败" }
        loading = false
    }

    LaunchedEffect(showTranscript, video.id) {
        if (!showTranscript) return@LaunchedEffect
        transcriptLoading = true
        transcriptError = null
        runCatching { api.transcript(video.id) }
            .onSuccess { transcript = it }
            .onFailure { transcriptError = it.message ?: "文稿读取失败" }
        transcriptLoading = false
    }

    val current = streams.getOrNull(streamIndex)
    val glassBackdrop = rememberLayerBackdrop {
        drawRect(Color.Black)
        drawRect(
            Brush.verticalGradient(
                listOf(Color(0x33000000), Color(0x00000000), Color(0x99000000)),
            ),
        )
    }
    val title = listOf(video.start, video.end)
        .filter { it.isNotBlank() }
        .joinToString(" – ")

    fun touched() {
        controlsVisible = true
        interactionTick++
    }

    val playingState by rememberUpdatedState(primaryPlaying)
    LaunchedEffect(controlsVisible, interactionTick) {
        if (!controlsVisible) return@LaunchedEffect
        delay(3500)
        if (playingState) controlsVisible = false
    }

    if (showTranscript) {
        TranscriptPanel(
            segments = transcript,
            loading = transcriptLoading,
            error = transcriptError,
            onBack = { showTranscript = false },
        )
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Box(Modifier.fillMaxSize().layerBackdrop(glassBackdrop))
        if (current != null) {
            VideoStage(
                context = context,
                videoId = video.id,
                primaryUrl = current.url,
                secondaryUrl = if (dual) {
                    streams.firstOrNull { it.url != current.url }?.url
                } else {
                    null
                },
                dual = dual,
                backdrop = glassBackdrop,
                controlsVisible = controlsVisible,
                onToggleControls = {
                    controlsVisible = !controlsVisible
                    interactionTick++
                },
                onInteraction = { touched() },
                onPlayingChanged = { playingNow ->
                    primaryPlaying = playingNow
                    if (!playingNow) controlsVisible = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Text(
                        error ?: "暂无可用画面",
                        color = Color(0xFFE38B75),
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xAA000000), Color.Transparent),
                            ),
                        ),
                )
                GlassBar(
                    backdrop = glassBackdrop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    GlassIconButton(
                        backdrop = glassBackdrop,
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        label = "返回",
                        onClick = {
                            touched()
                            onBack()
                        },
                    )
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    if (streams.size > 1) {
                        GlassPillButton(
                            backdrop = glassBackdrop,
                            label = if (dual) "单画面" else "双画面",
                            onClick = {
                                touched()
                                dual = !dual
                            },
                        )
                    }
                    if (!dual && streams.size > 1) {
                        GlassPillButton(
                            backdrop = glassBackdrop,
                            label = "画面 ${streamIndex + 1}/${streams.size}",
                            onClick = {
                                touched()
                                streamIndex = (streamIndex + 1) % streams.size
                            },
                        )
                    }
                    GlassPillButton(
                        backdrop = glassBackdrop,
                        label = "文稿",
                        onClick = {
                            touched()
                            showTranscript = true
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptPanel(
    segments: List<TranscriptSegment>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(WindowInsets.navigationBars.asPaddingValues()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回", color = Color.White) }
            Text(
                "语音文稿",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            error != null -> Text(
                error,
                color = Color(0xFFE38B75),
                modifier = Modifier.padding(24.dp),
            )
            segments.isEmpty() -> Text(
                "这节课暂无语音识别文稿。",
                color = Color(0xFFB0B6C0),
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(segments, key = { "${it.startMs}-${it.text.hashCode()}" }) { segment ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            formatClock(segment.startMs),
                            color = Color(0xFF9DC2FF),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Text(
                            segment.text,
                            color = Color(0xFFE6E8EC),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private class PlayerSlot {
    var player: MediaPlayer? = null
    var aspect by mutableStateOf(16f / 9f)
    var ready by mutableStateOf(false)
}

@Composable
private fun VideoStage(
    context: android.content.Context,
    videoId: Long,
    primaryUrl: String,
    secondaryUrl: String?,
    dual: Boolean,
    backdrop: Backdrop,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onInteraction: () -> Unit,
    onPlayingChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progressStore = remember { ClassroomApi(context) }
    val initialPosition = remember(videoId) { progressStore.progress(videoId) }
    val primarySlot = remember { PlayerSlot() }
    val secondarySlot = remember { PlayerSlot() }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(initialPosition) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(dual) {
        if (!dual) {
            runCatching { secondarySlot.player?.release() }
            secondarySlot.player = null
            secondarySlot.ready = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                primarySlot.player?.let {
                    progressStore.saveProgress(videoId, it.currentPosition.toLong())
                }
            }
        }
    }

    LaunchedEffect(primarySlot) {
        var tick = 0
        while (true) {
            val primary = primarySlot.player
            if (primary != null && primarySlot.ready) {
                runCatching {
                    position = primary.currentPosition.toLong()
                    duration = primary.duration.toLong().coerceAtLeast(0L)
                    playing = primary.isPlaying
                }
                onPlayingChanged(playing)
                if (dual) {
                    val second = secondarySlot.player
                    if (second != null && secondarySlot.ready) {
                        runCatching {
                            if (primary.isPlaying != second.isPlaying) {
                                if (primary.isPlaying) second.start() else second.pause()
                            }
                            if (primary.isPlaying &&
                                kotlin.math.abs(primary.currentPosition - second.currentPosition) > 1500
                            ) {
                                second.seekTo(primary.currentPosition)
                            }
                        }
                    }
                }
                if (++tick % 10 == 0) {
                    progressStore.saveProgress(videoId, position)
                }
            }
            delay(500)
        }
    }

    fun seekAll(target: Long) {
        onInteraction()
        runCatching {
            primarySlot.player?.seekTo(target.toInt())
            if (dual) secondarySlot.player?.seekTo(target.toInt())
        }
        position = target
    }

    fun setPlayback(play: Boolean) {
        onInteraction()
        runCatching {
            primarySlot.player?.let { if (play) it.start() else it.pause() }
            if (dual) secondarySlot.player?.let { if (play) it.start() else it.pause() }
        }
        playing = play
        onPlayingChanged(play)
    }

    val onPrimaryCompleted = {
        playing = false
        runCatching { secondarySlot.player?.pause() }
        progressStore.clearProgress(videoId)
    }

    Box(modifier.background(Color.Black)) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                VideoPane(
                    context = context,
                    url = primaryUrl,
                    muted = false,
                    startPosition = initialPosition,
                    slot = primarySlot,
                    onError = { error = it },
                    onCompleted = onPrimaryCompleted,
                )
            }
            if (dual && secondaryUrl != null) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    VideoPane(
                        context = context,
                        url = secondaryUrl,
                        muted = true,
                        startPosition = position,
                        slot = secondarySlot,
                        onError = { error = it },
                    )
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onToggleControls() },
        )
        if (error != null) {
            Text(
                error ?: "",
                color = Color(0xFFE38B75),
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (!primarySlot.ready) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xCC000000)),
                            ),
                        ),
                )
                GlassBar(
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    GlassIconButton(
                        backdrop = backdrop,
                        icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        label = if (playing) "暂停" else "播放",
                        onClick = { setPlayback(!playing) },
                    )
                    GlassSlider(
                        fraction = if (duration > 0) {
                            (position.toFloat() / duration).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        onScrub = { fraction ->
                            position = (fraction * duration).toLong()
                        },
                        onCommit = { fraction ->
                            seekAll((fraction * duration).toLong())
                        },
                        backdrop = backdrop,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 10.dp),
                    )
                    Text(
                        "${formatClock(position)} / ${formatClock(duration)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPane(
    context: android.content.Context,
    url: String,
    muted: Boolean,
    startPosition: Long,
    slot: PlayerSlot,
    onError: (String) -> Unit,
    onCompleted: (() -> Unit)? = null,
) {
    DisposableEffect(slot, url) {
        onDispose {
            runCatching { slot.player?.release() }
            slot.player = null
            slot.ready = false
        }
    }
    AndroidView(
        modifier = Modifier.aspectRatio(slot.aspect, matchHeightConstraintsFirst = true),
        factory = { viewContext ->
            TextureView(viewContext).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        val media = slot.player ?: MediaPlayer().also { player ->
                            slot.player = player
                            runCatching {
                                player.setDataSource(
                                    viewContext,
                                    Uri.parse(url),
                                    mapOf(
                                        "Referer" to VIDEO_HOME,
                                        "Origin" to VIDEO_ORIGIN,
                                        "Accept" to "*/*",
                                    ),
                                )
                                player.setVolume(
                                    if (muted) 0f else 1f,
                                    if (muted) 0f else 1f,
                                )
                                player.setOnPreparedListener { prepared ->
                                    slot.aspect = prepared.videoWidth.toFloat() /
                                        prepared.videoHeight.coerceAtLeast(1).toFloat()
                                    val resume = startPosition
                                        .takeIf { it > 1000 && it < prepared.duration - 3000 }
                                        ?: 0L
                                    if (resume > 0) prepared.seekTo(resume.toInt())
                                    prepared.start()
                                    slot.ready = true
                                }
                                player.setOnErrorListener { _, what, extra ->
                                    onError("播放失败（$what/$extra）")
                                    true
                                }
                                if (onCompleted != null) {
                                    player.setOnCompletionListener { onCompleted() }
                                }
                                player.prepareAsync()
                            }.onFailure { onError(it.message ?: "无法播放") }
                        }
                        media.setSurface(Surface(surface))
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        slot.player?.setSurface(null)
                        return true
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                }
            }
        },
    )
}

@Composable
private fun GlassSlider(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableIntStateOf(0) }
    val thumbSize = 18.dp
    val thumbPx = with(LocalDensity.current) { thumbSize.toPx() }
    val tint = GlassDefaults.glassColor(Color(0xFF0F1115), 0.3f)
    Box(
        modifier
            .height(34.dp)
            .clip(ContinuousCapsule)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule },
                effects = {
                    vibrancy()
                    blur(20.dp.toPx())
                    lens(14.dp.toPx(), 14.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.7f) },
                shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = 0.25f)) },
                onDrawSurface = { drawRect(tint) },
            )
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val trackWidth = width
                    if (trackWidth <= 0) return@awaitEachGesture
                    var last = (down.position.x / trackWidth).coerceIn(0f, 1f)
                    onScrub(last)
                    drag(down.id) { change ->
                        last = (change.position.x / width).coerceIn(0f, 1f)
                        onScrub(last)
                        change.consume()
                    }
                    onCommit(last)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(ContinuousCapsule)
                .background(Color.White.copy(alpha = 0.16f)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(ContinuousCapsule)
                .background(Color.White.copy(alpha = 0.82f)),
        )
        Box(
            Modifier
                .offset {
                    IntOffset(
                        ((width - thumbPx) * fraction.coerceIn(0f, 1f)).roundToInt(),
                        0,
                    )
                }
                .size(thumbSize)
                .clip(ContinuousCapsule)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        vibrancy()
                        blur(12.dp.toPx())
                        lens(8.dp.toPx(), 8.dp.toPx())
                    },
                    highlight = { Highlight.Default },
                    shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = 0.3f)) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.4f)) },
                ),
        )
    }
}

@Composable
private fun GlassBar(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val tint = GlassDefaults.glassColor(Color(0xFF0F1115), 0.55f)
    Row(
        modifier
            .clip(ContinuousCapsule)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule },
                effects = {
                    vibrancy()
                    blur(18.dp.toPx())
                    lens(24.dp.toPx(), 24.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.9f) },
                shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = 0.35f)) },
                onDrawSurface = { drawRect(tint) },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun GlassIconButton(
    backdrop: Backdrop,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val tint = GlassDefaults.glassColor(Color(0xFF0F1115), 0.45f)
    Box(
        Modifier
            .size(42.dp)
            .clip(ContinuousCapsule)
            .clickable(onClick = onClick)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule },
                effects = {
                    vibrancy()
                    blur(16.dp.toPx())
                    lens(18.dp.toPx(), 18.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.8f) },
                shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = 0.3f)) },
                onDrawSurface = { drawRect(tint) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun GlassPillButton(
    backdrop: Backdrop,
    label: String,
    onClick: () -> Unit,
) {
    val tint = GlassDefaults.glassColor(Color(0xFF0F1115), 0.45f)
    Box(
        Modifier
            .clip(ContinuousCapsule)
            .clickable(onClick = onClick)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { ContinuousCapsule },
                effects = {
                    vibrancy()
                    blur(16.dp.toPx())
                    lens(18.dp.toPx(), 18.dp.toPx())
                },
                highlight = { Highlight.Default.copy(alpha = 0.8f) },
                shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = 0.3f)) },
                onDrawSurface = { drawRect(tint) },
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

private fun formatClock(milliseconds: Long): String {
    val total = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun android.content.Context.findActivity(): Activity? {
    var current: android.content.Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
