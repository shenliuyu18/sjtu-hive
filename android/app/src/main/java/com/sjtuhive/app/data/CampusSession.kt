package com.sjtuhive.app.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 用不显示的 WebView 走一次 jAccount SSO，把站点新下发的 cookie 收进 [HiveSession]。
 * jAccount 会话还有效时全程无需用户操作；失效后调用方再打开登录窗口。
 */
object CampusSession {
    private const val FULL_REFRESH_INTERVAL = 5 * 60 * 1000L
    private val mainHosts = listOf(
        "my.sjtu.edu.cn",
        "calendar.sjtu.edu.cn",
        "i.sjtu.edu.cn",
        "v.sjtu.edu.cn",
        "pan.sjtu.edu.cn",
    )
    private const val VIDEO_HOME = "https://v.sjtu.edu.cn/jy-application-resourcemanage-ui/"
    private const val VIDEO_JWT_JS = """
(function(){
  try {
    var stores = [sessionStorage, localStorage];
    for (var s = 0; s < stores.length; s++) {
      var store = stores[s];
      for (var i = 0; i < store.length; i++) {
        var key = store.key(i);
        if (!key) continue;
        var value = store.getItem(key);
        if (!value) continue;
        if (key.indexOf('UserState') >= 0) {
          try {
            var state = JSON.parse(value);
            if (state && state.token) return state.token;
          } catch (e) {}
        }
        if (key.indexOf('JWT_TOKEN') >= 0) return value;
      }
    }
  } catch (e) {}
  return '';
})();
"""

    private var webView: WebView? = null
    private var lastFullRefresh = 0L
    private val mutex = Mutex()

    fun attach(view: WebView) {
        webView = view
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    suspend fun refresh(context: Context, host: String, timeoutMs: Long = 12_000): Boolean {
        val view = webView ?: return false
        return withContext(Dispatchers.Main) {
            if (!HiveSession.isLoggedIn(context)) return@withContext false
            // 先确保 WebView 手上有 jAccount Cookie，否则 SSO 一定失败。
            HiveSession.restoreToWebView(context)
            loadAndWait(view, "https://$host/", timeoutMs)
            if (host == "i.sjtu.edu.cn") {
                // 教学网需要先打开登录页再走 /jaccountlogin，才会下发新的 JSESSIONID。
                loadAndWait(view, "https://i.sjtu.edu.cn/student/index.html", timeoutMs)
                loadAndWait(view, "https://i.sjtu.edu.cn/jaccountlogin", timeoutMs)
                loadAndWait(view, "https://i.sjtu.edu.cn/student/index.html", timeoutMs)
            }
            if (host == "v.sjtu.edu.cn") {
                // 课堂视频的会话与 JWT 都在应用首页下发，只访问根域拿不到。
                loadAndWait(view, VIDEO_HOME, timeoutMs)
                var token: String? = null
                repeat(6) {
                    if (token.isNullOrBlank()) {
                        delay(1000)
                        token = readJwt(view)
                    }
                }
                if (!token.isNullOrBlank()) ClassroomApi(context).saveJwt(token)
            }
            // 有的站点在页面加载完后再用脚本种 cookie，留一点时间。
            delay(600)
            HiveSession.refreshFromWebView(context)
            runCatching { CookieManager.getInstance().flush() }
            HiveSession.store(context)[host].orEmpty().isNotEmpty()
        }
    }

    /** 启动/回到前台时把所有主要站点续一遍，避免会话闲置过期后才被动补救。 */
    suspend fun refreshAll(context: Context, force: Boolean = false): Boolean = mutex.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastFullRefresh < FULL_REFRESH_INTERVAL) return@withLock false
        if (webView == null || !HiveSession.isLoggedIn(context)) return@withLock false
        lastFullRefresh = now
        var refreshed = false
        for (host in mainHosts) {
            refreshed = refresh(context, host) || refreshed
        }
        refreshed
    }

    private suspend fun readJwt(view: WebView): String? =
        suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(VIDEO_JWT_JS) { raw ->
                if (!continuation.isActive) return@evaluateJavascript
                val token = raw.orEmpty().trim().trim('"')
                continuation.resume(token.takeIf { it.isNotEmpty() && it != "null" })
            }
        }

    private suspend fun loadAndWait(view: WebView, url: String, timeoutMs: Long) {
        val finished = CompletableDeferred<Unit>()
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loaded: String?) {
                if (!finished.isCompleted) finished.complete(Unit)
            }
        }
        view.loadUrl(url)
        withTimeoutOrNull(timeoutMs) { finished.await() }
        delay(250)
    }
}
