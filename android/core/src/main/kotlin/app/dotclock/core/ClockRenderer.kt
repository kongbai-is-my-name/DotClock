package app.dotclock.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 画一帧卡片需要的全部数据。 */
data class ClockScene(
    val instant: Instant,
    val mainName: String,
    val mainZone: ZoneId,
    val cities: List<City>,
    val use24h: Boolean,
    val theme: Theme,
    val dark: Boolean,
    val style: ClockStyle,
    val digitStyle: DigitStyle,
    val textFont: TextFont,
    val showDayProgress: Boolean,
    val weather: Weather?,
    val fahrenheit: Boolean,
    val glass: Boolean,
    /** 是否显示秒（桌面小组件每分钟刷新一次，不显示秒） */
    val showSeconds: Boolean = true,
    /** 天气动画；null = 不画动画 */
    val animationTime: Double? = null,
) {
    companion object {
        /** 根据设置和网络状态组装一帧。`systemSeed` 为 Android 12+ 的壁纸取色。 */
        fun from(
            settings: ClockSettings,
            state: NetworkState,
            dark: Boolean,
            systemSeed: Int? = null,
            nowMillis: Long = System.currentTimeMillis(),
            showSeconds: Boolean = true,
            animate: Boolean = false,
        ): ClockScene {
            val calibrated = ClockSync.calibratedMillis(settings, state, nowMillis)
            val zone = ClockSync.mainZone(settings, state)
            val weather = ClockSync.visibleWeather(settings, state, nowMillis)
            return ClockScene(
                instant = Instant.ofEpochMilli(calibrated),
                mainName = ClockSync.mainName(settings, state),
                mainZone = zone,
                cities = settings.cities,
                use24h = settings.use24h,
                theme = resolveTheme(settings, dark, systemSeed),
                dark = dark,
                style = settings.style,
                digitStyle = settings.digitStyle,
                textFont = settings.textFont,
                showDayProgress = settings.showDayProgress,
                weather = weather,
                fahrenheit = settings.fahrenheit,
                glass = settings.glass,
                showSeconds = showSeconds,
                animationTime = if (animate && settings.animateWeather && weather != null) {
                    val t = Instant.ofEpochMilli(calibrated).atZone(zone)
                    t.toLocalTime().toNanoOfDay() / 1e9
                } else null,
            )
        }

        /** 当前主题，并套用用户选择的数字颜色和玻璃风格。 */
        fun resolveTheme(settings: ClockSettings, dark: Boolean, systemSeed: Int? = null): Theme {
            val seed = if (settings.seed == "system") systemSeed ?: ThemePair.FALLBACK_SEED
            else ColorMath.parseHex(settings.seed) ?: ThemePair.FALLBACK_SEED
            var theme = ThemePair.of(seed).pick(dark)
            theme = when (settings.digitColor) {
                "primary" -> theme
                "accent" -> theme.copy(digits = theme.accent)
                "mono" -> theme.copy(digits = if (dark) Theme.WHITE else Theme.BLACK)
                else -> ColorMath.parseHex(settings.digitColor)?.let { theme.copy(digits = it) } ?: theme
            }
            return if (settings.glass) theme.glassy(dark, ColorMath.parseHex(settings.glassTint), settings.glassOpacity) else theme
        }
    }
}

/** 卡片里可以省掉的区块，空间不够时按从后往前的顺序依次省掉。 */
data class Sections(
    val header: Boolean = true,
    val secondsStrip: Boolean = true,
    val dayProgress: Boolean = true,
    val weather: Boolean = true,
    val cityCount: Int = Int.MAX_VALUE,
)

/**
 * 时钟卡片的排版与绘制，对应 macOS 版的 ClockCard / TimeFace / WeatherRow / CityRow。
 * App 界面与桌面小组件都用它画，保证两边长得一样。
 */
object ClockRenderer {
    private const val BASE_WIDTH = ClockFormat.WIDGET_WIDTH

    class Plan(val zoom: Float, val widthFactor: Float, val extraHeight: Float, val sections: Sections)

    /**
     * 给定像素尺寸，算出缩放倍数和要显示的区块，让卡片正好填满这个区域。
     * `density` 是每 pt 的像素数，`minScale` 是可接受的最小缩放（相对 density），
     * 低于它就开始省掉区块（世界时钟 → 天气 → 今日进度 → 秒条 → 标题行）。
     */
    fun plan(c: DotCanvas, scene: ClockScene, width: Float, height: Float, density: Float, minScale: Float = 0f): Plan {
        val candidates = candidates(scene)
        var chosen: Plan? = null
        for (sections in candidates) {
            val natural = cardNode(c, scene, density, 1f, 0f, sections).measure(BASE_WIDTH * density)
            val scale = min(width / natural.w, height / natural.h)
            val plan = finish(c, scene, width, height, density * scale, sections)
            chosen = plan
            if (scale >= minScale) break
        }
        return chosen!!
    }

    private fun candidates(scene: ClockScene): List<Sections> {
        val full = Sections(
            secondsStrip = scene.showSeconds && scene.style != ClockStyle.DIAL,
            dayProgress = scene.showDayProgress,
            weather = scene.weather != null,
        )
        val list = mutableListOf(full)
        for (n in scene.cities.size - 1 downTo 0) list += full.copy(cityCount = n)
        list += full.copy(cityCount = 0, weather = false)
        list += full.copy(cityCount = 0, weather = false, dayProgress = false)
        list += full.copy(cityCount = 0, weather = false, dayProgress = false, secondsStrip = false)
        list += Sections(header = false, secondsStrip = false, dayProgress = false, weather = false, cityCount = 0)
        return list.distinct()
    }

    private fun finish(c: DotCanvas, scene: ClockScene, width: Float, height: Float, zoom: Float, sections: Sections): Plan {
        val widthFactor = (width / (BASE_WIDTH * zoom)).coerceAtLeast(1f)
        val measured = cardNode(c, scene, zoom, widthFactor, 0f, sections).measure(width)
        val extra = ((height - measured.h) / zoom).coerceAtLeast(0f)
        return Plan(zoom, widthFactor, extra, sections)
    }

    /** 宽度固定时卡片的自然高度（像素）。 */
    fun naturalHeight(c: DotCanvas, scene: ClockScene, width: Float, density: Float): Float {
        val zoom = min(density, width / BASE_WIDTH)
        val wf = (width / (BASE_WIDTH * zoom)).coerceAtLeast(1f)
        return cardNode(c, scene, zoom, wf, 0f, Sections()).measure(width).h
    }

    /** 在 (x, y, width, height) 区域内画出卡片。 */
    fun draw(c: DotCanvas, scene: ClockScene, x: Float, y: Float, width: Float, height: Float, density: Float, minScale: Float = 0f) {
        val plan = plan(c, scene, width, height, density, minScale)
        val node = cardNode(c, scene, plan.zoom, plan.widthFactor, plan.extraHeight, plan.sections)
        // 缩放后卡片可能比区域略小，居中摆放
        val size = node.measure(width)
        val w = min(width, BASE_WIDTH * plan.zoom * plan.widthFactor)
        node.draw(c, x + (width - w) / 2, y + (height - size.h).coerceAtLeast(0f) / 2, w, size.h)
    }

    fun cardNode(c: DotCanvas, scene: ClockScene, zoom: Float, widthFactor: Float, extraHeight: Float, sections: Sections): Node {
        val z = zoom
        val ctx = Ctx(c, z, scene.digitStyle, scene.textFont)
        val theme = scene.theme
        val main = ClockFormat.parts(scene.instant, scene.mainZone, scene.use24h)
        val cities = scene.cities.take(sections.cityCount)
        val weather = scene.weather?.takeIf { sections.weather }
        val hasRows = weather != null || cities.isNotEmpty()
        val column = mutableListOf<Node>()

        if (sections.header) {
            column += Padding(
                HStack(
                    listOf(
                        Leaf(6 * z, 6 * z) { cv, x, y, w, h -> cv.fillOval(x, y, w, h, theme.accent) },
                        label(ctx, scene.mainName, 13f, theme.label),
                        spacer(),
                        label(ctx, ClockFormat.dateLine(scene.instant, scene.mainZone), 13f, theme.label),
                    ),
                    spacing = 6 * z,
                ),
                bottom = 16 * z,
            )
        }

        // 多出来的宽度让时间居中，多出来的高度平均留在时间上下
        val face = timeFace(ctx, scene, main)
        column += Padding(
            FillWidth(face, if (widthFactor > 1.02f) HAlign.CENTER else HAlign.LEADING),
            top = extraHeight * z / 2,
            bottom = extraHeight * z / 2 + if (sections.secondsStrip || sections.dayProgress || hasRows) 16 * z else 0f,
        )

        if (sections.secondsStrip) column += secondsStrip(ctx, main.second, theme)

        if (sections.dayProgress) {
            val fraction = ClockFormat.dayFraction(scene.instant, scene.mainZone)
            column += Padding(
                HStack(
                    listOf(
                        label(ctx, "今日", 11.5f, theme.sub),
                        dayProgressBar(ctx, fraction, theme),
                        label(ctx, "%.1f%%".format(java.util.Locale.US, kotlin.math.floor(fraction * 1000) / 10), 12.5f, theme.label, 1.8f),
                    ),
                    spacing = 10 * z,
                ),
                top = if (sections.secondsStrip) 14 * z else 0f,
            )
        }

        if (hasRows) {
            val rows = mutableListOf<Node>()
            if (weather != null) {
                rows += divider(z, theme)
                rows += weatherRow(ctx, scene, weather)
            }
            for (city in cities) {
                rows += divider(z, theme)
                rows += cityRow(ctx, scene, city)
            }
            column += Padding(VStack(rows), top = if (sections.secondsStrip || sections.dayProgress) 14 * z else 0f)
        }

        val content = Padding(
            VStack(column),
            left = 20 * z, right = 20 * z, top = 18 * z, bottom = (if (hasRows) 6 else 18) * z,
        )
        val width = BASE_WIDTH * z * widthFactor
        val radius = 26 * z
        val card = object : Node() {
            override val flexible get() = true
            override fun measure(avail: Float) = Size(width, content.measure(width).h)
            override fun draw(c: DotCanvas, x: Float, y: Float, w: Float, h: Float) {
                c.save()
                c.clipRoundRect(x, y, w, h, radius)
                if (scene.glass) glassBackground(c, scene, x, y, w, h, radius) else c.fillRoundRect(x, y, w, h, radius, theme.surface)
                content.draw(c, x, y, w, h)
                c.restore()
                if (scene.glass) glassRim(c, scene.dark, x, y, w, h, radius, z)
                else c.strokeRoundRect(x + 0.5f, y + 0.5f, w - 1, h - 1, radius, 1f, theme.border)
            }
        }
        return card
    }

    private fun divider(z: Float, theme: Theme) =
        Leaf(0f, z, flexible = true) { c, x, y, w, h -> c.fillRect(x, y, w, h, theme.border) }

    /** 主时间区域的几种样式。 */
    private fun timeFace(ctx: Ctx, scene: ClockScene, time: TimeParts): Node {
        val z = ctx.z
        val theme = scene.theme
        // 真实字体的字宽比点阵宽，适当缩小避免溢出
        val isFont = scene.digitStyle.isFont
        val classic = if (isFont) 0.9f else 1f
        val inline = if (isFont) 0.78f else 1f
        val seconds = scene.showSeconds
        fun period(size: Float, cell: Float) = time.period?.let { label(ctx, it, size, theme.sub, cell) }
        return when (scene.style) {
            ClockStyle.CLASSIC -> HStack(
                listOf(
                    digits(ctx, time.hm, 8 * classic, theme.digits),
                    VStack(
                        listOfNotNull(
                            period(14f, 2f),
                            if (seconds) digits(ctx, time.ss, 4.5f * classic, theme.accent) else null,
                        ),
                        spacing = 7 * z,
                    ),
                ),
                spacing = 12 * z, align = VAlign.BOTTOM,
            )
            ClockStyle.INLINE -> VStack(
                listOfNotNull(
                    period(14f, 2f),
                    if (seconds) HStack(
                        listOf(
                            digits(ctx, time.hm + ":", 7 * inline, theme.digits),
                            digits(ctx, time.ss, 7 * inline, theme.accent),
                        ),
                        spacing = if (isFont) 0f else 7 * z,
                    ) else digits(ctx, time.hm, 9 * inline, theme.digits),
                ),
                spacing = 8 * z,
            )
            ClockStyle.STACKED -> HStack(
                listOf(
                    VStack(
                        listOf(
                            digits(ctx, time.hm.take(2), 11f, theme.digits),
                            digits(ctx, time.hm.takeLast(2), 11f, ColorMath.withAlpha(theme.digits, 0.7)),
                        ),
                        spacing = 12 * z,
                    ),
                    spacer(16 * z),
                    VStack(
                        listOfNotNull(
                            period(17f, 2.5f),
                            if (seconds) digits(ctx, time.ss, 7f, theme.accent) else null,
                        ),
                        spacing = 9 * z, align = HAlign.TRAILING,
                    ),
                ),
                align = VAlign.BOTTOM,
            )
            ClockStyle.DIAL -> HStack(
                listOf(
                    dialFace(z, time, theme, seconds),
                    spacer(16 * z),
                    VStack(
                        listOfNotNull(
                            period(14f, 2f),
                            digits(ctx, time.hm, 4f, theme.digits),
                            if (seconds) digits(ctx, time.ss, 4f, theme.accent) else null,
                        ),
                        spacing = 9 * z, align = HAlign.TRAILING,
                    ),
                ),
            )
        }
    }

    /** 点阵表盘：外圈 60 个秒点（不显示秒时为分钟点），时针分针由一串圆点组成。 */
    private fun dialFace(z: Float, time: TimeParts, theme: Theme, seconds: Boolean): Node = Leaf(156 * z, 156 * z) { c, x, y, w, h ->
        val cx = x + w / 2
        val cy = y + h / 2
        val radius = min(w, h) / 2 - 3 * z
        fun point(fraction: Double, r: Float): Pair<Float, Float> {
            val angle = fraction * 2 * PI - PI / 2
            return (cx + cos(angle).toFloat() * r) to (cy + sin(angle).toFloat() * r)
        }
        fun dot(p: Pair<Float, Float>, d: Float, color: Int) = c.fillOval(p.first - d / 2, p.second - d / 2, d, d, color)
        val current = if (seconds) time.second else time.minute
        for (i in 0 until 60) {
            val color = if (i == current) theme.accent else if (i < current) theme.lit else theme.dim
            dot(point(i / 60.0, radius), (if (i % 5 == 0) 5 else 3) * z, color)
        }
        fun hand(fraction: Double, length: Float, d: Float, color: Int) {
            var r = d * 1.6f
            while (r <= length) {
                dot(point(fraction, r), d, color)
                r += d * 1.35f
            }
        }
        val minuteFraction = (time.minute + (if (seconds) time.second / 60.0 else 0.0)) / 60
        val hourFraction = (time.hour % 12 + time.minute / 60.0) / 12
        hand(minuteFraction, radius * 0.8f, 5 * z, theme.lit)
        hand(hourFraction, radius * 0.52f, 7 * z, theme.digits)
        dot(cx to cy, 7 * z, theme.accent)
    }

    /** 天气行：点阵图标 + 当前温度 + 天气和最高 / 最低温，背后铺点阵动画。 */
    private fun weatherRow(ctx: Ctx, scene: ClockScene, weather: Weather): Node {
        val z = ctx.z
        val theme = scene.theme
        val time = scene.animationTime
        val icon = if (time != null && weather.kind == WeatherKind.CLEAR && ((time / 0.8).toLong() % 2 == 1L))
            WeatherKind.SUN_ALTERNATE else weather.kind.icon
        val row = Padding(
            HStack(
                listOf(
                    weatherIcon(ctx, icon, 3f, theme),
                    digits(ctx, Weather.text(weather.temperature, scene.fahrenheit), 3.6f, theme.digits),
                    spacer(8 * z),
                    VStack(
                        listOf(
                            label(ctx, weather.kind.title, 15f, theme.label),
                            label(ctx, Weather.text(weather.high, scene.fahrenheit) + " / " + Weather.text(weather.low, scene.fahrenheit), 11.5f, theme.sub),
                        ),
                        spacing = 5 * z, align = HAlign.TRAILING,
                    ),
                ),
                spacing = 14 * z,
            ),
            top = 11 * z, bottom = 11 * z,
        )
        if (time == null) return row
        return Background(row) { c, x, y, w, h ->
            c.save()
            c.clipRect(x, y, w, h)
            WeatherPainter.paint(c, weather.kind, weather.intensity, theme, x, y, w, h, 3 * z,
                scene.digitStyle == DigitStyle.SQUARE, time)
            c.restore()
        }
    }

    private fun cityRow(ctx: Ctx, scene: ClockScene, city: City): Node {
        val z = ctx.z
        val theme = scene.theme
        val zone = ClockFormat.zoneOf(city.tzID)
        val time = ClockFormat.parts(scene.instant, zone, scene.use24h)
        val clock = mutableListOf<Node>()
        if (scene.showSeconds) {
            clock += digits(ctx, time.hm + ":", 2.6f, theme.digits)
            clock += digits(ctx, time.ss, 2.6f, theme.accent)
        } else {
            clock += digits(ctx, time.hm, 2.6f, theme.digits)
        }
        time.period?.let { clock += Padding(label(ctx, it, 11f, theme.sub, 1.6f), left = 3 * z) }
        return Padding(
            HStack(
                listOf(
                    VStack(
                        listOf(
                            label(ctx, city.name.take(12), 15f, theme.label),
                            label(ctx, ClockFormat.offsetLine(scene.instant, zone, scene.mainZone), 11.5f, theme.sub),
                        ),
                        spacing = 5 * z,
                    ),
                    spacer(8 * z),
                    HStack(clock, spacing = 2.6f * z, align = VAlign.BOTTOM),
                ),
            ),
            top = 11 * z, bottom = 11 * z,
        )
    }

    /** 玻璃底：半透明主题色 + 顶部柔光 + 底边回光，模拟厚玻璃的折射感。 */
    private fun glassBackground(c: DotCanvas, scene: ClockScene, x: Float, y: Float, w: Float, h: Float, r: Float) {
        val dark = scene.dark
        val white = Theme.WHITE
        c.fillRoundRect(x, y, w, h, r, ColorMath.withAlpha(if (dark) Theme.BLACK else white, 0.12))
        c.fillRoundRect(x, y, w, h, r, scene.theme.surface)
        c.fillRoundRectGradient(
            x, y, w, h, r, x + w / 2, y, x + w / 2, y + h * 0.45f,
            intArrayOf(ColorMath.withAlpha(white, if (dark) 0.10 else 0.30), ColorMath.withAlpha(white, 0.0)), floatArrayOf(0f, 1f),
        )
        c.fillRoundRectGradient(
            x, y, w, h, r, x + w / 2, y + h * 0.7f, x + w / 2, y + h,
            intArrayOf(ColorMath.withAlpha(white, 0.0), ColorMath.withAlpha(white, if (dark) 0.05 else 0.12)), floatArrayOf(0f, 1f),
        )
    }

    /** 玻璃边缘的镜面高光：左上和右下亮，两侧暗，外加一圈很细的内阴影线。 */
    private fun glassRim(c: DotCanvas, dark: Boolean, x: Float, y: Float, w: Float, h: Float, r: Float, z: Float) {
        val white = Theme.WHITE
        val line = 1.2f * maxOf(1f, z * 0.8f)
        c.strokeRoundRectGradient(
            x + line / 2, y + line / 2, w - line, h - line, r, line, x, y, x + w, y + h,
            intArrayOf(
                ColorMath.withAlpha(white, if (dark) 0.55 else 0.95),
                ColorMath.withAlpha(white, if (dark) 0.08 else 0.25),
                ColorMath.withAlpha(white, if (dark) 0.04 else 0.15),
                ColorMath.withAlpha(white, if (dark) 0.30 else 0.60),
            ),
            floatArrayOf(0f, 0.35f, 0.65f, 1f),
        )
        val inset = 1.5f * maxOf(1f, z * 0.8f)
        c.strokeRoundRect(x + inset, y + inset, w - 2 * inset, h - 2 * inset, r - inset, 0.5f * maxOf(1f, z / 2),
            ColorMath.withAlpha(Theme.BLACK, if (dark) 0.25 else 0.06))
    }
}
