package com.sjtuhive.app.ui

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.CloudFile
import com.sjtuhive.app.data.CloudPanApi
import com.sjtuhive.app.data.CloudPanTransfers
import com.sjtuhive.app.data.PanShare
import com.sjtuhive.app.data.PanShareItem
import com.sjtuhive.app.data.PanShareOptions
import com.sjtuhive.app.data.RecycledItem
import com.sjtuhive.app.data.TransferTask
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CloudPanScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
) {
    val api = remember { CloudPanApi(context) }
    val cached = HiveUiCache.cloud
    var path by remember { mutableStateOf(cached?.path ?: "") }
    var files by remember { mutableStateOf(cached?.files ?: emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var transfers by remember { mutableStateOf<List<TransferTask>>(emptyList()) }
    var showTransfers by remember { mutableStateOf(false) }
    var showShares by remember { mutableStateOf(false) }
    var shares by remember { mutableStateOf<List<PanShare>>(emptyList()) }
    var sharesLoading by remember { mutableStateOf(false) }
    var sharesVersion by remember { mutableIntStateOf(0) }
    var shareTarget by remember { mutableStateOf<CloudFile?>(null) }
    var deleteTarget by remember { mutableStateOf<CloudFile?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var showRecycled by remember { mutableStateOf(false) }
    var recycled by remember { mutableStateOf<List<RecycledItem>>(emptyList()) }
    var recycledLoading by remember { mutableStateOf(false) }
    var recycledError by remember { mutableStateOf<String?>(null) }
    var recycledVersion by remember { mutableIntStateOf(0) }
    var purgeTarget by remember { mutableStateOf<RecycledItem?>(null) }
    var recycleBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uris.forEach { CloudPanTransfers.enqueueUpload(context, it, path) }
        showTransfers = true
        toast(context, "已加入 ${uris.size} 个上传任务")
    }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        CloudPanTransfers.enqueueUploadTree(context, uri, path)
        showTransfers = true
        toast(context, "已加入文件夹上传任务")
    }

    LaunchedEffect(loggedIn) {
        if (loggedIn) CloudPanTransfers.init(context)
        CloudPanTransfers.tasks.collectLatest { transfers = it }
    }

    BackHandler(enabled = path.isNotEmpty()) {
        path = path.substringBeforeLast('/', "")
    }

    LaunchedEffect(loggedIn, path, reload) {
        if (!loggedIn) return@LaunchedEffect
        val cache = HiveUiCache.cloud
        if (reload == 0 && cache != null && cache.path == path && HiveUiCache.fresh(cache.at)) {
            files = cache.files
            return@LaunchedEffect
        }
        loading = true
        error = null
        runCatching { api.listing(path) }
            .onSuccess {
                files = it.files
                HiveUiCache.cloud = HiveUiCache.Cloud(path, it.files, System.currentTimeMillis())
            }
            .onFailure {
                if (it !is kotlinx.coroutines.CancellationException) {
                    error = it.message ?: "云盘读取失败"
                }
            }
        loading = false
    }

    LaunchedEffect(showShares, sharesVersion) {
        if (!showShares) return@LaunchedEffect
        sharesLoading = true
        runCatching { api.listShares() }
            .onSuccess { shares = it }
            .onFailure {
                if (it !is kotlinx.coroutines.CancellationException) {
                    toast(context, it.message ?: "读取分享失败")
                }
            }
        sharesLoading = false
    }

    LaunchedEffect(showRecycled, recycledVersion) {
        if (!showRecycled) return@LaunchedEffect
        recycledLoading = true
        recycledError = null
        runCatching { api.recycled() }
            .onSuccess { recycled = it }
            .onFailure {
                if (it !is kotlinx.coroutines.CancellationException) {
                    recycledError = it.message ?: "读取回收站失败"
                }
            }
        recycledLoading = false
    }

    LaunchedEffect(transfers.count { it.status == "done" }) {
        if (transfers.any { it.status == "done" }) reload++
    }

    if (!loggedIn) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("交大云盘", style = MaterialTheme.typography.headlineMedium)
            Text(
                "登录 jAccount 后浏览个人空间，文件通过交大预签名地址下载。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLogin) { Text("登录 jAccount") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
            Text("交大云盘", style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    loading -> "正在读取…"
                    error != null -> error ?: ""
                    else -> if (path.isEmpty()) "个人空间 · ${files.size} 项"
                    else "$path · ${files.size} 项"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) { Text("上传") }
                TextButton(onClick = { folderLauncher.launch(null) }) { Text("文件夹") }
                TextButton(onClick = {
                    sharesVersion++
                    showShares = true
                }) { Text("分享") }
                TextButton(onClick = { showTransfers = true }) {
                    val active = transfers.count { it.status == "running" || it.status == "queued" }
                    Text(if (active > 0) "传输 $active" else "传输")
                }
                TextButton(onClick = {
                    recycledVersion++
                    showRecycled = true
                }) { Text("回收站") }
                TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
            }
        }

        if (path.isNotEmpty()) {
            TextButton(
                onClick = { path = path.substringBeforeLast('/', "") },
                modifier = Modifier.padding(start = 12.dp),
            ) { Text("← 上一级") }
        }

        if (loading && files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
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
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { reload++ }) { Text("重试") }
                            TextButton(onClick = {
                                api.reset()
                                reload++
                            }) { Text("重新授权") }
                        }
                    }
                }
            }
            if (files.isEmpty() && error == null) {
                item {
                    Text(
                        "目录为空。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(files, key = { it.path }) { file ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            if (file.directory) {
                                path = file.path
                            } else {
                                PreviewHost.open(
                                    PreviewRequest(
                                        title = file.name,
                                        fileName = file.name,
                                        load = { api.downloadToCache(file.path, file.name) },
                                        download = {
                                            CloudPanTransfers.enqueueDownload(
                                                file.path,
                                                file.name,
                                                file.size,
                                            )
                                            "已加入下载队列"
                                        },
                                    ),
                                )
                            }
                        }
                        .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        if (file.directory) Icons.Outlined.Folder else Icons.Outlined.Description,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = if (file.directory) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (file.directory) "文件夹" else listOf(
                                formatSize(file.size),
                                file.modifiedAt.replace("T", " ").take(16),
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { shareTarget = file }) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = "创建分享",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    if (!file.directory) {
                        IconButton(
                            onClick = {
                                CloudPanTransfers.enqueueDownload(file.path, file.name, file.size)
                                showTransfers = true
                            },
                        ) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = "下载",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(onClick = { deleteTarget = file }) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "移入回收站",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    shareTarget?.let { file ->
        CreateShareDialog(
            file = file,
            onDismiss = { shareTarget = null },
            onCreate = { options, onDone ->
                scope.launch {
                    runCatching {
                        api.createShare(
                            listOf(PanShareItem(file.path, file.directory)),
                            options,
                        )
                    }.onSuccess { share ->
                        copyText(context, share.url)
                        toast(context, "分享已创建，链接已复制")
                        sharesVersion++
                        onDone()
                    }.onFailure {
                        toast(context, it.message ?: "创建分享失败")
                        onDone()
                    }
                }
            },
        )
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("移入回收站") },
            text = { Text("“${file.name}”将移入云盘回收站，可在一段时间内恢复。") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            runCatching { api.delete(file.path, file.directory) }
                                .onSuccess {
                                    files = files.filterNot { it.path == file.path }
                                    HiveUiCache.cloud = null
                                    toast(context, "已移入回收站")
                                    deleteTarget = null
                                }
                                .onFailure { toast(context, it.message ?: "删除失败") }
                            deleting = false
                        }
                    },
                ) { Text(if (deleting) "处理中…" else "移入回收站") }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    purgeTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!recycleBusy) purgeTarget = null },
            title = { Text("永久删除") },
            text = { Text("“${item.name}”将被永久删除，无法恢复。") },
            confirmButton = {
                TextButton(
                    enabled = !recycleBusy,
                    onClick = {
                        recycleBusy = true
                        scope.launch {
                            runCatching { api.deleteRecycled(listOf(item)) }
                                .onSuccess {
                                    recycled = recycled.filterNot { it.id == item.id }
                                    toast(context, "已永久删除")
                                    purgeTarget = null
                                }
                                .onFailure { toast(context, it.message ?: "删除失败") }
                            recycleBusy = false
                        }
                    },
                ) { Text(if (recycleBusy) "处理中…" else "永久删除") }
            },
            dismissButton = {
                TextButton(enabled = !recycleBusy, onClick = { purgeTarget = null }) { Text("取消") }
            },
        )
    }

    if (showRecycled) {
        ModalBottomSheet(onDismissRequest = { if (!recycleBusy) showRecycled = false }) {
            RecycledSheet(
                items = recycled,
                loading = recycledLoading,
                error = recycledError,
                busy = recycleBusy,
                onRefresh = { recycledVersion++ },
                onRestore = { item ->
                    recycleBusy = true
                    scope.launch {
                        runCatching { api.restoreRecycled(listOf(item)) }
                            .onSuccess {
                                recycled = recycled.filterNot { it.id == item.id }
                                HiveUiCache.cloud = null
                                reload++
                                toast(context, "已恢复")
                            }
                            .onFailure { toast(context, it.message ?: "恢复失败") }
                        recycleBusy = false
                    }
                },
                onPurge = { purgeTarget = it },
                onClose = { if (!recycleBusy) showRecycled = false },
            )
        }
    }

    if (showShares) {
        ModalBottomSheet(onDismissRequest = { showShares = false }) {
            SharesSheet(
                shares = shares,
                loading = sharesLoading,
                onCopy = { copyText(context, it) },
                onDelete = { share ->
                    scope.launch {
                        runCatching { api.deleteShare(share.id) }
                            .onSuccess {
                                shares = shares.filterNot { it.id == share.id }
                                toast(context, "分享已取消")
                            }
                            .onFailure { toast(context, it.message ?: "取消失败") }
                    }
                },
                onClose = { showShares = false },
            )
        }
    }

    if (showTransfers) {
        ModalBottomSheet(onDismissRequest = { showTransfers = false }) {
            TransfersSheet(
                tasks = transfers,
                onPause = CloudPanTransfers::pause,
                onResume = CloudPanTransfers::resume,
                onCancel = CloudPanTransfers::cancel,
                onClear = CloudPanTransfers::clear,
                onClearFinished = CloudPanTransfers::clearFinished,
                onPauseAll = CloudPanTransfers::pauseAll,
                onResumeAll = CloudPanTransfers::resumeAll,
                onClose = { showTransfers = false },
            )
        }
    }
}

@Composable
private fun TransfersSheet(
    tasks: List<TransferTask>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClear: (String) -> Unit,
    onClearFinished: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("传输列表", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val active = tasks.count { it.status == "running" || it.status == "queued" }
            val resumable = tasks.count { it.status == "paused" || it.status == "failed" }
            TextButton(onClick = onPauseAll, enabled = active > 0) { Text("全部暂停") }
            TextButton(onClick = onResumeAll, enabled = resumable > 0) { Text("全部继续") }
            TextButton(onClick = onClearFinished, enabled = tasks.isNotEmpty()) { Text("清除已完成") }
        }
        if (tasks.isEmpty()) {
            Text("暂无传输任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(
            Modifier.fillMaxWidth().height(360.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(tasks, key = { it.id }) { task ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            task.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            transferStatus(task),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (task.status == "failed") MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (task.status == "running" || task.status == "queued") {
                        LinearProgressIndicator(
                            progress = { task.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            buildString {
                                append(formatSize(task.sent))
                                if (task.size > 0) append(" / ${formatSize(task.size)}")
                                if (task.error.isNotBlank()) append(" · ${task.error}")
                                if (task.kind == "upload") append(" · 上传") else append(" · 下载")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        when (task.status) {
                            "running", "queued" -> TextButton(onClick = { onPause(task.id) }) {
                                Text("暂停")
                            }
                            "paused" -> TextButton(onClick = { onResume(task.id) }) { Text("继续") }
                            "failed" -> TextButton(onClick = { onResume(task.id) }) { Text("重试") }
                            else -> TextButton(onClick = { onClear(task.id) }) { Text("移除") }
                        }
                        if (task.status != "done" && task.status != "canceled" &&
                            task.status != "failed"
                        ) {
                            IconButton(onClick = { onCancel(task.id) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = "取消",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun transferStatus(task: TransferTask): String = when (task.status) {
    "queued" -> "排队中"
    "running" -> "进行中"
    "paused" -> "已暂停"
    "failed" -> "失败"
    "done" -> "已完成"
    "canceled" -> "已取消"
    else -> task.status
}

@Composable
private fun RecycledSheet(
    items: List<RecycledItem>,
    loading: Boolean,
    error: String?,
    busy: Boolean,
    onRefresh: () -> Unit,
    onRestore: (RecycledItem) -> Unit,
    onPurge: (RecycledItem) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("回收站", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !loading && !busy) { Text("刷新") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
        }
        if (loading && items.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (error != null) {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (items.isEmpty()) {
            Text("回收站是空的。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().height(420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (item.directory) Icons.Outlined.Folder else Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            listOfNotNull(
                                item.path.takeIf { it.isNotBlank() },
                                if (item.directory) "" else formatSize(item.size),
                                item.removalTime.takeIf { it.isNotBlank() }?.let { "删除于 ${it.replace("T", " ").take(16)}" },
                                item.remainingTime.takeIf { it.isNotBlank() }?.let { "剩余 $it 天" },
                                if (item.restoreExpired) "已过恢复期" else "",
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                enabled = !busy && item.canRestore && !item.restoreExpired,
                                onClick = { onRestore(item) },
                            ) { Text("恢复") }
                            TextButton(
                                enabled = !busy && item.canDelete,
                                onClick = { onPurge(item) },
                            ) { Text("永久删除") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharesSheet(
    shares: List<PanShare>,
    loading: Boolean,
    onCopy: (String) -> Unit,
    onDelete: (PanShare) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("我的分享", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "关闭") }
        }
        if (loading && shares.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (shares.isEmpty()) {
            Text("还没有分享。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().height(360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shares, key = { it.id }) { share ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                share.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    share.expireTime.takeIf { it.isNotBlank() }?.let { "到期 $it" },
                                    share.extractionCode.takeIf { it.isNotBlank() }
                                        ?.let { "提取码 $it" },
                                ).joinToString(" · ").ifBlank { "无到期时间" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onCopy(share.url) }) { Text("复制") }
                        IconButton(onClick = { onDelete(share) }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = "取消分享",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateShareDialog(
    file: CloudFile,
    onDismiss: () -> Unit,
    onCreate: (PanShareOptions, () -> Unit) -> Unit,
) {
    var expireDays by remember { mutableIntStateOf(30) }
    var permanent by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var canPreview by remember { mutableStateOf(true) }
    var canDownload by remember { mutableStateOf(true) }
    var canSave by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("创建分享") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(file.name, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            permanent = false
                            expireDays = 7
                        },
                        enabled = !permanent && expireDays == 7,
                    ) { Text("7 天") }
                    TextButton(
                        onClick = {
                            permanent = false
                            expireDays = 30
                        },
                        enabled = !permanent && expireDays == 30,
                    ) { Text("30 天") }
                    TextButton(onClick = { permanent = true }, enabled = !permanent) { Text("永久") }
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(8) },
                    label = { Text("提取码（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CheckRow("允许在线预览", canPreview) { canPreview = it }
                CheckRow("允许下载", canDownload) { canDownload = it }
                CheckRow("允许转存到他人网盘", canSave) { canSave = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating,
                onClick = {
                    creating = true
                    onCreate(
                        PanShareOptions(
                            name = file.name,
                            permanent = permanent,
                            expireDays = expireDays,
                            extractionCode = code,
                            canPreview = canPreview,
                            canDownload = canDownload,
                            canSaveToNetDisc = canSave,
                        ),
                        onDismiss,
                    )
                },
            ) { Text(if (creating) "创建中…" else "创建并复制链接") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !creating) { Text("取消") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun copyText(context: Context, value: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("分享链接", value))
}

internal fun startDownload(context: Context, url: String, name: String) {
    val request = DownloadManager.Request(Uri.parse(url))
        .setTitle(name)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
    val manager = context.getSystemService(DownloadManager::class.java)
        ?: throw IllegalStateException("系统下载器不可用")
    manager.enqueue(request)
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}
