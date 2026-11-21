package app.dotclock.core

import kotlinx.serialization.Serializable

@Serializable
data class City(
    val name: String,
    val tzID: String,
    /** 联网搜索到的城市会带上坐标，供天气使用 */
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    val id: String get() = "$name|$tzID"

    // 与 macOS 版一致：同名同时区即视为同一城市
    override fun equals(other: Any?) = other is City && other.id == id
    override fun hashCode() = id.hashCode()
}

enum class ClockStyle(val title: String) {
    CLASSIC("经典（大时分 + 小秒）"),
    INLINE("单行（时:分:秒）"),
    STACKED("堆叠（时 / 分）"),
    DIAL("表盘（点阵指针）"),
}

/** 点的形状。 */
enum class DotShape { ROUND, SQUARE, RING }

/** 绘制文字用的字体（由各平台映射到具体字体）。 */
enum class Face { SANS_BOLD, SANS_SEMIBOLD, ROUNDED_BOLD, MONO_MEDIUM, CONDENSED_BOLD, SERIF_BOLD, SERIF }

/** 数字样式：点阵变体、七段数码管，以及几种系统字体。 */
enum class DigitStyle(val title: String, val face: Face?) {
    ROUND("圆点点阵", null),
    SQUARE("方块像素", null),
    RING("空心圆点", null),
    SEGMENT("七段数码管", null),
    ROUNDED("粗黑体", Face.ROUNDED_BOLD),
    MONO("等宽", Face.MONO_MEDIUM),
    CONDENSED("窄体", Face.CONDENSED_BOLD),
    SERIF("衬线", Face.SERIF_BOLD);

    val isFont: Boolean get() = face != null

    val dotShape: DotShape
        get() = when (this) {
            SQUARE -> DotShape.SQUARE
            RING -> DotShape.RING
            else -> DotShape.ROUND
        }
}

/** 城市名、日期等文字可选的字体。 */
enum class TextFont(val title: String, val face: Face?) {
    DOT("点阵", null),
    SANS("黑体", Face.SANS_SEMIBOLD),
    MONO("等宽", Face.MONO_MEDIUM),
    SERIF("宋体", Face.SERIF),
}

enum class Appearance(val title: String) { AUTO("跟随系统"), DARK("深色"), LIGHT("浅色") }

/** 用户设置；安卓 App 与桌面小组件共用同一份。 */
@Serializable
data class ClockSettings(
    val style: ClockStyle = ClockStyle.CLASSIC,
    val digitStyle: DigitStyle = DigitStyle.ROUND,
    val textFont: TextFont = TextFont.DOT,
    val use24h: Boolean = true,
    /** 主题种子：六位十六进制颜色，或 "system"（Android 12+ 跟随壁纸取色） */
    val seed: String = ThemePair.DEFAULT_SEED_HEX,
    val appearance: Appearance = Appearance.AUTO,
    /** "primary" / "accent" / "mono" 或十六进制颜色 */
    val digitColor: String = "primary",
    val showDayProgress: Boolean = true,
    val showWeather: Boolean = true,
    val fahrenheit: Boolean = false,
    val animateWeather: Boolean = true,
    /** 透明玻璃风格（半透明底色 + 高光描边，透出壁纸） */
    val glass: Boolean = false,
    /** 玻璃底色："theme"（跟随主题）或十六进制颜色 */
    val glassTint: String = "theme",
    /** 玻璃底色浓度 0…1 */
    val glassOpacity: Double = 0.3,
    val autoLocation: Boolean = true,
    val useNetworkTime: Boolean = true,
    /** 手动指定的主城市（autoLocation == false 时生效；null = 跟随系统时区） */
    val mainCity: City? = null,
    /** 世界时钟 */
    val cities: List<City> = emptyList(),
    /** App 全屏显示时保持屏幕常亮（平板当台钟用） */
    val keepScreenOn: Boolean = false,
)

/** 联网得到的状态：校时偏移、定位城市、天气。也持久化，供小组件读取。 */
@Serializable
data class NetworkState(
    val timeOffsetMillis: Long = 0,
    val timeSynced: Boolean = false,
    val networkCity: City? = null,
    val weather: Weather? = null,
    /** 天气属于哪个城市（name|tz），城市变了就不再显示旧天气 */
    val weatherKey: String = "",
    val weatherUpdatedAt: Long = 0,
    /** 城市名 → 坐标缓存 */
    val coordinates: Map<String, List<Double>> = emptyMap(),
)
