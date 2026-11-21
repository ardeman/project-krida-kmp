import SwiftUI

/// People sharing one phone: hides the board while it changes hands.
struct PassCover: View {
    let name: String
    let color: Color
    let onContinue: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        ZStack {
            Theme.backdropGradient.ignoresSafeArea()
            VStack(spacing: 14 * s) {
                Circle().fill(color).frame(width: 28 * s, height: 28 * s)
                Text("Pass the phone to \(name)")
                    .font(Theme.font(22 * s, .black))
                    .multilineTextAlignment(.center)
                Button(action: onContinue) { Text("Continue").frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle(height: 48))
            }
            .padding(24 * s)
            .frame(maxWidth: 420 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
            .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
            .padding(.horizontal, 24 * s)
            .foregroundStyle(Theme.ink)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
