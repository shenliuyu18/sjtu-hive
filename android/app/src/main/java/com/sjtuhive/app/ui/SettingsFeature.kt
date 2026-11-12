package com.sjtuhive.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.AndroidUpdate
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import com.sjtuhive.app.data.HiveNotifications
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.UpdateApi
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    servicesEnabled: Boolean,
    onServicesEnabledChange: (Boolean) -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            HiveNotifications.notify(context, 9001, "SJTU Hive 通知测试", "通知权限已开启，新的交我办消息和邮件会在这里提醒。")
        }
    }
    val updateApi = remember { UpdateApi(context) }
    var updateInfo by remember { mutableStateOf<AndroidUpdate?>(null) }
    var updateOpen by remember { mutableStateOf(false) }
    var updateBusy by remember { mutableStateOf("") }
    var updateProgress by remember { mutableStateOf(0L to 0L) }
    var updateFile by remember { mutableStateOf<java.io.File?>(null) }
    var updateMessage by remember { mutableStateOf("") }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text(
            "账号与关于",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsCard(title = "jAccount") {
            Text(
                if (loggedIn) "已登录，会话保存在本机" else "未登录",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loggedIn) {
                    OutlinedButton(onClick = { confirmLogout = true }) { Text("退出登录") }
                    Button(onClick = onLogin) { Text("重新登录") }
                } else {
                    Button(onClick = onLogin) { Text("登录 jAccount") }
                }
            }
        }

        SettingsCard(title = "外观") {
            Text("深色 · MD3", style = MaterialTheme.typography.bodyMedium)
            Text(
                "纯色背景 + legado-with-MD3 玻璃底栏；状态栏固定浅色图标。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "测试功能") {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("办事大厅（测试功能）", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "在底部页签显示交我办办事入口；功能仍在测试，默认关闭。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = servicesEnabled, onCheckedChange = onServicesEnabledChange)
            }
        }

        SettingsCard(title = "关于") {
            Text("SJTU Hive", style = MaterialTheme.typography.titleSmall)
            Text(
                "版本 ${version.ifBlank { "0.1.0" }} · Android",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                updateMessage.ifBlank {
                    "从 GitHub Releases 检查正式版更新，只下载 APK，不上传任何数据。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        if (HiveNotifications.canNotify(context)) {
                            HiveNotifications.notify(
                                context,
                                9001,
                                "SJTU Hive 通知测试",
                                "系统通知已就绪，新的交我办消息和邮件会在这里提醒。",
                            )
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            android.widget.Toast.makeText(context, "系统通知已在系统设置中被关闭", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("测试通知") }
                Button(
                    enabled = updateBusy != "check",
                    onClick = {
                        scope.launch {
                            updateBusy = "check"
                            runCatching { updateApi.check() }
                                .onSuccess {
                                    updateInfo = it
                                    updateFile = null
                                    updateMessage = it.message
                                    if (it.available) updateOpen = true
                                }
                                .onFailure { updateMessage = it.message ?: "检查更新失败" }
                            updateBusy = ""
                        }
                    },
                ) { Text(if (updateBusy == "check") "检查中…" else "检查更新") }
                TextButton(
                    onClick = {
                        updateApi.openReleasePage(updateInfo?.pageUrl.orEmpty())
                    },
                ) { Text("发布页") }
            }
            Text(
                "本应用以 GPL-3.0 开源；玻璃组件源码来自 legado-with-MD3。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/HapeLee/legado-with-MD3"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            ) { Text("查看 legado-with-MD3 源码") }
        }
    }

    if (updateOpen && updateInfo != null) {
        val info = updateInfo!!
        AlertDialog(
            onDismissRequest = { if (updateBusy != "download") updateOpen = false },
            title = { Text("发现新版本 ${info.latest}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (info.publishedAt.isNotBlank()) {
                        Text(
                            "发布于 ${info.publishedAt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        info.notes.ifBlank { "这个版本没有提供更新说明。" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (updateBusy == "download") {
                        val (received, total) = updateProgress
                        LinearProgressIndicator(
                            progress = {
                                if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            buildString {
                                append("${received / 1024 / 1024} MB")
                                if (total > 0) append(" / ${total / 1024 / 1024} MB")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (updateBusy == "download" && updateProgress.second == 0L) {
                        CircularProgressIndicator(Modifier.size(18.dp))
                    }
                }
            },
            confirmButton = {
                when {
                    updateFile != null -> TextButton(onClick = {
                        runCatching { updateApi.install(updateFile!!) }
                            .onFailure { updateMessage = it.message ?: "无法启动安装器" }
                    }) { Text("立即安装") }
                    info.assetUrl.isEmpty() -> TextButton(onClick = {
                        updateApi.openReleasePage(info.pageUrl)
                        updateOpen = false
                    }) { Text("打开发布页") }
                    else -> TextButton(
                        enabled = updateBusy != "download",
                        onClick = {
                            scope.launch {
                                updateBusy = "download"
                                updateProgress = 0L to info.assetSize
                                runCatching {
                                    updateApi.download(info) { received, total ->
                                        updateProgress = received to total
                                    }
                                }.onSuccess {
                                    updateFile = it
                                    updateMessage = "更新包已下载并通过校验"
                                }.onFailure {
                                    updateMessage = it.message ?: "下载更新包失败"
                                }
                                updateBusy = ""
                            }
                        },
                    ) { Text(if (updateBusy == "download") "下载中…" else "下载 APK") }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = updateBusy != "download",
                    onClick = { updateOpen = false },
                ) { Text("关闭") }
            },
        )
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机保存的 jAccount 会话，云端数据不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmLogout = false
                        HiveSession.clear(context)
                        runCatching {
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                        }
                        onLogout()
                    },
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}
