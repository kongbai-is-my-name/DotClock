import AppKit
import Combine
import ServiceManagement
import SwiftUI

/// 鼠标落在小组件的哪些边缘上。
struct Edges: OptionSet {
    let rawValue: Int
    static let left = Edges(rawValue: 1), right = Edges(rawValue: 2)
    static let top = Edges(rawValue: 4), bottom = Edges(rawValue: 8)
}

/// 承载小组件的视图：中间可拖动移动，右键弹出菜单。
/// 拖左右边缘只改宽度，拖上下边缘只改高度，拖四角等比缩放。
final class WidgetHostingView<Content: View>: NSHostingView<Content> {
    var contextMenu: NSMenu?
    var beginResize: () -> Void = {}
    /// (目标尺寸, 固定右边缘, 固定下边缘)
    var resize: (NSSize, Bool, Bool) -> Void = { _, _, _ in }

    private var resizing: Edges = []
    private var startMouse = NSPoint.zero
    private var startSize = NSSize.zero
    private var tracking: NSTrackingArea?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    private func edges(at point: NSPoint) -> Edges {
        guard let size = window?.frame.size else { return [] }
        let margin = max(10, min(16, size.width * 0.04))
        var result: Edges = []
        if point.x < margin { result.insert(.left) } else if point.x > size.width - margin { result.insert(.right) }
        if point.y < margin { result.insert(.bottom) } else if point.y > size.height - margin { result.insert(.top) }
        return result
    }

    private func cursor(for edges: Edges) -> NSCursor {
        let horizontal = !edges.isDisjoint(with: [.left, .right]), vertical = !edges.isDisjoint(with: [.top, .bottom])
        if horizontal && vertical {
            if #available(macOS 15.0, *) {
                let position: NSCursor.FrameResizePosition = edges.contains(.top)
                    ? (edges.contains(.left) ? .topLeft : .topRight)
                    : (edges.contains(.left) ? .bottomLeft : .bottomRight)
                return NSCursor.frameResize(position: position, directions: .all)
            }
            return .crosshair
        }
        if horizontal { return .resizeLeftRight }
        if vertical { return .resizeUpDown }
        return .arrow
    }

    override func updateTrackingAreas() {
        super.updateTrackingAreas()
        if let tracking { removeTrackingArea(tracking) }
        let area = NSTrackingArea(rect: bounds, options: [.mouseMoved, .mouseEnteredAndExited, .activeAlways, .inVisibleRect],
                                  owner: self, userInfo: nil)
        addTrackingArea(area)
        tracking = area
    }

    override func mouseMoved(with event: NSEvent) {
        cursor(for: edges(at: event.locationInWindow)).set()
    }

    override func mouseExited(with event: NSEvent) {
        if resizing.isEmpty { NSCursor.arrow.set() }
    }

    override func mouseDown(with event: NSEvent) {
        let hit = edges(at: event.locationInWindow)
        guard !hit.isEmpty, let window else {
            self.window?.performDrag(with: event)
            return
        }
        resizing = hit
        startMouse = NSEvent.mouseLocation
        startSize = window.frame.size
        beginResize()
    }

    override func mouseDragged(with event: NSEvent) {
        guard !resizing.isEmpty else { return }
        let mouse = NSEvent.mouseLocation
        let dx = mouse.x - startMouse.x, dy = mouse.y - startMouse.y
        let horizontal = !resizing.isDisjoint(with: [.left, .right])
        let vertical = !resizing.isDisjoint(with: [.top, .bottom])
        var target = startSize
        if horizontal { target.width += resizing.contains(.right) ? dx : -dx }
        if vertical { target.height += resizing.contains(.top) ? dy : -dy }
        if horizontal && vertical {
            // 四角：等比缩放，以宽度变化为准
            target.height = startSize.height * target.width / startSize.width
        }
        cursor(for: resizing).set()
        resize(target, resizing.contains(.left), resizing.contains(.top))
    }

    override func mouseUp(with event: NSEvent) {
        resizing = []
    }

    override func rightMouseDown(with event: NSEvent) {
        if let contextMenu { NSMenu.popUpContextMenu(contextMenu, with: event, for: self) }
    }
}

/// 菜单项：显示标题、可选勾选、点击执行闭包。
final class ActionItem: NSMenuItem {
    private var handler: (() -> Void)?  // 点击时执行的闭包

    convenience init(_ title: String, checked: Bool = false, _ handler: @escaping () -> Void) {  // 初始化
        self.init(title: title, action: #selector(fire), keyEquivalent: "")
        self.target = self
        self.handler = handler
        self.state = checked ? .on : .off
    }

    @objc private func fire() { handler?() }
}

/// 菜单里的滑块：上面一行标题和百分比，下面是滑块，拖动时实时生效。
final class SliderItem: NSMenuItem {
    private var handler: ((Double) -> Void)?
    private var titleText = ""
    private let valueLabel = NSTextField(labelWithString: "")

    convenience init(_ title: String, value: Double, _ handler: @escaping (Double) -> Void) {
        self.init(title: title, action: nil, keyEquivalent: "")
        self.handler = handler
        titleText = title

        let container = NSView(frame: NSRect(x: 0, y: 0, width: 250, height: 46))
        valueLabel.font = .menuFont(ofSize: 0)
        valueLabel.textColor = .secondaryLabelColor
        valueLabel.frame = NSRect(x: 20, y: 26, width: 216, height: 16)
        container.addSubview(valueLabel)

        let slider = NSSlider(value: value, minValue: 0, maxValue: 1, target: self, action: #selector(changed(_:)))
        slider.isContinuous = true
        slider.controlSize = .small
        slider.frame = NSRect(x: 18, y: 4, width: 214, height: 20)
        container.addSubview(slider)
        view = container
        showValue(value)
    }

    private func showValue(_ value: Double) {
        valueLabel.stringValue = "\(titleText)  \(Int((value * 100).rounded()))%"
    }

    @objc private func changed(_ sender: NSSlider) {
        showValue(sender.doubleValue)
        handler?(sender.doubleValue)
    }
}

/// 自定义颜色面板当前在改哪一项。
enum ColorTarget {
    case seed, digits, glassTint
}

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate, NSWindowDelegate {
    private let store = Store()
    private var window: NSWindow!
    private var hosting: WidgetHostingView<WidgetRoot>!
    private var addPanel: NSPanel?
    private var subscriptions = Set<AnyCancellable>()
    private let menu = NSMenu()
    /// 缩放时保持不动的边：拖左边缘时固定右边，拖上边缘时固定下边；其余情况固定左上角
    private var anchorRight = false
    private var anchorBottom = false
    private var baseHeight: CGFloat = 0
    private var colorTarget = ColorTarget.digits

    func applicationDidFinishLaunching(_ notification: Notification) {
        hosting = WidgetHostingView(rootView: WidgetRoot(store: store))
        menu.delegate = self
        hosting.contextMenu = menu
        hosting.beginResize = { [weak self] in
            guard let self else { return }
            // 内容在 1 倍、无额外留白时的自然高度
            self.baseHeight = self.window.frame.height / self.store.zoom - self.store.extraHeight
        }
        hosting.resize = { [weak self] target, fixRight, fixBottom in
            guard let self, self.baseHeight > 0 else { return }
            let range = Store.zoomRange
            let width = max(target.width, ClockFormat.widgetWidth * range.lowerBound)
            let height = max(target.height, self.baseHeight * range.lowerBound)
            // 内容倍数取“宽、高都放得下”的最大值；剩余空间记为额外宽度 / 额外高度
            let zoom = min(range.upperBound, width / ClockFormat.widgetWidth, height / self.baseHeight)
            self.anchorRight = fixRight
            self.anchorBottom = fixBottom
            self.store.zoom = zoom
            self.store.widthFactor = max(1, width / (ClockFormat.widgetWidth * zoom))
            self.store.extraHeight = max(0, height / zoom - self.baseHeight)
        }

        window = NSWindow(contentRect: .zero, styleMask: [.borderless], backing: .buffered, defer: false)
        window.isOpaque = false
        window.backgroundColor = .clear
        window.hasShadow = false
        // 位于桌面图标层之上、所有普通窗口之下，像系统小组件一样贴在桌面
        window.level = NSWindow.Level(rawValue: Int(CGWindowLevelForKey(.desktopIconWindow)) + 1)
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .ignoresCycle]
        window.isReleasedWhenClosed = false
        window.contentView = hosting
        window.delegate = self
        applyAppearance()

        let size = hosting.fittingSize
        let visible = (NSScreen.main ?? NSScreen.screens[0]).visibleFrame
        let defaults = UserDefaults.standard
        var left = visible.maxX - size.width - 24
        var top = visible.maxY - 24
        if defaults.object(forKey: "left") != nil {
            left = defaults.double(forKey: "left")
            top = defaults.double(forKey: "top")
        }
        var frame = NSRect(x: left, y: top - size.height, width: size.width, height: size.height)
        if !NSScreen.screens.contains(where: { $0.frame.intersects(frame) }) {
            frame.origin = NSPoint(x: visible.maxX - size.width - 24, y: visible.maxY - 24 - size.height)
        }
        window.setFrame(frame, display: true)
        window.orderFront(nil)

        store.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.applyAppearance()
                self?.fitWindow()
            }
            .store(in: &subscriptions)
        store.startNetworkServices()
    }

    /// 城市增减后高度变化：保持左上角不动。
    private func fitWindow() {
        hosting.layoutSubtreeIfNeeded()
        let size = hosting.fittingSize
        var frame = window.frame
        guard frame.size != size else { return }
        let top = frame.maxY, right = frame.maxX
        frame.size = size
        if anchorRight { frame.origin.x = right - size.width }
        if !anchorBottom { frame.origin.y = top - size.height }
        window.setFrame(frame, display: true)
    }

    private func applyAppearance() {
        // 玻璃风格下用系统窗口阴影把卡片从桌面上“托”起来
        if window.hasShadow != store.glass {
            window.hasShadow = store.glass
            window.invalidateShadow()
        }
        switch store.appearance {
        case "dark": window.appearance = NSAppearance(named: .darkAqua)
        case "light": window.appearance = NSAppearance(named: .aqua)
        default: window.appearance = nil
        }
    }

    func windowDidChangeOcclusionState(_ notification: Notification) {
        let visible = window.occlusionState.contains(.visible)
        if store.isVisible != visible { store.isVisible = visible }
    }

    func windowDidMove(_ notification: Notification) {
        UserDefaults.standard.set(window.frame.minX, forKey: "left")
        UserDefaults.standard.set(window.frame.maxY, forKey: "top")
    }

    // MARK: 菜单

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        menu.addItem(ActionItem("添加城市…") { [weak self] in self?.showCityPicker(pickMain: false) })
        if !store.cities.isEmpty {
            let remove = NSMenuItem(title: "移除城市", action: nil, keyEquivalent: "")
            remove.submenu = NSMenu()
            for city in store.cities {
                remove.submenu?.addItem(ActionItem(city.name) { [weak self] in self?.store.remove(city) })
            }
            menu.addItem(remove)
        }
        let main = NSMenuItem(title: "主显示城市", action: nil, keyEquivalent: "")
        main.submenu = NSMenu()
        let autoCityLabel = store.networkCity.map { "自动网络获取（\($0.name)）" } ?? "自动网络获取（正在定位…）"
        main.submenu?.addItem(ActionItem(autoCityLabel, checked: store.autoLocation) { [weak self] in
            self?.store.autoLocation = true
        })
        main.submenu?.addItem(ActionItem("跟随系统时区（\(ClockFormat.localCityName())）", checked: !store.autoLocation && store.mainCity == nil) { [weak self] in
            self?.store.selectManualMainCity(nil)
        })
        var choices = store.cities
        if let current = store.mainCity, !choices.contains(current) { choices.insert(current, at: 0) }
        if !choices.isEmpty { main.submenu?.addItem(.separator()) }
        for city in choices {
            main.submenu?.addItem(ActionItem(city.name, checked: !store.autoLocation && store.mainCity == city) { [weak self] in
                self?.store.selectManualMainCity(city)
            })
        }
        main.submenu?.addItem(.separator())
        main.submenu?.addItem(ActionItem("选择其他城市…") { [weak self] in self?.showCityPicker(pickMain: true) })
        menu.addItem(main)
        menu.addItem(ActionItem("立即同步网络时间与天气") { [weak self] in self?.store.syncAllFromNetwork() })
        menu.addItem(.separator())

        let style = NSMenuItem(title: "时钟样式", action: nil, keyEquivalent: "")
        style.submenu = NSMenu()
        for option in ClockStyle.allCases {
            style.submenu?.addItem(ActionItem(option.title, checked: store.style == option) { [weak self] in
                self?.store.style = option
            })
        }
        menu.addItem(style)

        let size = NSMenuItem(title: "大小", action: nil, keyEquivalent: "")
        size.submenu = NSMenu()
        for percent in [60, 80, 100, 125, 150, 200] {
            let value = CGFloat(percent) / 100
            size.submenu?.addItem(ActionItem("\(percent)%", checked: abs(store.zoom - value) < 0.005) { [weak self] in
                self?.anchorRight = false
                self?.anchorBottom = false
                self?.store.zoom = value
            })
        }
        size.submenu?.addItem(.separator())
        let stretched = store.widthFactor > 1.001 || store.extraHeight > 0.5
        let reset = ActionItem("恢复默认比例") { [weak self] in
            self?.anchorRight = false
            self?.anchorBottom = false
            self?.store.widthFactor = 1
            self?.store.extraHeight = 0
        }
        reset.isEnabled = stretched
        size.submenu?.addItem(reset)
        size.submenu?.addItem(.separator())
        let hint = NSMenuItem(title: "当前 \(Int((store.zoom * 100).rounded()))% · 拖边缘改宽或高，拖四角等比缩放", action: nil, keyEquivalent: "")
        hint.isEnabled = false
        size.submenu?.addItem(hint)
        menu.addItem(size)

        let digits = NSMenuItem(title: "数字样式", action: nil, keyEquivalent: "")
        digits.submenu = NSMenu()
        for option in DigitStyle.allCases where option.isAvailable {
            if option == .rounded { digits.submenu?.addItem(.separator()) }
            digits.submenu?.addItem(ActionItem(option.title, checked: store.digitStyle == option) { [weak self] in
                self?.store.digitStyle = option
            })
        }
        menu.addItem(digits)

        let digitColor = NSMenuItem(title: "数字颜色", action: nil, keyEquivalent: "")
        digitColor.submenu = NSMenu()
        let presets = [("跟随主题（主色）", "primary"), ("跟随主题（强调色）", "accent"), ("纯黑白", "mono"),
                       ("Nothing 红", "d71921"), ("琥珀 LED", "ffb000"), ("荧光绿", "4ade80"),
                       ("冰蓝", "7dd3fc"), ("樱粉", "ff8fb1")]
        for (index, preset) in presets.enumerated() {
            if index == 3 { digitColor.submenu?.addItem(.separator()) }
            digitColor.submenu?.addItem(ActionItem(preset.0, checked: store.digitColor == preset.1) { [weak self] in
                self?.store.digitColor = preset.1
            })
        }
        digitColor.submenu?.addItem(.separator())
        let isCustom = !presets.contains { $0.1 == store.digitColor }
        digitColor.submenu?.addItem(ActionItem("自定义颜色…", checked: isCustom) { [weak self] in self?.showColorPanel(for: .digits) })
        menu.addItem(digitColor)

        let textFont = NSMenuItem(title: "文字字体", action: nil, keyEquivalent: "")
        textFont.submenu = NSMenu()
        for (index, option) in TextFont.options.enumerated() {
            if index == 3 { textFont.submenu?.addItem(.separator()) }
            textFont.submenu?.addItem(ActionItem(option.title, checked: store.textFont == option.id) { [weak self] in
                self?.store.textFont = option.id
            })
        }
        menu.addItem(textFont)
        menu.addItem(.separator())

        let color = NSMenuItem(title: "主题配色", action: nil, keyEquivalent: "")
        color.submenu = NSMenu()
        let seedHexes = SeedPreset.all.map { String(format: "%06x", $0.hex) }
        for (preset, hex) in zip(SeedPreset.all, seedHexes) {
            color.submenu?.addItem(ActionItem(preset.name, checked: store.seedMode.lowercased() == hex) { [weak self] in
                self?.store.seedMode = hex
            })
        }
        color.submenu?.addItem(.separator())
        let isCustomSeed = !seedHexes.contains(store.seedMode.lowercased())
        color.submenu?.addItem(ActionItem("自定义主题色…", checked: isCustomSeed) { [weak self] in self?.showColorPanel(for: .seed) })
        menu.addItem(color)

        let appearance = NSMenuItem(title: "外观", action: nil, keyEquivalent: "")
        appearance.submenu = NSMenu()
        for (title, value) in [("跟随系统", "auto"), ("深色", "dark"), ("浅色", "light")] {
            appearance.submenu?.addItem(ActionItem(title, checked: store.appearance == value) { [weak self] in
                self?.store.appearance = value
            })
        }
        menu.addItem(appearance)
        menu.addItem(glassMenu())

        menu.addItem(.separator())
        let netTimeTitle = store.useNetworkTime
            ? (store.timeSynced ? "网络自动校时（已校准）" : "网络自动校时（同步中…）")
            : "网络自动校时"
        menu.addItem(ActionItem(netTimeTitle, checked: store.useNetworkTime) { [weak self] in self?.store.useNetworkTime.toggle() })
        menu.addItem(ActionItem("显示天气", checked: store.showWeather) { [weak self] in self?.store.showWeather.toggle() })
        menu.addItem(ActionItem("天气动画", checked: store.animateWeather) { [weak self] in self?.store.animateWeather.toggle() })
        menu.addItem(ActionItem("华氏度 °F", checked: store.fahrenheit) { [weak self] in self?.store.fahrenheit.toggle() })
        menu.addItem(ActionItem("显示今日进度", checked: store.showDayProgress) { [weak self] in
            self?.store.showDayProgress.toggle()
        })
        menu.addItem(ActionItem("24 小时制", checked: store.use24h) { [weak self] in self?.store.use24h.toggle() })
        let loginEnabled = SMAppService.mainApp.status == .enabled
        menu.addItem(ActionItem("登录时启动", checked: loginEnabled) {
            do {
                if loginEnabled { try SMAppService.mainApp.unregister() } else { try SMAppService.mainApp.register() }
            } catch {
                let alert = NSAlert()
                alert.messageText = "无法更改登录项"
                alert.informativeText = error.localizedDescription
                NSApp.activate(ignoringOtherApps: true)
                alert.runModal()
            }
        })
        menu.addItem(.separator())
        menu.addItem(ActionItem("退出点阵时钟") { NSApp.terminate(nil) })
    }

    private func showCityPicker(pickMain: Bool) {
        if addPanel == nil {
            let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 340, height: 440),
                                styleMask: [.titled, .closable], backing: .buffered, defer: false)
            panel.isReleasedWhenClosed = false
            panel.level = .floating
            panel.center()
            addPanel = panel
        }
        addPanel?.title = pickMain ? "选择主显示城市" : "添加城市"
        addPanel?.contentView = NSHostingView(rootView: AddCityView(store: store, pickMain: pickMain) { [weak self] in
            self?.addPanel?.close()
        })
        NSApp.activate(ignoringOtherApps: true)
        addPanel?.makeKeyAndOrderFront(nil)
    }

    /// 透明玻璃子菜单：开关、底色浓度 / 模糊强度滑块、底色选择（含屏幕取色）。
    private func glassMenu() -> NSMenuItem {
        let item = NSMenuItem(title: "透明玻璃", action: nil, keyEquivalent: "")
        let sub = NSMenu()
        sub.addItem(ActionItem("开启透明玻璃风格", checked: store.glass) { [weak self] in self?.store.glass.toggle() })
        sub.addItem(.separator())
        // 调整任一项都顺带打开玻璃风格，免得拖了半天看不到变化
        sub.addItem(SliderItem("底色浓度", value: store.glassOpacity) { [weak self] value in
            self?.store.glass = true
            self?.store.glassOpacity = value
        })
        sub.addItem(SliderItem("背景模糊", value: store.glassBlur) { [weak self] value in
            self?.store.glass = true
            self?.store.glassBlur = value
        })
        sub.addItem(.separator())

        let header = NSMenuItem(title: "玻璃底色", action: nil, keyEquivalent: "")
        header.isEnabled = false
        sub.addItem(header)
        let presets = [("跟随主题", "theme"), ("纯白", "ffffff"), ("纯黑", "000000"), ("暖灰", "8a817c"), ("雾蓝", "6b8cae")]
        for (title, value) in presets {
            sub.addItem(ActionItem(title, checked: store.glassTint == value) { [weak self] in
                self?.store.glass = true
                self?.store.glassTint = value
            })
        }
        let isCustom = !presets.contains { $0.1 == store.glassTint }
        sub.addItem(ActionItem("从屏幕吸取颜色…", checked: isCustom) { [weak self] in self?.sampleScreenColor() })
        sub.addItem(ActionItem("自定义颜色…") { [weak self] in self?.showColorPanel(for: .glassTint) })
        item.submenu = sub
        return item
    }

    /// 系统取色器：鼠标变成放大镜，点哪里就取哪里的颜色（不需要屏幕录制权限）。
    private func sampleScreenColor() {
        NSApp.activate(ignoringOtherApps: true)
        NSColorSampler().show { [weak self] color in
            guard let self, let color, let hex = Self.hex(color) else { return }
            self.store.glass = true
            self.store.glassTint = hex
        }
    }

    private static func hex(_ color: NSColor) -> String? {
        guard let rgb = color.usingColorSpace(.sRGB) else { return nil }
        let value = ColorMath.pack(Int((rgb.redComponent * 255).rounded()), Int((rgb.greenComponent * 255).rounded()),
                                   Int((rgb.blueComponent * 255).rounded()))
        return String(format: "%06x", value)
    }

    private func showColorPanel(for target: ColorTarget) {
        colorTarget = target
        let panel = NSColorPanel.shared
        switch target {
        case .seed: panel.title = "选择主题色"
        case .digits: panel.title = "选择数字颜色"
        case .glassTint:
            panel.title = "选择玻璃底色"
            if let current = Store.color(hex: store.glassTint) { panel.color = NSColor(current) }
        }
        panel.setTarget(self)
        panel.setAction(#selector(customColorChanged(_:)))
        panel.showsAlpha = false
        panel.level = .floating
        NSApp.activate(ignoringOtherApps: true)
        panel.orderFront(nil)
    }

    @objc private func customColorChanged(_ sender: NSColorPanel) {
        guard let formatted = Self.hex(sender.color) else { return }
        switch colorTarget {
        case .seed: store.seedMode = formatted
        case .digits: store.digitColor = formatted
        case .glassTint:
            store.glass = true
            store.glassTint = formatted
        }
    }
}

// MARK: 入口

/// `--snapshot <png> [--light] [--seed <hex>] [--12h] [--style …] [--digits …] [--font …] [--color <hex>]
/// [--weather <WMO 代码>] [--zoom <倍数>] [--wide <宽度倍数>] [--tall <额外高度>]
/// [--glass [--tint <hex>] [--opacity <0…1>] [--blur <0…1>]]`：离屏渲染一张预览图，用于检查样式。
@MainActor
func renderSnapshot(to path: String, arguments: [String]) {
    func value(_ flag: String) -> String? {
        arguments.firstIndex(of: flag).flatMap { $0 + 1 < arguments.count ? arguments[$0 + 1] : nil }
    }
    func number(_ flag: String) -> CGFloat? { value(flag).flatMap(Double.init).map { CGFloat($0) } }
    let zoom = number("--zoom") ?? 1
    // 只读取设置，不写回，避免预览参数污染真实配置
    let store = Store()
    let themes = value("--seed").flatMap { RGB($0, radix: 16) }.map(ThemePair.init(seed:)) ?? store.themes
    let dark = !arguments.contains("--light")
    let sample = [City(name: "东京", tzID: "Asia/Tokyo"), City(name: "伦敦", tzID: "Europe/London")]
    let weather = Weather(temperature: 18.4, high: 22, low: 12, code: Int(value("--weather") ?? "2") ?? 2, isDay: true)
    let glass = arguments.contains("--glass")
    let theme = Store.theme(themes, dark: dark, digitColor: value("--color") ?? store.digitColor)
    let card = ClockCard(date: Date(), cities: sample, use24h: !arguments.contains("--12h"),
                         theme: glass ? theme.glassy(dark: dark, tint: value("--tint").flatMap(Store.color(hex:)),
                                                    opacity: number("--opacity").map(Double.init) ?? 0.3) : theme,
                         style: value("--style").flatMap(ClockStyle.init(rawValue:)) ?? .classic, weather: weather,
                         widthFactor: number("--wide") ?? 1, extraHeight: number("--tall") ?? 0,
                         weatherAnimationPaused: arguments.contains("--no-animation") ? nil : true, glass: glass, glassBlur: number("--blur").map(Double.init) ?? 1)
        .environment(\.isSnapshot, true)
        .environment(\.colorScheme, dark ? .dark : .light)
        .environment(\.zoom, zoom)
        .environment(\.look, Look(textFont: value("--font") ?? TextFont.dot,
                                  digitStyle: value("--digits").flatMap(DigitStyle.init(rawValue:)) ?? .round))
        .padding(24)
        .background(glass
            ? AnyView(LinearGradient(colors: [Color(red: 0.95, green: 0.45, blue: 0.35), Color(red: 0.25, green: 0.35, blue: 0.85)],
                                     startPoint: .topLeading, endPoint: .bottomTrailing))
            : AnyView(Color(white: 0.35)))
    let renderer = ImageRenderer(content: card)
    renderer.scale = 2
    guard let image = renderer.nsImage, let tiff = image.tiffRepresentation,
          let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:])
    else {
        FileHandle.standardError.write("snapshot failed\n".data(using: .utf8)!)
        exit(1)
    }
    try? png.write(to: URL(fileURLWithPath: path))
}

let arguments = CommandLine.arguments
if let i = arguments.firstIndex(of: "--snapshot"), i + 1 < arguments.count {
    MainActor.assumeIsolated { renderSnapshot(to: arguments[i + 1], arguments: arguments) }
    exit(0)
}

// `--weather-test [城市名 时区ID]`：联网测试网络授时、自动定位与天气获取。
if let i = arguments.firstIndex(of: "--weather-test") {
    if i + 2 < arguments.count {
        let name = arguments[i + 1], zone = arguments[i + 2]
        WeatherService.coordinate(name: name, tzID: zone) { coordinate in
            guard let coordinate else { print(name, "坐标查询失败"); exit(1) }
            WeatherService.fetch(latitude: coordinate.0, longitude: coordinate.1) { weather in
                print(name, coordinate, weather.map { "\($0.temperature)°C \($0.kind.title) 高\($0.high) 低\($0.low)" } ?? "天气获取失败")
                exit(weather == nil ? 1 : 0)
            }
        }
    } else {
        NetworkSync.syncTimeOffset { offset in
            let calibrated = Date().addingTimeInterval(offset ?? 0)
            NetworkSync.fetchLocation { loc in
                guard let loc else { print("网络定位失败"); exit(1) }
                let tz = TimeZone(identifier: loc.tzID) ?? .current
                let timeParts = ClockFormat.parts(calibrated, zone: tz, use24h: true)
                print(String(format: "网络时间: %@:%@ (%@, 偏移 %.3fs)", timeParts.hm, timeParts.ss, loc.tzID, offset ?? 0))
                guard let lat = loc.latitude, let lon = loc.longitude else {
                    print("网络定位城市: \(loc.name)，但坐标缺失")
                    exit(1)
                }
                WeatherService.fetch(latitude: lat, longitude: lon) { weather in
                    print("网络定位: \(loc.name) (\(lat), \(lon)) ->",
                          weather.map { "\($0.temperature)°C \($0.kind.title) 高\($0.high) 低\($0.low)" } ?? "天气获取失败")
                    exit(weather == nil ? 1 : 0)
                }
            }
        }
    }
    RunLoop.main.run(until: Date().addingTimeInterval(20))
    print("超时")
    exit(1)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
