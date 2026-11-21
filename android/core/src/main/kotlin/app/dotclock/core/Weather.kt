package app.dotclock.core

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

enum class WeatherKind(val title: String) {
    CLEAR("晴"), CLEAR_NIGHT("晴"), PARTLY("多云"), CLOUDY("阴"), FOG("雾"), RAIN("雨"), SNOW("雪"), THUNDER("雷雨");

    /** 11×11 点阵图标：`#` 用主色，`o` 用强调色。 */
    val icon: List<String>
        get() = when (this) {
            CLEAR -> listOf(
                ".....#.....", ".#...#...#.", "..#.....#..", "....ooo....", "...ooooo...",
                "##.ooooo.##", "...ooooo...", "....ooo....", "..#.....#..", ".#...#...#.", ".....#.....",
            )
            CLEAR_NIGHT -> listOf(
                BLANK, "....####...", "...##......", "..##.......", "..##.....o.", "..##.......",
                "..###......", "...###...#.", "....######.", BLANK, BLANK,
            )
            PARTLY -> listOf(
                "......ooo..", ".....o...o.", "....###...o", "...#...#..o", "..#.....##.", ".##.......#",
                "#.........#", "#.........#", ".#########.", BLANK, BLANK,
            )
            CLOUDY -> listOf(BLANK, BLANK) + CLOUD + listOf(BLANK, BLANK)
            FOG -> listOf(
                BLANK, ".#########.", BLANK, "###.#####..", BLANK, "..#####.###", BLANK, ".#########.",
                BLANK, "..###.###..", BLANK,
            )
            RAIN -> listOf(BLANK) + CLOUD + listOf(BLANK, "..o..o..o..", ".o..o..o...")
            SNOW -> listOf(BLANK) + CLOUD + listOf(BLANK, ".o...o...o.", "...o...o...")
            THUNDER -> listOf(BLANK) + CLOUD + listOf("....oo.....", "...oo......", "..o........")
        }

    companion object {
        private const val BLANK = "..........."
        private val CLOUD = listOf(
            "....###....", "...#...#...", "..#.....##.", ".##.......#", "#.........#", "#.........#", ".#########.",
        )

        /** 晴天的第二帧：光芒转过半格，和第一帧交替形成转动感 */
        val SUN_ALTERNATE = listOf(
            "...#...#...", "...........", ".#.......#.", "....ooo....", "...ooooo...", "...ooooo...",
            "...ooooo...", "....ooo....", ".#.......#.", "...........", "...#...#...",
        )

        /** WMO 天气代码 → 类型。 */
        fun of(code: Int, isDay: Boolean): WeatherKind = when (code) {
            0 -> if (isDay) CLEAR else CLEAR_NIGHT
            1, 2 -> if (isDay) PARTLY else CLEAR_NIGHT
            3 -> CLOUDY
            45, 48 -> FOG
            in 71..77, 85, 86 -> SNOW
            in 95..99 -> THUNDER
            in 51..67, in 80..82 -> RAIN
            else -> CLOUDY
        }
    }
}

@Serializable
data class Weather(
    val temperature: Double,
    val high: Double,
    val low: Double,
    val code: Int,
    val isDay: Boolean,
) {
    val kind: WeatherKind get() = WeatherKind.of(code, isDay)

    /** 雨雪强度（影响动画里粒子的多少）：小 0.6 / 中 1 / 大 1.6 */
    val intensity: Double
        get() = when (code) {
            51, 53, 56, 61, 71, 77, 80, 85 -> 0.6
            65, 67, 75, 82, 86 -> 1.6
            else -> 1.0
        }

    companion object {
        fun text(celsius: Double, fahrenheit: Boolean): String {
            val value = if (fahrenheit) celsius * 9 / 5 + 32 else celsius
            return "${value.roundToInt()}°"
        }
    }
}
