import Foundation
import Shared

/// A new snapshot, plus the move that produced it when it can be animated.
struct CongklakUpdate {
    let state: CongklakState
    let seat: Int32?
    let move: CongklakMove?
}

/// What the congklak screen plays against: pass-and-play or vs computer on this device, a
/// BLE host, or a BLE guest. Session callbacks arrive on the main thread, so this needs no locks.
@MainActor
class CongklakTable: ObservableObject {
    @Published fileprivate(set) var players: [PlayerInfo] = []
    /// Shown under the score (disconnects, refusals), or nil.
    @Published fileprivate(set) var notice: String?
    /// A dropped player the game waits for, with the seconds left; shown instead of `notice`.
    @Published fileprivate(set) var waiting: Waiting?
    /// Latest preset reaction to show as a bubble; nil when none is up.
    @Published fileprivate(set) var emotes: [EmoteEvent] = []

    /// The computer's difficulty, shown next to its name; nil without a computer player.
    fileprivate(set) var botLevel: String?
    fileprivate(set) var active = true

    /// Latest snapshot; the screen starts from it.
    private(set) var latest: CongklakState?
    /// Receives every snapshot after `latest`, in order.
    var onUpdate: ((CongklakUpdate) -> Void)?

    /// Seat whose side faces this device; nil for pass-and-play (whoever's turn it is).
    var mySeat: Int32? { nil }
    var canRestart: Bool { false }
    /// True when a move waits for the host's snapshot instead of applying at once.
    var isRemote: Bool { false }
    /// Nearby/online rooms can send preset reactions; pass-and-play and vs computer cannot.
    var canEmote: Bool { false }

    fileprivate func deliver(_ state: CongklakState, seat: Int32?, move: CongklakMove?) {
        guard active else { return }
        latest = state
        onUpdate?(CongklakUpdate(state: state, seat: seat, move: move))
    }

    fileprivate func showEmote(seat: Int, id: String) {
        guard active else { return }
        emotes = emotes.plusEmote(seat: seat, id: id)
    }

    /// Sow from `hole` for `seat`. Returns false when nothing was sent.
    func play(seat: Int32, hole: Int32) -> Bool { false }
    func restart() {}
    func leave() {
        active = false
        onUpdate = nil
        emotes = []
    }
    /// Lets a computer player move if it is its turn. Called once the board is idle.
    func stepBot() {}
    /// Sends a preset reaction from this device's seat. No-op when `canEmote` is false.
    func sendEmote(_ id: String) {}
}

/// The authoritative table: pass-and-play, vs computer (no transport peers) or hosting a BLE room.
final class HostTable: CongklakTable {
    private let session: HostSession<CongklakState, CongklakMove>
    private let transport: Transport
    private let seat: Int32?
    private var seq: Int32
    private var ticker: SecondTicker?

    init(session: HostSession<CongklakState, CongklakMove>, mySeat: Int32?, transport: Transport) {
        self.session = session
        self.transport = transport
        seat = mySeat
        seq = session.seq
        super.init()
        players = session.players
        session.onPlayersChanged = { [weak self] list in
            guard let self else { return }
            self.players = list
            self.notice = list.contains { !$0.connected } ? String(localized: "The other player disconnected. Waiting for them to return…", bundle: .app) : nil
        }
        session.onStateChanged = { [weak self, weak session] state in
            guard let self, let session, let state else { return }
            // Rebroadcasts (e.g. after a disconnect) keep seq; only animate a fresh move.
            let fresh = session.seq == self.seq + 1
            self.seq = session.seq
            self.deliver(state, seat: fresh ? session.lastSeat?.int32Value : nil, move: fresh ? session.lastMove : nil)
        }
        session.onEmote = { [weak self] seat, id in self?.showEmote(seat: seat.intValue, id: id) }
        if let state = session.state { deliver(state, seat: nil, move: nil) }
        if !(transport is LocalTransport) {
            // Counts down a dropped guest's wait, then lets the computer play that seat.
            ticker = SecondTicker { [weak self, weak session] in
                guard let self, let session else { return }
                for s in session.expiredSeats() {
                    _ = session.replaceWithBot(seat: s.int32Value, bot: CongklakBot(level: .normal))
                }
                self.waiting = hostWaiting(players: session.players) { session.handOffInMs(seat: $0) }
            }
            ticker?.start()
        }
    }

    override var mySeat: Int32? { seat }
    override var canRestart: Bool { true }
    override var canEmote: Bool { !(transport is LocalTransport) }

    override func play(seat: Int32, hole: Int32) -> Bool {
        active && session.submitLocal(seat: seat, move: CongklakMove(hole: hole)) == nil
    }

    override func restart() {
        guard active, let s = session.state, CongklakGame.shared.isFinished(state: s) else { return }
        session.restart()
    }

    override func stepBot() { if active { _ = session.stepBot() } }

    override func sendEmote(_ id: String) {
        guard active, canEmote, let seat else { return }
        _ = session.sendEmote(seat: seat, id: id)
    }

    override func leave() {
        guard active else { return }
        super.leave()
        ticker?.stop()
        transport.close()
    }

    static func passAndPlay(names: [String]) -> HostTable {
        let transport = LocalTransport()
        let session = HostSession<CongklakState, CongklakMove>(rules: CongklakSettings.rules, transport: transport, roomCode: "LOCAL")
        for name in names { _ = session.addLocalPlayer(name: name, platform: .ios) }
        session.start()
        return HostTable(session: session, mySeat: nil, transport: transport)
    }

    /// You (seat 0, moving first) against a `CongklakBot` on this device.
    static func vsComputer(you: String, computer: String, level: CongklakBot.Level, levelName: String) -> HostTable {
        let transport = LocalTransport()
        let session = HostSession<CongklakState, CongklakMove>(rules: CongklakSettings.rules, transport: transport, roomCode: "LOCAL")
        _ = session.addLocalPlayer(name: you, platform: .ios)
        _ = session.addBot(name: computer, platform: .ios, bot: CongklakBot(level: level))
        session.start()
        let table = HostTable(session: session, mySeat: 0, transport: transport)
        table.botLevel = levelName
        return table
    }
}

/// A remote player's table: moves go to the host, snapshots come back.
final class GuestTable: CongklakTable {
    private let session: GuestSession<CongklakState, CongklakMove>
    private var away = GuestWaiting()
    private var ticker: SecondTicker?

    init(session: GuestSession<CongklakState, CongklakMove>) {
        self.session = session
        super.init()
        players = session.players
        away.players(players)
        session.onState = { [weak self, weak session] state in
            guard let self, let session, let state else { return }
            self.players = session.players
            self.away.players(self.players)
            self.notice = nil
            self.deliver(state, seat: session.lastSeat?.int32Value, move: session.lastMove)
        }
        session.onInvalid = { [weak self] _ in self?.notice = String(localized: "Move refused", bundle: .app) }
        session.onPlayers = { [weak self] list in
            guard let self else { return }
            self.players = list
            self.away.players(list)
            self.notice = nil
        }
        session.onDisconnected = { [weak self] in self?.away.hostLost() }
        session.onEmote = { [weak self] seat, id in self?.showEmote(seat: seat.intValue, id: id) }
        if let state = session.state { deliver(state, seat: nil, move: nil) }
        // The host's and other guests' waits; past the host's wait the game is over ("host left").
        ticker = SecondTicker { [weak self] in
            guard let self else { return }
            let w = self.away.current(self.players)
            self.waiting = w.flatMap { $0.seconds > 0 ? $0 : nil }
            if let w, w.host, w.seconds == 0, self.notice == nil {
                self.notice = String(localized: "Disconnected from the host", bundle: .app)
            }
        }
        ticker?.start()
    }

    override var mySeat: Int32? { session.seat?.int32Value }
    override var isRemote: Bool { true }
    override var canEmote: Bool { true }

    override func play(seat: Int32, hole: Int32) -> Bool {
        active && session.sendMove(move: CongklakMove(hole: hole)) != nil
    }

    override func sendEmote(_ id: String) {
        if active { session.sendEmote(id: id) }
    }

    override func leave() {
        guard active else { return }
        super.leave()
        ticker?.stop()
        // Leaving a running game keeps the seat to rejoin; after the end there is nothing to go back to.
        if session.finished, let code = session.roomCode { LastRoom.shared.clear(code) }
        session.leave()
    }
}

/// Congklak options chosen on the menu before a game, kept on this device. Only a host
/// reads them; guests play by the rule in the host's state.
enum CongklakSettings {
    static let tembakAfterStoreKey = "congklak.tembakAfterStore"

    /// The Indonesian tembak rule (only after passing your own store); on by default.
    static var tembakAfterStore: Bool {
        UserDefaults.standard.object(forKey: tembakAfterStoreKey) as? Bool ?? true
    }

    static let starterKey = "congklak.starter"

    /// Who moves first: "me" (seat 0), "opponent" or "random" (default). Same values as Android.
    static var starter: CongklakStarter {
        switch UserDefaults.standard.string(forKey: starterKey) {
        case "me": return .me
        case "opponent": return .opponent
        default: return .random
        }
    }

    /// Rules for a new host session, with the chosen tembak rule and starter.
    static var rules: CongklakRules { CongklakRules(tembakAfterStore: tembakAfterStore, starter: starter) }
}
