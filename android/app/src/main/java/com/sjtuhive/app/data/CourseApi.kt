package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CourseUser(val id: Long, val username: String, val email: String, val role: String)

data class CourseRating(val count: Int, val score: Double)

data class CourseSummary(
    val id: Long,
    val code: String,
    val name: String,
    val credit: Double,
    val department: String,
    val language: String,
    val targetYears: List<String>,
    val lastSemester: String,
    val teacher: String,
    val rating: CourseRating,
)

data class CourseDetail(
    val course: CourseSummary,
    val moderatorRemark: String,
    val offeredSemesters: List<String>,
)

data class CourseReview(
    val id: Long,
    val course: CourseSummary?,
    val semester: String,
    val score: String,
    val rating: Int,
    val content: String,
    val createdAt: String,
)

data class CourseReviewPage(val items: List<CourseReview>, val total: Int, val page: Int, val pageSize: Int)

data class CoursePage(val items: List<CourseSummary>, val total: Int, val page: Int, val pageSize: Int)

data class CourseEnrollment(val id: Long, val semester: String, val course: CourseSummary)

data class CourseFilterOption(val name: String, val count: Int)

data class CourseFilters(val departments: List<CourseFilterOption>, val semesters: List<CourseFilterOption>)

/** 选课社区（course.sjtu.plus）只读客户端；只持久化会话 Cookie。 */
class CourseApi(private val context: Context) {
    private val prefs = context.getSharedPreferences("hive_course", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun loggedIn(): Boolean = loadCookie().isNotEmpty()

    suspend fun state(): CourseUser? = withContext(Dispatchers.IO) {
        if (loadCookie().isEmpty()) return@withContext null
        runCatching { me() }.getOrNull()
    }

    suspend fun login(email: String, password: String): CourseUser = withContext(Dispatchers.IO) {
        val csrfRequest = request("/auth/csrf") { header("Accept", "application/json") }.build()
        val csrfToken = client.newCall(csrfRequest).execute().use { response ->
            updateCookies(response)
            if (!response.isSuccessful) throw CourseAuthException("获取登录校验失败（HTTP ${response.code}）")
            response.header("X-CSRF-Token").orEmpty()
        }
        if (csrfToken.isEmpty()) throw CourseAuthException("选课社区没有返回登录校验")
        val body = JSONObject().put("email", email.trim()).put("password", password)
            .toString().toRequestBody(JSON)
        val loginRequest = request("/auth/login") {
            post(body)
            header("X-CSRF-Token", csrfToken)
            header("Origin", ORIGIN)
            header("Referer", "$ORIGIN/login")
        }.build()
        client.newCall(loginRequest).execute().use { response ->
            val text = response.body?.string().orEmpty()
            updateCookies(response)
            if (!response.isSuccessful) throw CourseAuthException(errorMessage(text, "邮箱或密码不正确"))
            val user = JSONObject(text)
            saveCookie()
            CourseUser(
                id = user.optLong("id"),
                username = user.optString("username"),
                email = user.optString("email"),
                role = user.optString("role"),
            )
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        runCatching {
            val request = request("/auth/logout") {
                post(EMPTY_BODY)
                header("Origin", ORIGIN)
                header("Referer", "$ORIGIN/")
            }.build()
            client.newCall(request).execute().close()
        }
        clearCookie()
    }

    suspend fun hot(limit: Int = 6): List<CourseSummary> = withContext(Dispatchers.IO) {
        val data = get("/course/hot?period=week&limit=${limit.coerceIn(1, 20)}")
        val array = data.optJSONArray("items") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optJSONObject("course")?.toCourseSummary()
        }
    }

    suspend fun filters(): CourseFilters = withContext(Dispatchers.IO) {
        val data = get("/course/filter")
        CourseFilters(
            departments = data.optJSONArray("departments").toFilterOptions(),
            semesters = data.optJSONArray("semesters").toFilterOptions(),
        )
    }

    suspend fun list(
        query: String,
        page: Int,
        pageSize: Int = 20,
        department: String = "",
        semester: String = "",
    ): CoursePage = withContext(Dispatchers.IO) {
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https").host("course.sjtu.plus").addPathSegments("api/course/")
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("page_size", pageSize.coerceIn(1, 50).toString())
            .apply {
                if (query.isNotBlank()) addQueryParameter("q", query.trim())
                if (department.isNotBlank()) addQueryParameter("department", department)
                if (semester.isNotBlank()) addQueryParameter("semester", semester)
            }
            .build()
        val data = getAbsolute(url.toString())
        CoursePage(
            items = data.optJSONArray("items").toCourseSummaries(),
            total = data.optInt("total"),
            page = data.optInt("page", 1),
            pageSize = data.optInt("page_size", pageSize),
        )
    }

    suspend fun recentReviews(page: Int = 1, pageSize: Int = 20): CourseReviewPage =
        withContext(Dispatchers.IO) {
            val data = get("/review?page=${page.coerceAtLeast(1)}&page_size=${pageSize.coerceIn(1, 50)}")
            val array = data.optJSONArray("items") ?: JSONArray()
            CourseReviewPage(
                items = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toReview() },
                total = data.optInt("total"),
                page = data.optInt("page", 1),
                pageSize = data.optInt("page_size", pageSize),
            )
        }

    suspend fun followed(page: Int = 1, pageSize: Int = 10): CoursePage =
        withContext(Dispatchers.IO) {
            val data = get("/course/followed?page=${page.coerceAtLeast(1)}&page_size=${pageSize.coerceIn(1, 50)}")
            CoursePage(
                items = data.optJSONArray("items").toCourseSummaries(),
                total = data.optInt("total"),
                page = data.optInt("page", 1),
                pageSize = data.optInt("page_size", pageSize),
            )
        }

    suspend fun ignored(page: Int = 1, pageSize: Int = 10): CoursePage =
        withContext(Dispatchers.IO) {
            val data = get("/course/ignored?page=${page.coerceAtLeast(1)}&page_size=${pageSize.coerceIn(1, 50)}")
            CoursePage(
                items = data.optJSONArray("items").toCourseSummaries(),
                total = data.optInt("total"),
                page = data.optInt("page", 1),
                pageSize = data.optInt("page_size", pageSize),
            )
        }

    suspend fun enrolled(): List<CourseEnrollment> = withContext(Dispatchers.IO) {
        val data = getValue("/course/enrolled")
        val array = when (data) {
            is JSONArray -> data
            is JSONObject -> data.optJSONArray("items") ?: JSONArray()
            else -> JSONArray()
        }
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val course = item.optJSONObject("course")?.toCourseSummary() ?: return@mapNotNull null
            CourseEnrollment(item.optLong("id"), item.optString("semester"), course)
        }
    }

    suspend fun detail(id: Long): CourseDetail = withContext(Dispatchers.IO) {
        val data = get("/course/$id")
        val summary = data.toCourseSummary()
        val semesters = data.optJSONArray("offered_courses")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.optString("semester")?.takeIf { it.isNotBlank() }
            }.distinct()
        }.orEmpty()
        CourseDetail(summary, data.optString("moderator_remark"), semesters)
    }

    suspend fun reviews(id: Long, page: Int = 1, pageSize: Int = 10): CourseReviewPage =
        withContext(Dispatchers.IO) {
            val data = get("/course/$id/review?page=${page.coerceAtLeast(1)}&page_size=${pageSize.coerceIn(1, 50)}")
            val array = data.optJSONArray("items") ?: JSONArray()
            CourseReviewPage(
                items = (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toReview() },
                total = data.optInt("total"),
                page = data.optInt("page", 1),
                pageSize = data.optInt("page_size", pageSize),
            )
        }

    private fun me(): CourseUser {
        val data = get("/auth/me")
        return CourseUser(
            id = data.optLong("id"),
            username = data.optString("username"),
            email = data.optString("email"),
            role = data.optString("role"),
        )
    }

    private fun get(path: String): JSONObject = getValue("$BASE$path") as JSONObject

    private fun getValue(path: String): Any = getValueAbsolute(if (path.startsWith("http")) path else "$BASE$path")

    private fun getAbsolute(url: String): JSONObject = getValueAbsolute(url) as JSONObject

    private fun getValueAbsolute(url: String): Any {
        val request = request(url) { header("Accept", "application/json") }.build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            updateCookies(response)
            if (response.code == 401 || response.code == 403) {
                clearCookie()
                throw CourseAuthException("选课社区登录已失效，请重新登录")
            }
            if (!response.isSuccessful) throw CourseAuthException(errorMessage(text, "选课社区返回 HTTP ${response.code}"))
            return runCatching { JSONObject(text) }.getOrElse { JSONArray(text) }
        }
    }

    private fun request(path: String, block: Request.Builder.() -> Unit = {}): Request.Builder {
        val builder = Request.Builder()
            .url(if (path.startsWith("http")) path else "$BASE$path")
            .header("User-Agent", USER_AGENT)
        val cookie = cookieHeader()
        if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
        return builder.apply(block)
    }

    private fun cookieHeader(): String {
        val saved = loadCookie()
        if (saved.isEmpty()) return ""
        return runCatching {
            val json = JSONObject(saved)
            json.keys().asSequence().mapNotNull { key -> json.optString(key).takeIf { it.isNotEmpty() }?.let { "$key=$it" } }
                .joinToString("; ")
        }.getOrDefault("")
    }

    private fun updateCookies(response: okhttp3.Response) {
        val cookies = mutableMapOf<String, String>()
        val saved = loadCookie()
        if (saved.isNotEmpty()) {
            runCatching {
                val json = JSONObject(saved)
                json.keys().asSequence().forEach { key -> cookies[key] = json.optString(key) }
            }
        }
        response.headers("Set-Cookie").forEach { raw ->
            val pair = raw.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) cookies[name] = value
        }
        if (cookies.isNotEmpty()) {
            val json = JSONObject()
            cookies.forEach { (key, value) -> json.put(key, value) }
            saveJson(json.toString())
        }
    }

    private fun loadCookie(): String = SecureStore.decrypt(context, prefs.getString("cookie", "").orEmpty())

    private fun saveCookie() {
        val header = cookieHeader()
        if (header.isNotEmpty()) saveJson(JSONObject().apply {
            header.split("; ").forEach { part ->
                val name = part.substringBefore('=')
                val value = part.substringAfter('=', "")
                if (name.isNotEmpty() && value.isNotEmpty()) put(name, value)
            }
        }.toString())
    }

    private fun saveJson(value: String) {
        prefs.edit().putString("cookie", SecureStore.encrypt(context, value)).apply()
    }

    private fun clearCookie() {
        prefs.edit().remove("cookie").apply()
    }

    private fun errorMessage(text: String, fallback: String): String {
        return runCatching { JSONObject(text).optString("error").ifBlank { fallback } }.getOrDefault(fallback)
    }

    private fun JSONObject?.toCourseSummary(): CourseSummary {
        val item = this ?: JSONObject()
        val rating = item.optJSONObject("rating")
        val teacher = item.optJSONObject("main_teacher")
        return CourseSummary(
            id = item.optLong("id"),
            code = item.optString("code"),
            name = item.optString("name"),
            credit = item.optDouble("credit"),
            department = item.optString("department"),
            language = item.optString("language"),
            targetYears = item.optJSONArray("target_years").toStringList(),
            lastSemester = item.optString("last_semester"),
            teacher = teacher?.optString("name").orEmpty(),
            rating = CourseRating(rating?.optInt("count") ?: 0, rating?.optDouble("score") ?: 0.0),
        )
    }

    private fun JSONArray?.toCourseSummaries(): List<CourseSummary> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toCourseSummary() }
    }

    private fun JSONArray?.toFilterOptions(): List<CourseFilterOption> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            CourseFilterOption(item.optString("name"), item.optInt("count"))
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }

    private fun JSONObject.toReview(): CourseReview = CourseReview(
        id = optLong("id"),
        course = optJSONObject("course")?.toCourseSummary(),
        semester = optString("semester"),
        score = optString("score"),
        rating = optInt("rating"),
        content = optString("content"),
        createdAt = optString("created_at"),
    )

    private companion object {
        const val BASE = "https://course.sjtu.plus/api"
        const val ORIGIN = "https://course.sjtu.plus"
        const val USER_AGENT = "SJTU-Hive/Android"
        val JSON = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

class CourseAuthException(message: String) : Exception(message)
