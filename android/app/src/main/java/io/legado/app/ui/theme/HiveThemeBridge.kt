package io.legado.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import io.legado.app.domain.model.settings.AppUiConfiguration

/**
 * 兼容层（适配层）：legado-with-MD3 的玻璃组件引用 io.legado.app.ui.theme 下的这些成员。
 * 组件源码保持原样，这里把主题与模糊状态映射到 SJTU Hive 的 Material 3 主题。
 */
val LocalIsDark = staticCompositionLocalOf { true }
val LocalHazeState = compositionLocalOf<HazeState?> { null }
val LocalTopBarBackdrop = compositionLocalOf<Backdrop?> { null }
val LocalAppUiConfiguration = compositionLocalOf { AppUiConfiguration() }

object LegadoTheme {
    val colorScheme: ColorScheme
        @Composable
        @ReadOnlyComposable
        get() = MaterialTheme.colorScheme

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalIsDark.current

    /** 只走 Material 3 引擎，不启用 miuix 皮肤。 */
    const val MD3_ENGINE: String = "md3"

    val composeEngine: String
        @Composable
        @ReadOnlyComposable
        get() = LocalAppUiConfiguration.current.theme.composeEngine
}

object ThemeResolver {
    fun isMiuixEngine(engine: String): Boolean = engine == "miuix"
}

fun Modifier.responsiveHazeSource(state: HazeState): Modifier = hazeSource(state)
