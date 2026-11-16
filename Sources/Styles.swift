import AppKit
import SwiftUI

/// 数字样式：点阵变体、七段数码管，以及几种真实字体。
enum DigitStyle: String, CaseIterable {
    case round, square, ring, segment, rounded, mono, din, futura, didot, phosphate

    var title: String {
        switch self {
        case .round: return "圆点点阵"
        case .square: return "方块像素"
        case .ring: return "空心圆点"
        case .segment: return "七段数码管"
        case .rounded: return "圆体 · SF Rounded"
        case .mono: return "等宽 · SF Mono"
        case .din: return "窄体 · DIN Condensed"
        case .futura: return "几何 · Futura"
        case .didot: return "衬线 · Didot"
        case .phosphate: return "霓虹线 · Phosphate"
        }
    }

    var isFont: Bool {
        switch self {
        case .round, .square, .ring, .segment: return false
        default: return true
        }
    }

    var dotShape: DotShape {
        switch self {
        case .square: return .square
        case .ring: return .ring
        default: return .round
        }
    }

    private var fontName: String? {
        switch self {
        case .din: return "DINCondensed-Bold"
        case .futura: return "Futura-Medium"
        case .didot: return "Didot-Bold"
        case .phosphate: return "Phosphate-Inline"
        default: return nil
        }
    }

    var isAvailable: Bool {
        fontName.map { NSFont(name: $0, size: 12) != nil } ?? true
    }

    /// 让数字的高度（大写字高）正好等于 `height` 的字体。
    func font(height: CGFloat) -> Font {
        let reference: NSFont
        if let fontName, let named = NSFont(name: fontName, size: 100) {
            reference = named
        } else {
            reference = NSFont.systemFont(ofSize: 100, weight: .bold)
        }
        let size = height / (reference.capHeight / 100)
        if let fontName {
            return .custom(fontName, fixedSize: size)
        } else if self == .mono {
            return .system(size: size, weight: .medium, design: .monospaced)
        } else {
            return .system(size: size, weight: .bold, design: .rounded)
        }
    }
}

/// 文字（城市名、日期等）可选的字体。
enum TextFont {
    static let dot = "dot"

    private static let candidates: [(id: String, title: String)] = [
        (dot, "点阵"), ("system", "苹方"), ("mono", "等宽"),
        ("STYuanti-SC-Bold", "圆体"), ("STSongti-SC-Bold", "宋体"), ("STKaitiSC-Bold", "楷体"),
        ("STXingkaiSC-Bold", "行楷"), ("STLibianSC-Regular", "隶变"), ("STBaoliSC-Regular", "报隶"),
        ("MLingWaiMedium-SC", "凌慧体"), ("HannotateSC-W5", "手札体"), ("HanziPenSC-W5", "翩翩体"),
        ("YuppySC-Regular", "雅痞"), ("DFWaWaSC-W5", "娃娃体"),
    ]

    /// 只列出本机已安装的字体。
    static let options: [(id: String, title: String)] = candidates.filter {
        $0.id == dot || $0.id == "system" || $0.id == "mono" || NSFont(name: $0.id, size: 12) != nil
    }

    static func font(_ id: String, size: CGFloat) -> Font {
        switch id {
        case "system": return .system(size: size * 0.92, weight: .semibold)
        case "mono": return .system(size: size * 0.9, weight: .medium, design: .monospaced)
        default: return .custom(id, fixedSize: size)
        }
    }
}

struct Look: Equatable {
    var textFont = TextFont.dot
    var digitStyle = DigitStyle.round
}

private struct LookKey: EnvironmentKey {
    static let defaultValue = Look()
}

private struct ZoomKey: EnvironmentKey {
    static let defaultValue: CGFloat = 1
}

extension EnvironmentValues {
    /// 小组件整体缩放倍数。绘制文字、数字、图标的视图会自己乘上它，容器只需缩放间距。
    var zoom: CGFloat {
        get { self[ZoomKey.self] }
        set { self[ZoomKey.self] = newValue }
    }

    var look: Look {
        get { self[LookKey.self] }
        set { self[LookKey.self] = newValue }
    }
}

/// 时间、温度等数字，按当前数字样式绘制。占位高度固定为 7 × cell。
struct Digits: View {
    let text: String
    let cell: CGFloat
    let color: Color
    @Environment(\.look) private var look
    @Environment(\.zoom) private var zoom

    var body: some View {
        let style = look.digitStyle
        if style == .segment {
            SegmentText(text: text, cell: cell, color: color)
        } else if style.isFont {
            // 把文字基线对齐到占位框底部，数字正好填满 7 × cell 的高度，与字体自身的行高无关
            Text(text)
                .font(style.font(height: 7 * cell * zoom))
                .monospacedDigit()
                .foregroundStyle(color)
                .lineLimit(1)
                .fixedSize()
                .alignmentGuide(.bottom) { $0[.firstTextBaseline] }
                .frame(height: 7 * cell * zoom, alignment: .bottom)
        } else {
            DotText(text: text, cell: cell, color: color, shape: style.dotShape)
        }
    }
}

/// 城市名、日期等文字，按当前文字字体绘制。
/// `size` 是字号；点阵模式下每个点的间距默认为 size / 10，也可用 `dotCell` 单独指定。
struct LabelText: View {
    let text: String
    let size: CGFloat
    let color: Color
    var dotCell: CGFloat?
    @Environment(\.look) private var look
    @Environment(\.zoom) private var zoom

    var body: some View {
        if look.textFont == TextFont.dot {
            DotText(text: text, cell: dotCell ?? size / 10, color: color,
                    shape: look.digitStyle == .square ? .square : .round)
        } else {
            Text(text)
                .font(TextFont.font(look.textFont, size: size * zoom))
                .foregroundStyle(color)
                .lineLimit(1)
                .fixedSize()
        }
    }
}

/// 七段数码管。字符占位与 5×7 点阵一致，所以可以和点阵样式互换而不影响布局。
struct SegmentText: View {
    let text: String
    let cell: CGFloat
    let color: Color
    @Environment(\.zoom) private var zoom

    private static let segments: [Character: String] = [
        "0": "abcdef", "1": "bc", "2": "abged", "3": "abgcd", "4": "fgbc", "5": "afgcd",
        "6": "afgedc", "7": "abc", "8": "abcdefg", "9": "abfgcd", "-": "g",
        "A": "abcefg", "P": "abefg", "C": "adef", "F": "aefg", "E": "adefg", "H": "bcefg", "L": "def",
    ]

    var body: some View {
        let cell = self.cell * zoom
        let characters = Array(text.uppercased())
        let widths = characters.map { ":. °".contains($0) ? 3 : 5 }
        let columns = widths.reduce(0, +) + max(0, characters.count - 1)
        Canvas { context, _ in
            let w = 5 * cell, h = 7 * cell
            let t = cell * 1.05, gap = cell * 0.14
            var path = Path()
            func horizontal(_ x: CGFloat, _ y: CGFloat) {
                let x1 = x + t / 2 + gap, x2 = x + w - t / 2 - gap
                path.addLines([CGPoint(x: x1, y: y), CGPoint(x: x1 + t / 2, y: y - t / 2),
                               CGPoint(x: x2 - t / 2, y: y - t / 2), CGPoint(x: x2, y: y),
                               CGPoint(x: x2 - t / 2, y: y + t / 2), CGPoint(x: x1 + t / 2, y: y + t / 2)])
                path.closeSubpath()
            }
            func vertical(_ x: CGFloat, _ y1: CGFloat, _ y2: CGFloat) {
                path.addLines([CGPoint(x: x, y: y1), CGPoint(x: x + t / 2, y: y1 + t / 2),
                               CGPoint(x: x + t / 2, y: y2 - t / 2), CGPoint(x: x, y: y2),
                               CGPoint(x: x - t / 2, y: y2 - t / 2), CGPoint(x: x - t / 2, y: y1 + t / 2)])
                path.closeSubpath()
            }
            var x: CGFloat = 0
            for (index, character) in characters.enumerated() {
                if character == ":" {
                    for y in [h * 0.3, h * 0.7] {
                        path.addRect(CGRect(x: x + 1.5 * cell - t / 2, y: y - t / 2, width: t, height: t))
                    }
                } else if character == "°" {
                    // 小方框形的度数符号
                    let side = 2.6 * cell, bar = cell * 0.7
                    path.addRect(CGRect(x: x, y: 0, width: side, height: bar))
                    path.addRect(CGRect(x: x, y: side - bar, width: side, height: bar))
                    path.addRect(CGRect(x: x, y: 0, width: bar, height: side))
                    path.addRect(CGRect(x: x + side - bar, y: 0, width: bar, height: side))
                } else if character == "." {
                    path.addRect(CGRect(x: x + 1.5 * cell - t / 2, y: h - t, width: t, height: t))
                } else if let lit = SegmentText.segments[character] {
                    for segment in lit {
                        switch segment {
                        case "a": horizontal(x, t / 2)
                        case "g": horizontal(x, h / 2)
                        case "d": horizontal(x, h - t / 2)
                        case "f": vertical(x + t / 2, t / 2 + gap, h / 2 - gap)
                        case "b": vertical(x + w - t / 2, t / 2 + gap, h / 2 - gap)
                        case "e": vertical(x + t / 2, h / 2 + gap, h - t / 2 - gap)
                        default: vertical(x + w - t / 2, h / 2 + gap, h - t / 2 - gap)
                        }
                    }
                }
                x += CGFloat(widths[index] + 1) * cell
            }
            context.fill(path, with: .color(color))
        }
        .frame(width: CGFloat(columns) * cell, height: 7 * cell)
    }
}
