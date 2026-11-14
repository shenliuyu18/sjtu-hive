package com.sjtuhive.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.sjtuhive.app.data.CampusSession
import com.sjtuhive.app.data.HiveCacheManager
import com.sjtuhive.app.data.HiveNotifications
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.data.HiveThemePrefs
import com.sjtuhive.app.data.HiveTestFeatures
import com.sjtuhive.app.data.UpdateApi
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.legado.app.domain.model.settings.AppUiConfiguration
import io.legado.app.domain.model.settings.ThemeSettingsSnapshot
import io.legado.app.ui.theme.LocalAppUiConfiguration
import com.sjtuhive.app.ui.theme.HiveAccent
import com.sjtuhive.app.ui.theme.HiveNeutrals
import com.sjtuhive.app.ui.theme.hiveDarkScheme
import com.sjtuhive.app.ui.theme.hiveLightScheme
import io.legado.app.ui.theme.LocalIsDark
import io.legado.app.ui.widget.components.FloatingBottomBar
import io.legado.app.ui.widget.components.FloatingBottomBarItem

/** legado-with-MD3 暗色配色；纯色背景，不使用壁纸。 */
private enum class HiveTabId { Overview, Schedule, Services, CloudPan, Settings }

private data class HiveTab(val id: HiveTabId, val label: String, val icon: ImageVector)

private val allTabs = listOf(
    HiveTab(HiveTabId.Overview, "概览", Icons.Outlined.Home),
    HiveTab(HiveTabId.Schedule, "日程", Icons.Outlined.CalendarMonth),
    HiveTab(HiveTabId.Services, "办事", Icons.Outlined.Assignment),
    HiveTab(HiveTabId.CloudPan, "云盘", Icons.Outlined.Cloud),
    HiveTab(HiveTabId.Settings, "设置", Icons.Outlined.Settings),
)

@Composable
fun HiveApp() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var themeMode by remember { mutableStateOf(HiveThemePrefs.themeMode(context)) }
    var accentId by remember { mutableStateOf(HiveThemePrefs.accentId(context)) }
    var customHue by remember { mutableStateOf(HiveThemePrefs.customHue(context)) }
    var customSat by remember { mutableStateOf(HiveThemePrefs.customSat(context)) }
    var customVal by remember { mutableStateOf(HiveThemePrefs.customVal(context)) }
    var bottomBarDocked by remember { mutableStateOf(HiveThemePrefs.bottomBarDocked(context)) }
    var blurRadius by remember { mutableStateOf(HiveThemePrefs.blurRadius(context)) }
    var blurAlpha by remember { mutableStateOf(HiveThemePrefs.blurAlpha(context)) }

    val isDark = when (themeMode) {
        HiveThemePrefs.MODE_LIGHT -> false
        HiveThemePrefs.MODE_SYSTEM -> (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        else -> true
    }
    val accent = if (accentId == HiveAccent.CUSTOM_ID) {
        HiveAccent.derive(HiveAccent.hsvToColor(customHue, customSat, customVal))
    } else {
        HiveAccent.preset(accentId)
    }
    val neutrals = HiveNeutrals.of(accent.tintHue, accent.tintChroma)
    val colors = if (isDark) hiveDarkScheme(accent, neutrals.second) else hiveLightScheme(accent, neutrals.first)

    // 系统栏图标深浅跟随主题（浅色主题用深色图标）。
    val view = LocalView.current
    LaunchedEffect(isDark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !isDark
        controller.isAppearanceLightNavigationBars = !isDark
    }

    CompositionLocalProvider(
        LocalIsDark provides isDark,
        LocalAppUiConfiguration provides AppUiConfiguration(
            ThemeSettingsSnapshot(
                bottomBarBlurRadius = blurRadius,
                bottomBarBlurAlpha = blurAlpha,
                bottomBarLensRadius = 36f,
            ),
        ),
    ) {
        MaterialTheme(colorScheme = colors) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                HiveShell(
                    themeMode = themeMode,
                    accentId = accentId,
                    customHue = customHue,
                    customSat = customSat,
                    customVal = customVal,
                    bottomBarDocked = bottomBarDocked,
                    onThemeModeChange = { mode ->
                        HiveThemePrefs.setThemeMode(context, mode)
                        themeMode = mode
                    },
                    onAccentChange = { id ->
                        HiveThemePrefs.setAccentId(context, id)
                        accentId = id
                    },
                    onCustomHueChange = { hue ->
                        HiveThemePrefs.setCustomHue(context, hue)
                        HiveThemePrefs.setAccentId(context, HiveAccent.CUSTOM_ID)
                        customHue = hue
                        accentId = HiveAccent.CUSTOM_ID
                    },
                    onCustomSvChange = { sat, value ->
                        HiveThemePrefs.setCustomSv(context, sat, value)
                        HiveThemePrefs.setAccentId(context, HiveAccent.CUSTOM_ID)
                        customSat = sat
                        customVal = value
                        accentId = HiveAccent.CUSTOM_ID
                    },
                    onCustomHexChange = { hex ->
                        runCatching {
                            val color = android.graphics.Color.parseColor("#$hex")
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(color, hsv)
                            HiveThemePrefs.setCustomHue(context, hsv[0].toDouble())
                            HiveThemePrefs.setCustomSv(context, hsv[1], hsv[2])
                            HiveThemePrefs.setAccentId(context, HiveAccent.CUSTOM_ID)
                            customHue = hsv[0].toDouble()
                            customSat = hsv[1]
                            customVal = hsv[2]
                            accentId = HiveAccent.CUSTOM_ID
                        }
                    },
                    onBottomBarDockedChange = { docked ->
                        HiveThemePrefs.setBottomBarDocked(context, docked)
                        bottomBarDocked = docked
                    },
                    blurRadius = blurRadius,
                    blurAlpha = blurAlpha,
                    onBlurRadiusChange = { value ->
                        HiveThemePrefs.setBlurRadius(context, value)
                        blurRadius = value
                    },
                    onBlurAlphaChange = { value ->
                        HiveThemePrefs.setBlurAlpha(context, value)
                        blurAlpha = value
                    },
                )
            }
        }
    }
}

private data class LoginTarget(
    val startUrl: String,
    val completeHost: String,
    val autoClickSelector: String? = null,
)

private val MySjtuLogin = LoginTarget("https://my.sjtu.edu.cn/ui/message/", "my.sjtu.edu.cn")
private val CalendarLogin = LoginTarget("https://calendar.sjtu.edu.cn/?tenantUserId=", "calendar.sjtu.edu.cn")
private val TeachingLogin = LoginTarget(
    "https://i.sjtu.edu.cn/student/index.html",
    "i.sjtu.edu.cn",
    autoClickSelector = "#authJwglxtLoginURL",
)

/** 停靠式底栏：贴底、不透明，替代悬浮玻璃胶囊（设置里可切换）。 */
@Composable
private fun HiveDockedBar(
    tabs: List<HiveTab>,
    selectedId: HiveTabId,
    onSelect: (HiveTabId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
        ) {
            tabs.forEach { tab ->
                val isSelected = tab.id == selectedId
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(tab.id) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HiveShell(
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
) {
    var selectedId by remember { mutableStateOf(HiveTabId.Overview) }
    val context = LocalContext.current
    var servicesEnabled by remember { mutableStateOf(HiveTestFeatures.servicesEnabled(context)) }
    val tabs = remember(servicesEnabled) {
        allTabs.filter { it.id != HiveTabId.Services || servicesEnabled }
    }
    var loggedIn by remember { mutableStateOf(HiveSession.isLoggedIn(context)) }

    // WebView 体系初始化完成前读不到 WebView 的 Cookie；回到前台时再用它校准登录态，
    // 避免会话其实还在、界面却显示“未登录”。
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                HiveSession.refreshFromWebView(context)
                loggedIn = HiveSession.isLoggedIn(context)
                sessionScope.launch { CampusSession.refreshAll(context) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        delay(1500)
        HiveSession.refreshFromWebView(context)
        loggedIn = HiveSession.isLoggedIn(context)
        // 启动时把所有主要在用的站点会话先续一遍，避免闲置过期后各模块各自报失效。
        CampusSession.refreshAll(context)
        loggedIn = HiveSession.isLoggedIn(context)
        delay(3000)
        runCatching { UpdateApi(context).check() }
            .getOrNull()
            ?.takeIf { it.available }
            ?.let {
                android.widget.Toast.makeText(
                    context,
                    "发现新版本 ${it.latest}，可在设置中更新",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
    }
    // 通知权限在登录后请求；工作线程负责后台轮询交我办与邮箱。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(loggedIn) {
        HiveNotifications.ensureChannel(context)
        if (loggedIn) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            HiveNotifications.schedule(context)
        }
    }
    var loginTarget by remember { mutableStateOf<LoginTarget?>(null) }
    var sessionVersion by remember { mutableIntStateOf(0) }
    var showShuiyuan by remember { mutableStateOf(false) }
    var showCanvas by remember { mutableStateOf(false) }
    var showEmail by remember { mutableStateOf(false) }
    var emailRequest by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var showClassroom by remember { mutableStateOf(false) }
    var showCourseCommunity by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    var showMessages by remember { mutableStateOf(false) }
    var servicesFullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(tabs) {
        // 开关「办事」会改变页签数量：重置可能残留的全屏标志，
        // 并在当前页签被移除时立即切走，避免以禁用状态渲染办事页一帧。
        servicesFullscreen = false
        if (tabs.none { it.id == selectedId }) selectedId = tabs.first().id
    }
    val tabStates = rememberSaveableStateHolder()
    // 登录态切换（登录完成/退出）时丢弃旧账号的页面缓存。
    LaunchedEffect(sessionVersion) {
        if (sessionVersion > 0) HiveUiCache.clear()
    }
    // 玻璃底栏采样的是内容层的画面。
    // 注意不要给 rememberLayerBackdrop 传自定义 onDraw：那会替换掉默认的
    // drawContent()，等于把捕获到的内容擦掉，玻璃里只剩一块纯色。
    val backdrop = rememberLayerBackdrop()
    // 自动清除缓存：按用户在设置里选择的类别与时机，启动时静默执行一次
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { HiveCacheManager.maybeAutoClear(context) }
        }
    }
    // 隐藏的会话续期窗口：站点会话过期时静默走一次 SSO，用户不必手动重新登录。
    val sessionView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
        }
    }
    DisposableEffect(sessionView) {
        CampusSession.attach(sessionView)
        onDispose {
            CampusSession.detach(sessionView)
            sessionView.destroy()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
    ) {
        // layerBackdrop 让底栏采样真实内容，从而做出内容模糊（而不是只模糊背景色）。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            // 记住每个页签的滚动位置等可保存状态，切回来不会跳回顶部。
            tabStates.SaveableStateProvider(selectedId.name) {
            key(sessionVersion) {
                when (selectedId) {
                    HiveTabId.Overview -> OverviewScreen(
                        context = context,
                        loggedIn = loggedIn,
                        onLogin = { loginTarget = MySjtuLogin },
                        onNavigate = { target ->
                            when (target) {
                                1 -> selectedId = HiveTabId.Schedule
                                2 -> if (servicesEnabled) selectedId = HiveTabId.Services
                            }
                        },
                        onOpenShuiyuan = { showShuiyuan = true },
                        onOpenCanvas = { showCanvas = true },
                        onOpenEmail = { showEmail = true },
                        onOpenClassroom = { showClassroom = true },
                        onOpenCourse = { showCourseCommunity = true },
                        onOpenShare = { showShare = true },
                        onOpenMessages = { showMessages = true },
                        onOpenMail = { index ->
                            emailRequest = "INBOX" to index
                            showEmail = true
                        },
                    )
                    HiveTabId.Schedule -> ScheduleScreen(
                        context = context,
                        loggedIn = loggedIn,
                        onLogin = { loginTarget = CalendarLogin },
                        onLoginTeachingSite = { loginTarget = TeachingLogin },
                    )
                    HiveTabId.Services -> if (servicesEnabled) {
                        ServicesScreen(
                            context = context,
                            loggedIn = loggedIn,
                            onLogin = { loginTarget = MySjtuLogin },
                            onFullscreenChange = { servicesFullscreen = it },
                        )
                    } else {
                        // 开关刚关掉的那一帧 selectedId 还没被 LaunchedEffect 改走，
                        // 这里兜住，避免渲染一个处于禁用状态的办事页。
                        Box(Modifier.fillMaxSize())
                    }
                    HiveTabId.CloudPan -> CloudPanScreen(
                        context = context,
                        loggedIn = loggedIn,
                        onLogin = { loginTarget = CalendarLogin },
                    )
                    HiveTabId.Settings -> SettingsScreen(
                        context = context,
                        loggedIn = loggedIn,
                        onLogin = { loginTarget = CalendarLogin },
                        onLogout = { loggedIn = false },
                        themeMode = themeMode,
                        accentId = accentId,
                        customHue = customHue,
                        customSat = customSat,
                        customVal = customVal,
                        bottomBarDocked = bottomBarDocked,
                        onThemeModeChange = onThemeModeChange,
                        onAccentChange = onAccentChange,
                        onCustomHueChange = onCustomHueChange,
                        onCustomSvChange = onCustomSvChange,
                        onCustomHexChange = onCustomHexChange,
                        onBottomBarDockedChange = onBottomBarDockedChange,
                        blurRadius = blurRadius,
                        blurAlpha = blurAlpha,
                        onBlurRadiusChange = onBlurRadiusChange,
                        onBlurAlphaChange = onBlurAlphaChange,
                        servicesEnabled = servicesEnabled,
                        onServicesEnabledChange = { enabled ->
                            HiveTestFeatures.setServicesEnabled(context, enabled)
                            servicesEnabled = enabled
                        },
                    )
                }
            }
            }
        }
        AndroidView(
            factory = { sessionView },
            modifier = Modifier.size(1.dp).alpha(0.01f),
        )
        if (showShuiyuan) {
            ShuiyuanScreen(context = context, onClose = { showShuiyuan = false })
        }
        if (showCanvas) {
            CanvasScreen(context = context, onClose = { showCanvas = false })
        }
        if (showClassroom) {
            ClassroomScreen(context = context, onClose = { showClassroom = false })
        }
        if (showCourseCommunity) {
            CourseCommunityScreen(context = context, onClose = { showCourseCommunity = false })
        }
        if (showShare) {
            ShareScreen(context = context, onClose = { showShare = false })
        }
        if (showMessages) {
            MessagesScreen(
                context = context,
                loggedIn = loggedIn,
                onLogin = { loginTarget = MySjtuLogin },
                onOpenEmail = { folder, index ->
                    emailRequest = folder to index
                    showEmail = true
                },
                onClose = { showMessages = false },
            )
        }
        if (showEmail) {
            EmailScreen(
                context = context,
                onClose = {
                    showEmail = false
                    emailRequest = null
                },
                initialFolder = emailRequest?.first,
                initialIndex = emailRequest?.second,
            )
        }
        loginTarget?.let { target ->
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                LoginWebView(
                    startUrl = target.startUrl,
                    autoClickSelector = target.autoClickSelector,
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            HiveSession.refreshFromWebView(context)
                            loggedIn = HiveSession.isLoggedIn(context)
                            sessionVersion++
                            loginTarget = null
                            // 首次登录完成后把各站点会话一次备好，避免刚进页面就报失效。
                            sessionScope.launch { CampusSession.refreshAll(context, force = true) }
                        },
                    ) { Text("完成登录") }
                    Button(onClick = { loginTarget = null }) { Text("取消") }
                }
            }
        }
        if (loginTarget == null && !showShuiyuan && !showCanvas && !showEmail && !showClassroom &&
            !showCourseCommunity && !showShare && !showMessages && !servicesFullscreen &&
            PreviewHost.request == null
        ) if (bottomBarDocked) {
            HiveDockedBar(
                tabs = tabs,
                selectedId = selectedId,
                onSelect = { selectedId = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else key(tabs) { FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp)
                .height(64.dp),
            // 必须读 selectedId（状态）而不是 selected（本次组合的快照值），
            // 否则底栏内部的 snapshotFlow 观察不到切换，指示器不会跟随。
            selectedIndex = { tabs.indexOfFirst { it.id == selectedId }.coerceAtLeast(0) },
            onSelected = { index -> tabs.getOrNull(index)?.let { selectedId = it.id } },
            backdrop = backdrop,
            // kyant backdrop 的模糊/折射走 RenderEffect，Android 13 以下不可用，
            // 关掉后组件自动退化为半透明纯色胶囊（见 docs/android12-compat.md）。
            isBlurEnabled = Build.VERSION.SDK_INT >= 33,
            tabsCount = tabs.size,
        ) {
            tabs.forEach { tab ->
                FloatingBottomBarItem(onClick = { selectedId = tab.id }) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(tab.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        } }
        if (PreviewHost.request != null) {
            FilePreviewOverlay()
        }
    }
}
