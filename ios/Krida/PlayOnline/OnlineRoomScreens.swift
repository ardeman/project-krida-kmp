import Shared
import SwiftUI

// Online "Create room" and "Join a room" from the public-rooms mockup
// (`docs/mockups/public-rooms.md`). Same as Android's `OnlineRoomScreens.kt`.

/// Room setup (players and rules) on the left, Private / Public and Create on the right.
struct CreateOnlineRoomView<Options: View>: View {
    let game: String
    let onBack: () -> Void
    let onCreate: (_ publicRoom: Bool, _ seats: Int) -> Void
    @ViewBuilder let options: Options

    @State private var publicRoom = false
    @State private var seats: Int
    @Environment(\.uiScale) private var s

    init(game: String, onBack: @escaping () -> Void,
         onCreate: @escaping (_ publicRoom: Bool, _ seats: Int) -> Void,
         @ViewBuilder options: () -> Options) {
        self.game = game
        self.onBack = onBack
        self.onCreate = onCreate
        self.options = options()
        _seats = State(initialValue: game == UlarTanggaGame.shared.id || game == LudoGame.shared.id ? 4 : 2)
    }

    var body: some View {
        NearbyFrame(title: gameTitle(game), subtitle: placeLine(true), online: true, backLabel: "Back", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 10 * s) {
                    RoomHeading(text: "Room setup")
                    RoomPlayerCount(game: game, seats: $seats)
                    options
                }
                .padding(18 * s)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .nearbyCard(s)
            VStack(alignment: .leading, spacing: 12 * s) {
                RoomHeading(text: "Create room")
                RoomDetail(text: "Who can join?")
                Segmented(options: [(false, "Private", nil), (true, "Public", nil)],
                          selection: $publicRoom, label: "Who can join?")
                RoomDetail(text: publicRoom
                    ? "Matchmaking can find your room."
                    : "Only players with your code or QR can join.")
                RoomDetail(text: "Friends can still use its code or QR.")
                Spacer(minLength: 0)
                Button { onCreate(publicRoom, seats) } label: {
                    Text("Create room").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(18 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .nearbyCard(s)
        }
    }
}

/// Searching (public rooms, then matchmaking) on the left, a code or QR on the right.
struct JoinOnlineRoomView: View {
    let game: String
    @Binding var code: String
    let error: LocalizedStringKey?
    let onScan: () -> Void
    let onJoin: () -> Void
    let onSearch: (_ seats: Int) -> Void
    let onBack: () -> Void

    @State private var seats: Int
    @Environment(\.uiScale) private var s

    init(game: String, code: Binding<String>, error: LocalizedStringKey?, onScan: @escaping () -> Void,
         onJoin: @escaping () -> Void, onSearch: @escaping (_ seats: Int) -> Void, onBack: @escaping () -> Void) {
        self.game = game
        _code = code
        self.error = error
        self.onScan = onScan
        self.onJoin = onJoin
        self.onSearch = onSearch
        self.onBack = onBack
        _seats = State(initialValue: game == UlarTanggaGame.shared.id || game == LudoGame.shared.id ? 4 : 2)
    }

    private var valid: Bool { RoomCode.shared.isValidOnline(code: code) }

    var body: some View {
        NearbyFrame(title: gameTitle(game), subtitle: placeLine(true), online: true, backLabel: "Back", onBack: onBack) {
            VStack(alignment: .leading, spacing: 12 * s) {
                RoomHeading(text: "Find a room")
                RoomDetail(text: "Join a public room (its host's rules) or find other players (standard rules).")
                RoomPlayerCount(game: game, seats: $seats)
                RoomDetail(text: "After 20 seconds, agree to play with the players found or add computers.")
                Spacer(minLength: 0)
                Button { onSearch(seats) } label: {
                    Text("Find a match").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(18 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .nearbyCard(s)
            VStack(alignment: .leading, spacing: 8 * s) {
                RoomHeading(text: "Have a room code?")
                RoomDetail(text: "Join a public or private room directly.")
                CodeBoxes(code: $code, length: Int(Krida.shared.ONLINE_ROOM_CODE_LENGTH),
                          valid: valid, onSubmit: onJoin, compact: true)
                    .frame(maxWidth: .infinity)
                if let error {
                    Text(error).font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.error).lineLimit(2)
                }
                Spacer(minLength: 0)
                Button(action: onScan) {
                    Label("Scan the QR", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity)
                }
                .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                Button(action: onJoin) {
                    Text("Join a room").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
                .opacity(valid ? 1 : 0.5)
                .disabled(!valid)
            }
            .padding(18 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .nearbyCard(s)
        }
    }
}

/// Total seats: 2–4 for ular tangga and ludo, always two for congklak.
private struct RoomPlayerCount: View {
    let game: String
    @Binding var seats: Int
    @Environment(\.uiScale) private var s

    var body: some View {
        if game == UlarTanggaGame.shared.id || game == LudoGame.shared.id {
            RoomDetail(text: "Players")
            Segmented(options: (2...4).map { ($0, LocalizedStringKey("\($0)"), nil) },
                      selection: $seats, label: "Players")
        } else {
            RoomDetail(text: "2 players · you and an opponent")
        }
    }
}

private struct RoomHeading: View {
    let text: LocalizedStringKey
    @Environment(\.uiScale) private var s
    var body: some View { Text(text).font(Theme.font(20 * s, .black)) }
}

private struct RoomDetail: View {
    let text: LocalizedStringKey
    @Environment(\.uiScale) private var s
    var body: some View {
        Text(text).font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
            .fixedSize(horizontal: false, vertical: true)
    }
}
