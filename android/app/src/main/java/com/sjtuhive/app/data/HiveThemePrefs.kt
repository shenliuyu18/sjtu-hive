package com.sjtuhive.app.data

import android.content.Context
import com.sjtuhive.app.ui.theme.HiveAccent

/**
 * 主题偏好：明暗模式、配色方案、自定义色相。
 * 与 [HiveTestFeatures] 同样走 SharedPreferences，改动即时生效。
 */
object HiveThemePrefs {
    private const val PREFS = "hive_theme"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_ACCENT = "accent_id"
    private const val KEY_CUSTOM_HUE = "custom_hue"
    private const val KEY_CUSTOM_SAT = "custom_sat"
    private const val KEY_CUSTOM_VAL = "custom_val"
    private const val KEY_DOCKED = "bottom_bar_docked"
    private const val KEY_BLUR_RADIUS = "bottom_bar_blur_radius"
    private const val KEY_BLUR_ALPHA = "bottom_bar_blur_alpha"

    /** 底栏玻璃模糊半径（dp）。 */
    fun blurRadius(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BLUR_RADIUS, 28)

    fun setBlurRadius(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BLUR_RADIUS, value).apply()
    }

    /** 底栏玻璃底色不透明度（0–100）。 */
    fun blurAlpha(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BLUR_ALPHA, 38)

    fun setBlurAlpha(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_BLUR_ALPHA, value).apply()
    }

    const val MODE_DARK = "dark"
    const val MODE_LIGHT = "light"
    const val MODE_SYSTEM = "system"

    fun themeMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, MODE_DARK) ?: MODE_DARK

    fun setThemeMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode).apply()
    }

    fun accentId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCENT, "mist") ?: "mist"

    fun setAccentId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCENT, id).apply()
    }

    /** 自定义配色的色相（0–360）；彩度与明度按雾蓝基准取，保证可读性。 */
    fun customHue(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_CUSTOM_HUE, 266f).toDouble()

    fun setCustomHue(context: Context, hue: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_CUSTOM_HUE, hue.toFloat()).apply()
    }

    /** 底栏是否悬浮（玻璃胶囊）；关闭时为停靠式纯色底栏。 */
    fun bottomBarDocked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DOCKED, false)

    fun setBottomBarDocked(context: Context, docked: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DOCKED, docked).apply()
    }

    fun customSat(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_CUSTOM_SAT, 0.55f)

    fun customVal(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_CUSTOM_VAL, 0.78f)

    fun setCustomSv(context: Context, sat: Float, value: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_CUSTOM_SAT, sat).putFloat(KEY_CUSTOM_VAL, value).apply()
    }

    /** 解析出强调色：预设或按自定义 HSV 现推。 */
    fun accent(context: Context): HiveAccent {
        val id = accentId(context)
        if (id == HiveAccent.CUSTOM_ID) {
            return HiveAccent.derive(HiveAccent.hsvToColor(customHue(context), customSat(context), customVal(context)))
        }
        return HiveAccent.preset(id)
    }
}
