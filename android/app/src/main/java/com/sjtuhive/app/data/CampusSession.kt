package com.sjtuhive.app.data

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用不显示的 WebView 走一次 jAccount SSO，把站点新下发的 cookie 收进 [HiveSession]。
 * jAccount 会话还有效时全程无需用户操作；失效后调用方再打开登录窗口。
 */
object CampusSession {
    private var webView: WebView? = null

    fun attach(view: WebView) {
        webView = view
    }

    fun detach(view: WebView) {
        if (webView === view) webView = null
    }

    suspend fun refresh(context: Context, host: String, timeoutMs: Long = 12_000): Boolean {
        val view = webView ?: return false
        return withContext(Dispatchers.Main) {
            val finished = CompletableDeferred<Unit>()
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!finished.isCompleted) finished.complete(Unit)
                }
            }
            view.loadUrl("https://$host/")
            withTimeoutOrNull(timeoutMs) { finished.await() }
            // 有的站点在页面加载完后再用脚本种 cookie，留一点时间。
            delay(600)
            HiveSession.refreshFromWebView(context)
            runCatching { CookieManager.getInstance().flush() }
            HiveSession.store(context)[host].orEmpty().isNotEmpty()
        }
    }
}
