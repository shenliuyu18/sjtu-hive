package com.sjtuhive.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.CampusSession
import com.sjtuhive.app.data.ExamApi
import com.sjtuhive.app.data.ExamItem
import com.sjtuhive.app.data.SchoolCalendarApi
import com.sjtuhive.app.data.SchoolEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val weekdayLabels = listOf("一", "二", "三", "四", "五", "六", "日")
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val rangeFormatter = DateTimeFormatter.ofPattern("M月d日")

@Composable
fun ScheduleScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onLoginTeachingSite: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var weekStart by remember { mutableStateOf(today.with(DayOfWeek.MONDAY)) }
    var selectedDay by remember { mutableStateOf(today) }
    var events by remember { mutableStateOf<List<SchoolEvent>>(emptyList()) }
    var exams by remember { mutableStateOf<List<ExamItem>>(emptyList()) }
    var examError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(loggedIn, reload) {
        if (!loggedIn) return@LaunchedEffect
        loading = true
        error = null
        examError = null
        suspend fun loadEvents() = SchoolCalendarApi(context).events(
            today.minusDays(7).atStartOfDay(),
            today.plusDays(70).atStartOfDay(),
        )
        suspend fun loadExams() = ExamApi(context).upcoming()

        var eventsResult = runCatching { loadEvents() }
        if (eventsResult.isFailure) {
            // 日历会话过期时先静默续期再重试一次。
            CampusSession.refresh(context, "calendar.sjtu.edu.cn")
            eventsResult = runCatching { loadEvents() }
        }
        eventsResult.onSuccess { events = it }
            .onFailure { error = it.message ?: "读取失败" }

        var examResult = runCatching { loadExams() }
        if (examResult.isFailure) {
            CampusSession.refresh(context, "i.sjtu.edu.cn")
            examResult = runCatching { loadExams() }
        }
        examResult.onSuccess { exams = it }
            .onFailure { examError = it.message ?: "考试信息读取失败" }
        loading = false
    }

    if (!loggedIn) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("日程", style = MaterialTheme.typography.headlineMedium)
            Text(
                "登录 jAccount 后读取校历、课程与个人日程；会话只保存在本机。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLogin) { Text("登录 jAccount") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("日程", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        loading -> "正在读取…"
                        error != null -> error ?: ""
                        else -> "当天 ${events.count { it.covers(selectedDay) }} 项 · 共 ${events.size} 项日程"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        WeekHeader(
            weekStart = weekStart,
            selectedDay = selectedDay,
            events = events,
            onPrevious = {
                val target = weekStart.minusWeeks(1)
                weekStart = target
                selectedDay = target
            },
            onNext = {
                val target = weekStart.plusWeeks(1)
                weekStart = target
                selectedDay = target
            },
            onSelect = { selectedDay = it },
        )

        if (loading && events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val dayEvents = events.filter { it.covers(selectedDay) }.sortedBy { it.startAt }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    dayLabel(selectedDay, today),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (dayEvents.isEmpty()) {
                item {
                    Text(
                        if (error != null) "读取失败，点右上角刷新重试。" else "这一天没有日程。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (error != null) {
                        TextButton(onClick = onLogin) { Text("重新登录 jAccount") }
                    }
                }
            }
            items(dayEvents, key = { it.id }) { event -> EventCard(event) }
            val upcoming = events
                .filter { it.endAt.isAfter(selectedDay.plusDays(1).atStartOfDay()) }
                .sortedBy { it.startAt }
                .take(8)
            if (upcoming.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("接下来", style = MaterialTheme.typography.titleMedium)
                }
                items(upcoming, key = { "next-${it.id}" }) { event ->
                    NextEventRow(event)
                }
            }
            if (exams.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text("考试安排", style = MaterialTheme.typography.titleMedium)
                }
                items(exams, key = { "exam-${it.courseName}-${it.examDate}-${it.timeText}" }) { exam ->
                    ExamCard(exam)
                }
            } else if (examError != null) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "考试信息：$examError",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onLoginTeachingSite) { Text("重新登录教学网") }
                }
            }
        }
    }
}

@Composable
private fun WeekHeader(
    weekStart: LocalDate,
    selectedDay: LocalDate,
    events: List<SchoolEvent>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit,
) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "上一周")
            }
            Text(
                "${weekStart.format(rangeFormatter)} – ${weekStart.plusDays(6).format(rangeFormatter)}",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "下一周")
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (offset in 0L..6L) {
                val day = weekStart.plusDays(offset)
                val selected = day == selectedDay
                val today = day == LocalDate.now()
                val hasEvents = events.any { it.covers(day) }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .then(
                            if (today && !selected) {
                                Modifier.border(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    RoundedCornerShape(16.dp),
                                )
                            } else {
                                Modifier
                            },
                        )
                        .clickable { onSelect(day) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        weekdayLabels[day.dayOfWeek.value - 1],
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected || today) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !hasEvents -> Color.Transparent
                                    selected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    else -> MaterialTheme.colorScheme.primary
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: SchoolEvent) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(event.colorHex)) }
        .getOrDefault(MaterialTheme.colorScheme.primary)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(event.title, style = MaterialTheme.typography.titleSmall)
            Text(
                if (event.allDay) "全天" else
                    "${event.startAt.format(timeFormatter)} – ${event.endAt.format(timeFormatter)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val meta = listOf(event.location, event.calendarName)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NextEventRow(event: SchoolEvent) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            event.startAt.format(rangeFormatter),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            event.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (event.allDay) "全天" else event.startAt.format(timeFormatter),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExamCard(exam: ExamItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(width = 4.dp, height = 40.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.75f)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                exam.courseName + if (exam.examName.isNotBlank()) " · ${exam.examName}" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                listOf(exam.dateLabel, exam.timeText).filter { it.isNotBlank() }.joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val place = listOf(exam.campus, exam.location)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            val meta = listOf(
                place,
                if (exam.seat.isNotBlank()) "座位 ${exam.seat}" else "",
                exam.form,
            ).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String {
    val weekday = weekdayLabels[day.dayOfWeek.value - 1]
    return when (day) {
        today -> "今天 · ${day.monthValue}月${day.dayOfMonth}日 周$weekday"
        today.plusDays(1) -> "明天 · ${day.monthValue}月${day.dayOfMonth}日 周$weekday"
        else -> "${day.monthValue}月${day.dayOfMonth}日 周$weekday"
    }
}
