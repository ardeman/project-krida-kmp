// Generates every Krida brand asset (app icons, favicon, wordmark) from one geometry.
//
//   swift scripts/brand.swift path/to/Baloo2[wght].ttf
//
// Run from the repo root. The font (OFL, github.com/google/fonts/tree/main/ofl/baloo2) is
// only needed to outline the "rida" part of the wordmark; nothing ships with the font.
// See docs/brand.md for the palette and the rules for using the assets.

import CoreGraphics
import CoreText
import Foundation
import ImageIO
import UniformTypeIdentifiers

// MARK: Palette (keep in sync with docs/brand.md)

enum Brand {
    static let krem = "#FFF7EC"
    static let malam = "#1B1612"
    static let ink = "#2B211A"
    static let inkDark = "#F6EADC"
    static let accent = "#C9783A"
    static let accentDark = "#E39A5C"
    static let kayuTerang = "#E3A66B"
    static let kayu = "#B8743F"
    static let kayuGelap = "#8F552A"
    static let merah = "#D6453D"
    static let biru = "#2F6FD1"
    static let hijau = "#3E9A4F"
    static let kuning = "#F0B429"
    // Die faces: cream lit from the top-left.
    static let dieTop = "#FFF7EC"
    static let dieLeft = "#E8D5BD"
    static let dieRight = "#CDB597"
}

/// `fill` is a hex colour, or "ink" for the text colour that follows light/dark mode.
struct Shape {
    var path: CGPath
    var fill: String
    var alpha: Double = 1
    var evenOdd = false
}

func poly(_ pts: [CGPoint]) -> CGMutablePath {
    let p = CGMutablePath()
    p.addLines(between: pts)
    p.closeSubpath()
    return p
}

func bounds(_ shapes: [Shape]) -> CGRect {
    shapes.reduce(CGRect.null) { $0.union($1.path.boundingBoxOfPath) }
}

/// Scales [shapes] uniformly so their larger side is [maxDim], centred on [center].
func fit(_ shapes: [Shape], center: CGPoint, maxDim: Double) -> [Shape] {
    let b = bounds(shapes)
    let k = maxDim / max(b.width, b.height)
    var t = CGAffineTransform(translationX: center.x, y: center.y)
        .scaledBy(x: k, y: k)
        .translatedBy(x: -b.midX, y: -b.midY)
    return shapes.map { var s = $0; s.path = $0.path.copy(using: &t)!; return s }
}

func translate(_ shapes: [Shape], dx: Double, dy: Double) -> [Shape] {
    var t = CGAffineTransform(translationX: dx, y: dy)
    return shapes.map { var s = $0; s.path = $0.path.copy(using: &t)!; return s }
}

// MARK: The die (app icon mark)

let cos30 = cos(Double.pi / 6)
func iso(_ x: Double, _ y: Double, _ z: Double) -> CGPoint {
    CGPoint(x: (x - y) * cos30, y: (x + y) * 0.5 - z)
}

/// Isometric cube whose top face shows four pips, one per player colour.
/// [mono] is the single-colour version for Android themed icons: pips are holes.
func die(mono: Bool = false) -> [Shape] {
    let top = [iso(0, 0, 1), iso(1, 0, 1), iso(1, 1, 1), iso(0, 1, 1)]
    let left = [iso(0, 1, 1), iso(1, 1, 1), iso(1, 1, 0), iso(0, 1, 0)]
    let right = [iso(1, 0, 1), iso(1, 1, 1), iso(1, 1, 0), iso(1, 0, 0)]
    let r = 0.11
    let pips = [(0.28, 0.28, Brand.merah), (0.72, 0.28, Brand.biru), (0.28, 0.72, Brand.kuning), (0.72, 0.72, Brand.hijau)]
    func pip(_ u: Double, _ v: Double) -> [CGPoint] {
        [iso(u - r, v - r, 1), iso(u + r, v - r, 1), iso(u + r, v + r, 1), iso(u - r, v + r, 1)]
    }
    // The voxel K from the wordmark, painted on the left face (y = 1), where u runs
    // left to right on screen so the letter reads the right way round.
    let cell = 0.13, u0 = 0.24, w0 = 0.175
    let k = CGMutablePath()
    for (cx, cz) in kBlocks {
        let u = u0 + Double(cx) * cell, w = w0 + Double(cz) * cell
        k.addLines(between: [iso(u, 1, w + cell), iso(u + cell, 1, w + cell), iso(u + cell, 1, w), iso(u, 1, w)])
        k.closeSubpath()
    }
    // Two wood pips across the right face (x = 1), like the "2" face of a real die.
    let two = CGMutablePath()
    for (v, w) in [(0.28, 0.72), (0.72, 0.28)] {
        two.addLines(between: [iso(1, v - r, w + r), iso(1, v + r, w + r), iso(1, v + r, w - r), iso(1, v - r, w - r)])
        two.closeSubpath()
    }
    if mono {
        let t = poly(top)
        for p in pips { t.addLines(between: pip(p.0, p.1)); t.closeSubpath() }
        let l = poly(left)
        l.addPath(k)
        return [
            Shape(path: t, fill: "#FFFFFF", evenOdd: true),
            Shape(path: l, fill: "#FFFFFF", alpha: 0.75, evenOdd: true),
            Shape(path: { let p = poly(right); p.addPath(two); return p }(), fill: "#FFFFFF", alpha: 0.5, evenOdd: true),
        ]
    }
    return [
        Shape(path: poly(top), fill: Brand.dieTop),
        Shape(path: poly(left), fill: Brand.dieLeft),
        Shape(path: poly(right), fill: Brand.dieRight),
        Shape(path: k, fill: Brand.kayu),
        Shape(path: two, fill: Brand.kayu),
    ] + pips.map { Shape(path: poly(pip($0.0, $0.1)), fill: $0.2) }
}

// MARK: The wordmark: a voxel K followed by "rida"

/// Blocks as (column, row from the bottom); 5 rows tall = the cap height of "rida".
let kBlocks = [(0, 0), (0, 1), (0, 2), (0, 3), (0, 4), (1, 2), (2, 3), (3, 4), (2, 1), (3, 0)]
let depth = 0.32

func wordmark(font url: URL) -> [Shape] {
    var shapes: [Shape] = []
    // Oblique view: square front faces, depth going up-right. Drawing in order of
    // column + row lets nearer front faces cover the depth faces behind them.
    for (x, z) in kBlocks.sorted(by: { ($0.0 + $0.1, $0.1) < ($1.0 + $1.1, $1.1) }) {
        let l = Double(x), r = l + 1, t = -Double(z) - 1, b = -Double(z), d = depth
        shapes.append(Shape(path: poly([CGPoint(x: l, y: t), CGPoint(x: r, y: t), CGPoint(x: r + d, y: t - d), CGPoint(x: l + d, y: t - d)]), fill: Brand.kayuTerang))
        shapes.append(Shape(path: poly([CGPoint(x: r, y: t), CGPoint(x: r, y: b), CGPoint(x: r + d, y: b - d), CGPoint(x: r + d, y: t - d)]), fill: Brand.kayuGelap))
        shapes.append(Shape(path: poly([CGPoint(x: l, y: t), CGPoint(x: r, y: t), CGPoint(x: r, y: b), CGPoint(x: l, y: b)]), fill: Brand.kayu))
    }

    guard let descs = CTFontManagerCreateFontDescriptorsFromURL(url as CFURL) as? [CTFontDescriptor], let base = descs.first else {
        fatalError("Cannot read font at \(url.path)")
    }
    let wght = NSNumber(value: 0x7767_6874) // 'wght'
    let desc = CTFontDescriptorCreateCopyWithAttributes(base, [kCTFontVariationAttribute: [wght: 800]] as CFDictionary)
    let probe = CTFontCreateWithFontDescriptor(desc, 1000, nil)
    let size = 5.0 * 1000 / CTFontGetCapHeight(probe)
    let font = CTFontCreateWithFontDescriptor(desc, size, nil)
    let line = CTLineCreateWithAttributedString(
        NSAttributedString(string: "rida", attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font])
    )
    let text = CGMutablePath()
    let x0 = 4 + depth + 0.45
    for run in CTLineGetGlyphRuns(line) as! [CTRun] {
        let runFont = (CTRunGetAttributes(run) as NSDictionary)[kCTFontAttributeName] as! CTFont
        let n = CTRunGetGlyphCount(run)
        var glyphs = [CGGlyph](repeating: 0, count: n)
        var positions = [CGPoint](repeating: .zero, count: n)
        CTRunGetGlyphs(run, CFRange(location: 0, length: 0), &glyphs)
        CTRunGetPositions(run, CFRange(location: 0, length: 0), &positions)
        for i in 0..<n {
            guard let g = CTFontCreatePathForGlyph(runFont, glyphs[i], nil) else { continue }
            // Glyphs are y-up; everything here is y-down with the baseline at 0.
            text.addPath(g, transform: CGAffineTransform(a: 1, b: 0, c: 0, d: -1, tx: x0 + positions[i].x, ty: -positions[i].y))
        }
    }
    shapes.append(Shape(path: text, fill: "ink"))

    let b = bounds(shapes)
    let pad = 0.15
    return translate(shapes, dx: -b.minX + pad, dy: -b.minY + pad)
}

func size(of shapes: [Shape], pad: Double = 0.15) -> CGSize {
    let b = bounds(shapes)
    return CGSize(width: b.maxX + pad, height: b.maxY + pad)
}

// MARK: Writers

func num(_ v: CGFloat) -> String {
    var s = String(format: "%.3f", Double(v))
    while s.hasSuffix("0") { s.removeLast() }
    if s.hasSuffix(".") { s.removeLast() }
    return s == "-0" ? "0" : s
}

func pathData(_ p: CGPath) -> String {
    var out: [String] = []
    p.applyWithBlock { e in
        let pts = e.pointee.points
        switch e.pointee.type {
        case .moveToPoint: out.append("M\(num(pts[0].x)) \(num(pts[0].y))")
        case .addLineToPoint: out.append("L\(num(pts[0].x)) \(num(pts[0].y))")
        case .addQuadCurveToPoint: out.append("Q\(num(pts[0].x)) \(num(pts[0].y)) \(num(pts[1].x)) \(num(pts[1].y))")
        case .addCurveToPoint: out.append("C\(num(pts[0].x)) \(num(pts[0].y)) \(num(pts[1].x)) \(num(pts[1].y)) \(num(pts[2].x)) \(num(pts[2].y))")
        case .closeSubpath: out.append("Z")
        @unknown default: break
        }
    }
    return out.joined()
}

func svgPaths(_ shapes: [Shape], ink: String) -> String {
    shapes.map { s in
        var a = "<path d=\"\(pathData(s.path))\" fill=\"\(s.fill == "ink" ? ink : s.fill)\""
        if s.alpha < 1 { a += " fill-opacity=\"\(num(s.alpha))\"" }
        if s.evenOdd { a += " fill-rule=\"evenodd\"" }
        return a + "/>"
    }.joined(separator: "\n  ")
}

func svg(_ shapes: [Shape], size: CGSize, background: String? = nil, radius: Double = 0, ink: String = Brand.ink, label: String = "Krida") -> String {
    let w = num(size.width), h = num(size.height)
    let bg = background.map { "<rect width=\"\(w)\" height=\"\(h)\" rx=\"\(num(radius))\" fill=\"\($0)\"/>\n  " } ?? ""
    return """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 \(w) \(h)" role="img" aria-label="\(label)">
      \(bg)\(svgPaths(shapes, ink: ink))
    </svg>

    """
}

func androidVector(_ shapes: [Shape], size: CGSize, heightDp: Double, ink: String = "@color/krida_ink") -> String {
    let widthDp = (heightDp * size.width / size.height).rounded()
    let paths = shapes.map { s in
        var a = "    <path\n        android:fillColor=\"\(s.fill == "ink" ? ink : s.fill)\"\n        android:pathData=\"\(pathData(s.path))\""
        if s.alpha < 1 { a += "\n        android:fillAlpha=\"\(num(s.alpha))\"" }
        if s.evenOdd { a += "\n        android:fillType=\"evenOdd\"" }
        return a + " />"
    }.joined(separator: "\n")
    return """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- Generated by scripts/brand.swift. Do not edit by hand. -->
    <vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="\(num(widthDp))dp"
        android:height="\(num(heightDp))dp"
        android:viewportWidth="\(num(size.width))"
        android:viewportHeight="\(num(size.height))">
    \(paths)
    </vector>

    """
}

func cgColor(_ hex: String, alpha: Double = 1) -> CGColor {
    let v = UInt32(hex.dropFirst(), radix: 16)!
    return CGColor(
        srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
        blue: CGFloat(v & 0xFF) / 255, alpha: CGFloat(alpha)
    )
}

/// Square PNG. With a background it is opaque (the App Store rejects alpha in the main
/// icon); without one it is transparent, as iOS wants for the dark and tinted variants.
func png(_ shapes: [Shape], viewBox: Double, pixels: Int, background: String?, to path: String) {
    let ctx = CGContext(
        data: nil, width: pixels, height: pixels, bitsPerComponent: 8, bytesPerRow: 0,
        space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: (background == nil ? CGImageAlphaInfo.premultipliedLast : .noneSkipLast).rawValue
    )!
    let k = Double(pixels) / viewBox
    ctx.translateBy(x: 0, y: CGFloat(pixels))
    ctx.scaleBy(x: k, y: -k)
    if let background {
        ctx.setFillColor(cgColor(background))
        ctx.fill(CGRect(x: 0, y: 0, width: viewBox, height: viewBox))
    }
    for s in shapes {
        ctx.addPath(s.path)
        ctx.setFillColor(cgColor(s.fill, alpha: s.alpha))
        ctx.fillPath(using: s.evenOdd ? .evenOdd : .winding)
    }
    let url = URL(fileURLWithPath: path)
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
    guard CGImageDestinationFinalize(dest) else { fatalError("Cannot write \(path)") }
}

func write(_ text: String, to path: String) {
    let url = URL(fileURLWithPath: path)
    try! FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try! text.write(to: url, atomically: true, encoding: .utf8)
}

func json(_ value: Any) -> String {
    let data = try! JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys])
    return String(data: data, encoding: .utf8)! + "\n"
}

func colorset(_ light: String, _ dark: String) -> String {
    func comp(_ hex: String) -> [String: Any] {
        let v = UInt32(hex.dropFirst(), radix: 16)!
        return ["color-space": "srgb", "components": [
            "red": String(format: "0x%02X", (v >> 16) & 0xFF), "green": String(format: "0x%02X", (v >> 8) & 0xFF),
            "blue": String(format: "0x%02X", v & 0xFF), "alpha": "1.000",
        ]]
    }
    return json([
        "colors": [
            ["idiom": "universal", "color": comp(light)],
            ["idiom": "universal", "color": comp(dark), "appearances": [["appearance": "luminosity", "value": "dark"]]],
        ],
        "info": ["author": "xcode", "version": 1],
    ])
}

// MARK: Main

guard CommandLine.arguments.count == 2 else {
    FileHandle.standardError.write("usage: swift scripts/brand.swift path/to/Baloo2[wght].ttf\n".data(using: .utf8)!)
    exit(1)
}
let fontURL = URL(fileURLWithPath: CommandLine.arguments[1])

// App icon in a 100-unit square: die at 58% of the side (iOS squircle and store listings).
let iconDie = fit(die(), center: CGPoint(x: 50, y: 50), maxDim: 58)
let square = CGSize(width: 100, height: 100)
let mark = wordmark(font: fontURL)
let markSize = size(of: mark)

// Masters for docs and design tools.
write(svg(iconDie, size: square, background: Brand.accent), to: "docs/brand/icon.svg")
write(svg(fit(die(), center: CGPoint(x: 50, y: 50), maxDim: 96), size: square), to: "docs/brand/die.svg")
write(svg(mark, size: markSize, ink: Brand.ink), to: "docs/brand/wordmark.svg")
write(svg(mark, size: markSize, ink: Brand.inkDark), to: "docs/brand/wordmark-dark.svg")

// Web.
write(svg(fit(die(), center: CGPoint(x: 50, y: 50), maxDim: 68), size: square, background: Brand.accent, radius: 22), to: "web/public/favicon.svg")
png(iconDie, viewBox: 100, pixels: 180, background: Brand.accent, to: "web/public/apple-touch-icon.png")
write("""
---
// Generated by scripts/brand.swift. Do not edit by hand.
// The "rida" letters use currentColor, so the wordmark follows the text colour.
interface Props {
  height?: number;
}
const { height = 32 } = Astro.props;
const width = Math.round((height * \(num(markSize.width))) / \(num(markSize.height)));
---

<svg class="wordmark" width={width} height={height} viewBox="0 0 \(num(markSize.width)) \(num(markSize.height))" role="img" aria-label="Krida">
  \(svgPaths(mark, ink: "currentColor"))
</svg>

""", to: "web/src/components/Wordmark.astro")

// iOS asset catalog.
let assets = "ios/Krida/Assets.xcassets"
write(json(["info": ["author": "xcode", "version": 1]]), to: "\(assets)/Contents.json")
png(iconDie, viewBox: 100, pixels: 1024, background: Brand.accent, to: "\(assets)/AppIcon.appiconset/AppIcon.png")
// iOS 18 home screen variants: dark (system draws the dark backdrop) and tinted (grayscale).
png(iconDie, viewBox: 100, pixels: 1024, background: nil, to: "\(assets)/AppIcon.appiconset/AppIcon-dark.png")
png(fit(die(mono: true), center: CGPoint(x: 50, y: 50), maxDim: 58), viewBox: 100, pixels: 1024, background: nil, to: "\(assets)/AppIcon.appiconset/AppIcon-tinted.png")
func icon(_ file: String, _ appearance: String?) -> [String: Any] {
    var i: [String: Any] = ["filename": file, "idiom": "universal", "platform": "ios", "size": "1024x1024"]
    if let appearance { i["appearances"] = [["appearance": "luminosity", "value": appearance]] }
    return i
}
write(json([
    "images": [icon("AppIcon.png", nil), icon("AppIcon-dark.png", "dark"), icon("AppIcon-tinted.png", "tinted")],
    "info": ["author": "xcode", "version": 1],
]), to: "\(assets)/AppIcon.appiconset/Contents.json")
write(colorset(Brand.accent, Brand.accentDark), to: "\(assets)/AccentColor.colorset/Contents.json")
write(svg(mark, size: markSize, ink: Brand.ink), to: "\(assets)/Wordmark.imageset/wordmark.svg")
write(svg(mark, size: markSize, ink: Brand.inkDark), to: "\(assets)/Wordmark.imageset/wordmark-dark.svg")
write(json([
    "images": [
        ["filename": "wordmark.svg", "idiom": "universal"],
        ["filename": "wordmark-dark.svg", "idiom": "universal", "appearances": [["appearance": "luminosity", "value": "dark"]]],
    ],
    "info": ["author": "xcode", "version": 1],
    "properties": ["preserves-vector-representation": true],
]), to: "\(assets)/Wordmark.imageset/Contents.json")

// Android: adaptive icon (108 dp canvas, ~72 dp visible) plus the wordmark.
let res = "androidApp/src/main/res"
let canvas = CGSize(width: 108, height: 108)
write(androidVector(fit(die(), center: CGPoint(x: 54, y: 54), maxDim: 46), size: canvas, heightDp: 108), to: "\(res)/drawable/ic_launcher_foreground.xml")
write(androidVector(fit(die(mono: true), center: CGPoint(x: 54, y: 54), maxDim: 46), size: canvas, heightDp: 108), to: "\(res)/drawable/ic_launcher_monochrome.xml")
let adaptive = """
<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/brand.swift. Do not edit by hand. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>

"""
write(adaptive, to: "\(res)/mipmap-anydpi-v26/ic_launcher.xml")
write(adaptive, to: "\(res)/mipmap-anydpi-v26/ic_launcher_round.xml")
write(androidVector(mark, size: markSize, heightDp: 40), to: "\(res)/drawable/krida_wordmark.xml")

func colorsXml(_ entries: [(String, String)]) -> String {
    """
    <?xml version="1.0" encoding="utf-8"?>
    <!-- Generated by scripts/brand.swift. Do not edit by hand. -->
    <resources>
    \(entries.map { "    <color name=\"\($0.0)\">\($0.1)</color>" }.joined(separator: "\n"))
    </resources>

    """
}
write(colorsXml([
    ("ic_launcher_background", Brand.accent),
    ("krida_accent", Brand.accent), ("krida_ink", Brand.ink), ("krida_background", Brand.krem),
    ("krida_kayu_terang", Brand.kayuTerang), ("krida_kayu", Brand.kayu), ("krida_kayu_gelap", Brand.kayuGelap),
    ("krida_merah", Brand.merah), ("krida_biru", Brand.biru), ("krida_hijau", Brand.hijau), ("krida_kuning", Brand.kuning),
]), to: "\(res)/values/brand_colors.xml")
write(colorsXml([
    ("krida_accent", Brand.accentDark), ("krida_ink", Brand.inkDark), ("krida_background", Brand.malam),
]), to: "\(res)/values-night/brand_colors.xml")

print("Brand assets written.")
