import SwiftUI

/// Host of a linked room: warn that leaving ends the room for every guest.
struct HostLeaveConfirm: View {
    let onCancel: () -> Void
    let onConfirm: () -> Void
    @Environment(\.uiScale) private var s

    var body: some View {
        ZStack {
            Color.black.opacity(0.35)
                .ignoresSafeArea()
                .onTapGesture(perform: onCancel)
            VStack(spacing: 12 * s) {
                Text("Leave room?").font(Theme.font(22 * s, .black))
                Text("Leaving closes the room for everyone.")
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                HStack(spacing: 10 * s) {
                    Button(action: onCancel) { Text("Cancel").frame(maxWidth: .infinity) }
                        .buttonStyle(SecondaryButtonStyle(height: 48, tint: Theme.muted))
                    Button(action: onConfirm) { Text("Leave room").frame(maxWidth: .infinity) }
                        .buttonStyle(PrimaryButtonStyle(height: 48))
                }
            }
            .padding(24 * s)
            .frame(maxWidth: 420 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
            .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
            .foregroundStyle(Theme.ink)
            .padding(.horizontal, 24 * s)
        }
    }
}
