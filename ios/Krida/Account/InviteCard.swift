import Shared
import SwiftUI

/// The invites inbox and its card, over the menus. The inbox is open while the app is in
/// front, signed in and on a menu (`onMenus`): never over a game, a lobby or a match
/// search, the same rule as room links. Same as Android (`MainActivity`, `InviteCard.kt`).
struct InviteLayer: View {
    let onMenus: Bool
    let onJoin: (Invites.Invite) -> Void
    @ObservedObject private var account = Account.shared
    @ObservedObject private var invites = Invites.shared
    @Environment(\.scenePhase) private var scenePhase

    private var listening: Bool { onMenus && scenePhase == .active && account.user != nil }

    var body: some View {
        ZStack {
            if onMenus, let invite = invites.current {
                InviteCard(
                    invite: invite,
                    onNotNow: {
                        invites.dismiss(invite.id)
                        // The next invite should also arrive while the app is closed. Asked
                        // only now, so the question never covers the invite itself.
                        Push.shared.ask()
                    },
                    onJoin: { onJoin(invite) }
                )
                    .id(invite.id)
            }
        }
        // No stop on disappear: SwiftUI rebuilds this layer when a menu screen changes (the
        // Account screen opening or closing), and the old copy's disappear comes after the
        // new copy's first `listen`, which would close the inbox for good. The layer lives
        // as long as the app, and `listening` already closes it off the menus.
        .onChange(of: listening, initial: true) { _, on in
            if on { invites.listen() } else { invites.stop() }
        }
    }
}

/// A friend's invite, over whichever menu screen is showing: who invites, the game, the room
/// and its players, with Not now and Join. Goes away by itself when the invite runs out.
private struct InviteCard: View {
    let invite: Invites.Invite
    let onNotNow: () -> Void
    let onJoin: () -> Void
    @Environment(\.uiScale) private var s

    private var game: String {
        switch invite.game {
        case UlarTanggaGame.shared.id: String(localized: "Snakes & ladders", bundle: .app)
        case LudoGame.shared.id: String(localized: "Ludo", bundle: .app)
        default: String(localized: "Congklak", bundle: .app)
        }
    }

    var body: some View {
        let name = invite.from.isEmpty ? String(localized: "Player", bundle: .app) : invite.from
        ZStack {
            // The scrim takes the taps, so the menu underneath can't be used by accident.
            Color.black.opacity(0.45).ignoresSafeArea().contentShape(Rectangle()).onTapGesture {}
            VStack(spacing: 12 * s) {
                Image(systemName: "person.2.fill")
                    .font(.system(size: 22 * s, weight: .bold))
                    .foregroundStyle(Theme.accent)
                    .frame(width: 48 * s, height: 48 * s)
                    .background(Theme.chip, in: Circle())
                    .accessibilityHidden(true)
                Text("\(name) invites you")
                    .font(Theme.font(22 * s, .black))
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                Text("\(game) · room \(invite.code) · \(invite.players) of \(invite.seats) players")
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                HStack(spacing: 10 * s) {
                    Button(action: onNotNow) { Text("Not now").frame(maxWidth: .infinity) }
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                    Button(action: onJoin) { Text("Join").frame(maxWidth: .infinity) }
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
        .task {
            do { try await Task.sleep(for: .seconds(max(0, invite.until.timeIntervalSinceNow))) } catch { return }
            Invites.shared.expire(invite.id)
        }
    }
}
