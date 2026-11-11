import SwiftUI

/// Where a friend stands in this room's picker.
enum InviteState { case none, sent, joined }

/// The invites sent from `room` that still count at `now`, by friend id: joined while that
/// friend holds a seat, sent until the invite runs out.
@MainActor
func inviteStates(_ room: NearbyRoom, now: Date) -> [String: InviteState] {
    Invites.shared.sent.filter { $0.value.code == room.code }.mapValues { sent in
        if !sent.id.isEmpty && room.joinedInvites.contains(sent.id) { .joined } else if sent.until > now { .sent } else { .none }
    }
}

/// Under the seats: the friends whose invite is still waiting ("Budi · invite sent").
struct WaitingInvites: View {
    @ObservedObject var room: NearbyRoom
    @ObservedObject private var account = Account.shared
    @ObservedObject private var invites = Invites.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        // Each second, so the line goes when the invite runs out.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let states = inviteStates(room, now: context.date)
            let names = account.friends.filter { states[$0.id] == .sent }
                .map { $0.name.isEmpty ? String(localized: "Player", bundle: .app) : $0.name }
            if !names.isEmpty {
                Text("\(names.joined(separator: ", ")) · invite sent")
                    .font(Theme.font(13 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
            }
        }
    }
}

/// The friends list in place of the seats: the host invites friends to the open seats.
struct InvitePicker: View {
    @ObservedObject var room: NearbyRoom
    let onBack: () -> Void
    @ObservedObject private var account = Account.shared
    @ObservedObject private var invites = Invites.shared
    @Environment(\.uiScale) private var s

    var body: some View {
        let open = room.maxPlayers - room.players.count
        VStack(alignment: .leading, spacing: 10 * s) {
            HStack(spacing: 12 * s) {
                CircleIconButton(systemImage: "chevron.left", label: "Back", action: onBack)
                Text("Invite friends").font(Theme.font(17 * s, .black)).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                (open == 1 ? Text("1 open seat") : Text("\(open) open seats"))
                    .font(Theme.font(13 * s, .bold)).foregroundStyle(Theme.muted).lineLimit(1)
            }
            if invites.sendFailed {
                Text("The invite could not be sent.").font(Theme.font(13 * s, .bold)).foregroundStyle(Theme.error)
            }
            if account.friends.isEmpty {
                VStack(spacing: 4 * s) {
                    Text("No friends yet").font(Theme.font(17 * s, .black))
                    Text("Add friends from your account first.").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    let states = inviteStates(room, now: context.date)
                    ScrollView {
                        VStack(spacing: 0) {
                            ForEach(Array(account.friends.enumerated()), id: \.element.id) { index, friend in
                                if index > 0 { Rectangle().fill(Theme.line).frame(height: 1) }
                                row(friend, states[friend.id] ?? .none)
                            }
                        }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .onAppear {
            invites.sendFailed = false
            account.loadFriends()
            // So the friends' own invites reach this phone while the app is closed.
            Push.shared.ask()
        }
    }

    private func row(_ friend: Account.Friend, _ state: InviteState) -> some View {
        HStack(spacing: 10 * s) {
            Image(systemName: "person.fill")
                .font(.system(size: 15 * s, weight: .bold))
                .foregroundStyle(Theme.muted)
                .frame(width: 32 * s, height: 32 * s)
                .background(Theme.chip, in: Circle())
                .accessibilityHidden(true)
            Text(verbatim: friend.name.isEmpty ? String(localized: "Player", bundle: .app) : friend.name)
                .font(Theme.font(16 * s, .bold)).lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
            switch state {
            case .joined: Text("Joined").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
            case .sent: Text("Invite sent").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
            case .none:
                Button {
                    if let key = room.hostKey { invites.send(friendId: friend.id, code: room.code, hostKey: key) }
                } label: { Text("Invite") }
                    .buttonStyle(PrimaryButtonStyle())
            }
        }
        .frame(height: 52 * s)
    }
}
