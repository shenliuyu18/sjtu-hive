package com.sjtuhive.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** 系统通知渠道与权限检查。 */
object HiveNotifications {
    private const val CHANNEL_ID = "hive_updates"
    private const val CHANNEL_NAME = "校园消息与邮件"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "交我办新消息与未读邮件提醒"
            },
        )
    }

    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun notify(context: Context, id: Int, title: String, body: String) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        val pending = PendingIntent.getActivity(
            context,
            id,
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    /** 每 15 分钟检查一次新消息；首次安装只建立基线，不轰炸通知。 */
    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<NotificationWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            "hive-notifications",
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic,
        )
        val immediate = OneTimeWorkRequestBuilder<NotificationWorker>()
            .setConstraints(constraints)
            .setInitialDelay(8, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(
            "hive-notifications-now",
            ExistingWorkPolicy.REPLACE,
            immediate,
        )
    }

    /** 开机/升级后重新登记轮询，保证应用没打开时也能检查消息。 */
    fun ensureScheduled(context: Context) {
        if (!HiveSession.isLoggedIn(context)) return
        ensureChannel(context)
        schedule(context)
    }
}

class NotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!HiveSession.isLoggedIn(context)) return@withContext Result.success()
        val prefs = context.getSharedPreferences("hive_notifications", Context.MODE_PRIVATE)
        val legacyBaseline = prefs.getBoolean("baseline", false)
        val portalFirst = !prefs.getBoolean("portal_baseline", legacyBaseline)
        val mailFirst = !prefs.getBoolean("mail_baseline", legacyBaseline)
        val seenPortal = prefs.getStringSet("portal_seen", emptySet()).orEmpty().toMutableSet()
        val seenMail = prefs.getStringSet("mail_seen", emptySet()).orEmpty().toMutableSet()
        var notified = 0
        var portalBaselined = !portalFirst
        var mailBaselined = !mailFirst

        runCatching {
            val api = HiveApi(context)
            for (group in api.groups().filter { it.unread > 0 }) {
                for (message in api.messages(group)) {
                    if (message.id in seenPortal) continue
                    if (portalFirst) {
                        seenPortal.add(message.id)
                        continue
                    }
                    if (notified >= 5) continue
                    seenPortal.add(message.id)
                    HiveNotifications.notify(
                        context,
                        message.id.hashCode() and 0x7FFFFFF,
                        message.title.ifBlank { "交我办新消息" },
                        listOf(message.source, message.body).filter { it.isNotBlank() }
                            .joinToString(" · ").ifBlank { "你有一条新的交我办消息" },
                    )
                    notified++
                }
            }
            // 只有真正走完一次交我办同步，才认为基线建立完成。
            portalBaselined = true
        }

        runCatching {
            val mailApi = MailApi(context)
            if (mailApi.account() == null) {
                mailBaselined = true
                return@runCatching
            }
            for (mail in mailApi.inbox(limit = 20)) {
                val key = "${mail.date}:${mail.from}:${mail.subject}"
                if (key in seenMail) continue
                if (mailFirst || mail.seen) {
                    seenMail.add(key)
                    continue
                }
                if (notified >= 10) continue
                seenMail.add(key)
                HiveNotifications.notify(
                    context,
                    key.hashCode() and 0x7FFFFFF,
                    "新邮件：${mail.subject}",
                    mail.from.ifBlank { "交大邮箱" },
                )
                notified++
            }
            mailBaselined = true
        }

        prefs.edit()
            .putBoolean("portal_baseline", portalBaselined)
            .putBoolean("mail_baseline", mailBaselined)
            .putBoolean("baseline", portalBaselined && mailBaselined)
            .putStringSet("portal_seen", seenPortal.toList().takeLast(200).toSet())
            .putStringSet("mail_seen", seenMail.toList().takeLast(200).toSet())
            .apply()
        Result.success()
    }
}
