package com.sjtuhive.app.data

import android.content.Context

/** 仍在测试中的功能，默认关闭，由设置页显式开启。 */
object HiveTestFeatures {
    private const val PREFS = "hive_features"
    private const val KEY_SERVICES = "services_enabled"

    fun servicesEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICES, false)

    fun setServicesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICES, enabled).apply()
    }
}
