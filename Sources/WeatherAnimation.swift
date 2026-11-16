import SwiftUI

/// 天气行背后的点阵动画：雨滴斜落、雪花飘落、云朵漂移、雾气流动、星星闪烁、闪电。
///
/// 所有位置都以“格”为单位（1 格 = 3pt × 缩放倍数），并吸附到点阵网格上：
/// - 放大 / 缩小：格子跟着倍数变，动画整体等比缩放；
/// - 拉宽：粒子按列间距生成，宽度增加只会在右侧多出粒子，密度不变、不会被拉伸；
/// - 拉长：天气行本身高度不变，动画不受影响。
struct WeatherAnimation: View {
    let kind: WeatherKind
    let intensity: Double
    let theme: Theme
    let paused: Bool
    @Environment(\.zoom) private var zoom
    @Environment(\.look) private var look

    private static let timelineAnchor = Date()

    var body: some View {
        // 12 帧 / 秒：点阵动画本来就是一格一格跳的，帧率低一些既有味道也省电。
        // 暂停时把间隔拉到一小时，相当于停住。
        TimelineView(.periodic(from: WeatherAnimation.timelineAnchor, by: paused ? 3600 : 1.0 / 12)) { timeline in
            Canvas { context, size in
                var painter = Painter(context: context, cell: 3 * zoom, square: look.digitStyle == .square,
                                      columns: Int((size.width / (3 * zoom)).rounded(.up)),
                                      rows: Int((size.height / (3 * zoom)).rounded(.up)),
                                      time: timeline.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 86400))
                switch kind {
                case .rain: painter.rain(intensity: intensity, color: theme.lit)
                case .snow: painter.snow(intensity: intensity, color: theme.label)
                case .thunder:
                    painter.rain(intensity: 1.3, color: theme.lit)
                    painter.lightning(color: theme.accent)
                case .cloudy: painter.clouds(density: 1, color: theme.sub)
                case .partly: painter.clouds(density: 0.6, color: theme.sub)
                case .fog: painter.fog(color: theme.sub)
                case .clear: painter.sparkle(density: 0.5, color: theme.accent)
                case .clearNight: painter.sparkle(density: 1, color: theme.label)
                }
            }
        }
        .allowsHitTesting(false)
    }
}

private struct Painter {
    var context: GraphicsContext
    let cell: CGFloat
    let square: Bool
    let columns: Int
    let rows: Int
    let time: Double

    /// 稳定的伪随机数（0..<1）：同一个粒子每一帧取到的值相同。
    func random(_ index: Int, _ salt: Int) -> Double {
        var x = UInt64(truncatingIfNeeded: index &* 374_761_393 &+ salt &* 668_265_263 &+ 1_013_904_223)
        x = (x ^ (x >> 13)) &* 1_274_126_177
        x ^= x >> 16
        return Double(x % 100_000) / 100_000
    }

    mutating func dot(_ column: Double, _ row: Double, _ color: Color, _ opacity: Double) {
        let c = column.rounded(.down), r = row.rounded(.down)
        guard c >= -1, r >= -1, c <= Double(columns), r <= Double(rows), opacity > 0.02 else { return }
        let diameter = cell * 0.8, inset = (cell - diameter) / 2
        let rect = CGRect(x: CGFloat(c) * cell + inset, y: CGFloat(r) * cell + inset, width: diameter, height: diameter)
        context.fill(square ? Path(rect) : Path(ellipseIn: rect), with: .color(color.opacity(opacity)))
    }

    /// 斜向下落的雨滴，每滴带一个淡淡的拖尾。
    mutating func rain(intensity: Double, color: Color) {
        let spacing = 5 / intensity
        let slope = 1.0  // 每下落一格向左偏一格，45° 斜线正好落在点阵网格上
        let span = Double(rows + 4)
        let count = Int((Double(columns) + Double(rows) * slope) / spacing) + 1
        for i in 0..<count {
            let progress = time * (9 + 6 * random(i, 1)) + random(i, 2) * span
            let y = progress.truncatingRemainder(dividingBy: span) - 2
            let lap = Int(progress / span)
            // 每落完一轮换一个水平位置，看起来不重复
            let x0 = (Double(i) + random(i &+ lap &* 31, 3)) * spacing
            let column = (x0 - y * slope).rounded(.down), row = y.rounded(.down)
            dot(column, row, color, 0.75)
            dot(column + 1, row - 1, color, 0.3)
        }
    }

    /// 缓慢飘落、左右摇摆的雪花；少数是十字形的大雪花。
    mutating func snow(intensity: Double, color: Color) {
        let spacing = 6 / intensity
        let span = Double(rows + 4)
        let count = Int(Double(columns) / spacing) + 2
        for i in 0..<count {
            let progress = time * (2.2 + 2 * random(i, 1)) + random(i, 2) * span
            let y = progress.truncatingRemainder(dividingBy: span) - 2
            let lap = Int(progress / span)
            let x = (Double(i) + random(i &+ lap &* 31, 3)) * spacing + sin(time * 0.9 + random(i, 4) * 6.28) * 1.5
            let opacity = 0.35 + 0.4 * random(i, 5)
            dot(x, y, color, opacity)
            if random(i, 6) < 0.22 {
                for (dx, dy) in [(-1.0, 0.0), (1.0, 0.0), (0.0, -1.0), (0.0, 1.0)] {
                    dot(x.rounded(.down) + dx, y.rounded(.down) + dy, color, opacity * 0.6)
                }
            }
        }
    }

    private static let cloudShapes: [[String]] = [
        ["...###....", ".#######..", "##########"],
        ["..##......", ".#####.#..", "#########."],
        ["....##..", "..#####.", "########"],
    ]

    /// 横向漂移的小云朵。
    mutating func clouds(density: Double, color: Color) {
        let count = max(2, Int(Double(columns) / 26 * density) + 1)
        for i in 0..<count {
            let shape = Painter.cloudShapes[i % Painter.cloudShapes.count].map(Array.init)
            let width = Double(shape[0].count)
            let span = Double(columns) + width
            let lane = span / Double(count)
            let x = (time * (1 + 1.2 * random(i, 1)) + Double(i) * lane + random(i, 2) * lane * 0.5)
                .truncatingRemainder(dividingBy: span) - width
            let y = (random(i, 3) * Double(max(1, rows - shape.count - 1))).rounded(.down) + 1
            let opacity = 0.22 + 0.2 * random(i, 4)
            for (r, row) in shape.enumerated() {
                for (c, mark) in row.enumerated() where mark == "#" {
                    dot(x.rounded(.down) + Double(c), y + Double(r), color, opacity)
                }
            }
        }
    }

    /// 几行左右流动的断续点线。
    mutating func fog(color: Color) {
        var row = 1
        var lane = 0
        while row < rows {
            let direction: Double = lane % 2 == 0 ? 1 : -1
            let shift = Int((time * (1.5 + 1.5 * random(lane, 1)) * direction).rounded(.down))
            for column in 0...columns {
                // 以 3 格为一段决定这一段亮不亮，形成断续的雾带
                if random((column &+ shift) / 3, lane &+ 7) < 0.42 {
                    dot(Double(column), Double(row), color, 0.2)
                }
            }
            row += 4
            lane += 1
        }
    }

    /// 位置固定、忽明忽暗的光点：白天是阳光闪烁，夜里是星星。
    mutating func sparkle(density: Double, color: Color) {
        let spacing = 5 / density
        let count = Int(Double(columns) / spacing) + 1
        for i in 0..<count {
            let x = (Double(i) + random(i, 1)) * spacing
            let y = random(i, 2) * Double(max(1, rows - 1))
            let glow = sin(time * (0.6 + 1.4 * random(i, 3)) + random(i, 4) * 6.28)
            dot(x, y, color, max(0, glow) * 0.7)
        }
    }

    /// 每隔几秒在随机位置闪两下的折线闪电。
    mutating func lightning(color: Color) {
        let period = 3.7
        let phase = time.truncatingRemainder(dividingBy: period)
        guard phase < 0.1 || (phase > 0.18 && phase < 0.3) else { return }
        let strike = Int(time / period)
        var x = (0.15 + 0.7 * random(strike, 1)) * Double(columns)
        for row in 0..<rows {
            dot(x, Double(row), color, 0.95)
            // 折线：每隔两三格换一次方向
            x += random(strike &+ row / 3, 2) < 0.5 ? -1 : 1
        }
    }
}
