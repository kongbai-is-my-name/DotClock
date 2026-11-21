package app.dotclock.core

/** 一个点阵字形：rows[行][列]。 */
class Glyph(val width: Int, val rows: List<BooleanArray>)

/** 把内置字形之外的字符（中文等）栅格化成 n×n 点阵，由平台实现（Android 用 Paint，电脑上用 Java2D）。 */
fun interface GlyphRasterizer {
    /** 返回 size×size 的灰度阈值结果，行优先；字符画不出来时返回 null。 */
    fun rasterize(character: String, size: Int): Array<BooleanArray>?
}

/** 5×7 点阵字形（Nothing 风格的点阵显示）。移植自 macOS 版 DotMatrix.swift。 */
object DotFont {
    const val RASTER_SIZE = 12

    private val source = mapOf(
        '0' to "01110|10001|10011|10101|11001|10001|01110",
        '1' to "00100|01100|00100|00100|00100|00100|01110",
        '2' to "01110|10001|00001|00010|00100|01000|11111",
        '3' to "11111|00010|00100|00010|00001|10001|01110",
        '4' to "00010|00110|01010|10010|11111|00010|00010",
        '5' to "11111|10000|11110|00001|00001|10001|01110",
        '6' to "00110|01000|10000|11110|10001|10001|01110",
        '7' to "11111|00001|00010|00100|01000|01000|01000",
        '8' to "01110|10001|10001|01110|10001|10001|01110",
        '9' to "01110|10001|10001|01111|00001|00010|01100",
        'A' to "01110|10001|10001|10001|11111|10001|10001",
        'B' to "11110|10001|10001|11110|10001|10001|11110",
        'C' to "01110|10001|10000|10000|10000|10001|01110",
        'D' to "11100|10010|10001|10001|10001|10010|11100",
        'E' to "11111|10000|10000|11110|10000|10000|11111",
        'F' to "11111|10000|10000|11110|10000|10000|10000",
        'G' to "01110|10001|10000|10111|10001|10001|01111",
        'H' to "10001|10001|10001|11111|10001|10001|10001",
        'I' to "01110|00100|00100|00100|00100|00100|01110",
        'J' to "00111|00010|00010|00010|00010|10010|01100",
        'K' to "10001|10010|10100|11000|10100|10010|10001",
        'L' to "10000|10000|10000|10000|10000|10000|11111",
        'M' to "10001|11011|10101|10101|10001|10001|10001",
        'N' to "10001|10001|11001|10101|10011|10001|10001",
        'O' to "01110|10001|10001|10001|10001|10001|01110",
        'P' to "11110|10001|10001|11110|10000|10000|10000",
        'Q' to "01110|10001|10001|10001|10101|10010|01101",
        'R' to "11110|10001|10001|11110|10100|10010|10001",
        'S' to "01111|10000|10000|01110|00001|00001|11110",
        'T' to "11111|00100|00100|00100|00100|00100|00100",
        'U' to "10001|10001|10001|10001|10001|10001|01110",
        'V' to "10001|10001|10001|10001|10001|01010|00100",
        'W' to "10001|10001|10001|10101|10101|10101|01010",
        'X' to "10001|10001|01010|00100|01010|10001|10001",
        'Y' to "10001|10001|10001|01010|00100|00100|00100",
        'Z' to "11111|00001|00010|00100|01000|10000|11111",
        ':' to "000|000|010|000|010|000|000",
        '.' to "000|000|000|000|000|000|010",
        '+' to "00000|00100|00100|11111|00100|00100|00000",
        '%' to "11001|11010|00010|00100|01000|01011|10011",
        '°' to "010|101|010|000|000|000|000",
        '/' to "00001|00001|00010|00100|01000|10000|10000",
        '-' to "00000|00000|00000|11111|00000|00000|00000",
        ' ' to "000|000|000|000|000|000|000",
        '\'' to "1|1|0|0|0|0|0",
    )

    private val glyphs: Map<Char, Glyph> = source.mapValues { (_, text) ->
        val rows = text.split('|').map { row -> BooleanArray(row.length) { row[it] == '1' } }
        Glyph(rows[0].size, rows)
    }

    private val space = glyphs.getValue(' ')
    private val rasterCache = HashMap<String, Glyph>()

    @Volatile
    var rasterizer: GlyphRasterizer? = null

    /** 按字素（而不是 UTF-16 单元）拆分，表情、生僻字也不会被切开。 */
    fun split(text: String): List<String> {
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            out += text.substring(i, i + n)
            i += n
        }
        return out
    }

    fun glyph(character: String): Glyph {
        if (character.length == 1) {
            val c = character[0]
            glyphs[c]?.let { return it }
            glyphs[c.uppercaseChar()]?.let { return it }
        }
        synchronized(rasterCache) {
            rasterCache[character]?.let { return it }
            val glyph = rasterize(character)
            rasterCache[character] = glyph
            return glyph
        }
    }

    private fun rasterize(character: String): Glyph {
        val n = RASTER_SIZE
        val pixels = rasterizer?.rasterize(character, n) ?: return space
        if (pixels.none { row -> row.any { it } }) return space
        // 全角字居中保留整格；窄字符裁掉右侧空列
        var width = n
        while (width > 1 && pixels.none { it[width - 1] }) width--
        val wide = width > n * 0.6
        if (wide) return Glyph(n, pixels.toList())
        return Glyph(width, pixels.map { it.copyOf(width) })
    }
}
