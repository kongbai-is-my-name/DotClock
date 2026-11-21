package app.dotclock.core

/**
 * 平台无关的画布。坐标单位是像素，y 轴向下。
 * Android 上由 android.graphics.Canvas 实现（App 与桌面小组件共用），电脑上由 Java2D 实现（出预览图）。
 */
interface DotCanvas {
    fun fillOval(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun strokeOval(x: Float, y: Float, w: Float, h: Float, stroke: Float, color: Int)
    fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int)
    /** 填充多边形，xs / ys 为顶点坐标 */
    fun fillPolygon(xs: FloatArray, ys: FloatArray, color: Int)
    fun fillRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Int)
    fun strokeRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, stroke: Float, color: Int)

    /** 线性渐变填充圆角矩形，渐变从 (x0, y0) 到 (x1, y1) */
    fun fillRoundRectGradient(
        x: Float, y: Float, w: Float, h: Float, radius: Float,
        x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray,
    )

    /** 线性渐变描边圆角矩形（玻璃高光边） */
    fun strokeRoundRectGradient(
        x: Float, y: Float, w: Float, h: Float, radius: Float, stroke: Float,
        x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray,
    )

    fun save()
    fun restore()
    fun clipRect(x: Float, y: Float, w: Float, h: Float)
    fun clipRoundRect(x: Float, y: Float, w: Float, h: Float, radius: Float)

    /** 字体的大写字高 / 字号 比例，用来让数字正好填满点阵数字的高度 */
    fun capHeightRatio(face: Face): Float
    fun textWidth(text: String, face: Face, size: Float): Float
    /** 字体上沿（基线以上）与下沿（基线以下）的高度 */
    fun ascent(face: Face, size: Float): Float
    fun descent(face: Face, size: Float): Float
    fun drawText(text: String, x: Float, baseline: Float, face: Face, size: Float, color: Int)
}
