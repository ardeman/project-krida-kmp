import Foundation
import Shared
import UIKit

/// A room over BLE ("Teman dekat" on the play card), from hosting or joining until the game
/// starts. Then `congklak` or `ular` takes over the session and that game's screen shows it.
/// A guest joins by code and learns the game from the host's Welcome. Same as Android.
@MainActor
final class NearbyRoom: ObservableObject {
    enum Game { case congklak, ularTangga }

    let code: String
    let isHost: Bool
    /// The room's game id (`GameRules.id`); nil for a guest until the host welcomes it.
    @Published private(set) var game: String?
    @Published private(set) var players: [PlayerInfo] = []
    @Published private(set) var maxPlayers = 2
    @Published private(set) var canStart = false
    /// Where the room stands, for the lobby.
    @Published private(set) var status = String(localized: "Menunggu pemain lain…", bundle: .app)
    @Published private(set) var congklak: CongklakTable?
    @Published private(set) var ular: UlarTanggaTable?

    private let transport: Transport
    private var begin: (() -> Void)?
    private var guest: AnyObject?
    private var guestLeave: (() -> Void)?
    private var join: GuestJoin?

    private init(code: String, isHost: Bool, transport: Transport) {
        self.code = code
        self.isHost = isHost
        self.transport = transport
    }

    func start() { begin?() }

    func leave() {
        if let congklak { congklak.leave() }
        else if let ular { ular.leave() }
        else if let guestLeave { guestLeave() }
        else if let join { join.leave() }
        else { transport.close() }
    }

    private static var deviceName: String { UIDevice.current.name }

    private func hostSession<S: AnyObject, M: AnyObject>(_ rules: any GameRules, ble: BleHostTransport) -> HostSession<S, M> {
        game = rules.id
        maxPlayers = Int(rules.maxPlayers)
        let session = HostSession<S, M>(rules: rules, transport: ble, roomCode: code)
        session.onPlayersChanged = { [weak self, weak session] list in
            guard let self, let session else { return }
            self.players = list
            self.canStart = session.canStart()
        }
        _ = session.addLocalPlayer(name: Self.deviceName, platform: .ios)
        return session
    }

    private func guestSession<S: AnyObject, M: AnyObject>(_ rules: any GameRules, join: GuestJoin) -> GuestSession<S, M> {
        game = rules.id
        maxPlayers = Int(rules.maxPlayers)
        let session = GuestSession<S, M>(rules: rules, join: join)
        session.onLobby = { [weak self] list in
            self?.players = list
            self?.status = String(localized: "Sudah masuk. Menunggu host memulai…", bundle: .app)
        }
        session.onRejected = { [weak self] in self?.rejected($0) }
        session.onDisconnected = { [weak self] in self?.status = String(localized: "Terputus dari host", bundle: .app) }
        guest = session
        guestLeave = { [weak session] in session?.leave() }
        return session
    }

    private func rejected(_ reason: String) {
        status = switch reason {
        case RejectReason.shared.VERSION: String(localized: "Versi aplikasi berbeda. Perbarui aplikasi.", bundle: .app)
        case RejectReason.shared.FULL: String(localized: "Room sudah penuh", bundle: .app)
        case RejectReason.shared.STARTED: String(localized: "Permainan sudah dimulai", bundle: .app)
        default: String(localized: "Tidak bisa masuk room", bundle: .app)
        }
    }

    static func host(game: Game) -> NearbyRoom {
        let code = RoomCode.shared.generate()
        let ble = BleHostTransport(roomCode: code)
        let room = NearbyRoom(code: code, isHost: true, transport: ble)
        switch game {
        case .congklak:
            let session: HostSession<CongklakState, CongklakMove> = room.hostSession(CongklakSettings.rules, ble: ble)
            room.begin = { [weak room] in
                guard let room, session.canStart() else { return }
                session.start()
                room.congklak = HostTable(session: session, mySeat: 0, transport: ble)
            }
        case .ularTangga:
            let session: HostSession<UlarTanggaState, UlarTanggaMove> = room.hostSession(UlarTanggaSettings.rules, ble: ble)
            room.begin = { [weak room] in
                guard let room, session.canStart() else { return }
                session.start()
                room.ular = UlarTanggaHostTable(session: session, mySeat: 0, transport: ble)
            }
        }
        ble.start()
        return room
    }

    static func join(code: String) -> NearbyRoom {
        let ble = BleGuestTransport(roomCode: code)
        let room = NearbyRoom(code: code, isHost: false, transport: ble)
        let join = GuestJoin(transport: ble, name: deviceName, platform: .ios)
        room.join = join
        room.status = String(localized: "Mencari room…", bundle: .app)
        join.onRejected = { [weak room] in room?.rejected($0) }
        join.onDisconnected = { [weak room] in room?.status = String(localized: "Terputus dari host", bundle: .app) }
        join.onGame = { [weak room, weak join] game in
            guard let room, let join else { return }
            switch game {
            case CongklakGame.shared.id:
                let s: GuestSession<CongklakState, CongklakMove> = room.guestSession(CongklakGame.shared, join: join)
                s.onState = { [weak room, weak s] _ in
                    guard let room, let s, room.congklak == nil else { return }
                    room.congklak = GuestTable(session: s)
                }
                s.replay()
            case UlarTanggaGame.shared.id:
                let s: GuestSession<UlarTanggaState, UlarTanggaMove> = room.guestSession(UlarTanggaGame.shared, join: join)
                s.onState = { [weak room, weak s] _ in
                    guard let room, let s, room.ular == nil else { return }
                    room.ular = UlarTanggaGuestTable(session: s)
                }
                s.replay()
            default:
                room.status = String(localized: "Room ini memainkan permainan yang belum ada di aplikasi ini. Perbarui aplikasi.", bundle: .app)
            }
        }
        ble.start()
        return room
    }
}
