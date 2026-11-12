package com.sjtuhive.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class TransferTask(
    val id: String,
    val kind: String,
    val name: String,
    val localPath: String,
    val remotePath: String,
    val size: Long,
    val sent: Long,
    val status: String,
    val error: String = "",
    val confirmKey: String = "",
) {
    val progress: Float
        get() = if (size <= 0) 0f else (sent.toFloat() / size).coerceIn(0f, 1f)
}

/**
 * 进程内云盘传输队列：2 个并发，下载 Range 续传，上传沿用 confirmKey 补分片。
 * 任务元数据存 SharedPreferences；进程被杀后重启会标记为暂停。
 */
object CloudPanTransfers {
    private const val PREFS = "hive_cloudpan_transfers"
    private const val KEY = "tasks"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    private lateinit var appContext: Context
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        val stored = load()
        _tasks.value = stored.map {
            if (it.status == "running" || it.status == "queued") it.copy(status = "paused") else it
        }
        persist()
    }

    fun enqueueUpload(context: Context, uri: Uri, remoteDir: String) {
        init(context)
        scope.launch {
            runCatching {
                val name = displayName(context, uri) ?: "upload.bin"
                val safe = sanitize(name)
                val dir = File(context.cacheDir, "transfers").apply { mkdirs() }
                val local = File(dir, "${UUID.randomUUID()}-$safe")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    local.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取所选文件")
                val remote = if (remoteDir.isBlank()) sanitize(name) else "${remoteDir.trim('/')}/$safe"
                val task = TransferTask(
                    id = UUID.randomUUID().toString(),
                    kind = "upload",
                    name = name,
                    localPath = local.absolutePath,
                    remotePath = remote,
                    size = local.length(),
                    sent = 0,
                    status = "queued",
                )
                add(task)
                start(task)
            }.onFailure { }
        }
    }

    fun enqueueUploadTree(context: Context, tree: Uri, remoteDir: String) {
        init(context)
        scope.launch {
            runCatching { walkTree(context, tree, remoteDir) }
        }
    }

    fun enqueueDownload(path: String, name: String, size: Long) {
        if (!initialized) return
        val safe = sanitize(name)
        val task = TransferTask(
            id = UUID.randomUUID().toString(),
            kind = "download",
            name = name,
            localPath = File(File(appContext.cacheDir, "transfers"), "${UUID.randomUUID()}-$safe.part").absolutePath,
            remotePath = path,
            size = size,
            sent = 0,
            status = "queued",
        )
        add(task)
        start(task)
    }

    fun pause(id: String) {
        update(id) { it.copy(status = "paused") }
        jobs.remove(id)?.cancel()
        persist()
    }

    fun pauseAll() {
        _tasks.value.filter { it.status == "running" || it.status == "queued" }
            .forEach { pause(it.id) }
    }

    fun resumeAll() {
        _tasks.value.filter { it.status == "paused" || it.status == "failed" }
            .forEach { resume(it.id) }
    }

    fun resume(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status != "paused" && task.status != "failed") return
        val next = task.copy(status = "queued", sent = if (task.status == "failed") 0 else task.sent, error = "")
        update(id) { next }
        start(next)
    }

    fun cancel(id: String) {
        update(id) { it.copy(status = "canceled") }
        jobs.remove(id)?.cancel()
        _tasks.value.firstOrNull { it.id == id }?.let { task ->
            runCatching { File(task.localPath).delete() }
        }
        persist()
    }

    fun retry(id: String) = resume(id)

    fun clear(id: String) {
        jobs.remove(id)?.cancel()
        _tasks.value = _tasks.value.filterNot { it.id == id }
        persist()
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filter { it.status !in setOf("done", "canceled") }
        persist()
    }

    private fun start(task: TransferTask) {
        if (jobs.containsKey(task.id)) return
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(task)
            } catch (canceled: CancellationException) {
                // 暂停/取消由调用方写入状态
            } catch (error: Throwable) {
                val current = _tasks.value.firstOrNull { it.id == task.id } ?: return@launch
                if (current.status == "paused" || current.status == "canceled") return@launch
                update(task.id) { it.copy(status = "failed", error = error.message ?: "传输失败") }
            } finally {
                jobs.remove(task.id, job)
                persist()
            }
        }
        jobs[task.id] = job
        job.start()
    }

    private suspend fun run(task: TransferTask) {
        update(task.id) { it.copy(status = "running", error = "") }
        if (task.kind == "download") {
            download(task)
        } else {
            upload(task)
            runCatching { File(task.localPath).delete() }
        }
        update(task.id) { it.copy(status = "done", sent = it.size, error = "") }
    }

    private suspend fun download(task: TransferTask) {
        val api = CloudPanApi(appContext)
        val (url, size) = api.fileInfo(task.remotePath)
        if (size > 0 && size != task.size) update(task.id) { it.copy(size = size) }
        val part = File(task.localPath)
        part.parentFile?.mkdirs()
        var offset = if (part.exists()) part.length() else 0L
        if (size > 0 && offset > size) {
            part.delete()
            offset = 0
        }
        val builder = Request.Builder().url(url).header("User-Agent", HiveHttp.USER_AGENT)
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        HiveHttp.client(appContext).newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            if (response.code == 200 && offset > 0) offset = 0
            val body = response.body ?: error("下载内容为空")
            val total = if (size > 0) size else body.contentLength()
            var written = offset
            java.io.FileOutputStream(part, offset > 0).use { stream ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == false) throw kotlinx.coroutines.CancellationException("传输已暂停")
                        val read = input.read(buffer)
                        if (read <= 0) break
                        stream.write(buffer, 0, read)
                        written += read
                        if (System.currentTimeMillis() - last > 300) {
                            last = System.currentTimeMillis()
                            update(task.id) { it.copy(sent = written, size = total) }
                        }
                    }
                }
            }
        }
        if (size > 0 && part.length() != size) error("下载不完整，可继续传输")
        saveToDownloads(appContext, part, task.name)
        part.delete()
    }

    private suspend fun upload(task: TransferTask) {
        val api = CloudPanApi(appContext)
        val local = File(task.localPath)
        if (!local.exists()) error("本地文件不存在")
        api.uploadFile(
            local = local,
            remotePath = task.remotePath,
            confirmKey = task.confirmKey.takeIf { it.isNotEmpty() },
            onProgress = { sent, total ->
                update(task.id) { it.copy(sent = sent, size = total) }
            },
            onSession = { key ->
                if (key.isNotEmpty() && key != task.confirmKey) {
                    update(task.id) { it.copy(confirmKey = key) }
                    persist()
                }
            },
        )
    }

    @Synchronized
    private fun add(task: TransferTask) {
        _tasks.value = listOf(task) + _tasks.value
        persist()
    }

    @Synchronized
    private fun update(id: String, transform: (TransferTask) -> TransferTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    @Synchronized
    private fun persist() {
        if (!initialized) return
        val array = JSONArray()
        _tasks.value.forEach { task ->
            array.put(JSONObject().apply {
                put("id", task.id)
                put("kind", task.kind)
                put("name", task.name)
                put("localPath", task.localPath)
                put("remotePath", task.remotePath)
                put("size", task.size)
                put("sent", task.sent)
                put("status", task.status)
                put("error", task.error)
                put("confirmKey", task.confirmKey)
            })
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<TransferTask> {
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        TransferTask(
                            id = item.optString("id"),
                            kind = item.optString("kind"),
                            name = item.optString("name"),
                            localPath = item.optString("localPath"),
                            remotePath = item.optString("remotePath"),
                            size = item.optLong("size"),
                            sent = item.optLong("sent"),
                            status = item.optString("status"),
                            error = item.optString("error"),
                            confirmKey = item.optString("confirmKey"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun walkTree(context: Context, tree: Uri, remoteDir: String) {
        val root = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree) ?: return
        fun visit(dir: androidx.documentfile.provider.DocumentFile, remote: String) {
            dir.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                val nextRemote = if (remote.isBlank()) name else "$remote/$name"
                if (child.isDirectory) {
                    visit(child, nextRemote)
                } else {
                    enqueueUpload(context, child.uri, remote)
                }
            }
        }
        visit(root, if (remoteDir.isBlank()) root.name.orEmpty() else "${remoteDir.trim('/')}/${root.name}")
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) return cursor.getString(index)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download.bin" }

    private fun saveToDownloads(context: Context, source: File, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, sanitize(name))
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: error("无法写入下载目录")
        context.contentResolver.openOutputStream(uri)?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        } ?: error("无法写入下载目录")
    }
}
