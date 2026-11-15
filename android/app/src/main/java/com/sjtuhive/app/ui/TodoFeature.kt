package com.sjtuhive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.CanvasApi
import com.sjtuhive.app.data.TodoItem
import com.sjtuhive.app.data.TodoStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 本地待办：本地条目 + Canvas 作业 DDL 聚合。
 * 对应桌面端 todo 模块的移动端形态（不做终端/月历，保留列表与完成状态）。
 */
@Composable
fun TodoScreen(context: android.content.Context, onClose: () -> Unit) {
    val store = remember { TodoStore(context) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(store.list()) }
    var title by remember { mutableStateOf("") }
    var dueAt by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        items = store.list()
    }

    androidx.activity.compose.BackHandler { onClose() }

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

        val pending = items.filterNot { it.done }
        val done = items.filter { it.done }
        if (items.isEmpty()) {
            Text(
                "还没有待办。可以手动添加，或用「同步 Canvas DDL」把作业截止日期聚合进来。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(pending, key = { it.id }) { item ->
                TodoRow(item, onToggle = { store.toggle(item.id); refresh() }, onDelete = { store.remove(item.id); refresh() })
            }
            if (done.isNotEmpty()) {
                item {
                    Text(
                        "已完成",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(done, key = { it.id }) { item ->
                    TodoRow(item, onToggle = { store.toggle(item.id); refresh() }, onDelete = { store.remove(item.id); refresh() })
                }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem, onToggle: () -> Unit, onDelete: () -> Unit) {
    val overdue = !item.done && item.dueAt.isNotBlank() && runCatching {
        LocalDate.parse(item.dueAt.take(10)).isBefore(LocalDate.now())
    }.getOrDefault(false)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = item.done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
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
                if (item.dueAt.isNotBlank()) add("截止 ${item.dueAt.take(10)}")
                if (item.source == "canvas") add("Canvas 作业")
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
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
        }
    }
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
