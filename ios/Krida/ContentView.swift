import SwiftUI
import Shared

/// Menu: congklak pass-and-play on this device, or a nearby room over BLE (host or join),
/// iOS and Android alike. Then the lobby, then the board.
struct ContentView: View {
    @Binding var roomCode: String
    @State private var passAndPlay: CongklakTable?
    @State private var room: NearbyRoom?

    var body: some View {
        Group {
            if let table = passAndPlay {
                CongklakView(table: table) { passAndPlay = nil }
                    .id(ObjectIdentifier(table))
            } else if let room {
                RoomView(room: room) { self.room = nil }
                    .id(ObjectIdentifier(room))
            } else {
                menu
            }
        }
        // `-openCongklak` launch argument: straight to the board (simulator screenshots, UI tests).
        .onAppear { if ProcessInfo.processInfo.arguments.contains("-openCongklak") { startPassAndPlay() } }
    }

    private var menu: some View {
        VStack(alignment: .leading, spacing: 16) {
            Image("Wordmark").resizable().scaledToFit().frame(height: 40).accessibilityLabel("Krida")
            Button("Main congklak (satu perangkat)", action: startPassAndPlay)
                .buttonStyle(.borderedProminent)
            Text("Main bareng dekat (Bluetooth)").font(.headline)
            Button("Buat room") { room = NearbyRoom.host() }
                .buttonStyle(.borderedProminent)
            HStack {
                TextField("Kode room", text: $roomCode)
                    .textInputAutocapitalization(.characters)
                    .autocorrectionDisabled()
                    .textFieldStyle(.roundedBorder)
                    .frame(maxWidth: 160)
                    .onChange(of: roomCode) { _, code in
                        let clean = String(code.uppercased().prefix(4))
                        if clean != code { roomCode = clean }
                    }
                Button("Gabung") { room = NearbyRoom.join(code: roomCode) }
                    .buttonStyle(.bordered)
                    .disabled(!RoomCode.shared.isValid(code: roomCode))
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(Color(red: 1.0, green: 0.97, blue: 0.93))
    }

    private func startPassAndPlay() {
        passAndPlay = HostTable.passAndPlay(names: [String(localized: "Pemain 1"), String(localized: "Pemain 2")])
    }
}

/// A nearby room: the lobby until the game starts, then the board.
private struct RoomView: View {
    @ObservedObject var room: NearbyRoom
    let onExit: () -> Void

    var body: some View {
        if let table = room.table {
            CongklakView(table: table, onExit: onExit)
                .id(ObjectIdentifier(table))
        } else {
            lobby
        }
    }

    private var lobby: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Congklak").font(.title2.bold())
            Text("Kode room").font(.subheadline)
            Text(room.code).font(.system(size: 44, weight: .bold, design: .monospaced))
            Text(room.status)
            ForEach(room.players, id: \.seat) { p in
                Text("\(p.seat + 1). \(p.name)")
            }
            if room.isHost {
                Button("Mulai", action: room.start)
                    .buttonStyle(.borderedProminent)
                    .disabled(!room.canStart)
            }
            Button("Kembali") {
                room.leave()
                onExit()
            }
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .background(Color(red: 1.0, green: 0.97, blue: 0.93))
    }
}
