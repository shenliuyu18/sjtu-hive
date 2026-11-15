package com.sjtuhive.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.CanvasApi
import com.sjtuhive.app.data.MailApi
import com.sjtuhive.app.data.SchoolCalendarApi
import com.sjtuhive.app.data.TodoStore
import java.time.LocalDateTime

private data class SearchHit(
    val title: String,
    val subtitle: String,
    val group: String,
)

/**
 * 全局搜索：在本地缓存与待办里查课程、文件、邮件、日程与待办。
 * 数据来自各模块的磁盘缓存（冷启动即可搜），不额外发请求。
 */
@Composable
fun SearchScreen(
    context: android.content.Context,
    onClose: () -> Unit,
    onOpenCanvas: () -> Unit,
    onOpenEmail: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenTodo: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var scanned by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler { onClose() }

    fun runSearch() {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            hits = emptyList()
            scanned = false
            return
        }
        val result = mutableListOf<SearchHit>()
        val lower = keyword.lowercase()

        runCatching {
            TodoStore(context).list().filter {
                it.title.lowercase().contains(lower) || it.note.lowercase().contains(lower)
            }.forEach {
                result += SearchHit(it.title, if (it.done) "待办 · 已完成" else "待办 · 未完成", "待办")
            }
        }
        runCatching {
            val canvas = CanvasApi(context)
            canvas.coursesCached().filter { it.name.lowercase().contains(lower) || it.code.lowercase().contains(lower) }
                .forEach { result += SearchHit(it.name, "Canvas 课程 · ${it.term}", "Canvas") }
            canvas.coursesCached().take(6).forEach { course ->
                canvas.filesCached(course.id).filter { it.name.lowercase().contains(lower) }.take(8).forEach {
                    result += SearchHit(it.name, "Canvas 文件 · ${course.name}", "Canvas")
                }
            }
        }
        runCatching {
            val mail = MailApi(context)
            mail.inboxCached().filter {
                it.subject.lowercase().contains(lower) || it.from.lowercase().contains(lower)
            }.take(10).forEach {
                result += SearchHit(it.subject.ifBlank { "(无主题)" }, "邮件 · ${it.from}", "邮箱")
            }
        }
        runCatching {
            val calendar = SchoolCalendarApi(context)
            val now = LocalDateTime.now()
            calendar.eventsCached(now.minusDays(7), now.plusDays(60)).filter {
                it.title.lowercase().contains(lower) || it.location.lowercase().contains(lower)
            }.take(10).forEach {
                result += SearchHit(it.title, "日程 · ${it.startAt.toString().take(16).replace('T', ' ')}", "日程")
            }
        }
        hits = result
        scanned = true
    }

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
            Text("搜索", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) {
                    hits = emptyList()
                    scanned = false
                }
            },
            placeholder = { Text("课程、文件、邮件、日程、待办") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (hits.isEmpty()) {
            Text(
                if (scanned) "没有匹配的结果。" else "输入关键词后按搜索。搜索范围是本地已缓存的数据，无需联网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(hits) { hit ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(
                                hit.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = {
                                when (hit.group) {
                                    "待办" -> onOpenTodo()
                                    "Canvas" -> onOpenCanvas()
                                    "邮箱" -> onOpenEmail()
                                    else -> onOpenSchedule()
                                }
                            },
                        ) { Text("打开") }
                    }
                }
            }
        }
    }
}
