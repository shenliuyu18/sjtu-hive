package com.sjtuhive.app.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 接口级磁盘缓存：把原始响应（或轻量 JSON 快照）按 key 存进 filesDir/cache。
 * 页面冷启动时先读缓存立即渲染，再刷新覆盖。
 */
object HiveCache {
    private fun file(context: Context, key: String): File {
        val dir = File(context.filesDir, "cache").apply { mkdirs() }
        // key 可能带斜杠等非法字符，取哈希做文件名
        return File(dir, Integer.toHexString(key.hashCode()) + ".json")
    }

    fun putString(context: Context, key: String, value: String) {
        runCatching { file(context, key).writeText(value) }
    }

    fun getString(context: Context, key: String): String? =
        runCatching {
            val file = file(context, key)
            if (file.exists()) file.readText() else null
        }.getOrNull()

    fun putArray(context: Context, key: String, items: JSONArray) {
        putString(context, key, items.toString())
    }

    fun getArray(context: Context, key: String): JSONArray? =
        getString(context, key)?.let { body ->
            runCatching { JSONArray(body) }.getOrNull()
        }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, "cache").deleteRecursively() }
    }
}
