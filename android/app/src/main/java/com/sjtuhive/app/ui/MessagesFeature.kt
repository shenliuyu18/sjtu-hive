package com.sjtuhive.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sjtuhive.app.data.MailApi
import com.sjtuhive.app.data.MailSummary
import com.sjtuhive.app.data.CampusSession
import com.sjtuhive.app.data.HiveApi
import com.sjtuhive.app.data.HiveHttp
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.PortalGroup
import com.sjtuhive.app.data.PortalMessage

/**
 * 消息页：未登录时引导登录（WebView 走 jAccount），登录后直接读交我办消息。
 */
@Composable
fun MessagesScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onOpenEmail: (folder: String, index: Int) -> Unit,
) {
    val cached = HiveUiCache.messages
    val mailApi = remember { MailApi(context) }
    val mailConfigured = remember { mailApi.account() != null }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(cached?.messages ?: emptyList()) }
    var unread by remember { mutableStateOf(cached?.unread ?: 0) }
    var reload by remember { mutableStateOf(0) }
    var selected by remember { mutableStateOf<PortalMessage?>(null) }
    var mailMessages by remember { mutableStateOf<List<MailSummary>>(emptyList()) }
    var mailLoading by remember { mutableStateOf(false) }
    var mailError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    BackHandler(enabled = selected != null) { selected = null }

    LaunchedEffect(loggedIn, reload, mailConfigured) {
        if (!loggedIn) return@LaunchedEffect
        if (!mailConfigured) {
            mailMessages = emptyList()
            return@LaunchedEffect
        }
        mailLoading = true
        mailError = null
        runCatching { mailApi.inbox("INBOX", 12).filter { !it.seen } }
            .onSuccess { mailMessages = it }
            .onFailure { mailError = it.message ?: "读取邮件失败" }
        mailLoading = false
    }

    LaunchedEffect(loggedIn, reload) {
        if (!loggedIn) return@LaunchedEffect
        val cache = HiveUiCache.messages
        if (reload == 0 && cache != null && HiveUiCache.fresh(cache.at)) {
            messages = cache.messages
            unread = cache.unread
            return@LaunchedEffect
        }
        loading = true
        error = null
        suspend fun fetch(): Triple<List<PortalMessage>, Int, List<PortalGroup>> {
            val api = HiveApi(context)
            val groups = api.groups()
            val collected = mutableListOf<PortalMessage>()
            val unreadGroups = groups.filter { it.unread > 0 }
            for (group in (unreadGroups.ifEmpty { groups }).sortedByDescending(PortalGroup::unread)) {
                collected += api.messages(group)
                if (collected.size >= 60) break
            }
            return Triple(collected, groups.sumOf { it.unread }, groups)
        }
        val result = runCatching { fetch() }.recoverCatching {
            // 交我办首页会话过期时，先静默走一次 SSO 再重试。
            CampusSession.refresh(context, "my.sjtu.edu.cn")
            fetch()
        }
        result.onSuccess { (fetched, total, _) ->
            val list = fetched.distinctBy { it.id }.sortedByDescending { it.createdAt }
            messages = list
            unread = total
            HiveUiCache.messages = HiveUiCache.Messages(list, total, System.currentTimeMillis())
        }.onFailure { error = it.message ?: "读取失败" }
        loading = false
    }

    if (!loggedIn) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("交我办消息", style = MaterialTheme.typography.headlineMedium)
            Text(
                "使用 jAccount 登录后，这里显示未读的校园通知；登录会话只保存在本机。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLogin) { Text("登录 jAccount") }
            if (error != null) {
                Text(error ?: "", color = MaterialTheme.colorScheme.error)
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        val detail = selected
        if (detail != null) {
            MessageDetail(detail, onBack = { selected = null })
            return@Column
        }
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)) {
            Text("消息", style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    loading || mailLoading -> "正在读取…"
                    error != null -> error ?: ""
                    unread > 0 || mailMessages.isNotEmpty() ->
                        "交我办 $unread · 邮件 ${mailMessages.size}"
                    messages.isNotEmpty() || mailMessages.isNotEmpty() -> "暂无未读消息"
                    else -> "暂无未读消息"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (loading && messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "邮件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!mailConfigured) {
                item {
                    TextButton(onClick = { onOpenEmail("INBOX", 0) }) {
                        Text("配置邮箱以同步邮件")
                    }
                }
            } else {
                items(mailMessages, key = { "mail-${it.index}" }) { mail ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onOpenEmail("INBOX", mail.index) }
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "邮件",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            Text(
                                mail.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            mail.subject,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            mail.from,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (mailError != null) {
                    item {
                        Text(
                            "邮件读取失败：$mailError",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (mailMessages.isEmpty() && !mailLoading) {
                    item {
                        Text(
                            "没有未读邮件。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    "交我办",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            items(messages, key = { it.id }) { message ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { selected = message }
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(message.title, style = MaterialTheme.typography.titleSmall)
                    if (message.body.isNotBlank()) {
                        Text(
                            message.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                        )
                    }
                    Text(
                        listOf(message.source, message.createdAt)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (messages.isEmpty() && mailMessages.isEmpty() && error == null && mailConfigured) {
                item {
                    Text(
                        "暂无未读消息。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (error != null && messages.isEmpty()) {
                item {
                    TextButton(onClick = onLogin) { Text("重新登录 jAccount") }
                }
            }
            if (error != null && messages.isNotEmpty()) {
                item {
                    TextButton(onClick = { reload++ }) { Text("重新读取") }
                }
            }
        }
    }
}

@Composable
private fun MessageDetail(message: PortalMessage, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text("消息详情", style = MaterialTheme.typography.titleMedium)
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(message.title, style = MaterialTheme.typography.titleLarge)
            Text(
                listOf(message.source, message.createdAt).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    message.body.ifBlank { "（没有更多内容）" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val link = message.url?.takeIf { it.isNotBlank() }
            if (link != null) {
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                ) { Text("在浏览器打开") }
            }
        }
    }
}

/** jAccount 登录窗口：加载起始页，登录完成后由调用方刷新并保存会话。 */@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebView(
    startUrl: String = "https://my.sjtu.edu.cn/ui/message/",
    autoClickSelector: String? = null,
    onWebView: ((WebView) -> Unit)? = null,
) {
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        factory = { viewContext ->
            WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = HiveHttp.USER_AGENT
                CookieManager.getInstance().setAcceptCookie(true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val target = url ?: return false
                        return !target.startsWith("http://") && !target.startsWith("https://")
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val target = request?.url?.toString() ?: return false
                        return !target.startsWith("http://") && !target.startsWith("https://")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        // i.sjtu 的 jAccount 按钮用 target=_blank / window.open，
                        // 不改写会被系统浏览器接走，登录态就回不到应用里。
                        view?.evaluateJavascript(LOGIN_PATCH_JS, null)
                        if (!autoClickSelector.isNullOrBlank()) {
                            view?.evaluateJavascript(
                                "document.querySelector('$autoClickSelector')?.click();",
                                null,
                            )
                        }
                    }
                }
                webChromeClient = WebChromeClient()
                loadUrl(startUrl)
                onWebView?.invoke(this)
            }
        },
    )
}

private const val LOGIN_PATCH_JS = """
(function() {
  window.open = function(url) { if (url) { location.href = url; } return null; };
  var links = document.querySelectorAll('a[target="_blank"]');
  for (var i = 0; i < links.length; i++) { links[i].target = '_self'; }
})();
"""