import SwiftUI

enum WeatherKind {
    case clear, clearNight, partly, cloudy, fog, rain, snow, thunder

    /// WMO 天气代码 → 类型。
    init(code: Int, isDay: Bool) {
        switch code {
        case 0: self = isDay ? .clear : .clearNight
        case 1, 2: self = isDay ? .partly : .clearNight
        case 3: self = .cloudy
        case 45, 48: self = .fog
        case 71...77, 85, 86: self = .snow
        case 95...99: self = .thunder
        case 51...67, 80...82: self = .rain
        default: self = .cloudy
        }
    }

    var title: String {
        switch self {
        case .clear, .clearNight: return "晴"
        case .partly: return "多云"
        case .cloudy: return "阴"
        case .fog: return "雾"
        case .rain: return "雨"
        case .snow: return "雪"
        case .thunder: return "雷雨"
        }
    }

    private static let cloud = [
        "....###....",
        "...#...#...",
        "..#.....##.",
        ".##.......#",
        "#.........#",
        "#.........#",
        ".#########.",
    ]

    /// 11×11 点阵图标：`#` 用主色，`o` 用强调色。
    var icon: [String] {
        let blank = "..........."
        switch self {
        case .clear:
            return [".....#.....", ".#...#...#.", "..#.....#..", "....ooo....", "...ooooo...",
                    "##.ooooo.##", "...ooooo...", "....ooo....", "..#.....#..", ".#...#...#.", ".....#....."]
        case .clearNight:
            return [blank, "....####...", "...##......", "..##.......", "..##.....o.", "..##.......",
                    "..###......", "...###...#.", "....######.", blank, blank]
        case .partly:
            return ["......ooo..", ".....o...o.", "....###...o", "...#...#..o", "..#.....##.", ".##.......#",
                    "#.........#", "#.........#", ".#########.", blank, blank]
        case .cloudy:
            return [blank, blank] + WeatherKind.cloud + [blank, blank]
        case .fog:
            return [blank, ".#########.", blank, "###.#####..", blank, "..#####.###", blank, ".#########.",
                    blank, "..###.###..", blank]
        case .rain:
            return [blank] + WeatherKind.cloud + [blank, "..o..o..o..", ".o..o..o..."]
        case .snow:
            return [blank] + WeatherKind.cloud + [blank, ".o...o...o.", "...o...o..."]
        case .thunder:
            return [blank] + WeatherKind.cloud + ["....oo.....", "...oo......", "..o........"]
        }
    }
}

struct Weather: Equatable {
    var temperature: Double
    var high: Double
    var low: Double
    var code: Int
    var isDay: Bool

    var kind: WeatherKind { WeatherKind(code: code, isDay: isDay) }

    /// 雨雪强度（影响动画里粒子的多少）：小 0.6 / 中 1 / 大 1.6
    var intensity: Double {
        switch code {
        case 51, 53, 56, 61, 71, 77, 80, 85: return 0.6
        case 65, 67, 75, 82, 86: return 1.6
        default: return 1
        }
    }

    static func text(_ celsius: Double, fahrenheit: Bool) -> String {
        let value = fahrenheit ? celsius * 9 / 5 + 32 : celsius
        return "\(Int(value.rounded()))°"
    }
}

/// 天气数据来自 Open-Meteo（免费、无需密钥、不读取任何本地文件）。
enum WeatherService {
    /// 城市 → 坐标：通过 Open-Meteo 地理编码联网查询，优先匹配同时区结果。
    static func coordinate(name: String, tzID: String, completion: @escaping ((Double, Double)?) -> Void) {
        let entry = CityDB.all.first { $0.name == name && $0.tzID == tzID }
        // 地名库里一般不带“市 / 县 / 区”后缀
        var query = entry?.english ?? name
        if entry == nil, query.count > 2, let last = query.last, "市县区".contains(last) { query.removeLast() }
        var components = URLComponents(string: "https://geocoding-api.open-meteo.com/v1/search")!
        components.queryItems = [
            URLQueryItem(name: "name", value: query),
            URLQueryItem(name: "count", value: "10"),
            URLQueryItem(name: "language", value: "zh"),
            URLQueryItem(name: "format", value: "json"),
        ]
        URLSession.shared.dataTask(with: components.url!) { data, _, _ in
            var found: (Double, Double)?
            if let data, let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let results = json["results"] as? [[String: Any]] {
                let best = results.first { $0["timezone"] as? String == tzID } ?? results.first
                if let latitude = (best?["latitude"] as? NSNumber)?.doubleValue,
                   let longitude = (best?["longitude"] as? NSNumber)?.doubleValue {
                    found = (latitude, longitude)
                }
            }
            // 查不到就不显示天气，而不是拿同时区的另一座城市顶替
            DispatchQueue.main.async { completion(found) }
        }.resume()
    }

    static func fetch(latitude: Double, longitude: Double, completion: @escaping (Weather?) -> Void) {
        var components = URLComponents(string: "https://api.open-meteo.com/v1/forecast")!
        components.queryItems = [
            URLQueryItem(name: "latitude", value: String(format: "%.3f", latitude)),
            URLQueryItem(name: "longitude", value: String(format: "%.3f", longitude)),
            URLQueryItem(name: "current", value: "temperature_2m,weather_code,is_day"),
            URLQueryItem(name: "daily", value: "temperature_2m_max,temperature_2m_min"),
            URLQueryItem(name: "timezone", value: "auto"),
            URLQueryItem(name: "forecast_days", value: "1"),
        ]
        URLSession.shared.dataTask(with: components.url!) { data, _, _ in
            var result: Weather?
            if let data,
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let current = json["current"] as? [String: Any],
               let temperature = (current["temperature_2m"] as? NSNumber)?.doubleValue,
               let code = (current["weather_code"] as? NSNumber)?.intValue {
                let daily = json["daily"] as? [String: Any]
                let high = ((daily?["temperature_2m_max"] as? [Any])?.first as? NSNumber)?.doubleValue
                let low = ((daily?["temperature_2m_min"] as? [Any])?.first as? NSNumber)?.doubleValue
                result = Weather(temperature: temperature, high: high ?? temperature, low: low ?? temperature,
                                 code: code, isDay: (current["is_day"] as? NSNumber)?.intValue != 0)
            }
            DispatchQueue.main.async { completion(result) }
        }.resume()
    }
}

struct WeatherIcon: View {
    let kind: WeatherKind
    let cell: CGFloat
    let theme: Theme
    var animated = false
    @Environment(\.look) private var look
    @Environment(\.zoom) private var zoom

    private static let timelineAnchor = Date()
    /// 晴天的第二帧：光芒转过半格，和第一帧交替形成转动感
    private static let sunAlternate = [
        "...#...#...", "...........", ".#.......#.", "....ooo....", "...ooooo...", "...ooooo...",
        "...ooooo...", "....ooo....", ".#.......#.", "...........", "...#...#...",
    ]

    var body: some View {
        if animated && kind == .clear {
            TimelineView(.periodic(from: WeatherIcon.timelineAnchor, by: 0.8)) { timeline in
                let second = Int(timeline.date.timeIntervalSinceReferenceDate / 0.8) % 2 == 1
                canvas(rows: second ? WeatherIcon.sunAlternate : kind.icon)
            }
        } else {
            canvas(rows: kind.icon)
        }
    }

    private func canvas(rows icon: [String]) -> some View {
        let cell = self.cell * zoom
        let rows = icon.map(Array.init)
        return Canvas { context, _ in
            let shape = look.digitStyle.dotShape
            let diameter = cell * 0.8
            let inset = (cell - diameter) / 2
            var main = Path(), accent = Path()
            for (r, row) in rows.enumerated() {
                for (c, mark) in row.enumerated() where mark != "." {
                    let rect = CGRect(x: CGFloat(c) * cell + inset, y: CGFloat(r) * cell + inset,
                                      width: diameter, height: diameter)
                    if mark == "o" {
                        shape == .square ? accent.addRect(rect) : accent.addEllipse(in: rect)
                    } else {
                        shape == .square ? main.addRect(rect) : main.addEllipse(in: rect)
                    }
                }
            }
            context.fill(main, with: .color(theme.label))
            context.fill(accent, with: .color(theme.accent))
        }
        .frame(width: 11 * cell, height: 11 * cell)
    }
}

/// 天气行：点阵图标 + 当前温度 + 天气和最高 / 最低温。
struct WeatherRow: View {
    let weather: Weather
    let fahrenheit: Bool
    let theme: Theme
    /// nil = 不显示动画；true / false = 动画暂停与否
    var animationPaused: Bool?
    @Environment(\.zoom) private var z

    var body: some View {
        HStack(alignment: .center, spacing: 14 * z) {
            WeatherIcon(kind: weather.kind, cell: 3, theme: theme, animated: animationPaused == false)
            Digits(text: Weather.text(weather.temperature, fahrenheit: fahrenheit), cell: 3.6, color: theme.digits)
            Spacer(minLength: 8 * z)
            VStack(alignment: .trailing, spacing: 5 * z) {
                LabelText(text: weather.kind.title, size: 15, color: theme.label)
                LabelText(text: Weather.text(weather.high, fahrenheit: fahrenheit) + " / "
                    + Weather.text(weather.low, fahrenheit: fahrenheit), size: 11.5, color: theme.sub)
            }
        }
        .padding(.vertical, 11 * z)
        .background {
            if let animationPaused {
                // 动画铺满整行，与上下分隔线同宽
                WeatherAnimation(kind: weather.kind, intensity: weather.intensity, theme: theme, paused: animationPaused)
                    .clipped()
            }
        }
    }
}
