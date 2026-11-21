package app.dotclock.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import app.dotclock.core.DotCanvas
import app.dotclock.core.Face
import app.dotclock.core.GlyphRasterizer

/** 用 android.graphics.Canvas 实现的画布。canvas 为 null 时只用来量尺寸。App 界面和桌面小组件共用。 */
class AndroidDotCanvas(var canvas: Canvas? = null) : DotCanvas {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { fontFeatureSettings = "tnum" }
    private val rect = RectF()
    private val path = Path()

    override fun fillOval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.shader = null; fill.color = color
        canvas?.drawOval(x, y, x + w, y + h, fill)
    }

    override fun strokeOval(x: Float, y: Float, w: Float, h: Float, stroke: Float, color: Int) {
        this.stroke.shader = null; this.stroke.color = color; this.stroke.strokeWidth = stroke
        canvas?.drawOval(x, y, x + w, y + h, this.stroke)
    }

    override fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        fill.shader = null; fill.color = color
        canvas?.drawRect(x, y, x + w, y + h, fill)
    }

    override fun fillPolygon(xs: FloatArray, ys: FloatArray, color: Int) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) path.lineTo(xs[i], ys[i])
        path.close()
        fill.shader = null; fill.color = color
        canvas?.drawPath(path, fill)
    }

    override fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int) {
        fill.shader = null; fill.color = color
        rect.set(x, y, x + w, y + h)
        canvas?.drawRoundRect(rect, radius, radius, fill)
    }

    override fun strokeRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, stroke: Float, color: Int) {
        this.stroke.shader = null; this.stroke.color = color; this.stroke.strokeWidth = stroke
        rect.set(x, y, x + w, y + h)
        canvas?.drawRoundRect(rect, radius, radius, this.stroke)
    }

    override fun fillRoundRectGradient(
        x: Float, y: Float, w: Float, h: Float, radius: Float,
        x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray,
    ) {
        fill.shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
        fill.color = 0xFF000000.toInt()
        rect.set(x, y, x + w, y + h)
        canvas?.drawRoundRect(rect, radius, radius, fill)
        fill.shader = null
    }

    override fun strokeRoundRectGradient(
        x: Float, y: Float, w: Float, h: Float, radius: Float, stroke: Float,
        x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray,
    ) {
        this.stroke.shader = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
        this.stroke.color = 0xFF000000.toInt()
        this.stroke.strokeWidth = stroke
        rect.set(x, y, x + w, y + h)
        canvas?.drawRoundRect(rect, radius, radius, this.stroke)
        this.stroke.shader = null
    }

    override fun save() {
        canvas?.save()
    }

    override fun restore() {
        canvas?.restore()
    }

    override fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        canvas?.clipRect(x, y, x + w, y + h)
    }

    override fun clipRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        path.reset()
        path.addRoundRect(x, y, x + w, y + h, radius, radius, Path.Direction.CW)
        canvas?.clipPath(path)
    }

    private fun setFont(face: Face, size: Float): Paint {
        text.typeface = typeface(face)
        text.textSize = size
        return text
    }

    override fun capHeightRatio(face: Face): Float = capRatios.getOrPut(face) {
        val bounds = Rect()
        setFont(face, 100f).getTextBounds("8", 0, 1, bounds)
        bounds.height() / 100f
    }

    override fun textWidth(text: String, face: Face, size: Float) = setFont(face, size).measureText(text)
    override fun ascent(face: Face, size: Float) = -setFont(face, size).fontMetrics.ascent
    override fun descent(face: Face, size: Float) = setFont(face, size).fontMetrics.descent

    override fun drawText(text: String, x: Float, baseline: Float, face: Face, size: Float, color: Int) {
        val paint = setFont(face, size)
        paint.color = color
        canvas?.drawText(text, x, baseline, paint)
    }

    companion object {
        private val capRatios = HashMap<Face, Float>()
        private val faces = HashMap<Face, Typeface>()

        fun typeface(face: Face): Typeface = faces.getOrPut(face) {
            when (face) {
                Face.SANS_BOLD -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                Face.SANS_SEMIBOLD -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                Face.ROUNDED_BOLD -> Typeface.create("sans-serif-black", Typeface.NORMAL)
                Face.MONO_MEDIUM -> Typeface.MONOSPACE
                Face.CONDENSED_BOLD -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                Face.SERIF_BOLD -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
                Face.SERIF -> Typeface.SERIF
            }
        }

        /** 把中文等字符画进 n×n 位图，阈值化成点阵（对应 macOS 版用 CoreText 栅格化 PingFang）。 */
        val rasterizer = GlyphRasterizer { character, n ->
            val paint = Paint().apply {
                isAntiAlias = true
                textSize = n.toFloat()
                color = 0xFFFFFFFF.toInt()
                typeface = Typeface.DEFAULT
            }
            val bounds = Rect()
            paint.getTextBounds(character, 0, character.length, bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return@GlyphRasterizer null
            val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
            val c = Canvas(bitmap)
            // 全角字居中；窄字符按基线对齐，避免标点被抬到中间
            val wide = bounds.width() > n * 0.6f
            val x = if (wide) (n - bounds.width()) / 2f - bounds.left else -bounds.left.toFloat()
            val y = if (wide) (n - bounds.height()) / 2f - bounds.top else (n - 2).toFloat()
            c.drawText(character, x, y, paint)
            val result = Array(n) { r -> BooleanArray(n) { col -> (bitmap.getPixel(col, r) ushr 24) > 90 } }
            bitmap.recycle()
            result
        }
    }
}
