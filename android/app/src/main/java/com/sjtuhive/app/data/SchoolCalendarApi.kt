package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class SchoolEvent(
    val id: String,
    val title: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val location: String,
    val calendarName: String,
    val colorHex: String,
    val allDay: Boolean,
) {
    fun covers(day: LocalDate): Boolean {
        val dayStart = day.atStartOfDay()
        val dayEnd = day.plusDays(1).atStartOfDay()
        return startAt.isBefore(dayEnd) && endAt.isAfter(dayStart)
    }
}

class CampusAuthException(message: String) : Exception(message)

/**
 * 日程服务（calendar.sjtu.edu.cn）。与桌面端一致：
 * 先访问 my.sjtu 日历页走 jAccount SSO，再读 /api/user/get、/api/calendar/list、/api/event/list。
 */
class SchoolCalendarApi(private val context: Context) {

    /** 冷启动先读磁盘快照，随后页面会刷新覆盖。 */
    fun eventsCached(start: LocalDateTime, end: LocalDateTime): List<SchoolEvent> =
        HiveCache.getArray(context, "calendar:events:${start}-${end}")?.let { array ->
            (0 until array.length()).mapNotNull { item ->
                array.optJSONObject(item)?.let { json ->
                    runCatching {
                        SchoolEvent(
                            id = json.optString("id"),
                            title = json.optString("title"),
                            startAt = java.time.LocalDateTime.parse(json.optString("startAt")),
                            endAt = java.time.LocalDateTime.parse(json.optString("endAt")),
                            location = json.optString("location"),
                            calendarName = json.optString("calendarName"),
                            colorHex = json.optString("colorHex"),
                            allDay = json.optBoolean("allDay"),
                        )
                    }.getOrNull()
                }
            }
        } ?: emptyList()

    suspend fun events(start: LocalDateTime, end: LocalDateTime): List<SchoolEvent> = withContext(Dispatchers.IO) {
        try {
            loadEvents(start, end).also { events ->
                runCatching {
                    val snapshot = org.json.JSONArray()
                    events.forEach { event ->
                        snapshot.put(
                            org.json.JSONObject()
                                .put("id", event.id)
                                .put("title", event.title)
                                .put("startAt", event.startAt.toString())
                                .put("endAt", event.endAt.toString())
                                .put("location", event.location)
                                .put("calendarName", event.calendarName)
                                .put("colorHex", event.colorHex)
                                .put("allDay", event.allDay),
                        )
                    }
                    HiveCache.putArray(context, "calendar:events:${start}-${end}", snapshot)
                }
            }
        } catch (error: Exception) {
            // 首次登录或日程会话过期时，OkHttp 的 SSO 链路未必能拿到日历会话，
            // 先用隐藏 WebView 静默登录一次日历站，再重试。
            CampusSession.refresh(context, "calendar.sjtu.edu.cn")
            loadEvents(start, end)
        }
    }

    private suspend fun loadEvents(start: LocalDateTime, end: LocalDateTime): List<SchoolEvent> {
        ensureSession()
        val calendars = loadCalendars()
        val query = "startDate=${encode(start.format(DATE_TIME))}" +
            "&endDate=${encode(end.format(DATE_TIME))}&weekly=false&ids="
        val payload = requestJson("https://calendar.sjtu.edu.cn/api/event/list?$query")
        val data = payload.optJSONObject("data") ?: JSONObject()
        val rawEvents = mutableListOf<JSONObject>()
        data.optJSONArray("events")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(rawEvents::add)
            }
        }
        data.optJSONObject("schoolCalendar")?.optJSONArray("events")?.let { array ->
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let(rawEvents::add)
            }
        }
        return rawEvents.mapNotNull { mapEvent(it, calendars) }
            .distinctBy { it.id }
            .sortedBy { it.startAt }
    }

    private suspend fun ensureSession() {
        if (runCatching { accountName() }.isSuccess) return
        for (page in listOf(
            "https://my.sjtu.edu.cn/ui/calendar/",
            "https://calendar.sjtu.edu.cn/?tenantUserId=",
        )) {
            runCatching {
                HiveHttp.get(context, page, "https://my.sjtu.edu.cn/", acceptJson = false)
            }
        }
        accountName()
    }

    private suspend fun accountName(): String {
        val payload = requestJson("https://calendar.sjtu.edu.cn/api/user/get")
        val data = payload.optJSONObject("data")
        val account = data?.text("accountNo").orEmpty()
        if (!payload.optBoolean("success") || account.isEmpty()) {
            throw CampusAuthException("需要登录交大账号后才能读取日程")
        }
        return data?.text("name").orEmpty().ifBlank { account }
    }

    private suspend fun loadCalendars(): Map<String, Pair<String, String>> {
        val payload = requestJson("https://calendar.sjtu.edu.cn/api/calendar/list")
        val result = mutableMapOf<String, Pair<String, String>>()
        listOf("my", "share").forEach { key ->
            payload.optJSONObject("data")?.optJSONArray(key)?.let { array ->
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.text("id")
                    if (id.isEmpty()) continue
                    result[id] = item.text("name").ifBlank { id } to
                        item.text("color").ifBlank { "#5698DB" }
                }
            }
        }
        result["schoolCalendar"] = "校历" to "#3788D8"
        return result
    }

    private suspend fun requestJson(url: String): JSONObject {
        val body = try {
            HiveHttp.get(context, url, "https://calendar.sjtu.edu.cn/")
        } catch (error: Exception) {
            throw CampusAuthException(error.message ?: "日程服务无法连接")
        }
        val payload = runCatching { JSONObject(body) }.getOrElse {
            throw CampusAuthException("登录已失效，请重新登录")
        }
        if (!payload.optBoolean("success", true)) {
            throw CampusAuthException(payload.optString("msg", "日程服务返回失败"))
        }
        return payload
    }

    private fun mapEvent(raw: JSONObject, calendars: Map<String, Pair<String, String>>): SchoolEvent? {
        val start = parseTime(raw.text("startTime")) ?: return null
        var end = parseTime(raw.text("endTime")) ?: start.plusMinutes(30)
        if (!end.isAfter(start)) end = start.plusMinutes(30)
        val title = raw.text("title").ifBlank { raw.text("titleEn") }
        val id = raw.text("eventId")
        if (title.isEmpty() || id.isEmpty()) return null
        val calendarId = raw.text("calendarId").ifBlank { "schoolCalendar" }
        val (name, color) = calendars[calendarId] ?: calendarId to "#5698DB"
        val location = raw.text("location").ifBlank { raw.text("locationEn") }
        return SchoolEvent(
            id = id,
            title = title,
            startAt = start,
            endAt = end,
            location = location,
            calendarName = name,
            colorHex = color,
            allDay = raw.optBoolean("allDay"),
        )
    }

    private fun parseTime(value: String): LocalDateTime? {
        val trimmed = value.trim()
        for (formatter in TIME_FORMATS) {
            try {
                return LocalDateTime.parse(trimmed, formatter)
            } catch (_: DateTimeParseException) {
            }
        }
        try {
            return LocalDate.parse(trimmed, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
        } catch (_: DateTimeParseException) {
        }
        return null
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val TIME_FORMATS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
        )
    }
}
