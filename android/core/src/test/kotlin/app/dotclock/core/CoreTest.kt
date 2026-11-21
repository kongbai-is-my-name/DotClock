package app.dotclock.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.ZoneId
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoreTest {
    init {
        DotFont.rasterizer = Java2DCanvas.rasterizer
    }

    @Test
    fun cam16RoundTrip() {
        for (color in listOf(0xFF1B6EF3.toInt(), 0xFFD71921.toInt(), 0xFF2E7D32.toInt(), 0xFFF59E0B.toInt())) {
            val cam = Cam16(color)
            val back = Cam16.rgb(cam.j, cam.chroma, cam.hue)
            for (shift in listOf(16, 8, 0)) {
                val a = (color shr shift) and 0xFF
                val b = (back shr shift) and 0xFF
                assertTrue(kotlin.math.abs(a - b) <= 1, "通道偏差过大: ${ColorMath.toHex(color)} → ${ColorMath.toHex(back)}")
            }
        }
    }

    @Test
    fun hctHitsRequestedTone() {
        for (tone in listOf(10.0, 40.0, 80.0, 96.0)) {
            val c = Hct.rgb(260.0, 36.0, tone)
            assertTrue(kotlin.math.abs(ColorMath.lstar(c) - tone) < 1.0, "tone $tone → ${ColorMath.lstar(c)}")
        }
    }

    @Test
    fun themeIsReadable() {
        val pair = ThemePair(ThemePair.FALLBACK_SEED)
        assertTrue(ColorMath.lstar(pair.dark.digits) - ColorMath.lstar(pair.dark.surface) > 60)
        assertTrue(ColorMath.lstar(pair.light.surface) - ColorMath.lstar(pair.light.digits) > 60)
    }

    @Test
    fun clockFormat() {
        val instant = Instant.parse("2026-11-21T05:07:09Z")
        val shanghai = ZoneId.of("Asia/Shanghai")
        val p = ClockFormat.parts(instant, shanghai, use24h = false)
        assertEquals("01:07", p.hm)
        assertEquals("PM", p.period)
        assertEquals("13:07", ClockFormat.parts(instant, shanghai, true).hm)
        assertEquals("11月21日 周六", ClockFormat.dateLine(instant, shanghai))
        assertEquals("今天 -8H", ClockFormat.offsetLine(instant, ZoneId.of("UTC"), shanghai))
        assertEquals("明天 +5.5H", ClockFormat.offsetLine(Instant.parse("2026-11-21T20:00:00Z"), ZoneId.of("Asia/Kolkata"), ZoneId.of("UTC")))
        assertEquals(0.5, ClockFormat.dayFraction(Instant.parse("2026-11-21T04:00:00Z"), shanghai), 1e-9)
    }

    @Test
    fun weatherParsing() {
        val json = Json.parseToJsonElement(
            """{"current":{"temperature_2m":12.4,"weather_code":61,"is_day":1},"daily":{"temperature_2m_max":[15.0],"temperature_2m_min":[8.2]}}""",
        ) as JsonObject
        val w = assertNotNull(WeatherService.parseForecast(json))
        assertEquals(WeatherKind.RAIN, w.kind)
        assertEquals(0.6, w.intensity)
        assertEquals("12°", Weather.text(w.temperature, false))
        assertEquals("54°", Weather.text(w.temperature, true))
    }

    @Test
    fun cityNameCleanup() {
        assertEquals("香港", NetworkSync.cleanChineseCityName("香港特别行政区"))
        assertEquals("杭州", NetworkSync.cleanChineseCityName("杭州市"))
        assertEquals("广西", NetworkSync.cleanChineseCityName("广西壮族自治区"))
    }

    @Test
    fun chineseGlyphsRasterize() {
        val g = DotFont.glyph("东")
        assertEquals(DotFont.RASTER_SIZE, g.rows.size)
        assertTrue(g.rows.sumOf { r -> r.count { it } } > 10)
    }

    @Test
    fun layoutFitsEverySize() {
        val image = BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)
        val canvas = Java2DCanvas(image.createGraphics())
        val scene = scene(ClockStyle.CLASSIC, DigitStyle.ROUND, cities = 2)
        // 小组件常见尺寸（px，密度 2.75）：全部区块都能塞下，或按顺序省掉
        for ((w, h) in listOf(300f to 120f, 600f to 300f, 900f to 900f, 1800f to 600f)) {
            val plan = ClockRenderer.plan(canvas, scene, w, h, 2.75f, 0.6f)
            val node = ClockRenderer.cardNode(canvas, scene, plan.zoom, plan.widthFactor, plan.extraHeight, plan.sections)
            val size = node.measure(w)
            assertTrue(size.w <= w + 1 && size.h <= h + 1, "$w×$h 放不下: $size")
        }
    }

    private fun scene(
        style: ClockStyle, digits: DigitStyle, cities: Int = 2, dark: Boolean = true, glass: Boolean = false,
        seconds: Boolean = true, font: TextFont = TextFont.DOT, use24h: Boolean = true, seed: String = ThemePair.DEFAULT_SEED_HEX,
    ): ClockScene {
        val settings = ClockSettings(
            style = style, digitStyle = digits, textFont = font, glass = glass, use24h = use24h, seed = seed,
            cities = listOf(City("东京", "Asia/Tokyo"), City("伦敦", "Europe/London"), City("纽约", "America/New_York")).take(cities),
            mainCity = City("杭州", "Asia/Shanghai"), autoLocation = false,
        )
        val state = NetworkState(
            weather = Weather(12.4, 15.0, 8.2, 61, true), weatherKey = "杭州|Asia/Shanghai",
            weatherUpdatedAt = System.currentTimeMillis(),
        )
        return ClockScene.from(settings, state, dark, nowMillis = Instant.parse("2026-11-21T05:07:42Z").toEpochMilli(),
            showSeconds = seconds, animate = true).copy(animationTime = 47000.3)
    }

    /** `./gradlew :core:test -Ppreview` 时把各种样式画成 PNG，方便在电脑上检查排版。 */
    @Test
    fun renderPreviews() {
        val dir = System.getProperty("dotclock.preview").orEmpty()
        if (dir.isEmpty()) return
        File(dir).mkdirs()
        fun render(name: String, scene: ClockScene, w: Int, h: Int, density: Float, minScale: Float = 0f, wallpaper: Boolean = false, transparent: Boolean = false) {
            val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            if (transparent) {
                g.color = java.awt.Color(0, 0, 0, 0)
            } else if (wallpaper) {
                g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0x3a6ea5), w.toFloat(), h.toFloat(), java.awt.Color(0xc06c84))
            } else {
                g.color = java.awt.Color(if (scene.dark) 0x111111 else 0xEEEEEE)
            }
            g.fillRect(0, 0, w, h)
            val pad = 12f
            ClockRenderer.draw(Java2DCanvas(g), scene, pad, pad, w - 2 * pad, h - 2 * pad, density, minScale)
            g.dispose()
            ImageIO.write(image, "png", File(dir, "$name.png"))
        }
        for (style in ClockStyle.entries) render("phone-${style.name.lowercase()}", scene(style, DigitStyle.ROUND), 1080, 1100, 2.75f)
        render("phone-segment-light", scene(ClockStyle.CLASSIC, DigitStyle.SEGMENT, dark = false, use24h = false), 1080, 1100, 2.75f)
        render("phone-font-serif", scene(ClockStyle.INLINE, DigitStyle.SERIF, font = TextFont.SANS, seed = "d71921"), 1080, 1100, 2.75f)
        render("phone-square", scene(ClockStyle.STACKED, DigitStyle.SQUARE, seed = "2e7d32"), 1080, 1300, 2.75f)
        render("tablet-landscape", scene(ClockStyle.CLASSIC, DigitStyle.ROUND, cities = 3), 2560, 1600, 2f)
        render("glass", scene(ClockStyle.DIAL, DigitStyle.RING, glass = true), 1080, 1200, 2.75f, wallpaper = true)
        // 小组件：4×2、4×1、2×2、5×4
        render("widget-4x2", scene(ClockStyle.CLASSIC, DigitStyle.ROUND, seconds = false), 880, 460, 2.75f, 0.6f, true)
        render("widget-4x1", scene(ClockStyle.INLINE, DigitStyle.ROUND, seconds = false), 880, 230, 2.75f, 0.6f, true)
        render("widget-2x2", scene(ClockStyle.DIAL, DigitStyle.ROUND, seconds = false), 440, 460, 2.75f, 0.6f, true)
        // 小组件选择器里的预览图（透明背景）
        render("widget_preview", scene(ClockStyle.CLASSIC, DigitStyle.ROUND, cities = 0, seconds = false).copy(animationTime = null), 640, 340, 2f, 0.6f, transparent = true)
        render("widget-5x4", scene(ClockStyle.CLASSIC, DigitStyle.ROUND, cities = 3, seconds = false, glass = true), 1100, 1000, 2.75f, 0.6f, true)
    }
}
