import SwiftUI

/// Home card preview: deep night, stars, one moon, and a howling wolf silhouette
/// (mockup `#1A2332`). Same picture idea as Android’s `WerewolfPreview`.
struct WerewolfPreview: View {
    let onReady: () -> Void

    var body: some View {
        Canvas { context, size in
            Self.draw(context: context, size: size)
        }
        .onAppear { onReady() }
        .accessibilityHidden(true)
    }

    private static func draw(context: GraphicsContext, size: CGSize) {
        let w = size.width
        let h = size.height
        // Night sky.
        context.fill(
            Path(CGRect(origin: .zero, size: size)),
            with: .linearGradient(
                Gradient(colors: [
                    Color(hex: 0x0F1520),
                    Color(hex: 0x1A2332),
                    Color(hex: 0x243044),
                ]),
                startPoint: CGPoint(x: 0, y: 0),
                endPoint: CGPoint(x: w, y: h)
            )
        )
        // Soft ground ridge so the wolf isn’t floating in empty space.
        var ground = Path()
        ground.move(to: CGPoint(x: 0, y: h * 0.78))
        ground.addCurve(
            to: CGPoint(x: w, y: h * 0.72),
            control1: CGPoint(x: w * 0.35, y: h * 0.9),
            control2: CGPoint(x: w * 0.7, y: h * 0.68)
        )
        ground.addLine(to: CGPoint(x: w, y: h))
        ground.addLine(to: CGPoint(x: 0, y: h))
        ground.closeSubpath()
        context.fill(ground, with: .color(Color(hex: 0x0A1018).opacity(0.85)))

        // Stars.
        let stars: [(CGFloat, CGFloat, CGFloat)] = [
            (0.12, 0.18, 1.2), (0.28, 0.12, 0.9), (0.42, 0.22, 1.4),
            (0.55, 0.1, 0.8), (0.68, 0.28, 1.1), (0.22, 0.35, 0.7),
            (0.48, 0.38, 1.0), (0.9, 0.42, 0.9),
        ]
        for (sx, sy, r) in stars {
            let rect = CGRect(x: w * sx, y: h * sy, width: r * 2, height: r * 2)
            context.fill(Path(ellipseIn: rect.offsetBy(dx: -r, dy: -r)), with: .color(Color(hex: 0xE8EEF5).opacity(0.55)))
        }

        // One moon + soft glow (no second SF Symbol moon).
        let moonCenter = CGPoint(x: w * 0.78, y: h * 0.22)
        let moonR = min(w, h) * 0.11
        context.fill(
            Path(ellipseIn: CGRect(x: moonCenter.x - moonR * 1.8, y: moonCenter.y - moonR * 1.8, width: moonR * 3.6, height: moonR * 3.6)),
            with: .color(Color(hex: 0xF4E4BC).opacity(0.12))
        )
        context.fill(
            Path(ellipseIn: CGRect(x: moonCenter.x - moonR, y: moonCenter.y - moonR, width: moonR * 2, height: moonR * 2)),
            with: .color(Color(hex: 0xF4E4BC).opacity(0.95))
        )
        // Crescent bite for a clearer moon shape.
        context.fill(
            Path(ellipseIn: CGRect(
                x: moonCenter.x - moonR * 0.35,
                y: moonCenter.y - moonR * 1.05,
                width: moonR * 1.7,
                height: moonR * 1.7
            )),
            with: .color(Color(hex: 0x1A2332))
        )

        // Howling wolf silhouette — mid-tone so it reads on the night sky.
        context.fill(wolfPath(w: w, h: h), with: .color(Color(hex: 0x9AA8BC)))
        context.stroke(wolfPath(w: w, h: h), with: .color(Color(hex: 0xC5D0E0).opacity(0.35)), lineWidth: 1)
    }

    /// Side-view wolf, head tilted toward the moon.
    private static func wolfPath(w: CGFloat, h: CGFloat) -> Path {
        var p = Path()
        // Hind haunch.
        p.move(to: CGPoint(x: w * 0.18, y: h * 0.72))
        p.addCurve(
            to: CGPoint(x: w * 0.22, y: h * 0.52),
            control1: CGPoint(x: w * 0.12, y: h * 0.68),
            control2: CGPoint(x: w * 0.14, y: h * 0.56)
        )
        // Back up to withers.
        p.addCurve(
            to: CGPoint(x: w * 0.42, y: h * 0.46),
            control1: CGPoint(x: w * 0.28, y: h * 0.48),
            control2: CGPoint(x: w * 0.36, y: h * 0.44)
        )
        // Neck and head rising toward the moon.
        p.addCurve(
            to: CGPoint(x: w * 0.52, y: h * 0.28),
            control1: CGPoint(x: w * 0.46, y: h * 0.42),
            control2: CGPoint(x: w * 0.48, y: h * 0.32)
        )
        // Ear.
        p.addLine(to: CGPoint(x: w * 0.5, y: h * 0.18))
        p.addLine(to: CGPoint(x: w * 0.56, y: h * 0.26))
        // Second ear tip.
        p.addLine(to: CGPoint(x: w * 0.58, y: h * 0.16))
        p.addLine(to: CGPoint(x: w * 0.62, y: h * 0.27))
        // Snout open (howl).
        p.addCurve(
            to: CGPoint(x: w * 0.72, y: h * 0.3),
            control1: CGPoint(x: w * 0.66, y: h * 0.24),
            control2: CGPoint(x: w * 0.7, y: h * 0.26)
        )
        p.addLine(to: CGPoint(x: w * 0.66, y: h * 0.34))
        p.addCurve(
            to: CGPoint(x: w * 0.58, y: h * 0.4),
            control1: CGPoint(x: w * 0.64, y: h * 0.38),
            control2: CGPoint(x: w * 0.6, y: h * 0.4)
        )
        // Chest and front leg.
        p.addCurve(
            to: CGPoint(x: w * 0.48, y: h * 0.72),
            control1: CGPoint(x: w * 0.54, y: h * 0.5),
            control2: CGPoint(x: w * 0.52, y: h * 0.64)
        )
        p.addLine(to: CGPoint(x: w * 0.44, y: h * 0.72))
        // Belly to hind foot.
        p.addCurve(
            to: CGPoint(x: w * 0.28, y: h * 0.72),
            control1: CGPoint(x: w * 0.4, y: h * 0.62),
            control2: CGPoint(x: w * 0.32, y: h * 0.62)
        )
        p.addLine(to: CGPoint(x: w * 0.24, y: h * 0.72))
        // Tail tip.
        p.addCurve(
            to: CGPoint(x: w * 0.18, y: h * 0.72),
            control1: CGPoint(x: w * 0.2, y: h * 0.78),
            control2: CGPoint(x: w * 0.16, y: h * 0.76)
        )
        p.closeSubpath()
        return p
    }
}
