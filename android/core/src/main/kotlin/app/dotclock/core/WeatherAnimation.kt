package app.dotclock.core

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * 天气行背后的点阵动画：雨滴斜落、雪花飘落、云朵漂移、雾气流动、星星闪烁、闪电。移植自 WeatherAnimation.swift。
 * 所有位置都以“格”为单位（1 格 = 3pt × 缩放倍数），并吸附到点阵网格上。
 */
class WeatherPainter(
    private val c: DotCanvas,
    private val originX: Float,
    private val originY: Float,
    private val cell: Float,
    private val square: Boolean,
    private val columns: Int,
    private val rows: Int,
    /** 当天经过的秒数（带小数） */
    private val time: Double,
) {
    companion object {
        /** 稳定的伪随机数（0..<1）：同一个粒子每一帧取到的值相同。 */
        fun random(index: Int, salt: Int): Double {
            var x = (index.toLong() * 374_761_393L + salt.toLong() * 668_265_263L + 1_013_904_223L)
            x = (x xor (x ushr 13)) * 1_274_126_177L
            x = x xor (x ushr 16)
            return java.lang.Long.remainderUnsigned(x, 100_000L) / 100_000.0
        }

        private val cloudShapes = listOf(
            listOf("...###....", ".#######..", "##########"),
            listOf("..##......", ".#####.#..", "#########."),
            listOf("....##..", "..#####.", "########"),
        )

        fun paint(c: DotCanvas, kind: WeatherKind, intensity: Double, theme: Theme, x: Float, y: Float, w: Float, h: Float,
                  cell: Float, square: Boolean, time: Double) {
            val p = WeatherPainter(c, x, y, cell, square, kotlin.math.ceil(w / cell).toInt(), kotlin.math.ceil(h / cell).toInt(), time)
            when (kind) {
                WeatherKind.RAIN -> p.rain(intensity, theme.lit)
                WeatherKind.SNOW -> p.snow(intensity, theme.label)
                WeatherKind.THUNDER -> {
                    p.rain(1.3, theme.lit)
                    p.lightning(theme.accent)
                }
                WeatherKind.CLOUDY -> p.clouds(1.0, theme.sub)
                WeatherKind.PARTLY -> p.clouds(0.6, theme.sub)
                WeatherKind.FOG -> p.fog(theme.sub)
                WeatherKind.CLEAR -> p.sparkle(0.5, theme.accent)
                WeatherKind.CLEAR_NIGHT -> p.sparkle(1.0, theme.label)
            }
        }
    }

    private fun dot(column: Double, row: Double, color: Int, opacity: Double) {
        val col = floor(column)
        val r = floor(row)
        if (col < -1 || r < -1 || col > columns || r > rows || opacity <= 0.02) return
        val d = cell * 0.8f
        val inset = (cell - d) / 2
        val x = originX + col.toFloat() * cell + inset
        val y = originY + r.toFloat() * cell + inset
        val tinted = ColorMath.withAlpha(color, opacity)
        if (square) c.fillRect(x, y, d, d, tinted) else c.fillOval(x, y, d, d, tinted)
    }

    /** 斜向下落的雨滴，每滴带一个淡淡的拖尾。 */
    fun rain(intensity: Double, color: Int) {
        val spacing = 5 / intensity
        val slope = 1.0 // 每下落一格向左偏一格，45° 斜线正好落在点阵网格上
        val span = (rows + 4).toDouble()
        val count = ((columns + rows * slope) / spacing).toInt() + 1
        for (i in 0 until count) {
            val progress = time * (9 + 6 * random(i, 1)) + random(i, 2) * span
            val y = progress % span - 2
            val lap = (progress / span).toInt()
            // 每落完一轮换一个水平位置，看起来不重复
            val x0 = (i + random(i + lap * 31, 3)) * spacing
            val column = floor(x0 - y * slope)
            val row = floor(y)
            dot(column, row, color, 0.75)
            dot(column + 1, row - 1, color, 0.3)
        }
    }

    /** 缓慢飘落、左右摇摆的雪花；少数是十字形的大雪花。 */
    fun snow(intensity: Double, color: Int) {
        val spacing = 6 / intensity
        val span = (rows + 4).toDouble()
        val count = (columns / spacing).toInt() + 2
        for (i in 0 until count) {
            val progress = time * (2.2 + 2 * random(i, 1)) + random(i, 2) * span
            val y = progress % span - 2
            val lap = (progress / span).toInt()
            val x = (i + random(i + lap * 31, 3)) * spacing + sin(time * 0.9 + random(i, 4) * 6.28) * 1.5
            val opacity = 0.35 + 0.4 * random(i, 5)
            dot(x, y, color, opacity)
            if (random(i, 6) < 0.22) {
                for ((dx, dy) in listOf(-1.0 to 0.0, 1.0 to 0.0, 0.0 to -1.0, 0.0 to 1.0)) {
                    dot(floor(x) + dx, floor(y) + dy, color, opacity * 0.6)
                }
            }
        }
    }

    /** 横向漂移的小云朵。 */
    fun clouds(density: Double, color: Int) {
        val count = max(2, (columns / 26.0 * density).toInt() + 1)
        for (i in 0 until count) {
            val shape = cloudShapes[i % cloudShapes.size]
            val width = shape[0].length.toDouble()
            val span = columns + width
            val lane = span / count
            val x = (time * (1 + 1.2 * random(i, 1)) + i * lane + random(i, 2) * lane * 0.5) % span - width
            val y = floor(random(i, 3) * max(1, rows - shape.size - 1)) + 1
            val opacity = 0.22 + 0.2 * random(i, 4)
            for ((r, row) in shape.withIndex()) {
                for ((col, mark) in row.withIndex()) {
                    if (mark == '#') dot(floor(x) + col, y + r, color, opacity)
                }
            }
        }
    }

    /** 几行左右流动的断续点线。 */
    fun fog(color: Int) {
        var row = 1
        var lane = 0
        while (row < rows) {
            val direction = if (lane % 2 == 0) 1.0 else -1.0
            val shift = floor(time * (1.5 + 1.5 * random(lane, 1)) * direction).toInt()
            for (column in 0..columns) {
                // 以 3 格为一段决定这一段亮不亮，形成断续的雾带
                if (random(Math.floorDiv(column + shift, 3), lane + 7) < 0.42) dot(column.toDouble(), row.toDouble(), color, 0.2)
            }
            row += 4
            lane += 1
        }
    }

    /** 位置固定、忽明忽暗的光点：白天是阳光闪烁，夜里是星星。 */
    fun sparkle(density: Double, color: Int) {
        val spacing = 5 / density
        val count = (columns / spacing).toInt() + 1
        for (i in 0 until count) {
            val x = (i + random(i, 1)) * spacing
            val y = random(i, 2) * max(1, rows - 1)
            val glow = sin(time * (0.6 + 1.4 * random(i, 3)) + random(i, 4) * 6.28)
            dot(x, y, color, max(0.0, glow) * 0.7)
        }
    }

    /** 每隔几秒在随机位置闪两下的折线闪电。 */
    fun lightning(color: Int) {
        val period = 3.7
        val phase = time % period
        if (!(phase < 0.1 || (phase > 0.18 && phase < 0.3))) return
        val strike = (time / period).toInt()
        var x = (0.15 + 0.7 * random(strike, 1)) * columns
        for (row in 0 until rows) {
            dot(x, row.toDouble(), color, 0.95)
            // 折线：每隔两三格换一次方向
            x += if (random(strike + row / 3, 2) < 0.5) -1 else 1
        }
    }
}
