package com.sjtuhive.app

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sjtuhive.app.data.HiveNotifications
import com.sjtuhive.app.data.HiveSession
import com.sjtuhive.app.ui.HiveApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installCrashLogger()


        // 应用始终是深色界面，系统栏固定用浅色图标，不跟随系统深浅色。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // 升级旧版只保存 my.sjtu 会话的数据：把 WebView 里各个交大站点的 cookie 一起收进来。
        HiveSession.refreshFromWebView(this)
        HiveNotifications.ensureChannel(this)
        HiveNotifications.ensureScheduled(this)
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        setContent { HiveApp() }
    }

    /**
     * 把未捕获异常的完整堆栈写到「Android/data/com.sjtuhive.app/files/」下，
     * 方便复现后直接把 crash-*.txt 发给维护者定位。
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val file = java.io.File(dir, "crash-${System.currentTimeMillis()}.txt")
                file.writeText(
                    buildString {
                        appendLine("time: ${java.time.Instant.now()}")
                        appendLine("thread: ${thread.name}")
                        appendLine()
                        Log.getStackTraceString(throwable).lineSequence().forEach { appendLine(it) }
                    },
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
