import Foundation
import Shared
import UIKit

/// A room over BLE ("Teman dekat" on the play card) or through the online relay ("Online"),
/// from hosting or joining until the game starts. Then `congklak` or `ular` takes over the session and that game's screen shows it.
/// A guest joins by code and learns the game from the host's Welcome. Same as Android.
@MainActor
final class NearbyRoom: ObservableObject {
    enum Game { case congklak, ularTangga }
    /// Where the room stands; a guest goes searching → connecting → lobby, or stops at a
    /// failure. offline: the relay couldn't be reached (online only, host or guest).
    enum Stage { case searching, connecting, lobby, full, started, version, notFound, lost, unknownGame, refused, offline }

    let code: String
    let isHost: Bool
    /// Through the relay rather than over Bluetooth.
    let online: Bool
    /// The room's game id (`GameRules.id`); nil for a guest until the host welcomes it.
    @Published private(set) var game: String?
    @Published private(set) var players: [PlayerInfo] = []
    @Published private(set) var maxPlayers = 2
    @Published private(set) var canStart = false
    @Published private(set) var stage: Stage
    /// This device's seat: 0 for the host, the host's choice for a guest (nil until then).
    @Published private(set) var mySeat: Int?
    /// Pawn colours to pick from in the lobby (ular tangga); 0 when the game has none.
    @Published private(set) var colorCount = 0
    /// The host can fill empty seats with computers (ular tangga only).
    @Published private(set) var canAddBots = false
    @Published private(set) var congklak: CongklakTable?
    @Published private(set) var ular: UlarTanggaTable?

    private let transport: Transport
    private var begin: (() -> Void)?
    private var guest: AnyObject?
    private var guestLeave: (() -> Void)?
    private var join: GuestJoin?
    private var pickColorAction: ((Int) -> Void)?
    private var addBotAction: ((String) -> Void)?
    private var removeBotAction: ((Int) -> Void)?
    private var notFoundTask: Task<Void, Never>?
    /// What the host chose, for `retry`.
    private var hostGame: Game?

    /// A guest that hasn't reached the host by then gives up; online may be unreachable.
    private static let notFoundSeconds = 20

    var failed: Bool { ![.searching, .connecting, .lobby].contains(stage) }

    private init(code: String, isHost: Bool, online: Bool, transport: Transport) {
        self.code = code
        self.isHost = isHost
        self.online = online
        self.transport = transport
        stage = isHost ? .lobby : .searching
        mySeat = isHost ? 0 : nil
    }

    func start() { begin?() }

    /// Asks for pawn colour `color`: the host takes it at once, a guest asks the host.
    func pickColor(_ color: Int) { pickColorAction?(color) }

    /// Seats a computer called `name` in the next empty seat (host, ular tangga).
    func addBot(name: String) { addBotAction?(name) }

    func removeBot(seat: Int) { removeBotAction?(seat) }

    func leave() {
        notFoundTask?.cancel()
        // A guest leaving the lobby gives up its seat; leaving a running game (the tables
        // handle that) or a search keeps it to come back to. Same as Android.
        if !isHost, congklak == nil, ular == nil, stage == .lobby { LastRoom.shared.clear(code) }
        if let congklak { congklak.leave() }
        else if let ular { ular.leave() }
        else if let guestLeave { guestLeave() }
        else if let join { join.leave() }
        else { transport.close() }
    }

    /// Leaves and opens the same kind of room again: a new one for a host, the same code for a guest.
    func retry() -> NearbyRoom {
        leave()
        if isHost, let hostGame { return Self.host(game: hostGame, online: online) }
        return Self.join(code: code, online: online)
    }

    private static var deviceName: String { UIDevice.current.name }

    private func hostSession<S: AnyObject, M: AnyObject>(_ rules: any GameRules, transport: Transport) -> HostSession<S, M> {
        game = rules.id
        maxPlayers = Int(rules.maxPlayers)
        let session = HostSession<S, M>(rules: rules, transport: transport, roomCode: code)
        colorCount = Int(session.colorCount)
        session.onPlayersChanged = { [weak self, weak session] list in
            guard let self, let session else { return }
            self.players = list
            self.canStart = session.canStart()
        }
        _ = session.addLocalPlayer(name: Self.deviceName, platform: .ios)
        pickColorAction = { [weak session] in _ = session?.setColor(seat: 0, color: Int32($0)) }
        return session
    }

    private func guestSession<S: AnyObject, M: AnyObject>(_ rules: any GameRules, join: GuestJoin) -> GuestSession<S, M> {
        game = rules.id
        maxPlayers = Int(rules.maxPlayers)
        colorCount = Int((rules as? SeatColorRules)?.colorCount ?? 0)
        let session = GuestSession<S, M>(rules: rules, join: join)
        session.onLobby = { [weak self, weak session] list in
            guard let self else { return }
            self.players = list
            self.mySeat = session?.seat.map { $0.intValue }
            self.stage = .lobby
        }
        session.onRejected = { [weak self] in self?.rejected($0) }
        session.onDisconnected = { [weak self] in self?.lost() }
        pickColorAction = { [weak session] in session?.pickColor(color: Int32($0)) }
        guest = session
        guestLeave = { [weak session] in session?.leave() }
        return session
    }

    private func rejected(_ reason: String) {
        notFoundTask?.cancel()
        LastRoom.shared.clear(code)
        stage = switch reason {
        case RejectReason.shared.VERSION: .version
        case RejectReason.shared.FULL: .full
        case RejectReason.shared.STARTED: .started
        default: .refused
        }
    }

    /// The relay closed a guest's socket for good; the game's table shows drops once it runs.
    private func relayClosed(_ closeCode: Int) {
        notFoundTask?.cancel()
        if failed || congklak != nil || ular != nil { return }
        if closeCode != OnlineTransport.gaveUp { LastRoom.shared.clear(code) }
        stage = switch closeCode {
        case OnlineTransport.noRoom: .notFound
        case OnlineTransport.full: .full
        case OnlineTransport.kicked: .refused
        case OnlineTransport.gaveUp: stage == .searching ? .offline : .lost
        default: .lost
        }
    }

    private func lost() {
        notFoundTask?.cancel()
        if !failed { stage = .lost }
    }

    static func host(game: Game, online: Bool = false, code: String? = nil) -> NearbyRoom {
        let roomCode = code ?? (online ? RoomCode.shared.generateOnline() : RoomCode.shared.generate())
        let relay = online ? OnlineHostTransport(code: roomCode) : nil
        let ble = online ? nil : BleHostTransport(roomCode: roomCode)
        let transport: Transport = (relay as Transport?) ?? ble!
        let room = NearbyRoom(code: roomCode, isHost: true, online: online, transport: transport)
        room.hostGame = game
        switch game {
        case .congklak:
            let session: HostSession<CongklakState, CongklakMove> = room.hostSession(CongklakSettings.rules, transport: transport)
            room.begin = { [weak room] in
                guard let room, session.canStart() else { return }
                session.start()
                room.congklak = HostTable(session: session, mySeat: 0, transport: transport)
            }
        case .ularTangga:
            let session: HostSession<UlarTanggaState, UlarTanggaMove> = room.hostSession(UlarTanggaSettings.rules, transport: transport)
            room.begin = { [weak room] in
                guard let room, session.canStart() else { return }
                session.start()
                room.ular = UlarTanggaHostTable(session: session, mySeat: 0, transport: transport)
            }
            room.canAddBots = true
            room.addBotAction = { [weak session] name in _ = session?.addBot(name: name, platform: .ios, bot: UlarTanggaBot.shared) }
            room.removeBotAction = { [weak session] seat in _ = session?.removeBot(seat: Int32(seat)) }
        }
        if let relay {
            // Only before the game: once it runs, the table shows the drop.
            relay.onClosed = { [weak room] _ in
                guard let room, room.congklak == nil, room.ular == nil else { return }
                room.stage = .offline
            }
            relay.start()
        }
        ble?.start()
        return room
    }

    static func join(code: String, online: Bool = false) -> NearbyRoom {
        let relay = online ? OnlineGuestTransport(code: code) : nil
        let ble = online ? nil : BleGuestTransport(roomCode: code)
        let transport: Transport = (relay as Transport?) ?? ble!
        let room = NearbyRoom(code: code, isHost: false, online: online, transport: transport)
        // Joining a room this device has a seat in asks for that seat back.
        let join = GuestJoin(transport: transport, name: deviceName, platform: .ios,
                             rejoinToken: LastRoom.shared.token(for: code, online: online))
        room.join = join
        join.onConnected = { [weak room] in if room?.stage == .searching { room?.stage = .connecting } }
        join.onRejected = { [weak room] in room?.rejected($0) }
        join.onDisconnected = { [weak room] in room?.lost() }
        join.onGame = { [weak room, weak join] game in
            guard let room, let join else { return }
            if let token = join.rejoinToken {
                LastRoom.shared.save(code: code, online: online, game: game, token: token)
            }
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
                room.stage = .unknownGame
            }
        }
        room.notFoundTask = Task { [weak room] in
            try? await Task.sleep(for: .seconds(notFoundSeconds))
            guard !Task.isCancelled, let room, room.stage == .searching else { return }
            // Only the relay's noRoom response proves an online room is missing.
            room.stage = room.online ? .offline : .notFound
            room.leave()
        }
        if let relay {
            relay.onClosed = { [weak room] in room?.relayClosed($0) }
            relay.start()
        }
        ble?.start()
        return room
    }
}
