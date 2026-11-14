package io.legado.app.domain.model.settings

import androidx.compose.runtime.Immutable

/**
 * 只读的界面配置快照（SJTU Hive 适配层）。
 * 字段名与 legado-with-MD3 的 ThemeSettings 保持一致，玻璃组件无需改动；
 * 纯色背景方案下不含壁纸，模糊强度沿用上游默认值。
 */
@Immutable
data class ThemeSettingsSnapshot(
    val appTheme: String = "0",
    val composeEngine: String = "md3",
    val enableBlur: Boolean = true,
    val enableProgressiveBlur: Boolean = false,
    val enableDeepPersonalization: Boolean = false,
    val topBarButtonStyle: String = "tonal",
    // 对照 Lyrico 的磨砂折射观感：更大模糊半径 + 更透的底 + 更强的透镜折射。
    val bottomBarBlurRadius: Int = 28,
    val bottomBarBlurAlpha: Int = 38,
    val bottomBarLensRadius: Float = 36f,
    val backgroundImageLight: String? = null,
    val backgroundImageDark: String? = null,
    val backgroundImageBlurring: Int = 0,
    val backgroundImageDarkBlurring: Int = 0,
    val primaryColor: Int = 0,
    val secondaryColor: Int = 0,
)

@Immutable
data class AppUiConfiguration(
    val theme: ThemeSettingsSnapshot = ThemeSettingsSnapshot(),
)

@Immutable
data class ThemeCustomColors(
    val primary: Int = 0,
    val secondary: Int = 0,
    val primaryText: Int = 0,
    val secondaryText: Int = 0,
    val background: Int = 0,
    val labelContainer: Int = 0,
)

fun ThemeSettingsSnapshot.customColors(@Suppress("UNUSED_PARAMETER") isDark: Boolean): ThemeCustomColors =
    ThemeCustomColors(primary = primaryColor, secondary = secondaryColor)

fun ThemeSettingsSnapshot.hasBackgroundImage(isDark: Boolean): Boolean =
    if (isDark) !backgroundImageDark.isNullOrBlank() else !backgroundImageLight.isNullOrBlank()
