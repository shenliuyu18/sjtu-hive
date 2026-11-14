package com.sjtuhive.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * 配色系统：中性色阶（页面、玻璃、文字）+ 强调色（六套预设 + 自定义现推）。
 *
 * 角色划分与换算规则移植自 dsh-Go（AGPL-3.0，github.com/ccch1mneyyy/dsh-Go）：
 * 强调色只占界面 ≤5%，中性色按强调色的色相整体染色（明度不动，对比度不随配色变）。
 * 自定义颜色走 OKLCH 现推：彩度封顶 0.13、明度钳制 0.30–0.60、深色主色再提 0.234，
 * 并做 WCAG 对比度守卫。
 */

/** 浅色中性色（晨雾纸面）。 */
data class HiveLightNeutrals(
    val mistTop: Int, val mistBottom: Int, val mist: Int,
    val mistLowest: Int, val mistLow: Int, val mistContainer: Int,
    val mistHigh: Int, val mistHighest: Int,
    val ink: Int, val inkMuted: Int, val inkFaint: Int,
    val outline: Int, val rule: Int, val shadow: Int,
    val slate: Int, val slateContainer: Int,
    val inverseSurface: Int, val onInverseSurface: Int, val scrim: Int,
)

/** 深色中性色（深海石板）。 */
data class HiveDarkNeutrals(
    val nightTop: Int, val nightBottom: Int, val night: Int,
    val nightLowest: Int, val nightLow: Int, val nightContainer: Int,
    val nightHigh: Int, val nightHighest: Int,
    val paper: Int, val paperMuted: Int, val paperFaint: Int,
    val outline: Int, val rule: Int, val shadow: Int,
    val slateLift: Int, val onSlateLift: Int,
    val scrim: Int,
)

/** 雾蓝的中性色阶（基准），其余配色由 [HiveNeutrals.of] 染色得到。 */
private val mistLight = HiveLightNeutrals(
    mistTop = 0xFFEBF1FE.toInt(), mistBottom = 0xFFF6F7FC.toInt(), mist = 0xFFF0F4FD.toInt(),
    mistLowest = 0xFFFBFCFF.toInt(), mistLow = 0xFFF6F9FE.toInt(), mistContainer = 0xFFEEF1F9.toInt(),
    mistHigh = 0xFFE7EBF4.toInt(), mistHighest = 0xFFDFE4EE.toInt(),
    ink = 0xFF1E232E.toInt(), inkMuted = 0xFF4F5562.toInt(), inkFaint = 0xFF646974.toInt(),
    outline = 0xFF7E838D.toInt(), rule = 0xFFDCE0E8.toInt(), shadow = 0xFF0B152D.toInt(),
    slate = 0xFF545E6F.toInt(), slateContainer = 0xFFE2E6F0.toInt(),
    inverseSurface = 0xFF252A35.toInt(), onInverseSurface = 0xFFECEEF4.toInt(), scrim = 0xFF0B111F.toInt(),
)

private val mistDark = HiveDarkNeutrals(
    nightTop = 0xFF1D222C.toInt(), nightBottom = 0xFF1B1C23.toInt(), night = 0xFF1C1F28.toInt(),
    nightLowest = 0xFF14171E.toInt(), nightLow = 0xFF232730.toInt(), nightContainer = 0xFF272C36.toInt(),
    nightHigh = 0xFF2D323C.toInt(), nightHighest = 0xFF343944.toInt(),
    paper = 0xFFE6E9F0.toInt(), paperMuted = 0xFFB6BBC5.toInt(), paperFaint = 0xFF9A9EA9.toInt(),
    outline = 0xFF737781.toInt(), rule = 0xFF343841.toInt(), shadow = 0xFF03060D.toInt(),
    slateLift = 0xFFB3BED2.toInt(), onSlateLift = 0xFF1A2230.toInt(),
    scrim = 0xFF000000.toInt(),
)

object HiveNeutrals {
    /** 雾蓝中性色所在色相。 */
    const val BASE_HUE = 266.0

    private val cache = mutableMapOf<Pair<Long, Long>, Pair<HiveLightNeutrals, HiveDarkNeutrals>>()

    /** 把雾蓝中性色转到 [tintHue]、彩度乘 [tintChroma]；明度不动。 */
    fun of(tintHue: Double, tintChroma: Double): Pair<HiveLightNeutrals, HiveDarkNeutrals> {
        if (tintHue == BASE_HUE && tintChroma == 1.0) return mistLight to mistDark
        val key = (tintHue * 100).toLong() to (tintChroma * 1000).toLong()
        cache[key]?.let { return it }
        if (cache.size > 32) cache.clear()
        val shift = tintHue - BASE_HUE
        fun t(color: Int) = hiveRetint(color, shift, tintChroma)
        val light = HiveLightNeutrals(
            mistTop = t(mistLight.mistTop), mistBottom = t(mistLight.mistBottom), mist = t(mistLight.mist),
            mistLowest = t(mistLight.mistLowest), mistLow = t(mistLight.mistLow), mistContainer = t(mistLight.mistContainer),
            mistHigh = t(mistLight.mistHigh), mistHighest = t(mistLight.mistHighest),
            ink = t(mistLight.ink), inkMuted = t(mistLight.inkMuted), inkFaint = t(mistLight.inkFaint),
            outline = t(mistLight.outline), rule = t(mistLight.rule), shadow = t(mistLight.shadow),
            slate = t(mistLight.slate), slateContainer = t(mistLight.slateContainer),
            inverseSurface = t(mistLight.inverseSurface), onInverseSurface = t(mistLight.onInverseSurface),
            scrim = t(mistLight.scrim),
        )
        val dark = HiveDarkNeutrals(
            nightTop = t(mistDark.nightTop), nightBottom = t(mistDark.nightBottom), night = t(mistDark.night),
            nightLowest = t(mistDark.nightLowest), nightLow = t(mistDark.nightLow), nightContainer = t(mistDark.nightContainer),
            nightHigh = t(mistDark.nightHigh), nightHighest = t(mistDark.nightHighest),
            paper = t(mistDark.paper), paperMuted = t(mistDark.paperMuted), paperFaint = t(mistDark.paperFaint),
            outline = t(mistDark.outline), rule = t(mistDark.rule), shadow = t(mistDark.shadow),
            slateLift = t(mistDark.slateLift), onSlateLift = t(mistDark.onSlateLift),
            scrim = mistDark.scrim,
        )
        return (light to dark).also { cache[key] = it }
    }
}

/**
 * 一套强调色：强调色的浅深两套色阶，加中性色往哪个色调上染。
 * 浅色主色上用白字（≥4.5），深色主色上用同色相深墨字（≥7）。
 */
data class HiveAccent(
    val id: String,
    val label: String,
    val seed: Int, val pressed: Int,
    val container: Int, val onContainer: Int,
    val bubble: Int, val glow: Int,
    val seedDark: Int, val pressedDark: Int, val onSeedDark: Int,
    val containerDark: Int, val onContainerDark: Int,
    val bubbleDark: Int, val glowDark: Int,
    val tintHue: Double, val tintChroma: Double,
    val haze: Int, val hazeDark: Int,
) {
    companion object {
        /** 浅色主色上的文字，所有配色都是白字。 */
        const val ON_SEED = 0xFFFFFFFF.toInt()

        private val mist = HiveAccent(
            id = "mist", label = "雾蓝",
            seed = 0xFF4B67B4.toInt(), pressed = 0xFF3D569D.toInt(),
            container = 0xFFDEE8FF.toInt(), onContainer = 0xFF213262.toInt(),
            bubble = 0xFFD9E4FD.toInt(), glow = 0xFFABC3FC.toInt(),
            seedDark = 0xFF97B1F0.toInt(), pressedDark = 0xFF859EDC.toInt(), onSeedDark = 0xFF121C34.toInt(),
            containerDark = 0xFF2E3C5F.toInt(), onContainerDark = 0xFFD8E1F6.toInt(),
            bubbleDark = 0xFF293144.toInt(), glowDark = 0xFF51679D.toInt(),
            tintHue = HiveNeutrals.BASE_HUE, tintChroma = 1.0,
            haze = 0xFFD5CBEB.toInt(), hazeDark = 0xFF514765.toInt(),
        )

        private val daiqing = HiveAccent(
            id = "daiqing", label = "黛青",
            seed = 0xFF236185.toInt(), pressed = 0xFF145071.toInt(),
            container = 0xFFDBEAF5.toInt(), onContainer = 0xFF123950.toInt(),
            bubble = 0xFFD6E7F3.toInt(), glow = 0xFFA2CAE5.toInt(),
            seedDark = 0xFF8CB9D7.toInt(), pressedDark = 0xFF79A6C4.toInt(), onSeedDark = 0xFF0D1F2B.toInt(),
            containerDark = 0xFF274152.toInt(), onContainerDark = 0xFFD5E3ED.toInt(),
            bubbleDark = 0xFF26333C.toInt(), glowDark = 0xFF466E88.toInt(),
            tintHue = 238.0, tintChroma = 0.9,
            haze = 0xFFC7D0EC.toInt(), hazeDark = 0xFF444C66.toInt(),
        )

        private val dusk = HiveAccent(
            id = "dusk", label = "暮紫",
            seed = 0xFF726291.toInt(), pressed = 0xFF61517D.toInt(),
            container = 0xFFEAE5F4.toInt(), onContainer = 0xFF392F4C.toInt(),
            bubble = 0xFFE6E1F1.toInt(), glow = 0xFFC8BDE1.toInt(),
            seedDark = 0xFFB8ABD2.toInt(), pressedDark = 0xFFA598BF.toInt(), onSeedDark = 0xFF1F1A29.toInt(),
            containerDark = 0xFF40394F.toInt(), onContainerDark = 0xFFE2DEEC.toInt(),
            bubbleDark = 0xFF332F3B.toInt(), glowDark = 0xFF6D6284.toInt(),
            tintHue = 295.0, tintChroma = 0.85,
            haze = 0xFFE6C7D7.toInt(), hazeDark = 0xFF5F4353.toInt(),
        )

        private val turquoise = HiveAccent(
            id = "turquoise", label = "松石",
            seed = 0xFF34766F.toInt(), pressed = 0xFF296760.toInt(),
            container = 0xFFDBECE9.toInt(), onContainer = 0xFF123D39.toInt(),
            bubble = 0xFFD6E8E6.toInt(), glow = 0xFFA3CEC8.toInt(),
            seedDark = 0xFF8DBEB7.toInt(), pressedDark = 0xFF7BABA4.toInt(), onSeedDark = 0xFF0D211F.toInt(),
            containerDark = 0xFF274340.toInt(), onContainerDark = 0xFFD6E5E2.toInt(),
            bubbleDark = 0xFF263533.toInt(), glowDark = 0xFF47726D.toInt(),
            tintHue = 190.0, tintChroma = 0.7,
            haze = 0xFFB9D7E3.toInt(), hazeDark = 0xFF34525E.toInt(),
        )

        private val amber = HiveAccent(
            id = "amber", label = "琥珀",
            seed = 0xFF8D6636.toInt(), pressed = 0xFF7A5528.toInt(),
            container = 0xFFF2E6D9.toInt(), onContainer = 0xFF483011.toInt(),
            bubble = 0xFFEEE2D3.toInt(), glow = 0xFFDCBE9D.toInt(),
            seedDark = 0xFFCCAC87.toInt(), pressedDark = 0xFFB99975.toInt(), onSeedDark = 0xFF271A0C.toInt(),
            containerDark = 0xFF4C3A25.toInt(), onContainerDark = 0xFFEADFD3.toInt(),
            bubbleDark = 0xFF392F24.toInt(), glowDark = 0xFF7F6343.toInt(),
            tintHue = 75.0, tintChroma = 0.75,
            haze = 0xFFEAC8C1.toInt(), hazeDark = 0xFF64443D.toInt(),
        )

        private val graphite = HiveAccent(
            id = "graphite", label = "石墨",
            seed = 0xFF2C2E32.toInt(), pressed = 0xFF1D1F23.toInt(),
            container = 0xFFE3E6EC.toInt(), onContainer = 0xFF2C2E32.toInt(),
            bubble = 0xFFE0E3E8.toInt(), glow = 0xFFBBBEC3.toInt(),
            seedDark = 0xFFE5E8ED.toInt(), pressedDark = 0xFFCED1D6.toInt(), onSeedDark = 0xFF191B1E.toInt(),
            containerDark = 0xFF3B3D41.toInt(), onContainerDark = 0xFFDEE1E7.toInt(),
            bubbleDark = 0xFF2E3034.toInt(), glowDark = 0xFF616368.toInt(),
            tintHue = 262.0, tintChroma = 0.25,
            haze = 0xFFCED1D6.toInt(), hazeDark = 0xFF4A4D52.toInt(),
        )

        /** 设置页展示顺序。 */
        val presets = listOf(mist, daiqing, dusk, turquoise, amber, graphite)

        const val CUSTOM_ID = "custom"

        fun preset(id: String?): HiveAccent = presets.firstOrNull { it.id == id } ?: mist

        /** 调色盘取色：HSV → ARGB。 */
        fun hsvToColor(hue: Double, saturation: Float, value: Float): Int {
            val h = (hue % 360.0) / 60.0
            val s = saturation.toDouble().coerceIn(0.0, 1.0)
            val v = value.toDouble().coerceIn(0.0, 1.0)
            val i = h.toInt()
            val f = h - i
            val p = v * (1 - s)
            val q = v * (1 - s * f)
            val t = v * (1 - s * (1 - f))
            val rgb = when (i) {
                0 -> triple(v, t, p)
                1 -> triple(q, v, p)
                2 -> triple(p, v, t)
                3 -> triple(p, q, v)
                4 -> triple(t, p, v)
                else -> triple(v, p, q)
            }
            fun channel(x: Double) = (x * 255).roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (channel(rgb[0]) shl 16) or (channel(rgb[1]) shl 8) or channel(rgb[2])
        }

        private fun triple(a: Double, b: Double, c: Double) = doubleArrayOf(a, b, c)

        /** 色相条用的颜色：[hue] 度、雾蓝的彩度与明度。 */
        fun hueColor(hue: Double, chroma: Double = 0.11): Int =
            HiveOklch(0.53, chroma, hue).toColor()

        /**
         * 从任意颜色现推整套配色（自定义配色的核心）：
         * 取色相与彩度（封顶 0.13）、明度钳制 0.30–0.60、深色主色提亮 0.234，
         * 按固定明度表推出各角色，最后做对比度守卫。
         */
        fun derive(pick: Int, id: String = CUSTOM_ID, label: String = "自定义"): HiveAccent {
            val o = HiveOklch.fromColor(pick)
            val c = minOf(o.c, 0.13)
            val h = o.h
            val k = minOf(1.0, c / 0.1255)
            val tintChroma = 0.8 * k
            val neutrals = HiveNeutrals.of(tintHue = h, tintChroma = tintChroma)
            fun tone(l: Double, ratio: Double) = HiveOklch(l, c * ratio, h).toColor()

            val onSeedDark = tone(0.231, 0.39)
            val lightL = o.l.coerceIn(0.30, 0.60)
            val seed = guard(
                HiveOklch(lightL, c, h),
                dark = false,
                surface = neutrals.first.mistHighest,
                onAccent = ON_SEED,
            )
            val seedDark = guard(
                HiveOklch(minOf(0.93, lightL + 0.234), c * 0.76, h),
                dark = true,
                surface = neutrals.second.nightHighest,
                onAccent = onSeedDark,
            )
            val sl = HiveOklch.fromColor(seed).l
            val sdl = HiveOklch.fromColor(seedDark).l
            return HiveAccent(
                id = id, label = label,
                seed = seed, pressed = tone(sl - 0.06, 0.95),
                container = tone(0.93, 0.26), onContainer = tone(0.33, 0.70),
                bubble = tone(0.918, 0.29), glow = tone(0.819, 0.68),
                seedDark = seedDark, pressedDark = tone(sdl - 0.06, 0.76), onSeedDark = onSeedDark,
                containerDark = tone(0.361, 0.51), onContainerDark = tone(0.909, 0.24),
                bubbleDark = tone(0.314, 0.29), glowDark = tone(0.52, 0.72),
                tintHue = h, tintChroma = tintChroma,
                haze = HiveOklch(0.86, 0.045 * k, h + 33).toColor(),
                hazeDark = HiveOklch(0.42, 0.05 * k, h + 33).toColor(),
            )
        }

        /**
         * 对比度守卫：色相、彩度不动，只沿明度移动，直到
         * 强调色对 surface ≥ 3:1 且 onAccent 对强调色 ≥ 4.5:1。
         * 浅色往暗处走（白字在上），深色往亮处走（深墨字在上）。
         */
        private fun guard(start: HiveOklch, dark: Boolean, surface: Int, onAccent: Int): Int {
            fun ok(l: Double): Boolean {
                val candidate = start.copyWith(l = l).toColor()
                return hiveContrastRatio(candidate, surface) >= 3.0 &&
                    hiveContrastRatio(onAccent, candidate) >= 4.5
            }
            if (ok(start.l)) return start.toColor()
            var pass = if (dark) 1.0 else 0.0
            var fail = start.l
            repeat(24) {
                val mid = (pass + fail) / 2
                if (ok(mid)) pass = mid else fail = mid
            }
            return start.copyWith(l = pass).toColor()
        }
    }
}

/** 由强调色与中性色组装 Material 3 浅色方案（低饱和、去 elevation 染色）。 */
fun hiveLightScheme(accent: HiveAccent, n: HiveLightNeutrals): ColorScheme =
    lightColorScheme(
        primary = Color(accent.seed),
        onPrimary = Color(HiveAccent.ON_SEED),
        primaryContainer = Color(accent.container),
        onPrimaryContainer = Color(accent.onContainer),
        secondary = Color(n.slate),
        onSecondary = Color(0xFFFFFFFF.toInt()),
        secondaryContainer = Color(n.slateContainer),
        onSecondaryContainer = Color(n.ink),
        background = Color(n.mist),
        onBackground = Color(n.ink),
        surface = Color(n.mist),
        onSurface = Color(n.ink),
        surfaceVariant = Color(n.mistContainer),
        onSurfaceVariant = Color(n.inkMuted),
        surfaceContainerLowest = Color(n.mistLowest),
        surfaceContainerLow = Color(n.mistLow),
        surfaceContainer = Color(n.mistContainer),
        surfaceContainerHigh = Color(n.mistHigh),
        surfaceContainerHighest = Color(n.mistHighest),
        surfaceTint = Color.Transparent,
        inversePrimary = Color(accent.seedDark),
        outline = Color(n.outline),
        outlineVariant = Color(n.rule),
        scrim = Color(n.scrim),
    )

/** 由强调色与中性色组装 Material 3 深色方案（深海石板 + 提亮主色）。 */
fun hiveDarkScheme(accent: HiveAccent, n: HiveDarkNeutrals): ColorScheme =
    darkColorScheme(
        primary = Color(accent.seedDark),
        onPrimary = Color(accent.onSeedDark),
        primaryContainer = Color(accent.containerDark),
        onPrimaryContainer = Color(accent.onContainerDark),
        secondary = Color(n.slateLift),
        onSecondary = Color(n.onSlateLift),
        secondaryContainer = Color(n.nightContainer),
        onSecondaryContainer = Color(n.paper),
        background = Color(n.night),
        onBackground = Color(n.paper),
        surface = Color(n.night),
        onSurface = Color(n.paper),
        surfaceVariant = Color(n.nightContainer),
        onSurfaceVariant = Color(n.paperMuted),
        surfaceContainerLowest = Color(n.nightLowest),
        surfaceContainerLow = Color(n.nightLow),
        surfaceContainer = Color(n.nightContainer),
        surfaceContainerHigh = Color(n.nightHigh),
        surfaceContainerHighest = Color(n.nightHighest),
        surfaceTint = Color.Transparent,
        inversePrimary = Color(accent.seed),
        outline = Color(n.outline),
        outlineVariant = Color(n.rule),
        scrim = Color(n.scrim),
    )
