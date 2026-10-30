package com.sjtuhive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

data class ExamItem(
    val courseName: String,
    val examName: String,
    val examDate: String,
    val timeText: String,
    val startAt: LocalDateTime?,
    val campus: String,
    val location: String,
    val seat: String,
    val form: String,
    val teacher: String,
) {
    val dateLabel: String
        get() = startAt?.let { "${it.monthValue}月${it.dayOfMonth}日" } ?: examDate
}

/**
 * 教学信息服务网「学生考试查询」。
 * 与课表不同，必须 POST 到 Index.html?doType=query，参数放 body，并带页面里的隐藏 token。
 */
class ExamApi(private val context: Context) {

    suspend fun upcoming(): List<ExamItem> = withContext(Dispatchers.IO) {
        try {
            queryExams()
        } catch (error: Exception) {
            val message = error.message.orEmpty()
            if (!message.contains("901") && error !is CampusAuthException) throw error
            // 教学网旧 JSESSIONID 失效时不会自动跳 SSO，清掉后重新走一次。
            HiveSession.removeHost(context, "i.sjtu.edu.cn")
            queryExams()
        }
    }

    private suspend fun queryExams(): List<ExamItem> {
        val page = loadPage()
        val tokens = parseTokens(page)
        if (tokens["csrftoken"].isNullOrEmpty()) {
            throw CampusAuthException("需要登录教学信息服务网后才能查询考试")
        }
        val semester = currentSemester(LocalDate.now())
        val (xnm, xqm) = semesterParams(semester)
        val fields = linkedMapOf(
            "xnm" to xnm,
            "xqm" to xqm,
            "ksmcdmb_id" to "",
            "kch" to "",
            "kc" to "",
            "ksrq" to "",
            "kkbm_id" to "",
            "csrftoken" to tokens["csrftoken"].orEmpty(),
            "pkey" to tokens["pkey"].orEmpty(),
            "sessionUserKey" to tokens["sessionUserKey"].orEmpty(),
            "gnmkdmKey" to tokens["gnmkdmKey"].orEmpty(),
            "localeKey" to tokens["localeKey"].orEmpty(),
            "queryModel.currentPage" to "1",
            "queryModel.showCount" to "100",
            "_search" to "false",
            "nd" to System.currentTimeMillis().toString(),
            "time" to "1",
        )
        val body = HiveHttp.postForm(context, QUERY_URL, PAGE_URL, ORIGIN, fields)
        val payload = runCatching { JSONObject(body) }.getOrElse {
            throw CampusAuthException("考试接口没有返回预期数据，请重新登录后再试")
        }
        val items = payload.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { index ->
            val raw = items.optJSONObject(index) ?: return@mapNotNull null
            val course = raw.text("kcmc")
            if (course.isEmpty()) return@mapNotNull null
            val (date, timeText, startAt) = parseExamTime(raw.text("kssj"))
            ExamItem(
                courseName = course,
                examName = raw.text("ksmc"),
                examDate = date,
                timeText = timeText,
                startAt = startAt,
                campus = raw.text("cdxqmc"),
                location = raw.text("cdmc").ifBlank { raw.text("cdbh") },
                seat = raw.text("zwh"),
                form = raw.text("ksfs").ifBlank { raw.text("khfs") },
                teacher = raw.text("jsxx"),
            )
        }.sortedBy { it.startAt ?: LocalDateTime.MAX }
    }

    private suspend fun loadPage(): String {
        val first = HiveHttp.get(context, PAGE_URL, STUDENT_URL, acceptJson = false)
        if (parseTokens(first).isNotEmpty()) return first
        // 第一次访问被重定向到登录页：走一次教学网首页的 SSO，再回考试页。
        runCatching { HiveHttp.get(context, STUDENT_URL, ORIGIN, acceptJson = false) }
        return HiveHttp.get(context, PAGE_URL, STUDENT_URL, acceptJson = false)
    }

    private fun parseTokens(page: String): Map<String, String> {
        val tokens = mutableMapOf<String, String>()
        HIDDEN_NAME.findAll(page).forEach { match ->
            val name = match.groupValues[1].trim()
            if (name in TOKEN_NAMES && !tokens.containsKey(name)) {
                tokens[name] = unescape(match.groupValues[2].trim())
            }
        }
        HIDDEN_ID.findAll(page).forEach { match ->
            val name = match.groupValues[1].trim()
            if (!tokens.containsKey(name)) tokens[name] = unescape(match.groupValues[2].trim())
        }
        return tokens
    }

    private fun parseExamTime(value: String): Triple<String, String, LocalDateTime?> {
        val trimmed = value.trim()
        TIME_PATTERN.find(trimmed)?.let { match ->
            val groups = match.groupValues
            val start = runCatching {
                LocalDate.parse(groups[1]).atTime(groups[2].toInt(), groups[3].toInt())
            }.getOrNull()
            val end = runCatching {
                LocalDate.parse(groups[1]).atTime(groups[4].toInt(), groups[5].toInt())
            }.getOrNull()
            val timeText = if (start != null && end != null) {
                "%02d:%02d-%02d:%02d".format(groups[2].toInt(), groups[3].toInt(), groups[4].toInt(), groups[5].toInt())
            } else {
                trimmed
            }
            return Triple(groups[1], timeText, start)
        }
        DATE_ONLY.find(trimmed)?.let { match ->
            val groups = match.groupValues
            val date = "%s-%02d-%02d".format(groups[1], groups[2].toInt(), groups[3].toInt())
            return Triple(date, trimmed, LocalDate.parse(date).atStartOfDay())
        }
        return Triple("", trimmed, null)
    }

    private fun unescape(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun currentSemester(today: LocalDate): String {
        val year = today.year
        return when {
            today.monthValue >= 9 -> "$year-${year + 1}-1"
            today.monthValue <= 2 -> "${year - 1}-$year-1"
            today.monthValue <= 7 -> "${year - 1}-$year-2"
            else -> "${year - 1}-$year-3"
        }
    }

    private fun semesterParams(semester: String): Pair<String, String> {
        val parts = semester.split("-")
        val term = when (parts.getOrNull(2)) {
            "2" -> "12"
            "3" -> "16"
            else -> "3"
        }
        return parts.first() to term
    }

    companion object {
        private const val ORIGIN = "https://i.sjtu.edu.cn"
        private const val STUDENT_URL = "$ORIGIN/student/index.html"
        private const val PAGE_URL = "$ORIGIN/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default"
        private const val QUERY_URL = "$ORIGIN/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"

        private val TOKEN_NAMES = setOf("csrftoken", "pkey", "localeKey", "gnmkdmKey")
        private val HIDDEN_NAME = Regex("""(?is)<input[^>]+name="([^"]+)"[^>]*value="([^"]*)"""")
        private val HIDDEN_ID = Regex(
            """(?is)<input[^>]+id="(sessionUserKey|gnmkdmKey|pkey|localeKey)"[^>]*value="([^"]*)"""",
        )
        private val TIME_PATTERN = Regex(
            """(\d{4}-\d{2}-\d{2})\s*[（(](\d{1,2}):(\d{2})\s*[-–~至]\s*(\d{1,2}):(\d{2})[）)]""",
        )
        private val DATE_ONLY = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})""")
    }
}
