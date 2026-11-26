package com.sjtuhive.app.ui

import android.media.MediaPlayer
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextAlign
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
import com.sjtuhive.app.data.ClassroomApi
import com.sjtuhive.app.data.ClassroomDownloads
import com.sjtuhive.app.data.VideoDownloadState
import com.sjtuhive.app.data.VideoDownloadTask
import kotlin.math.roundToInt
import com.sjtuhive.app.data.CampusSession
import com.sjtuhive.app.data.ClassroomStream
import com.sjtuhive.app.data.ClassroomVideo
import com.sjtuhive.app.ui.video.HiveVideoHandle
import com.sjtuhive.app.ui.video.HiveVideoPlayer
import com.sjtuhive.app.ui.video.HiveVideoSource
import io.legado.app.ui.theme.LocalIsDark
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.TranscriptSegment
import com.sjtuhive.app.data.VideoCourse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
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
    var courseQuery by remember { mutableStateOf("") }
    var course by remember { mutableStateOf<VideoCourse?>(null) }
    var videos by remember { mutableStateOf<List<ClassroomVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf<ClassroomVideo?>(null) }
    var showDownloads by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
            showDownloads -> showDownloads = false
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
                if (connected) {
                    TextButton(onClick = { showDownloads = true }) { Text("下载") }
                    TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
                }
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
                        item {
                            OutlinedTextField(
                                value = courseQuery,
                                onValueChange = { courseQuery = it },
                                placeholder = { Text("搜索课程名或代码") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        val lower = courseQuery.trim().lowercase()
                        val visible = if (lower.isEmpty()) {
                            courses
                        } else {
                            courses.filter {
                                it.name.lowercase().contains(lower) ||
                                    it.code.lowercase().contains(lower)
                            }
                        }
                        items(visible, key = { it.id }) { item ->
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
                        if (visible.isEmpty() && error == null) {
                            item {
                                Text(
                                    if (courseQuery.isBlank()) {
                                        "本学期没有录像课程。"
                                    } else {
                                        "没有匹配的课程。"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        items(videos, key = { it.id }) { video ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable {
                                            error = null
                                            playing = video
                                        },
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
                                        listOf(
                                            video.room,
                                            if (video.period.isNotBlank()) "第 ${video.period} 节" else "",
                                        )
                                            .filter { it.isNotBlank() }
                                            .joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        runCatching { api.streams(video.id) }
                                            .onSuccess { list ->
                                                val url = list.firstOrNull()?.url.orEmpty()
                                                if (url.isBlank()) {
                                                    Toast.makeText(
                                                        context,
                                                        "没有可下载的播放地址",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                } else {
                                                    val label = listOf(video.start, video.end)
                                                        .filter { it.isNotBlank() }
                                                        .joinToString("–")
                                                        .ifBlank { "课堂录像" }
                                                    ClassroomDownloads.enqueue(
                                                        context,
                                                        video.id,
                                                        label,
                                                        url,
                                                    )
                                                    Toast.makeText(
                                                        context,
                                                        "已加入下载队列",
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                }
                                            }
                                            .onFailure {
                                                Toast.makeText(
                                                    context,
                                                    it.message ?: "下载地址读取失败",
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                    }
                                }) { Text("下载") }
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

        if (showDownloads) {
            ClassroomDownloadsScreen(
                context = context,
                onBack = { showDownloads = false },
            )
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
private fun ClassroomDownloadsScreen(context: android.content.Context, onBack: () -> Unit) {
    val tasks = ClassroomDownloads.tasks
    BackHandler { onBack() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                "课堂下载",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = tasks.any {
                    it.state == VideoDownloadState.DONE ||
                        it.state == VideoDownloadState.FAILED ||
                        it.state == VideoDownloadState.CANCELED
                },
                onClick = { ClassroomDownloads.removeFinished() },
            ) { Text("清除已完成") }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text("下载目录") }
        }
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有下载任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { task.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val status = when (task.state) {
                        VideoDownloadState.QUEUED -> "等待中"
                        VideoDownloadState.DOWNLOADING ->
                            "${task.progress}% · ${formatBytes(task.downloaded)} / ${formatBytes(task.total)}"
                        VideoDownloadState.PAUSED -> "已暂停"
                        VideoDownloadState.DONE -> "已完成，保存在系统下载目录"
                        VideoDownloadState.FAILED -> "失败：${task.error.orEmpty()}"
                        VideoDownloadState.CANCELED -> "已取消"
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (task.state == VideoDownloadState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (task.state == VideoDownloadState.DOWNLOADING ||
                            task.state == VideoDownloadState.QUEUED
                        ) {
                            TextButton(onClick = { ClassroomDownloads.pause(task.id) }) {
                                Text("暂停")
                            }
                        }
                        if (task.state == VideoDownloadState.PAUSED ||
                            task.state == VideoDownloadState.FAILED
                        ) {
                            TextButton(onClick = { ClassroomDownloads.resume(context, task.id) }) {
                                Text("继续")
                            }
                        }
                        if (task.state != VideoDownloadState.DONE &&
                            task.state != VideoDownloadState.CANCELED
                        ) {
                            TextButton(onClick = { ClassroomDownloads.cancel(task.id) }) {
                                Text("取消")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(value: Long): String = when {
    value <= 0 -> "0 B"
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> "%.0f KB".format(value / 1024.0)
    else -> "%.1f MB".format(value / 1024.0 / 1024.0)
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
    var pendingSeek by remember(video.id) { mutableStateOf<Long?>(null) }

    val activity = context.findActivity()
    // 退出播放器时系统栏图标要回到当前主题的深浅（全屏期间系统可能改写）。
    val isDarkTheme = LocalIsDark.current
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller?.isAppearanceLightStatusBars = !isDarkTheme
            controller?.isAppearanceLightNavigationBars = !isDarkTheme
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
        // 只重置自动隐藏计时，不强制显示：点击画面的显隐由 onToggleControls 统一翻转，
        // 这里再置 true 会和翻转互相抵消（表现就是隐藏后点不出来）。
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
            videoId = video.id,
            title = title,
            onSeek = { positionMs ->
                pendingSeek = positionMs
                showTranscript = false
            },
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
                startPositionMs = pendingSeek,
                onSeekConsumed = { pendingSeek = null },
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
                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = {
                        touched()
                        onBack()
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                    )
                    if (streams.size > 1) {
                        TextButton(onClick = {
                            touched()
                            dual = !dual
                        }) { Text(if (dual) "单画面" else "双画面", color = Color.White) }
                    }
                    if (!dual && streams.size > 1) {
                        TextButton(onClick = {
                            touched()
                            streamIndex = (streamIndex + 1) % streams.size
                        }) { Text("画面 ${streamIndex + 1}/${streams.size}", color = Color.White) }
                    }
                    TextButton(onClick = {
                        touched()
                        showTranscript = true
                    }) { Text("文稿", color = Color.White) }
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
    videoId: Long,
    title: String,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(WindowInsets.navigationBars.asPaddingValues()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                "语音文稿",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = !exporting && segments.isNotEmpty(),
                onClick = {
                    exporting = true
                    scope.launch {
                        runCatching {
                            val text = segments.joinToString("\n") { segment ->
                                "${formatClock(segment.startMs)} ${segment.text}"
                            }
                            val file = File(context.cacheDir, "classroom-transcript-$videoId.txt")
                            file.writeText(text)
                            saveToDownloads(context, file, "课堂文稿_$videoId.txt")
                        }.onSuccess {
                            Toast.makeText(context, "已导出到下载目录", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                it.message ?: "导出失败",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                        exporting = false
                    }
                },
            ) { Text(if (exporting) "导出中" else "导出") }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Text(
                error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            segments.isEmpty() -> Text(
                "这节课暂无语音识别文稿。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSeek(segment.startMs) }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            formatClock(segment.startMs),
                            color = MaterialTheme.colorScheme.primary,
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
    var player: HiveVideoHandle? = null
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
    startPositionMs: Long? = null,
    onSeekConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val progressStore = remember { ClassroomApi(context) }
    val initialPosition = remember(videoId) {
        startPositionMs ?: progressStore.progress(videoId)
    }
    LaunchedEffect(startPositionMs) {
        if (startPositionMs != null) onSeekConsumed()
    }
    val primarySlot = remember { PlayerSlot() }
    val secondarySlot = remember { PlayerSlot() }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(initialPosition) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryTick by remember { mutableIntStateOf(0) }

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
                    progressStore.saveProgress(videoId, it.positionMs)
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
                    position = primary.positionMs
                    duration = primary.durationMs
                    playing = primary.isPlaying
                }
                onPlayingChanged(playing)
                if (dual) {
                    val second = secondarySlot.player
                    if (second != null && secondarySlot.ready) {
                        runCatching {
                            if (primary.isPlaying != second.isPlaying) {
                                if (primary.isPlaying) second.play() else second.pause()
                            }
                            if (primary.isPlaying &&
                                kotlin.math.abs(primary.positionMs - second.positionMs) > 1500
                            ) {
                                second.seekTo(primary.positionMs)
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
                    restartKey = retryTick,
                    controlsVisibleOverride = controlsVisible,
                    onControlsToggle = onToggleControls,
                    onUserInteraction = onInteraction,
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
                        restartKey = retryTick,
                    )
                }
            }
        }
        if (error != null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    error ?: "",
                    color = Color(0xFFE38B75),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    error = null
                    retryTick++
                }) { Text("重试") }
            }
        } else if (!primarySlot.ready) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
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
    restartKey: Any? = null,
    controlsVisibleOverride: Boolean? = null,
    onControlsToggle: (() -> Unit)? = null,
    onUserInteraction: (() -> Unit)? = null,
) {
    // 旧的裸 MediaPlayer/TextureView 实现已统一到 HiveVideoPlayer：
    // 文件预览与课堂视频共用同一套播放核心与控制体验。
    DisposableEffect(slot, url) {
        slot.ready = false
        onDispose {
            slot.ready = false
            slot.player = null
        }
    }
    HiveVideoPlayer(
        source = HiveVideoSource.Remote(
            url = url,
            headers = mapOf("Referer" to VIDEO_HOME, "Origin" to VIDEO_ORIGIN),
        ),
        modifier = Modifier.aspectRatio(slot.aspect, matchHeightConstraintsFirst = true),
        autoStart = true,
        startPositionMs = startPosition,
        muted = muted,
        // 统一控制条：主画面带控制条；副画面（静音小窗）不带
        showControls = !muted,
        controlsVisibleOverride = if (muted) null else controlsVisibleOverride,
        onControlsToggle = if (muted) null else onControlsToggle,
        onUserInteraction = onUserInteraction,
        restartKey = restartKey,
        onReady = { width, height, _ ->
            if (width > 0 && height > 0) slot.aspect = width.toFloat() / height.toFloat()
            slot.ready = true
        },
        onError = onError,
        onCompleted = onCompleted,
        // 父级的位置轮询与 seekAll 通过 PlayerSlot 访问播放器，这里桥接回去
        onPlayerCreated = { media -> slot.player = media },
    )
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
