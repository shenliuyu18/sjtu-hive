package com.sjtuhive.app.ui

import android.content.Context
import com.sjtuhive.app.data.CloudFile
import com.sjtuhive.app.data.ExamItem
import com.sjtuhive.app.data.MailSummary
import com.sjtuhive.app.data.PortalMessage
import com.sjtuhive.app.data.SchoolEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/**
 * 页面缓存：进程内 + 磁盘两层。底部页签切换会销毁上一个页面的状态，
 * 这里保存最近一次的数据，回到页面时先展示缓存、只有过期才重新请求；
 * 进程重启后磁盘快照仍在，页面先展示缓存、再后台刷新覆盖。
 */
internal object HiveUiCache {
    private const val TTL = 2 * 60 * 1000L
    private const val FILE = "hive_ui_cache.json"
    private var loaded = false

    data class Overview(
        val unread: Int,
        val recent: List<PortalMessage>,
        val events: List<SchoolEvent>,
        val nextExam: ExamItem?,
        val mailUnread: Int = 0,
        val recentMail: List<MailSummary> = emptyList(),
        val at: Long,
    )

    data class Messages(
        val messages: List<PortalMessage>,
        val unread: Int,
        val at: Long,
    )

    data class Schedule(
        val events: List<SchoolEvent>,
        val exams: List<ExamItem>,
        val examError: String?,
        val at: Long,
    )

    data class Cloud(
        val path: String,
        val files: List<CloudFile>,
        val at: Long,
    )

    var overview: Overview? = null
    var messages: Messages? = null
    var schedule: Schedule? = null
    var cloud: Cloud? = null

    fun fresh(at: Long): Boolean = System.currentTimeMillis() - at < TTL

    /** 进程启动后第一次进入页面时调用：把磁盘快照读回内存（幂等）。 */
    fun loadPersisted(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return
            val root = JSONObject(file.readText())
            overview = root.optJSONObject("overview")?.let(::decodeOverview)
            messages = root.optJSONObject("messages")?.let(::decodeMessagesObj)
            schedule = root.optJSONObject("schedule")?.let(::decodeSchedule)
        }
    }

    /** 页面刷新成功后调用：内存快照写盘，供下次冷启动使用。 */
    fun persist(context: Context) {
        runCatching {
            val root = JSONObject()
            overview?.let { root.put("overview", encodeOverview(it)) }
            messages?.let { root.put("messages", encodeMessages(it)) }
            schedule?.let { root.put("schedule", encodeSchedule(it)) }
            File(context.filesDir, FILE).writeText(root.toString())
        }
    }

    fun clear(context: Context? = null) {
        overview = null
        messages = null
        schedule = null
        cloud = null
        context?.let { runCatching { File(it.filesDir, FILE).delete() } }
    }

    // ══ 编解码 ════════════════════════════════════════════════════════

    private fun jsonMessage(item: PortalMessage) = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("body", item.body)
        put("source", item.source)
        put("createdAt", item.createdAt)
        put("url", item.url ?: "")
    }

    private fun decodeMessage(item: JSONObject) = PortalMessage(
        id = item.optString("id"),
        title = item.optString("title"),
        body = item.optString("body"),
        source = item.optString("source"),
        createdAt = item.optString("createdAt"),
        url = item.optString("url").takeIf { it.isNotEmpty() },
    )

    private fun jsonMessages(items: List<PortalMessage>) =
        JSONArray().apply { items.forEach { put(jsonMessage(it)) } }

    private fun decodeMessageList(items: JSONArray): List<PortalMessage> =
        (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::decodeMessage) }

    private fun jsonEvent(item: SchoolEvent) = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("startAt", item.startAt.toString())
        put("endAt", item.endAt.toString())
        put("location", item.location)
        put("calendarName", item.calendarName)
        put("colorHex", item.colorHex)
        put("allDay", item.allDay)
    }

    private fun decodeEvent(item: JSONObject) = SchoolEvent(
        id = item.optString("id"),
        title = item.optString("title"),
        startAt = LocalDateTime.parse(item.optString("startAt")),
        endAt = LocalDateTime.parse(item.optString("endAt")),
        location = item.optString("location"),
        calendarName = item.optString("calendarName"),
        colorHex = item.optString("colorHex"),
        allDay = item.optBoolean("allDay"),
    )

    private fun jsonEvents(items: List<SchoolEvent>) =
        JSONArray().apply { items.forEach { put(jsonEvent(it)) } }

    private fun decodeEventList(items: JSONArray): List<SchoolEvent> =
        (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::decodeEvent) }

    private fun jsonExam(item: ExamItem) = JSONObject().apply {
        put("courseName", item.courseName)
        put("examName", item.examName)
        put("examDate", item.examDate)
        put("timeText", item.timeText)
        put("startAt", item.startAt?.toString() ?: "")
        put("campus", item.campus)
        put("location", item.location)
        put("seat", item.seat)
        put("form", item.form)
        put("teacher", item.teacher)
    }

    private fun decodeExam(item: JSONObject) = ExamItem(
        courseName = item.optString("courseName"),
        examName = item.optString("examName"),
        examDate = item.optString("examDate"),
        timeText = item.optString("timeText"),
        startAt = item.optString("startAt").takeIf { it.isNotEmpty() }?.let {
            runCatching { LocalDateTime.parse(it) }.getOrNull()
        },
        campus = item.optString("campus"),
        location = item.optString("location"),
        seat = item.optString("seat"),
        form = item.optString("form"),
        teacher = item.optString("teacher"),
    )

    private fun jsonExams(items: List<ExamItem>) =
        JSONArray().apply { items.forEach { put(jsonExam(it)) } }

    private fun decodeExamList(items: JSONArray): List<ExamItem> =
        (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::decodeExam) }

    private fun jsonMail(item: MailSummary) = JSONObject().apply {
        put("index", item.index)
        put("subject", item.subject)
        put("from", item.from)
        put("date", item.date)
        put("seen", item.seen)
    }

    private fun decodeMail(item: JSONObject) = MailSummary(
        index = item.optInt("index"),
        subject = item.optString("subject"),
        from = item.optString("from"),
        date = item.optString("date"),
        seen = item.optBoolean("seen"),
    )

    private fun jsonMails(items: List<MailSummary>) =
        JSONArray().apply { items.forEach { put(jsonMail(it)) } }

    private fun decodeMailList(items: JSONArray): List<MailSummary> =
        (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::decodeMail) }

    private fun encodeOverview(item: Overview) = JSONObject().apply {
        put("unread", item.unread)
        put("recent", jsonMessages(item.recent))
        put("events", jsonEvents(item.events))
        item.nextExam?.let { put("nextExam", jsonExam(it)) }
        put("mailUnread", item.mailUnread)
        put("recentMail", jsonMails(item.recentMail))
        put("at", item.at)
    }

    private fun decodeOverview(item: JSONObject) = Overview(
        unread = item.optInt("unread"),
        recent = decodeMessageList(item.optJSONArray("recent") ?: JSONArray()),
        events = decodeEventList(item.optJSONArray("events") ?: JSONArray()),
        nextExam = item.optJSONObject("nextExam")?.let(::decodeExam),
        mailUnread = item.optInt("mailUnread"),
        recentMail = decodeMailList(item.optJSONArray("recentMail") ?: JSONArray()),
        at = item.optLong("at"),
    )

    private fun encodeMessages(item: Messages) = JSONObject().apply {
        put("messages", jsonMessages(item.messages))
        put("unread", item.unread)
        put("at", item.at)
    }

    private fun decodeMessagesObj(item: JSONObject) = Messages(
        messages = decodeMessageList(item.optJSONArray("messages") ?: JSONArray()),
        unread = item.optInt("unread"),
        at = item.optLong("at"),
    )

    private fun encodeSchedule(item: Schedule) = JSONObject().apply {
        put("events", jsonEvents(item.events))
        put("exams", jsonExams(item.exams))
        put("examError", item.examError ?: "")
        put("at", item.at)
    }

    private fun decodeSchedule(item: JSONObject) = Schedule(
        events = decodeEventList(item.optJSONArray("events") ?: JSONArray()),
        exams = decodeExamList(item.optJSONArray("exams") ?: JSONArray()),
        examError = item.optString("examError").takeIf { it.isNotEmpty() },
        at = item.optLong("at"),
    )
}
