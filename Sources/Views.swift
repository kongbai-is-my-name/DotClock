import AppKit
import SwiftUI

enum ClockFormat {
    static let widgetWidth: CGFloat = 344

    static func parts(_ date: Date, zone: TimeZone, use24h: Bool) -> (hm: String, ss: String, period: String?, second: Int, hour: Int, minute: Int) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let c = calendar.dateComponents([.hour, .minute, .second], from: date)
        let hour = c.hour ?? 0, minute = c.minute ?? 0, second = c.second ?? 0
        let shown = use24h ? hour : (hour % 12 == 0 ? 12 : hour % 12)
        return (String(format: "%02d:%02d", shown, minute), String(format: "%02d", second),
                use24h ? nil : (hour < 12 ? "AM" : "PM"), second, hour, minute)
    }

    static func dateLine(_ date: Date, zone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = zone
        formatter.dateFormat = "M月d日 EEE"
        return formatter.string(from: date)
    }

    static func localCityName() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.dateFormat = "VVV"
        return formatter.string(from: Date())
    }

    /// 主城市的今天已经过去的比例（按日历算，夏令时切换日也准确）。
    static func dayFraction(_ date: Date, zone: TimeZone) -> Double {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let start = calendar.startOfDay(for: date)
        let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86400)
        return date.timeIntervalSince(start) / end.timeIntervalSince(start)
    }

    /// 相对主城市的日期和时差，例如 “明天 +13H”
    static func offsetLine(_ date: Date, zone: TimeZone, base: TimeZone) -> String {
        let diff = zone.secondsFromGMT(for: date) - base.secondsFromGMT(for: date)
        func dayNumber(_ tz: TimeZone) -> Int {
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = tz
            let c = calendar.dateComponents([.year, .month, .day], from: date)
            var utc = Calendar(identifier: .gregorian)
            utc.timeZone = TimeZone(identifier: "UTC")!
            let day = utc.date(from: c) ?? date
            return Int(day.timeIntervalSince1970 / 86400)
        }
        let dayDiff = dayNumber(zone) - dayNumber(base)
        let day = dayDiff > 0 ? "明天" : (dayDiff < 0 ? "昨天" : "今天")
        let hours = diff == 0 ? "+0H" : String(format: "%+gH", Double(diff) / 3600)
        return day + " " + hours
    }
}

enum ClockStyle: String, CaseIterable {
    case classic, inline, stacked, dial

    var title: String {
        switch self {
        case .classic: return "经典（大时分 + 小秒）"
        case .inline: return "单行（时:分:秒）"
        case .stacked: return "堆叠（时 / 分）"
        case .dial: return "表盘（点阵指针）"
        }
    }
}

typealias TimeParts = (hm: String, ss: String, period: String?, second: Int, hour: Int, minute: Int)

/// 主时间区域的几种样式。
struct TimeFace: View {
    let style: ClockStyle
    let time: TimeParts
    let theme: Theme
    @Environment(\.look) private var look
    @Environment(\.zoom) private var z

    var body: some View {
        // 真实字体的字宽比点阵宽，适当缩小避免溢出
        let isFont = look.digitStyle.isFont
        let classic: CGFloat = isFont ? 0.9 : 1
        let inline: CGFloat = isFont ? 0.78 : 1
        switch style {
        case .classic:
            HStack(alignment: .bottom, spacing: 12 * z) {
                Digits(text: time.hm, cell: 8 * classic, color: theme.digits)
                VStack(alignment: .leading, spacing: 7 * z) {
                    if let period = time.period {
                        LabelText(text: period, size: 14, color: theme.sub, dotCell: 2)
                    }
                    Digits(text: time.ss, cell: 4.5 * classic, color: theme.accent)
                }
            }
        case .inline:
            VStack(alignment: .leading, spacing: 8 * z) {
                if let period = time.period {
                    LabelText(text: period, size: 14, color: theme.sub, dotCell: 2)
                }
                HStack(spacing: isFont ? 0 : 7 * z) {
                    Digits(text: time.hm + ":", cell: 7 * inline, color: theme.digits)
                    Digits(text: time.ss, cell: 7 * inline, color: theme.accent)
                }
            }
        case .stacked:
            HStack(alignment: .bottom, spacing: 0) {
                VStack(alignment: .leading, spacing: 12 * z) {
                    Digits(text: String(time.hm.prefix(2)), cell: 11, color: theme.digits)
                    Digits(text: String(time.hm.suffix(2)), cell: 11, color: theme.digits.opacity(0.7))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 9 * z) {
                    if let period = time.period {
                        LabelText(text: period, size: 17, color: theme.sub, dotCell: 2.5)
                    }
                    Digits(text: time.ss, cell: 7, color: theme.accent)
                }
            }
        case .dial:
            HStack(alignment: .center, spacing: 0) {
                DialFace(time: time, theme: theme).frame(width: 156 * z, height: 156 * z)
                Spacer()
                VStack(alignment: .trailing, spacing: 9 * z) {
                    if let period = time.period {
                        LabelText(text: period, size: 14, color: theme.sub, dotCell: 2)
                    }
                    Digits(text: time.hm, cell: 4, color: theme.digits)
                    Digits(text: time.ss, cell: 4, color: theme.accent)
                }
            }
        }
    }
}

/// 点阵表盘：外圈 60 个秒点，时针分针由一串圆点组成。
struct DialFace: View {
    let time: TimeParts
    let theme: Theme
    @Environment(\.zoom) private var z

    var body: some View {
        Canvas { context, size in
            let center = CGPoint(x: size.width / 2, y: size.height / 2)
            let radius = min(size.width, size.height) / 2 - 3 * z
            func point(_ fraction: Double, _ r: CGFloat) -> CGPoint {
                let angle = fraction * 2 * .pi - .pi / 2
                return CGPoint(x: center.x + CGFloat(cos(angle)) * r, y: center.y + CGFloat(sin(angle)) * r)
            }
            func dot(_ p: CGPoint, _ diameter: CGFloat, _ color: Color) {
                context.fill(Path(ellipseIn: CGRect(x: p.x - diameter / 2, y: p.y - diameter / 2,
                                                    width: diameter, height: diameter)), with: .color(color))
            }
            for i in 0..<60 {
                let color = i == time.second ? theme.accent : (i < time.second ? theme.lit : theme.dim)
                dot(point(Double(i) / 60, radius), (i % 5 == 0 ? 5 : 3) * z, color)
            }
            func hand(_ fraction: Double, length: CGFloat, diameter: CGFloat, color: Color) {
                var r: CGFloat = diameter * 1.6
                while r <= length {
                    dot(point(fraction, r), diameter, color)
                    r += diameter * 1.35
                }
            }
            let minuteFraction = (Double(time.minute) + Double(time.second) / 60) / 60
            let hourFraction = (Double(time.hour % 12) + Double(time.minute) / 60) / 12
            hand(minuteFraction, length: radius * 0.8, diameter: 5 * z, color: theme.lit)
            hand(hourFraction, length: radius * 0.52, diameter: 7 * z, color: theme.digits)
            dot(center, 7 * z, theme.accent)
        }
    }
}

struct ClockCard: View {
    let date: Date
    var mainName = ClockFormat.localCityName()
    var mainZone = TimeZone.current
    let cities: [City]
    let use24h: Bool
    let theme: Theme
    var style: ClockStyle = .classic
    var showDayProgress = true
    var weather: Weather?
    var fahrenheit = false
    /// 只拉宽 / 只拉长时多出来的空间：宽度倍数和额外高度（缩放前的点数）
    var widthFactor: CGFloat = 1
    var extraHeight: CGFloat = 0
    /// nil = 关闭天气动画
    var weatherAnimationPaused: Bool?
    var glass = false
    /// 玻璃背景的模糊强度 0…1
    var glassBlur: Double = 1
    @Environment(\.zoom) private var z

    var body: some View {
        let main = ClockFormat.parts(date, zone: mainZone, use24h: use24h)
        let hasRows = weather != nil || !cities.isEmpty
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 6 * z) {
                Circle().fill(theme.accent).frame(width: 6 * z, height: 6 * z)
                LabelText(text: mainName, size: 13, color: theme.label)
                Spacer()
                LabelText(text: ClockFormat.dateLine(date, zone: mainZone), size: 13, color: theme.label)
            }
            .padding(.bottom, 16 * z)

            // 多出来的宽度让时间居中，多出来的高度平均留在时间上下
            TimeFace(style: style, time: main, theme: theme)
                .frame(maxWidth: .infinity, alignment: widthFactor > 1.02 ? .center : .leading)
                .padding(.vertical, extraHeight * z / 2)
                .padding(.bottom, 16 * z)

            if style != .dial {
                SecondsStrip(second: main.second, theme: theme)
            }

            if showDayProgress {
                let fraction = ClockFormat.dayFraction(date, zone: mainZone)
                HStack(spacing: 10 * z) {
                    LabelText(text: "今日", size: 11.5, color: theme.sub)
                    DayProgressBar(fraction: fraction, theme: theme)
                    LabelText(text: String(format: "%.1f%%", (fraction * 1000).rounded(.down) / 10),
                              size: 12.5, color: theme.label, dotCell: 1.8)
                }
                .padding(.top, style == .dial ? 0 : 14 * z)
            }

            if hasRows {
                VStack(spacing: 0) {
                    if let weather {
                        Rectangle().fill(theme.border).frame(height: z)
                        WeatherRow(weather: weather, fahrenheit: fahrenheit, theme: theme,
                                   animationPaused: weatherAnimationPaused)
                    }
                    ForEach(cities) { city in
                        Rectangle().fill(theme.border).frame(height: z)
                        CityRow(date: date, city: city, base: mainZone, use24h: use24h, theme: theme)
                    }
                }
                .padding(.top, 14 * z)
            }
        }
        .padding(.horizontal, 20 * z)
        .padding(.top, 18 * z)
        .padding(.bottom, (hasRows ? 6 : 18) * z)
        .frame(width: ClockFormat.widgetWidth * z * widthFactor, alignment: .leading)
        .background {
            if glass {
                GlassBackground(tint: theme.surface, blur: glassBlur, cornerRadius: 26 * z)
            } else {
                theme.surface
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 26 * z, style: .continuous))
        .overlay {
            if glass {
                GlassRim(cornerRadius: 26 * z)
            } else {
                RoundedRectangle(cornerRadius: 26 * z, style: .continuous).strokeBorder(theme.border, lineWidth: 1)
            }
        }
    }
}

private struct SnapshotKey: EnvironmentKey {
    static let defaultValue = false
}

extension EnvironmentValues {
    /// 离屏渲染预览图时为 true：ImageRenderer 画不出 NSVisualEffectView，玻璃改用近似效果。
    var isSnapshot: Bool {
        get { self[SnapshotKey.self] }
        set { self[SnapshotKey.self] = newValue }
    }
}

/// 实时模糊窗口后面的桌面。
struct BackdropBlur: NSViewRepresentable {
    let cornerRadius: CGFloat
    /// 模糊层的不透明度，越低越能看清背后的桌面
    var amount: Double = 1

    func makeNSView(context: Context) -> NSVisualEffectView {
        let view = NSVisualEffectView()
        view.blendingMode = .behindWindow
        view.material = .hudWindow
        view.state = .active
        view.wantsLayer = true
        view.layer?.masksToBounds = true
        view.layer?.cornerCurve = .continuous
        return view
    }

    func updateNSView(_ view: NSVisualEffectView, context: Context) {
        view.layer?.cornerRadius = cornerRadius
        view.alphaValue = amount
    }
}

/// 玻璃底：模糊的桌面 + 半透明主题色 + 顶部柔光，模拟 Liquid Glass 的厚玻璃折射感。
struct GlassBackground: View {
    let tint: Color
    var blur: Double = 1
    let cornerRadius: CGFloat
    @Environment(\.colorScheme) private var scheme
    @Environment(\.isSnapshot) private var isSnapshot

    var body: some View {
        let dark = scheme == .dark
        ZStack {
            if isSnapshot {
                (dark ? Color.black : Color.white).opacity(0.18 * blur)
            } else {
                BackdropBlur(cornerRadius: cornerRadius, amount: blur)
            }
            tint
            // 顶部的漫射高光，越往下越淡
            LinearGradient(colors: [.white.opacity(dark ? 0.10 : 0.30), .white.opacity(0)],
                           startPoint: .top, endPoint: UnitPoint(x: 0.5, y: 0.45))
            // 底边的一点回光，让玻璃显得有厚度
            LinearGradient(colors: [.white.opacity(0), .white.opacity(dark ? 0.05 : 0.12)],
                           startPoint: UnitPoint(x: 0.5, y: 0.7), endPoint: .bottom)
        }
    }
}

/// 玻璃边缘的镜面高光：左上和右下亮，两侧暗，外加一圈很细的内阴影线。
struct GlassRim: View {
    let cornerRadius: CGFloat
    @Environment(\.colorScheme) private var scheme
    @Environment(\.zoom) private var z

    var body: some View {
        let dark = scheme == .dark
        let shape = RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        ZStack {
            shape.strokeBorder(
                LinearGradient(stops: [
                    .init(color: .white.opacity(dark ? 0.55 : 0.95), location: 0),
                    .init(color: .white.opacity(dark ? 0.08 : 0.25), location: 0.35),
                    .init(color: .white.opacity(dark ? 0.04 : 0.15), location: 0.65),
                    .init(color: .white.opacity(dark ? 0.30 : 0.60), location: 1),
                ], startPoint: .topLeading, endPoint: .bottomTrailing),
                lineWidth: 1.2 * max(1, z * 0.8))
            shape.inset(by: 1.5 * max(1, z * 0.8))
                .strokeBorder(Color.black.opacity(dark ? 0.25 : 0.06), lineWidth: 0.5)
        }
        .allowsHitTesting(false)
    }
}

struct CityRow: View {
    let date: Date
    let city: City
    let base: TimeZone
    let use24h: Bool
    let theme: Theme
    @Environment(\.zoom) private var z

    var body: some View {
        let zone = TimeZone(identifier: city.tzID) ?? .current
        let time = ClockFormat.parts(date, zone: zone, use24h: use24h)
        HStack(alignment: .center, spacing: 0) {
            VStack(alignment: .leading, spacing: 5 * z) {
                LabelText(text: String(city.name.prefix(12)), size: 15, color: theme.label)
                LabelText(text: ClockFormat.offsetLine(date, zone: zone, base: base), size: 11.5, color: theme.sub)
            }
            Spacer(minLength: 8 * z)
            HStack(alignment: .bottom, spacing: 2.6 * z) {
                Digits(text: time.hm + ":", cell: 2.6, color: theme.digits)
                Digits(text: time.ss, cell: 2.6, color: theme.accent)
                if let period = time.period {
                    LabelText(text: period, size: 11, color: theme.sub, dotCell: 1.6).padding(.leading, 3 * z)
                }
            }
        }
        .padding(.vertical, 11 * z)
    }
}

/// 桌面小组件根视图：每秒刷新，使用网络校准后的标准时间。
struct WidgetRoot: View {
    @ObservedObject var store: Store
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        TimelineView(.periodic(from: Date(timeIntervalSince1970: Date().timeIntervalSince1970.rounded(.down)), by: 1)) { context in
            ClockCard(date: store.calibratedDate(context.date), mainName: store.mainName, mainZone: store.mainZone,
                      cities: store.cities, use24h: store.use24h, theme: store.theme(dark: scheme == .dark),
                      style: store.style, showDayProgress: store.showDayProgress,
                      weather: store.showWeather ? store.weather : nil, fahrenheit: store.fahrenheit,
                      widthFactor: store.widthFactor, extraHeight: store.extraHeight,
                      weatherAnimationPaused: store.animateWeather ? !store.isVisible : nil, glass: store.glass, glassBlur: store.glassBlur)
                .environment(\.look, store.look)
                .environment(\.zoom, store.zoom)
        }
    }
}

struct AddCityView: View {
    @ObservedObject var store: Store
    /// true：选择主显示城市（选中即关闭）；false：添加 / 移除世界时钟城市
    var pickMain = false
    var onDone: () -> Void = {}
    @State private var query = ""
    @State private var online: [CityDB.Entry] = []
    @State private var status = ""
    @FocusState private var focused: Bool

    var body: some View {
        let results = online + CityDB.search(query).filter { !online.contains($0) }
        VStack(spacing: 0) {
            TextField("搜索城市，例如 东京 / London", text: $query)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .onSubmit(searchOnline)
                .onChange(of: query) { _ in
                    online = []
                    status = ""
                }
                .padding(12)
            List(results) { entry in
                Button {
                    toggle(entry)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.name)
                            Text(entry.english.isEmpty ? entry.tzID : "\(entry.english) · \(entry.tzID)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if pickMain ? (!store.autoLocation && store.mainCity == entry.city) : store.cities.contains(entry.city) {
                            Image(systemName: "checkmark").foregroundStyle(.tint)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            HStack {
                Text(status.isEmpty ? (pickMain ? "点击设为主显示城市 · 没找到？按回车联网搜索" : "点击添加或移除 · 没找到？按回车联网搜索") : status)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
        }
        .frame(width: 340, height: 440)
        .onAppear { focused = true }
    }

    private func toggle(_ entry: CityDB.Entry) {
        if pickMain {
            store.selectManualMainCity(entry.city)
            onDone()
        } else if store.cities.contains(entry.city) {
            store.remove(entry.city)
        } else {
            store.add(entry.city)
        }
    }

    private func searchOnline() {
        let text = query.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        status = "正在联网搜索…"
        CityDB.lookupOnline(text) { entries in
            guard text == query.trimmingCharacters(in: .whitespaces) else { return }
            online = entries
            status = entries.isEmpty ? "没有找到“\(text)”" : ""
        }
    }
}
