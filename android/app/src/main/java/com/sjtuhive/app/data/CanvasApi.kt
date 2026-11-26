package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Canvas 未开放该课程内容（403/404），不等于登录失效。 */
class CanvasAccessException(message: String) : Exception(message)

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

data class CanvasPage(
    val url: String,
    val title: String,
    val updatedAt: String,
)

data class CanvasDiscussion(
    val id: Long,
    val title: String,
    val postedAt: String,
    val replies: Int,
    val htmlUrl: String,
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

    fun usable(): Boolean {
        if (token().isNotEmpty()) return true
        val bucket = HiveSession.store(context)["jaccount.sjtu.edu.cn"].orEmpty()
        return bucket.keys.any { it.equals("JAAuthCookie", ignoreCase = true) }
    }

    private var sessionReady = false

    private suspend fun ensureSession() {
        if (sessionReady) return
        val result = HiveHttp.raw(
            context,
            "https://oc.sjtu.edu.cn/login/openid_connect",
            "https://oc.sjtu.edu.cn/",
        )
        if (result.code !in 200..299) {
            throw CampusAuthException("请先在设置中登录 jAccount")
        }
        sessionReady = true
    }

    fun saveToken(value: String) {
        prefs.edit().putString("token", value.trim()).apply()
    }

    fun clearToken() {
        prefs.edit().remove("token").apply()
    }

    /** 冷启动先读磁盘快照，随后页面会刷新覆盖。 */
    fun coursesCached(): List<CanvasCourse> =
        HiveCache.getString(context, "canvas:courses")?.let { body ->
            runCatching { parseCourses(body) }.getOrNull()
        } ?: emptyList()

    fun filesCached(courseId: Long): List<CanvasFile> =
        HiveCache.getString(context, "canvas:files:$courseId")?.let { body ->
            runCatching { parseFiles(body) }.getOrNull()
        } ?: emptyList()

    suspend fun courses(): List<CanvasCourse> = withContext(Dispatchers.IO) {
        val body = getList("/courses?enrollment_state=active&per_page=100&include[]=term")
        HiveCache.putString(context, "canvas:courses", body)
        parseCourses(body)
    }

    private fun parseCourses(body: String): List<CanvasCourse> {
        val array = JSONArray(body)
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
        return result.sortedBy { it.name }
    }

    suspend fun files(courseId: Long): List<CanvasFile> = withContext(Dispatchers.IO) {
        val body = getList("/courses/$courseId/files?per_page=100&sort=name&order=asc")
        HiveCache.putString(context, "canvas:files:$courseId", body)
        parseFiles(body)
    }

    private fun parseFiles(body: String): List<CanvasFile> {
        val array = JSONArray(body)
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
        return result
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
        if (token().isEmpty()) ensureSession()
        val name = file.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "canvas-file" }
        val directory = File(cacheDir, "canvas-preview").apply { mkdirs() }
        val target = File(directory, name)
        if (target.exists() && target.length() > 0) return@withContext target
        val token = token()
        val builder = Request.Builder().url(file.url)
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        val httpClient = if (token.isEmpty()) HiveHttp.client(context) else downloadClient
        httpClient.newCall(builder.build()).execute().use { response ->
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
        val array = JSONArray(getList("/courses/$courseId/assignments?per_page=100&order_by=due_at"))
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
            getList("/courses/$courseId/discussion_topics?only_announcements=true&per_page=50"),
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

    /** 课程页面（Pages）：列表与正文。 */
    suspend fun pages(courseId: Long): List<CanvasPage> = withContext(Dispatchers.IO) {
        val array = JSONArray(getList("/courses/$courseId/pages?per_page=100&sort=title"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.text("url")
            if (url.isEmpty()) return@mapNotNull null
            CanvasPage(
                url = url,
                title = item.text("title").ifBlank { url },
                updatedAt = item.text("updated_at"),
            )
        }
    }

    suspend fun pageBody(courseId: Long, pageUrl: String): CanvasDoc = withContext(Dispatchers.IO) {
        val item = JSONObject(get("/courses/$courseId/pages/$pageUrl"))
        CanvasDoc(title = item.text("title"), html = item.text("body"))
    }

    /** 课程讨论（Discussion Topics）：列表与正文。 */
    suspend fun discussions(courseId: Long): List<CanvasDiscussion> = withContext(Dispatchers.IO) {
        val array = JSONArray(getList("/courses/$courseId/discussion_topics?per_page=100"))
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val title = item.text("title")
            if (title.isEmpty()) return@mapNotNull null
            CanvasDiscussion(
                id = item.optLong("id"),
                title = title,
                postedAt = item.text("posted_at").ifBlank { item.text("created_at") },
                replies = item.optInt("discussion_subentry_count"),
                htmlUrl = item.text("html_url"),
            )
        }
    }

    suspend fun discussionBody(courseId: Long, id: Long): CanvasDoc = withContext(Dispatchers.IO) {
        val item = JSONObject(get("/courses/$courseId/discussion_topics/$id"))
        CanvasDoc(title = item.text("title"), html = item.text("message"))
    }

    suspend fun modules(courseId: Long): List<CanvasModule> = withContext(Dispatchers.IO) {
        val array = JSONArray(getList("/courses/$courseId/modules?include[]=items&per_page=100"))
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
        if (token.isEmpty()) ensureSession()
        val current = token()
        val result = HiveHttp.raw(
            context,
            "$BASE$path",
            "https://oc.sjtu.edu.cn/",
            headers = if (current.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $current"),
        )
        if (result.code == 404) throw CanvasAccessException("Canvas 资源不存在或无访问权限")
        if (result.code == 401 || result.code == 403) {
            // Canvas 会话过期但 jAccount 仍有效时，静默走一次 SSO 换新会话再试一次；
            // 只有重试仍然失败才报登录失效，避免「无内容/会话过期」被误报成未登录。
            sessionReady = false
            val renewed = runCatching { CampusSession.refresh(context, "oc.sjtu.edu.cn") }.getOrDefault(false)
            ensureSession()
            val fresh = token()
            if (renewed || fresh.isNotEmpty()) {
                val retry = HiveHttp.raw(
                    context,
                    "$BASE$path",
                    "https://oc.sjtu.edu.cn/",
                    headers = if (fresh.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $fresh"),
                )
                if (retry.code in 200..299) return retry.body
                if (retry.code == 403 || retry.code == 404) {
                    throw CanvasAccessException("没有访问权限（课程可能未开放该内容）")
                }
            }
            if (result.code == 403) throw CanvasAccessException("没有访问权限（课程可能未开放该内容）")
            throw CampusAuthException("Canvas 登录已失效，请先在设置中重新登录 jAccount")
        }
        if (result.code !in 200..299) error("Canvas 返回 HTTP ${result.code}")
        return result.body
    }

    /** 课程未开放某类内容时 Canvas 返回 403/404，按空列表处理而不是报登录失效。 */
    private suspend fun getList(path: String): String = try {
        get(path)
    } catch (_: CanvasAccessException) {
        "[]"
    }

    companion object {
        private const val BASE = "https://oc.sjtu.edu.cn/api/v1"
        private val downloadClient = OkHttpClient.Builder().followRedirects(true).build()
    }
}
