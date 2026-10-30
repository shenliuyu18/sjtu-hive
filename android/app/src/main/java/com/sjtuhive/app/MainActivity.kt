package com.sjtuhive.app

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.ui.HiveApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 应用始终是深色界面，系统栏固定用浅色图标，不跟随系统深浅色。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 升级旧版只保存 my.sjtu 会话的数据：把 WebView 里各个交大站点的 cookie 一起收进来。
        HiveSession.refreshFromWebView(this)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        setContent { HiveApp() }
    }
}
