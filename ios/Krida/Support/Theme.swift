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

extension Color {
    init(hex: UInt32) {
        self.init(red: Double(hex >> 16 & 0xff) / 255, green: Double(hex >> 8 & 0xff) / 255, blue: Double(hex & 0xff) / 255)
    }
}

/// Filled terracotta button (Mulai, Buat room, Main lagi).
struct PrimaryButtonStyle: ButtonStyle {
    var height: CGFloat = 44
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(16))
            .foregroundStyle(.white)
            .padding(.horizontal, 18)
            .frame(height: height)
            .background(Theme.accent.opacity(configuration.isPressed ? 0.8 : 1), in: RoundedRectangle(cornerRadius: 14))
    }
}

/// Outlined button (Gabung pakai kode, Ke menu).
struct SecondaryButtonStyle: ButtonStyle {
    var height: CGFloat = 44
    var tint: Color = Theme.accent
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Theme.font(16))
            .foregroundStyle(tint)
            .padding(.horizontal, 18)
            .frame(height: height)
            .background(.white.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).stroke(tint == Theme.accent ? Theme.accent : Color(hex: 0xD9C6AF), lineWidth: 1.5))
    }
}

/// Round 44 pt icon button with a light border (back, help).
struct CircleIconButton: View {
    let systemImage: String
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(Theme.ink)
                .frame(width: 44, height: 44)
                .background(.white, in: Circle())
                .overlay(Circle().stroke(Theme.line, lineWidth: 1.5))
        }
        .accessibilityLabel(Text(label))
    }
}
