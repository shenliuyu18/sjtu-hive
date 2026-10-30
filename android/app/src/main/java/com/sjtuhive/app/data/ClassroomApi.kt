package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class VideoCourse(val id: Long, val code: String, val name: String)

data class ClassroomVideo(
    val id: Long,
    val start: String,
    val end: String,
    val room: String,
    val period: String,
)

data class ClassroomStream(val view: Int, val duration: Double, val url: String)

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

/** 课堂视频（v.sjtu.edu.cn），使用 jAccount SSO 会话读取课程、录像与播放地址。 */
class ClassroomApi(private val context: Context) {

    private val prefs = context.getSharedPreferences("hive_classroom", Context.MODE_PRIVATE)
    private val progressPrefs = context.getSharedPreferences("hive_classroom_progress", Context.MODE_PRIVATE)

    fun jwt(): String = prefs.getString("jwt", "").orEmpty()

    fun saveJwt(value: String) {
        prefs.edit().putString("jwt", value.trim()).apply()
    }

    private fun headers(): Map<String, String> = buildMap {
        put("Origin", ORIGIN)
        jwt().takeIf { it.isNotEmpty() }?.let { put("jwt-token", it) }
    }

    fun progress(videoId: Long): Long = progressPrefs.getLong("v$videoId", 0L)

    fun saveProgress(videoId: Long, positionMs: Long) {
        progressPrefs.edit().putLong("v$videoId", positionMs.coerceAtLeast(0L)).apply()
    }

    fun clearProgress(videoId: Long) {
        progressPrefs.edit().remove("v$videoId").apply()
    }

    /** 只检查登录状态，不抛列表错误。 */
    suspend fun connected(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val result = HiveHttp.raw(
                context,
                ME,
                HOME,
                headers = headers(),
            )
            val root = JSONObject(result.body)
            val code = root.opt("code")?.toString()?.trim('"') ?: ""
            code == "0" && root.optJSONObject("result")?.has("user") == true
        }.getOrDefault(false)
    }

    suspend fun courses(): List<VideoCourse> = withContext(Dispatchers.IO) {
        val terms = runCatching { termYear() }.getOrDefault(emptyList())
        val termId = terms.firstOrNull { it.second }?.first ?: terms.lastOrNull()?.first ?: 0L
        var records = if (termId > 0) {
            records(
                "/myself/curriculum",
                mapOf("acteId" to termId.toString(), "courBeginTime" to "", "courEndTime" to ""),
            )
        } else {
            emptyList()
        }
        if (records.isEmpty()) {
            records = records(
                "/group_subject_vod_list/t-1",
                mapOf("page.orders[0].asc" to "false", "page.orders[0].field" to "courBeginTime"),
            )
        }
        val courses = linkedMapOf<Long, VideoCourse>()
        records.forEach { record ->
            val id = if (record.isNull("teclId")) 0L else record.optLong("teclId")
            if (id == 0L) return@forEach
            val name = record.text("subjName")
            if (name.isEmpty()) return@forEach
            courses[id] = VideoCourse(id, record.text("subjCode"), name)
        }
        courses.values.sortedBy { it.name }
    }

    suspend fun videos(courseId: Long): List<ClassroomVideo> = withContext(Dispatchers.IO) {
        records(
            "/subject_vod_list_new",
            mapOf(
                "teclIds" to courseId.toString(),
                "page.orders[0].asc" to "false",
                "page.orders[0].field" to "courBeginTime",
            ),
        ).mapNotNull { record ->
            val id = record.optLong("id")
            if (id == 0L) return@mapNotNull null
            ClassroomVideo(
                id = id,
                start = record.text("courBeginTime"),
                end = record.text("courEndTime"),
                room = record.text("clroName"),
                period = if (record.isNull("letiNumber")) "" else record.opt("letiNumber").toString(),
            )
        }
    }

    suspend fun streams(videoId: Long): List<ClassroomStream> = withContext(Dispatchers.IO) {
        val data = unwrap(
            request("/course_vod_urls_new?courseId=$videoId"),
        ) as? JSONObject ?: throw IllegalStateException("暂未获取到可播放录像")
        val list = data.optJSONArray("courseVodViewList") ?: return@withContext emptyList()
        val streams = mutableListOf<ClassroomStream>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val raw = item.text("url")
            val uri = runCatching { URI(raw) }.getOrNull() ?: continue
            if (uri.scheme != "https" || uri.host != "live.sjtu.edu.cn") continue
            if (!uri.path.orEmpty().lowercase().endsWith(".mp4")) continue
            streams += ClassroomStream(
                view = item.optInt("viewNum"),
                duration = item.optDouble("vodTime", 0.0),
                url = raw,
            )
        }
        streams.sortedBy { it.view }
    }

    suspend fun transcript(videoId: Long): List<TranscriptSegment> = withContext(Dispatchers.IO) {
        val data = runCatching {
            unwrap(request("/course/ai/translate/$videoId?useOriginal=true"))
        }.getOrElse {
            if (it.message?.contains("没有返回数据") == true) return@withContext emptyList()
            throw it
        } as? JSONObject ?: return@withContext emptyList()
        val list = data.optJSONArray("afterAssemblyList") ?: return@withContext emptyList()
        val segments = mutableListOf<TranscriptSegment>()
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val text = item.text("res").trim()
            if (text.isEmpty()) continue
            val start = item.optDouble("bg", 0.0).toLong()
            val end = item.optDouble("ed", 0.0).toLong()
            if (end < start) continue
            segments += TranscriptSegment(start, end, text)
        }
        segments.sortedBy { it.startMs }
    }

    private suspend fun termYear(): List<Pair<Long, Boolean>> {
        val data = unwrap(request("/list/termYear"))
        val array = data as? JSONArray ?: return emptyList()
        val terms = mutableListOf<Pair<Long, Boolean>>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            terms += item.optLong("id") to item.optBoolean("currentTerm")
        }
        return terms
    }

    private suspend fun records(path: String, params: Map<String, String>): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        var page = 1
        while (page <= 20) {
            val data = unwrap(
                request(
                    path,
                    params + mapOf(
                        "page.pageIndex" to page.toString(),
                        "page.pageSize" to "1000",
                    ),
                ),
            ) as? JSONObject ?: break
            val items = data.optJSONArray("records") ?: break
            for (index in 0 until items.length()) {
                items.optJSONObject(index)?.let(result::add)
            }
            val pageCount = data.optInt("pageCount", 1)
            if (page >= pageCount) break
            page++
        }
        return result
    }

    private suspend fun request(path: String, params: Map<String, String> = emptyMap()): String {
        val query = params.entries.joinToString("&") { (name, value) ->
            "${java.net.URLEncoder.encode(name, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
        }
        val url = BASE + path + if (query.isEmpty()) "" else if ('?' in path) "&$query" else "?$query"
        return try {
            HiveHttp.get(
                context,
                url,
                HOME,
                headers = headers(),
            )
        } catch (error: Exception) {
            throw CampusAuthException("需要登录交大视频：${error.message}")
        }
    }

    private fun unwrap(body: String): Any {
        val trimmed = body.trim()
        if (trimmed.startsWith("[")) return JSONArray(trimmed)
        val root = JSONObject(trimmed)
        val code = root.opt("code")?.toString()?.trim('"') ?: ""
        val status = if (root.has("status") && !root.isNull("status")) root.optInt("status") else 200
        if (code == "-1" || status == 401 || status == 403) {
            throw CampusAuthException("需要登录交大视频")
        }
        if (root.has("ok") && !root.isNull("ok") && !root.optBoolean("ok")) {
            throw IllegalStateException("交大视频接口请求失败")
        }
        if (status != 200) error("交大视频接口返回 $status")
        if (code.isNotEmpty() && code != "0" && code != "200") {
            val message = root.text("msg").ifBlank { root.text("message") }
            if (message.isNotEmpty()) error(message)
        }
        return when {
            root.has("result") && !root.isNull("result") -> root.opt("result")
            root.has("data") && !root.isNull("data") -> root.opt("data")
            else -> throw IllegalStateException("交大视频没有返回数据")
        }
    }

    companion object {
        private const val ORIGIN = "https://v.sjtu.edu.cn"
        private const val HOME = "$ORIGIN/jy-application-resourcemanage-ui/"
        private const val BASE = "$ORIGIN/jy-application-resourcemanage/v1"
        private const val ME = "$ORIGIN/jy-application-resourcemanage/authority/me"
    }
}
