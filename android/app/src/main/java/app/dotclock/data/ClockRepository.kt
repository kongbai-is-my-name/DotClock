package app.dotclock.data

import android.content.Context
import android.os.Build
import android.util.Log
import app.dotclock.core.ClockSettings
import app.dotclock.core.ClockSync
import app.dotclock.core.NetworkState
import app.dotclock.widget.WidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * 设置与联网状态的唯一来源。存在 SharedPreferences 里（JSON），App 界面、后台同步和桌面小组件共用。
 */
class ClockRepository private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("dotclock", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val syncLock = Mutex()

    private val _settings = MutableStateFlow(load(KEY_SETTINGS) { ClockSettings() })
    val settings: StateFlow<ClockSettings> = _settings.asStateFlow()

    private val _state = MutableStateFlow(load(KEY_STATE) { NetworkState() })
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private inline fun <reified T> load(key: String, default: () -> T): T =
        prefs.getString(key, null)?.let {
            try {
                json.decodeFromString<T>(it)
            } catch (e: Exception) {
                Log.w(TAG, "读取 $key 失败，使用默认值", e)
                null
            }
        } ?: default()

    /** Android 12+ 的壁纸主色，作为“跟随壁纸”主题的种子。 */
    val systemSeed: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getColor(android.R.color.system_accent1_500) else null

    fun updateSettings(transform: (ClockSettings) -> ClockSettings) {
        val old = _settings.value
        val new = transform(old)
        if (new == old) return
        _settings.value = new
        prefs.edit().putString(KEY_SETTINGS, json.encodeToString(ClockSettings.serializer(), new)).apply()
        WidgetUpdater.updateAll(context)
    }

    private fun saveState(new: NetworkState) {
        _state.value = new
        prefs.edit().putString(KEY_STATE, json.encodeToString(NetworkState.serializer(), new)).apply()
    }

    /** 全量同步（校时 + 定位 + 天气）；`weatherOnly` 时只刷新天气（换城市、开关天气后）。 */
    suspend fun sync(weatherOnly: Boolean = false) = syncLock.withLock {
        _syncing.value = true
        try {
            val settings = _settings.value
            val new = if (weatherOnly) ClockSync.refreshWeather(settings, _state.value)
            else ClockSync.syncAll(settings, _state.value)
            if (new != _state.value) {
                saveState(new)
                WidgetUpdater.updateAll(context)
            }
            Log.i(TAG, "同步完成：offset=${new.timeOffsetMillis}ms city=${new.networkCity?.name} weather=${new.weather}")
        } catch (e: Exception) {
            Log.w(TAG, "同步失败", e)
        } finally {
            _syncing.value = false
        }
    }

    companion object {
        private const val TAG = "DotClock"
        private const val KEY_SETTINGS = "settings"
        private const val KEY_STATE = "state"

        @Volatile
        private var instance: ClockRepository? = null

        fun get(context: Context): ClockRepository = instance ?: synchronized(this) {
            instance ?: ClockRepository(context.applicationContext).also { instance = it }
        }
    }
}
