package app.dotclock.core

/**
 * 极简的排版树，对应 macOS 版 SwiftUI 里用到的 HStack / VStack / Spacer / padding。
 * 所有尺寸都是像素（构建节点时已经乘好缩放倍数）。
 */
data class Size(val w: Float, val h: Float)

abstract class Node {
    /** 能否被拉伸到可用宽度（Spacer、进度条等） */
    open val flexible: Boolean get() = false

    /** 在可用宽度 `avail` 下的尺寸；可拉伸节点返回最小宽度 */
    abstract fun measure(avail: Float): Size

    abstract fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float)
}

class Leaf(
    private val w: Float,
    private val h: Float,
    override val flexible: Boolean = false,
    private val painter: (c: DotCanvas, x: Float, y: Float, w: Float, h: Float) -> Unit = { _, _, _, _, _ -> },
) : Node() {
    override fun measure(avail: Float) = Size(w, h)
    override fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float) = painter(c, x, y, w, h)
}

fun spacer(minLength: Float = 0f) = Leaf(minLength, 0f, flexible = true)

enum class VAlign { TOP, CENTER, BOTTOM }
enum class HAlign { LEADING, CENTER, TRAILING }

class HStack(
    private val children: List<Node>,
    private val spacing: Float = 0f,
    private val align: VAlign = VAlign.CENTER,
) : Node() {
    override val flexible get() = children.any { it.flexible }

    private fun fixedWidth(avail: Float) =
        children.sumOf { it.measure(avail).w.toDouble() }.toFloat() + spacing * (children.size - 1).coerceAtLeast(0)

    override fun measure(avail: Float): Size {
        val fixed = fixedWidth(avail)
        val h = children.maxOfOrNull { it.measure(avail).h } ?: 0f
        return Size(fixed, h)
    }

    override fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float) {
        val flexCount = children.count { it.flexible }
        val extra = if (flexCount > 0) ((w - fixedWidth(w)) / flexCount).coerceAtLeast(0f) else 0f
        var cx = x
        for (child in children) {
            val size = child.measure(w)
            val cw = size.w + if (child.flexible) extra else 0f
            val cy = when (align) {
                VAlign.TOP -> y
                VAlign.CENTER -> y + (h - size.h) / 2
                VAlign.BOTTOM -> y + h - size.h
            }
            child.draw(c, cx, cy, cw, size.h)
            cx += cw + spacing
        }
    }
}

class VStack(
    private val children: List<Node>,
    private val spacing: Float = 0f,
    private val align: HAlign = HAlign.LEADING,
) : Node() {
    override val flexible get() = children.any { it.flexible }

    override fun measure(avail: Float): Size {
        var w = 0f
        var h = 0f
        for (child in children) {
            val s = child.measure(avail)
            w = maxOf(w, if (child.flexible) avail else s.w)
            h += s.h
        }
        return Size(w, h + spacing * (children.size - 1).coerceAtLeast(0))
    }

    override fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float) {
        var cy = y
        for (child in children) {
            val s = child.measure(w)
            val cw = if (child.flexible) w else s.w
            val cx = when (align) {
                HAlign.LEADING -> x
                HAlign.CENTER -> x + (w - cw) / 2
                HAlign.TRAILING -> x + w - cw
            }
            child.draw(c, cx, cy, cw, s.h)
            cy += s.h + spacing
        }
    }
}

class Padding(
    private val child: Node,
    private val left: Float = 0f,
    private val top: Float = 0f,
    private val right: Float = 0f,
    private val bottom: Float = 0f,
) : Node() {
    override val flexible get() = child.flexible

    override fun measure(avail: Float): Size {
        val s = child.measure(avail - left - right)
        return Size(s.w + left + right, s.h + top + bottom)
    }

    override fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float) =
        child.draw(c, x + left, y + top, w - left - right, h - top - bottom)
}

/** 占满可用宽度，把子节点按 `align` 放在里面。 */
class FillWidth(private val child: Node, private val align: HAlign) : Node() {
    override val flexible get() = true
    override fun measure(avail: Float) = child.measure(avail)

    override fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float) {
        val s = child.measure(w)
        val cw = if (child.flexible) w else s.w
        val cx = when (align) {
            HAlign.LEADING -> x
            HAlign.CENTER -> x + (w - cw) / 2
            HAlign.TRAILING -> x + w - cw
        }
        child.draw(c, cx, y + (h - s.h) / 2, cw, s.h)
    }
}

/** 先画背景，再画子节点（背景铺满子节点的区域）。 */
class Background(
    private val child: Node,
    private val painter: (c: DotCanvas, x: Float, y: Float, w: Float, h: Float) -> Unit,
) : Node() {
    override val flexible get() = child.flexible
    override fun measure(avail: Float) = child.measure(avail)
    override fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float) {
        painter(c, x, y, w, h)
        child.draw(c, x, y, w, h)
    }
}
