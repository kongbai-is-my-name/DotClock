import AppKit
import CoreText
import SwiftUI

/// 5×7 点阵字形（Nothing 风格的点阵显示）。
enum DotFont {
    struct Glyph {
        let width: Int
        let rows: [[Bool]]
    }

    private static let source: [Character: String] = [
        "0": "01110|10001|10011|10101|11001|10001|01110",
        "1": "00100|01100|00100|00100|00100|00100|01110",
        "2": "01110|10001|00001|00010|00100|01000|11111",
        "3": "11111|00010|00100|00010|00001|10001|01110",
        "4": "00010|00110|01010|10010|11111|00010|00010",
        "5": "11111|10000|11110|00001|00001|10001|01110",
        "6": "00110|01000|10000|11110|10001|10001|01110",
        "7": "11111|00001|00010|00100|01000|01000|01000",
        "8": "01110|10001|10001|01110|10001|10001|01110",
        "9": "01110|10001|10001|01111|00001|00010|01100",
        "A": "01110|10001|10001|10001|11111|10001|10001",
        "B": "11110|10001|10001|11110|10001|10001|11110",
        "C": "01110|10001|10000|10000|10000|10001|01110",
        "D": "11100|10010|10001|10001|10001|10010|11100",
        "E": "11111|10000|10000|11110|10000|10000|11111",
        "F": "11111|10000|10000|11110|10000|10000|10000",
        "G": "01110|10001|10000|10111|10001|10001|01111",
        "H": "10001|10001|10001|11111|10001|10001|10001",
        "I": "01110|00100|00100|00100|00100|00100|01110",
        "J": "00111|00010|00010|00010|00010|10010|01100",
        "K": "10001|10010|10100|11000|10100|10010|10001",
        "L": "10000|10000|10000|10000|10000|10000|11111",
        "M": "10001|11011|10101|10101|10001|10001|10001",
        "N": "10001|10001|11001|10101|10011|10001|10001",
        "O": "01110|10001|10001|10001|10001|10001|01110",
        "P": "11110|10001|10001|11110|10000|10000|10000",
        "Q": "01110|10001|10001|10001|10101|10010|01101",
        "R": "11110|10001|10001|11110|10100|10010|10001",
        "S": "01111|10000|10000|01110|00001|00001|11110",
        "T": "11111|00100|00100|00100|00100|00100|00100",
        "U": "10001|10001|10001|10001|10001|10001|01110",
        "V": "10001|10001|10001|10001|10001|01010|00100",
        "W": "10001|10001|10001|10101|10101|10101|01010",
        "X": "10001|10001|01010|00100|01010|10001|10001",
        "Y": "10001|10001|10001|01010|00100|00100|00100",
        "Z": "11111|00001|00010|00100|01000|10000|11111",
        ":": "000|000|010|000|010|000|000",
        ".": "000|000|000|000|000|000|010",
        "+": "00000|00100|00100|11111|00100|00100|00000",
        "%": "11001|11010|00010|00100|01000|01011|10011",
        "°": "010|101|010|000|000|000|000",
        "/": "00001|00001|00010|00100|01000|10000|10000",
        "-": "00000|00000|00000|11111|00000|00000|00000",
        " ": "000|000|000|000|000|000|000",
    ]

    private static let glyphs: [Character: Glyph] = source.mapValues { text in
        let rows = text.split(separator: "|").map { $0.map { $0 == "1" } }
        return Glyph(width: rows[0].count, rows: rows)
    }

    private static var rasterCache: [Character: Glyph] = [:]
    private static let lock = NSLock()

    /// 内置字形之外的字符（中文等）：把系统字体栅格化成 12×12 点阵。
    static func glyph(_ character: Character) -> Glyph {
        if let builtIn = glyphs[character] { return builtIn }
        let upper = Character(String(character).uppercased().first.map(String.init) ?? " ")
        if let builtIn = glyphs[upper] { return builtIn }
        lock.lock()
        defer { lock.unlock() }
        if let cached = rasterCache[character] { return cached }
        let glyph = rasterize(character)
        rasterCache[character] = glyph
        return glyph
    }

    static let rasterSize = 12

    private static func rasterize(_ character: Character) -> Glyph {
        let n = rasterSize
        var pixels = [UInt8](repeating: 0, count: n * n)
        guard let context = CGContext(data: &pixels, width: n, height: n, bitsPerComponent: 8, bytesPerRow: n,
                                      space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)
        else { return glyphs[" "]! }
        context.setShouldSmoothFonts(false)
        let font = CTFontCreateWithName("PingFangSC-Regular" as CFString, CGFloat(n), nil)
        let line = CTLineCreateWithAttributedString(NSAttributedString(
            string: String(character), attributes: [.font: font, .foregroundColor: NSColor.white]))
        let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
        guard bounds.width > 0, bounds.height > 0 else { return glyphs[" "]! }
        // 全角字居中；窄字符按基线对齐，避免标点被抬到中间
        let wide = bounds.width > CGFloat(n) * 0.6
        context.textPosition = CGPoint(x: wide ? (CGFloat(n) - bounds.width) / 2 - bounds.minX : -bounds.minX,
                                       y: wide ? (CGFloat(n) - bounds.height) / 2 - bounds.minY : 2)
        CTLineDraw(line, context)
        var rows = (0..<n).map { r in (0..<n).map { pixels[r * n + $0] > 90 } }
        // 裁掉右侧空列（窄字符）
        var width = n
        while width > 1, !rows.contains(where: { $0[width - 1] }) { width -= 1 }
        if !wide { rows = rows.map { Array($0.prefix(width)) } } else { width = n }
        return Glyph(width: width, rows: rows)
    }
}

enum DotShape {
    case round, square, ring
}

struct DotText: View {
    let text: String
    let cell: CGFloat
    let color: Color
    var shape: DotShape = .round
    @Environment(\.zoom) private var zoom

    var body: some View {
        let cell = self.cell * zoom
        let glyphs = text.map { DotFont.glyph($0) }
        let columns = glyphs.reduce(0) { $0 + $1.width } + max(0, glyphs.count - 1)
        let lineRows = glyphs.map(\.rows.count).max() ?? 7
        Canvas { context, _ in
            // 小字号的点更饱满一些，保证可读性
            let diameter = cell * (cell < 2 ? 0.92 : 0.8)
            let inset = (cell - diameter) / 2
            let ringWidth = max(cell * 0.2, 0.7)
            var path = Path()
            var column = 0
            for glyph in glyphs {
                // 5×7 字形与 12 行的中文混排时，落在同一条基线上
                let offset = glyph.rows.count < lineRows ? lineRows - glyph.rows.count - 1 : 0
                for (r, row) in glyph.rows.enumerated() {
                    for (c, on) in row.enumerated() where on {
                        let rect = CGRect(x: CGFloat(column + c) * cell + inset,
                                          y: CGFloat(r + offset) * cell + inset,
                                          width: diameter, height: diameter)
                        switch shape {
                        case .round: path.addEllipse(in: rect)
                        case .square: path.addRect(rect)
                        case .ring: path.addEllipse(in: rect.insetBy(dx: ringWidth / 2, dy: ringWidth / 2))
                        }
                    }
                }
                column += glyph.width + 1
            }
            if shape == .ring {
                context.stroke(path, with: .color(color), lineWidth: ringWidth)
            } else {
                context.fill(path, with: .color(color))
            }
        }
        .frame(width: CGFloat(columns) * cell, height: CGFloat(lineRows) * cell)
    }
}

/// 60 个点的秒针进度条。
struct SecondsStrip: View {
    let second: Int
    let theme: Theme
    @Environment(\.zoom) private var zoom

    var body: some View {
        Canvas { context, size in
            let step = size.width / 60
            let diameter = min(step * 0.62, size.height)
            for i in 0..<60 {
                let rect = CGRect(x: CGFloat(i) * step + (step - diameter) / 2,
                                  y: (size.height - diameter) / 2, width: diameter, height: diameter)
                let color = i == second ? theme.accent : (i < second ? theme.lit : theme.dim)
                context.fill(Path(ellipseIn: rect), with: .color(color))
            }
        }
        .frame(height: 4 * zoom)
    }
}

/// 今日进度：24 个圆点代表 24 小时，已过去的点亮，当前小时按分钟从左向右填充。
struct DayProgressBar: View {
    let fraction: Double
    let theme: Theme
    @Environment(\.zoom) private var zoom

    var body: some View {
        Canvas { context, size in
            let step = size.width / 24
            let diameter = min(step * 0.72, size.height)
            let elapsed = max(0, min(1, fraction)) * 24
            let current = min(23, Int(elapsed))
            for i in 0..<24 {
                let rect = CGRect(x: CGFloat(i) * step + (step - diameter) / 2,
                                  y: (size.height - diameter) / 2, width: diameter, height: diameter)
                let circle = Path(ellipseIn: rect)
                context.fill(circle, with: .color(i < current ? theme.lit : theme.dim))
                if i == current {
                    var partial = context
                    partial.clip(to: Path(CGRect(x: rect.minX, y: rect.minY,
                                                 width: diameter * CGFloat(elapsed - Double(current)), height: diameter)))
                    partial.fill(circle, with: .color(theme.accent))
                }
            }
        }
        .frame(height: 7 * zoom)
    }
}
