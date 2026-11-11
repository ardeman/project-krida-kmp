import SwiftUI

/// The app's own question before iOS asks about notifications: what they are for (a
/// friend's invite while Krida is closed), with Not now and Allow. Allow brings up the
/// system question; Not now leaves it unasked, so it can still come later. Shown right
/// after sign-in and from the friend and invite screens (see `Push.ask`). iPhone only.
struct PushAskLayer: View {
    @ObservedObject private var push = Push.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        if push.asking {
            ZStack {
                // The scrim takes the taps, so the screen underneath can't be used by accident.
                Color.black.opacity(0.45).ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}
                VStack(spacing: 12 * s) {
                    Image(systemName: "bell.fill")
                        .font(.system(size: 22 * s, weight: .bold))
                        .foregroundStyle(Theme.accent)
                        .frame(width: 48 * s, height: 48 * s)
                        .background(Theme.chip, in: Circle())
                        .accessibilityHidden(true)
                    Text("Get invites from friends")
                        .font(Theme.font(22 * s, .black))
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                    Text("Allow notifications so a friend's invite reaches you while Krida is closed.")
                        .font(Theme.font(14 * s, .bold))
                        .foregroundStyle(Theme.muted)
                        .multilineTextAlignment(.center)
                    HStack(spacing: 10 * s) {
                        Button(action: push.later) { Text("Not now").frame(maxWidth: .infinity) }
                            .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                        Button(action: push.allow) { Text("Allow").frame(maxWidth: .infinity) }
                            .buttonStyle(PrimaryButtonStyle())
                    }
                    .padding(.top, 6 * s)
                }
                .padding(.horizontal, 24 * s)
                .padding(.vertical, 22 * s)
                .frame(width: 420 * s)
                .nearbyCard(s)
                .foregroundStyle(Theme.ink)
                .padding(24 * s)
            }
        }
    }
}
