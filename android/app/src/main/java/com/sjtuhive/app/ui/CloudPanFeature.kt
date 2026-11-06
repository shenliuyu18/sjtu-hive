package com.sjtuhive.app.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.CloudFile
import com.sjtuhive.app.data.CloudPanApi
import kotlinx.coroutines.launch

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
    var reload by remember { mutableStateOf(0) }

    // 二级目录里按返回键应回上一级，而不是直接退出应用。
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
            .onFailure { error = it.message ?: "云盘读取失败" }
        loading = false
    }

    if (!loggedIn) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
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
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
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
                )
            }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        if (path.isNotEmpty()) {
            TextButton(
                onClick = {
                    val parent = path.substringBeforeLast('/', "")
                    path = parent
                },
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
                                // 点击文件直接进入预览，下载按钮放在预览页里。
                                PreviewHost.open(
                                    PreviewRequest(
                                        title = file.name,
                                        fileName = file.name,
                                        load = { api.downloadToCache(file.path, file.name) },
                                        download = {
                                            val url = api.fileUrl(file.path, download = true)
                                            startDownload(context, url, file.name)
                                            "已开始下载到系统下载目录"
                                        },
                                    ),
                                )
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                }
            }
        }
    }
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
