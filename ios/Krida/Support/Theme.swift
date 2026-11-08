import SwiftUI

/// Colors and type from the agreed UI mockup (warm cream, wood, terracotta accent).
enum Theme {
    static let cream = Color(hex: 0xFBF4EA)
    static let ink = Color(hex: 0x2B1D14)
    static let muted = Color(hex: 0x6E5A4B)
    static let accent = Color(hex: 0xA65A26)
    static let line = Color(hex: 0xEADCCB)
    static let chip = Color(hex: 0xF6E6D3)
    static let track = Color(hex: 0xF3EADF)
    static let wood = Color(hex: 0x8A5A2E)
    static let woodShadow = Color(hex: 0x6B4320)
    static let woodDark = Color(hex: 0x6E4422)
    static let pit = Color(hex: 0x3F2611)
    static let seed = Color(hex: 0xF3E3C8)
    static let tan = Color(hex: 0xF1D9B8)
    static let gold = Color(hex: 0xF2B84B)

    static func font(_ size: CGFloat, _ weight: Font.Weight = .heavy) -> Font {
        .system(size: size, weight: weight, design: .rounded)
    }
}

/// How much bigger than the phone design the UI is drawn: 1 on phones, up to 1.5 on iPads.
/// Set by `LandscapeStage`; fonts and metrics multiply by it.
private struct UIScaleKey: EnvironmentKey {
    static let defaultValue: CGFloat = 1
}

extension EnvironmentValues {
    var uiScale: CGFloat {
        get { self[UIScaleKey.self] }
        set { self[UIScaleKey.self] = newValue }
    }
}

/// A camera hole in one corner of a landscape screen (a foldable's cover display). iOS
/// reserves a whole strip on that side; the hole itself is only in the corner: top-leading
/// when the strip is on the leading side, bottom-trailing when it is on the trailing side.
struct PunchHole: Equatable {
    var edge: HorizontalEdge
    /// Width of the strip iOS reserves; keep content in the hole's corner this far in.
    var inset: CGFloat

    var leading: CGFloat { edge == .leading ? inset : 0 }
    var trailing: CGFloat { edge == .trailing ? inset : 0 }
}

private struct PunchHoleKey: EnvironmentKey {
    static let defaultValue: PunchHole? = nil
}

extension EnvironmentValues {
    /// Set by `LandscapeStage` when the screen has a corner punch hole; nil otherwise.
    var punchHole: PunchHole? {
        get { self[PunchHoleKey.self] }
        set { self[PunchHoleKey.self] = newValue }
    }
}

/// Keeps every screen in landscape, whatever shape the window has. Where iOS ignores the
/// landscape-only orientations (resizable scenes: iPad windows, an unfolded foldable) and
/// hands over a portrait window, the stage turns the layout a quarter turn, as if the
/// screen were locked to landscape. A landscape window taller than 4:3 gets a centered
/// stage with cream bars above and below. Inside the stage, `uiScale`
/// grows with the stage so an iPad shows the phone design bigger rather than emptier.
///
/// A safe-area inset on one side only means a corner punch hole: the stage then takes the
/// full width and screens keep just that corner clear (`punchHole`). Insets on both sides
/// (an iPhone's Dynamic Island, centered on its side) are kept as they are.
struct LandscapeStage<Content: View>: View {
    @ViewBuilder let content: Content

    /// The phone design's landscape size (iPhone 17), in points.
    static var designSize: CGSize { CGSize(width: 874, height: 402) }
    /// The tallest stage allowed, as height / width.
    private static var maxHeightRatio: CGFloat { 3.0 / 4.0 }

    var body: some View {
        GeometryReader { outer in
            let insets = outer.safeAreaInsets
            let hole: PunchHole? = switch (insets.leading > 0, insets.trailing > 0) {
            case (true, false): PunchHole(edge: .leading, inset: insets.leading)
            case (false, true): PunchHole(edge: .trailing, inset: insets.trailing)
            default: nil
            }
            GeometryReader { geo in
                let portrait = geo.size.height > geo.size.width
                let width = portrait ? geo.size.height : geo.size.width
                let height = min(portrait ? geo.size.width : geo.size.height, width * Self.maxHeightRatio)
                let scale = min(max(min(width / Self.designSize.width, height / Self.designSize.height), 1), 1.5)
                content
                    .frame(width: width, height: height)
                    .environment(\.uiScale, scale)
                    .environment(\.punchHole, portrait ? nil : hole)
                    .rotationEffect(.degrees(portrait ? 90 : 0))
                    .frame(width: geo.size.width, height: geo.size.height)
            }
            .ignoresSafeArea(.container, edges: hole == nil ? [] : .horizontal)
        }
        .background(Theme.cream.ignoresSafeArea())
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xff) / 255, green: Double(hex >> 8 & 0xff) / 255, blue: Double(hex & 0xff) / 255)
    }
}

/// Filled terracotta button (Mulai, Buat room, Main lagi).
struct PrimaryButtonStyle: ButtonStyle {
    var height: CGFloat = 44
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, height: height)
    }

    private struct Styled: View {
        let configuration: Configuration
        let height: CGFloat
        @Environment(\.uiScale) private var s

        var body: some View {
            configuration.label
                .font(Theme.font(16 * s))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .foregroundStyle(.white)
                .padding(.horizontal, 18 * s)
                .frame(height: height * s)
                .background(Theme.accent.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 14 * s))
                .tapSound(configuration.isPressed)
        }
    }
}

/// Outlined button (Gabung pakai kode, Ke menu).
struct SecondaryButtonStyle: ButtonStyle {
    var height: CGFloat = 44
    var tint: Color = Theme.accent
    func makeBody(configuration: Configuration) -> some View {
        Styled(configuration: configuration, height: height, tint: tint)
    }

    private struct Styled: View {
        let configuration: Configuration
        let height: CGFloat
        let tint: Color
        @Environment(\.uiScale) private var s

        var body: some View {
            configuration.label
                .font(Theme.font(16 * s))
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .foregroundStyle(tint)
                .padding(.horizontal, 18 * s)
                .frame(height: height * s)
                .background(.white.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14 * s))
                .overlay(RoundedRectangle(cornerRadius: 14 * s).stroke(tint == Theme.accent ? Theme.accent : Color(hex: 0xD9C6AF), lineWidth: 1.5))
                .tapSound(configuration.isPressed)
        }
    }
}

extension View {
    /// The wooden tap as a button goes down.
    func tapSound(_ pressed: Bool) -> some View {
        onChange(of: pressed) { _, down in if down { Sounds.shared.play(.tap) } }
    }
}

/// Round 44 pt (times `uiScale`) icon button with a light border (back, help).
struct CircleIconButton: View {
    let systemImage: String
    let label: LocalizedStringKey
    /// Draws a slash over the icon (a setting that is off).
    var struck = false
    let action: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        Button {
            Sounds.shared.play(.tap)
            action()
        } label: {
            Image(systemName: systemImage)
                .font(.system(size: 18 * s, weight: .bold))
                .foregroundStyle(struck ? Theme.muted : Theme.ink)
                .overlay {
                    if struck {
                        Capsule().fill(Theme.muted).frame(width: 2.5 * s, height: 26 * s).rotationEffect(.degrees(-45))
                    }
                }
                .frame(width: 44 * s, height: 44 * s)
                .background(.white, in: Circle())
                .overlay(Circle().stroke(Theme.line, lineWidth: 1.5))
        }
        .accessibilityLabel(Text(label))
    }
}
