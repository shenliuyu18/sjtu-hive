package com.sjtuhive.app.data

import android.content.Context
import com.sjtuhive.app.ui.HiveUiCache
import android.webkit.WebView
import java.io.File

/** 可清理的缓存类别。 */
enum class HiveCacheCategory(val id: String, val label: String) {
    PAGE("page", "页面与接口缓存"),
    PREVIEW("preview", "预览与传输临时文件"),
    UPDATE("update", "更新安装包"),
    WEBVIEW("webview", "其他临时文件"),
}

/**
 * 缓存清理与自动清理策略。
 *
 * 用户可以在设置页逐类勾选「自动清除哪些」，并选择清理时机
 * （关闭 / 每次启动 / 每天一次 / 每周一次）。自动清除只清理勾选的类别。
 */
object HiveCacheManager {
    private const val PREFS = "hive_cache"
    private const val KEY_SCHEDULE = "auto_schedule"
    private const val KEY_ENABLED = "auto_enabled"
    private const val KEY_LAST_CLEAR = "last_clear_at"

    const val SCHEDULE_NEVER = "never"
    const val SCHEDULE_ON_START = "on_start"
    const val SCHEDULE_DAILY = "daily"
    const val SCHEDULE_WEEKLY = "weekly"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun schedule(context: Context): String =
        prefs(context).getString(KEY_SCHEDULE, SCHEDULE_NEVER) ?: SCHEDULE_NEVER

    fun setSchedule(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SCHEDULE, value).apply()
    }

    /** 默认勾选页面缓存与预览临时文件（体积最可控的两类）。 */
    fun enabledCategories(context: Context): Set<String> =
        prefs(context).getStringSet(
            KEY_ENABLED,
            setOf(HiveCacheCategory.PAGE.id, HiveCacheCategory.PREVIEW.id),
        ) ?: emptySet()

    fun setEnabledCategories(context: Context, ids: Set<String>) {
        prefs(context).edit().putStringSet(KEY_ENABLED, ids).apply()
    }

    private fun lastClearAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_CLEAR, 0L)

    private fun setLastClearAt(context: Context, time: Long) {
        prefs(context).edit().putLong(KEY_LAST_CLEAR, time).apply()
    }

    /** 单个类别的占用大小（字节）。 */
    fun sizeOf(context: Context, category: HiveCacheCategory): Long = when (category) {
        HiveCacheCategory.PAGE ->
            dirSize(File(context.filesDir, "cache")) +
                File(context.filesDir, "hive_ui_cache.json").takeIf { it.exists() }?.length().orZero()
        HiveCacheCategory.PREVIEW ->
            dirSize(File(context.cacheDir, "preview")) +
                dirSize(File(context.cacheDir, "transfers")) +
                dirSize(File(context.cacheDir, "share"))
        HiveCacheCategory.UPDATE ->
            dirSize(File(context.cacheDir, "updates"))
        HiveCacheCategory.WEBVIEW ->
            // 只统计 cacheDir 根下的散落文件（App 完全掌控、必定可删）；
            // WebView 等系统目录不计入也不强清，避免"点了没反应"的假象。
            context.cacheDir.listFiles()
                ?.filter { it.isFile }
                ?.sumOf { it.length() }
                ?: 0L
    }

    fun totalSize(context: Context): Long =
        HiveCacheCategory.entries.sumOf { sizeOf(context, it) }

    /** 清除指定类别的缓存。 */
    fun clear(context: Context, ids: Set<String>) {
        if (HiveCacheCategory.PAGE.id in ids) {
            runCatching { HiveCache.clear(context) }
            runCatching { HiveUiCache.clear() }
            runCatching { File(context.filesDir, "hive_ui_cache.json").delete() }
            runCatching { File(context.filesDir, "cache").deleteRecursively() }
        }
        if (HiveCacheCategory.PREVIEW.id in ids) {
            runCatching { File(context.cacheDir, "preview").deleteRecursively() }
            runCatching { File(context.cacheDir, "transfers").deleteRecursively() }
            runCatching { File(context.cacheDir, "share").deleteRecursively() }
        }
        if (HiveCacheCategory.UPDATE.id in ids) {
            runCatching { File(context.cacheDir, "updates").deleteRecursively() }
        }
        if (HiveCacheCategory.WEBVIEW.id in ids) {
            // WebView 必须在主线程创建，否则抛异常且不生效
            runCatching {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        WebView(context).apply {
                            clearCache(true)
                            destroy()
                        }
                    }
                }
            }
            // 清掉 cacheDir 根下的散落临时文件
            runCatching {
                context.cacheDir.listFiles()?.forEach { child ->
                    if (child.isFile) runCatching { child.delete() }
                }
            }
        }
    }

    /**
     * 按用户策略判断是否该自动清除；启动时调用一次。
     * 返回 true 表示本次执行了清理。
     */
    fun maybeAutoClear(context: Context): Boolean {
        val schedule = schedule(context)
        if (schedule == SCHEDULE_NEVER) return false
        val ids = enabledCategories(context)
            .filter { id -> HiveCacheCategory.entries.any { it.id == id } }
            .toSet()
        if (ids.isEmpty()) return false

        val now = System.currentTimeMillis()
        val last = lastClearAt(context)
        val due = when (schedule) {
            SCHEDULE_ON_START -> true
            SCHEDULE_DAILY -> last == 0L || now - last >= 24L * 60 * 60 * 1000
            SCHEDULE_WEEKLY -> last == 0L || now - last >= 7L * 24 * 60 * 60 * 1000
            else -> false
        }
        if (!due) return false
        runCatching { clear(context, ids) }
        setLastClearAt(context, now)
        return true
    }

    private val managedCacheNames = setOf("preview", "transfers", "share", "updates")

    private fun managedCacheSize(context: Context): Long =
        dirSize(File(context.cacheDir, "preview")) +
            dirSize(File(context.cacheDir, "transfers")) +
            dirSize(File(context.cacheDir, "share")) +
            dirSize(File(context.cacheDir, "updates"))

    private fun dirSize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return runCatching {
            file.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        }.getOrDefault(0L)
    }

    private fun Long?.orZero(): Long = this ?: 0L
}
