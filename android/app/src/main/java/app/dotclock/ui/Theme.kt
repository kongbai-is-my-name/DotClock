package app.dotclock.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.dotclock.core.Cam16
import app.dotclock.core.ClockSettings
import app.dotclock.core.ColorMath
import app.dotclock.core.Hct
import app.dotclock.core.ThemePair

fun Int.toColor() = Color(this)

/** 设置界面的 Material 3 配色，和时钟卡片用同一个主题种子（TonalSpot）。 */
object AppColors {
    private val cache = HashMap<Pair<Int, Boolean>, ColorScheme>()

    fun seedOf(settings: ClockSettings, systemSeed: Int?): Int =
        if (settings.seed == "system") systemSeed ?: ThemePair.FALLBACK_SEED
        else ColorMath.parseHex(settings.seed) ?: ThemePair.FALLBACK_SEED

    fun scheme(seed: Int, dark: Boolean): ColorScheme = cache.getOrPut(seed to dark) {
        val hue = Cam16(seed).hue
        fun a1(t: Double) = Hct.rgb(hue, 36.0, t).toColor()
        fun a2(t: Double) = Hct.rgb(hue, 16.0, t).toColor()
        fun a3(t: Double) = Hct.rgb(hue + 60, 24.0, t).toColor()
        fun n1(t: Double) = Hct.rgb(hue, 6.0, t).toColor()
        fun n2(t: Double) = Hct.rgb(hue, 8.0, t).toColor()
        if (dark) darkColorScheme(
            primary = a1(80.0), onPrimary = a1(20.0), primaryContainer = a1(30.0), onPrimaryContainer = a1(90.0),
            secondary = a2(80.0), onSecondary = a2(20.0), secondaryContainer = a2(30.0), onSecondaryContainer = a2(90.0),
            tertiary = a3(80.0), onTertiary = a3(20.0), tertiaryContainer = a3(30.0), onTertiaryContainer = a3(90.0),
            background = n1(3.0), onBackground = n1(90.0), surface = n1(6.0), onSurface = n1(90.0),
            surfaceVariant = n2(30.0), onSurfaceVariant = n2(80.0), outline = n2(60.0), outlineVariant = n2(30.0),
            surfaceContainerLowest = n1(4.0), surfaceContainerLow = n1(10.0), surfaceContainer = n1(12.0),
            surfaceContainerHigh = n1(17.0), surfaceContainerHighest = n1(22.0),
        ) else lightColorScheme(
            primary = a1(40.0), onPrimary = a1(100.0), primaryContainer = a1(90.0), onPrimaryContainer = a1(10.0),
            secondary = a2(40.0), onSecondary = a2(100.0), secondaryContainer = a2(90.0), onSecondaryContainer = a2(10.0),
            tertiary = a3(40.0), onTertiary = a3(100.0), tertiaryContainer = a3(90.0), onTertiaryContainer = a3(10.0),
            background = n1(91.0), onBackground = n1(10.0), surface = n1(98.0), onSurface = n1(10.0),
            surfaceVariant = n2(90.0), onSurfaceVariant = n2(30.0), outline = n2(50.0), outlineVariant = n2(80.0),
            surfaceContainerLowest = n1(100.0), surfaceContainerLow = n1(96.0), surfaceContainer = n1(94.0),
            surfaceContainerHigh = n1(92.0), surfaceContainerHighest = n1(90.0),
        )
    }
}
