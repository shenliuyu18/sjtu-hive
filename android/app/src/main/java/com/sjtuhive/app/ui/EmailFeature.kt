package com.sjtuhive.app.ui

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sjtuhive.app.data.MailAccount
import com.sjtuhive.app.data.MailApi
import com.sjtuhive.app.data.MailAttachment
import com.sjtuhive.app.data.MailFolder
import com.sjtuhive.app.data.MailSummary
import kotlinx.coroutines.launch
import java.util.UUID

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
    var prefillTo by remember { mutableStateOf<String?>(null) }
    var prefillSubject by remember { mutableStateOf("") }
    var prefillBody by remember { mutableStateOf("") }
    var searchInput by remember { mutableStateOf("") }
    var searchTerm by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 详情页是同一屏内的分支；列表状态必须提升到这里，返回后才会停在原位置。
    val listState = rememberLazyListState()

    val draftPrefs = remember { context.getSharedPreferences("hive_mail_draft", android.content.Context.MODE_PRIVATE) }
    var draftKey by remember { mutableStateOf(draftPrefs.getString("draft_key", "").orEmpty()) }
    var draftRestored by remember { mutableStateOf(false) }

    fun ensureDraftKey(): String {
        if (draftKey.isBlank()) {
            draftKey = UUID.randomUUID().toString()
            draftPrefs.edit().putString("draft_key", draftKey).apply()
        }
        return draftKey
    }

    fun saveDraftToServer() {
        if (composeTo.isBlank() && composeSubject.isBlank() && composeBody.isBlank()) return
        val key = ensureDraftKey()
        val to = composeTo
        val subject = composeSubject
        val body = composeBody
        scope.launch {
            runCatching { api.saveServerDraft(key, to, subject, body) }
                .onSuccess {
                    Toast.makeText(context, "草稿已保存到邮箱", Toast.LENGTH_SHORT).show()
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: "草稿保存失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }
    LaunchedEffect(composing) {
        if (!composing) {
            draftRestored = false
            prefillTo = null
            return@LaunchedEffect
        }
        if (draftRestored) return@LaunchedEffect
        if (prefillTo != null) {
            composeTo = prefillTo.orEmpty()
            composeSubject = prefillSubject
            composeBody = prefillBody
        } else {
            composeTo = draftPrefs.getString("to", "").orEmpty()
            composeSubject = draftPrefs.getString("subject", "").orEmpty()
            composeBody = draftPrefs.getString("body", "").orEmpty()
        }
        draftRestored = true
    }
    // 草稿自动保存：停止输入 0.8 秒后落盘，关掉写信也不会丢
    LaunchedEffect(composing, composeTo, composeSubject, composeBody) {
        if (!composing || !draftRestored) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        draftPrefs.edit()
            .putString("to", composeTo)
            .putString("subject", composeSubject)
            .putString("body", composeBody)
            .apply()
    }

    LaunchedEffect(configured) {
        if (!configured) return@LaunchedEffect
        runCatching { folders = api.folders() }
    }

    LaunchedEffect(configured, selectedFolder, reload, searchTerm) {
        if (!configured) return@LaunchedEffect
        loading = true
        error = null
        val term = searchTerm
        runCatching {
            if (term == null) api.inbox(selectedFolder) else api.search(selectedFolder, term)
        }
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
                        searchTerm != null -> "搜索：$searchTerm · ${messages.size} 封邮件"
                        else -> "${messages.size} 封邮件"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (configured) {
                if (openIndex == 0) {
                    TextButton(onClick = {
                        prefillTo = null
                        composing = true
                    }) { Text("写信") }
                    if (selectedFolder == "Trash") {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { api.emptyTrash() }
                                    .onSuccess {
                                        Toast.makeText(context, "回收站已清空", Toast.LENGTH_SHORT).show()
                                        reload++
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            it.message ?: "清空失败",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                            }
                        }) { Text("清空回收站") }
                    }
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

        if (configured && openIndex == 0) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    placeholder = { Text("搜索当前文件夹") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        searchTerm = searchInput.trim().ifBlank { null }
                    }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = searchInput.isNotBlank(),
                    onClick = { searchTerm = searchInput.trim() },
                ) { Text("搜索") }
                if (searchTerm != null) {
                    TextButton(onClick = {
                        searchTerm = null
                        searchInput = ""
                    }) { Text("清除") }
                }
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
            MailBody(
                api = api,
                folder = selectedFolder,
                index = openIndex,
                summary = messages.firstOrNull { it.index == openIndex },
                onMoved = {
                    openIndex = 0
                    reload++
                },
                onCompose = { to, subject, body ->
                    prefillTo = to
                    prefillSubject = subject
                    prefillBody = body
                    composing = true
                },
                onDeleted = {
                    openIndex = 0
                    reload++
                },
            )
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
                        onClick = {
                            selectedFolder = folder.name
                            searchTerm = null
                            searchInput = ""
                        },
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
            onDismissRequest = {
                if (!sending) {
                    saveDraftToServer()
                    composing = false
                }
            },
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
                                    val key = draftKey
                                    draftKey = ""
                                    draftPrefs.edit().clear().apply()
                                    if (key.isNotBlank()) {
                                        runCatching { api.deleteServerDraft(key) }
                                    }
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
                Row {
                    TextButton(
                        enabled = !sending,
                        onClick = {
                            saveDraftToServer()
                            composing = false
                        },
                    ) { Text("存草稿") }
                    TextButton(enabled = !sending, onClick = { composing = false }) { Text("取消") }
                }
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
    onMoved: () -> Unit = {},
    onCompose: (String, String, String) -> Unit = { _, _, _ -> },
    onDeleted: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var moving by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("") }
    var html by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<MailAttachment>>(emptyList()) }

    LaunchedEffect(index, folder) {
        loading = true
        error = null
        html = ""
        runCatching { api.body(folder, index) }
            .onSuccess { body = it }
            .onFailure { error = it.message ?: "正文读取失败" }
        runCatching { api.bodyHtml(folder, index) }.onSuccess { html = it }
        runCatching { api.attachments(folder, index) }
            .onSuccess { attachments = it }
        runCatching { api.markSeen(folder, index, true) }
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    listOf(summary.from, summary.date).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !moving,
                    onClick = {
                        val target = if (folder == "Trash") "INBOX" else "Trash"
                        scope.launch {
                            moving = true
                            runCatching { api.move(folder, index, target) }
                                .onSuccess {
                                    Toast.makeText(
                                        context,
                                        if (target == "Trash") "已移到回收站" else "已恢复到收件箱",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onMoved()
                                }
                                .onFailure { Toast.makeText(context, it.message ?: "操作失败", Toast.LENGTH_SHORT).show() }
                            moving = false
                        }
                    },
                ) { Text(if (folder == "Trash") "恢复到收件箱" else "移到回收站") }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(
                    enabled = !loading,
                    onClick = {
                        val subject = summary?.subject.orEmpty()
                        val quote = buildString {
                            append("\n\n------ 原始邮件 ------\n")
                            append("发件人：${summary?.from.orEmpty()}\n")
                            append("时间：${summary?.date.orEmpty()}\n")
                            append("主题：$subject\n\n")
                            append(body)
                        }
                        val replySubject = if (subject.startsWith("Re:", true)) {
                            subject
                        } else {
                            "Re: $subject"
                        }
                        onCompose(summary?.from.orEmpty(), replySubject, quote)
                    },
                ) { Text("回复") }
                TextButton(
                    enabled = !loading,
                    onClick = {
                        val subject = summary?.subject.orEmpty()
                        val quote = buildString {
                            append("\n\n------ 原始邮件 ------\n")
                            append("发件人：${summary?.from.orEmpty()}\n")
                            append("时间：${summary?.date.orEmpty()}\n")
                            append("主题：$subject\n\n")
                            append(body)
                        }
                        val forwardSubject = if (subject.startsWith("Fwd:", true)) {
                            subject
                        } else {
                            "Fwd: $subject"
                        }
                        onCompose("", forwardSubject, quote)
                    },
                ) { Text("转发") }
                TextButton(
                    enabled = !moving,
                    onClick = {
                        scope.launch {
                            moving = true
                            runCatching { api.markSeen(folder, index, false) }
                                .onSuccess {
                                    Toast.makeText(context, "已标为未读", Toast.LENGTH_SHORT).show()
                                    onMoved()
                                }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        it.message ?: "操作失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            moving = false
                        }
                    },
                ) { Text("标为未读") }
                if (folder == "Trash") {
                    TextButton(
                        enabled = !moving,
                        onClick = {
                            scope.launch {
                                moving = true
                                runCatching { api.deleteForever(folder, index) }
                                    .onSuccess {
                                        Toast.makeText(context, "已彻底删除", Toast.LENGTH_SHORT).show()
                                        onDeleted()
                                    }
                                    .onFailure {
                                        Toast.makeText(
                                            context,
                                            it.message ?: "删除失败",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                moving = false
                            }
                        },
                    ) { Text("彻底删除") }
                }
            }
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
            html.isNotBlank() -> MailHtmlBody(
                html = html,
                isDark = io.legado.app.ui.theme.LocalIsDark.current,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            else -> Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
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

@Composable
private fun MailHtmlBody(
    html: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember { WebView(context) }
    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }
    AndroidView(
        modifier = modifier,
        factory = {
            webView.apply {
                settings.javaScriptEnabled = false
                settings.blockNetworkImage = true
                settings.loadsImagesAutomatically = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return true
                        return HiveLinkRouter.open(context, url)
                    }
                }
            }
        },
        update = { view ->
            val background = if (isDark) "#16181D" else "#FFFFFF"
            val ink = if (isDark) "#E8EAED" else "#1A1C1F"
            val link = if (isDark) "#8AB4F8" else "#1A73E8"
            val rule = if (isDark) "#30343B" else "#E5E7EB"
            val css = """
                body{background:$background;color:$ink;font:15px/1.65 -apple-system,system-ui,sans-serif;margin:0;padding:4px 0 24px;word-break:break-word}
                a{color:$link}
                img{max-width:100%;height:auto}
                pre,code{white-space:pre-wrap;word-break:break-word}
                blockquote{border-left:3px solid $rule;margin:8px 0;padding-left:12px;opacity:.85}
                table{max-width:100%;border-collapse:collapse}
            """.trimIndent()
            view.loadDataWithBaseURL(
                "about:blank",
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                    "<style>$css</style>$html",
                "text/html",
                "UTF-8",
                null,
            )
        },
    )
}
