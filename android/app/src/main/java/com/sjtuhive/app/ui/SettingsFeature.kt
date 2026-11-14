package com.sjtuhive.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import com.sjtuhive.app.data.HiveCacheManager
import com.sjtuhive.app.data.HiveCacheCategory
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sjtuhive.app.R
import com.sjtuhive.app.data.AndroidUpdate
import com.sjtuhive.app.data.HiveNotifications
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.HiveThemePrefs
import com.sjtuhive.app.data.UpdateApi
import com.sjtuhive.app.ui.theme.HiveAccent
import io.legado.app.ui.theme.LocalIsDark
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class SettingsSection { None, Account, Appearance, Labs, Storage, About }

@Composable
fun SettingsScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    themeMode: String,
    accentId: String,
    customHue: Double,
    customSat: Float,
    customVal: Float,
    bottomBarDocked: Boolean,
    onThemeModeChange: (String) -> Unit,
    onAccentChange: (String) -> Unit,
    onCustomHueChange: (Double) -> Unit,
    onCustomSvChange: (Float, Float) -> Unit,
    onCustomHexChange: (String) -> Unit,
    onBottomBarDockedChange: (Boolean) -> Unit,
    blurRadius: Int,
    blurAlpha: Int,
    onBlurRadiusChange: (Int) -> Unit,
    onBlurAlphaChange: (Int) -> Unit,
    servicesEnabled: Boolean,
    onServicesEnabledChange: (Boolean) -> Unit,
) {
    var section by remember { mutableStateOf(SettingsSection.None) }
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

    BackHandler(enabled = section != SettingsSection.None) { section = SettingsSection.None }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 120.dp),
    ) {
        when (section) {
            SettingsSection.None -> {
                // ══ 一级：品牌头部 + 紧凑菜单 ══
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(id = R.mipmap.ic_launcher),
                        contentDescription = null,
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(30.dp)),
                    )
                    Spacer(Modifier.height(18.dp))
                    Text("SJTU Hive", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "交大人的掌上工具箱：日程、邮箱、课堂与校园服务。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(26.dp))
                SettingsMenuRow(Icons.Outlined.AccountCircle, "账号与登录") {
                    section = SettingsSection.Account
                }
                MenuDivider()
                SettingsMenuRow(Icons.Outlined.Palette, "外观与底栏") {
                    section = SettingsSection.Appearance
                }
                MenuDivider()
                SettingsMenuRow(Icons.Outlined.Build, "测试功能") {
                    section = SettingsSection.Labs
                }
                MenuDivider()
                SettingsMenuRow(Icons.Outlined.CleaningServices, "存储与缓存") {
                    section = SettingsSection.Storage
                }
                MenuDivider()
                SettingsMenuRow(Icons.Outlined.Info, "关于") {
                    section = SettingsSection.About
                }
            }

            SettingsSection.Account -> SettingsSubPage("账号与登录", { section = SettingsSection.None }) {
                SettingGroup("jAccount") {
                    SettingInfoRow(if (loggedIn) "已登录，会话保存在本机" else "未登录")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (loggedIn) {
                            OutlinedButton(onClick = { confirmLogout = true }) { Text("退出登录") }
                            Button(onClick = onLogin) { Text("重新登录") }
                        } else {
                            Button(onClick = onLogin) { Text("登录 jAccount") }
                        }
                    }
                }
            }

            SettingsSection.Appearance -> SettingsSubPage("外观与底栏", { section = SettingsSection.None }) {
                SettingGroup("明暗") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            HiveThemePrefs.MODE_SYSTEM to "跟随系统",
                            HiveThemePrefs.MODE_LIGHT to "浅色",
                            HiveThemePrefs.MODE_DARK to "深色",
                        ).forEach { (id, label) ->
                            FilterChip(
                                selected = themeMode == id,
                                onClick = { onThemeModeChange(id) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                SettingGroup("配色") {
                    val isDarkTheme = LocalIsDark.current
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HiveAccent.presets.forEach { preset ->
                            val swatch = if (isDarkTheme) preset.seedDark else preset.seed
                            val selected = accentId == preset.id
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(swatch))
                                    .then(
                                        if (selected) Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        ) else Modifier
                                    )
                                    .clickable { onAccentChange(preset.id) },
                            )
                        }
                        val customAccent = HiveAccent.derive(
                            HiveAccent.hsvToColor(customHue, customSat, customVal),
                        )
                        val customSwatch = if (isDarkTheme) customAccent.seedDark else customAccent.seed
                        val customSelected = accentId == HiveAccent.CUSTOM_ID
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF4FC3F7)),
                                    ),
                                )
                                .then(
                                    if (customSelected) Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = CircleShape,
                                    ) else Modifier
                                )
                                .clickable { onAccentChange(HiveAccent.CUSTOM_ID) },
                        )
                    }
                    if (accentId == HiveAccent.CUSTOM_ID) {
                        Spacer(Modifier.height(10.dp))
                        Text("色相", style = MaterialTheme.typography.labelSmall)
                        HiveHueBar(hue = customHue.toFloat()) { onCustomHueChange(it.toDouble()) }
                        Text("深浅（左灰右艳，上亮下暗）", style = MaterialTheme.typography.labelSmall)
                        HiveColorPad(
                            hue = customHue.toFloat(),
                            sat = customSat,
                            value = customVal,
                            onSvChange = onCustomSvChange,
                        )
                        var hexInput by remember { mutableStateOf("") }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = hexInput,
                                onValueChange = { hexInput = it },
                                label = { Text("颜色编号（如 1E88E5）") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = {
                                    val hex = hexInput.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
                                    if (hex.length == 6 && hex.all { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }) {
                                        onCustomHexChange(hex)
                                        hexInput = ""
                                    }
                                },
                            ) { Text("应用") }
                        }
                    }
                }
                SettingGroup("底栏") {
                    SettingSwitchRow(
                        title = "悬浮玻璃底栏",
                        subtitle = "关闭后使用停靠式纯色底栏",
                        checked = !bottomBarDocked,
                        onChange = { onBottomBarDockedChange(!it) },
                    )
                    SettingSliderRow(
                        label = "模糊强度",
                        valueText = "${blurRadius}dp",
                        value = blurRadius.toFloat(),
                        onValueChange = { onBlurRadiusChange(it.roundToInt()) },
                        valueRange = 0f..48f,
                    )
                    SettingSliderRow(
                        label = "不透明度",
                        valueText = "$blurAlpha%",
                        value = blurAlpha / 100f,
                        onValueChange = { onBlurAlphaChange((it * 100).roundToInt()) },
                        valueRange = 0.10f..0.90f,
                    )
                    SettingInfoRow("玻璃折射需要 Android 13+；更低版本自动退为半透明纯色。")
                }
            }

            SettingsSection.Labs -> SettingsSubPage("测试功能", { section = SettingsSection.None }) {
                SettingGroup("实验性功能") {
                    SettingSwitchRow(
                        title = "办事大厅",
                        subtitle = "在底部页签显示交我办办事入口；功能仍在测试，默认关闭",
                        checked = servicesEnabled,
                        onChange = onServicesEnabledChange,
                    )
                }
            }

            SettingsSection.Storage -> SettingsSubPage("存储与缓存", { section = SettingsSection.None }) {
                var enabledAuto by remember { mutableStateOf(HiveCacheManager.enabledCategories(context)) }
                var scheduleValue by remember { mutableStateOf(HiveCacheManager.schedule(context)) }
                // 缓存大小是普通函数计算，不参与状态；清理后靠这个 tick 强制重算
                var refreshTick by remember { mutableIntStateOf(0) }
                SettingGroup("缓存明细（勾选 = 纳入自动清除）") {
                    val totalText = remember(refreshTick) {
                        formatBytes(HiveCacheManager.totalSize(context))
                    }
                    SettingInfoRow("当前总占用：$totalText")
                    HiveCacheCategory.entries.forEach { category ->
                        val sizeText = remember(refreshTick, category) {
                            formatBytes(HiveCacheManager.sizeOf(context, category))
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(category.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    sizeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Checkbox(
                                checked = category.id in enabledAuto,
                                onCheckedChange = { checked ->
                                    enabledAuto = if (checked) {
                                        enabledAuto + category.id
                                    } else {
                                        enabledAuto - category.id
                                    }
                                    HiveCacheManager.setEnabledCategories(context, enabledAuto)
                                },
                            )
                        }
                    }
                }
                SettingGroup("自动清除时机") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            HiveCacheManager.SCHEDULE_NEVER to "关闭",
                            HiveCacheManager.SCHEDULE_ON_START to "每次启动",
                            HiveCacheManager.SCHEDULE_DAILY to "每天一次",
                            HiveCacheManager.SCHEDULE_WEEKLY to "每周一次",
                        ).forEach { (id, label) ->
                            FilterChip(
                                selected = scheduleValue == id,
                                onClick = {
                                    scheduleValue = id
                                    HiveCacheManager.setSchedule(context, id)
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    SettingInfoRow("达到时机后，应用启动时会静默清除勾选的类别；登录状态、设置与下载好的文件不受影响。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = enabledAuto.isNotEmpty(),
                            onClick = {
                                val targets = enabledAuto
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        HiveCacheManager.clear(context, targets)
                                    }
                                                    refreshTick++
                                    Toast.makeText(context, "已清除勾选的缓存", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) { Text("立即清除勾选项") }
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        HiveCacheManager.clear(
                                            context,
                                            HiveCacheCategory.entries.map { it.id }.toSet(),
                                        )
                                    }
                                                    refreshTick++
                                    Toast.makeText(context, "已清除全部缓存", Toast.LENGTH_SHORT).show()
                                }
                            },
                        ) { Text("全部清除") }
                    }
                }
            }

            SettingsSection.About -> SettingsSubPage("关于", { section = SettingsSection.None }) {
                SettingGroup("版本") {
                    SettingInfoRow("SJTU Hive ${version.ifBlank { "0.1.0" }} · Android")
                    SettingInfoRow(
                        updateMessage.ifBlank {
                            "从 GitHub Releases 检查正式版更新，只下载 APK，不上传任何数据。"
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        TextButton(onClick = { updateApi.openReleasePage(updateInfo?.pageUrl.orEmpty()) }) {
                            Text("发布页")
                        }
                    }
                }
                SettingGroup("通知") {
                    SettingActionRow("测试通知", "发送") {
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
                            Toast.makeText(context, "系统通知已在系统设置中被关闭", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                SettingGroup("开源") {
                    SettingInfoRow("本应用以 GPL-3.0 开源；玻璃组件改编自 legado-with-MD3，配色规则参考 dsh-Go。")
                    SettingLinkRow("本应用源码", "https://github.com/shenliuyu18/sjtu-hive") {
                        runCatching { context.startActivity(it) }
                    }
                    SettingLinkRow("legado-with-MD3 源码", "https://github.com/HapeLee/legado-with-MD3") {
                        runCatching { context.startActivity(it) }
                    }
                }
            }
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
                            android.webkit.CookieManager.getInstance().removeAllCookies(null)
                            android.webkit.CookieManager.getInstance().flush()
                        }
                        onLogout()
                        section = SettingsSection.None
                    },
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

// ══ 设置页组件 ════════════════════════════════════════════════════

/** 一级菜单行：56dp 紧凑行，无副标题。 */
@Composable
private fun SettingsMenuRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable { onClick() }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
}

/** 二级页骨架：返回行 + 内容（外层 BackHandler 已处理系统返回键）。 */
@Composable
private fun SettingsSubPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

/** 分组：小标题 + 内容 + 细分隔线。 */
@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(top = 16.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
    Spacer(Modifier.height(10.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun SettingSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingSliderRow(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}

@Composable
private fun SettingInfoRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    )
}

@Composable
private fun SettingActionRow(title: String, buttonText: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Button(onClick = onAction) { Text(buttonText) }
    }
}

@Composable
private fun SettingLinkRow(title: String, url: String, open: (Intent) -> Unit) {
    TextButton(onClick = {
        open(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }) { Text(title) }
}

/** 彩虹色相条。 */
@Composable
private fun HiveHueBar(hue: Float, onHueChange: (Float) -> Unit) {
    var barWidth by remember { mutableStateOf(1f) }

    fun apply(offset: Offset) {
        onHueChange((offset.x / barWidth * 360f).coerceIn(0f, 359.9f))
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .onSizeChanged { barWidth = it.width.coerceAtLeast(1).toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { offset -> apply(offset) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> apply(offset) },
                    onDrag = { change, _ -> apply(change.position) },
                )
            },
    ) {
        val rainbow = List(13) { i ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, 1f, 1f)))
        }
        drawRect(Brush.horizontalGradient(rainbow))
        drawCircle(
            color = Color.White,
            radius = 8.dp.toPx(),
            center = Offset((hue / 360f) * size.width, size.height / 2),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

/** 二维调色盘：横轴饱和度、纵轴明度。 */
@Composable
private fun HiveColorPad(
    hue: Float,
    sat: Float,
    value: Float,
    onSvChange: (Float, Float) -> Unit,
) {
    var padWidth by remember { mutableStateOf(1f) }
    var padHeight by remember { mutableStateOf(1f) }
    val baseColor = android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f))

    fun apply(offset: Offset) {
        val s = (offset.x / padWidth).coerceIn(0f, 1f)
        val v = 1f - (offset.y / padHeight).coerceIn(0f, 1f)
        onSvChange(s, v)
    }

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(14.dp))
            .onSizeChanged {
                padWidth = it.width.coerceAtLeast(1).toFloat()
                padHeight = it.height.coerceAtLeast(1).toFloat()
            }
            .pointerInput(hue) {
                detectTapGestures { offset -> apply(offset) }
            }
            .pointerInput(hue) {
                detectDragGestures(
                    onDragStart = { offset -> apply(offset) },
                    onDrag = { change, _ -> apply(change.position) },
                )
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color(baseColor))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(
            color = Color.White,
            radius = 9.dp.toPx(),
            center = Offset(sat * size.width, (1f - value) * size.height),
            style = Stroke(width = 3.dp.toPx()),
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
