import SwiftUI
import simd

/// Backdrop screen with the Krida die turning around and the wordmark's letters filling from left
/// to right like a progress bar. Shown at app start until the menu's board snapshot is ready, and
/// before each game until the board's first frames are on screen. It swallows taps so nothing
/// underneath reacts. Same as Android.
struct LoadingScreen: View {
    @Environment(\.uiScale) private var s
    @State private var start = Date()

    /// One fill of the letters, then a short pause with them full.
    private static let fill: TimeInterval = 1.8

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSince(start)
            let progress = min(1, t.truncatingRemainder(dividingBy: Self.fill) / (Self.fill * 0.85))
            VStack(spacing: 24 * s) {
                LoadingIndicator(size: 130)
                ProgressWord(progress: progress, height: 52 * s)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.backdropGradient)
        .contentShape(Rectangle())
        .onTapGesture {}
        .ignoresSafeArea()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Loading", bundle: .app))
    }
}

/// The startup cube/K animation, reused by every compact waiting indicator.
struct LoadingIndicator: View {
    var size: CGFloat = 64
    @Environment(\.uiScale) private var s
    @State private var start = Date()

    private static let turn: TimeInterval = 2.4

    var body: some View {
        TimelineView(.animation) { context in
            let t = context.date.timeIntervalSince(start)
            let angle = t.truncatingRemainder(dividingBy: Self.turn) / Self.turn * 2 * .pi
            SpinningDie(angle: angle)
        }
        .frame(width: size * s, height: size * s)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "Loading", bundle: .app))
    }
}

/// The wordmark's "Krida" letters (the asset minus its die), dimmed, with the same letters in
/// full color revealed from left to right.
private struct ProgressWord: View {
    let progress: Double
    let height: CGFloat

    /// The wordmark's viewBox is 27.255 x 7.3 and its letters start at x = 7.6, after the die.
    private static let aspect = 27.255 / 7.3
    private static let letterShare = 1 - 7.6 / 27.255

    var body: some View {
        let width = height * Self.aspect * Self.letterShare
        letters(width: width)
            .opacity(0.2)
            .overlay(alignment: .leading) {
                letters(width: width)
                    .mask(alignment: .leading) { Rectangle().frame(width: width * progress) }
            }
    }

    private func letters(width: CGFloat) -> some View {
        Image("Wordmark").resizable()
            .frame(width: height * Self.aspect, height: height)
            .frame(width: width, height: height, alignment: .trailing)
            .clipped()
    }
}

/// The die of the Krida logo as a real cube, so it turns around its vertical axis: cream faces,
/// four colored tiles on top, the voxel "k", and pip faces on the sides.
private struct SpinningDie: View {
    /// Turn around the vertical axis, in radians; 0 is the logo's pose.
    let angle: Double

    private typealias V = SIMD3<Double>
    private struct Mark { let u0, v0, u1, v1: Double; let color: Color }
    private struct Face { let o: V, u: V, v: V, n: V; let marks: [Mark]; let top: Bool }

    private static let brown = Color(hex: 0x8F552A)
    private static let cell = 0.13
    private static let voxelK: [Mark] = [
        (0.24, 0.695), (0.24, 0.565), (0.24, 0.435), (0.24, 0.305), (0.24, 0.175),
        (0.37, 0.435), (0.5, 0.305), (0.63, 0.175), (0.5, 0.565), (0.63, 0.695),
    ].map { Mark(u0: $0.0, v0: $0.1, u1: $0.0 + cell, v1: $0.1 + cell, color: brown) }
    private static let square = [Mark(u0: 0.33, v0: 0.33, u1: 0.67, v1: 0.67, color: brown)]
    private static let two = pips([(0.23, 0.23), (0.77, 0.77)])
    private static let six = pips([
        (0.23, 0.23), (0.77, 0.23), (0.23, 0.5),
        (0.77, 0.5), (0.23, 0.77), (0.77, 0.77),
    ])
    private static func pips(_ centers: [(Double, Double)]) -> [Mark] {
        centers.map { u, v in
            Mark(u0: u - 0.08, v0: v - 0.08, u1: u + 0.08, v1: v + 0.08, color: brown)
        }
    }
    private static let tiles = [
        Mark(u0: 0.17, v0: 0.17, u1: 0.39, v1: 0.39, color: Color(hex: 0xD6453D)),
        Mark(u0: 0.61, v0: 0.17, u1: 0.83, v1: 0.39, color: Color(hex: 0x2F6FD1)),
        Mark(u0: 0.17, v0: 0.61, u1: 0.39, v1: 0.83, color: Color(hex: 0xF0B429)),
        Mark(u0: 0.61, v0: 0.61, u1: 0.83, v1: 0.83, color: Color(hex: 0x3E9A4F)),
    ]
    private static let faces = [
        Face(o: V(-1, 1, -1), u: V(2, 0, 0), v: V(0, 0, 2), n: V(0, 1, 0), marks: tiles, top: true),
        Face(o: V(-1, 1, 1), u: V(2, 0, 0), v: V(0, -2, 0), n: V(0, 0, 1), marks: voxelK, top: false),
        Face(o: V(1, 1, 1), u: V(0, 0, -2), v: V(0, -2, 0), n: V(1, 0, 0), marks: square, top: false),
        Face(o: V(1, 1, -1), u: V(-2, 0, 0), v: V(0, -2, 0), n: V(0, 0, -1), marks: two, top: false),
        Face(o: V(-1, 1, -1), u: V(0, 0, 2), v: V(0, -2, 0), n: V(-1, 0, 0), marks: six, top: false),
    ]
    private static let topColor = (1.0, 0xF7 / 255.0, 0xEC / 255.0)
    private static let lightSide = (0xE8 / 255.0, 0xD5 / 255.0, 0xBD / 255.0)
    private static let darkSide = (0xCD / 255.0, 0xB5 / 255.0, 0x97 / 255.0)
    /// The logo's isometric view: the cube seen from 35.26 degrees above.
    private static let tilt = 0.6155

    var body: some View {
        Canvas { ctx, size in
            let scale = min(size.width, size.height) / 3.2
            let center = CGPoint(x: size.width / 2, y: size.height / 2 + scale * 0.1)
            let theta = angle - .pi / 4
            func rotate(_ p: V) -> V {
                let c = cos(theta), s = sin(theta), ct = cos(Self.tilt), st = sin(Self.tilt)
                let x = p.x * c + p.z * s, z = -p.x * s + p.z * c
                return V(x, p.y * ct - z * st, p.y * st + z * ct)
            }
            func polygon(_ pts: [V]) -> Path {
                var path = Path()
                path.addLines(pts.map { p in
                    let r = rotate(p)
                    return CGPoint(x: center.x + r.x * scale, y: center.y - r.y * scale)
                })
                path.closeSubpath()
                return path
            }
            for f in Self.faces {
                let n = rotate(f.n)
                guard n.z > 0.001 else { continue }
                let rgb: (Double, Double, Double)
                if f.top {
                    rgb = Self.topColor
                } else {
                    let t = min(1, max(0, 0.5 - n.x))
                    rgb = (
                        Self.darkSide.0 + (Self.lightSide.0 - Self.darkSide.0) * t,
                        Self.darkSide.1 + (Self.lightSide.1 - Self.darkSide.1) * t,
                        Self.darkSide.2 + (Self.lightSide.2 - Self.darkSide.2) * t)
                }
                func at(_ a: Double, _ b: Double) -> V { f.o + f.u * a + f.v * b }
                let outline = polygon([at(0, 0), at(1, 0), at(1, 1), at(0, 1)])
                let color = Color(red: rgb.0, green: rgb.1, blue: rgb.2)
                ctx.fill(outline, with: .color(color))
                ctx.stroke(outline, with: .color(color), lineWidth: 0.6)
                for m in f.marks {
                    ctx.fill(polygon([at(m.u0, m.v0), at(m.u1, m.v0), at(m.u1, m.v1), at(m.u0, m.v1)]), with: .color(m.color))
                }
            }
        }
    }
}
