import SwiftUI

/// Home card preview: night sky and a black werewolf shadow against the moon
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
        // Ground so the shadow stands on something.
        var ground = Path()
        ground.move(to: CGPoint(x: 0, y: h * 0.82))
        ground.addCurve(
            to: CGPoint(x: w, y: h * 0.78),
            control1: CGPoint(x: w * 0.4, y: h * 0.92),
            control2: CGPoint(x: w * 0.7, y: h * 0.76)
        )
        ground.addLine(to: CGPoint(x: w, y: h))
        ground.addLine(to: CGPoint(x: 0, y: h))
        ground.closeSubpath()
        context.fill(ground, with: .color(Color(hex: 0x070B12)))

        // A few dim stars only — the moon + shadow do the work.
        for (sx, sy, r) in [(0.1, 0.14, 1.0), (0.22, 0.28, 0.7), (0.92, 0.18, 0.9), (0.86, 0.48, 0.6)] as [(CGFloat, CGFloat, CGFloat)] {
            context.fill(
                Path(ellipseIn: CGRect(x: w * sx - r, y: h * sy - r, width: r * 2, height: r * 2)),
                with: .color(Color(hex: 0xE8EEF5).opacity(0.4))
            )
        }

        // Large moon — backlight for the shadow.
        let moonC = CGPoint(x: w * 0.62, y: h * 0.38)
        let moonR = min(w, h) * 0.34
        context.fill(
            Path(ellipseIn: CGRect(x: moonC.x - moonR * 1.25, y: moonC.y - moonR * 1.25, width: moonR * 2.5, height: moonR * 2.5)),
            with: .color(Color(hex: 0xF4E4BC).opacity(0.14))
        )
        context.fill(
            Path(ellipseIn: CGRect(x: moonC.x - moonR, y: moonC.y - moonR, width: moonR * 2, height: moonR * 2)),
            with: .color(Color(hex: 0xF4E4BC).opacity(0.92))
        )

        // Black werewolf shadow (bipedal, howling) in front of the moon.
        let shadow = werewolfShadow(w: w, h: h)
        context.fill(shadow, with: .color(Color.black.opacity(0.92)))
        // Soft foot shadow on the ground.
        context.fill(
            Path(ellipseIn: CGRect(x: w * 0.22, y: h * 0.78, width: w * 0.36, height: h * 0.06)),
            with: .color(Color.black.opacity(0.45))
        )
    }

    /// Side-facing bipedal werewolf: pointed ears, snout up, hunched back, clawed arms, digitigrade legs.
    private static func werewolfShadow(w: CGFloat, h: CGFloat) -> Path {
        var p = Path()
        // Back foot.
        p.move(to: CGPoint(x: w * 0.28, y: h * 0.82))
        p.addLine(to: CGPoint(x: w * 0.24, y: h * 0.82))
        // Hind leg up to hip.
        p.addCurve(
            to: CGPoint(x: w * 0.30, y: h * 0.58),
            control1: CGPoint(x: w * 0.22, y: h * 0.74),
            control2: CGPoint(x: w * 0.24, y: h * 0.64)
        )
        // Hunched back / mane.
        p.addCurve(
            to: CGPoint(x: w * 0.42, y: h * 0.36),
            control1: CGPoint(x: w * 0.32, y: h * 0.5),
            control2: CGPoint(x: w * 0.34, y: h * 0.4)
        )
        // Rear ear.
        p.addLine(to: CGPoint(x: w * 0.40, y: h * 0.18))
        p.addLine(to: CGPoint(x: w * 0.46, y: h * 0.30))
        // Front ear.
        p.addLine(to: CGPoint(x: w * 0.48, y: h * 0.14))
        p.addLine(to: CGPoint(x: w * 0.54, y: h * 0.28))
        // Snout howling up toward the moon.
        p.addCurve(
            to: CGPoint(x: w * 0.68, y: h * 0.22),
            control1: CGPoint(x: w * 0.58, y: h * 0.22),
            control2: CGPoint(x: w * 0.64, y: h * 0.18)
        )
        p.addLine(to: CGPoint(x: w * 0.62, y: h * 0.30))
        // Jaw / throat.
        p.addCurve(
            to: CGPoint(x: w * 0.52, y: h * 0.38),
            control1: CGPoint(x: w * 0.6, y: h * 0.34),
            control2: CGPoint(x: w * 0.55, y: h * 0.38)
        )
        // Chest and raised clawed arm.
        p.addCurve(
            to: CGPoint(x: w * 0.58, y: h * 0.48),
            control1: CGPoint(x: w * 0.54, y: h * 0.42),
            control2: CGPoint(x: w * 0.58, y: h * 0.44)
        )
        p.addLine(to: CGPoint(x: w * 0.70, y: h * 0.42)) // forearm
        p.addLine(to: CGPoint(x: w * 0.74, y: h * 0.46)) // claw tip
        p.addLine(to: CGPoint(x: w * 0.68, y: h * 0.48))
        p.addLine(to: CGPoint(x: w * 0.72, y: h * 0.52))
        p.addLine(to: CGPoint(x: w * 0.64, y: h * 0.52))
        p.addLine(to: CGPoint(x: w * 0.56, y: h * 0.54))
        // Belly to front knee.
        p.addCurve(
            to: CGPoint(x: w * 0.48, y: h * 0.70),
            control1: CGPoint(x: w * 0.54, y: h * 0.6),
            control2: CGPoint(x: w * 0.52, y: h * 0.66)
        )
        // Front foot / claws.
        p.addLine(to: CGPoint(x: w * 0.52, y: h * 0.82))
        p.addLine(to: CGPoint(x: w * 0.46, y: h * 0.82))
        p.addLine(to: CGPoint(x: w * 0.44, y: h * 0.74))
        // Inner legs back to starting foot.
        p.addCurve(
            to: CGPoint(x: w * 0.34, y: h * 0.72),
            control1: CGPoint(x: w * 0.42, y: h * 0.68),
            control2: CGPoint(x: w * 0.38, y: h * 0.68)
        )
        p.addLine(to: CGPoint(x: w * 0.28, y: h * 0.82))
        p.closeSubpath()
        return p
    }
}
