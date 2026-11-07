import Foundation
import Shared
import UIKit

/// Connectivity test: host or join a BLE room and play the demo counter game against
/// another iOS or Android device. Replace with the real menu + RealityKit scenes later.
@MainActor
final class RoomModel: ObservableObject {
    @Published var status = "Siap main"
    @Published var roomCode = ""
    @Published var players = ""
    @Published var total: Int?
    @Published var myTurn = false
    @Published private(set) var canStart = false
    @Published private(set) var inRoom = false

    private var hostTransport: BleHostTransport?
    private var guestTransport: BleGuestTransport?
    private var host: HostSession<CounterState, CounterMove>?
    private var guest: GuestSession<CounterState, CounterMove>?

    private var deviceName: String { UIDevice.current.name }

    func startHost() {
        let code = RoomCode.shared.generate()
        let ble = BleHostTransport(roomCode: code)
        let session = HostSession<CounterState, CounterMove>(rules: CounterGame.shared, transport: ble, roomCode: code)
        _ = session.addLocalPlayer(name: deviceName, platform: .ios)

        session.onPlayersChanged = { [weak self, weak session] list in
            guard let self, let session else { return }
            self.players = list.map { $0.connected ? $0.name : "\($0.name) (putus)" }.joined(separator: ", ")
            self.canStart = session.canStart()
        }
        session.onStateChanged = { [weak self] state in
            guard let state else { return }
            self?.render(state, isMyTurn: state.turn == 0)
        }

        hostTransport = ble
        host = session
        roomCode = code
        inRoom = true
        status = "Room \(code) — tunggu pemain lain…"
        ble.start()
    }

    func startGame() {
        guard let host, host.canStart() else { return }
        host.start()
        canStart = false
    }

    func join() {
        let code = roomCode.uppercased()
        let ble = BleGuestTransport(roomCode: code)
        let session = GuestSession<CounterState, CounterMove>(
            rules: CounterGame.shared, transport: ble, name: deviceName, platform: .ios, rejoinToken: nil
        )
        session.onLobby = { [weak self] list in
            self?.players = list.map(\.name).joined(separator: ", ")
            self?.status = "Masuk room \(code)"
        }
        session.onState = { [weak self, weak session] state in
            guard let state else { return }
            self?.render(state, isMyTurn: session?.isMyTurn ?? false)
        }
        session.onRejected = { [weak self] reason in
            self?.status = "Ditolak: \(reason)"
            self?.guestTransport?.close()
        }
        session.onInvalid = { [weak self] reason in self?.status = "Langkah ditolak: \(reason)" }
        session.onDisconnected = { [weak self] in self?.status = "Terputus dari host" }

        guestTransport = ble
        guest = session
        inRoom = true
        status = "Mencari room \(code)…"
        ble.start()
    }

    func play(_ n: Int32) {
        let move = CounterMove(add: n)
        _ = host?.submitLocal(seat: 0, move: move)
        _ = guest?.sendMove(move: move)
    }

    private func render(_ state: CounterState, isMyTurn: Bool) {
        let finished = CounterGame.shared.isFinished(state: state)
        total = Int(state.total)
        myTurn = isMyTurn && !finished
        if finished {
            let winner = (state.turn + state.players - 1) % state.players + 1
            status = "Selesai! Pemain \(winner) menang"
        } else {
            status = myTurn ? "Giliranmu" : "Giliran pemain \(state.turn + 1)"
        }
    }
}
