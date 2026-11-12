import Shared
import SwiftUI
import UIKit

/// Circular avatar: a drawn shape+colour mix, a Google picture (only from the allowed host),
/// or the neutral person icon. Drawn in code; no image assets for the shapes. Same as
/// Android (`account/AvatarBadge.kt`).
struct AvatarBadge: View {
    let avatar: String?
    var accountId: String? = nil
    var picture: String? = nil
    let size: CGFloat
    var ring: Bool = false
    @Environment(\.uiScale) private var s

    var body: some View {
        let side = size * s
        Group {
            if Avatar.shared.isGooglePictureUrl(value: avatar) || (avatar == Avatar.shared.GOOGLE && Avatar.shared.isGooglePictureUrl(value: picture)) {
                GoogleAvatar(url: (Avatar.shared.isGooglePictureUrl(value: avatar) ? avatar : picture) ?? "", size: side)
            } else if let mix = Avatar.shared.parseMix(value: avatar)
                ?? accountId.map({ Avatar.shared.defaultMix(accountId: $0) }) {
                DrawnAvatar(mix: mix, size: side)
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: side * 0.44, weight: .bold))
                    .foregroundStyle(Theme.muted)
                    .frame(width: side, height: side)
                    .background(Theme.chip, in: Circle())
            }
        }
        .overlay {
            if ring {
                Circle().stroke(Theme.accent, lineWidth: 3 * s)
            }
        }
    }
}

extension Array where Element == PlayerInfo {
    /// The avatar of the player in `seat`, or nil for bots and players without one.
    func avatar(of seat: Int) -> String? {
        guard let p = first(where: { Int($0.seat) == seat }), !p.bot else { return nil }
        return p.avatar
    }
}

/// In-game seat marker: the player's avatar inside a ring of the seat `color`, or the plain
/// colour dot when the seat has no avatar (signed out, bot, pass-and-play). `size` is
/// unscaled. Same as Android `SeatAvatar`.
struct SeatAvatar: View {
    let avatar: String?
    let color: Color
    let size: CGFloat
    @Environment(\.uiScale) private var s

    var body: some View {
        if let avatar {
            let ring: CGFloat = size < 24 ? 2 : 3
            AvatarBadge(avatar: avatar, size: size - ring * 2)
                .padding(ring * s)
                .background(color, in: Circle())
        } else {
            Circle().fill(color).frame(width: size * s, height: size * s)
        }
    }
}

private struct DrawnAvatar: View {
    let mix: Avatar.Mix
    let size: CGFloat

    var body: some View {
        Canvas { context, canvasSize in
            let r = min(canvasSize.width, canvasSize.height) / 2
            context.translateBy(x: canvasSize.width / 2, y: canvasSize.height / 2)
            context.scaleBy(x: r / 22, y: r / 22)
            AvatarShapes.draw(mix.shapeKind, fg: avatarColor(mix.symbolArgb), bg: avatarColor(mix.backgroundArgb), in: &context)
        }
        .frame(width: size * 0.72, height: size * 0.72)
        .frame(width: size, height: size)
        .background(avatarColor(mix.backgroundArgb), in: Circle())
        .clipShape(Circle())
    }
}

/// ARGB int from shared `Avatar.COLOURS` → SwiftUI colour.
func avatarColor(_ argb: Int32) -> Color {
    let u = UInt32(bitPattern: argb)
    return Color(
        .sRGB,
        red: Double((u >> 16) & 0xff) / 255,
        green: Double((u >> 8) & 0xff) / 255,
        blue: Double(u & 0xff) / 255,
        opacity: Double((u >> 24) & 0xff) / 255
    )
}

private struct GoogleAvatar: View {
    let url: String
    let size: CGFloat
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "person.fill")
                    .font(.system(size: size * 0.44, weight: .bold))
                    .foregroundStyle(Theme.muted)
            }
        }
        .frame(width: size, height: size)
        .background(Theme.chip, in: Circle())
        .clipShape(Circle())
        .task(id: url) { image = await GooglePictures.load(url) }
    }
}

/// Small in-memory cache of Google profile pictures; loads only allowed URLs.
private enum GooglePictures {
    private static let cache = NSCache<NSString, UIImage>()

    static func load(_ url: String) async -> UIImage? {
        guard Avatar.shared.isGooglePictureUrl(value: url), let address = URL(string: url) else { return nil }
        if let hit = cache.object(forKey: url as NSString) { return hit }
        guard let (data, response) = try? await URLSession.shared.data(from: address),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let image = UIImage(data: data) else { return nil }
        cache.setObject(image, forKey: url as NSString)
        return image
    }
}

/// Shape paths matching the Android / mockup glyphs, in a ±14 unit box.
private enum AvatarShapes {
    static func draw(_ shape: Avatar.Shape, fg: Color, bg: Color, in context: inout GraphicsContext) {
        switch shape {
        case .seed:
            var context = context
            context.rotate(by: .degrees(25))
            context.fill(Ellipse().path(in: CGRect(x: -8, y: -11, width: 16, height: 22)), with: .color(fg))
            var vein = Path()
            vein.move(to: CGPoint(x: -2, y: -8))
            vein.addQuadCurve(to: CGPoint(x: -1, y: 8), control: CGPoint(x: 3, y: 0))
            context.stroke(vein, with: .color(bg), lineWidth: 2)
        case .die:
            context.fill(Path(roundedRect: CGRect(x: -10, y: -10, width: 20, height: 20), cornerRadius: 5), with: .color(fg))
            for p in [CGPoint(x: -5, y: -5), CGPoint(x: 5, y: -5), .zero, CGPoint(x: -5, y: 5), CGPoint(x: 5, y: 5)] {
                context.fill(Path(ellipseIn: CGRect(x: p.x - 2.2, y: p.y - 2.2, width: 4.4, height: 4.4)), with: .color(bg))
            }
        case .kite:
            var kite = Path()
            kite.move(to: CGPoint(x: 0, y: -12))
            kite.addLine(to: CGPoint(x: 8, y: -2))
            kite.addLine(to: CGPoint(x: 0, y: 8))
            kite.addLine(to: CGPoint(x: -8, y: -2))
            kite.closeSubpath()
            context.fill(kite, with: .color(fg))
            var tail = Path()
            tail.move(to: CGPoint(x: 0, y: 8))
            tail.addQuadCurve(to: CGPoint(x: 0, y: 14), control: CGPoint(x: 4, y: 11))
            context.stroke(tail, with: .color(fg), lineWidth: 2)
        case .star:
            var star = Path()
            for i in 0..<10 {
                let r: CGFloat = i % 2 == 0 ? 12 : 5.2
                let a = Double(i) * .pi / 5 - .pi / 2
                let p = CGPoint(x: r * Foundation.cos(a), y: r * Foundation.sin(a))
                if i == 0 { star.move(to: p) } else { star.addLine(to: p) }
            }
            star.closeSubpath()
            context.fill(star, with: .color(fg))
        case .moon:
            context.fill(Path(ellipseIn: CGRect(x: -11, y: -11, width: 22, height: 22)), with: .color(fg))
            context.fill(Path(ellipseIn: CGRect(x: -4, y: -11, width: 18, height: 18)), with: .color(bg))
        case .sun:
            context.fill(Path(ellipseIn: CGRect(x: -6, y: -6, width: 12, height: 12)), with: .color(fg))
            for a in stride(from: 0, to: 360, by: 45) {
                var ray = context
                ray.rotate(by: .degrees(Double(a)))
                var line = Path()
                line.move(to: CGPoint(x: 0, y: -9))
                line.addLine(to: CGPoint(x: 0, y: -12.5))
                ray.stroke(line, with: .color(fg), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))
            }
        case .flower:
            for a in stride(from: 0, to: 360, by: 72) {
                var petal = context
                petal.rotate(by: .degrees(Double(a)))
                petal.fill(Path(ellipseIn: CGRect(x: -5, y: -11.5, width: 10, height: 10)), with: .color(fg))
            }
            context.fill(Path(ellipseIn: CGRect(x: -3.2, y: -3.2, width: 6.4, height: 6.4)), with: .color(bg))
        case .leaf:
            var leaf = Path()
            leaf.move(to: CGPoint(x: -9, y: 9))
            leaf.addQuadCurve(to: CGPoint(x: 9, y: -10), control: CGPoint(x: -10, y: -9))
            leaf.addQuadCurve(to: CGPoint(x: -9, y: 9), control: CGPoint(x: 10, y: 9))
            context.fill(leaf, with: .color(fg))
            var stem = Path()
            stem.move(to: CGPoint(x: -9, y: 9))
            stem.addLine(to: CGPoint(x: 4, y: -4))
            context.stroke(stem, with: .color(bg), lineWidth: 2)
        case .heart:
            var heart = Path()
            heart.move(to: CGPoint(x: 0, y: 10))
            heart.addCurve(to: CGPoint(x: -4, y: -10), control1: CGPoint(x: -14, y: 0), control2: CGPoint(x: -10, y: -11))
            heart.addCurve(to: CGPoint(x: 0, y: -6), control1: CGPoint(x: -1, y: -10), control2: CGPoint(x: 0, y: -7))
            heart.addCurve(to: CGPoint(x: 4, y: -10), control1: CGPoint(x: 0, y: -7), control2: CGPoint(x: 1, y: -10))
            heart.addCurve(to: CGPoint(x: 0, y: 10), control1: CGPoint(x: 10, y: -11), control2: CGPoint(x: 14, y: 0))
            context.fill(heart, with: .color(fg))
        case .crown:
            var crown = Path()
            crown.move(to: CGPoint(x: -11, y: 8))
            crown.addLine(to: CGPoint(x: -12, y: -6))
            crown.addLine(to: CGPoint(x: -5, y: 0))
            crown.addLine(to: CGPoint(x: 0, y: -9))
            crown.addLine(to: CGPoint(x: 5, y: 0))
            crown.addLine(to: CGPoint(x: 12, y: -6))
            crown.addLine(to: CGPoint(x: 11, y: 8))
            crown.closeSubpath()
            context.fill(crown, with: .color(fg))
        case .drop:
            var drop = Path()
            drop.move(to: CGPoint(x: 0, y: -12))
            drop.addCurve(to: CGPoint(x: 9, y: 5), control1: CGPoint(x: 6, y: -3), control2: CGPoint(x: 9, y: 1))
            drop.addCurve(to: CGPoint(x: 0, y: 14), control1: CGPoint(x: 9, y: 10), control2: CGPoint(x: 4.5, y: 14))
            drop.addCurve(to: CGPoint(x: -9, y: 5), control1: CGPoint(x: -4.5, y: 14), control2: CGPoint(x: -9, y: 10))
            drop.addCurve(to: CGPoint(x: 0, y: -12), control1: CGPoint(x: -9, y: 1), control2: CGPoint(x: -6, y: -3))
            context.fill(drop, with: .color(fg))
        case .hill:
            var hill = Path()
            hill.move(to: CGPoint(x: -13, y: 9))
            hill.addLine(to: CGPoint(x: -4, y: -7))
            hill.addLine(to: CGPoint(x: 1, y: 1))
            hill.addLine(to: CGPoint(x: 5, y: -4))
            hill.addLine(to: CGPoint(x: 13, y: 9))
            hill.closeSubpath()
            context.fill(hill, with: .color(fg))
        default:
            break
        }
    }
}
