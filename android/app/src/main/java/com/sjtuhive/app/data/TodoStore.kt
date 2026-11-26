package com.sjtuhive.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TodoItem(
    val id: String,
    val title: String,
    val dueAt: String,
    val note: String,
    val done: Boolean,
    val source: String,
    val calendarEventId: String? = null,
    val notified: Boolean = false,
) {
    /** 截止时间：带时间按原值，只有日期时按当天 09:00 处理。 */
    fun dueDateTime(): java.time.LocalDateTime? = runCatching {
        val text = dueAt.trim()
        when {
            text.isEmpty() -> null
            text.contains('T') -> java.time.LocalDateTime.parse(text.take(19))
            else -> java.time.LocalDate.parse(text.take(10)).atTime(9, 0)
        }
    }.getOrNull()
}

/**
 * 本地待办：JSON 落盘（filesDir/todo.json），与桌面端的 todo 模块对应。
 * Canvas 作业 DDL 以 source = "canvas" 的条目聚合进来，去重按 canvas 作业 id。
 */
class TodoStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "todo.json")

    fun list(): List<TodoItem> = read().sortedWith(
        compareBy<TodoItem> { it.done }.thenBy { it.dueAt.ifBlank { "9999" } },
    )

    fun add(title: String, dueAt: String, note: String = ""): TodoItem {
        val item = TodoItem(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            dueAt = dueAt.trim(),
            note = note.trim(),
            done = false,
            source = "local",
        )
        val all = read() + item
        write(all)
        return item
    }

    fun toggle(id: String) {
        write(read().map { if (it.id == id) it.copy(done = !it.done) else it })
    }

    fun update(id: String, title: String, dueAt: String, note: String) {
        write(
            read().map {
                if (it.id == id) {
                    it.copy(title = title.trim(), dueAt = dueAt.trim(), note = note.trim())
                } else {
                    it
                }
            },
        )
    }

    fun setCalendarEvent(id: String, eventId: String?) {
        write(read().map { if (it.id == id) it.copy(calendarEventId = eventId) else it })
    }

    fun markNotified(id: String) {
        write(read().map { if (it.id == id) it.copy(notified = true) else it })
    }

    fun remove(id: String) {
        write(read().filterNot { it.id == id })
    }

    /** 合并 Canvas 作业 DDL：已存在（source=canvas 且 id 相同）的跳过。 */
    fun mergeCanvas(ddl: List<TodoItem>): Int {
        val existing = read().toMutableList()
        val known = existing.filter { it.source == "canvas" }.map { it.id }.toMutableSet()
        var added = 0
        ddl.forEach { item ->
            val key = "canvas-${item.id}"
            if (known.add(key)) {
                existing += item.copy(id = key)
                added++
            }
        }
        if (added > 0) write(existing)
        return added
    }

    private fun read(): List<TodoItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { obj ->
                    TodoItem(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        dueAt = obj.optString("dueAt"),
                        note = obj.optString("note"),
                        done = obj.optBoolean("done"),
                        source = obj.optString("source", "local"),
                        calendarEventId = obj.optString("calendarEventId").takeIf { it.isNotBlank() },
                        notified = obj.optBoolean("notified"),
                    )
                }?.takeIf { it.id.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(items: List<TodoItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("dueAt", item.dueAt)
                    .put("note", item.note)
                    .put("done", item.done)
                    .put("source", item.source)
                    .put("calendarEventId", item.calendarEventId.orEmpty())
                    .put("notified", item.notified),
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }
    }
}
