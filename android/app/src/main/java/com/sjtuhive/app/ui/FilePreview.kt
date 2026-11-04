package com.sjtuhive.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/** 内置预览：云盘文件与邮件附件统一走这个入口。 */
data class PreviewRequest(
    val title: String,
    val fileName: String,
    val load: suspend () -> File,
)

object PreviewHost {
    var request by mutableStateOf<PreviewRequest?>(null)
        private set

    fun open(request: PreviewRequest) {
        this.request = request
    }

    fun close() {
        request = null
    }
}

private enum class PreviewKind { PDF, IMAGE, TEXT, AUDIO, VIDEO, DOCX, XLSX, PPTX, UNKNOWN }

private fun kindOf(fileName: String): PreviewKind = when (fileName.substringAfterLast('.', "").lowercase()) {
    "pdf" -> PreviewKind.PDF
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif" -> PreviewKind.IMAGE
    "txt", "md", "markdown", "csv", "log", "json", "xml", "yaml", "yml", "ini", "kt", "java", "go", "py", "js", "ts", "html", "css" -> PreviewKind.TEXT
    "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus" -> PreviewKind.AUDIO
    "mp4", "mkv", "webm", "mov", "3gp", "ts" -> PreviewKind.VIDEO
    "docx" -> PreviewKind.DOCX
    "xlsx" -> PreviewKind.XLSX
    "pptx" -> PreviewKind.PPTX
    else -> PreviewKind.UNKNOWN
}

@Composable
fun FilePreviewOverlay() {
    val request = PreviewHost.request ?: return
    FilePreviewScreen(request)
}

@Composable
private fun FilePreviewScreen(request: PreviewRequest) {
    val context = LocalContext.current
    var file by remember(request) { mutableStateOf<File?>(null) }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var loading by remember(request) { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    val kind = remember(request.fileName) { kindOf(request.fileName) }

    BackHandler {
        if (fullscreen) fullscreen = false else PreviewHost.close()
    }

    LaunchedEffect(request) {
        loading = true
        error = null
        runCatching { request.load() }
            .onSuccess { file = it }
            .onFailure { error = it.message ?: "文件读取失败" }
        loading = false
    }

    val activity = context.findActivity()
    DisposableEffect(fullscreen) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (fullscreen) Color.Black else MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            request.title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                        )
                        Text(
                            request.fileName.substringAfterLast('.', "").uppercase().ifBlank { "文件" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { fullscreen = true }) { Text("全屏") }
                    TextButton(onClick = { PreviewHost.close() }) { Text("关闭") }
                }
            }

            Box(Modifier.weight(1f)) {
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            error ?: "",
                            modifier = Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    file != null -> when (kind) {
                        PreviewKind.PDF -> PdfPreview(file!!)
                        PreviewKind.IMAGE -> ZoomableImage(file!!)
                        PreviewKind.TEXT -> TextPreview(file!!)
                        PreviewKind.AUDIO -> AudioPreview(file!!, request.title)
                        PreviewKind.VIDEO -> VideoPreview(file!!)
                        PreviewKind.DOCX, PreviewKind.XLSX, PreviewKind.PPTX -> OfficePreview(file!!, request.fileName)
                        PreviewKind.UNKNOWN -> Column(
                            Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "暂不支持在应用内预览该格式。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "文件已保存到缓存，可用其他应用打开。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (fullscreen) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { fullscreen = false }) { Text("退出全屏", color = Color.White) }
                TextButton(onClick = { PreviewHost.close() }) { Text("关闭", color = Color.White) }
            }
        }
    }
}

@Composable
private fun ZoomableImage(file: File) {
    val bitmap = remember(file) { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
    if (bitmap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("图片无法解码") }
        return
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else 2.5f
                        if (scale <= 1f) offset = Offset.Zero
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}

@Composable
private fun PdfPreview(file: File) {
    var pages by remember(file) { mutableStateOf<List<Bitmap>>(emptyList()) }
    var truncated by remember(file) { mutableStateOf(false) }
    var rendering by remember(file) { mutableStateOf(true) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    val density = LocalDensity.current

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF111317))) {
        val targetWidth = with(density) { minOf(maxWidth.toPx(), 1440f) }.toInt()
        LaunchedEffect(file, targetWidth) {
            if (targetWidth <= 0) return@LaunchedEffect
            rendering = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            val count = minOf(renderer.pageCount, 60)
                            val bitmaps = ArrayList<Bitmap>(count)
                            for (index in 0 until count) {
                                renderer.openPage(index).use { page ->
                                    val ratio = page.height.toFloat() / page.width.toFloat()
                                    val width = targetWidth
                                    val height = (width * ratio).toInt().coerceAtLeast(1)
                                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(AndroidColor.WHITE)
                                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bitmaps += bitmap
                                }
                            }
                            bitmaps to (renderer.pageCount > count)
                        }
                    }
                }
            }
            result.onSuccess {
                pages = it.first
                truncated = it.second
            }.onFailure { error = it.message ?: "PDF 渲染失败" }
            rendering = false
        }

        when {
            rendering -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text("正在渲染 PDF…", modifier = Modifier.padding(top = 12.dp))
                }
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
            else -> LazyColumn {
                itemsIndexed(pages) { index, bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "第 ${index + 1} 页",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (truncated) {
                    item {
                        Text(
                            "仅显示前 60 页。",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextPreview(file: File) {
    val text = remember(file) { rememberText(file) }
    SelectionContainer {
        Text(
            text,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun rememberText(file: File): String {
    if (file.length() > 4L * 1024 * 1024) return "文件过大，暂不预览文本。"
    val bytes = runCatching { file.readBytes() }.getOrElse { return "无法读取文件：${it.message}" }
    return decodeText(bytes)
}

private fun decodeText(bytes: ByteArray): String {
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    if (utf8 != null) return utf8
    return runCatching { String(bytes, charset("GBK")) }.getOrDefault(String(bytes, Charsets.ISO_8859_1))
}

@Composable
private fun AudioPreview(file: File, title: String) {
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    val player = remember(file) { MediaPlayer() }

    DisposableEffect(file) {
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepare()
            duration = player.duration
        }
        onDispose {
            runCatching { player.release() }
        }
    }
    LaunchedEffect(player) {
        while (true) {
            if (runCatching { player.isPlaying }.getOrDefault(false)) {
                position = runCatching { player.currentPosition }.getOrDefault(position)
            }
            delay(400)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(formatClock(position) + " / " + formatClock(duration))
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { ratio ->
                val target = (ratio * duration).toInt()
                position = target
                runCatching { player.seekTo(target) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = {
                runCatching {
                    if (player.isPlaying) {
                        player.pause()
                        playing = false
                    } else {
                        player.start()
                        playing = true
                    }
                }
            }) { Text(if (playing) "暂停" else "播放") }
            TextButton(onClick = {
                runCatching {
                    player.seekTo(0)
                    position = 0
                }
            }) { Text("回到开头") }
        }
    }
}

private fun formatClock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun VideoPreview(file: File) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                val controller = android.widget.MediaController(context)
                controller.setAnchorView(this)
                setMediaController(controller)
                setVideoPath(file.absolutePath)
                setOnPreparedListener { it.isLooping = false }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun OfficePreview(file: File, fileName: String) {
    var text by remember(file) { mutableStateOf<String?>(null) }
    var error by remember(file) { mutableStateOf<String?>(null) }
    var loading by remember(file) { mutableStateOf(true) }

    LaunchedEffect(file, fileName) {
        loading = true
        withContext(Dispatchers.IO) {
            runCatching { OfficeText.extract(file, fileName) }
                .onSuccess { text = it.ifBlank { "文档没有可提取的文字内容。" } }
                .onFailure { error = it.message ?: "文档解析失败" }
        }
        loading = false
    }

    when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("正在解析文档…", modifier = Modifier.padding(top = 12.dp))
            }
        }
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error ?: "", color = MaterialTheme.colorScheme.error)
        }
        else -> SelectionContainer {
            Text(
                text.orEmpty(),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** docx/xlsx/pptx 本质是 zip + XML，这里做轻量文字提取，避免引入重型渲染库。 */
private object OfficeText {

    fun extract(file: File, fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ZipFile(file).use { zip ->
            when (ext) {
                "docx" -> docx(zip)
                "xlsx" -> xlsx(zip)
                "pptx" -> pptx(zip)
                else -> ""
            }
        }
    }

    private fun zipText(zip: ZipFile, name: String): String? = runCatching {
        zip.getEntry(name)?.let { entry ->
            zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }.getOrNull()

    private fun docx(zip: ZipFile): String {
        val xml = zipText(zip, "word/document.xml") ?: return ""
        return Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { paragraph ->
                Regex("<w:t[^>]*>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(paragraph.value)
                    .joinToString("") { unescape(it.groupValues[1]) }
            }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun xlsx(zip: ZipFile): String {
        val shared = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL)
            .findAll(zipText(zip, "xl/sharedStrings.xml").orEmpty())
            .map { entry ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(entry.groupValues[1])
                    .joinToString("") { unescape(it.groupValues[1]) }
            }
            .toList()
        val sheetNames = zip.entries().toList()
            .map { it.name }
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sorted()
        if (sheetNames.isEmpty()) return ""
        val builder = StringBuilder()
        sheetNames.forEachIndexed { sheetIndex, name ->
            if (sheetIndex > 0) builder.append("\n\n")
            if (sheetNames.size > 1) builder.append("工作表 ${sheetIndex + 1}\n")
            val xml = zipText(zip, name).orEmpty()
            Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .forEach { row ->
                    val cells = Regex("<c([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(row.groupValues[1])
                        .map { cell ->
                            val attrs = cell.groupValues[1]
                            val inner = cell.groupValues[2]
                            val inline = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                                .findAll(inner)
                                .joinToString("") { unescape(it.groupValues[1]) }
                            val value = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                                .find(inner)?.groupValues?.get(1)
                            when {
                                attrs.contains("t=\"s\"") -> shared.getOrNull(value?.toIntOrNull() ?: -1).orEmpty()
                                inline.isNotEmpty() -> inline
                                else -> unescape(value.orEmpty())
                            }
                        }
                        .filter { it.isNotBlank() }
                        .joinToString("  ")
                    if (cells.isNotBlank()) builder.append(cells).append('\n')
                }
        }
        return builder.toString().trim()
    }

    private fun pptx(zip: ZipFile): String {
        val slides = zip.entries().toList()
            .map { it.name }
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
            .sortedBy { it.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 0 }
        return slides.mapIndexed { index, name ->
            val text = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(zipText(zip, name).orEmpty())
                .joinToString("\n") { unescape(it.groupValues[1]) }
            "第 ${index + 1} 页\n$text"
        }.joinToString("\n\n").trim()
    }

    private fun unescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
