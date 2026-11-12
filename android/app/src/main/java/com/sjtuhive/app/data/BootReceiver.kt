package com.sjtuhive.app.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机或应用升级后重新登记后台消息轮询，保证没打开应用也能收到通知。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> HiveNotifications.ensureScheduled(context)
        }
    }
}
