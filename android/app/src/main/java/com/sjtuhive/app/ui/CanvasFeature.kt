package com.sjtuhive.app.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.TextUtils
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sjtuhive.app.data.CanvasAnnouncement
import com.sjtuhive.app.data.CanvasApi
import com.sjtuhive.app.data.CanvasAssignment
import com.sjtuhive.app.data.CanvasCourse
import com.sjtuhive.app.data.CanvasDoc
import com.sjtuhive.app.data.CanvasFile
import com.sjtuhive.app.data.CanvasModule
import kotlinx.coroutines.launch

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

private fun formatDue(value: String): String = value.replace("T", " ").take(16)

private fun moduleTypeLabel(type: String): String = when (type) {
    "Page" -> "页面"
    "File" -> "文件"
    "Assignment" -> "作业"
    "Discussion" -> "讨论"
    "Quiz" -> "测验"
    "SubHeader" -> "小节"
    "ExternalUrl" -> "链接"
    "ExternalTool" -> "工具"
    else -> type
}

private fun formatPoints(points: Double): String =
    if (points % 1.0 == 0.0) "%.0f".format(points) else "%.1f".format(points)

private fun openLink(context: Context, url: String) {
    if (url.isBlank()) {
        toast(context, "没有可打开的链接")
        return
    }
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { toast(context, "没有可打开该链接的应用") }
}

@Composable
fun CanvasScreen(context: Context, onClose: () -> Unit) {
    val api = remember { CanvasApi(context) }
    var configured by remember { mutableStateOf(api.token().isNotEmpty()) }
    var tokenInput by remember { mutableStateOf("") }
    var courses by remember { mutableStateOf<List<CanvasCourse>>(emptyList()) }
    var course by remember { mutableStateOf<CanvasCourse?>(null) }
    var files by remember { mutableStateOf<List<CanvasFile>>(emptyList()) }
    var assignments by remember { mutableStateOf<List<CanvasAssignment>>(emptyList()) }
    var announcements by remember { mutableStateOf<List<CanvasAnnouncement>>(emptyList()) }
    var modules by remember { mutableStateOf<List<CanvasModule>>(emptyList()) }
    var section by remember { mutableStateOf("files") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var selectedFile by remember { mutableStateOf<CanvasFile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<CanvasDoc?>(null) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(configured, course?.id, section, reload) {
        if (!configured) return@LaunchedEffect
        loading = true
        error = null
        val current = course
        runCatching {
            if (current == null) {
                courses = api.courses()
                files = emptyList()
                assignments = emptyList()
                announcements = emptyList()
                modules = emptyList()
            } else {
                when (section) {
                    "modules" -> modules = api.modules(current.id)
                    "assignments" -> assignments = api.assignments(current.id)
                    "announcements" -> announcements = api.announcements(current.id)
                    else -> files = api.files(current.id)
                }
            }
        }.onFailure {
            error = it.message ?: "Canvas 读取失败"
            if (it.message?.contains("Token") == true) configured = api.token().isNotEmpty()
        }
        loading = false
    }

    BackHandler {
        when {
            reading != null -> reading = null
            course != null -> course = null
            else -> onClose()
        }
    }

    fun openDoc(fallbackUrl: String, loader: suspend () -> CanvasDoc) {
        scope.launch {
            opening = true
            val doc = runCatching { loader() }.getOrNull()
            if (doc != null && doc.html.isNotBlank()) {
                reading = doc
            } else if (fallbackUrl.isNotBlank()) {
                openLink(context, fallbackUrl)
            } else {
                toast(context, "这条内容没有可显示的正文")
            }
            opening = false
        }
    }

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
            TextButton(onClick = { if (course != null) course = null else onClose() }) {
                Text("← 返回")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    course?.name ?: "Canvas 资料",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                Text(
                    when {
                        !configured -> "需要访问令牌"
                        loading -> "正在读取…"
                        error != null -> error ?: ""
                        course == null -> "${courses.size} 门课程"
                        section == "modules" -> "${modules.size} 个模块"
                        section == "assignments" -> "${assignments.size} 个作业"
                        section == "announcements" -> "${announcements.size} 条公告"
                        else -> "${files.size} 个文件"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (configured) {
                TextButton(onClick = { showTokenDialog = true }) { Text("Token") }
            }
        }

        if (!configured) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("配置 Canvas", style = MaterialTheme.typography.titleMedium)
                Text(
                    "打开 oc.sjtu.edu.cn → 账户 → 设置 → 新建访问令牌，把生成的 Token 粘贴到这里。Token 只保存在本机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Canvas Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (tokenInput.isBlank()) {
                            toast(context, "请粘贴 Token")
                            return@Button
                        }
                        api.saveToken(tokenInput)
                        configured = true
                        reload++
                    },
                ) { Text("保存并读取") }
            }
        } else if (loading && courses.isEmpty() && files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            if (course != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "files" to "文件",
                        "modules" to "模块",
                        "assignments" to "作业",
                        "announcements" to "公告",
                    ).forEach { (key, label) ->
                        FilterChip(
                            selected = section == key,
                            onClick = { section = key },
                            label = { Text(label) },
                        )
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (error != null) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "读取失败：$error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { reload++ }) { Text("重试") }
                        }
                    }
                }
                if (course == null) {
                    items(courses, key = { it.id }) { item ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable {
                                    course = item
                                    section = "files"
                                }
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                listOf(item.code, item.term).filter { it.isNotBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (courses.isEmpty() && error == null) {
                        item { Text("没有进行中的课程。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                } else {
                    when (section) {
                        "modules" -> {
                            items(modules, key = { "m-${it.id}" }) { module ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(module.name, style = MaterialTheme.typography.titleSmall)
                                    module.items.forEach { moduleItem ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .clickable {
                                                    val current = course ?: return@clickable
                                                    when (moduleItem.type) {
                                                        "Page" -> openDoc(moduleItem.url) {
                                                            api.page(
                                                                current.id,
                                                                moduleItem.pageUrl.ifBlank {
                                                                    moduleItem.url.substringAfterLast('/')
                                                                },
                                                            )
                                                        }
                                                        "Assignment" -> openDoc(moduleItem.url) {
                                                            api.assignmentDoc(current.id, moduleItem.contentId)
                                                        }
                                                        "Discussion" -> openDoc(moduleItem.url) {
                                                            api.discussionDoc(current.id, moduleItem.contentId)
                                                        }
                                                        else -> openLink(context, moduleItem.url)
                                                    }
                                                }
                                                .padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                moduleTypeLabel(moduleItem.type),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                moduleItem.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                    if (module.items.isEmpty()) {
                                        Text(
                                            "模块内没有条目。",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (modules.isEmpty() && error == null) {
                                item { Text("这门课程没有模块。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        "assignments" -> {
                            items(assignments, key = { "a-${it.id}" }) { assignment ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            val current = course ?: return@clickable
                                            openDoc(assignment.url) {
                                                api.assignmentDoc(current.id, assignment.id)
                                            }
                                        }
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(assignment.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        listOf(
                                            if (assignment.dueAt.isBlank()) "无截止时间"
                                            else "截止 ${formatDue(assignment.dueAt)}",
                                            if (assignment.points > 0) "${formatPoints(assignment.points)} 分" else "",
                                        ).filter { it.isNotBlank() }.joinToString(" · "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (assignments.isEmpty() && error == null) {
                                item { Text("这门课程没有作业。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        "announcements" -> {
                            items(announcements, key = { "n-${it.id}" }) { announcement ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            val current = course ?: return@clickable
                                            openDoc(announcement.url) {
                                                api.discussionDoc(current.id, announcement.id)
                                            }
                                        }
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(announcement.title, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        announcement.postedAt.replace("T", " ").take(16),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (announcements.isEmpty() && error == null) {
                                item { Text("这门课程没有公告。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                        else -> {
                            items(files, key = { it.id }) { file ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable { selectedFile = file }
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            listOf(formatBytes(file.size), file.updatedAt.take(10))
                                                .filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            if (files.isEmpty() && error == null) {
                                item { Text("这门课程没有可读取的文件。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showTokenDialog) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text("更换 Canvas Token") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Token") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (value.isNotBlank()) {
                        api.saveToken(value)
                        configured = true
                        course = null
                        section = "files"
                        reload++
                    }
                    showTokenDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showTokenDialog = false }) { Text("取消") }
            },
        )
    }

    selectedFile?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!busy) selectedFile = null },
            title = { Text(file.name) },
            text = { Text("用系统应用打开，或保存到本机下载目录。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(file.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                            .onFailure { toast(context, "没有可打开该文件的应用") }
                        busy = false
                        selectedFile = null
                    },
                ) { Text("打开") }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        runCatching {
                            val request = DownloadManager.Request(Uri.parse(file.url))
                                .setTitle(file.name)
                                .setNotificationVisibility(
                                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                                )
                                .setDestinationInExternalPublicDir(
                                    Environment.DIRECTORY_DOWNLOADS,
                                    file.name,
                                )
                            context.getSystemService(DownloadManager::class.java)?.enqueue(request)
                                ?: error("系统下载器不可用")
                            toast(context, "已加入下载")
                        }.onFailure { toast(context, it.message ?: "下载失败") }
                        busy = false
                        selectedFile = null
                    },
                ) { Text("下载") }
            },
        )
    }

    if (opening) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x88000000)),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
    }

    reading?.let { doc ->
        CanvasReader(doc) { reading = null }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CanvasReader(doc: CanvasDoc, onBack: () -> Unit) {
    BackHandler { onBack() }
    val html = remember(doc) { buildCanvasHtml(doc) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                doc.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                WebView(viewContext).apply {
                    setBackgroundColor(0xFF0F1115.toInt())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadDataWithBaseURL("https://oc.sjtu.edu.cn", html, "text/html", "UTF-8", null)
                }
            },
        )
    }
}

private fun buildCanvasHtml(doc: CanvasDoc): String {
    val title = TextUtils.htmlEncode(doc.title)
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{background:#0F1115;color:#E6E8EC;font:16px/1.7 -apple-system,system-ui,sans-serif;margin:0;padding:16px 18px 80px}
h1{font-size:20px;line-height:1.45;margin:4px 0 16px}
img{max-width:100%;height:auto;border-radius:10px}
a{color:#9DC2FF;word-break:break-word}
pre,code{background:#1D2129;border-radius:8px;padding:2px 6px;white-space:pre-wrap;word-break:break-word;font-size:14px}
pre{padding:12px}
blockquote{border-left:3px solid #3A4150;margin:8px 0;padding-left:12px;color:#A8B0BC}
table{border-collapse:collapse;width:100%}
td,th{border:1px solid #2A303A;padding:6px 8px}
</style></head><body><h1>$title</h1>${doc.html}</body></html>"""
}
