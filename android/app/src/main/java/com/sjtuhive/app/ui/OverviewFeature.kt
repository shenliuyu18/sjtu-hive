package com.sjtuhive.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import com.sjtuhive.app.data.MailApi
import com.sjtuhive.app.data.MailSummary
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
    onOpenMail: (Int) -> Unit,
) {
    val today = remember { LocalDate.now() }
    val cached = HiveUiCache.overview
    val mailApi = remember { MailApi(context) }
    val mailConfigured = remember { mailApi.account() != null }
    var loading by remember { mutableStateOf(false) }
    var unread by remember { mutableStateOf(cached?.unread ?: 0) }
    var recent by remember { mutableStateOf(cached?.recent ?: emptyList()) }
    var todayEvents by remember { mutableStateOf(cached?.events ?: emptyList()) }
    var nextExam by remember { mutableStateOf(cached?.nextExam) }
    var mailUnread by remember { mutableStateOf(cached?.mailUnread ?: 0) }
    var recentMail by remember { mutableStateOf(cached?.recentMail ?: emptyList()) }
    var mailLoading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(loggedIn, reload) {
        if (!loggedIn) return@LaunchedEffect
        val cache = HiveUiCache.overview
        if (reload == 0 && cache != null && HiveUiCache.fresh(cache.at)) {
            unread = cache.unread
            recent = cache.recent
            todayEvents = cache.events
            nextExam = cache.nextExam
            mailUnread = cache.mailUnread
            recentMail = cache.recentMail
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
            mailUnread = mailUnread,
            recentMail = recentMail,
            at = System.currentTimeMillis(),
        )
        loading = false
    }

    LaunchedEffect(mailConfigured, reload) {
        if (!mailConfigured) return@LaunchedEffect
        val cache = HiveUiCache.overview
        if (reload == 0 && cache != null && HiveUiCache.fresh(cache.at)) {
            mailUnread = cache.mailUnread
            recentMail = cache.recentMail
            return@LaunchedEffect
        }
        mailLoading = true
        runCatching { mailApi.inbox("INBOX", 8) }
            .onSuccess { list ->
                val unseen = list.filter { !it.seen }
                mailUnread = unseen.size
                recentMail = unseen.take(2)
                HiveUiCache.overview = HiveUiCache.overview?.copy(
                    mailUnread = mailUnread,
                    recentMail = recentMail,
                )
            }
        mailLoading = false
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

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricPill("今日课程", todayEvents.size.toString(), Modifier.weight(1f))
            MetricPill("未读邮件", if (mailLoading) "…" else mailUnread.toString(), Modifier.weight(1f))
            MetricPill("交我办", unread.toString(), Modifier.weight(1f))
        }

        if (!loggedIn) {
            OverviewCard("jAccount") {
                Text("登录后同步日程、考试与交我办消息。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onLogin) { Text("登录 jAccount") }
            }
            OverviewCard("更多功能") {
                FeatureGrid(
                    onOpenShuiyuan = onOpenShuiyuan,
                    onOpenCanvas = onOpenCanvas,
                    onOpenEmail = onOpenEmail,
                    onOpenClassroom = onOpenClassroom,
                )
            }
            return@Column
        }

        OverviewCard("接下来") {
            if (todayEvents.isEmpty() && nextExam == null) {
                Text(
                    "今天没有安排。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
            nextExam?.let { exam ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "考试",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.width(44.dp),
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            exam.courseName + if (exam.examName.isNotBlank()) " · ${exam.examName}" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(exam.dateLabel, exam.timeText, exam.location)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = { onNavigate(1) }) { Text("打开日程") }
        }

        OverviewCard("消息") {
            Text(
                "交我办 $unread · 邮件 $mailUnread",
                style = MaterialTheme.typography.titleSmall,
            )
            if (recent.isEmpty() && recentMail.isEmpty()) {
                Text(
                    "暂无未读消息。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            recent.take(2).forEach { message ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onNavigate(2) }
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        message.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(message.source, message.createdAt)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            recentMail.take(2).forEach { mail ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenMail(mail.index) }
                        .padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "邮件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                        Text(
                            mail.date,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        mail.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        mail.from,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = { onNavigate(2) }) { Text("查看全部") }
        }

        OverviewCard("更多功能") {
            FeatureGrid(
                onOpenShuiyuan = onOpenShuiyuan,
                onOpenCanvas = onOpenCanvas,
                onOpenEmail = onOpenEmail,
                onOpenClassroom = onOpenClassroom,
            )
        }
    }
}

@Composable
private fun MetricPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureGrid(
    onOpenShuiyuan: () -> Unit,
    onOpenCanvas: () -> Unit,
    onOpenEmail: () -> Unit,
    onOpenClassroom: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureTile(
                icon = Icons.Outlined.Forum,
                title = "水源社区",
                subtitle = "校园讨论",
                onClick = onOpenShuiyuan,
                modifier = Modifier.weight(1f),
            )
            FeatureTile(
                icon = Icons.Outlined.Description,
                title = "Canvas 资料",
                subtitle = "课件与作业",
                onClick = onOpenCanvas,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeatureTile(
                icon = Icons.Outlined.Email,
                title = "邮箱",
                subtitle = "收信与发信",
                onClick = onOpenEmail,
                modifier = Modifier.weight(1f),
            )
            FeatureTile(
                icon = Icons.Outlined.PlayCircleOutline,
                title = "课堂视频",
                subtitle = "录像与文稿",
                onClick = onOpenClassroom,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun FeatureTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
