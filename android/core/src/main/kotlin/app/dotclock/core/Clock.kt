package app.dotclock.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class TimeParts(
    val hm: String,
    val ss: String,
    /** 12 小时制时的 "AM" / "PM"，24 小时制为 null */
    val period: String?,
    val second: Int,
    val hour: Int,
    val minute: Int,
)

object ClockFormat {
    /** 卡片的标准宽度（pt），与 macOS 版一致 */
    const val WIDGET_WIDTH = 344f

    fun parts(instant: Instant, zone: ZoneId, use24h: Boolean): TimeParts {
        val t = instant.atZone(zone)
        val hour = t.hour
        val shown = if (use24h) hour else if (hour % 12 == 0) 12 else hour % 12
        return TimeParts(
            hm = "%02d:%02d".format(shown, t.minute),
            ss = "%02d".format(t.second),
            period = if (use24h) null else if (hour < 12) "AM" else "PM",
            second = t.second, hour = hour, minute = t.minute,
        )
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.SIMPLIFIED_CHINESE)

    fun dateLine(instant: Instant, zone: ZoneId): String = dateFormatter.format(instant.atZone(zone))

    /** 今天已经过去的比例（按日历算，夏令时切换日也准确）。 */
    fun dayFraction(instant: Instant, zone: ZoneId): Double {
        val now = instant.atZone(zone)
        val start = now.toLocalDate().atStartOfDay(zone)
        val end = start.plusDays(1)
        return ChronoUnit.MILLIS.between(start, now).toDouble() / ChronoUnit.MILLIS.between(start, end)
    }

    /** 相对主城市的日期和时差，例如 “明天 +13H” */
    fun offsetLine(instant: Instant, zone: ZoneId, base: ZoneId): String {
        val diff = zone.rules.getOffset(instant).totalSeconds - base.rules.getOffset(instant).totalSeconds
        val dayDiff = instant.atZone(zone).toLocalDate().toEpochDay() - instant.atZone(base).toLocalDate().toEpochDay()
        val day = if (dayDiff > 0) "明天" else if (dayDiff < 0) "昨天" else "今天"
        val hoursValue = diff / 3600.0
        val hours = if (diff == 0) "+0H" else {
            val sign = if (diff > 0) "+" else "-"
            val abs = kotlin.math.abs(hoursValue)
            sign + (if (abs % 1.0 == 0.0) abs.toInt().toString() else abs.toString().trimEnd('0')) + "H"
        }
        return "$day $hours"
    }

    fun zoneOf(id: String?): ZoneId = try {
        if (id == null) ZoneId.systemDefault() else ZoneId.of(id)
    } catch (_: Exception) {
        ZoneId.systemDefault()
    }

    /** 当前时区的本地化城市名（由平台提供，例如 Android 的 ICU 时区名），拿不到就用时区 ID 末段。 */
    var exemplarCityName: (String) -> String? = { null }

    fun cityName(zoneId: String): String =
        exemplarCityName(zoneId) ?: zoneId.substringAfterLast('/').replace('_', ' ')

    fun nextMinute(now: ZonedDateTime): ZonedDateTime = now.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
}

/** 可搜索的城市库：常用城市别名 + 系统时区库；找不到时可联网地理编码。 */
object CityDB {
    data class Entry(val name: String, val english: String, val tzID: String) {
        val city get() = City(name, tzID)
    }

    val aliases: List<Entry> = listOf(
        "北京" to "Beijing" to "Asia/Shanghai", "上海" to "Shanghai" to "Asia/Shanghai",
        "东京" to "Tokyo" to "Asia/Tokyo", "首尔" to "Seoul" to "Asia/Seoul",
        "新加坡" to "Singapore" to "Asia/Singapore", "伦敦" to "London" to "Europe/London",
        "巴黎" to "Paris" to "Europe/Paris", "柏林" to "Berlin" to "Europe/Berlin",
        "纽约" to "New York" to "America/New_York", "旧金山" to "San Francisco" to "America/Los_Angeles",
        "洛杉矶" to "Los Angeles" to "America/Los_Angeles", "悉尼" to "Sydney" to "Australia/Sydney",
        "迪拜" to "Dubai" to "Asia/Dubai", "深圳" to "Shenzhen" to "Asia/Shanghai",
        "广州" to "Guangzhou" to "Asia/Shanghai", "杭州" to "Hangzhou" to "Asia/Shanghai",
        "成都" to "Chengdu" to "Asia/Shanghai", "重庆" to "Chongqing" to "Asia/Shanghai",
        "武汉" to "Wuhan" to "Asia/Shanghai", "西安" to "Xi'an" to "Asia/Shanghai",
        "南京" to "Nanjing" to "Asia/Shanghai", "合肥" to "Hefei" to "Asia/Shanghai",
        "苏州" to "Suzhou" to "Asia/Shanghai", "天津" to "Tianjin" to "Asia/Shanghai",
        "长沙" to "Changsha" to "Asia/Shanghai", "郑州" to "Zhengzhou" to "Asia/Shanghai",
        "青岛" to "Qingdao" to "Asia/Shanghai", "厦门" to "Xiamen" to "Asia/Shanghai",
        "香港" to "Hong Kong" to "Asia/Hong_Kong", "台北" to "Taipei" to "Asia/Taipei",
        "大阪" to "Osaka" to "Asia/Tokyo", "京都" to "Kyoto" to "Asia/Tokyo",
        "孟买" to "Mumbai" to "Asia/Kolkata", "新德里" to "New Delhi" to "Asia/Kolkata",
        "班加罗尔" to "Bangalore" to "Asia/Kolkata", "河内" to "Hanoi" to "Asia/Ho_Chi_Minh",
        "阿布扎比" to "Abu Dhabi" to "Asia/Dubai", "硅谷" to "Silicon Valley" to "America/Los_Angeles",
        "西雅图" to "Seattle" to "America/Los_Angeles", "拉斯维加斯" to "Las Vegas" to "America/Los_Angeles",
        "波士顿" to "Boston" to "America/New_York", "华盛顿" to "Washington DC" to "America/New_York",
        "迈阿密" to "Miami" to "America/New_York", "亚特兰大" to "Atlanta" to "America/New_York",
        "休斯顿" to "Houston" to "America/Chicago", "达拉斯" to "Dallas" to "America/Chicago",
        "蒙特利尔" to "Montreal" to "America/Toronto", "曼彻斯特" to "Manchester" to "Europe/London",
        "爱丁堡" to "Edinburgh" to "Europe/London", "慕尼黑" to "Munich" to "Europe/Berlin",
        "法兰克福" to "Frankfurt" to "Europe/Berlin", "米兰" to "Milan" to "Europe/Rome",
        "巴塞罗那" to "Barcelona" to "Europe/Madrid", "日内瓦" to "Geneva" to "Europe/Zurich",
    ).map { (pair, tz) -> Entry(pair.first, pair.second, tz) }

    val all: List<Entry> by lazy {
        val entries = aliases.toMutableList()
        val seen = entries.map { it.city.id }.toMutableSet()
        for (id in ZoneId.getAvailableZoneIds().sorted()) {
            if ('/' !in id || id.startsWith("Etc/") || id.startsWith("SystemV/")) continue
            val english = id.substringAfterLast('/').replace('_', ' ')
            val entry = Entry(ClockFormat.cityName(id), english, id)
            if (seen.add(entry.city.id)) entries += entry
        }
        entries
    }

    fun search(query: String): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all.take(40)
        val hits = all.filter { q in it.name.lowercase() || q in it.english.lowercase() || q in it.tzID.lowercase() }
        val prefixed = hits.filter { it.name.lowercase().startsWith(q) || it.english.lowercase().startsWith(q) }
        return (prefixed + (hits - prefixed.toSet())).take(60)
    }
}

/** 网络同步：校时、IP 定位、天气。对应 macOS 版 Store 里的同步逻辑，供 App 和后台任务共用。 */
object ClockSync {
    fun activeCity(settings: ClockSettings, state: NetworkState): City? =
        if (settings.autoLocation) state.networkCity ?: settings.mainCity else settings.mainCity

    fun mainZone(settings: ClockSettings, state: NetworkState): ZoneId =
        ClockFormat.zoneOf(activeCity(settings, state)?.tzID)

    fun mainName(settings: ClockSettings, state: NetworkState): String =
        activeCity(settings, state)?.name ?: ClockFormat.cityName(ZoneId.systemDefault().id)

    fun weatherKey(settings: ClockSettings, state: NetworkState) =
        mainName(settings, state) + "|" + mainZone(settings, state).id

    /** 当前要显示的天气：城市变了的旧天气、3 小时以上没更新的天气都不显示。 */
    fun visibleWeather(settings: ClockSettings, state: NetworkState, nowMillis: Long): Weather? {
        if (!settings.showWeather) return null
        if (state.weatherKey != weatherKey(settings, state)) return null
        if (nowMillis - state.weatherUpdatedAt > 3 * 3600_000L) return null
        return state.weather
    }

    fun calibratedMillis(settings: ClockSettings, state: NetworkState, systemMillis: Long = System.currentTimeMillis()) =
        if (settings.useNetworkTime && state.timeSynced) systemMillis + state.timeOffsetMillis else systemMillis

    /** 执行一次全量同步，返回新的状态（失败的部分保留旧值）。 */
    suspend fun syncAll(settings: ClockSettings, old: NetworkState): NetworkState {
        var state = old
        if (settings.useNetworkTime) {
            NetworkSync.syncTimeOffset()?.let { state = state.copy(timeOffsetMillis = it, timeSynced = true) }
        }
        if (settings.autoLocation) {
            NetworkSync.fetchLocation()?.let { state = state.copy(networkCity = it.city) }
        }
        return refreshWeather(settings, state)
    }

    suspend fun refreshWeather(settings: ClockSettings, old: NetworkState): NetworkState {
        if (!settings.showWeather) return old
        val city = activeCity(settings, old)
        val name = mainName(settings, old)
        val zone = mainZone(settings, old).id
        val key = "$name|$zone"
        var state = old
        val coordinate: Pair<Double, Double>? = if (city?.latitude != null && city.longitude != null) {
            city.latitude to city.longitude
        } else {
            state.coordinates[key]?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
                ?: WeatherService.coordinate(name, zone)?.also {
                    state = state.copy(coordinates = state.coordinates + (key to listOf(it.first, it.second)))
                }
        }
        coordinate ?: return state
        val weather = WeatherService.fetch(coordinate.first, coordinate.second) ?: return state
        return state.copy(weather = weather, weatherKey = key, weatherUpdatedAt = System.currentTimeMillis())
    }
}
