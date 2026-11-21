package app.dotclock.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.dotclock.DotClockApp
import app.dotclock.core.Appearance
import app.dotclock.core.ClockRenderer
import app.dotclock.core.ClockScene
import app.dotclock.data.ClockRepository
import app.dotclock.render.AndroidDotCanvas
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        DotClockApp.installPlatformHooks()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val repo = ClockRepository.get(this)
        setContent { DotClockScreen(repo, ::setImmersive) }
    }

    /** 全屏台钟模式：隐藏状态栏和导航栏；可选保持屏幕常亮。 */
    private fun setImmersive(fullscreen: Boolean, keepOn: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (fullscreen && keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun DotClockScreen(repo: ClockRepository, setImmersive: (Boolean, Boolean) -> Unit) {
    val settings by repo.settings.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val dark = when (settings.appearance) {
        Appearance.DARK -> true
        Appearance.LIGHT -> false
        Appearance.AUTO -> isSystemInDarkTheme()
    }
    val systemSeed = repo.systemSeed
    val scheme = remember(settings.seed, systemSeed, dark) { AppColors.scheme(AppColors.seedOf(settings, systemSeed), dark) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(fullscreen, settings.keepScreenOn) { setImmersive(fullscreen, settings.keepScreenOn) }
    BackHandler(enabled = fullscreen) { fullscreen = false }

    // 前台时每 15 分钟同步一次时间、位置和天气
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                repo.sync()
                delay(15 * 60_000L)
            }
        }
    }

    // 天气动画 12 帧 / 秒，否则每整秒刷新一次；App 不在前台时停止
    val animate = settings.animateWeather && settings.showWeather && state.weather != null
    val now by produceState(System.currentTimeMillis(), animate, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(if (animate) 83L else 1000L - value % 1000 + 5)
            }
        }
    }
    val scene = ClockScene.from(settings, state, dark, systemSeed, nowMillis = now, showSeconds = true, animate = true)

    MaterialTheme(colorScheme = scheme) {
        // 玻璃风格时透出桌面壁纸，只压一层淡淡的暗色
        val background = if (settings.glass) Color.Black.copy(alpha = if (dark) 0.35f else 0.12f) else scheme.background
        BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
            val wide = maxWidth >= 600.dp
            val toggle = { fullscreen = !fullscreen }
            when {
                fullscreen -> ClockPane(scene, Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), onTap = toggle)
                wide -> Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // 平板 / 横屏：左边大时钟，右边设置
                    ClockPane(scene, Modifier.weight(1f).fillMaxHeight().padding(24.dp), onTap = toggle, showHint = true)
                    Surface(
                        color = scheme.surfaceContainerLow.copy(alpha = if (settings.glass) 0.85f else 1f),
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.width(if (maxWidth >= 840.dp) 400.dp else 340.dp).fillMaxHeight().padding(vertical = 12.dp).padding(end = 12.dp),
                    ) {
                        SettingsPanel(repo, settings, state)
                    }
                }
                else -> Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    // 手机竖屏：上面时钟（自然高度，最多占屏幕一半），下面设置
                    val density = LocalDensity.current.density
                    val widthPx = (maxWidth.value - 32) * density
                    val natural = remember(scene.style, scene.digitStyle, scene.textFont, scene.cities, scene.weather != null, scene.showDayProgress, widthPx) {
                        ClockRenderer.naturalHeight(AndroidDotCanvas(), scene, widthPx, density) / density
                    }
                    val height = minOf(natural, maxHeight.value * 0.55f)
                    ClockPane(scene, Modifier.fillMaxWidth().height(height.dp + 32.dp).padding(16.dp), onTap = toggle)
                    Surface(
                        color = scheme.surfaceContainerLow.copy(alpha = if (settings.glass) 0.85f else 1f),
                        shape = MaterialTheme.shapes.extraLarge.copy(
                            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
                            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
                        ),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        SettingsPanel(repo, settings, state)
                    }
                }
            }
            AnimatedVisibility(fullscreen, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp)) {
                FilledTonalIconButton(onClick = toggle) { Icon(Icons.Default.Settings, contentDescription = "退出全屏") }
            }
        }
    }
}

/** 时钟卡片：在给定区域内等比缩放填满，点一下切换全屏台钟模式。 */
@Composable
private fun ClockPane(scene: ClockScene, modifier: Modifier, onTap: () -> Unit, showHint: Boolean = false) {
    val canvas = remember { AndroidDotCanvas() }
    val density = LocalDensity.current.density
    Box(modifier) {
        Canvas(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap,
            ),
        ) {
            drawIntoCanvas { c ->
                canvas.canvas = c.nativeCanvas
                ClockRenderer.draw(canvas, scene, 0f, 0f, size.width, size.height, density)
                canvas.canvas = null
            }
        }
        if (showHint) {
            androidx.compose.material3.Text(
                "点击时钟进入全屏",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
