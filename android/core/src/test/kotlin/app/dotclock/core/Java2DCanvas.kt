package app.dotclock.core

import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage

/** 电脑上用 Java2D 实现的画布，用来在没有安卓设备时出预览图、做测试。 */
class Java2DCanvas(private val g: Graphics2D) : DotCanvas {
    private val stack = ArrayList<java.awt.Shape?>()

    init {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    }

    private fun color(c: Int) = Color(c, true)

    override fun fillOval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        g.color = color(color); g.fill(Ellipse2D.Float(x, y, w, h))
    }

    override fun strokeOval(x: Float, y: Float, w: Float, h: Float, stroke: Float, color: Int) {
        g.color = color(color); g.stroke = BasicStroke(stroke); g.draw(Ellipse2D.Float(x, y, w, h))
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        g.color = color(color); g.fill(Rectangle2D.Float(x, y, w, h))
    }

    override fun fillPolygon(xs: FloatArray, ys: FloatArray, color: Int) {
        val p = Path2D.Float()
        p.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) p.lineTo(xs[i], ys[i])
        p.closePath()
        g.color = color(color); g.fill(p)
    }

    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) = RoundRectangle2D.Float(x, y, w, h, 2 * r, 2 * r)

    override fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        g.color = color(color); g.fill(rr(x, y, w, h, radius))
    }

    override fun strokeRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, stroke: Float, color: Int) {
        g.color = color(color); g.stroke = BasicStroke(stroke); g.draw(rr(x, y, w, h, radius))
    }

    private fun gradient(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray) =
        LinearGradientPaint(x0, y0, x1 + 0.01f, y1 + 0.01f, stops, colors.map { color(it) }.toTypedArray())

    override fun fillRoundRectGradient(x: Float, y: Float, w: Float, h: Float, radius: Float, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray) {
        g.paint = gradient(x0, y0, x1, y1, colors, stops); g.fill(rr(x, y, w, h, radius))
    }

    override fun strokeRoundRectGradient(x: Float, y: Float, w: Float, h: Float, radius: Float, stroke: Float, x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray) {
        g.paint = gradient(x0, y0, x1, y1, colors, stops); g.stroke = BasicStroke(stroke); g.draw(rr(x, y, w, h, radius))
    }

    override fun save() { stack += g.clip }
    override fun restore() { g.clip = stack.removeAt(stack.size - 1) }
    override fun clipRect(x: Float, y: Float, w: Float, h: Float) = g.clip(Rectangle2D.Float(x, y, w, h))
    override fun clipRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float) = g.clip(rr(x, y, w, h, radius))

    private fun font(face: Face, size: Float): Font {
        val (name, style) = when (face) {
            Face.SANS_BOLD, Face.ROUNDED_BOLD -> "WenQuanYi Zen Hei" to Font.BOLD
            Face.SANS_SEMIBOLD -> "WenQuanYi Zen Hei" to Font.PLAIN
            Face.MONO_MEDIUM -> Font.MONOSPACED to Font.PLAIN
            Face.CONDENSED_BOLD -> Font.SANS_SERIF to Font.BOLD
            Face.SERIF_BOLD -> Font.SERIF to Font.BOLD
            Face.SERIF -> Font.SERIF to Font.PLAIN
        }
        return Font(name, style, 1).deriveFont(size)
    }

    override fun capHeightRatio(face: Face): Float {
        val f = font(face, 100f)
        val bounds = f.createGlyphVector(g.fontRenderContext, "8").visualBounds
        return (bounds.height / 100).toFloat()
    }

    override fun textWidth(text: String, face: Face, size: Float) = g.getFontMetrics(font(face, size)).stringWidth(text).toFloat()
    override fun ascent(face: Face, size: Float) = g.getFontMetrics(font(face, size)).ascent.toFloat()
    override fun descent(face: Face, size: Float) = g.getFontMetrics(font(face, size)).descent.toFloat()

    override fun drawText(text: String, x: Float, baseline: Float, face: Face, size: Float, color: Int) {
        g.font = font(face, size); g.color = color(color); g.drawString(text, x, baseline)
    }

    companion object {
        /** 用文泉驿字体栅格化中文。 */
        val rasterizer = GlyphRasterizer { ch, n ->
            val image = BufferedImage(n, n, BufferedImage.TYPE_BYTE_GRAY)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            val font = Font("WenQuanYi Zen Hei", Font.PLAIN, n)
            val gv = font.createGlyphVector(g.fontRenderContext, ch)
            val b = gv.visualBounds
            if (b.width <= 0 || b.height <= 0) return@GlyphRasterizer null
            val wide = b.width > n * 0.6
            val x = if (wide) (n - b.width) / 2 - b.x else -b.x
            // 窄字符基线放在倒数第 2 行，和 5×7 字形对齐
            val y = if (wide) (n - b.height) / 2 - b.y else (n - 2).toDouble()
            g.color = Color.WHITE
            g.composite = AlphaComposite.Src
            g.drawGlyphVector(gv, x.toFloat(), y.toFloat())
            g.dispose()
            val raster = image.raster
            Array(n) { r -> BooleanArray(n) { c -> raster.getSample(c, r, 0) > 90 } }
        }
    }
}
