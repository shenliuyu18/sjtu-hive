package com.sjtuhive.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.TextUtils
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.theme.LocalIsDark
import org.json.JSONObject
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sjtuhive.app.data.ShuiyuanActivity
import com.sjtuhive.app.data.ShuiyuanApi
import com.sjtuhive.app.data.ShuiyuanBookmark
import com.sjtuhive.app.data.ShuiyuanCategory
import com.sjtuhive.app.data.ShuiyuanCounts
import com.sjtuhive.app.data.ShuiyuanNotification
import com.sjtuhive.app.data.ShuiyuanPost
import java.io.File
import kotlin.math.max
import com.sjtuhive.app.data.ShuiyuanProfile
import com.sjtuhive.app.data.ShuiyuanSite
import com.sjtuhive.app.data.ShuiyuanTag
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.ShuiyuanTopic
import com.sjtuhive.app.data.ShuiyuanTopicDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface ShuiyuanFeed {
    data object Latest : ShuiyuanFeed
    data object Featured : ShuiyuanFeed
    data class Category(val id: Long, val name: String, val slug: String) : ShuiyuanFeed
    data class Tag(val name: String) : ShuiyuanFeed
}

private fun ShuiyuanFeed.label(): String = when (this) {
    ShuiyuanFeed.Latest -> "最新"
    ShuiyuanFeed.Featured -> "精品"
    is ShuiyuanFeed.Category -> name
    is ShuiyuanFeed.Tag -> "#$name"
}

@Composable
fun ShuiyuanScreen(
    context: Context,
    onClose: () -> Unit,
    initialTopicId: Long? = null,
    initialUsername: String? = null,
    initialCategory: String? = null,
) {
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
    var openTopic by remember { mutableLongStateOf(initialTopicId ?: 0L) }
    var profileName by remember { mutableStateOf(initialUsername) }
    var composing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var feed by remember { mutableStateOf<ShuiyuanFeed>(ShuiyuanFeed.Latest) }
    var site by remember { mutableStateOf<ShuiyuanSite?>(null) }
    var feedPicker by remember { mutableStateOf<String?>(null) }
    var counts by remember { mutableStateOf(ShuiyuanCounts()) }
    var showNotifications by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var messageTarget by remember { mutableStateOf<String?>(null) }
    var deepLinkApplied by remember(initialCategory) { mutableStateOf(initialCategory == null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        if (!connected && api.ensureLogin()) connected = true
    }

    fun loadSite() {
        if (site != null) return
        scope.launch {
            runCatching { api.site() }
                .onSuccess { site = it }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: "分类读取失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    LaunchedEffect(connected) {
        if (connected) loadSite()
    }

    LaunchedEffect(connected, reload) {
        if (!connected) return@LaunchedEffect
        runCatching { api.counts() }.onSuccess { counts = it }
    }

    LaunchedEffect(site, initialCategory) {
        val key = initialCategory
        val available = site
        if (deepLinkApplied || key.isNullOrBlank() || available == null) return@LaunchedEffect
        val match = available.categories.firstOrNull {
            it.slug.equals(key, ignoreCase = true) || it.id.toString() == key
        }
        if (match != null) {
            searchQuery = null
            feed = ShuiyuanFeed.Category(match.id, match.name, match.slug)
        }
        deepLinkApplied = true
    }

    LaunchedEffect(connected, reload, searchQuery, feed) {
        if (!connected) return@LaunchedEffect
        loading = true
        error = null
        topics = emptyList()
        val keyword = searchQuery
        val activeFeed = feed
        runCatching {
            when {
                keyword != null -> api.search(keyword, 1)
                activeFeed is ShuiyuanFeed.Featured -> api.featured(0)
                activeFeed is ShuiyuanFeed.Category ->
                    api.categoryTopics(activeFeed.slug, activeFeed.id, 0)
                activeFeed is ShuiyuanFeed.Tag -> api.tagTopics(activeFeed.name, 0)
                else -> api.topics(0)
            }
        }
            .onSuccess {
                topics = it.topics
                hasMore = it.hasMore
                page = if (keyword != null) 1 else 0
            }
            .onFailure { error = it.message ?: "水源社区读取失败" }
        loading = false
    }

    fun submitSearch() {
        val keyword = query.trim()
        if (keyword.isEmpty()) return
        if (keyword == searchQuery) reload++ else searchQuery = keyword
    }

    fun loadMore() {
        if (loading || loadingMore || !hasMore) return
        loadingMore = true
        scope.launch {
            val next = page + 1
            val keyword = searchQuery
            val activeFeed = feed
            runCatching {
                when {
                    keyword != null -> api.search(keyword, next)
                    activeFeed is ShuiyuanFeed.Featured -> api.featured(next)
                    activeFeed is ShuiyuanFeed.Category ->
                        api.categoryTopics(activeFeed.slug, activeFeed.id, next)
                    activeFeed is ShuiyuanFeed.Tag -> api.tagTopics(activeFeed.name, next)
                    else -> api.topics(next)
                }
            }
                .onSuccess {
                    if (searchQuery != keyword || feed != activeFeed) return@onSuccess
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

    BackHandler(
        enabled = showLogin || openTopic != 0L || profileName != null || composing ||
            searchQuery != null || showNotifications || showBookmarks,
    ) {
        when {
            composing -> composing = false
            showLogin -> showLogin = false
            profileName != null -> profileName = null
            openTopic != 0L -> openTopic = 0L
            showNotifications -> showNotifications = false
            showBookmarks -> showBookmarks = false
            else -> {
                searchQuery = null
                query = ""
            }
        }
    }
    BackHandler(
        enabled = !showLogin && openTopic == 0L && profileName == null && !composing &&
            searchQuery == null && !showNotifications && !showBookmarks,
    ) { onClose() }

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
                            searchQuery != null -> "搜索：$searchQuery · ${topics.size} 个话题"
                            else -> "${feed.label()} · ${topics.size} 个话题"
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

            if (connected) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索水源话题") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                TextButton(onClick = {
                                    query = ""
                                    searchQuery = null
                                }) { Text("清除") }
                            }
                        },
                    )
                    TextButton(
                        onClick = { submitSearch() },
                        enabled = query.isNotBlank(),
                    ) { Text("搜索") }
                }
            }

            if (connected) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fun selectFeed(next: ShuiyuanFeed) {
                        query = ""
                        searchQuery = null
                        feed = next
                    }
                    FilterChip(
                        selected = searchQuery == null && feed == ShuiyuanFeed.Latest,
                        onClick = { selectFeed(ShuiyuanFeed.Latest) },
                        label = { Text("最新") },
                    )
                    FilterChip(
                        selected = searchQuery == null && feed == ShuiyuanFeed.Featured,
                        onClick = { selectFeed(ShuiyuanFeed.Featured) },
                        label = { Text("精品") },
                    )
                    val category = feed as? ShuiyuanFeed.Category
                    FilterChip(
                        selected = category != null,
                        onClick = {
                            loadSite()
                            feedPicker = "category"
                        },
                        label = { Text(category?.name ?: "分类") },
                    )
                    val tag = feed as? ShuiyuanFeed.Tag
                    FilterChip(
                        selected = tag != null,
                        onClick = {
                            loadSite()
                            feedPicker = "tag"
                        },
                        label = { Text(tag?.let { "#${it.name}" } ?: "标签") },
                    )
                    TextButton(onClick = { showNotifications = true }) {
                        Text(if (counts.notifications > 0) "通知 ${counts.notifications}" else "通知")
                    }
                    TextButton(onClick = { showBookmarks = true }) { Text("收藏") }
                    TextButton(onClick = { messageTarget = "" }) {
                        Text(if (counts.messages > 0) "私信 ${counts.messages}" else "私信")
                    }
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
            } else if (error != null && topics.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.error)
                        Button(onClick = { reload++ }) { Text("重试") }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (topics.isEmpty() && searchQuery != null) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(32.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "没有找到相关话题",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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

        feedPicker?.let { kind ->
            ShuiyuanFeedPickerDialog(
                kind = kind,
                site = site,
                onDismiss = { feedPicker = null },
                onRetry = { loadSite() },
                onCategory = { category ->
                    query = ""
                    searchQuery = null
                    feed = ShuiyuanFeed.Category(category.id, category.name, category.slug)
                    feedPicker = null
                },
                onTag = { tag ->
                    query = ""
                    searchQuery = null
                    feed = ShuiyuanFeed.Tag(tag.name)
                    feedPicker = null
                },
            )
        }

        if (showNotifications) {
            ShuiyuanNotificationsScreen(
                context = context,
                onBack = { showNotifications = false },
                onRead = { counts = counts.copy(notifications = 0) },
                onOpenTopic = { topicId ->
                    showNotifications = false
                    openTopic = topicId
                },
            )
        }

        if (showBookmarks) {
            ShuiyuanBookmarksScreen(
                context = context,
                onBack = { showBookmarks = false },
                onOpenTopic = { topicId ->
                    showBookmarks = false
                    openTopic = topicId
                },
            )
        }

        messageTarget?.let { target ->
            ShuiyuanMessageDialog(
                context = context,
                initialTarget = target,
                onDismiss = { messageTarget = null },
            )
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
                onOpenTopic = { topicId -> openTopic = topicId },
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
private fun ShuiyuanFeedPickerDialog(
    kind: String,
    site: ShuiyuanSite?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCategory: (ShuiyuanCategory) -> Unit,
    onTag: (ShuiyuanTag) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (kind == "category") "选择分类" else "选择标签") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when {
                    site == null -> Box(
                        Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            TextButton(onClick = onRetry) { Text("重新加载") }
                        }
                    }
                    kind == "category" -> {
                        if (site.categories.isEmpty()) {
                            Text("没有可用的分类")
                        } else {
                            site.categories.forEach { category ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onCategory(category) }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        category.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (category.topicCount > 0) {
                                        Text(
                                            "${category.topicCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        if (site.tags.isEmpty()) {
                            Text("没有可用的标签")
                        } else {
                            site.tags.forEach { tag ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onTag(tag) }
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "#${tag.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (tag.topicCount > 0) {
                                        Text(
                                            "${tag.topicCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ShuiyuanNotificationsScreen(
    context: Context,
    onBack: () -> Unit,
    onRead: () -> Unit,
    onOpenTopic: (Long) -> Unit,
) {
    val api = remember { ShuiyuanApi(context) }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<ShuiyuanNotification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var marking by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching { api.notifications() }
            .onSuccess { items = it }
            .onFailure { error = it.message ?: "通知读取失败" }
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
            Text(
                "水源通知",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = !marking && items.any { !it.read },
                onClick = {
                    marking = true
                    scope.launch {
                        runCatching { api.markNotificationsRead() }
                            .onSuccess {
                                items = items.map { it.copy(read = true) }
                                onRead()
                            }
                            .onFailure { error = it.message ?: "标记失败" }
                        marking = false
                    }
                },
            ) { Text(if (marking) "处理中" else "全部已读") }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        when {
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && items.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reload++ }) { Text("重试") }
                }
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "没有新通知",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { notification ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable(enabled = notification.topicId != null) {
                                notification.topicId?.let(onOpenTopic)
                            }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (notification.read) "" else "● ") + notificationLabel(notification.type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                shuiyuanTime(notification.createdAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            notification.title.ifBlank { "水源社区通知" },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                        )
                        notification.username?.let { name ->
                            Text(
                                "@$name",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (error != null) {
                    item {
                        Text(
                            error ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShuiyuanBookmarksScreen(
    context: Context,
    onBack: () -> Unit,
    onOpenTopic: (Long) -> Unit,
) {
    val api = remember { ShuiyuanApi(context) }
    var items by remember { mutableStateOf<List<ShuiyuanBookmark>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching { api.bookmarks() }
            .onSuccess { items = it }
            .onFailure { error = it.message ?: "收藏读取失败" }
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
            Text(
                "我的收藏",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        when {
            loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && items.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error)
                    Button(onClick = { reload++ }) { Text("重试") }
                }
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有收藏的话题",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.id }) { bookmark ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { onOpenTopic(bookmark.topicId) }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(bookmark.title, style = MaterialTheme.typography.titleSmall)
                        if (bookmark.excerpt.isNotBlank()) {
                            Text(
                                bookmark.excerpt,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        Text(
                            shuiyuanTime(bookmark.createdAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun notificationLabel(type: Int): String = when (type) {
    1 -> "提及"
    2, 9 -> "回复"
    3 -> "引用"
    5 -> "点赞"
    6, 13 -> "私信"
    12 -> "徽章"
    15 -> "关注"
    else -> "通知"
}

@Composable
private fun rememberImagePicker(
    context: Context,
    onPicked: (ByteArray, String) -> Unit,
    onError: (String) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取图片")
                }
            }.onSuccess { bytes ->
                onPicked(bytes, "image_${System.currentTimeMillis()}.jpg")
            }.onFailure {
                onError(it.message ?: "读取图片失败")
            }
        }
    }
    return { launcher.launch("image/*") }
}

private class PendingImage(val bytes: ByteArray, val name: String)

@Composable
private fun ImageConfirmDialog(
    picked: PendingImage,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val bitmap = remember(picked) { decodeImageBytes(picked.bytes, 1600) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送前预览") },
        text = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "待发送图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                )
            } else {
                Text("图片格式暂不支持预览，仍可上传。")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("插入并上传") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ShuiyuanImageViewer(context: Context, url: String, onDismiss: () -> Unit) {
    val api = remember(url) { ShuiyuanApi(context) }
    val scope = rememberCoroutineScope()
    var bytes by remember(url) { mutableStateOf<ByteArray?>(null) }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    var saving by remember(url) { mutableStateOf(false) }
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var viewport by remember(url) { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(url) {
        runCatching { api.imageBytes(url) }
            .onSuccess { data ->
                bytes = data
                bitmap = decodeImageBytes(data)
                if (bitmap == null) error = "图片格式暂不支持，可用浏览器打开原图"
            }
            .onFailure { error = it.message ?: "图片加载失败" }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val image = bitmap
            when {
                error != null -> Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(error ?: "", color = Color.White)
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(absoluteImageUrl(url)))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text("在浏览器打开原图", color = Color.White) }
                }
                image == null -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "图片预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewport = it }
                        .pointerInput(url) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(1f, 6f)
                                scale = next
                                if (next <= 1f) {
                                    offset = Offset.Zero
                                } else {
                                    val maxX = viewport.width * (next - 1f) / 2f
                                    val maxY = viewport.height * (next - 1f) / 2f
                                    offset = Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY),
                                    )
                                }
                            }
                        }
                        .pointerInput(url) {
                            detectTapGestures(
                                onDoubleTap = {
                                    scale = if (scale > 1f) 1f else 2.5f
                                    offset = Offset.Zero
                                },
                            )
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (image != null) {
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            val data = bytes ?: return@TextButton
                            saving = true
                            scope.launch {
                                runCatching {
                                    val name = imageFileName(url)
                                    val file = File(context.cacheDir, "shuiyuan/$name").apply {
                                        parentFile?.mkdirs()
                                        writeBytes(data)
                                    }
                                    saveToDownloads(context, file, name)
                                }.onSuccess {
                                    Toast.makeText(context, "已保存到下载目录", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, it.message ?: "保存失败", Toast.LENGTH_SHORT).show()
                                }
                                saving = false
                            }
                        },
                    ) { Text(if (saving) "保存中…" else "保存", color = Color.White) }
                }
                TextButton(onClick = onDismiss) { Text("关闭", color = Color.White) }
            }
        }
    }
}

private fun absoluteImageUrl(url: String): String =
    if (url.startsWith("http://") || url.startsWith("https://")) url else ShuiyuanApi.ORIGIN + url

private fun imageFileName(url: String): String {
    val raw = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val decoded = runCatching { Uri.decode(raw) }.getOrDefault(raw)
    val name = decoded.ifBlank { "shuiyuan-image.jpg" }
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return if (name.contains('.')) name else "$name.jpg"
}

private fun decodeImageBytes(bytes: ByteArray, maxSide: Int = 2400): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

@Composable
private fun ShuiyuanMessageDialog(
    context: Context,
    initialTarget: String,
    onDismiss: () -> Unit,
) {
    val api = remember { ShuiyuanApi(context) }
    val scope = rememberCoroutineScope()
    var targets by remember(initialTarget) { mutableStateOf(initialTarget) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!sending) onDismiss() },
        title = { Text("发送私信") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = targets,
                    onValueChange = { targets = it },
                    label = { Text("收件人（多个用逗号分隔）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("内容（支持 Markdown）") },
                    minLines = 4,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && targets.isNotBlank() && title.isNotBlank() && body.isNotBlank(),
                onClick = {
                    sending = true
                    error = null
                    scope.launch {
                        val recipients = targets
                            .split(',', '，', ';', ' ', '\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        runCatching { api.sendMessage(recipients, title, body) }
                            .onSuccess {
                                Toast.makeText(context, "私信已发送", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                            .onFailure { error = it.message ?: "发送失败" }
                        sending = false
                    }
                },
            ) { Text(if (sending) "发送中" else "发送") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !sending) { Text("取消") }
        },
    )
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
    onOpenTopic: (Long) -> Unit,
) {
    val api = remember { ShuiyuanApi(context) }
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<ShuiyuanTopicDetail?>(null) }
    var posts by remember { mutableStateOf<List<ShuiyuanPost>>(emptyList()) }
    var stream by remember { mutableStateOf<List<Long>>(emptyList()) }
    var loadingMore by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var reply by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var bookmarked by remember(topicId) { mutableStateOf(false) }
    var uploadingImage by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<PendingImage?>(null) }
    var imageUrl by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    fun uploadPicked(picked: PendingImage) {
        scope.launch {
            uploadingImage = true
            runCatching { api.uploadImage(picked.name, picked.bytes) }
                .onSuccess { short ->
                    reply = if (reply.isBlank()) {
                        "![图片]($short)\n"
                    } else {
                        reply.trimEnd() + "\n\n![图片]($short)\n"
                    }
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: "图片上传失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            uploadingImage = false
        }
    }

    val pickImage = rememberImagePicker(
        context = context,
        onPicked = { bytes, name -> pendingImage = PendingImage(bytes, name) },
        onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
    )

    LaunchedEffect(topicId, reload) {
        loading = true
        error = null
        posts = emptyList()
        stream = emptyList()
        webView = null
        runCatching { ShuiyuanApi(context).topic(topicId) }
            .onSuccess {
                detail = it
                posts = it.posts
                stream = it.stream
                bookmarked = it.bookmarked
            }
            .onFailure { error = it.message ?: "帖子读取失败" }
        loading = false
    }

    // 划到底部时由页面脚本触发：按 stream 里的 id 补下一批楼层，追加到 WebView。
    fun loadMorePosts() {
        if (loadingMore) return
        val remaining = stream.drop(posts.size)
        if (remaining.isEmpty()) {
            webView?.evaluateJavascript("HiveDone()", null)
            return
        }
        loadingMore = true
        scope.launch {
            runCatching { api.posts(topicId, remaining.take(20)) }
                .onSuccess { more ->
                    if (more.isEmpty()) {
                        webView?.evaluateJavascript("HiveDone()", null)
                    } else {
                        posts = posts + more
                        val html = more.joinToString("") { buildPostHtml(it) }
                        webView?.evaluateJavascript(
                            "HiveAppend(${JSONObject.quote(html)})",
                            null,
                        )
                        if (posts.size >= stream.size) {
                            webView?.evaluateJavascript("HiveDone()", null)
                        }
                    }
                }
                .onFailure {
                    webView?.evaluateJavascript("hiveLoading = false;", null)
                    Toast.makeText(context, it.message ?: "加载更多失败", Toast.LENGTH_SHORT).show()
                }
            loadingMore = false
        }
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
            TextButton(
                onClick = {
                    val next = !bookmarked
                    scope.launch {
                        runCatching { api.toggleBookmark(topicId, next) }
                            .onSuccess {
                                bookmarked = it
                                Toast.makeText(
                                    context,
                                    if (it) "已收藏" else "已取消收藏",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    it.message ?: "收藏失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                    }
                },
            ) { Text(if (bookmarked) "已收藏" else "收藏") }
            TextButton(
                onClick = {
                    val url = "${ShuiyuanApi.ORIGIN}/t/$topicId"
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(share, "分享帖子")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
            ) { Text("分享") }
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
                detail != null -> TopicHtmlView(
                    context = context,
                    detail = detail!!,
                    isDark = LocalIsDark.current,
                    onOpenProfile = onOpenProfile,
                    onOpenTopic = onOpenTopic,
                    onLoadMore = { loadMorePosts() },
                    onToggleLike = { postId ->
                        scope.launch {
                            val post = posts.firstOrNull { it.id == postId } ?: return@launch
                            val next = !post.liked
                            runCatching { api.likePost(postId, next) }
                                .onSuccess {
                                    val count = (post.likeCount + if (next) 1 else -1).coerceAtLeast(0)
                                    posts = posts.map {
                                        if (it.id == postId) it.copy(liked = next, likeCount = count) else it
                                    }
                                    webView?.evaluateJavascript(
                                        "HiveSetLike($postId,$next,$count)",
                                        null,
                                    )
                                }
                                .onFailure {
                                    Toast.makeText(
                                        context,
                                        it.message ?: "点赞失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                        }
                    },
                    onOpenImage = { imageUrl = it },
                    onReady = { webView = it },
                )
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
            TextButton(
                onClick = pickImage,
                enabled = !sending && !uploadingImage,
            ) { Text(if (uploadingImage) "上传中" else "图片") }
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
    pendingImage?.let { picked ->
        ImageConfirmDialog(
            picked = picked,
            onDismiss = { pendingImage = null },
            onConfirm = {
                pendingImage = null
                uploadPicked(picked)
            },
        )
    }
    imageUrl?.let { url ->
        ShuiyuanImageViewer(context, url) { imageUrl = null }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TopicHtmlView(
    context: Context,
    detail: ShuiyuanTopicDetail,
    isDark: Boolean,
    onOpenProfile: (String) -> Unit,
    onOpenTopic: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onToggleLike: (Long) -> Unit,
    onOpenImage: (String) -> Unit,
    onReady: (WebView) -> Unit,
) {
    val pageBg = MaterialTheme.colorScheme.background.toArgb()
    val ink = MaterialTheme.colorScheme.onBackground.toArgb()
    val inkMuted = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val rule = MaterialTheme.colorScheme.outlineVariant.toArgb()
    val html = remember(detail, isDark, pageBg, ink, link) {
        buildTopicHtml(detail, isDark, pageBg, ink, inkMuted, link, rule)
    }
    // html 变化（重新拉取）时重建 WebView，否则新内容不会生效。
    key(html) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            WebView(viewContext).apply {
                setBackgroundColor(pageBg)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        val target = url ?: return false
                        // 帖子正文里的链接：水源同源链接在模块内直接切换，
                        // 其他校园站点交给统一路由进对应模块，外部链接才走浏览器。
                        when (val link = HiveLinkRouter.resolve(target)) {
                            is HiveLink.ShuiyuanTopic -> {
                                onOpenTopic(link.id)
                                return true
                            }
                            is HiveLink.ShuiyuanUser -> {
                                onOpenProfile(link.username)
                                return true
                            }
                            is HiveLink.ShuiyuanCategory, is HiveLink.ShuiyuanSearch, HiveLink.Ignore -> {
                                return true
                            }
                            else -> return HiveLinkRouter.open(context, target)
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
                // 滚到底部时由页面里的脚本回调，向 Compose 侧要下一批楼层。
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun loadMore() {
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { onLoadMore() }
                        }

                        @JavascriptInterface
                        fun toggleLike(postId: String) {
                            val id = postId.toLongOrNull() ?: return
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { onToggleLike(id) }
                        }

                        @JavascriptInterface
                        fun openImage(url: String) {
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { onOpenImage(url) }
                        }
                    },
                    "HiveBridge",
                )
                // factory 处于 compose 阶段，直接写状态不安全，投递到主线程队列。
                val created = this
                android.os.Handler(android.os.Looper.getMainLooper()).post { onReady(created) }
                loadDataWithBaseURL(ShuiyuanApi.ORIGIN, html, "text/html", "UTF-8", null)
            }
        },
    )
    }
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
    var uploadingImage by remember { mutableStateOf(false) }
    var pendingImage by remember { mutableStateOf<PendingImage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun uploadPicked(picked: PendingImage) {
        scope.launch {
            uploadingImage = true
            runCatching { api.uploadImage(picked.name, picked.bytes) }
                .onSuccess { short ->
                    raw = if (raw.isBlank()) {
                        "![图片]($short)\n"
                    } else {
                        raw.trimEnd() + "\n\n![图片]($short)\n"
                    }
                }
                .onFailure {
                    Toast.makeText(
                        context,
                        it.message ?: "图片上传失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            uploadingImage = false
        }
    }

    val pickImage = rememberImagePicker(
        context = context,
        onPicked = { bytes, name -> pendingImage = PendingImage(bytes, name) },
        onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
    )

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
            TextButton(onClick = pickImage, enabled = !sending && !uploadingImage) {
                Text(if (uploadingImage) "上传中…" else "插入图片")
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    pendingImage?.let { picked ->
        ImageConfirmDialog(
            picked = picked,
            onDismiss = { pendingImage = null },
            onConfirm = {
                pendingImage = null
                uploadPicked(picked)
            },
        )
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
    var tab by remember(username) { mutableStateOf("topics") }
    var activity by remember(username) { mutableStateOf<List<ShuiyuanActivity>>(emptyList()) }
    var activityLoading by remember(username) { mutableStateOf(false) }
    var activityError by remember(username) { mutableStateOf<String?>(null) }
    var showMessage by remember { mutableStateOf(false) }

    LaunchedEffect(username, tab) {
        if (tab != "activity" || activity.isNotEmpty()) return@LaunchedEffect
        activityLoading = true
        activityError = null
        runCatching { api.activity(username) }
            .onSuccess { activity = it }
            .onFailure { activityError = it.message ?: "动态读取失败" }
        activityLoading = false
    }

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
            TextButton(onClick = { showMessage = true }) { Text("私信") }
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = tab == "topics",
                            onClick = { tab = "topics" },
                            label = { Text("主题") },
                        )
                        FilterChip(
                            selected = tab == "badges",
                            onClick = { tab = "badges" },
                            label = { Text("徽章 ${profile?.badgeCount ?: 0}") },
                        )
                        FilterChip(
                            selected = tab == "activity",
                            onClick = { tab = "activity" },
                            label = { Text("动态") },
                        )
                    }
                }
                when (tab) {
                    "badges" -> {
                        val badges = profile?.badges.orEmpty()
                        if (badges.isEmpty()) {
                            item {
                                Text(
                                    "还没有徽章。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(badges, key = { it.id }) { badge ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(badge.name, style = MaterialTheme.typography.titleSmall)
                                    if (badge.badgeType.isNotBlank()) {
                                        Text(
                                            badge.badgeType,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    if (badge.description.isNotBlank()) {
                                        Text(
                                            badge.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    val meta = listOfNotNull(
                                        badge.grantedAt.take(10).takeIf { it.isNotBlank() },
                                        "×${badge.count}".takeIf { badge.count > 1 },
                                    ).joinToString(" · ")
                                    if (meta.isNotBlank()) {
                                        Text(
                                            meta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    "activity" -> {
                        when {
                            activityLoading && activity.isEmpty() -> item {
                                Box(
                                    Modifier.fillMaxWidth().padding(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }
                            }
                            activityError != null && activity.isEmpty() -> item {
                                Text(
                                    activityError ?: "动态读取失败",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            activity.isEmpty() -> item {
                                Text(
                                    "没有公开的动态。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> items(
                                activity,
                                key = { "${it.topicId}-${it.postNumber}-${it.createdAt}" },
                            ) { entry ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable { onOpenTopic(entry.topicId) }
                                        .padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    if (entry.topicTitle.isNotBlank()) {
                                        Text(
                                            entry.topicTitle,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                        )
                                    }
                                    Text(
                                        entry.blurb,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                    )
                                    Text(
                                        shuiyuanTime(entry.createdAt),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    else -> {
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

    if (showMessage) {
        ShuiyuanMessageDialog(
            context = context,
            initialTarget = username,
            onDismiss = { showMessage = false },
        )
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

private fun buildPostHtml(post: ShuiyuanPost): String {
    val meta = "#${post.postNumber} · ${TextUtils.htmlEncode(post.username)} · " +
        shuiyuanTime(post.createdAt)
    val likeClass = if (post.liked) "hive-like liked" else "hive-like"
    val likeText = if (post.likeCount > 0) "♥ ${post.likeCount}" else "♥ 赞"
    val like = "<button class=\"$likeClass\" data-post-id=\"${post.id}\" " +
        "onclick=\"HiveToggleLike('${post.id}')\">$likeText</button>"
    return "<article><div class=\"meta\">$meta$like</div>${post.cooked}</article>"
}

private fun buildTopicHtml(
    detail: ShuiyuanTopicDetail,
    isDark: Boolean,
    pageBg: Int,
    ink: Int,
    inkMuted: Int,
    link: Int,
    rule: Int,
): String {
    fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)
    val title = TextUtils.htmlEncode(detail.title)
    val posts = detail.posts.joinToString("") { buildPostHtml(it) }
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
body{background:${hex(pageBg)};color:${hex(ink)};font:16px/1.7 -apple-system,system-ui,sans-serif;margin:0;padding:16px 18px 80px}
h1{font-size:20px;line-height:1.45;margin:4px 0 16px}
article{border-bottom:1px solid ${hex(rule)};padding:14px 0}
.meta{color:${hex(inkMuted)};font-size:12px;margin-bottom:8px}
.hive-like{float:right;background:transparent;border:1px solid ${hex(rule)};border-radius:12px;color:${hex(inkMuted)};font-size:12px;line-height:1;padding:4px 8px;margin-left:8px}
.hive-like.liked{color:#E0245E;border-color:#E0245E}
img{max-width:100%;height:auto;border-radius:10px;cursor:zoom-in}
.lightbox-wrapper .meta,.lightbox .meta,.lightbox-wrapper .filename,.lightbox-wrapper .informations{display:none}
a{color:${hex(link)};word-break:break-word}
pre,code{background:${hex(rule)};border-radius:8px;padding:2px 6px;white-space:pre-wrap;word-break:break-word;font-size:14px}
pre{padding:12px}
blockquote{border-left:3px solid #3A4150;margin:8px 0;padding-left:12px;color:#A8B0BC}
video,iframe{max-width:100%}
.onebox,aside.onebox{border:1px solid #23272F;border-radius:10px;padding:10px;margin:8px 0}
</style></head><body><h1>$title</h1><div id="hive-posts">$posts</div><div id="hive-end"></div>
<script>
var hiveLoading = false, hiveDone = false;
function HiveAppend(html) {
  var end = document.getElementById('hive-end');
  if (end) { end.insertAdjacentHTML('beforebegin', html); }
  HiveClean(document);
  hiveLoading = false;
}
function HiveDone() {
  hiveDone = true;
  var end = document.getElementById('hive-end');
  if (end) { end.textContent = '已经到底了'; }
}
function HiveToggleLike(id) {
  if (window.HiveBridge) { window.HiveBridge.toggleLike(String(id)); }
}
function HiveSetLike(id, liked, count) {
  var buttons = document.querySelectorAll('.hive-like');
  for (var i = 0; i < buttons.length; i++) {
    var button = buttons[i];
    if (button.getAttribute('data-post-id') === String(id)) {
      button.className = liked ? 'hive-like liked' : 'hive-like';
      button.textContent = count > 0 ? ('♥ ' + count) : '♥ 赞';
    }
  }
}
function HiveClean(root) {
  var scope = root || document;
  var anchors = scope.querySelectorAll('a.lightbox, a[href]');
  for (var i = anchors.length - 1; i >= 0; i--) {
    var anchor = anchors[i];
    var image = anchor.querySelector('img');
    if (!image) { continue; }
    var href = anchor.getAttribute('href') || '';
    var imageLink = anchor.classList.contains('lightbox') ||
      /\.(png|jpe?g|gif|webp|bmp|svg)([?#]|$)/i.test(href);
    if (!imageLink) { continue; }
    anchor.parentNode.replaceChild(image, anchor);
  }
  var metas = scope.querySelectorAll('.lightbox-wrapper .meta, .lightbox .meta, .lightbox-wrapper .filename, .lightbox-wrapper .informations');
  for (var j = 0; j < metas.length; j++) { metas[j].remove(); }
}
HiveClean(document);
window.addEventListener('scroll', function () {
  if (hiveLoading || hiveDone) { return; }
  if (window.innerHeight + window.scrollY >= document.body.scrollHeight - 320) {
    hiveLoading = true;
    if (window.HiveBridge) { window.HiveBridge.loadMore(); }
  }
}, { passive: true });
document.addEventListener('click', function (event) {
  var target = event.target;
  if (!target || target.tagName !== 'IMG') { return; }
  var src = target.currentSrc || target.src;
  if (!src) { return; }
  event.preventDefault();
  event.stopPropagation();
  if (window.HiveBridge && window.HiveBridge.openImage) { window.HiveBridge.openImage(src); }
}, true);
</script></body></html>"""
}
