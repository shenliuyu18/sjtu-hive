package com.sjtuhive.app.ui

import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.sjtuhive.app.data.PortalServicesApi
import com.sjtuhive.app.data.ServiceApp
import com.sjtuhive.app.data.ServiceCatalog
import com.sjtuhive.app.data.ServiceProcess
import com.sjtuhive.app.data.ServiceProcessPage
import kotlinx.coroutines.launch
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val processTime = DateTimeFormatter.ofPattern("MM-dd HH:mm")

@Composable
fun ServicesScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onFullscreenChange: (Boolean) -> Unit = {},
) {
    val api = remember { PortalServicesApi(context) }
    val icons = remember { mutableMapOf<Int, String?>() }
    var iconVersion by remember { mutableIntStateOf(0) }
    val iconLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(SvgDecoder.Factory())
                add(DataUriFetcher.Factory())
            }
            .build()
    }
    var catalog by remember { mutableStateOf<ServiceCatalog?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("todo") }
    var processes by remember { mutableStateOf<List<ServiceProcess>>(emptyList()) }
    var processTotal by remember { mutableIntStateOf(0) }
    var processLoading by remember { mutableStateOf(false) }
    var processError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var formTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(formTarget) { onFullscreenChange(formTarget != null) }
    DisposableEffect(Unit) { onDispose { onFullscreenChange(false) } }
    LaunchedEffect(kind) {
        processes = emptyList()
        processTotal = 0
    }

    LaunchedEffect(loggedIn, reload) {
        if (!loggedIn) return@LaunchedEffect
        loading = true
        error = null
        runCatching { api.catalog() }
            .onSuccess { catalog = it }
            .onFailure { error = it.message ?: "读取办事目录失败" }
        loading = false
    }

    LaunchedEffect(catalog) {
        val ids = catalog?.apps?.map { it.iconId }?.filter { it > 0 }?.distinct().orEmpty()
        val missing = ids.filter { it !in icons }
        if (missing.isEmpty()) return@LaunchedEffect
        val loaded = runCatching { api.icons(missing) }.getOrDefault(emptyMap())
        if (loaded.isEmpty()) return@LaunchedEffect
        icons.putAll(loaded)
        iconVersion++
    }

    LaunchedEffect(loggedIn, kind, reload) {
        if (!loggedIn) return@LaunchedEffect
        processLoading = true
        processError = null
        runCatching { api.processes(kind) }
            .onSuccess {
                processes = it.items
                processTotal = it.total
            }
            .onFailure { processError = it.message ?: "读取流程失败" }
        processLoading = false
    }

    if (!loggedIn) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("办事大厅", style = MaterialTheme.typography.headlineMedium)
            Text(
                "登录 jAccount 后查看交我办的应用目录与在办流程。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onLogin) { Text("登录 jAccount") }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("办事大厅", style = MaterialTheme.typography.headlineMedium)
                Text(
                    when {
                        loading -> "正在读取…"
                        error != null -> error.orEmpty()
                        else -> "交我办 · ${catalog?.apps?.size ?: 0} 个应用"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { reload++ }, enabled = !loading) { Text("刷新") }
        }

        if (loading && catalog == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text("搜索办事应用") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            catalog?.recent?.takeIf { it.isNotEmpty() }?.let { recent ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "最近使用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recent.take(6).forEach { item ->
                                Text(
                                    item.itemName,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .clickable {
                                            scope.launch {
                                                runCatching { api.touchRecent(item.itemId, item.itemName, item.url) }
                                                if (validFormUrl(item.url)) formTarget = item.itemName to item.url
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            catalog?.categories?.takeIf { it.isNotEmpty() }?.let { categories ->
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CategoryChip("全部", category.isEmpty()) { category = "" }
                        categories.forEach { item ->
                            CategoryChip("${item.title} ${item.count}", category == item.title) {
                                category = if (category == item.title) "" else item.title
                            }
                        }
                    }
                }
            }
            val apps = filteredApps(catalog, query, category)
            if (apps.isEmpty()) {
                item { Text("没有匹配的办事应用。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(apps.chunked(2)) { rowApps ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowApps.forEach { app ->
                            AppCard(
                                app = app,
                                iconUrl = remember(iconVersion, app.iconId) { icons[app.iconId] },
                                imageLoader = iconLoader,
                                modifier = Modifier.weight(1f),
                                onOpen = {
                                    if (!validFormUrl(app.uri)) {
                                        serviceToast(context, "这个条目没有可打开的办理地址")
                                        return@AppCard
                                    }
                                    scope.launch {
                                        runCatching { api.touchRecent(app.id, app.name, app.uri) }
                                        formTarget = app.name to app.uri
                                    }
                                },
                                onFavorite = {
                                    scope.launch {
                                        val next = !app.favorite
                                        runCatching { api.setFavorite(app.id, next) }
                                            .onSuccess {
                                                catalog = catalog?.let { current ->
                                                    current.copy(
                                                        apps = current.apps.map {
                                                            if (it.id == app.id) it.copy(favorite = next) else it
                                                        },
                                                    )
                                                }
                                            }
                                            .onFailure { serviceToast(context, it.message ?: "收藏失败") }
                                    }
                                },
                            )
                        }
                        if (rowApps.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("todo" to "在办", "completed" to "已办", "cc" to "抄送").forEach { (id, label) ->
                        TextButton(onClick = { kind = id }, enabled = kind != id) { Text(label) }
                    }
                }
            }
            when {
                processLoading && processes.isEmpty() -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                processError != null -> item {
                    Text("读取流程失败：$processError", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                processes.isEmpty() -> item {
                    Text(
                        when (kind) {
                            "todo" -> "没有待办流程。"
                            "completed" -> "还没有已办记录。"
                            else -> "没有抄送给你的流程。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    items(processes, key = { "${kind}-${it.id}" }) { process ->
                        ProcessRow(process) {
                            if (!validFormUrl(process.entry)) {
                                serviceToast(context, "这个流程没有可打开的办理地址")
                                return@ProcessRow
                            }
                            formTarget = process.name to process.entry
                        }
                    }
                    if ((kind == "completed" || kind == "cc") &&
                        (processTotal < 0 || processes.size < processTotal)
                    ) {
                        item {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            processLoading = true
                                            runCatching {
                                                val page: ServiceProcessPage =
                                                    api.processes(kind, 20, processes.size)
                                                processes = processes + page.items
                                                processTotal = page.total
                                            }.onFailure { processError = it.message }
                                            processLoading = false
                                        }
                                    },
                                    enabled = !processLoading,
                                ) { Text(if (processLoading) "加载中…" else "加载更多") }
                            }
                        }
                    }
                }
            }
        }
    }

    formTarget?.let { (name, url) ->
        BackHandler { formTarget = null }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            LoginWebView(startUrl = url, onWebView = { view ->
                view.settings.userAgentString = com.sjtuhive.app.data.HiveHttp.USER_AGENT
            })
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun AppCard(
    app: ServiceApp,
    iconUrl: String?,
    imageLoader: ImageLoader,
    modifier: Modifier,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (iconUrl != null) {
                val iconContext = LocalContext.current
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(iconContext).data(iconUrl).crossfade(true).build(),
                    imageLoader = imageLoader,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp),
                    loading = { AppIconFallback(app.name) },
                    error = { AppIconFallback(app.name) },
                )
            } else {
                AppIconFallback(app.name)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                app.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.topCategoryName.ifBlank { app.department }.ifBlank { "校园服务" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (app.favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
            contentDescription = if (app.favorite) "取消收藏" else "收藏",
            modifier = Modifier.size(18.dp).clickable(onClick = onFavorite),
            tint = if (app.favorite) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProcessRow(process: ServiceProcess, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(process.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    process.appName.takeIf { it.isNotBlank() },
                    process.stepName.takeIf { it.isNotBlank() },
                    process.percent.takeIf { it > 0 }?.let { "$it%" },
                    process.updatedAt?.let {
                        processTime.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun filteredApps(catalog: ServiceCatalog?, query: String, category: String): List<ServiceApp> {
    val apps = catalog?.apps ?: return emptyList()
    val keyword = query.trim().lowercase()
    return apps.asSequence()
        .filter { category.isEmpty() || it.topCategoryName == category || it.categoryName == category }
        .filter {
            keyword.isEmpty() || listOf(
                it.name, it.department, it.categoryName, it.topCategoryName,
            ).joinToString(" ").lowercase().contains(keyword)
        }
        .sortedWith(compareBy({ !it.favorite }, { it.name }))
        .toList()
}

@Composable
private fun AppIconFallback(name: String) {
    Text(name.take(1), style = MaterialTheme.typography.labelMedium)
}

private fun serviceToast(context: Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
}

private fun validFormUrl(raw: String): Boolean {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return host == "sjtu.edu.cn" || host.endsWith(".sjtu.edu.cn")
}
