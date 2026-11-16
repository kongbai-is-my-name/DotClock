import AppKit
import Combine
import CoreLocation
import SwiftUI

struct City: Codable, Hashable, Identifiable {
    var name: String
    var tzID: String
    /// 联网搜索到的城市会带上坐标，供天气使用
    var latitude: Double?
    var longitude: Double?
    var id: String { name + "|" + tzID }

    static func == (a: City, b: City) -> Bool { a.id == b.id }
    func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

enum SeedPreset {
    static let all: [(name: String, hex: RGB)] = [
        ("Nothing 红", 0xD71921), ("海蓝", 0x1B6EF3), ("森绿", 0x2E7D32),
        ("薰紫", 0x6750A4), ("琥珀", 0xF59E0B), ("樱粉", 0xE91E63),
    ]
}

final class Store: ObservableObject {
    static let zoomRange: ClosedRange<CGFloat> = 0.5...3

    @Published var cities: [City] { didSet { save(cities, "cities") } }
    @Published var showDayProgress: Bool { didSet { defaults.set(showDayProgress, forKey: "showDayProgress") } }
    @Published var use24h: Bool { didSet { defaults.set(use24h, forKey: "use24h") } }
    /// 主题种子的十六进制颜色字符串（不再读取本地壁纸文件）
    @Published var seedMode: String { didSet { defaults.set(seedMode, forKey: "seedMode"); refreshTheme() } }
    /// "auto" / "dark" / "light"
    @Published var appearance: String { didSet { defaults.set(appearance, forKey: "appearance") } }
    /// 是否自动通过网络定位当前城市与时区
    @Published var autoLocation: Bool {
        didSet {
            defaults.set(autoLocation, forKey: "autoLocation")
            if autoLocation { refreshLocationAndWeather() } else { refreshWeather() }
        }
    }
    /// 是否自动通过网络校准时间
    @Published var useNetworkTime: Bool {
        didSet {
            defaults.set(useNetworkTime, forKey: "useNetworkTime")
            if useNetworkTime { syncNetworkTime() }
        }
    }
    /// 手动指定的主城市（autoLocation == false 时生效；nil = 跟随系统时区）
    @Published var mainCity: City? { didSet { save(mainCity, "mainCity"); if !autoLocation { refreshWeather() } } }
    @Published var showWeather: Bool { didSet { defaults.set(showWeather, forKey: "showWeather"); refreshWeather() } }
    @Published var fahrenheit: Bool { didSet { defaults.set(fahrenheit, forKey: "fahrenheit") } }
    @Published var textFont: String { didSet { defaults.set(textFont, forKey: "textFont") } }
    @Published var digitStyle: DigitStyle { didSet { defaults.set(digitStyle.rawValue, forKey: "digitStyle") } }
    /// "primary" / "accent" / "mono" 或十六进制颜色
    @Published var digitColor: String { didSet { defaults.set(digitColor, forKey: "digitColor") } }
    /// 小组件缩放倍数（拖动边缘或在菜单里调整）
    @Published var zoom: CGFloat { didSet { defaults.set(Double(zoom), forKey: "zoom") } }
    /// 宽度相对标准宽度的倍数（≥1，只拉宽时变大）
    @Published var widthFactor: CGFloat { didSet { defaults.set(Double(widthFactor), forKey: "widthFactor") } }
    /// 额外高度（缩放前的点数，≥0，只拉长时变大）
    @Published var extraHeight: CGFloat { didSet { defaults.set(Double(extraHeight), forKey: "extraHeight") } }
    @Published var animateWeather: Bool { didSet { defaults.set(animateWeather, forKey: "animateWeather") } }
    /// 小组件当前是否露在屏幕上；被全屏窗口等完全遮住时暂停动画，省电
    @Published var isVisible = true
    @Published private(set) var weather: Weather?
    @Published private(set) var networkCity: City?
    @Published private(set) var timeOffset: TimeInterval = 0
    @Published private(set) var timeSynced = false
    @Published var style: ClockStyle { didSet { defaults.set(style.rawValue, forKey: "style") } }
    @Published private(set) var themes = ThemePair(seed: Monet.fallbackSeed)

    private let defaults = UserDefaults.standard
    private var syncTimer: Timer?
    private var weatherKey = ""
    private var wakeObserver: NSObjectProtocol?
    private var clockObserver: NSObjectProtocol?

    init() {
        if let data = defaults.data(forKey: "cities"), let list = try? JSONDecoder().decode([City].self, from: data) {
            cities = list
        } else {
            cities = []
        }
        showDayProgress = defaults.object(forKey: "showDayProgress") as? Bool ?? true
        use24h = defaults.object(forKey: "use24h") as? Bool ?? true
        let rawSeed = defaults.string(forKey: "seedMode") ?? Monet.defaultSeedHex
        if RGB(rawSeed, radix: 16) != nil {
            seedMode = rawSeed
        } else {
            // 旧版 "wallpaper" 配置自动迁移为内置海蓝预设色，不再读取任何壁纸图片文件
            seedMode = Monet.defaultSeedHex
            defaults.set(Monet.defaultSeedHex, forKey: "seedMode")
        }
        appearance = defaults.string(forKey: "appearance") ?? "auto"
        style = ClockStyle(rawValue: defaults.string(forKey: "style") ?? "") ?? .classic
        autoLocation = defaults.object(forKey: "autoLocation") as? Bool ?? true
        useNetworkTime = defaults.object(forKey: "useNetworkTime") as? Bool ?? true
        mainCity = defaults.data(forKey: "mainCity").flatMap { try? JSONDecoder().decode(City.self, from: $0) }
        networkCity = defaults.data(forKey: "networkCity").flatMap { try? JSONDecoder().decode(City.self, from: $0) }
        showWeather = defaults.object(forKey: "showWeather") as? Bool ?? true
        fahrenheit = defaults.bool(forKey: "fahrenheit")
        animateWeather = defaults.object(forKey: "animateWeather") as? Bool ?? true
        let savedFont = defaults.string(forKey: "textFont") ?? TextFont.dot
        textFont = TextFont.options.contains { $0.id == savedFont } ? savedFont : TextFont.dot
        let savedDigits = DigitStyle(rawValue: defaults.string(forKey: "digitStyle") ?? "") ?? .round
        digitStyle = savedDigits.isAvailable ? savedDigits : .round
        digitColor = defaults.string(forKey: "digitColor") ?? "primary"
        let savedZoom = defaults.double(forKey: "zoom")
        widthFactor = max(1, CGFloat(defaults.double(forKey: "widthFactor")))
        extraHeight = max(0, CGFloat(defaults.double(forKey: "extraHeight")))
        zoom = savedZoom > 0 ? min(Store.zoomRange.upperBound, max(Store.zoomRange.lowerBound, CGFloat(savedZoom))) : 1
        refreshTheme()
    }

    var activeCity: City? { autoLocation ? (networkCity ?? mainCity) : mainCity }
    var mainName: String { activeCity?.name ?? ClockFormat.localCityName() }
    var mainZone: TimeZone { activeCity.flatMap { TimeZone(identifier: $0.tzID) } ?? .current }
    var look: Look { Look(textFont: textFont, digitStyle: digitStyle) }

    /// 返回经过网络时间偏移校准后的当前时间。
    func calibratedDate(_ systemDate: Date = Date()) -> Date {
        (useNetworkTime && timeSynced) ? systemDate.addingTimeInterval(timeOffset) : systemDate
    }

    /// 当前主题，并套用用户选择的数字颜色。
    func theme(dark: Bool) -> Theme {
        Store.theme(themes, dark: dark, digitColor: digitColor)
    }

    static func theme(_ themes: ThemePair, dark: Bool, digitColor: String) -> Theme {
        var theme = dark ? themes.dark : themes.light
        switch digitColor {
        case "primary": break
        case "accent": theme.digits = theme.accent
        case "mono": theme.digits = dark ? .white : .black
        default:
            if let hex = RGB(digitColor, radix: 16) {
                let (r, g, b) = ColorMath.channels(hex)
                theme.digits = Color(.sRGB, red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255)
            }
        }
        return theme
    }

    /// 同步网络标准时间。
    func syncNetworkTime() {
        guard useNetworkTime else { return }
        NetworkSync.syncTimeOffset { [weak self] offset in
            guard let self, let offset else { return }
            self.timeOffset = offset
            self.timeSynced = true
            NSLog("DotClock network time synced: offset %.3fs", offset)
        }
    }

    /// 自动通过网络定位城市并刷新天气。
    func refreshLocationAndWeather() {
        guard autoLocation else {
            refreshWeather()
            return
        }
        NetworkSync.fetchLocation { [weak self] loc in
            guard let self else { return }
            if let loc {
                let detected = loc.city
                self.networkCity = detected
                self.save(detected, "networkCity")
                NSLog("DotClock network location: %@ (%@)", detected.name, detected.tzID)
            }
            self.refreshWeather()
        }
    }

    /// 拉取主城市的天气。
    func refreshWeather() {
        guard showWeather else { return }
        let city = activeCity
        let name = mainName, zone = mainZone.identifier
        let key = name + "|" + zone
        if key != weatherKey { weather = nil }
        weatherKey = key
        let load: (Double, Double) -> Void = { [weak self] latitude, longitude in
            WeatherService.fetch(latitude: latitude, longitude: longitude) { result in
                NSLog("DotClock weather %@: %@", key, result.map { "\($0.temperature)°C code \($0.code)" } ?? "failed")
                guard let self, self.weatherKey == key, let result else { return }
                self.weather = result
            }
        }
        if let latitude = city?.latitude, let longitude = city?.longitude {
            load(latitude, longitude)
            return
        }
        var cache = defaults.dictionary(forKey: "coordinates3") as? [String: [Double]] ?? [:]
        if let saved = cache[key], saved.count == 2 {
            load(saved[0], saved[1])
            return
        }
        WeatherService.coordinate(name: name, tzID: zone) { [weak self] coordinate in
            DispatchQueue.main.async {
                if coordinate == nil { NSLog("DotClock weather %@: location not found", key) }
                guard let self, let coordinate else { return }
                cache[key] = [coordinate.0, coordinate.1]
                self.defaults.set(cache, forKey: "coordinates3")
                if self.weatherKey == key { load(coordinate.0, coordinate.1) }
            }
        }
    }

    /// 立即执行一次全量网络同步（时间 + 位置 + 天气）。
    func syncAllFromNetwork() {
        syncNetworkTime()
        if autoLocation {
            refreshLocationAndWeather()
        } else {
            refreshWeather()
        }
    }

    /// 启动网络自动同步服务（每 15 分钟自动更新网络时间、定位与天气；睡眠唤醒或系统时钟改动时立即重校准）。
    func startNetworkServices() {
        syncAllFromNetwork()
        syncTimer = Timer.scheduledTimer(withTimeInterval: 900, repeats: true) { [weak self] _ in
            self?.syncAllFromNetwork()
        }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.syncAllFromNetwork()
        }
        clockObserver = NotificationCenter.default.addObserver(
            forName: .NSSystemClockDidChange, object: nil, queue: .main
        ) { [weak self] _ in
            self?.syncNetworkTime()
        }
    }

    func refreshTheme() {
        themes = ThemePair(seed: RGB(seedMode, radix: 16) ?? Monet.fallbackSeed)
    }

    func selectManualMainCity(_ city: City?) {
        mainCity = city
        if autoLocation {
            autoLocation = false
        } else {
            refreshWeather()
        }
    }

    func add(_ city: City) {
        if !cities.contains(city) { cities.append(city) }
    }

    func remove(_ city: City) {
        cities.removeAll { $0 == city }
    }

    private func save<T: Encodable>(_ value: T, _ key: String) {
        defaults.set(try? JSONEncoder().encode(value), forKey: key)
    }
}

/// 可搜索的城市库：系统时区库 + 常用城市别名；找不到时可联网地理编码。
enum CityDB {
    struct Entry: Identifiable, Hashable {
        let name: String
        let english: String
        let tzID: String
        var latitude: Double?
        var longitude: Double?
        var id: String { name + "|" + tzID }
        var city: City { City(name: name, tzID: tzID, latitude: latitude, longitude: longitude) }

        static func == (a: Entry, b: Entry) -> Bool { a.id == b.id }
        func hash(into hasher: inout Hasher) { hasher.combine(id) }
    }

    private static let aliases: [(String, String, String)] = [
        ("北京", "Beijing", "Asia/Shanghai"), ("上海", "Shanghai", "Asia/Shanghai"),
        ("东京", "Tokyo", "Asia/Tokyo"), ("首尔", "Seoul", "Asia/Seoul"),
        ("新加坡", "Singapore", "Asia/Singapore"), ("伦敦", "London", "Europe/London"),
        ("巴黎", "Paris", "Europe/Paris"), ("柏林", "Berlin", "Europe/Berlin"),
        ("纽约", "New York", "America/New_York"), ("旧金山", "San Francisco", "America/Los_Angeles"),
        ("洛杉矶", "Los Angeles", "America/Los_Angeles"), ("悉尼", "Sydney", "Australia/Sydney"),
        ("迪拜", "Dubai", "Asia/Dubai"), ("深圳", "Shenzhen", "Asia/Shanghai"),
        ("广州", "Guangzhou", "Asia/Shanghai"), ("杭州", "Hangzhou", "Asia/Shanghai"),
        ("成都", "Chengdu", "Asia/Shanghai"), ("重庆", "Chongqing", "Asia/Shanghai"),
        ("武汉", "Wuhan", "Asia/Shanghai"), ("西安", "Xi'an", "Asia/Shanghai"),
        ("南京", "Nanjing", "Asia/Shanghai"), ("合肥", "Hefei", "Asia/Shanghai"),
        ("苏州", "Suzhou", "Asia/Shanghai"), ("天津", "Tianjin", "Asia/Shanghai"),
        ("长沙", "Changsha", "Asia/Shanghai"), ("郑州", "Zhengzhou", "Asia/Shanghai"),
        ("青岛", "Qingdao", "Asia/Shanghai"), ("厦门", "Xiamen", "Asia/Shanghai"),
        ("香港", "Hong Kong", "Asia/Hong_Kong"), ("台北", "Taipei", "Asia/Taipei"),
        ("大阪", "Osaka", "Asia/Tokyo"),
        ("京都", "Kyoto", "Asia/Tokyo"), ("孟买", "Mumbai", "Asia/Kolkata"),
        ("新德里", "New Delhi", "Asia/Kolkata"), ("班加罗尔", "Bangalore", "Asia/Kolkata"),
        ("河内", "Hanoi", "Asia/Ho_Chi_Minh"), ("阿布扎比", "Abu Dhabi", "Asia/Dubai"),
        ("硅谷", "Silicon Valley", "America/Los_Angeles"), ("西雅图", "Seattle", "America/Los_Angeles"),
        ("拉斯维加斯", "Las Vegas", "America/Los_Angeles"), ("波士顿", "Boston", "America/New_York"),
        ("华盛顿", "Washington DC", "America/New_York"), ("迈阿密", "Miami", "America/New_York"),
        ("亚特兰大", "Atlanta", "America/New_York"), ("休斯顿", "Houston", "America/Chicago"),
        ("达拉斯", "Dallas", "America/Chicago"), ("蒙特利尔", "Montreal", "America/Toronto"),
        ("曼彻斯特", "Manchester", "Europe/London"), ("爱丁堡", "Edinburgh", "Europe/London"),
        ("慕尼黑", "Munich", "Europe/Berlin"), ("法兰克福", "Frankfurt", "Europe/Berlin"),
        ("米兰", "Milan", "Europe/Rome"), ("巴塞罗那", "Barcelona", "Europe/Madrid"),
        ("日内瓦", "Geneva", "Europe/Zurich"),
    ]

    static let all: [Entry] = {
        var entries = aliases.map { Entry(name: $0.0, english: $0.1, tzID: $0.2) }
        var seen = Set(entries.map(\.id))
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "VVV"
        for id in TimeZone.knownTimeZoneIdentifiers.sorted() {
            guard id.contains("/"), !id.hasPrefix("Etc/"), let zone = TimeZone(identifier: id) else { continue }
            formatter.timeZone = zone
            let english = (id.split(separator: "/").last.map(String.init) ?? id).replacingOccurrences(of: "_", with: " ")
            let entry = Entry(name: formatter.string(from: Date()), english: english, tzID: id)
            if seen.insert(entry.id).inserted { entries.append(entry) }
        }
        return entries
    }()

    static func search(_ query: String) -> [Entry] {
        let q = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return Array(all.prefix(40)) }
        let hits = all.filter {
            $0.name.lowercased().contains(q) || $0.english.lowercased().contains(q) || $0.tzID.lowercased().contains(q)
        }
        let prefixed = hits.filter { $0.name.lowercased().hasPrefix(q) || $0.english.lowercased().hasPrefix(q) }
        let rest = hits.filter { !prefixed.contains($0) }
        return Array((prefixed + rest).prefix(60))
    }

    /// 联网查询任意城市的时区（Apple 地理编码服务）。
    static func lookupOnline(_ query: String, completion: @escaping ([Entry]) -> Void) {
        CLGeocoder().geocodeAddressString(query) { placemarks, _ in
            let entries = (placemarks ?? []).compactMap { mark -> Entry? in
                guard let zone = mark.timeZone, let name = mark.locality ?? mark.name else { return nil }
                return Entry(name: name, english: mark.country ?? "", tzID: zone.identifier,
                             latitude: mark.location?.coordinate.latitude, longitude: mark.location?.coordinate.longitude)
            }
            DispatchQueue.main.async { completion(entries) }
        }
    }
}
