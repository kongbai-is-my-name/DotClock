@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.dotclock.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.dotclock.core.Appearance
import app.dotclock.core.ClockSettings
import app.dotclock.core.ClockStyle
import app.dotclock.core.ColorMath
import app.dotclock.core.DigitStyle
import app.dotclock.core.Hct
import app.dotclock.core.NetworkState
import app.dotclock.core.SeedPreset
import app.dotclock.core.TextFont
import app.dotclock.data.ClockRepository
import app.dotclock.widget.ClockWidgetProvider
import kotlinx.coroutines.launch

/** 设置面板，对应 macOS 版的右键菜单。 */
@Composable
fun SettingsPanel(repo: ClockRepository, settings: ClockSettings, state: NetworkState) {
    val scope = rememberCoroutineScope()
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<Boolean?>(null) } // true = 选主城市，false = 添加世界时钟
    var colorTarget by remember { mutableStateOf<String?>(null) } // "seed" / "digits" / "glass"
    val update: ((ClockSettings) -> ClockSettings) -> Unit = repo::updateSettings
    fun syncWeather() = scope.launch { repo.sync(weatherOnly = true) }
    fun syncAll() = scope.launch { repo.sync() }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        header("表盘")
        chips(ClockStyle.entries, settings.style, { it.title.substringBefore('（') }) { v -> update { it.copy(style = v) } }
        header("数字样式")
        chips(DigitStyle.entries, settings.digitStyle, { it.title }) { v -> update { it.copy(digitStyle = v) } }
        header("文字字体")
        chips(TextFont.entries, settings.textFont, { it.title }) { v -> update { it.copy(textFont = v) } }

        header("主题色")
        item {
            Swatches(
                presets = SeedPreset.all,
                selected = settings.seed,
                extra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf("system" to "壁纸取色") else emptyList(),
                systemColor = repo.systemSeed,
                onPick = { hex -> update { it.copy(seed = hex) } },
                onCustom = { colorTarget = "seed" },
            )
        }
        item {
            Segmented(Appearance.entries, settings.appearance, { it.title }) { v -> update { it.copy(appearance = v) } }
        }
        header("数字颜色")
        item {
            val options = listOf("primary" to "主色", "accent" to "强调色", "mono" to "黑白")
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (id, title) ->
                    FilterChip(settings.digitColor == id, { update { it.copy(digitColor = id) } }, label = { Text(title) })
                }
                val custom = ColorMath.parseHex(settings.digitColor)
                FilterChip(
                    custom != null, { colorTarget = "digits" }, label = { Text("自定义") },
                    leadingIcon = if (custom != null) {
                        { Box(Modifier.size(14.dp).background(Color(custom), CircleShape)) }
                    } else null,
                )
            }
        }

        header("透明玻璃")
        toggle("透明玻璃风格", "卡片半透明，透出桌面壁纸（小组件同样生效）", settings.glass) { v -> update { it.copy(glass = v) } }
        if (settings.glass) {
            item {
                LabeledSlider("底色浓度", settings.glassOpacity.toFloat()) { v -> update { it.copy(glassOpacity = v.toDouble()) } }
            }
            item {
                Swatches(
                    presets = SeedPreset.all,
                    selected = settings.glassTint,
                    extra = listOf("theme" to "跟随主题"),
                    systemColor = null,
                    onPick = { hex -> update { it.copy(glassTint = hex) } },
                    onCustom = { colorTarget = "glass" },
                )
            }
        }

        header("显示")
        toggle("24 小时制", null, settings.use24h) { v -> update { it.copy(use24h = v) } }
        toggle("今日进度", "24 个点代表一天，已过去的点亮起", settings.showDayProgress) { v -> update { it.copy(showDayProgress = v) } }
        toggle("天气", "数据来自 Open-Meteo，无需定位权限", settings.showWeather) { v ->
            update { it.copy(showWeather = v) }
            if (v) syncWeather()
        }
        if (settings.showWeather) {
            toggle("华氏度", null, settings.fahrenheit) { v -> update { it.copy(fahrenheit = v) } }
            toggle("天气动画", "雨雪、云雾、星光的点阵动画（仅 App 内）", settings.animateWeather) { v -> update { it.copy(animateWeather = v) } }
        }
        toggle("全屏时屏幕常亮", "点击时钟进入全屏，平板可当台钟", settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }

        header("城市")
        toggle(
            "自动定位", state.networkCity?.let { "通过网络 IP 识别：${it.name}" } ?: "通过网络 IP 识别所在城市与时区",
            settings.autoLocation,
        ) { v ->
            update { it.copy(autoLocation = v) }
            if (v) syncAll() else syncWeather()
        }
        item {
            ListItem(
                headlineContent = { Text("主城市") },
                supportingContent = {
                    Text(if (settings.autoLocation) "自动定位中，选择城市会关闭自动定位" else settings.mainCity?.name ?: "跟随系统时区")
                },
                trailingContent = { TextButton({ picker = true }) { Text("选择") } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        item {
            ListItem(
                headlineContent = { Text("世界时钟") },
                supportingContent = { Text(if (settings.cities.isEmpty()) "还没有添加城市" else "${settings.cities.size} 个城市") },
                trailingContent = {
                    IconButton({ picker = false }) { Icon(Icons.Default.Add, contentDescription = "添加城市") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        items(settings.cities, key = { "city-" + it.id }) { city ->
            ListItem(
                headlineContent = { Text(city.name) },
                supportingContent = { Text(city.tzID) },
                trailingContent = {
                    IconButton({ update { s -> s.copy(cities = s.cities - city) } }) {
                        Icon(Icons.Default.Close, contentDescription = "移除 ${city.name}")
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        header("网络")
        toggle(
            "网络校时",
            if (state.timeSynced) "已校准，本机时钟偏差 %+.2f 秒".format(state.timeOffsetMillis / 1000.0) else "启动和每 30 分钟自动校准时间",
            settings.useNetworkTime,
        ) { v ->
            update { it.copy(useNetworkTime = v) }
            if (v) syncAll()
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton({ syncAll() }, enabled = !syncing) {
                    if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("立即同步时间、位置和天气")
                }
            }
        }

        header("桌面小组件")
        item { WidgetHint() }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }

    picker?.let { pickMain ->
        CityPicker(
            settings = settings,
            pickMain = pickMain,
            onDismiss = { picker = null },
            onPick = { city ->
                if (pickMain) {
                    update { it.copy(mainCity = city, autoLocation = false) }
                    syncWeather()
                    picker = null
                } else {
                    update { s -> s.copy(cities = if (city in s.cities) s.cities - city else s.cities + city) }
                }
            },
        )
    }

    colorTarget?.let { target ->
        val current = when (target) {
            "seed" -> settings.seed
            "digits" -> settings.digitColor
            else -> settings.glassTint
        }
        ColorDialog(
            initial = ColorMath.parseHex(current),
            onDismiss = { colorTarget = null },
            onPick = { hex ->
                update {
                    when (target) {
                        "seed" -> it.copy(seed = hex)
                        "digits" -> it.copy(digitColor = hex)
                        else -> it.copy(glassTint = hex)
                    }
                }
                colorTarget = null
            },
        )
    }
}

private fun LazyListScope.header(title: String) = item(key = "h-$title") {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

private fun <T> LazyListScope.chips(options: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) = item {
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(title(option)) },
                leadingIcon = if (option == selected) {
                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
}

private fun LazyListScope.toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) = item(key = "t-$title") {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle != null) {
            { Text(subtitle) }
        } else null,
        trailingContent = { Switch(checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun <T> Segmented(options: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(title(option)) }
        }
    }
}

@Composable
private fun LabeledSlider(title: String, value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$title ${(local * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Slider(local, { local = it }, onValueChangeFinished = { onChange(local) }, modifier = Modifier.weight(1f))
    }
}

/** 一排圆形色块：预设色 + 额外选项（壁纸取色 / 跟随主题）+ 自定义。 */
@Composable
private fun Swatches(
    presets: List<Pair<String, Int>>,
    selected: String,
    extra: List<Pair<String, String>>,
    systemColor: Int?,
    onPick: (String) -> Unit,
    onCustom: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.onSurface
    FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        extra.forEach { (id, title) ->
            FilterChip(
                selected == id, { onPick(id) }, label = { Text(title) },
                leadingIcon = if (systemColor != null && id == "system") {
                    { Box(Modifier.size(14.dp).background(Color(systemColor), CircleShape)) }
                } else null,
            )
        }
        presets.forEach { (name, color) ->
            val hex = ColorMath.toHex(color)
            val isSelected = selected.equals(hex, ignoreCase = true)
            Box(
                Modifier.size(32.dp)
                    .background(Color(color), CircleShape)
                    .border(if (isSelected) 3.dp else 0.dp, outline, CircleShape)
                    .clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Icon(Icons.Default.Check, contentDescription = name, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        val custom = ColorMath.parseHex(selected)?.takeIf { c -> presets.none { it.second == c } }
        FilterChip(
            custom != null, onCustom, label = { Text("自定义") },
            leadingIcon = if (custom != null) {
                { Box(Modifier.size(14.dp).background(Color(custom), CircleShape)) }
            } else null,
        )
    }
}

/** 自定义颜色：色相 + 明暗滑块，或直接输入十六进制。 */
@Composable
private fun ColorDialog(initial: Int?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val start = initial?.let { app.dotclock.core.Cam16(it) }
    var hue by remember { mutableFloatStateOf(start?.hue?.toFloat() ?: 250f) }
    var tone by remember { mutableFloatStateOf(initial?.let { ColorMath.lstar(it).toFloat() } ?: 50f) }
    var text by remember { mutableStateOf(initial?.let { ColorMath.toHex(it) } ?: "") }
    val fromSliders = Hct.rgb(hue.toDouble(), 60.0, tone.toDouble())
    val typed = ColorMath.parseHex(text.trim().removePrefix("#").lowercase())
    val color = typed ?: fromSliders
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义颜色") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().size(56.dp).background(Color(color), MaterialTheme.shapes.medium))
                Text("色相", style = MaterialTheme.typography.labelMedium)
                Slider(hue, { hue = it; text = ColorMath.toHex(Hct.rgb(it.toDouble(), 60.0, tone.toDouble())) }, valueRange = 0f..360f)
                Text("明暗", style = MaterialTheme.typography.labelMedium)
                Slider(tone, { tone = it; text = ColorMath.toHex(Hct.rgb(hue.toDouble(), 60.0, it.toDouble())) }, valueRange = 10f..90f)
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(7) }, singleLine = true,
                    label = { Text("十六进制，例如 1b6ef3") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
            }
        },
        confirmButton = { TextButton({ onPick(ColorMath.toHex(color)) }) { Text("确定") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WidgetHint() {
    val context = LocalContext.current
    val manager = AppWidgetManager.getInstance(context)
    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 20.dp)) {
        Text(
            "长按桌面空白处 → 小组件 → 点阵时钟。小组件可自由拉伸：小尺寸只显示时间，拉大后依次显示日期、今日进度、天气和世界时钟；平板横竖屏会分别排版。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (manager.isRequestPinAppWidgetSupported) {
            Spacer(Modifier.size(8.dp))
            FilledTonalButton({
                manager.requestPinAppWidget(ComponentName(context, ClockWidgetProvider::class.java), null, null)
            }) { Text("添加到桌面") }
        }
        HorizontalDivider(Modifier.padding(top = 16.dp), color = Color.Transparent)
    }
}
