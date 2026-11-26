package com.sjtuhive.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.util.UUID

enum class VideoDownloadState { QUEUED, DOWNLOADING, PAUSED, DONE, FAILED, CANCELED }

data class VideoDownloadTask(
    val id: String,
    val videoId: Long,
    val title: String,
    val url: String,
    val fileName: String,
    val state: VideoDownloadState = VideoDownloadState.QUEUED,
    val downloaded: Long = 0,
    val total: Long = 0,
    val error: String? = null,
) {
    val progress: Int
        get() = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
}

/**
 * 课堂录像下载队列：最多 2 个并发，临时文件写 cache，完成后落到系统「下载」目录。
 * 队列仅保存在进程内；应用被系统回收后未完成的下载需要重新发起。
 */
object ClassroomDownloads {
    private const val MAX_CONCURRENT = 2
    private const val REFERER = "https://v.sjtu.edu.cn/"
    private const val ORIGIN = "https://v.sjtu.edu.cn"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _tasks = mutableStateListOf<VideoDownloadTask>()
    private var running = 0
    private val lock = Any()

    val tasks: List<VideoDownloadTask> get() = _tasks

    fun enqueue(context: Context, videoId: Long, title: String, url: String) {
        if (url.isBlank()) return
        val appContext = context.applicationContext
        val id = UUID.randomUUID().toString()
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "课堂录像" }
        val fileName = "$safeTitle-${System.currentTimeMillis()}.mp4"
        synchronized(lock) {
            _tasks.add(
                0,
                VideoDownloadTask(
                    id = id,
                    videoId = videoId,
                    title = safeTitle,
                    url = url,
                    fileName = fileName,
                ),
            )
        }
        pump(appContext)
    }

    fun pause(id: String) {
        synchronized(lock) {
            update(id) { it.copy(state = VideoDownloadState.PAUSED) }
            jobs.remove(id)?.cancel()
        }
    }

    fun resume(context: Context, id: String) {
        synchronized(lock) {
            update(id) { it.copy(state = VideoDownloadState.QUEUED, error = null) }
        }
        pump(context.applicationContext)
    }

    fun cancel(id: String) {
        synchronized(lock) {
            jobs.remove(id)?.cancel()
            update(id) { it.copy(state = VideoDownloadState.CANCELED) }
        }
    }

    fun removeFinished() {
        synchronized(lock) {
            _tasks.removeAll {
                it.state == VideoDownloadState.DONE ||
                    it.state == VideoDownloadState.CANCELED ||
                    it.state == VideoDownloadState.FAILED
            }
        }
    }

    private fun pump(context: Context) {
        synchronized(lock) {
            while (running < MAX_CONCURRENT) {
                val task = _tasks.firstOrNull { it.state == VideoDownloadState.QUEUED } ?: break
                update(task.id) { it.copy(state = VideoDownloadState.DOWNLOADING) }
                running++
                jobs[task.id] = scope.launch {
                    runCatching { download(context, task) }
                        .onFailure { cause ->
                            synchronized(lock) {
                                if (cause is kotlinx.coroutines.CancellationException) {
                                    // 暂停/取消状态由调用方写入，这里不覆盖
                                } else {
                                    update(task.id) {
                                        it.copy(
                                            state = VideoDownloadState.FAILED,
                                            error = cause.message ?: "下载失败",
                                        )
                                    }
                                }
                            }
                        }
                    synchronized(lock) {
                        running--
                        jobs.remove(task.id)
                    }
                    pump(context)
                }
            }
        }
    }

    private suspend fun download(context: Context, task: VideoDownloadTask) {
        val directory = File(context.cacheDir, "classroom_downloads").apply { mkdirs() }
        val part = File(directory, "${task.id}.part")
        var existing = if (part.exists()) part.length() else 0L
        val request = Request.Builder()
            .url(task.url)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("User-Agent", HiveHttp.USER_AGENT)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .get()
            .build()
        HiveHttp.client(context).newCall(request).execute().use { response ->
            if (response.code !in 200..299) {
                error("下载失败：HTTP ${response.code}")
            }
            if (response.code == 200 && existing > 0) {
                // 服务器不支持 Range：清掉断点，从头下载，避免拼接出损坏文件。
                part.delete()
                existing = 0
            }
            val length = response.body?.contentLength() ?: -1L
            val total = when {
                response.code == 206 && length > 0 -> existing + length
                length > 0 -> length
                else -> 0L
            }
            synchronized(lock) {
                update(task.id) { it.copy(downloaded = existing, total = total) }
            }
            val input = response.body?.byteStream() ?: error("下载响应为空")
            input.use { stream ->
                java.io.FileOutputStream(part, existing > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = existing
                    var lastUpdate = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            lastUpdate = now
                            synchronized(lock) {
                                update(task.id) { it.copy(downloaded = downloaded) }
                            }
                        }
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    }
                    synchronized(lock) {
                        update(task.id) { it.copy(downloaded = downloaded) }
                    }
                }
            }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        saveToSystemDownloads(context, part, task.fileName)
        part.delete()
        synchronized(lock) {
            update(task.id) {
                it.copy(state = VideoDownloadState.DONE, downloaded = it.total)
            }
        }
    }

    private fun saveToSystemDownloads(context: Context, source: File, displayName: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法写入系统下载目录")
        runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法打开下载目录文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure {
            runCatching { resolver.delete(uri, null, null) }
            throw it
        }
    }

    private fun update(id: String, block: (VideoDownloadTask) -> VideoDownloadTask) {
        val index = _tasks.indexOfFirst { it.id == id }
        if (index >= 0) _tasks[index] = block(_tasks[index])
    }
}
