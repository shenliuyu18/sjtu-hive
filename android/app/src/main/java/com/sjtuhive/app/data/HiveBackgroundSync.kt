package com.sjtuhive.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** 自动同步间隔（分钟，0 = 关闭），WorkManager 最短 15 分钟。 */
object HiveSyncPrefs {
    private const val PREFS = "hive_sync"
    private const val KEY_MAIL = "mail_interval"
    private const val KEY_CANVAS = "canvas_interval"

    fun mailInterval(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAIL, 15)

    fun setMailInterval(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAIL, minutes).apply()
    }

    fun canvasInterval(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_CANVAS, 30)

    fun setCanvasInterval(context: Context, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CANVAS, minutes).apply()
    }

    fun lastRun(context: Context, key: String): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_$key", 0L)

    fun markRun(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("last_$key", System.currentTimeMillis()).apply()
    }
}

object HiveBackgroundSync {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<HiveSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "hive-background-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class HiveSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!HiveSession.isLoggedIn(context)) return Result.success()
        val now = System.currentTimeMillis()

        val mailMinutes = HiveSyncPrefs.mailInterval(context)
        if (mailMinutes > 0 && elapsed(context, "mail", now, mailMinutes)) {
            runCatching {
                val mail = MailApi(context)
                if (mail.account() != null) mail.inbox("INBOX", limit = 20)
            }
            HiveSyncPrefs.markRun(context, "mail")
        }

        val canvasMinutes = HiveSyncPrefs.canvasInterval(context)
        if (canvasMinutes > 0 && elapsed(context, "canvas", now, canvasMinutes)) {
            runCatching {
                val canvas = CanvasApi(context)
                if (canvas.usable()) {
                    val courses = canvas.courses()
                    courses.take(20).forEach { course ->
                        runCatching { canvas.files(course.id) }
                    }
                }
            }
            runCatching {
                val nowTime = LocalDateTime.now()
                SchoolCalendarApi(context).events(
                    nowTime.minusDays(7).toLocalDate().atStartOfDay(),
                    nowTime.plusDays(60),
                )
            }
            HiveSyncPrefs.markRun(context, "canvas")
        }
        return Result.success()
    }

    private fun elapsed(context: Context, key: String, now: Long, minutes: Int): Boolean =
        now - HiveSyncPrefs.lastRun(context, key) >= minutes * 60_000L
}
