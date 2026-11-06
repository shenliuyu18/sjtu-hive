package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CanvasCourse(
    val id: Long,
    val name: String,
    val code: String,
    val term: String,
)

data class CanvasFile(
    val id: Long,
    val name: String,
    val size: Long,
    val contentType: String,
    val url: String,
    val updatedAt: String,
)

data class CanvasAssignment(
    val id: Long,
    val name: String,
    val dueAt: String,
    val points: Double,
    val url: String,
)

data class CanvasAnnouncement(
    val id: Long,
    val title: String,
    val postedAt: String,
    val url: String,
)

data class CanvasModuleItem(
    val id: Long,
    val title: String,
    val type: String,
    val url: String,
    val contentId: Long,
    val pageUrl: String,
)

data class CanvasModule(
    val id: Long,
    val name: String,
    val items: List<CanvasModuleItem>,
)

data class CanvasDoc(val title: String, val html: String)

/** Canvas（oc.sjtu.edu.cn）资料。账号使用用户在 Canvas 设置里生成的 API Token。 */
class CanvasApi(private val context: Context) {

    private val prefs = context.getSharedPreferences("hive_canvas", Context.MODE_PRIVATE)

    fun token(): String = prefs.getString("token", "").orEmpty()

    fun saveToken(value: String) {
        prefs.edit().putString("token", value.trim()).apply()
    }

    fun clearToken() {
        prefs.edit().remove("token").apply()
    }

    suspend fun courses(): List<CanvasCourse> = withContext(Dispatchers.IO) {
        val array = JSONArray(get("/courses?enrollment_state=active&per_page=100&include[]=term"))
        val result = mutableListOf<CanvasCourse>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.text("name")
            if (name.isEmpty()) continue
            result += CanvasCourse(
                id = item.optLong("id"),
                name = name,
                code = item.text("course_code"),
                term = item.optJSONObject("term")?.text("name").orEmpty(),
            )
        }
        result.sortedBy { it.name }
    }

    suspend fun files(courseId: Long): List<CanvasFile> = withContext(Dispatchers.IO) {
        val array = JSONArray(get("/courses/$courseId/files?per_page=100&sort=name&order=asc"))
        val result = mutableListOf<CanvasFile>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.text("display_name").ifBlank { item.text("filename") }
            val url = item.text("url")
            if (name.isEmpty() || url.isEmpty()) continue
            result += CanvasFile(
                id = item.optLong("id"),
                name = name,
                size = item.optLong("size"),
                contentType = item.text("content-type").ifBlank { item.text("content_type") },
                url = url,
                updatedAt = item.text("updated_at").ifBlank { item.text("created_at") },
            )
        }
        result
    }

    suspend fun file(fileId: Long): CanvasFile = withContext(Dispatchers.IO) {
        val item = JSONObject(get("/files/$fileId"))
        CanvasFile(
            id = item.optLong("id", fileId),
            name = item.text("display_name").ifBlank { item.text("filename") },
            size = item.optLong("size"),
            contentType = item.text("content-type").ifBlank { item.text("content_type") },
            url = item.text("url"),
            updatedAt = item.text("updated_at").ifBlank { item.text("created_at") },
        )
    }

    suspend fun download(file: CanvasFile, cacheDir: File): File = withContext(Dispatchers.IO) {
        val name = file.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "canvas-file" }
        val directory = File(cacheDir, "canvas-preview").apply { mkdirs() }
        val target = File(directory, name)
        if (target.exists() && target.length() > 0) return@withContext target
        val request = Request.Builder()
            .url(file.url)
            .header("Authorization", "Bearer ${token()}")
            .build()
        downloadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
            val body = response.body ?: error("下载失败：响应为空")
            val temp = File(directory, "$name.part")
            temp.outputStream().use { output -> body.byteStream().use { it.copyTo(output) } }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
        target
    }

    suspend fun assignments(courseId: Long): List<CanvasAssignment> = withContext(Dispatchers.IO) {
        val array = JSONArray(get("/courses/$courseId/assignments?per_page=100&order_by=due_at"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.text("name")
            if (name.isEmpty()) return@mapNotNull null
            CanvasAssignment(
                id = item.optLong("id"),
                name = name,
                dueAt = item.text("due_at"),
                points = item.optDouble("points_possible", 0.0),
                url = item.text("html_url"),
            )
        }
    }

    suspend fun announcements(courseId: Long): List<CanvasAnnouncement> = withContext(Dispatchers.IO) {
        val array = JSONArray(
            get("/courses/$courseId/discussion_topics?only_announcements=true&per_page=50"),
        )
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = item.text("title")
            if (title.isEmpty()) return@mapNotNull null
            CanvasAnnouncement(
                id = item.optLong("id"),
                title = title,
                postedAt = item.text("posted_at").ifBlank { item.text("created_at") },
                url = item.text("html_url"),
            )
        }
    }

    suspend fun modules(courseId: Long): List<CanvasModule> = withContext(Dispatchers.IO) {
        val array = JSONArray(get("/courses/$courseId/modules?include[]=items&per_page=100"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.text("name")
            if (name.isEmpty()) return@mapNotNull null
            val items = mutableListOf<CanvasModuleItem>()
            item.optJSONArray("items")?.let { list ->
                for (itemIndex in 0 until list.length()) {
                    val child = list.optJSONObject(itemIndex) ?: continue
                    val title = child.text("title")
                    if (title.isEmpty()) continue
                    items += CanvasModuleItem(
                        id = child.optLong("id"),
                        title = title,
                        type = child.text("type"),
                        url = child.text("html_url").ifBlank { child.text("url") },
                        contentId = child.optLong("content_id"),
                        pageUrl = child.text("page_url"),
                    )
                }
            }
            CanvasModule(
                id = item.optLong("id"),
                name = name,
                items = items,
            )
        }
    }

    suspend fun page(courseId: Long, pageUrl: String): CanvasDoc = withContext(Dispatchers.IO) {
        val root = JSONObject(get("/courses/$courseId/pages/$pageUrl"))
        CanvasDoc(root.text("title"), root.text("body"))
    }

    suspend fun assignmentDoc(courseId: Long, assignmentId: Long): CanvasDoc =
        withContext(Dispatchers.IO) {
            val root = JSONObject(get("/courses/$courseId/assignments/$assignmentId"))
            CanvasDoc(root.text("name"), root.text("description"))
        }

    suspend fun discussionDoc(courseId: Long, topicId: Long): CanvasDoc =
        withContext(Dispatchers.IO) {
            val root = JSONObject(get("/courses/$courseId/discussion_topics/$topicId"))
            CanvasDoc(root.text("title"), root.text("message"))
        }

    private suspend fun get(path: String): String {
        val token = token()
        if (token.isEmpty()) throw CampusAuthException("请先填写 Canvas Token")
        val result = HiveHttp.raw(
            context,
            "$BASE$path",
            "https://oc.sjtu.edu.cn/",
            headers = mapOf("Authorization" to "Bearer $token"),
        )
        if (result.code == 401 || result.code == 403) {
            throw CampusAuthException("Canvas Token 无效或已过期")
        }
        if (result.code !in 200..299) error("Canvas 返回 HTTP ${result.code}")
        return result.body
    }

    companion object {
        private const val BASE = "https://oc.sjtu.edu.cn/api/v1"
        private val downloadClient = OkHttpClient.Builder().followRedirects(true).build()
    }
}
