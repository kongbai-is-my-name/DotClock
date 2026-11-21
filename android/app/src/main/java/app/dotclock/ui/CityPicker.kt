package app.dotclock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.dotclock.core.City
import app.dotclock.core.CityDB
import app.dotclock.core.ClockSettings
import app.dotclock.core.WeatherService
import kotlinx.coroutines.launch

/**
 * 城市搜索：内置常用城市 + 系统时区库，找不到时按回车联网搜索（Open-Meteo 地理编码）。
 * `pickMain` 为 true 时选主城市（选中即关闭），否则添加 / 移除世界时钟城市。
 */
@Composable
fun CityPicker(settings: ClockSettings, pickMain: Boolean, onDismiss: () -> Unit, onPick: (City) -> Unit) {
    var query by remember { mutableStateOf("") }
    var online by remember { mutableStateOf<List<City>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun searchOnline() {
        val text = query.trim()
        if (text.isEmpty()) return
        status = "正在联网搜索…"
        scope.launch {
            val found = WeatherService.search(text)
            if (text != query.trim()) return@launch
            online = found
            status = if (found.isEmpty()) "没有找到“$text”" else ""
        }
    }

    val local = remember(query) { CityDB.search(query).map { it.city } }
    val results = (online + local).distinctBy { it.id }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.92f).heightIn(max = 640.dp),
        ) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    if (pickMain) "选择主城市" else "世界时钟",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        online = emptyList()
                        status = ""
                    },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("搜索城市，例如 东京 / London") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { searchOnline() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(results, key = { it.id }) { city ->
                        val checked = if (pickMain) !settings.autoLocation && settings.mainCity == city else city in settings.cities
                        ListItem(
                            headlineContent = { Text(city.name) },
                            supportingContent = { Text(city.tzID) },
                            trailingContent = if (checked) {
                                { Icon(Icons.Default.Check, contentDescription = "已选", tint = MaterialTheme.colorScheme.primary) }
                            } else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(city) },
                        )
                    }
                }
                Text(
                    status.ifEmpty { if (pickMain) "点击设为主城市 · 没找到？按回车联网搜索" else "点击添加或移除 · 没找到？按回车联网搜索" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                TextButton(onDismiss, Modifier.padding(horizontal = 12.dp)) { Text("完成") }
            }
        }
    }
}
