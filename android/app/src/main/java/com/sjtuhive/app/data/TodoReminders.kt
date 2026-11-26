package com.sjtuhive.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** 本地待办到期提醒：每 15 分钟检查一次，到期前 60 分钟内通知一次。 */
object TodoReminders {
    fun schedule(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<TodoReminderWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "hive-todo-reminders",
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
    }
}

class TodoReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = TodoStore(applicationContext)
        val now = LocalDateTime.now()
        val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        store.list()
            .filter { !it.done && !it.notified }
            .forEach { item ->
                val due = item.dueDateTime() ?: return@forEach
                val minutes = Duration.between(now, due).toMinutes()
                if (minutes in 0..60) {
                    HiveNotifications.notify(
                        applicationContext,
                        ("todo-" + item.id).hashCode() and 0x7FFFFFF,
                        "待办即将到期",
                        "${item.title} · ${due.format(formatter)}",
                    )
                    store.markNotified(item.id)
                }
            }
        return Result.success()
    }
}
