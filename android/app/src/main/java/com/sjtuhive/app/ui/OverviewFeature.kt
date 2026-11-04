package com.sjtuhive.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.ExamApi
import com.sjtuhive.app.data.ExamItem
import com.sjtuhive.app.data.HiveApi
import com.sjtuhive.app.data.PortalMessage
import com.sjtuhive.app.data.SchoolCalendarApi
import com.sjtuhive.app.data.SchoolEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val overviewTime = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun OverviewScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onNavigate: (Int) -> Unit,
    onOpenShuiyuan: () -> Unit,
    onOpenCanvas: () -> Unit,
    onOpenEmail: () -> Unit,
    onOpenClassroom: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val cached = HiveUiCache.overview
    var loading by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(cached?.unread ?: 0) }
    var recent by remember { mutableStateOf(cached?.recent ?: emptyList()) }
    var todayEvents by remember { mutableStateOf(cached?.events ?: emptyList()) }
    var nextExam by remember { mutableStateOf(cached?.nextExam) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(loggedIn, reload) {
        if (!loggedIn) return@LaunchedEffect
        val cache = HiveUiCache.overview
        if (reload == 0 && cache != null && HiveUiCache.fresh(cache.at)) {
            unread = cache.unread
            recent = cache.recent
            todayEvents = cache.events
            nextExam = cache.nextExam
            return@LaunchedEffect
        }
        loading = true
        runCatching {
            val api = HiveApi(context)
            val groups = api.groups()
            val unreadGroups = groups.filter { it.unread > 0 }.sortedByDescending { it.unread }
            unread = groups.sumOf { it.unread }
            val target = unreadGroups.firstOrNull() ?: groups.firstOrNull()
            recent = target?.let { api.messages(it, 3) } ?: emptyList()
        }
        runCatching {
            SchoolCalendarApi(context).events(
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay(),
            )
        }.onSuccess { todayEvents = it }
        runCatching { ExamApi(context).upcoming() }
            .onSuccess { exams ->
                nextExam = exams.firstOrNull { exam ->
                    exam.startAt == null || exam.startAt.isAfter(LocalDateTime.now())
                }
            }
        HiveUiCache.overview = HiveUiCache.Overview(
            unread = unread,
            recent = recent,
            events = todayEvents,
            nextExam = nextExam,
            at = System.currentTimeMillis(),
        )
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("概览", style = MaterialTheme.typography.headlineMedium)
        Text(
            "${today.monthValue}月${today.dayOfMonth}日 · ${weekdayName(today)}" +
                if (loading) " · 正在刷新…" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!loggedIn) {
            OverviewCard("jAccount") {
                Text("登录后同步日程、考试与交我办消息。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onLogin) { Text("登录 jAccount") }
            }
            OverviewCard("更多功能") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onOpenShuiyuan) { Text("水源社区") }
                        TextButton(onClick = onOpenCanvas) { Text("Canvas 资料") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = onOpenEmail) { Text("邮箱") }
                        TextButton(onClick = onOpenClassroom) { Text("课堂视频") }
                    }
                }
            }
            return@Column
        }

        OverviewCard("今日日程") {
            if (todayEvents.isEmpty()) {
                Text(
                    "今天没有日程。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                todayEvents.take(3).forEach { event ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            if (event.allDay) "全天" else event.startAt.format(overviewTime),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(44.dp),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                event.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (event.location.isNotBlank()) {
                                Text(
                                    event.location,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { onNavigate(1) }) { Text("打开日程") }
        }

        OverviewCard("交我办消息") {
            Text(
                if (unread > 0) "$unread 条未读" else "暂无未读消息",
                style = MaterialTheme.typography.titleSmall,
            )
            recent.take(2).forEach { message ->
                Text(
                    message.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onNavigate(2) }) { Text("查看消息") }
        }

        nextExam?.let { exam ->
            OverviewCard("下一场考试") {
                Text(
                    exam.courseName + if (exam.examName.isNotBlank()) " · ${exam.examName}" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    listOf(exam.dateLabel, exam.timeText, exam.location)
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onNavigate(1) }) { Text("查看考试安排") }
            }
        }

        OverviewCard("更多功能") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onOpenShuiyuan) { Text("水源社区") }
                    TextButton(onClick = onOpenCanvas) { Text("Canvas 资料") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = onOpenEmail) { Text("邮箱") }
                    TextButton(onClick = onOpenClassroom) { Text("课堂视频") }
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

private fun weekdayName(day: LocalDate): String =
    "周" + listOf("一", "二", "三", "四", "五", "六", "日")[day.dayOfWeek.value - 1]
