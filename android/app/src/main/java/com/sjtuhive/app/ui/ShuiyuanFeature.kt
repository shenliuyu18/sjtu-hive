package com.sjtuhive.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.TextUtils
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sjtuhive.app.data.ShuiyuanApi
import com.sjtuhive.app.data.ShuiyuanCategory
import com.sjtuhive.app.data.ShuiyuanProfile
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.ShuiyuanTopic
import com.sjtuhive.app.data.ShuiyuanTopicDetail
import kotlinx.coroutines.launch

@Composable
fun ShuiyuanScreen(context: Context, onClose: () -> Unit) {
    val api = remember { ShuiyuanApi(context) }
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(api.isConnected()) }
    var showLogin by remember { mutableStateOf(false) }
    var topics by remember { mutableStateOf<List<ShuiyuanTopic>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var openTopic by remember { mutableLongStateOf(0L) }
    var profileName by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (!connected && api.ensureLogin()) connected = true
    }

    LaunchedEffect(connected, reload) {
        if (!connected) return@LaunchedEffect
        loading = true
        error = null
        runCatching { api.topics(0) }
            .onSuccess {
                topics = it.topics
                hasMore = it.hasMore
                page = 0
            }
            .onFailure { error = it.message ?: "水源社区读取失败" }
        loading = false
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            val next = page + 1
            runCatching { api.topics(next) }
                .onSuccess {
                    topics = topics + it.topics.filter { topic -> topics.none { old -> old.id == topic.id } }
                    hasMore = it.hasMore
                    page = next
                }
                .onFailure { error = it.message }
            loadingMore = false
        }
    }

    if (connected) {
        LaunchedEffect(listState, topics.size) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
                .collect { last ->
                    if (topics.isNotEmpty() && last >= topics.lastIndex - 2) loadMore()
                }
        }
    }

    BackHandler(enabled = showLogin || openTopic != 0L || profileName != null || composing) {
        when {
            composing -> composing = false
            showLogin -> showLogin = false
            profileName != null -> profileName = null
            else -> openTopic = 0L
        }
    }
    BackHandler(enabled = !showLogin && openTopic == 0L && profileName == null && !composing) { onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("← 返回") }
                Column(Modifier.weight(1f)) {
                    Text("水源社区", style = MaterialTheme.typography.titleLarge)
                    Text(
                        when {
                            !connected -> "需要登录"
                            loading -> "正在读取…"
                            error != null -> error ?: ""
                            else -> "${topics.size} 个话题"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (connected) {
                    TextButton(onClick = { composing = true }) { Text("发帖") }
                    TextButton(onClick = {
                        scope.launch {
                            val name = api.currentUsername()
                            if (name != null) {
                                profileName = name
                            } else {
                                Toast.makeText(context, "没有取得当前用户", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text("我的") }
                    TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
                }
            }

            if (!connected) {
                Column(
                    Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "水源社区使用 jAccount 单点登录；登录后可以阅读帖子。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { showLogin = true }) { Text("登录水源社区") }
                }
            } else if (loading && topics.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(topics, key = { it.id }) { topic ->
                        ShuiyuanTopicCard(topic) { openTopic = topic.id }
                    }
                    if (loadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    if (error != null && topics.isNotEmpty()) {
                        item {
                            TextButton(onClick = { reload++ }) { Text("加载失败，点此重试") }
                        }
                    }
                }
            }
        }

        if (showLogin) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                LoginWebView(startUrl = ShuiyuanApi.ORIGIN + "/")
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            HiveSession.refreshFromWebView(context)
                            connected = api.isConnected()
                            if (!connected) error = "登录未完成，请重试"
                            showLogin = false
                        },
                    ) { Text("完成登录") }
                    Button(onClick = { showLogin = false }) { Text("取消") }
                }
            }
        }

        if (openTopic != 0L) {
            ShuiyuanTopicScreen(
                context = context,
                topicId = openTopic,
                onBack = { openTopic = 0L },
                onOpenProfile = { profileName = it },
            )
        }

        profileName?.let { name ->
            ShuiyuanProfileScreen(
                context = context,
                username = name,
                onBack = { profileName = null },
                onOpenTopic = { topicId ->
                    profileName = null
                    openTopic = topicId
                },
            )
        }

        if (composing) {
            ShuiyuanComposeScreen(
                context = context,
                onBack = { composing = false },
                onCreated = { topicId ->
                    composing = false
                    openTopic = topicId
                    reload++
                },
            )
        }
    }
}

@Composable
private fun ShuiyuanTopicCard(topic: ShuiyuanTopic, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            (if (topic.pinned) "置顶 · " else "") + topic.title,
            style = MaterialTheme.typography.titleSmall,
        )
        if (topic.excerpt.isNotBlank()) {
            Text(
                topic.excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        Text(
            "回复 ${topic.postsCount} · 浏览 ${topic.views} · ${shuiyuanTime(topic.lastPostedAt)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShuiyuanTopicScreen(
    context: Context,
    topicId: Long,
    onBack: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val api = remember { ShuiyuanApi(context) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ShuiyuanTopicDetail?>(null) }
    var reply by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(topicId, reload) {
        loading = true
        error = null
        runCatching { ShuiyuanApi(context).topic(topicId) }
            .onSuccess { detail = it }
            .onFailure { error = it.message ?: "帖子读取失败" }
        loading = false
    }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Text(
                detail?.title ?: "帖子",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        Box(Modifier.weight(1f)) {
            when {
                loading && detail == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                error != null && detail == null -> Text(
                    error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                detail != null -> TopicHtmlView(context, detail!!, onOpenProfile)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = reply,
                onValueChange = { reply = it },
                placeholder = { Text("回复这个话题…") },
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = !sending && reply.isNotBlank(),
                onClick = {
                    sending = true
                    scope.launch {
                        runCatching { api.reply(topicId, reply) }
                            .onSuccess {
                                reply = ""
                                Toast.makeText(context, "已回复", Toast.LENGTH_SHORT).show()
                                reload++
                            }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    it.message ?: "回复失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        sending = false
                    }
                },
            ) { Text(if (sending) "发送中" else "发送") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TopicHtmlView(
    context: Context,
    detail: ShuiyuanTopicDetail,
    onOpenProfile: (String) -> Unit,
) {
    val html = remember(detail) { buildTopicHtml(detail) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                setBackgroundColor(0xFF0F1115.toInt())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val target = url ?: return false
                        val uri = runCatching { java.net.URI(target) }.getOrNull()
                        if (uri?.host == "shuiyuan.sjtu.edu.cn") {
                            val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }
                            if (segments.size >= 2 && segments[0] == "u") {
                                val username = runCatching {
                                    java.net.URLDecoder.decode(segments[1], "UTF-8")
                                }.getOrDefault(segments[1])
                                onOpenProfile(username)
                                return true
                            }
                        }
                        if (target.startsWith(ShuiyuanApi.ORIGIN)) return false
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(target))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        return true
                    }
                }
                loadDataWithBaseURL(ShuiyuanApi.ORIGIN, html, "text/html", "UTF-8", null)
            }
        },
    )
}

@Composable
private fun ShuiyuanComposeScreen(
    context: Context,
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val api = remember { ShuiyuanApi(context) }
    val scope = rememberCoroutineScope()
    var categories by remember { mutableStateOf<List<ShuiyuanCategory>>(emptyList()) }
    var categoryId by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { api.categories() }
            .onSuccess {
                categories = it
                categoryId = it.firstOrNull()?.id ?: 0L
            }
            .onFailure { error = it.message ?: "分类读取失败" }
        loading = false
    }

    BackHandler { onBack() }

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
            TextButton(onClick = onBack) { Text("取消") }
            Text(
                "发布新话题",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(
                enabled = !sending && title.isNotBlank() && raw.isNotBlank() && categoryId != 0L,
                onClick = {
                    sending = true
                    scope.launch {
                        runCatching { api.createTopic(title, raw, categoryId) }
                            .onSuccess {
                                Toast.makeText(context, "已发布", Toast.LENGTH_SHORT).show()
                                onCreated(it)
                            }
                            .onFailure {
                                error = it.message ?: "发布失败"
                                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                            }
                        sending = false
                    }
                },
            ) { Text(if (sending) "发布中" else "发布") }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("标题") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "分类",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = categoryId == category.id,
                        onClick = { categoryId = category.id },
                        label = { Text(category.name) },
                    )
                }
            }
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                label = { Text("正文（支持 Markdown）") },
                minLines = 8,
                maxLines = 16,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ShuiyuanProfileScreen(
    context: Context,
    username: String,
    onBack: () -> Unit,
    onOpenTopic: (Long) -> Unit,
) {
    val api = remember { ShuiyuanApi(context) }
    var profile by remember { mutableStateOf<ShuiyuanProfile?>(null) }
    var topics by remember { mutableStateOf<List<ShuiyuanTopic>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(username, reload) {
        loading = true
        error = null
        runCatching { api.profile(username) to api.createdTopics(username) }
            .onSuccess {
                profile = it.first
                topics = it.second
            }
            .onFailure { error = it.message ?: "个人主页读取失败" }
        loading = false
    }

    BackHandler { onBack() }

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
            TextButton(onClick = onBack) { Text("← 返回") }
            Column(Modifier.weight(1f)) {
                Text(
                    profile?.name?.takeIf { it.isNotBlank() } ?: username,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                )
                Text(
                    "@$username · 个人主页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        when {
            loading && profile == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && profile == null -> Text(
                error ?: "",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profile?.let { info ->
                    item {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (info.bio.isNotBlank()) {
                                Text(info.bio, style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(
                                "加入于 ${info.joinedAt}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "主题 ${info.topics} · 回复 ${info.posts} · 获赞 ${info.likesReceived}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                item {
                    Text("创建的主题", style = MaterialTheme.typography.titleMedium)
                }
                items(topics, key = { it.id }) { topic ->
                    ShuiyuanTopicCard(topic) { onOpenTopic(topic.id) }
                }
                if (topics.isEmpty() && error == null) {
                    item {
                        Text(
                            "没有公开的主题。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (error != null) {
                    item {
                        Text(
                            "部分内容读取失败：$error",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun shuiyuanTime(raw: String): String {
    val instant = runCatching { java.time.Instant.parse(raw) }.getOrNull()
        ?: return raw.replace("T", " ").take(16)
    val minutes = java.time.Duration.between(instant, java.time.Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 24 * 60 -> "${minutes / 60}小时前"
        else -> java.time.format.DateTimeFormatter
            .ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)
    }
}

private fun buildTopicHtml(detail: ShuiyuanTopicDetail): String {    val title = TextUtils.htmlEncode(detail.title)
    val posts = detail.posts.joinToString("") { post ->
        val meta = "#${post.postNumber} · ${TextUtils.htmlEncode(post.username)} · " +
            shuiyuanTime(post.createdAt)
        "<article><div class=\"meta\">$meta</div>${post.cooked}</article>"
    }
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{background:#0F1115;color:#E6E8EC;font:16px/1.7 -apple-system,system-ui,sans-serif;margin:0;padding:16px 18px 80px}
h1{font-size:20px;line-height:1.45;margin:4px 0 16px}
article{border-bottom:1px solid #23272F;padding:14px 0}
.meta{color:#8B93A1;font-size:12px;margin-bottom:8px}
img{max-width:100%;height:auto;border-radius:10px}
a{color:#9DC2FF;word-break:break-word}
pre,code{background:#1D2129;border-radius:8px;padding:2px 6px;white-space:pre-wrap;word-break:break-word;font-size:14px}
pre{padding:12px}
blockquote{border-left:3px solid #3A4150;margin:8px 0;padding-left:12px;color:#A8B0BC}
video,iframe{max-width:100%}
.onebox,aside.onebox{border:1px solid #23272F;border-radius:10px;padding:10px;margin:8px 0}
</style></head><body><h1>$title</h1>$posts</body></html>"""
}
