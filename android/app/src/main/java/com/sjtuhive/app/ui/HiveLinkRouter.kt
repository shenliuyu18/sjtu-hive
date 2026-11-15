package com.sjtuhive.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.net.URLDecoder

/**
 * 应用内链接路由：把校园站点的 URL 映射到应用内的适配页面。
 *
 * 目标是不让用户被丢进系统浏览器或内置 WebView 看官方网页——
 * 水源帖子/用户/分类进水源模块，Canvas 课程进资料页，云盘进云盘，
 * 课堂进课堂视频，选课社区进课程页，交我办进办事大厅。
 * 识别不了的外部链接才交给系统浏览器。
 */
sealed interface HiveLink {
    data class ShuiyuanTopic(val id: Long) : HiveLink
    data class ShuiyuanUser(val username: String) : HiveLink
    data class ShuiyuanCategory(val slug: String) : HiveLink
    data class ShuiyuanSearch(val query: String) : HiveLink
    data class CanvasCourse(val courseId: Long) : HiveLink
    data object Classroom : HiveLink
    data object CloudPan : HiveLink
    data object CourseCommunity : HiveLink
    data object PortalServices : HiveLink
    data class External(val url: String) : HiveLink
    data object Ignore : HiveLink
}

object HiveLinkRouter {
    private const val SHUIYUAN = "shuiyuan.sjtu.edu.cn"
    private const val CANVAS = "oc.sjtu.edu.cn"

    fun resolve(raw: String): HiveLink {
        val text = raw.trim()
        if (text.isEmpty()) return HiveLink.Ignore
        if (text.startsWith("mailto:", ignoreCase = true)) return HiveLink.External(text)
        val uri = runCatching { URI(text) }.getOrNull() ?: return HiveLink.Ignore
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return HiveLink.External(text)
        val host = uri.host?.lowercase() ?: return HiveLink.External(text)
        val segments = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }

        return when {
            host == SHUIYUAN || host.endsWith(".$SHUIYUAN") -> when (segments.firstOrNull()) {
                "t" -> {
                    // /t/<slug>/<id> 或 /t/<id>
                    val id = segments.lastOrNull()?.toLongOrNull()
                    if (id != null) HiveLink.ShuiyuanTopic(id) else HiveLink.Ignore
                }
                "u" -> segments.getOrNull(1)
                    ?.let { HiveLink.ShuiyuanUser(decode(it)) }
                    ?: HiveLink.Ignore
                "c" -> segments.lastOrNull()
                    ?.let { HiveLink.ShuiyuanCategory(it) }
                    ?: HiveLink.Ignore
                "search" -> HiveLink.ShuiyuanSearch(queryParam(uri, "q").orEmpty())
                else -> HiveLink.External(text)
            }
            host == CANVAS || host.endsWith(".$CANVAS") -> {
                val idx = segments.indexOf("courses")
                val courseId = if (idx >= 0) segments.getOrNull(idx + 1)?.toLongOrNull() else null
                if (courseId != null) HiveLink.CanvasCourse(courseId) else HiveLink.Ignore
            }
            host == "pan.sjtu.edu.cn" || host.endsWith(".pan.sjtu.edu.cn") -> HiveLink.CloudPan
            host == "v.sjtu.edu.cn" || host.endsWith(".v.sjtu.edu.cn") -> HiveLink.Classroom
            host == "course.sjtu.plus" || host.endsWith(".course.sjtu.plus") -> HiveLink.CourseCommunity
            host == "my.sjtu.edu.cn" || host.endsWith(".sjtu.edu.cn") && segments.firstOrNull() == "service" ->
                HiveLink.PortalServices
            else -> HiveLink.External(text)
        }
    }

    /** 打开链接：外部链接交给系统浏览器，内部目标通过 [HiveLinkBus] 交给应用路由。 */
    fun open(context: Context, raw: String): Boolean {
        val link = resolve(raw)
        return when (link) {
            is HiveLink.External -> {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(link.url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                true
            }
            HiveLink.Ignore -> true
            else -> {
                HiveLinkBus.open(link)
                true
            }
        }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun queryParam(uri: URI, key: String): String? =
        uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.let { decode(it) }
}

/** 跨页面投递链接目标：由 HiveApp 消费并切到对应模块。 */
object HiveLinkBus {
    private var pendingLink: HiveLink? = null

    fun open(link: HiveLink) {
        pendingLink = link
        listeners.forEach { it(link) }
        pendingLink = null
    }

    /** HiveApp 注册；也支持先投递后注册（pendingLink 暂存一次）。 */
    fun consume(link: HiveLink) {
        listeners.forEach { it(link) }
    }

    private val listeners = mutableListOf<(HiveLink) -> Unit>()

    fun register(listener: (HiveLink) -> Unit) {
        listeners += listener
        pendingLink?.let {
            pendingLink = null
            listener(it)
        }
    }

    fun unregister(listener: (HiveLink) -> Unit) {
        listeners -= listener
    }
}
