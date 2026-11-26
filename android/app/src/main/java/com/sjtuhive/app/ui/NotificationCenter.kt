package com.sjtuhive.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.MailApi
import com.sjtuhive.app.data.ShuiyuanApi
import com.sjtuhive.app.data.TodoStore
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HiveNotice(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val time: String,
    val route: String,
    val mailIndex: Int? = null,
    val topicId: Long? = null,
)

/** 通知中心：聚合邮件、待办、交我办、考试与水源，已读状态本地保存。 */
class NotificationCenter(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("hive_notification_center", Context.MODE_PRIVATE)

    fun readIds(): Set<String> = prefs.getStringSet("read", emptySet()).orEmpty().toSet()

    fun markRead(ids: Collection<String>) {
        if (ids.isEmpty()) return
        prefs.edit().putStringSet("read", (readIds() + ids).toSet()).apply()
    }

    suspend fun load(): List<HiveNotice> = withContext(Dispatchers.IO) {
        HiveUiCache.loadPersisted(appContext)
        val result = mutableListOf<HiveNotice>()
        val now = LocalDateTime.now()

        runCatching { MailApi(appContext).inboxCached("INBOX") }
            .getOrDefault(emptyList())
            .filterNot { it.seen }
            .take(20)
            .forEach { mail ->
                result += HiveNotice(
                    id = "mail:${mail.from}:${mail.date}:${mail.subject}",
                    kind = "mail",
                    title = "新邮件：${mail.subject}",
                    body = mail.from,
                    time = mail.date,
                    route = "mail",
                    mailIndex = mail.index,
                )
            }

        runCatching { TodoStore(appContext).list() }
            .getOrDefault(emptyList())
            .filterNot { it.done }
            .mapNotNull { item ->
                val due = item.dueDateTime() ?: return@mapNotNull null
                if (due.isAfter(now.plusDays(7))) return@mapNotNull null
                HiveNotice(
                    id = "todo:${item.id}:${item.dueAt}",
                    kind = "todo",
                    title = "待办：${item.title}",
                    body = "截止 ${item.dueAt.take(16)}",
                    time = item.dueAt.take(16),
                    route = "todo",
                )
            }
            .sortedBy { it.time }
            .take(20)
            .forEach(result::add)

        HiveUiCache.messages?.messages.orEmpty()
            .take(20)
            .forEach { message ->
                result += HiveNotice(
                    id = "portal:${message.id}",
                    kind = "portal",
                    title = message.title.ifBlank { "交我办消息" },
                    body = listOf(message.source, message.body).filter { it.isNotBlank() }
                        .joinToString(" · "),
                    time = message.createdAt,
                    route = "portal",
                )
            }

        runCatching { ShuiyuanApi(appContext).notifications() }
            .getOrDefault(emptyList())
            .take(20)
            .forEach { notification ->
                result += HiveNotice(
                    id = "sy:${notification.id}",
                    kind = "shuiyuan",
                    title = notification.title.ifBlank { "水源社区通知" },
                    body = notification.username?.let { "@$it" }.orEmpty(),
                    time = notification.createdAt,
                    route = "shuiyuan",
                    topicId = notification.topicId,
                )
            }

        HiveUiCache.schedule?.exams.orEmpty()
            .take(10)
            .forEach { exam ->
                result += HiveNotice(
                    id = "exam:${exam.courseName}:${exam.examDate}:${exam.text()}",
                    kind = "exam",
                    title = "考试：${exam.courseName}",
                    body = listOf(exam.dateLabel, exam.timeText, exam.location)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    time = exam.examDate,
                    route = "exam",
                )
            }

        result
    }

    private fun com.sjtuhive.app.data.ExamItem.text(): String =
        listOf(examName, examDate, timeText, location).joinToString("|")
}

private fun noticeLabel(kind: String): String = when (kind) {
    "mail" -> "邮件"
    "todo" -> "待办"
    "portal" -> "交我办"
    "exam" -> "考试"
    "shuiyuan" -> "水源"
    else -> "通知"
}

@Composable
fun NotificationCenterScreen(
    context: Context,
    onClose: () -> Unit,
    onOpen: (HiveNotice) -> Unit,
) {
    val center = remember { NotificationCenter(context) }
    var notices by remember { mutableStateOf<List<HiveNotice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var readIds by remember { mutableStateOf(center.readIds()) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching { center.load() }
            .onSuccess { notices = it }
            .onFailure { error = it.message ?: "通知读取失败" }
        readIds = center.readIds()
        loading = false
    }

    androidx.activity.compose.BackHandler { onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text("← 返回") }
            Text(
                "通知中心",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = notices.any { it.id !in readIds },
                onClick = {
                    center.markRead(notices.map { it.id })
                    readIds = center.readIds()
                },
            ) { Text("全部已读") }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        when {
            loading && notices.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && notices.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reload++ }) { Text("重试") }
                }
            }
            notices.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无通知", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(notices, key = { it.id }) { notice ->
                    val unread = notice.id !in readIds
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable {
                                center.markRead(listOf(notice.id))
                                readIds = center.readIds()
                                onOpen(notice)
                            }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (unread) "● " else "") + noticeLabel(notice.kind),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                notice.time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(notice.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        if (notice.body.isNotBlank()) {
                            Text(
                                notice.body,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}
