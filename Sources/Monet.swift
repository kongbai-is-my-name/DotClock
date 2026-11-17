import AppKit
import SwiftUI

// Material You 主题色彩生成：CAM16 / HCT 色彩空间 + TonalSpot 色调盘。
// 算法对应 Google material-color-utilities 的 Cam16、Hct、SchemeTonalSpot。

typealias RGB = UInt32

enum ColorMath {
    static func channels(_ c: RGB) -> (Int, Int, Int) {
        (Int((c >> 16) & 0xFF), Int((c >> 8) & 0xFF), Int(c & 0xFF))
    }

    static func pack(_ r: Int, _ g: Int, _ b: Int) -> RGB {
        RGB(r) << 16 | RGB(g) << 8 | RGB(b)
    }

    static func linearized(_ component: Int) -> Double {
        let n = Double(component) / 255
        return (n <= 0.040449936 ? n / 12.92 : pow((n + 0.055) / 1.055, 2.4)) * 100
    }

    static func delinearized(_ value: Double) -> Int {
        let n = value / 100
        let d = n <= 0.0031308 ? n * 12.92 : 1.055 * pow(n, 1 / 2.4) - 0.055
        return max(0, min(255, Int((d * 255).rounded())))
    }

    static func yFromLstar(_ lstar: Double) -> Double {
        let ft = (lstar + 16) / 116
        let ft3 = ft * ft * ft
        return 100 * (ft3 > 216.0 / 24389 ? ft3 : (116 * ft - 16) / (24389.0 / 27))
    }

    static func lstar(_ c: RGB) -> Double {
        let (r, g, b) = channels(c)
        let y = (0.2126 * linearized(r) + 0.7152 * linearized(g) + 0.0722 * linearized(b)) / 100
        let f = y > 216.0 / 24389 ? cbrt(y) : (24389.0 / 27 * y + 16) / 116
        return 116 * f - 16
    }

    static func gray(tone: Double) -> RGB {
        let v = delinearized(yFromLstar(tone))
        return pack(v, v, v)
    }

    static func xyz(_ c: RGB) -> (Double, Double, Double) {
        let (r8, g8, b8) = channels(c)
        let r = linearized(r8), g = linearized(g8), b = linearized(b8)
        return (0.41233895 * r + 0.35762064 * g + 0.18051042 * b,
                0.2126 * r + 0.7152 * g + 0.0722 * b,
                0.01932141 * r + 0.11916382 * g + 0.95034478 * b)
    }

    static func rgb(x: Double, y: Double, z: Double) -> RGB {
        let r = 3.2413774792388685 * x - 1.5376652402851851 * y - 0.49885366846268053 * z
        let g = -0.9691452513005321 * x + 1.8758853451067872 * y + 0.04156585616912061 * z
        let b = 0.05562093689691305 * x - 0.20395524564742123 * y + 1.0571799111220335 * z
        return pack(delinearized(r), delinearized(g), delinearized(b))
    }

    static func sign(_ v: Double) -> Double { v < 0 ? -1 : (v == 0 ? 0 : 1) }
}

/// CAM16 默认观察条件（sRGB、D65、背景 L*=50）。
private struct ViewingConditions {
    let n, aw, nbb, ncb, c, nc, fl, fLRoot, z: Double
    let rgbD: [Double]

    static let standard = ViewingConditions()

    init() {
        let white = (95.047, 100.0, 108.883)
        let la = 200 / Double.pi * ColorMath.yFromLstar(50) / 100
        let rW = white.0 * 0.401288 + white.1 * 0.650173 + white.2 * -0.051461
        let gW = white.0 * -0.250268 + white.1 * 1.204414 + white.2 * 0.045854
        let bW = white.0 * -0.002079 + white.1 * 0.048952 + white.2 * 0.953127
        let f = 1.0
        c = 0.69
        nc = f
        let d = max(0, min(1, f * (1 - (1 / 3.6) * exp((-la - 42) / 92))))
        rgbD = [d * (100 / rW) + 1 - d, d * (100 / gW) + 1 - d, d * (100 / bW) + 1 - d]
        let k = 1 / (5 * la + 1)
        let k4 = k * k * k * k
        let k4F = 1 - k4
        fl = k4 * la + 0.1 * k4F * k4F * cbrt(5 * la)
        n = ColorMath.yFromLstar(50) / white.1
        z = 1.48 + sqrt(n)
        nbb = 0.725 / pow(n, 0.2)
        ncb = nbb
        let factors = [pow(fl * rgbD[0] * rW / 100, 0.42),
                       pow(fl * rgbD[1] * gW / 100, 0.42),
                       pow(fl * rgbD[2] * bW / 100, 0.42)]
        let rgbA = factors.map { 400 * $0 / ($0 + 27.13) }
        aw = (2 * rgbA[0] + rgbA[1] + 0.05 * rgbA[2]) * nbb
        fLRoot = pow(fl, 0.25)
    }
}

struct Cam16 {
    let hue: Double
    let chroma: Double
    let j: Double

    init(_ color: RGB) {
        let vc = ViewingConditions.standard
        let (x, y, z) = ColorMath.xyz(color)
        let rD = vc.rgbD[0] * (x * 0.401288 + y * 0.650173 + z * -0.051461)
        let gD = vc.rgbD[1] * (x * -0.250268 + y * 1.204414 + z * 0.045854)
        let bD = vc.rgbD[2] * (x * -0.002079 + y * 0.048952 + z * 0.953127)
        func adapt(_ v: Double) -> Double {
            let af = pow(vc.fl * abs(v) / 100, 0.42)
            return ColorMath.sign(v) * 400 * af / (af + 27.13)
        }
        let rA = adapt(rD), gA = adapt(gD), bA = adapt(bD)
        let a = (11 * rA - 12 * gA + bA) / 11
        let b = (rA + gA - 2 * bA) / 9
        let u = (20 * rA + 20 * gA + 21 * bA) / 20
        let p2 = (40 * rA + 20 * gA + bA) / 20
        var h = atan2(b, a) * 180 / .pi
        if h < 0 { h += 360 }
        if h >= 360 { h -= 360 }
        let ac = p2 * vc.nbb
        let jj = 100 * pow(ac / vc.aw, vc.c * vc.z)
        let huePrime = h < 20.14 ? h + 360 : h
        let eHue = 0.25 * (cos(huePrime * .pi / 180 + 2) + 3.8)
        let p1 = 50000 / 13 * eHue * vc.nc * vc.ncb
        let t = p1 * hypot(a, b) / (u + 0.305)
        let alpha = pow(1.64 - pow(0.29, vc.n), 0.73) * pow(t, 0.9)
        hue = h
        j = jj
        chroma = alpha * sqrt(jj / 100)
    }

    /// J/C/h 反解回 sRGB（超出色域的分量会被裁剪）。
    static func rgb(j: Double, chroma: Double, hue: Double) -> RGB {
        let vc = ViewingConditions.standard
        let alpha = (chroma == 0 || j == 0) ? 0 : chroma / sqrt(j / 100)
        let t = pow(alpha / pow(1.64 - pow(0.29, vc.n), 0.73), 1 / 0.9)
        let hRad = hue * .pi / 180
        let eHue = 0.25 * (cos(hRad + 2) + 3.8)
        let ac = vc.aw * pow(j / 100, 1 / vc.c / vc.z)
        let p1 = eHue * (50000 / 13) * vc.nc * vc.ncb
        let p2 = ac / vc.nbb
        let hSin = sin(hRad), hCos = cos(hRad)
        let gamma = 23 * (p2 + 0.305) * t / (23 * p1 + 11 * t * hCos + 108 * t * hSin)
        let a = gamma * hCos, b = gamma * hSin
        let rA = (460 * p2 + 451 * a + 288 * b) / 1403
        let gA = (460 * p2 - 891 * a - 261 * b) / 1403
        let bA = (460 * p2 - 220 * a - 6300 * b) / 1403
        func unadapt(_ v: Double) -> Double {
            let base = max(0, 27.13 * abs(v) / (400 - abs(v)))
            return ColorMath.sign(v) * (100 / vc.fl) * pow(base, 1 / 0.42)
        }
        let rF = unadapt(rA) / vc.rgbD[0]
        let gF = unadapt(gA) / vc.rgbD[1]
        let bF = unadapt(bA) / vc.rgbD[2]
        return ColorMath.rgb(x: 1.86206786 * rF - 1.01125463 * gF + 0.14918677 * bF,
                             y: 0.38752654 * rF + 0.62144744 * gF - 0.00897398 * bF,
                             z: -0.01584150 * rF - 0.03412294 * gF + 1.04996444 * bF)
    }
}

enum HCT {
    /// 给定色相、色度、色调（L*）求 sRGB；色度超出色域时保持色相和色调，尽量取最大色度。
    static func rgb(hue: Double, chroma: Double, tone: Double) -> RGB {
        if chroma < 1 || tone.rounded() <= 0 || tone.rounded() >= 100 {
            return ColorMath.gray(tone: tone)
        }
        var h = hue.truncatingRemainder(dividingBy: 360)
        if h < 0 { h += 360 }
        if let exact = solve(hue: h, chroma: chroma, tone: tone) { return exact }
        var low = 0.0, high = chroma
        var answer: RGB?
        while high - low >= 0.4 {
            let mid = low + (high - low) / 2
            if let hit = solve(hue: h, chroma: mid, tone: tone) {
                answer = hit
                low = mid
            } else {
                high = mid
            }
        }
        return answer ?? ColorMath.gray(tone: tone)
    }

    private static func solve(hue: Double, chroma: Double, tone: Double) -> RGB? {
        let vc = ViewingConditions.standard
        var low = 0.0, high = 100.0
        var best: RGB?
        var bestError = 1.0
        while high - low > 0.01 {
            let mid = low + (high - low) / 2
            let clipped = Cam16.rgb(j: mid, chroma: chroma, hue: hue)
            let l = ColorMath.lstar(clipped)
            if abs(tone - l) < 0.2 {
                // 裁剪后色相偏移量（CAM16-UCS 距离）
                let cam = Cam16(clipped)
                let m = log(1 + 0.0228 * cam.chroma * vc.fLRoot) / 0.0228
                let dh = (cam.hue - hue) * .pi / 180
                let error = abs(2 * m * sin(dh / 2))
                if error <= bestError {
                    bestError = error
                    best = clipped
                }
            }
            if l < tone { low = mid } else { high = mid }
        }
        return best
    }
}

enum Monet {
    static let fallbackSeed: RGB = 0x1B6EF3
    static let defaultSeedHex = "1b6ef3"
}

/// 小组件用到的颜色角色。
struct Theme {
    var surface, border, digits, accent, label, sub, dim, lit: Color

    /// 玻璃风格：底色变成半透明的着色层，分隔线和暗点改为半透明白 / 黑，
    /// 让背后的桌面透过来，同时保证文字在任意壁纸上都看得清。
    /// `tint` 为 nil 时用主题自己的底色；`opacity` 是底色浓度（0 = 完全透明，1 = 不透明）。
    func glassy(dark: Bool, tint: Color? = nil, opacity: Double = 0.3) -> Theme {
        var theme = self
        theme.surface = (tint ?? surface).opacity(opacity)
        theme.border = dark ? Color.white.opacity(0.14) : Color.black.opacity(0.08)
        theme.dim = dark ? Color.white.opacity(0.16) : Color.black.opacity(0.10)
        return theme
    }
}

struct ThemePair {
    let dark: Theme
    let light: Theme

    /// TonalSpot：A1 主色、A3 第三色（色相 +60）、N1/N2 中性色。
    init(seed: RGB) {
        let hue = Cam16(seed).hue
        func tone(_ h: Double, _ chroma: Double, _ t: Double) -> Color {
            let (r, g, b) = ColorMath.channels(HCT.rgb(hue: h, chroma: chroma, tone: t))
            return Color(.sRGB, red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255)
        }
        let a1 = { tone(hue, 36, $0) }
        let a3 = { tone(hue + 60, 24, $0) }
        let n1 = { tone(hue, 6, $0) }
        let n2 = { tone(hue, 8, $0) }
        dark = Theme(surface: n1(6), border: n2(20), digits: a1(90), accent: a3(70),
                     label: n2(80), sub: n2(60), dim: n2(22), lit: a1(80))
        light = Theme(surface: n1(96), border: n2(87), digits: a1(20), accent: a3(45),
                      label: n2(30), sub: n2(50), dim: n2(85), lit: a1(40))
    }
}
