package com.sjtuhive.app.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.MailAccount
import com.sjtuhive.app.data.MailApi
import com.sjtuhive.app.data.MailAttachment
import com.sjtuhive.app.data.MailFolder
import com.sjtuhive.app.data.MailSummary
import kotlinx.coroutines.launch

@Composable
fun EmailScreen(
    context: Context,
    onClose: () -> Unit,
    initialFolder: String? = null,
    initialIndex: Int? = null,
) {
    val api = remember { MailApi(context) }
    var configured by remember { mutableStateOf(api.account() != null) }
    var address by remember { mutableStateOf(api.account()?.address.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<MailSummary>>(emptyList()) }
    var folders by remember { mutableStateOf<List<MailFolder>>(emptyList()) }
    var selectedFolder by remember { mutableStateOf(initialFolder ?: "INBOX") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var openIndex by remember { mutableIntStateOf(0) }
    var initialIndexHandled by remember { mutableStateOf(false) }
    var composing by remember { mutableStateOf(false) }
    var composeTo by remember { mutableStateOf("") }
    var composeSubject by remember { mutableStateOf("") }
    var composeBody by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 详情页是同一屏内的分支；列表状态必须提升到这里，返回后才会停在原位置。
    val listState = rememberLazyListState()

    LaunchedEffect(configured) {
        if (!configured) return@LaunchedEffect
        runCatching { folders = api.folders() }
    }

    LaunchedEffect(configured, selectedFolder, reload) {
        if (!configured) return@LaunchedEffect
        loading = true
        error = null
        runCatching { api.inbox(selectedFolder) }
            .onSuccess { messages = it }
            .onFailure { error = it.message ?: "收信失败" }
        loading = false
    }

    LaunchedEffect(messages, initialIndex) {
        val target = initialIndex
        if (initialIndexHandled || target == null || target <= 0) return@LaunchedEffect
        if (messages.any { it.index == target }) {
            openIndex = target
            initialIndexHandled = true
        }
    }

    BackHandler {
        if (openIndex != 0) openIndex = 0 else onClose()
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
                .padding(start = 8.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { if (openIndex != 0) openIndex = 0 else onClose() }) {
                Text("← 返回")
            }
            Column(Modifier.weight(1f)) {
                Text("邮箱", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        !configured -> "需要配置账号"
                        loading -> "正在连接 IMAP…"
                        error != null -> error ?: ""
                        else -> "${messages.size} 封邮件"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (configured) {
                if (openIndex == 0) {
                    TextButton(onClick = { composing = true }) { Text("写信") }
                }
                TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
                TextButton(onClick = {
                    api.logout()
                    configured = false
                    messages = emptyList()
                    folders = emptyList()
                    selectedFolder = "INBOX"
                    address = ""
                    password = ""
                }) { Text("账号") }
            }
        }

        if (!configured) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("配置交大邮箱", style = MaterialTheme.typography.titleMedium)
                Text(
                    "使用 mail.sjtu.edu.cn 的 IMAP（993）收信。账号密码只保存在本机，密码用 Android Keystore 加密。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("邮箱地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("邮箱密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (address.isBlank() || password.isBlank()) {
                            error = "请填写邮箱地址和密码"
                            return@Button
                        }
                        api.save(
                            MailAccount(
                                address = address.trim(),
                                password = password,
                                imapHost = "mail.sjtu.edu.cn",
                                imapPort = 993,
                                smtpHost = "mail.sjtu.edu.cn",
                                smtpPort = 465,
                            ),
                        )
                        error = null
                        configured = true
                        reload++
                    },
                ) { Text("保存并收信") }
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                }
            }
            return@Column
        }

        if (openIndex != 0) {
            MailBody(api, selectedFolder, openIndex, messages.firstOrNull { it.index == openIndex })
            return@Column
        }

        if (folders.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                folders.forEach { folder ->
                    FilterChip(
                        selected = selectedFolder == folder.name,
                        onClick = { selectedFolder = folder.name },
                        label = { Text(folder.label) },
                    )
                }
            }
        }

        if (loading && messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (error != null) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "收信失败：$error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = { reload++ }) { Text("重试") }
                    }
                }
            }
            items(messages, key = { it.index }) { message ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { openIndex = message.index }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        message.subject,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (message.seen) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        listOf(message.from, message.date).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (messages.isEmpty() && error == null) {
                item { Text("收件箱为空。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }

    if (composing) {
        AlertDialog(
            onDismissRequest = { if (!sending) composing = false },
            title = { Text("写邮件") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = composeTo,
                        onValueChange = { composeTo = it },
                        label = { Text("收件人") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = composeSubject,
                        onValueChange = { composeSubject = it },
                        label = { Text("主题") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = composeBody,
                        onValueChange = { composeBody = it },
                        label = { Text("正文") },
                        minLines = 3,
                        maxLines = 6,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        scope.launch {
                            runCatching { api.send(composeTo, composeSubject, composeBody) }
                                .onSuccess {
                                    Toast.makeText(context, "已发送", Toast.LENGTH_SHORT).show()
                                    composing = false
                                    composeTo = ""
                                    composeSubject = ""
                                    composeBody = ""
                                }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        it.message ?: "发送失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            sending = false
                        }
                    },
                ) { Text(if (sending) "发送中" else "发送") }
            },
            dismissButton = {
                TextButton(enabled = !sending, onClick = { composing = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MailBody(
    api: MailApi,
    folder: String,
    index: Int,
    summary: MailSummary?,
) {
    val context = LocalContext.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<MailAttachment>>(emptyList()) }

    LaunchedEffect(index, folder) {
        loading = true
        runCatching { api.body(folder, index) }
            .onSuccess { body = it }
            .onFailure { error = it.message ?: "正文读取失败" }
        runCatching { api.attachments(folder, index) }
            .onSuccess { attachments = it }
        loading = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            summary?.subject ?: "邮件",
            style = MaterialTheme.typography.titleMedium,
        )
        if (summary != null) {
            Text(
                listOf(summary.from, summary.date).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (attachments.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "附件（点按预览）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                attachments.forEach { attachment ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable {
                                PreviewHost.open(
                                    PreviewRequest(
                                        title = attachment.name,
                                        fileName = attachment.name,
                                        load = { api.downloadAttachment(folder, index, attachment.name) },
                                        download = {
                                            val saved = api.downloadAttachment(folder, index, attachment.name)
                                            saveToDownloads(context, saved, attachment.name)
                                            "已保存到下载目录"
                                        },
                                    ),
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(attachment.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            previewSizeLabel(attachment.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.error)
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                SelectionContainer {
                    Text(body, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun previewSizeLabel(size: Long): String = when {
    size <= 0 -> ""
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.0f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / 1024.0 / 1024.0)
}
