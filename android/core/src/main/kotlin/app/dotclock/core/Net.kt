package app.dotclock.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 极简 HTTP 工具：5 秒超时，不走缓存。 */
internal object Http {
    class Response(val code: Int, val body: String?, val date: String?)

    fun request(url: String, method: String = "GET"): Response? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.requestMethod = method
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", "DotClock-Android")
        try {
            val code = connection.responseCode
            val body = if (method == "HEAD" || code !in 200..299) null
            else connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            Response(code, body, connection.getHeaderField("Date"))
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    fun json(url: String): JsonObject? = request(url)?.body?.let { parse(it) }

    fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (_: Exception) {
        null
    }

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
internal fun JsonElement.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/** 纯网络时间校准与 IP 自动定位服务（无需定位权限）。移植自 macOS 版 NetworkSync.swift。 */
object NetworkSync {
    data class LocationResult(val name: String, val tzID: String, val latitude: Double? = null, val longitude: Double? = null) {
        val city get() = City(name, tzID, latitude, longitude)
    }

    /**
     * 网络标准时间与本机时钟的偏移（毫秒）：`真实时间 = 本机时间 + offset`。
     * 自动扣除单程网络延迟（RTT / 2），多源顺序容错。
     */
    suspend fun syncTimeOffset(): Long? = withContext(Dispatchers.IO) {
        fromTaobao() ?: fromTencent() ?: fromHttpDate("https://www.apple.com") ?: fromHttpDate("https://www.baidu.com")
    }

    private inline fun timed(block: () -> Long?): Long? {
        val start = System.nanoTime()
        val serverMillis = block() ?: return null
        val rtt = (System.nanoTime() - start) / 1_000_000
        return serverMillis + rtt / 2 - System.currentTimeMillis()
    }

    /** 源 1：淘宝毫秒级授时接口。 */
    private fun fromTaobao(): Long? = timed {
        Http.json("https://acs.m.taobao.com/gw/mtop.common.getTimestamp/")?.obj("data")?.str("t")?.toLongOrNull()
    }

    /** 源 2：腾讯秒级授时接口（返回 JSONP）。 */
    private fun fromTencent(): Long? = timed {
        val text = Http.request("https://vv.video.qq.com/checktime?otype=json")?.body ?: return@timed null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return@timed null
        Http.parse(text.substring(start, end + 1))?.num("t")?.let { (it * 1000).toLong() }
    }

    /** 源 3 / 4：HTTPS HEAD 响应头里的 Date 字段（秒级）。 */
    private fun fromHttpDate(url: String): Long? = timed {
        val header = Http.request(url, "HEAD")?.date ?: return@timed null
        try {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 通过公网 IP 获取当前城市、时区与坐标。
     * 优先国内直连接口（开着分流代理也不会被判到海外节点），失败再回退国际接口。
     */
    suspend fun fetchLocation(): LocationResult? = withContext(Dispatchers.IO) {
        val domestic = viaTencent() ?: viaIpip()
        if (domestic != null) {
            val coordinate = WeatherService.coordinate(domestic.name, domestic.tzID)
            return@withContext domestic.copy(latitude = coordinate?.first, longitude = coordinate?.second)
        }
        viaIpWhois() ?: viaIpApi()
    }

    private fun viaTencent(): LocationResult? {
        val json = Http.json("https://r.inews.qq.com/api/ip2city") ?: return null
        if (json.num("ret")?.toInt() != 0) return null
        val province = json.str("province").orEmpty()
        val rawCity = json.str("city").orEmpty()
        if (json.str("country") != "中国" || (province.isEmpty() && rawCity.isEmpty())) return null
        val name = cleanChineseCityName(rawCity.ifEmpty { province })
        return LocationResult(name, chineseTimeZone(province, name))
    }

    private fun viaIpip(): LocationResult? {
        val location = Http.json("https://myip.ipip.net/json")?.obj("data")?.arr("location")
            ?.map { (it as? JsonPrimitive)?.content.orEmpty() } ?: return null
        if (location.firstOrNull() != "中国") return null
        val province = location.getOrElse(1) { "" }
        val rawCity = location.getOrElse(2) { "" }
        val chosen = rawCity.ifEmpty { province }
        if (chosen.isEmpty()) return null
        val name = cleanChineseCityName(chosen)
        return LocationResult(name, chineseTimeZone(province, name))
    }

    private fun viaIpWhois(): LocationResult? {
        val json = Http.json("https://ipwho.is/?lang=zh-CN") ?: return null
        if ((json["success"] as? JsonPrimitive)?.booleanOrNull == false) return null
        val city = json.str("city")?.takeIf { it.isNotEmpty() } ?: return null
        val tz = json.obj("timezone")?.str("id")?.takeIf(::validZone) ?: return null
        return LocationResult(cleanChineseCityName(city), tz, json.num("latitude"), json.num("longitude"))
    }

    private fun viaIpApi(): LocationResult? {
        val json = Http.json("https://ipapi.co/json/") ?: return null
        val city = json.str("city")?.takeIf { it.isNotEmpty() } ?: return null
        val tz = json.str("timezone")?.takeIf(::validZone) ?: return null
        return LocationResult(city, tz, json.num("latitude"), json.num("longitude"))
    }

    private fun validZone(id: String) = try {
        ZoneId.of(id); true
    } catch (_: Exception) {
        false
    }

    fun cleanChineseCityName(raw: String): String {
        var s = raw.trim()
        for (suffix in listOf("特别行政区", "维吾尔自治区", "壮族自治区", "回族自治区", "自治区")) {
            if (s.endsWith(suffix) && s.length > suffix.length) s = s.dropLast(suffix.length)
        }
        if (s.length > 2 && (s.last() == '市' || s.last() == '省')) s = s.dropLast(1)
        return s
    }

    private fun chineseTimeZone(province: String, city: String): String {
        val combined = province + city
        return when {
            "香港" in combined -> "Asia/Hong_Kong"
            "澳门" in combined -> "Asia/Macau"
            "台湾" in combined || "台北" in combined || "高雄" in combined -> "Asia/Taipei"
            else -> "Asia/Shanghai"
        }
    }
}

/** 天气数据来自 Open-Meteo（免费、无需密钥）。 */
object WeatherService {
    /** 城市 → 坐标：Open-Meteo 地理编码，优先匹配同时区的结果。查不到返回 null。 */
    suspend fun coordinate(name: String, tzID: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val entry = CityDB.aliases.firstOrNull { it.name == name && it.tzID == tzID }
        var query = entry?.english ?: name
        // 地名库里一般不带“市 / 县 / 区”后缀
        if (entry == null && query.length > 2 && query.last() in "市县区") query = query.dropLast(1)
        val results = search(query)
        val best = results.firstOrNull { it.tzID == tzID } ?: results.firstOrNull()
        best?.let { b -> b.latitude?.let { lat -> b.longitude?.let { lon -> lat to lon } } }
    }

    /** 联网地理编码搜索城市（替代 macOS 版的 CLGeocoder）。 */
    suspend fun search(query: String): List<City> = withContext(Dispatchers.IO) {
        val json = Http.json(
            "https://geocoding-api.open-meteo.com/v1/search?name=${Http.encode(query)}&count=10&language=zh&format=json",
        ) ?: return@withContext emptyList()
        json.arr("results").orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            val tz = o.str("timezone") ?: return@mapNotNull null
            City(name, tz, o.num("latitude"), o.num("longitude"))
        }
    }

    suspend fun fetch(latitude: Double, longitude: Double): Weather? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f".format(java.util.Locale.US, latitude, longitude) +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
        val json = Http.json(url) ?: return@withContext null
        parseForecast(json)
    }

    fun parseForecast(json: JsonObject): Weather? {
        val current = json.obj("current") ?: return null
        val temperature = current.num("temperature_2m") ?: return null
        val code = (current["weather_code"] as? JsonPrimitive)?.intOrNull
            ?: current.num("weather_code")?.toInt() ?: return null
        val daily = json.obj("daily")
        val high = daily?.arr("temperature_2m_max")?.firstOrNull()?.asDouble()
        val low = daily?.arr("temperature_2m_min")?.firstOrNull()?.asDouble()
        val isDay = current["is_day"]?.jsonPrimitive?.intOrNull != 0
        return Weather(temperature, high ?: temperature, low ?: temperature, code, isDay)
    }
}
