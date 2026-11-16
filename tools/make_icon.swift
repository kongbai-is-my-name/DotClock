// 生成应用图标：深色圆角底 + 点阵数字 “12” + 红色秒点，与小组件风格一致。
// 用法：swift make_icon.swift <输出 1024×1024 PNG 路径>
import AppKit

let size = 1024
let glyphs: [Character: [String]] = [
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    ":": ["0", "0", "1", "0", "1", "0", "0"],
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
]

guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0,
                              space: CGColorSpace(name: CGColorSpace.sRGB)!,
                              bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { exit(1) }
context.translateBy(x: 0, y: CGFloat(size))
context.scaleBy(x: 1, y: -1)

// macOS 图标网格：824×824 的圆角矩形居中
let inset: CGFloat = 100
let card = CGRect(x: inset, y: inset, width: CGFloat(size) - 2 * inset, height: CGFloat(size) - 2 * inset)
let shape = CGPath(roundedRect: card, cornerWidth: 185, cornerHeight: 185, transform: nil)
context.saveGState()
context.setShadow(offset: CGSize(width: 0, height: 12), blur: 28, color: CGColor(gray: 0, alpha: 0.35))
context.addPath(shape)
context.setFillColor(CGColor(srgbRed: 0.075, green: 0.078, blue: 0.09, alpha: 1))
context.fillPath()
context.restoreGState()
context.addPath(shape)
context.setStrokeColor(CGColor(gray: 1, alpha: 0.08))
context.setLineWidth(4)
context.strokePath()

func drawText(_ text: String, cell: CGFloat, origin: CGPoint, color: CGColor) {
    context.setFillColor(color)
    var x = origin.x
    for character in text {
        guard let rows = glyphs[character] else { continue }
        for (r, row) in rows.enumerated() {
            for (c, bit) in row.enumerated() where bit == "1" {
                let d = cell * 0.78
                context.fillEllipse(in: CGRect(x: x + CGFloat(c) * cell + (cell - d) / 2,
                                               y: origin.y + CGFloat(r) * cell + (cell - d) / 2, width: d, height: d))
            }
        }
        x += CGFloat(rows[0].count + 1) * cell
    }
}

// “12:00” 太宽，图标里只放 “12”，下方一排秒点
let cell: CGFloat = 52
let textWidth = CGFloat(5 + 1 + 5) * cell
drawText("12", cell: cell, origin: CGPoint(x: (CGFloat(size) - textWidth) / 2, y: 250),
         color: CGColor(srgbRed: 0.93, green: 0.94, blue: 0.97, alpha: 1))

// 秒进度：12 个点，前 7 个点亮，第 8 个是红色当前秒
let dots = 12, spacing: CGFloat = 52, diameter: CGFloat = 26
let startX = (CGFloat(size) - CGFloat(dots - 1) * spacing) / 2
for i in 0..<dots {
    let color: CGColor
    if i < 7 { color = CGColor(srgbRed: 0.93, green: 0.94, blue: 0.97, alpha: 0.85) }
    else if i == 7 { color = CGColor(srgbRed: 0.843, green: 0.098, blue: 0.129, alpha: 1) }
    else { color = CGColor(gray: 1, alpha: 0.16) }
    context.setFillColor(color)
    context.fillEllipse(in: CGRect(x: startX + CGFloat(i) * spacing - diameter / 2, y: 720 - diameter / 2,
                                   width: diameter, height: diameter))
}

guard let image = context.makeImage(),
      let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) else { exit(1) }
try png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
