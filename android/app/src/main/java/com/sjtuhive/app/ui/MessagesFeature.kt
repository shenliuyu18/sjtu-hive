package com.sjtuhive.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.text.TextUtils
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
) {
    val cached = HiveUiCache.messages
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf(cached?.messages ?: emptyList()) }
    var unread by remember { mutableStateOf(cached?.unread ?: 0) }
    var reload by remember { mutableStateOf(0) }

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
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp)) {
            Text("交我办消息", style = MaterialTheme.typography.headlineMedium)
            Text(
                when {
                    loading -> "正在读取…"
                    error != null -> error ?: ""
                    unread > 0 -> "$unread 条未读"
                    messages.isNotEmpty() -> "共 ${messages.size} 条最近消息"
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
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surface)
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
            if (messages.isEmpty() && error == null) {
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

/** jAccount 登录窗口：加载起始页，登录完成后由调用方刷新并保存会话。 */
@SuppressLint("SetJavaScriptEnabled")
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