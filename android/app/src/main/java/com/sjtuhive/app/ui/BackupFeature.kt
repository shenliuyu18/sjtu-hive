package com.sjtuhive.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.BackupApi
import com.sjtuhive.app.data.WebDavClient
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复：本地文件（SAF）+ 可选口令加密 + WebDAV 云端。
 * 与桌面端的备份不互通（两端数据模型不同），但同一台设备上导出后可完整恢复。
 */
@Composable
fun BackupPage(context: android.content.Context, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val webdav = remember { WebDavClient(context) }
    var message by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseFor by remember { mutableStateOf<String?>(null) } // "export" | "restore" | "upload"
    var pendingRestore by remember { mutableStateOf<ByteArray?>(null) }

    var url by remember { mutableStateOf(webdav.config()?.url.orEmpty()) }
    var user by remember { mutableStateOf(webdav.config()?.user.orEmpty()) }
    var password by remember { mutableStateOf(webdav.config()?.password.orEmpty()) }
    var dir by remember { mutableStateOf(webdav.config()?.dir ?: "SJTU-Hive") }
    var remoteFiles by remember { mutableStateOf<List<String>>(emptyList()) }

    val stamp = remember { LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) }

    fun applyRestore(bytes: ByteArray, pass: String?) {
        scope.launch {
            working = true
            val result = runCatching {
                withContext(Dispatchers.IO) { BackupApi.restore(context, bytes, pass) }
            }
            message = result.fold(
                { "已恢复 $it 项数据，重启应用后完全生效" },
                { "恢复失败：${it.message}" },
            )
            working = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = BackupApi.pack(context, null)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("无法写入所选文件")
                    bytes.size
                }
            }
            message = result.fold({ "已导出备份（${it / 1024} KB）" }, { "导出失败：${it.message}" })
            working = false
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val bytes = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取所选文件")
                }
            }
            bytes.onSuccess {
                if (BackupApi.isEncrypted(it)) {
                    pendingRestore = it
                } else {
                    applyRestore(it, null)
                }
            }.onFailure { message = "读取失败：${it.message}" }
            working = false
        }
    }

    Column(
        modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        message?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Text(
            "备份包含：账号与主题设置、待办、页面缓存、邮箱凭据。" +
                "加密备份用口令保护；未加密的备份里邮箱密码是明文，请只放在自己可信的位置。" +
                "与桌面端的备份不通用。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !working,
                onClick = { exportLauncher.launch("SJTU-Hive-backup-$stamp.zip") },
            ) { Text("导出备份") }
            Button(
                enabled = !working,
                onClick = { passphraseFor = "export" },
            ) { Text("导出加密备份") }
        }
        Button(
            enabled = !working,
            onClick = { importLauncher.launch(arrayOf("*/*")) },
        ) { Text("从文件恢复") }

        Text(
            "WebDAV（坚果云 / Nextcloud 等）",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("服务器地址") },
            placeholder = { Text("https://dav.example.com/remote.php/dav/files/user") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("账号") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = dir,
            onValueChange = { dir = it },
            label = { Text("目录") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !working && url.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    webdav.save(WebDavClient.Config(url, user, password, dir))
                    message = "WebDAV 配置已保存"
                },
            ) { Text("保存配置") }
            Button(
                enabled = !working && url.isNotBlank(),
                modifier = Modifier.weight(1f),
                onClick = {
                    webdav.save(WebDavClient.Config(url, user, password, dir))
                    val config = webdav.config() ?: return@Button
                    scope.launch {
                        working = true
                        val result = runCatching { webdav.list(config) }
                        message = result.fold(
                            { files ->
                                remoteFiles = files.map { it.first }
                                "云端 ${files.size} 个文件"
                            },
                            { "连接失败：${it.message}" },
                        )
                        working = false
                    }
                },
            ) { Text("测试并列出") }
        }
        Button(
            enabled = !working && url.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = { passphraseFor = "upload" },
        ) { Text("上传备份到 WebDAV") }
        remoteFiles.forEach { name ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(
                    enabled = !working,
                    onClick = {
                        val config = webdav.config() ?: return@TextButton
                        scope.launch {
                            working = true
                            val result = runCatching { webdav.download(config, name) }
                            result.onSuccess { bytes ->
                                if (BackupApi.isEncrypted(bytes)) {
                                    pendingRestore = bytes
                                } else {
                                    applyRestore(bytes, null)
                                }
                            }.onFailure { message = "下载失败：${it.message}" }
                            working = false
                        }
                    },
                ) { Text("恢复") }
            }
        }
    }

    // 口令输入框：导出 / 恢复 / 上传共用
    val mode = passphraseFor
    if (mode != null) {
        AlertDialog(
            onDismissRequest = { passphraseFor = null },
            title = { Text(if (mode == "export" || mode == "upload") "设置备份口令" else "输入备份口令") },
            text = {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("口令（至少 8 个字符）") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= 8,
                    onClick = {
                        when (mode) {
                            "export" -> {
                                passphraseFor = null
                                val pass = passphrase
                                scope.launch {
                                    working = true
                                    val result = runCatching {
                                        withContext(Dispatchers.IO) {
                                            val bytes = BackupApi.pack(context, pass)
                                            val temp = java.io.File(context.cacheDir, "backup-$stamp.hivebackup")
                                            temp.writeBytes(bytes)
                                            val name = "SJTU-Hive-backup-$stamp.hivebackup"
                                            saveToDownloads(context, temp, name)
                                            temp.delete()
                                            bytes.size
                                        }
                                    }
                                    message = result.fold(
                                        { "加密备份已保存到下载目录（${it / 1024} KB）" },
                                        { "导出失败：${it.message}" },
                                    )
                                    working = false
                                    passphrase = ""
                                }
                            }
                            "upload" -> {
                                passphraseFor = null
                                val pass = passphrase
                                val config = webdav.config()
                                if (config != null) {
                                    scope.launch {
                                        working = true
                                        val result = runCatching {
                                            withContext(Dispatchers.IO) {
                                                val bytes = BackupApi.pack(context, pass)
                                                webdav.upload(config, "SJTU-Hive-backup-$stamp.hivebackup", bytes)
                                            }
                                        }
                                        message = result.fold(
                                            { "已上传到 WebDAV" },
                                            { "上传失败：${it.message}" },
                                        )
                                        working = false
                                        passphrase = ""
                                    }
                                }
                            }
                            else -> {
                                passphraseFor = null
                                pendingRestore?.let { applyRestore(it, passphrase) }
                                pendingRestore = null
                            }
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { passphraseFor = null }) { Text("取消") }
            },
        )
    }
    // 加密备份：选完文件立刻弹口令（用 LaunchedEffect 而不是在组合里写状态）
    androidx.compose.runtime.LaunchedEffect(pendingRestore) {
        if (pendingRestore != null) passphraseFor = "restore"
    }
}
