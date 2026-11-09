import Shared
import SwiftUI

// Online "Buat room" and "Gabung room" from the public-rooms mockup
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
        _seats = State(initialValue: game == UlarTanggaGame.shared.id ? 4 : 2)
    }

    var body: some View {
        NearbyFrame(title: gameTitle(game), subtitle: placeLine(true), online: true, backLabel: "Kembali", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 10 * s) {
                    RoomHeading(text: "Pengaturan room")
                    RoomPlayerCount(game: game, seats: $seats)
                    options
                }
                .padding(18 * s)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .nearbyCard(s)
            VStack(alignment: .leading, spacing: 12 * s) {
                RoomHeading(text: "Buat room")
                RoomDetail(text: "Siapa yang bisa gabung?")
                Segmented(options: [(false, "Privat", nil), (true, "Publik", nil)],
                          selection: $publicRoom, label: "Siapa yang bisa gabung?")
                RoomDetail(text: publicRoom
                    ? "Room kamu bisa ditemukan lewat pencarian pemain."
                    : "Hanya pemain dengan kode atau QR kamu yang bisa gabung.")
                RoomDetail(text: "Teman tetap bisa pakai kode atau QR room.")
                Spacer(minLength: 0)
                Button { onCreate(publicRoom, seats) } label: {
                    Text("Buat room").frame(maxWidth: .infinity)
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
        _seats = State(initialValue: game == UlarTanggaGame.shared.id ? 4 : 2)
    }

    private var valid: Bool { RoomCode.shared.isValidOnline(code: code) }

    var body: some View {
        NearbyFrame(title: gameTitle(game), subtitle: placeLine(true), online: true, backLabel: "Kembali", onBack: onBack) {
            VStack(alignment: .leading, spacing: 12 * s) {
                RoomHeading(text: "Cari room")
                RoomDetail(text: "Gabung room publik atau cari pemain lain.")
                RoomPlayerCount(game: game, seats: $seats)
                RoomDetail(text: "match_fallback_hint")
                Spacer(minLength: 0)
                Button { onSearch(seats) } label: {
                    Text("Cari pemain").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(18 * s)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .nearbyCard(s)
            VStack(alignment: .leading, spacing: 8 * s) {
                RoomHeading(text: "Punya kode room?")
                RoomDetail(text: "Langsung gabung room publik atau privat.")
                CodeBoxes(code: $code, length: Int(Krida.shared.ONLINE_ROOM_CODE_LENGTH),
                          valid: valid, onSubmit: onJoin, compact: true)
                    .frame(maxWidth: .infinity)
                if let error {
                    Text(error).font(Theme.font(12 * s, .bold)).foregroundStyle(Theme.error).lineLimit(2)
                }
                Spacer(minLength: 0)
                Button(action: onScan) {
                    Label("Pindai QR", systemImage: "qrcode.viewfinder").frame(maxWidth: .infinity)
                }
                .buttonStyle(SecondaryButtonStyle(tint: Theme.muted))
                Button(action: onJoin) {
                    Text("Gabung room").frame(maxWidth: .infinity)
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

/// Total seats: 2–4 for ular tangga, always two for congklak.
private struct RoomPlayerCount: View {
    let game: String
    @Binding var seats: Int
    @Environment(\.uiScale) private var s

    var body: some View {
        if game == UlarTanggaGame.shared.id {
            RoomDetail(text: "play_players")
            Segmented(options: (2...4).map { ($0, LocalizedStringKey("\($0)"), nil) },
                      selection: $seats, label: "Pemain")
        } else {
            RoomDetail(text: "match_two_players")
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
