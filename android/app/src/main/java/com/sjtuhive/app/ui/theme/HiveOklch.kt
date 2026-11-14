package com.sjtuhive.app.ui.theme

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OKLCH 颜色：明度 l（0–1）、彩度 c、色相 h（度）。
 *
 * 整套配色推导都建立在这套空间上：同一明度在任何色相下视觉亮度一致，
 * 所以「换色相、留明度」就能让每套配色的对比度落在同一档。
 * 公式为 Björn Ottosson 的 OKLab（公有领域），与 dsh-Go 的实现一致。
 */
data class HiveOklch(val l: Double, val c: Double, val h: Double) {

    fun copyWith(l: Double? = null, c: Double? = null, h: Double? = null) =
        HiveOklch(l ?: this.l, c ?: this.c, h ?: this.h)

    /** 转回 ARGB。超出色域时只降彩度（明度、色相不动），对比度不受影响。 */
    fun toColor(alpha: Int = 0xFF): Int {
        val ll = l.coerceIn(0.0, 1.0)
        var cc = maxOf(0.0, c)
        var rgb = linear(ll, cc, h)
        if (!inGamut(rgb)) {
            var lo = 0.0
            var hi = cc
            repeat(24) {
                val mid = (lo + hi) / 2
                if (inGamut(linear(ll, mid, h))) lo = mid else hi = mid
            }
            cc = lo
            rgb = linear(ll, cc, h)
        }
        fun channel(v: Double) = (gamma(v.coerceIn(0.0, 1.0)) * 255).roundToInt().coerceIn(0, 255)
        return (alpha shl 24) or (channel(rgb[0]) shl 16) or (channel(rgb[1]) shl 8) or channel(rgb[2])
    }

    companion object {
        fun fromColor(color: Int): HiveOklch {
            val r = toLinear(((color shr 16) and 0xFF) / 255.0)
            val g = toLinear(((color shr 8) and 0xFF) / 255.0)
            val b = toLinear((color and 0xFF) / 255.0)
            val lp = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
            val mp = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
            val sp = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
            val l = 0.2104542553 * lp + 0.7936177850 * mp - 0.0040720468 * sp
            val a = 1.9779984951 * lp - 2.4285922050 * mp + 0.4505937099 * sp
            val bb = 0.0259040371 * lp + 0.7827717662 * mp - 0.8086757660 * sp
            val c = sqrt(a * a + bb * bb)
            val h = (atan2(bb, a) * 180 / Math.PI).mod(360.0)
            return HiveOklch(l, c, h)
        }

        private fun linear(l: Double, c: Double, h: Double): DoubleArray {
            val rad = h * Math.PI / 180
            val a = c * cos(rad)
            val b = c * sin(rad)
            val lp = l + 0.3963377774 * a + 0.2158037573 * b
            val mp = l - 0.1055613458 * a - 0.0638541728 * b
            val sp = l - 0.0894841775 * a - 1.2914855480 * b
            val l3 = lp * lp * lp
            val m3 = mp * mp * mp
            val s3 = sp * sp * sp
            return doubleArrayOf(
                4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3,
                -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3,
                -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3,
            )
        }

        private fun inGamut(rgb: DoubleArray): Boolean {
            val eps = 1e-4
            return rgb.all { it >= -eps && it <= 1 + eps }
        }

        private fun cbrt(v: Double): Double =
            if (v < 0) -(-v).pow(1.0 / 3.0) else v.pow(1.0 / 3.0)

        private fun toLinear(v: Double): Double =
            if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)

        private fun gamma(v: Double): Double =
            if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }
}

/** WCAG 相对亮度对比度。 */
fun hiveContrastRatio(a: Int, b: Int): Double {
    fun luminance(color: Int): Double {
        fun channel(v: Double): Double =
            if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        val r = channel(((color shr 16) and 0xFF) / 255.0)
        val g = channel(((color shr 8) and 0xFF) / 255.0)
        val b = channel((color and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    val la = luminance(a)
    val lb = luminance(b)
    val hi = maxOf(la, lb)
    val lo = minOf(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}

/** 明度不变，色相转 hueShift 度、彩度乘 chromaScale。中性色跟着配色走全靠它。 */
fun hiveRetint(color: Int, hueShift: Double, chromaScale: Double): Int {
    if (hueShift == 0.0 && chromaScale == 1.0) return color
    val o = HiveOklch.fromColor(color)
    return o.copyWith(c = o.c * chromaScale, h = (o.h + hueShift).mod(360.0)).toColor()
}
