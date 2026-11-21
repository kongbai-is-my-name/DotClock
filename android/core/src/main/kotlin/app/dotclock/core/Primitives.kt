package app.dotclock.core

import kotlin.math.max
import kotlin.math.min

/** 构建排版节点时共用的上下文：画布（用于量字宽）、缩放（每 pt 多少像素）、外观设置。 */
class Ctx(
    val canvas: DotCanvas,
    /** 1pt 对应的像素数 */
    val z: Float,
    val digitStyle: DigitStyle,
    val textFont: TextFont,
)

/** 点阵文字。`cell` 为每个点的间距（像素）。 */
fun dotText(text: String, cell: Float, color: Int, shape: DotShape = DotShape.ROUND): Node {
    val glyphs = DotFont.split(text).map { DotFont.glyph(it) }
    val columns = glyphs.sumOf { it.width } + max(0, glyphs.size - 1)
    val lineRows = glyphs.maxOfOrNull { it.rows.size } ?: 7
    return Leaf(columns * cell, lineRows * cell) { c, x, y, _, _ ->
        // 小字号的点更饱满一些，保证可读性
        val diameter = cell * (if (cell < 2f) 0.92f else 0.8f)
        val inset = (cell - diameter) / 2
        val ring = max(cell * 0.2f, 0.7f)
        var column = 0
        for (glyph in glyphs) {
            // 5×7 字形与 12 行的中文混排时，落在同一条基线上
            val offset = if (glyph.rows.size < lineRows) lineRows - glyph.rows.size - 1 else 0
            for ((r, row) in glyph.rows.withIndex()) {
                for (col in row.indices) {
                    if (!row[col]) continue
                    val px = x + (column + col) * cell + inset
                    val py = y + (r + offset) * cell + inset
                    when (shape) {
                        DotShape.ROUND -> c.fillOval(px, py, diameter, diameter, color)
                        DotShape.SQUARE -> c.fillRect(px, py, diameter, diameter, color)
                        DotShape.RING -> c.strokeOval(px + ring / 2, py + ring / 2, diameter - ring, diameter - ring, ring, color)
                    }
                }
            }
            column += glyph.width + 1
        }
    }
}

/** 用系统字体画的一行文字，高度为字体行高。 */
fun fontText(ctx: Ctx, text: String, face: Face, size: Float, color: Int): Node {
    val c = ctx.canvas
    val ascent = c.ascent(face, size)
    val height = ascent + c.descent(face, size)
    return Leaf(c.textWidth(text, face, size), height) { canvas, x, y, _, _ ->
        canvas.drawText(text, x, y + ascent, face, size, color)
    }
}

/** 时间、温度等数字，按当前数字样式绘制。占位高度固定为 7 × cell（cell 单位 pt）。 */
fun digits(ctx: Ctx, text: String, cell: Float, color: Int): Node {
    val style = ctx.digitStyle
    val cellPx = cell * ctx.z
    return when {
        style == DigitStyle.SEGMENT -> segmentText(text, cellPx, color)
        style.face != null -> {
            // 让数字的大写字高正好等于 7 × cell，基线对齐到占位框底部
            val face = style.face
            val height = 7 * cellPx
            val size = height / ctx.canvas.capHeightRatio(face)
            Leaf(ctx.canvas.textWidth(text, face, size), height) { c, x, y, _, h ->
                c.drawText(text, x, y + h, face, size, color)
            }
        }
        else -> dotText(text, cellPx, color, style.dotShape)
    }
}

/**
 * 城市名、日期等文字，按当前文字字体绘制。
 * `size` 是字号（pt）；点阵模式下每个点的间距默认为 size / 10，也可用 `dotCell` 单独指定。
 */
fun label(ctx: Ctx, text: String, size: Float, color: Int, dotCell: Float? = null): Node {
    val face = ctx.textFont.face
    return if (face == null) {
        dotText(text, (dotCell ?: (size / 10)) * ctx.z, color,
            if (ctx.digitStyle == DigitStyle.SQUARE) DotShape.SQUARE else DotShape.ROUND)
    } else {
        val scale = when (ctx.textFont) {
            TextFont.SANS -> 0.92f
            TextFont.MONO -> 0.9f
            else -> 1f
        }
        fontText(ctx, text, face, size * scale * ctx.z, color)
    }
}

/** 七段数码管。字符占位与 5×7 点阵一致，所以可以和点阵样式互换而不影响布局。 */
fun segmentText(text: String, cell: Float, color: Int): Node {
    val characters = text.uppercase().toList()
    val widths = characters.map { if (it in ":. °") 3 else 5 }
    val columns = widths.sum() + max(0, characters.size - 1)
    return Leaf(columns * cell, 7 * cell) { c, ox, oy, _, _ ->
        val w = 5 * cell
        val h = 7 * cell
        val t = cell * 1.05f
        val gap = cell * 0.14f
        fun horizontal(x: Float, y: Float) {
            val x1 = x + t / 2 + gap
            val x2 = x + w - t / 2 - gap
            c.fillPolygon(
                floatArrayOf(x1, x1 + t / 2, x2 - t / 2, x2, x2 - t / 2, x1 + t / 2).offset(ox),
                floatArrayOf(y, y - t / 2, y - t / 2, y, y + t / 2, y + t / 2).offset(oy), color,
            )
        }
        fun vertical(x: Float, y1: Float, y2: Float) {
            c.fillPolygon(
                floatArrayOf(x, x + t / 2, x + t / 2, x, x - t / 2, x - t / 2).offset(ox),
                floatArrayOf(y1, y1 + t / 2, y2 - t / 2, y2, y2 - t / 2, y1 + t / 2).offset(oy), color,
            )
        }
        var x = 0f
        for ((index, ch) in characters.withIndex()) {
            when (ch) {
                ':' -> for (y in listOf(h * 0.3f, h * 0.7f)) c.fillRect(ox + x + 1.5f * cell - t / 2, oy + y - t / 2, t, t, color)
                '°' -> {
                    // 小方框形的度数符号
                    val side = 2.6f * cell
                    val bar = cell * 0.7f
                    c.fillRect(ox + x, oy, side, bar, color)
                    c.fillRect(ox + x, oy + side - bar, side, bar, color)
                    c.fillRect(ox + x, oy, bar, side, color)
                    c.fillRect(ox + x + side - bar, oy, bar, side, color)
                }
                '.' -> c.fillRect(ox + x + 1.5f * cell - t / 2, oy + h - t, t, t, color)
                else -> SEGMENTS[ch]?.forEach { segment ->
                    when (segment) {
                        'a' -> horizontal(x, t / 2)
                        'g' -> horizontal(x, h / 2)
                        'd' -> horizontal(x, h - t / 2)
                        'f' -> vertical(x + t / 2, t / 2 + gap, h / 2 - gap)
                        'b' -> vertical(x + w - t / 2, t / 2 + gap, h / 2 - gap)
                        'e' -> vertical(x + t / 2, h / 2 + gap, h - t / 2 - gap)
                        else -> vertical(x + w - t / 2, h / 2 + gap, h - t / 2 - gap)
                    }
                }
            }
            x += (widths[index] + 1) * cell
        }
    }
}

private fun FloatArray.offset(d: Float) = FloatArray(size) { this[it] + d }

private val SEGMENTS = mapOf(
    '0' to "abcdef", '1' to "bc", '2' to "abged", '3' to "abgcd", '4' to "fgbc", '5' to "afgcd",
    '6' to "afgedc", '7' to "abc", '8' to "abcdefg", '9' to "abfgcd", '-' to "g",
    'A' to "abcefg", 'P' to "abefg", 'C' to "adef", 'F' to "aefg", 'E' to "adefg", 'H' to "bcefg", 'L' to "def",
)

/** 60 个点的进度条（秒针）。`current` 为当前点，之前的点亮起。 */
fun secondsStrip(ctx: Ctx, current: Int, theme: Theme): Node = Leaf(0f, 4 * ctx.z, flexible = true) { c, x, y, w, h ->
    val step = w / 60
    val d = min(step * 0.62f, h)
    for (i in 0 until 60) {
        val color = if (i == current) theme.accent else if (i < current) theme.lit else theme.dim
        c.fillOval(x + i * step + (step - d) / 2, y + (h - d) / 2, d, d, color)
    }
}

/** 今日进度：24 个圆点代表 24 小时，已过去的点亮，当前小时按分钟从左向右填充。 */
fun dayProgressBar(ctx: Ctx, fraction: Double, theme: Theme): Node = Leaf(0f, 7 * ctx.z, flexible = true) { c, x, y, w, h ->
    val step = w / 24
    val d = min(step * 0.72f, h)
    val elapsed = fraction.coerceIn(0.0, 1.0) * 24
    val current = min(23, elapsed.toInt())
    for (i in 0 until 24) {
        val rx = x + i * step + (step - d) / 2
        val ry = y + (h - d) / 2
        c.fillOval(rx, ry, d, d, if (i < current) theme.lit else theme.dim)
        if (i == current) {
            c.save()
            c.clipRect(rx, ry, d * (elapsed - current).toFloat(), d)
            c.fillOval(rx, ry, d, d, theme.accent)
            c.restore()
        }
    }
}

/** 11×11 点阵天气图标。 */
fun weatherIcon(ctx: Ctx, rows: List<String>, cell: Float, theme: Theme): Node {
    val px = cell * ctx.z
    val square = ctx.digitStyle.dotShape == DotShape.SQUARE
    return Leaf(11 * px, 11 * px) { c, x, y, _, _ ->
        val d = px * 0.8f
        val inset = (px - d) / 2
        for ((r, row) in rows.withIndex()) {
            for ((col, mark) in row.withIndex()) {
                if (mark == '.') continue
                val color = if (mark == 'o') theme.accent else theme.label
                val rx = x + col * px + inset
                val ry = y + r * px + inset
                if (square) c.fillRect(rx, ry, d, d, color) else c.fillOval(rx, ry, d, d, color)
            }
        }
    }
}
