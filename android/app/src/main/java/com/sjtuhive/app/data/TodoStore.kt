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
)

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
                    .put("source", item.source),
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }
    }
}
