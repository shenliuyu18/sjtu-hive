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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.sjtuhive.app.data.LocalScheduleEvent
import com.sjtuhive.app.data.LocalScheduleStore
import com.sjtuhive.app.data.SchoolCalendarApi
import com.sjtuhive.app.data.SchoolEvent
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
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
    onOpenExam: () -> Unit = {},
) {
    val today = remember { LocalDate.now() }
    val cached = HiveUiCache.schedule
    var weekStart by remember { mutableStateOf(today.with(DayOfWeek.MONDAY)) }
    var selectedDay by remember { mutableStateOf(today) }
    var events by remember { mutableStateOf(cached?.events ?: emptyList()) }
    var exams by remember { mutableStateOf(cached?.exams ?: emptyList()) }
    var examError by remember { mutableStateOf(cached?.examError) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val localStore = remember { LocalScheduleStore(context) }
    var localEvents by remember { mutableStateOf(localStore.list()) }
    var monthMode by remember { mutableStateOf(false) }
    var monthCursor by remember { mutableStateOf(YearMonth.from(today)) }
    var showEditor by remember { mutableStateOf(false) }
    var editingEvent by remember { mutableStateOf<LocalScheduleEvent?>(null) }
    var editorDay by remember { mutableStateOf(today) }

    LaunchedEffect(reload) {
        localEvents = localStore.list()
    }

    val allEvents = events + localEvents.map { it.asSchoolEvent() }

    LaunchedEffect(loggedIn, reload) {
        if (!loggedIn) return@LaunchedEffect
        HiveUiCache.loadPersisted(context)
        val cache = HiveUiCache.schedule
        if (reload == 0 && cache != null && HiveUiCache.fresh(cache.at)) {
            events = cache.events
            exams = cache.exams
            examError = cache.examError
            return@LaunchedEffect
        }
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
        if (error == null) {
            HiveUiCache.schedule = HiveUiCache.Schedule(
                events = events,
                exams = exams,
                examError = examError,
                at = System.currentTimeMillis(),
            )
            HiveUiCache.persist(context)
        }
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
                        else -> "当天 ${allEvents.count { it.covers(selectedDay) }} 项 · 共 ${allEvents.size} 项日程"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenExam) { Text("考试") }
            TextButton(onClick = { monthMode = !monthMode }) { Text(if (monthMode) "周" else "月") }
            TextButton(onClick = {
                editingEvent = null
                editorDay = selectedDay
                showEditor = true
            }) { Text("＋") }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        if (monthMode) {
            MonthCalendar(
                month = monthCursor,
                selectedDate = selectedDay,
                markedDays = { day -> allEvents.count { it.covers(day) } },
                onMonthChange = { monthCursor = it },
                onSelect = {
                    selectedDay = it
                    weekStart = it.with(DayOfWeek.MONDAY)
                    monthCursor = YearMonth.from(it)
                },
            )
        } else {
            WeekHeader(
                weekStart = weekStart,
                selectedDay = selectedDay,
                events = allEvents,
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
        }

        if (loading && allEvents.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val dayEvents = allEvents.filter { it.covers(selectedDay) }.sortedBy { it.startAt }
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
            items(dayEvents, key = { it.id }) { event ->
                EventCard(event, onClick = {
                    localEvents.firstOrNull { "local-${it.id}" == event.id }?.let {
                        editingEvent = it
                        editorDay = it.startAt.toLocalDate()
                        showEditor = true
                    }
                })
            }
            val upcoming = allEvents
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

    if (showEditor) {
        LocalEventDialog(
            initial = editingEvent,
            defaultDay = editorDay,
            onDismiss = { showEditor = false },
            onSave = { title, startAt, endAt, location, color ->
                val editing = editingEvent
                if (editing == null) {
                    localStore.add(title, startAt, endAt, location, color)
                } else {
                    localStore.update(editing.id, title, startAt, endAt, location, color)
                }
                localEvents = localStore.list()
                showEditor = false
            },
            onDelete = editingEvent?.takeIf { it.todoId == null }?.let { event ->
                {
                    localStore.remove(event.id)
                    localEvents = localStore.list()
                    showEditor = false
                }
            },
        )
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

private fun LocalScheduleEvent.asSchoolEvent() = SchoolEvent(
    id = "local-$id",
    title = title,
    startAt = startAt,
    endAt = endAt,
    location = location,
    calendarName = if (todoId != null) "来自待办" else "本地日程",
    colorHex = color,
    allDay = false,
)

@Composable
private fun LocalEventDialog(
    initial: LocalScheduleEvent?,
    defaultDay: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, LocalDateTime, LocalDateTime, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var date by remember(initial, defaultDay) {
        mutableStateOf((initial?.startAt?.toLocalDate() ?: defaultDay).toString())
    }
    var startText by remember(initial) {
        mutableStateOf(initial?.startAt?.format(timeFormatter) ?: "09:00")
    }
    var endText by remember(initial) {
        mutableStateOf(initial?.endAt?.format(timeFormatter) ?: "10:00")
    }
    var location by remember(initial) { mutableStateOf(initial?.location.orEmpty()) }
    var color by remember(initial) {
        mutableStateOf(initial?.color ?: LocalScheduleStore.DEFAULT_COLOR)
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建日程" else "编辑日程") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date,
                    onValueChange = { date = it },
                    label = { Text("日期") },
                    placeholder = { Text("2026-11-20") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("开始") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text("结束") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("地点") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("颜色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocalScheduleStore.COLORS.forEach { value ->
                        val selected = value == color
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    runCatching {
                                        Color(android.graphics.Color.parseColor(value))
                                    }.getOrDefault(MaterialTheme.colorScheme.primary),
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            2.dp,
                                            MaterialTheme.colorScheme.onSurface,
                                            CircleShape,
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { color = value },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (initial?.todoId != null) {
                    Text(
                        "该日程来自待办，请到「待办」中修改或删除。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = title.trim()
                if (trimmed.isEmpty()) {
                    error = "标题不能为空"
                    return@TextButton
                }
                val start = runCatching {
                    LocalDateTime.parse("${date.trim()}T${startText.trim()}")
                }.getOrNull()
                val end = runCatching {
                    LocalDateTime.parse("${date.trim()}T${endText.trim()}")
                }.getOrNull()
                if (start == null || end == null) {
                    error = "日期与时间格式应为 YYYY-MM-DD / HH:mm"
                    return@TextButton
                }
                if (!end.isAfter(start)) {
                    error = "结束时间必须晚于开始时间"
                    return@TextButton
                }
                onSave(trimmed, start, end, location, color)
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun EventCard(event: SchoolEvent, onClick: (() -> Unit)? = null) {
    val accent = runCatching { Color(android.graphics.Color.parseColor(event.colorHex)) }
        .getOrDefault(MaterialTheme.colorScheme.primary)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
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
