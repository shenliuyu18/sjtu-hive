package com.sjtuhive.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AndroidUpdate(
    val available: Boolean,
    val current: String,
    val latest: String,
    val notes: String,
    val publishedAt: String,
    val pageUrl: String,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    val digest: String,
    val message: String,
)

/** GitHub Releases 更新检查与安装包下载。 */
class UpdateApi(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun currentVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "0.0.0" }

    suspend fun check(): AndroidUpdate = withContext(Dispatchers.IO) {
        val current = currentVersion()
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SJTU-Hive/$current")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 429) {
                return@withContext base(current).copy(
                    latest = current,
                    message = "GitHub 接口访问受限，请稍后再试。",
                )
            }
            if (!response.isSuccessful) error("检查更新失败：HTTP ${response.code}")
            val body = response.body?.string() ?: error("更新信息为空")
            val json = JSONObject(body)
            val latest = json.optString("tag_name").removePrefix("v").trim()
            if (latest.isEmpty()) error("GitHub 没有返回版本号")
            val notes = json.optString("body").orEmpty()
            val published = json.optString("published_at").orEmpty()
            val page = json.optString("html_url").ifBlank { PAGE }
            var assetName = ""
            var assetUrl = ""
            var assetSize = 0L
            var digest = ""
            json.optJSONArray("assets")?.let { assets ->
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        assetName = name
                        assetUrl = asset.optString("browser_download_url")
                        assetSize = asset.optLong("size")
                        digest = asset.optString("digest")
                        break
                    }
                }
            }
            val available = compareVersions(latest, current) > 0
            AndroidUpdate(
                available = available,
                current = current,
                latest = latest,
                notes = notes,
                publishedAt = published.take(10),
                pageUrl = page,
                assetName = assetName,
                assetUrl = assetUrl,
                assetSize = assetSize,
                digest = digest,
                message = when {
                    !available -> "当前已是最新版本。"
                    assetUrl.isEmpty() -> "发现新版本 $latest，但这个版本没有提供 APK，请到发布页下载。"
                    else -> "发现新版本 $latest"
                },
            )
        }
    }

    suspend fun download(update: AndroidUpdate, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            if (update.assetUrl.isEmpty()) error("这个版本没有提供 APK")
            val request = Request.Builder()
                .url(update.assetUrl)
                .header("User-Agent", "SJTU-Hive/${update.current}")
                .build()
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, update.assetName.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
            val part = File(dir, target.name + ".part")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载更新包失败：HTTP ${response.code}")
                val body = response.body ?: error("更新包内容为空")
                val total = if (update.assetSize > 0) update.assetSize else body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                part.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var received = 0L
                        var last = 0L
                        while (true) {
                            check(!Thread.currentThread().isInterrupted) { "下载已取消" }
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            received += read
                            if (System.currentTimeMillis() - last > 300) {
                                last = System.currentTimeMillis()
                                onProgress(received, total)
                            }
                        }
                        onProgress(received, total)
                    }
                }
                val expected = update.digest.removePrefix("sha256:").lowercase()
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (expected.isNotEmpty() && expected != actual) {
                    part.delete()
                    error("更新包校验失败，已删除下载文件")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) error("无法保存更新包")
            }
            target
        }

    /** 拉起系统安装器；未授予“安装未知应用”时先跳转授权页。 */
    fun install(file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    fun openReleasePage(url: String) {
        val target = url.ifBlank { PAGE }
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun base(current: String) = AndroidUpdate(
        available = false,
        current = current,
        latest = current,
        notes = "",
        publishedAt = "",
        pageUrl = PAGE,
        assetName = "",
        assetUrl = "",
        assetSize = 0,
        digest = "",
        message = "当前已是最新版本。",
    )

    private fun compareVersions(left: String, right: String): Int {
        fun split(value: String): Pair<List<Int>, String> {
            var text = value.removePrefix("v").trim()
            text = text.substringBefore('+')
            val pre = if ('-' in text) text.substringAfter('-') else ""
            text = text.substringBefore('-')
            return text.split('.').map { it.toIntOrNull() ?: 0 } to pre
        }
        fun comparePre(a: String, b: String): Int {
            if (a == b) return 0
            val ap = a.split('.')
            val bp = b.split('.')
            for (index in 0 until maxOf(ap.size, bp.size)) {
                val av = ap.getOrElse(index) { "" }
                val bv = bp.getOrElse(index) { "" }
                if (av == bv) continue
                if (av.isEmpty()) return -1
                if (bv.isEmpty()) return 1
                val an = av.toIntOrNull()
                val bn = bv.toIntOrNull()
                return when {
                    an != null && bn != null -> if (an > bn) 1 else -1
                    an != null -> -1
                    bn != null -> 1
                    av > bv -> 1
                    else -> -1
                }
            }
            return 0
        }
        val (a, aPre) = split(left)
        val (b, bPre) = split(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrElse(index) { 0 }
            val bv = b.getOrElse(index) { 0 }
            if (av != bv) return if (av > bv) 1 else -1
        }
        return when {
            aPre.isEmpty() && bPre.isNotEmpty() -> 1
            aPre.isNotEmpty() && bPre.isEmpty() -> -1
            else -> comparePre(aPre, bPre)
        }
    }

    companion object {
        private const val API = "https://api.github.com/repos/shenliuyu18/sjtu-hive/releases/latest"
        private const val PAGE = "https://github.com/shenliuyu18/sjtu-hive/releases/latest"
    }
}
