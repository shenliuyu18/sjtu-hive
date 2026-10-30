package com.sjtuhive.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sjtuhive.app.data.HiveSession

@Composable
fun SettingsScreen(
    context: Context,
    loggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium)
        Text(
            "账号与关于",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsCard(title = "jAccount") {
            Text(
                if (loggedIn) "已登录，会话保存在本机" else "未登录",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (loggedIn) {
                    OutlinedButton(onClick = { confirmLogout = true }) { Text("退出登录") }
                    Button(onClick = onLogin) { Text("重新登录") }
                } else {
                    Button(onClick = onLogin) { Text("登录 jAccount") }
                }
            }
        }

        SettingsCard(title = "外观") {
            Text("深色 · MD3", style = MaterialTheme.typography.bodyMedium)
            Text(
                "纯色背景 + legado-with-MD3 玻璃底栏；状态栏固定浅色图标。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(title = "关于") {
            Text("SJTU Hive", style = MaterialTheme.typography.titleSmall)
            Text(
                "版本 ${version.ifBlank { "0.1.0" }} · Android",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "本应用以 GPL-3.0 开源；玻璃组件源码来自 legado-with-MD3。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/HapeLee/legado-with-MD3"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                },
            ) { Text("查看 legado-with-MD3 源码") }
        }
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
                            CookieManager.getInstance().removeAllCookies(null)
                            CookieManager.getInstance().flush()
                        }
                        onLogout()
                    },
                ) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
    }
}
