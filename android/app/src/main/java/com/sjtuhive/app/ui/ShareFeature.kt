package com.sjtuhive.app.ui

import android.content.Context
import android.webkit.WebView
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.ShareApi
import com.sjtuhive.app.data.ShareAuthException
import com.sjtuhive.app.data.ShareCourse
import com.sjtuhive.app.data.ShareMaterial
import com.sjtuhive.app.data.safeShareFileName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ShareScreen(context: Context, onClose: () -> Unit) {
    val api = remember { ShareApi(context) }
    val scope = rememberCoroutineScope()
    var loggedIn by remember { mutableStateOf(api.loggedIn()) }
    var showLogin by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var courses by remember { mutableStateOf<List<ShareCourse>>(emptyList()) }
    var selectedCourse by remember { mutableStateOf<ShareCourse?>(null) }
    var materials by remember { mutableStateOf<List<ShareMaterial>>(emptyList()) }
    var detail by remember { mutableStateOf<ShareMaterial?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingPurchase by remember { mutableStateOf<ShareMaterial?>(null) }
    var reload by remember { mutableStateOf(0) }

    fun handleError(cause: Throwable) {
        if (cause is ShareAuthException) {
            api.logout()
            loggedIn = false
        }
        error = cause.message ?: "读取失败"
    }

    fun search() {
        if (keyword.isBlank()) return
        scope.launch {
            loading = true
            error = null
            selectedCourse = null
            materials = emptyList()
            detail = null
            runCatching { api.searchCourses(keyword) }
                .onSuccess { courses = it }
                .onFailure { handleError(it) }
            loading = false
        }
    }

    fun openCourse(course: ShareCourse) {
        scope.launch {
            loading = true
            error = null
            selectedCourse = course
            detail = null
            runCatching { api.materials(course.id) }
                .onSuccess { materials = it }
                .onFailure { handleError(it) }
            loading = false
        }
    }

    fun openMaterial(material: ShareMaterial) {
        scope.launch {
            loading = true
            error = null
            runCatching { api.material(material.id) ?: material }
                .onSuccess { detail = it }
                .onFailure { handleError(it) }
            loading = false
        }
    }

    fun purchase(material: ShareMaterial) {
        scope.launch {
            loading = true
            error = null
            runCatching { api.purchase(material.id) }
                .onSuccess {
                    val updated = material.copy(hasPurchased = true)
                    detail = updated
                    materials = materials.map { if (it.id == material.id) updated else it }
                    Toast.makeText(
                        context,
                        if ((material.points ?: 0) > 0) "兑换成功，可以打开或下载" else "已免费领取，可以打开或下载",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                .onFailure { handleError(it) }
            loading = false
        }
    }

    fun openFile(material: ShareMaterial) {
        val fileName = safeShareFileName(material.fileName, material.title.ifBlank { "share-${material.id}" })
        PreviewHost.open(
            PreviewRequest(
                title = material.title,
                fileName = fileName,
                load = { api.downloadToCache(material.id, fileName) },
                download = {
                    val url = api.download(material.id)
                    if (url.isBlank()) throw IllegalStateException("没有获取到下载链接")
                    startDownload(context, url, fileName)
                    "已开始下载到系统下载目录"
                },
            ),
        )
    }

    fun save(material: ShareMaterial) {
        scope.launch {
            loading = true
            runCatching { api.download(material.id) }
                .onSuccess { url ->
                    if (url.isBlank()) {
                        error = "没有获取到下载链接"
                    } else {
                        runCatching {
                            startDownload(context, url, safeShareFileName(material.fileName, material.title.ifBlank { "share-${material.id}" }))
                            Toast.makeText(context, "已开始下载到系统下载目录", Toast.LENGTH_SHORT).show()
                        }.onFailure { error = it.message ?: "下载失败" }
                    }
                }
                .onFailure { handleError(it) }
            loading = false
        }
    }

    BackHandler {
        when {
            showLogin -> showLogin = false
            detail != null -> detail = null
            selectedCourse != null -> selectedCourse = null
            else -> onClose()
        }
    }

    if (showLogin) {
        WebLogin(
            onClose = { showLogin = false },
            onToken = { token ->
                api.saveToken(token)
                loggedIn = true
                showLogin = false
                error = null
                reload++
            },
        )
        return
    }

    pendingPurchase?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingPurchase = null },
            title = { Text("兑换资料") },
            text = { Text("该资料需要 ${target.points ?: 0} 积分兑换，确定继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPurchase = null
                    purchase(target)
                }) { Text("兑换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPurchase = null }) { Text("取消") }
            },
        )
    }

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
            TextButton(onClick = {
                when {
                    detail != null -> detail = null
                    selectedCourse != null -> selectedCourse = null
                    else -> onClose()
                }
            }) { Text("← 返回") }
            Column(Modifier.weight(1f)) {
                Text("传承交大", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (loggedIn) "share.dyweb.sjtu.cn · 只读" else "jAccount 登录后可浏览",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loggedIn) {
                TextButton(onClick = {
                    api.logout()
                    loggedIn = false
                    courses = emptyList()
                    materials = emptyList()
                    detail = null
                    selectedCourse = null
                }) { Text("退出") }
            }
        }

        if (!loggedIn) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("登录传承交大", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "使用 jAccount 登录 share.dyweb.sjtu.cn。登录在应用内 WebView 中完成，站点令牌只保存在本机。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { showLogin = true }) { Text("使用 jAccount 登录") }
            }
            return@Column
        }

        if (detail != null) {
            MaterialDetail(
                material = detail!!,
                loading = loading,
                onPurchase = { material ->
                    if ((material.points ?: 0) > 0) pendingPurchase = material else purchase(material)
                },
                onOpen = { openFile(detail!!) },
                onSave = { save(detail!!) },
            )
            return@Column
        }

        if (selectedCourse != null) {
            Text(
                "${selectedCourse!!.name} · ${materials.size} 份资料",
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                style = MaterialTheme.typography.titleSmall,
            )
            if (loading && materials.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(materials, key = { it.id }) { item ->
                        InfoRow(item.title, item.subtitle) { openMaterial(item) }
                    }
                    error?.let { message ->
                        item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            return@Column
        }

        OutlinedTextField(
            value = keyword,
            onValueChange = { keyword = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("搜索课程名或课程代码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
        )
        if (loading && courses.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(courses, key = { it.id }) { course ->
                    InfoRow(course.name, course.subtitle) { openCourse(course) }
                }
                if (courses.isEmpty() && error == null) {
                    item {
                        Text(
                            "输入课程名或课程代码开始搜索。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                error?.let { message ->
                    item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MaterialDetail(
    material: ShareMaterial,
    loading: Boolean,
    onPurchase: (ShareMaterial) -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(material.title, style = MaterialTheme.typography.headlineSmall)
        Text(material.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            material.description.ifBlank { "暂无描述。" },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(14.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (material.hasPurchased) {
                Button(onClick = onOpen, enabled = !loading) { Text("打开") }
                Button(onClick = onSave, enabled = !loading) { Text("下载") }
            } else {
                Button(onClick = { onPurchase(material) }, enabled = !loading) {
                    if (loading) CircularProgressIndicator(Modifier.padding(end = 8.dp)) else Unit
                    Text(if ((material.points ?: 0) > 0) "${material.points} 积分兑换" else "免费领取")
                }
            }
            material.points?.let { Text("$it 积分", color = MaterialTheme.colorScheme.tertiary) }
        }
        if (!material.hasPurchased) {
            Text(
                "领取后即可打开或下载；0 积分资料免费领取。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WebLogin(onClose: () -> Unit, onToken: (String) -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var handled by remember { mutableStateOf(false) }
    var clicked by remember { mutableStateOf(false) }

    LaunchedEffect(webView) {
        while (!handled) {
            val view = webView
            if (view != null) {
                if (!clicked) {
                    view.evaluateJavascript("(function(){var b=Array.from(document.querySelectorAll('button')).find(function(x){return (x.innerText||'').indexOf('jAccount')>=0});if(b){b.click();return 'clicked'}return 'none'})()") { value -> if (value.orEmpty().contains("clicked")) clicked = true }
                }
                view.evaluateJavascript("localStorage.getItem('token')") { result ->
                    val token = result?.trim()?.trim('"').orEmpty()
                    if (!handled && token.isNotEmpty() && token != "null") {
                        handled = true
                        onToken(token)
                    }
                }
            }
            delay(1000)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LoginWebView(
            startUrl = "https://share.dyweb.sjtu.cn/",
            onWebView = { view ->
                webView = view
                view.settings.useWideViewPort = true
                view.settings.loadWithOverviewMode = true
            },
        )
        TextButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) { Text("关闭") }
    }
}
