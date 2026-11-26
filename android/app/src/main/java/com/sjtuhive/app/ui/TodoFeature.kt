package com.sjtuhive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.CanvasApi
import com.sjtuhive.app.data.LocalScheduleStore
import com.sjtuhive.app.data.TodoItem
import com.sjtuhive.app.data.TodoStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地待办：本地条目 + Canvas 作业 DDL 聚合。
 * 对应桌面端 todo 模块：筛选、编辑、月历、写回本地日程与到期提醒。
 */
@Composable
fun TodoScreen(context: android.content.Context, onClose: () -> Unit) {
    val store = remember { TodoStore(context) }
    val localStore = remember { LocalScheduleStore(context) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(store.list()) }
    var title by remember { mutableStateOf("") }
    var dueAt by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }
    var monthMode by remember { mutableStateOf(false) }
    var monthCursor by remember { mutableStateOf(YearMonth.now()) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var editing by remember { mutableStateOf<TodoItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    val today = LocalDate.now()

    fun refresh() {
        items = store.list()
    }

    fun removeItem(item: TodoItem) {
        item.calendarEventId?.let(localStore::remove)
        store.remove(item.id)
        refresh()
    }

    fun toggleCalendar(item: TodoItem) {
        val eventId = item.calendarEventId
        if (eventId != null) {
            localStore.remove(eventId)
            store.setCalendarEvent(item.id, null)
        } else {
            val due = item.dueDateTime()
            if (due == null) {
                message = "请先填写截止日期"
                return
            }
            val event = localStore.add(
                title = "${item.title}（待办）",
                startAt = due.minusMinutes(30),
                endAt = due,
                location = "",
                color = LocalScheduleStore.DEFAULT_COLOR,
                todoId = item.id,
            )
            store.setCalendarEvent(item.id, event.id)
        }
        refresh()
    }

    androidx.activity.compose.BackHandler { onClose() }

    val pending = items.filterNot { it.done }
    val overdue = pending.count { item ->
        item.dueDateTime()?.isBefore(LocalDateTime.now()) == true
    }
    val todayCount = pending.count { it.dueDateTime()?.toLocalDate() == today }
    val doneCount = items.count { it.done }
    val weekEnd = today.plusDays(7)
    val filtered = when (filter) {
        "today" -> items.filter { it.dueDateTime()?.toLocalDate() == today }
        "week" -> items.filter {
            val day = it.dueDateTime()?.toLocalDate() ?: return@filter false
            !day.isBefore(today) && !day.isAfter(weekEnd)
        }
        "canvas" -> items.filter { it.source == "canvas" }
        "done" -> items.filter { it.done }
        "day" -> selectedDay?.let { day ->
            items.filter { it.dueDateTime()?.toLocalDate() == day }
        } ?: items
        else -> items
    }
    val ordered = filtered.sortedWith(
        compareBy<TodoItem> { it.done }.thenBy { it.dueDateTime() ?: LocalDateTime.MAX },
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("待办", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { creating = true }) { Text("新建") }
            TextButton(onClick = { monthMode = !monthMode }) {
                Text(if (monthMode) "列表" else "月历")
            }
            if (syncing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = {
                    scope.launch {
                        syncing = true
                        message = runCatching { syncCanvasDeadlines(context, store) }
                            .getOrElse { "同步失败：${it.message}" }
                        refresh()
                        syncing = false
                    }
                }) { Text("同步 Canvas DDL") }
            }
        }

        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        Text(
            "今天 $todayCount · 逾期 $overdue · 已完成 $doneCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("新待办") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = dueAt,
                onValueChange = { dueAt = it },
                label = { Text("截止") },
                placeholder = { Text("2026-11-20") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(0.6f),
            )
            FilledTonalButton(
                enabled = title.isNotBlank(),
                onClick = {
                    store.add(title, dueAt)
                    title = ""
                    dueAt = ""
                    refresh()
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加", modifier = Modifier.size(18.dp))
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val filters = listOf(
                "all" to "全部",
                "today" to "今天",
                "week" to "本周",
                "canvas" to "Canvas",
                "done" to "已完成",
            )
            filters.forEach { (key, label) ->
                FilterChip(
                    selected = filter == key,
                    onClick = { filter = key },
                    label = { Text(label) },
                )
            }
            if (selectedDay != null && filter == "day") {
                FilterChip(
                    selected = true,
                    onClick = { filter = "all"; selectedDay = null },
                    label = {
                        Text("${selectedDay?.monthValue}月${selectedDay?.dayOfMonth}日")
                    },
                )
            }
        }

        if (monthMode) {
            MonthCalendar(
                month = monthCursor,
                selectedDate = selectedDay,
                markedDays = { day ->
                    items.count {
                        !it.done && it.dueDateTime()?.toLocalDate() == day
                    }
                },
                onMonthChange = { monthCursor = it },
                onSelect = { day ->
                    selectedDay = day
                    filter = "day"
                    monthCursor = YearMonth.from(day)
                },
            )
        }

        if (ordered.isEmpty()) {
            Text(
                "没有符合条件的待办。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ordered, key = { it.id }) { item ->
                TodoRow(
                    item = item,
                    onToggle = { store.toggle(item.id); refresh() },
                    onEdit = { editing = item },
                    onDelete = { removeItem(item) },
                    onToggleCalendar = { toggleCalendar(item) },
                )
            }
        }
    }

    val dialogItem = when {
        creating -> null
        else -> editing
    }
    if (creating || editing != null) {
        TodoEditDialog(
            initial = dialogItem,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { newTitle, newDue, newNote ->
                val current = editing
                if (current == null) {
                    store.add(newTitle, newDue, newNote)
                } else {
                    store.update(current.id, newTitle, newDue, newNote)
                }
                creating = false
                editing = null
                refresh()
            },
            onDelete = editing?.let { item ->
                {
                    removeItem(item)
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleCalendar: () -> Unit,
) {
    val due = item.dueDateTime()
    val overdue = !item.done && due != null && due.isBefore(LocalDateTime.now())
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.done, onCheckedChange = { onToggle() })
            Column(
                Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                val meta = buildList {
                    if (item.dueAt.isNotBlank()) add("截止 ${item.dueAt.take(16)}")
                    if (item.source == "canvas") add("Canvas 作业")
                    if (item.calendarEventId != null) add("已写日历")
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onEdit) { Text("编辑") }
            TextButton(
                onClick = onToggleCalendar,
                enabled = due != null,
            ) { Text(if (item.calendarEventId != null) "取消日历" else "写日历") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
            }
        }
        if (item.note.isNotBlank()) {
            Text(
                item.note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp, end = 12.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun TodoEditDialog(
    initial: TodoItem?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var due by remember(initial) { mutableStateOf(initial?.dueAt.orEmpty()) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新建待办" else "编辑待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = due,
                    onValueChange = { due = it },
                    label = { Text("截止") },
                    placeholder = { Text("2026-11-20 或 2026-11-20T18:00") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) {
                    error = "标题不能为空"
                    return@TextButton
                }
                onSave(title.trim(), due.trim(), note.trim())
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

/** 拉取 Canvas 各课程作业的截止日期，合并进本地待办（按作业 id 去重）。 */
private suspend fun syncCanvasDeadlines(context: android.content.Context, store: TodoStore): String =
    withContext(Dispatchers.IO) {
        val api = CanvasApi(context)
        if (!api.usable()) return@withContext "未配置 Canvas Token，无法同步"
        val courses = runCatching { api.courses() }.getOrElse { emptyList() }
        if (courses.isEmpty()) return@withContext "没有读到课程"
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        val ddl = mutableListOf<TodoItem>()
        courses.forEach { course ->
            val assignments = runCatching { api.assignments(course.id) }.getOrElse { emptyList() }
            assignments.filter { it.dueAt.isNotBlank() }.forEach { assignment ->
                val due = runCatching {
                    LocalDateTime.parse(assignment.dueAt.substringBefore('Z')).format(formatter)
                }.getOrDefault(assignment.dueAt)
                ddl += TodoItem(
                    id = assignment.id.toString(),
                    title = "${course.name} · ${assignment.name}",
                    dueAt = due.take(10),
                    note = "",
                    done = false,
                    source = "canvas",
                )
            }
        }
        val added = store.mergeCanvas(ddl)
        if (added == 0) "没有新的 Canvas 截止日期" else "已加入 $added 条 Canvas 截止日期"
    }
