package app.dotclock.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

// Material You 主题色彩生成：CAM16 / HCT 色彩空间 + TonalSpot 色调盘。
// 移植自 macOS 版 Monet.swift，算法对应 Google material-color-utilities 的 Cam16、Hct、SchemeTonalSpot。
// 颜色统一用 ARGB Int 表示。

object ColorMath {
    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun alpha(c: Int) = (c ushr 24) and 0xFF

    fun pack(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** 替换透明度，`opacity` 取 0…1。 */
    fun withAlpha(c: Int, opacity: Double): Int {
        val a = (alpha(c) * opacity.coerceIn(0.0, 1.0)).roundToInt()
        return (c and 0x00FFFFFF) or (a shl 24)
    }

    fun linearized(component: Int): Double {
        val n = component / 255.0
        return (if (n <= 0.040449936) n / 12.92 else ((n + 0.055) / 1.055).pow(2.4)) * 100
    }

    fun delinearized(value: Double): Int {
        val n = value / 100
        val d = if (n <= 0.0031308) n * 12.92 else 1.055 * n.pow(1 / 2.4) - 0.055
        return (d * 255).roundToInt().coerceIn(0, 255)
    }

    fun yFromLstar(lstar: Double): Double {
        val ft = (lstar + 16) / 116
        val ft3 = ft * ft * ft
        return 100 * (if (ft3 > 216.0 / 24389) ft3 else (116 * ft - 16) / (24389.0 / 27))
    }

    fun lstar(c: Int): Double {
        val y = (0.2126 * linearized(r(c)) + 0.7152 * linearized(g(c)) + 0.0722 * linearized(b(c))) / 100
        val f = if (y > 216.0 / 24389) cbrt(y) else (24389.0 / 27 * y + 16) / 116
        return 116 * f - 16
    }

    fun gray(tone: Double): Int {
        val v = delinearized(yFromLstar(tone))
        return pack(v, v, v)
    }

    fun xyz(c: Int): DoubleArray {
        val r = linearized(r(c))
        val g = linearized(g(c))
        val b = linearized(b(c))
        return doubleArrayOf(
            0.41233895 * r + 0.35762064 * g + 0.18051042 * b,
            0.2126 * r + 0.7152 * g + 0.0722 * b,
            0.01932141 * r + 0.11916382 * g + 0.95034478 * b,
        )
    }

    fun fromXyz(x: Double, y: Double, z: Double): Int {
        val r = 3.2413774792388685 * x - 1.5376652402851851 * y - 0.49885366846268053 * z
        val g = -0.9691452513005321 * x + 1.8758853451067872 * y + 0.04156585616912061 * z
        val b = 0.05562093689691305 * x - 0.20395524564742123 * y + 1.0571799111220335 * z
        return pack(delinearized(r), delinearized(g), delinearized(b))
    }

    /** "1b6ef3" → 0xFF1B6EF3；不是 6 位十六进制时返回 null。 */
    fun parseHex(hex: String): Int? {
        if (hex.length != 6) return null
        return hex.toIntOrNull(16)?.let { it or (0xFF shl 24) }
    }

    fun toHex(c: Int): String = "%06x".format(c and 0xFFFFFF)
}

/** CAM16 默认观察条件（sRGB、D65、背景 L*=50）。 */
private object ViewingConditions {
    val n: Double
    val aw: Double
    val nbb: Double
    val ncb: Double
    const val c = 0.69
    const val nc = 1.0
    val fl: Double
    val fLRoot: Double
    val z: Double
    val rgbD: DoubleArray

    init {
        val white = doubleArrayOf(95.047, 100.0, 108.883)
        val la = 200 / PI * ColorMath.yFromLstar(50.0) / 100
        val rW = white[0] * 0.401288 + white[1] * 0.650173 + white[2] * -0.051461
        val gW = white[0] * -0.250268 + white[1] * 1.204414 + white[2] * 0.045854
        val bW = white[0] * -0.002079 + white[1] * 0.048952 + white[2] * 0.953127
        val f = 1.0
        val d = (f * (1 - (1 / 3.6) * exp((-la - 42) / 92))).coerceIn(0.0, 1.0)
        rgbD = doubleArrayOf(d * (100 / rW) + 1 - d, d * (100 / gW) + 1 - d, d * (100 / bW) + 1 - d)
        val k = 1 / (5 * la + 1)
        val k4 = k * k * k * k
        val k4F = 1 - k4
        fl = k4 * la + 0.1 * k4F * k4F * cbrt(5 * la)
        n = ColorMath.yFromLstar(50.0) / white[1]
        z = 1.48 + sqrt(n)
        nbb = 0.725 / n.pow(0.2)
        ncb = nbb
        val factors = doubleArrayOf(
            (fl * rgbD[0] * rW / 100).pow(0.42),
            (fl * rgbD[1] * gW / 100).pow(0.42),
            (fl * rgbD[2] * bW / 100).pow(0.42),
        )
        val rgbA = factors.map { 400 * it / (it + 27.13) }
        aw = (2 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb
        fLRoot = fl.pow(0.25)
    }
}

class Cam16(color: Int) {
    val hue: Double
    val chroma: Double
    val j: Double

    init {
        val vc = ViewingConditions
        val (x, y, z) = ColorMath.xyz(color).let { Triple(it[0], it[1], it[2]) }
        val rD = vc.rgbD[0] * (x * 0.401288 + y * 0.650173 + z * -0.051461)
        val gD = vc.rgbD[1] * (x * -0.250268 + y * 1.204414 + z * 0.045854)
        val bD = vc.rgbD[2] * (x * -0.002079 + y * 0.048952 + z * 0.953127)
        fun adapt(v: Double): Double {
            val af = (vc.fl * abs(v) / 100).pow(0.42)
            return sign(v) * 400 * af / (af + 27.13)
        }
        val rA = adapt(rD)
        val gA = adapt(gD)
        val bA = adapt(bD)
        val a = (11 * rA - 12 * gA + bA) / 11
        val b = (rA + gA - 2 * bA) / 9
        val u = (20 * rA + 20 * gA + 21 * bA) / 20
        val p2 = (40 * rA + 20 * gA + bA) / 20
        var h = atan2(b, a) * 180 / PI
        if (h < 0) h += 360
        if (h >= 360) h -= 360
        val ac = p2 * vc.nbb
        val jj = 100 * (ac / vc.aw).pow(vc.c * vc.z)
        val huePrime = if (h < 20.14) h + 360 else h
        val eHue = 0.25 * (cos(huePrime * PI / 180 + 2) + 3.8)
        val p1 = 50000.0 / 13 * eHue * vc.nc * vc.ncb
        val t = p1 * hypot(a, b) / (u + 0.305)
        val alpha = (1.64 - 0.29.pow(vc.n)).pow(0.73) * t.pow(0.9)
        hue = h
        j = jj
        chroma = alpha * sqrt(jj / 100)
    }

    companion object {
        /** J/C/h 反解回 sRGB（超出色域的分量会被裁剪）。 */
        fun rgb(j: Double, chroma: Double, hue: Double): Int {
            val vc = ViewingConditions
            val alpha = if (chroma == 0.0 || j == 0.0) 0.0 else chroma / sqrt(j / 100)
            val t = (alpha / (1.64 - 0.29.pow(vc.n)).pow(0.73)).pow(1 / 0.9)
            val hRad = hue * PI / 180
            val eHue = 0.25 * (cos(hRad + 2) + 3.8)
            val ac = vc.aw * (j / 100).pow(1 / vc.c / vc.z)
            val p1 = eHue * (50000.0 / 13) * vc.nc * vc.ncb
            val p2 = ac / vc.nbb
            val hSin = sin(hRad)
            val hCos = cos(hRad)
            val gamma = 23 * (p2 + 0.305) * t / (23 * p1 + 11 * t * hCos + 108 * t * hSin)
            val a = gamma * hCos
            val b = gamma * hSin
            val rA = (460 * p2 + 451 * a + 288 * b) / 1403
            val gA = (460 * p2 - 891 * a - 261 * b) / 1403
            val bA = (460 * p2 - 220 * a - 6300 * b) / 1403
            fun unadapt(v: Double): Double {
                val base = maxOf(0.0, 27.13 * abs(v) / (400 - abs(v)))
                return sign(v) * (100 / vc.fl) * base.pow(1 / 0.42)
            }
            val rF = unadapt(rA) / vc.rgbD[0]
            val gF = unadapt(gA) / vc.rgbD[1]
            val bF = unadapt(bA) / vc.rgbD[2]
            return ColorMath.fromXyz(
                1.86206786 * rF - 1.01125463 * gF + 0.14918677 * bF,
                0.38752654 * rF + 0.62144744 * gF - 0.00897398 * bF,
                -0.01584150 * rF - 0.03412294 * gF + 1.04996444 * bF,
            )
        }
    }
}

object Hct {
    /** 给定色相、色度、色调（L*）求 sRGB；色度超出色域时保持色相和色调，尽量取最大色度。 */
    fun rgb(hue: Double, chroma: Double, tone: Double): Int {
        if (chroma < 1 || tone.roundToInt() <= 0 || tone.roundToInt() >= 100) return ColorMath.gray(tone)
        var h = hue % 360
        if (h < 0) h += 360
        solve(h, chroma, tone)?.let { return it }
        var low = 0.0
        var high = chroma
        var answer: Int? = null
        while (high - low >= 0.4) {
            val mid = low + (high - low) / 2
            val hit = solve(h, mid, tone)
            if (hit != null) {
                answer = hit
                low = mid
            } else {
                high = mid
            }
        }
        return answer ?: ColorMath.gray(tone)
    }

    private fun solve(hue: Double, chroma: Double, tone: Double): Int? {
        var low = 0.0
        var high = 100.0
        var best: Int? = null
        var bestError = 1.0
        while (high - low > 0.01) {
            val mid = low + (high - low) / 2
            val clipped = Cam16.rgb(mid, chroma, hue)
            val l = ColorMath.lstar(clipped)
            if (abs(tone - l) < 0.2) {
                // 裁剪后色相偏移量（CAM16-UCS 距离）
                val cam = Cam16(clipped)
                val m = ln(1 + 0.0228 * cam.chroma * ViewingConditions.fLRoot) / 0.0228
                val dh = (cam.hue - hue) * PI / 180
                val error = abs(2 * m * sin(dh / 2))
                if (error <= bestError) {
                    bestError = error
                    best = clipped
                }
            }
            if (l < tone) low = mid else high = mid
        }
        return best
    }
}

/** 小组件用到的颜色角色（ARGB）。 */
data class Theme(
    val surface: Int,
    val border: Int,
    val digits: Int,
    val accent: Int,
    val label: Int,
    val sub: Int,
    val dim: Int,
    val lit: Int,
) {
    /**
     * 玻璃风格：底色变成半透明的着色层，分隔线和暗点改为半透明白 / 黑，
     * 让背后的壁纸透过来，同时保证文字在任意壁纸上都看得清。
     */
    fun glassy(dark: Boolean, tint: Int? = null, opacity: Double = 0.3): Theme = copy(
        surface = ColorMath.withAlpha(tint ?: surface, opacity),
        border = if (dark) ColorMath.withAlpha(WHITE, 0.14) else ColorMath.withAlpha(BLACK, 0.08),
        dim = if (dark) ColorMath.withAlpha(WHITE, 0.16) else ColorMath.withAlpha(BLACK, 0.10),
    )

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}

class ThemePair(seed: Int) {
    val dark: Theme
    val light: Theme

    init {
        // TonalSpot：A1 主色、A3 第三色（色相 +60）、N1/N2 中性色。
        val hue = Cam16(seed).hue
        fun a1(t: Double) = Hct.rgb(hue, 36.0, t)
        fun a3(t: Double) = Hct.rgb(hue + 60, 24.0, t)
        fun n1(t: Double) = Hct.rgb(hue, 6.0, t)
        fun n2(t: Double) = Hct.rgb(hue, 8.0, t)
        dark = Theme(
            surface = n1(6.0), border = n2(20.0), digits = a1(90.0), accent = a3(70.0),
            label = n2(80.0), sub = n2(60.0), dim = n2(22.0), lit = a1(80.0),
        )
        light = Theme(
            surface = n1(96.0), border = n2(87.0), digits = a1(20.0), accent = a3(45.0),
            label = n2(30.0), sub = n2(50.0), dim = n2(85.0), lit = a1(40.0),
        )
    }

    fun pick(dark: Boolean) = if (dark) this.dark else light

    companion object {
        const val FALLBACK_SEED = 0xFF1B6EF3.toInt()
        const val DEFAULT_SEED_HEX = "1b6ef3"

        private val cache = object : LinkedHashMap<Int, ThemePair>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ThemePair>?) = size > 8
        }

        /** 计算一套配色要做上百次二分求解，结果缓存起来，小组件每分钟刷新时直接复用。 */
        fun of(seed: Int): ThemePair = synchronized(cache) { cache.getOrPut(seed) { ThemePair(seed) } }
    }
}

object SeedPreset {
    val all: List<Pair<String, Int>> = listOf(
        "Nothing 红" to 0xFFD71921.toInt(), "海蓝" to 0xFF1B6EF3.toInt(), "森绿" to 0xFF2E7D32.toInt(),
        "薰紫" to 0xFF6750A4.toInt(), "琥珀" to 0xFFF59E0B.toInt(), "樱粉" to 0xFFE91E63.toInt(),
    )
}
