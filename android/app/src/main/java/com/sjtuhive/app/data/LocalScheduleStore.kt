package com.sjtuhive.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class LocalScheduleEvent(
    val id: String,
    val title: String,
    val startAt: LocalDateTime,
    val endAt: LocalDateTime,
    val location: String,
    val color: String,
    val todoId: String? = null,
)

/** 本地日程落盘（filesDir/schedule_events.json），对应桌面端 calendar_events。 */
class LocalScheduleStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "schedule_events.json")
    private val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    fun list(): List<LocalScheduleEvent> = read().sortedBy { it.startAt }

    fun add(
        title: String,
        startAt: LocalDateTime,
        endAt: LocalDateTime,
        location: String,
        color: String,
        todoId: String? = null,
    ): LocalScheduleEvent {
        val event = LocalScheduleEvent(
            id = UUID.randomUUID().toString(),
            title = title.trim(),
            startAt = startAt,
            endAt = endAt,
            location = location.trim(),
            color = color,
            todoId = todoId,
        )
        write(read() + event)
        return event
    }

    fun update(
        id: String,
        title: String,
        startAt: LocalDateTime,
        endAt: LocalDateTime,
        location: String,
        color: String,
    ) {
        write(
            read().map {
                if (it.id == id) {
                    it.copy(
                        title = title.trim(),
                        startAt = startAt,
                        endAt = endAt,
                        location = location.trim(),
                        color = color,
                    )
                } else {
                    it
                }
            },
        )
    }

    fun remove(id: String) {
        write(read().filterNot { it.id == id })
    }

    fun removeByTodo(todoId: String) {
        write(read().filterNot { it.todoId == todoId })
    }

    fun byTodo(todoId: String): LocalScheduleEvent? = read().firstOrNull { it.todoId == todoId }

    private fun read(): List<LocalScheduleEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val start = runCatching {
                    LocalDateTime.parse(item.optString("startAt"), formatter)
                }.getOrNull() ?: return@mapNotNull null
                val end = runCatching {
                    LocalDateTime.parse(item.optString("endAt"), formatter)
                }.getOrNull() ?: return@mapNotNull null
                LocalScheduleEvent(
                    id = id,
                    title = item.optString("title"),
                    startAt = start,
                    endAt = end,
                    location = item.optString("location"),
                    color = item.optString("color", DEFAULT_COLOR),
                    todoId = item.optString("todoId").takeIf { it.isNotBlank() },
                ).takeIf { it.id.isNotBlank() && it.title.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(events: List<LocalScheduleEvent>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("title", event.title)
                    .put("startAt", event.startAt.format(formatter))
                    .put("endAt", event.endAt.format(formatter))
                    .put("location", event.location)
                    .put("color", event.color)
                    .put("todoId", event.todoId.orEmpty()),
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(array.toString())
        }
    }

    companion object {
        const val DEFAULT_COLOR = "#2563EB"
        val COLORS = listOf("#2563EB", "#0EA5E9", "#10B981", "#F59E0B", "#EF4444", "#8B5CF6")
    }
}
