import Foundation
import Shared
import UIKit

/// A congklak room over BLE ("Main bareng dekat"), from hosting or joining until the game
/// starts. Then `table` takes over the session and the board screen shows it.
@MainActor
final class NearbyRoom: ObservableObject {
    let code: String
    let isHost: Bool
    @Published private(set) var players: [PlayerInfo] = []
    @Published private(set) var canStart = false
    /// Where the room stands, for the lobby.
    @Published private(set) var status = String(localized: "Menunggu pemain lain…", bundle: .app)
    @Published private(set) var table: CongklakTable?

    private let transport: Transport
    private var host: HostSession<CongklakState, CongklakMove>?
    private var guest: GuestSession<CongklakState, CongklakMove>?

    private init(code: String, isHost: Bool, transport: Transport) {
        self.code = code
        self.isHost = isHost
        self.transport = transport
    }

    func start() {
        guard let host, host.canStart() else { return }
        host.start()
        table = HostTable(session: host, mySeat: 0, transport: transport)
    }

    func leave() {
        if let table { table.leave() } else if let guest { guest.leave() } else { transport.close() }
    }

    private static var deviceName: String { UIDevice.current.name }

    static func host() -> NearbyRoom {
        let code = RoomCode.shared.generate()
        let ble = BleHostTransport(roomCode: code)
        let room = NearbyRoom(code: code, isHost: true, transport: ble)
        let session = HostSession<CongklakState, CongklakMove>(rules: CongklakSettings.rules, transport: ble, roomCode: code)
        session.onPlayersChanged = { [weak room, weak session] list in
            guard let room, let session else { return }
            room.players = list
            room.canStart = session.canStart()
        }
        _ = session.addLocalPlayer(name: deviceName, platform: .ios)
        room.host = session
        ble.start()
        return room
    }

    static func join(code: String) -> NearbyRoom {
        let ble = BleGuestTransport(roomCode: code)
        let room = NearbyRoom(code: code, isHost: false, transport: ble)
        let session = GuestSession<CongklakState, CongklakMove>(
            rules: CongklakGame.shared, transport: ble, name: deviceName, platform: .ios, rejoinToken: nil
        )
        room.status = String(localized: "Mencari room…", bundle: .app)
        session.onLobby = { [weak room] list in
            room?.players = list
            room?.status = String(localized: "Sudah masuk. Menunggu host memulai…", bundle: .app)
        }
        session.onState = { [weak room, weak session] _ in
            guard let room, let session, room.table == nil else { return }
            room.table = GuestTable(session: session)
        }
        session.onRejected = { [weak room] reason in
            room?.status = switch reason {
            case RejectReason.shared.VERSION: String(localized: "Versi aplikasi berbeda. Perbarui aplikasi.", bundle: .app)
            case RejectReason.shared.FULL: String(localized: "Room sudah penuh", bundle: .app)
            case RejectReason.shared.STARTED: String(localized: "Permainan sudah dimulai", bundle: .app)
            default: String(localized: "Tidak bisa masuk room", bundle: .app)
            }
        }
        session.onDisconnected = { [weak room] in room?.status = String(localized: "Terputus dari host", bundle: .app) }
        room.guest = session
        ble.start()
        return room
    }
}
