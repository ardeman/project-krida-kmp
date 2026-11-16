import SwiftUI

/// Home card preview: night sky, moon, and a humanoid werewolf shadow (mockup `#1A2332`).
/// Baked art — hand Bezier paths kept reading wrong at card size.
struct WerewolfPreview: View {
    let onReady: () -> Void

    var body: some View {
        Image("WerewolfPreview")
            .resizable()
            .scaledToFill()
            .clipped()
            .accessibilityHidden(true)
            .onAppear { onReady() }
    }
}
