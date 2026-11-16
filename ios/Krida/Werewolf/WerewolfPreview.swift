import SwiftUI

/// Home card preview: night sky and moon (mockup `#1A2332`).
struct WerewolfPreview: View {
    let onReady: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(hex: 0x1A2332), Color(hex: 0x243044)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Circle()
                .fill(Color(hex: 0xF4E4BC).opacity(0.9))
                .frame(width: 28 * s, height: 28 * s)
                .offset(x: 50 * s, y: -24 * s)
            Image(systemName: "moon.fill")
                .font(.system(size: 22 * s))
                .foregroundStyle(Color(hex: 0xF4E4BC).opacity(0.35))
                .offset(x: 54 * s, y: -20 * s)
            Image(systemName: "pawprint.fill")
                .font(.system(size: 36 * s, weight: .bold))
                .foregroundStyle(Color(hex: 0xFFF6E8).opacity(0.25))
                .offset(x: -20 * s, y: 12 * s)
        }
        .onAppear { onReady() }
    }
}