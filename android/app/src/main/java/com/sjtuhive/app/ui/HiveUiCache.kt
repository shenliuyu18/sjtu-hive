package com.sjtuhive.app.ui

import com.sjtuhive.app.data.CloudFile
import com.sjtuhive.app.data.ExamItem
import com.sjtuhive.app.data.PortalMessage
import com.sjtuhive.app.data.SchoolEvent

/**
 * 进程内的页面缓存。底部页签切换会销毁上一个页面的状态，
 * 这里保存最近一次的数据，回到页面时先展示缓存、只有过期才重新请求。
 */
internal object HiveUiCache {
    private const val TTL = 2 * 60 * 1000L

    data class Overview(
        val unread: Int,
        val recent: List<PortalMessage>,
        val events: List<SchoolEvent>,
        val nextExam: ExamItem?,
        val at: Long,
    )

    data class Messages(
        val messages: List<PortalMessage>,
        val unread: Int,
        val at: Long,
    )

    data class Schedule(
        val events: List<SchoolEvent>,
        val exams: List<ExamItem>,
        val examError: String?,
        val at: Long,
    )

    data class Cloud(
        val path: String,
        val files: List<CloudFile>,
        val at: Long,
    )

    var overview: Overview? = null
    var messages: Messages? = null
    var schedule: Schedule? = null
    var cloud: Cloud? = null

    fun fresh(at: Long): Boolean = System.currentTimeMillis() - at < TTL

    fun clear() {
        overview = null
        messages = null
        schedule = null
        cloud = null
    }
}