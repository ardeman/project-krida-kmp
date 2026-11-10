import Foundation
import Shared

/// A new snapshot; `fresh` when it follows a roll or pawn move that can be animated.
struct LudoUpdate {
    let state: LudoState
    let fresh: Bool
}

/// What the ludo screen plays on: this phone, hosting a room, or a guest. Session callbacks
/// arrive on the main thread. Same as Android's `LudoTable`.
@MainActor
class LudoTable: ObservableObject {
    @Published fileprivate(set) var players: [PlayerInfo] = []
    /// Shown under the status (disconnects, refusals), or nil.
    @Published fileprivate(set) var notice: String?
    /// A dropped player the game waits for, with the seconds left; shown instead of `notice`.
    @Published fileprivate(set) var waiting: Waiting?
    /// Latest preset reaction to show as a bubble; nil when none is up.
    @Published fileprivate(set) var emote: (seat: Int, id: String)?
    /// Latest snapshot; the screen starts from it.
    private(set) var latest: LudoState
    /// Receives every snapshot after `latest`, in order.
    var onUpdate: ((LudoUpdate) -> Void)?

    fileprivate(set) var active = true
    private var seq: Int32

    /// This device's seat; nil for pass-and-play (whoever's turn it is).
    var mySeat: Int? { nil }
    var canRestart: Bool { false }
    /// Nearby/online rooms can send preset reactions; games on this phone only cannot.
    var canEmote: Bool { false }

    /// Seats the computer plays (including dropped guests handed to it).
    var botSeats: Set<Int> { Set(players.filter(\.bot).map { Int($0.seat) }) }

    fileprivate init(first: LudoState, seq: Int32) {
        latest = first
        self.seq = seq
    }

    fileprivate func deliver(_ state: LudoState, seq: Int32) {
        guard active else { return }
        // Rebroadcasts keep seq, and a restart's first state has no roll yet; only a roll
        // right after the shown state animates.
        let fresh = seq == self.seq + 1 && state.lastRoll != nil
        self.seq = seq
        latest = state
        onUpdate?(LudoUpdate(state: state, fresh: fresh))
    }

    fileprivate func showEmote(seat: Int, id: String) {
        guard active else { return }
        emote = (seat, id)
    }

    fileprivate func playersChanged(_ list: [PlayerInfo]) {
        players = list
        notice = list.contains { !$0.connected }
            ? String(localized: "A player disconnected. The computer takes over after 15 seconds.", bundle: .app)
            : nil
    }

    func name(_ seat: Int) -> String {
        players.first { Int($0.seat) == seat }?.name ?? "\(seat + 1)"
    }

    /// The seat can be played from this device (own seat, or any person in pass-and-play).
    func isLocal(_ seat: Int) -> Bool {
        !botSeats.contains(seat) && (mySeat == nil || mySeat == seat)
    }

    /// Rolls for `seat`. Returns false when nothing was sent.
    func roll(seat: Int) -> Bool { false }
    /// Moves `pawn` (0...3) for `seat` with the pending roll. Returns false when nothing was sent.
    func movePawn(seat: Int, pawn: Int) -> Bool { false }
    /// Lets a computer roll or move if it is its turn. Called once the board is idle.
    func stepBot() {}
    func restart() {}
    func leave() { active = false; onUpdate = nil; emote = nil }
    /// Sends a preset reaction from this device's seat. No-op when `canEmote` is false.
    func sendEmote(_ id: String) {}
}

/// The authoritative table: on this device only, or hosting a nearby room.
final class LudoHostTable: LudoTable {
    private let session: HostSession<LudoState, LudoMove>
    private let transport: Transport
    private let seat: Int?
    private var ticker: SecondTicker?

    init(session: HostSession<LudoState, LudoMove>, mySeat: Int?, transport: Transport) {
        self.session = session
        self.transport = transport
        seat = mySeat
        super.init(first: session.state!, seq: session.seq)
        players = session.players
        session.onPlayersChanged = { [weak self] list in self?.playersChanged(list) }
        session.onStateChanged = { [weak self, weak session] state in
            guard let self, let session, let state else { return }
            self.playersChanged(session.players)
            self.deliver(state, seq: session.seq)
        }
        session.onEmote = { [weak self] seat, id in self?.showEmote(seat: seat.intValue, id: id) }
        if !(transport is LocalTransport) {
            ticker = SecondTicker { [weak self, weak session] in
                guard let self, let session else { return }
                for s in session.expiredSeats() {
                    _ = session.replaceWithBot(seat: s.int32Value, bot: LudoBot(level: .normal))
                }
                self.waiting = hostWaiting(players: session.players) { session.handOffInMs(seat: $0) }
            }
            ticker?.start()
        }
    }

    override var mySeat: Int? { seat }
    override var canRestart: Bool { true }
    override var canEmote: Bool { !(transport is LocalTransport) }

    override func roll(seat: Int) -> Bool {
        active && session.submitLocal(seat: Int32(seat), move: LudoMove(pawn: nil)) == nil
    }

    override func movePawn(seat: Int, pawn: Int) -> Bool {
        active && session.submitLocal(seat: Int32(seat), move: LudoMove(pawn: KotlinInt(int: Int32(pawn)))) == nil
    }

    override func stepBot() { if active { _ = session.stepBot() } }

    override func restart() {
        if active && latest.finished { session.restart() }
    }

    override func sendEmote(_ id: String) {
        guard active, canEmote, let seat else { return }
        _ = session.sendEmote(seat: Int32(seat), id: id)
    }

    override func leave() {
        guard active else { return }
        super.leave()
        ticker?.stop()
        transport.close()
    }

    /// A game on this phone: one seat per `players` entry (a name, and whether a computer plays
    /// it) with that entry's token colour. With a single person their seat is "you"; with more
    /// the phone goes round. Same as Android.
    static func local(players: [LocalPlayer]) -> LudoTable {
        let transport = LocalTransport()
        let session = HostSession<LudoState, LudoMove>(rules: LudoSettings.rules, transport: transport, roomCode: "LOCAL")
        for p in players {
            if p.computer {
                _ = session.addBot(name: p.name, platform: .ios, bot: LudoBot(level: .normal))
            } else {
                _ = session.addLocalPlayer(name: p.name, platform: .ios)
            }
        }
        for seat in players.indices.reversed() { _ = session.setColor(seat: Int32(seat), color: Int32(players[seat].color)) }
        session.start()
        let people = players.indices.filter { !players[$0].computer }
        return LudoHostTable(session: session, mySeat: people.count == 1 ? people[0] : nil, transport: transport)
    }
}

/// A guest in a nearby room: rolls and pawn picks go to the host, snapshots come back.
final class LudoGuestTable: LudoTable {
    private let session: GuestSession<LudoState, LudoMove>
    private var away = GuestWaiting()
    private var ticker: SecondTicker?

    init(session: GuestSession<LudoState, LudoMove>) {
        self.session = session
        super.init(first: session.state!, seq: session.stateSeq)
        players = session.players
        away.players(players)
        session.onState = { [weak self, weak session] state in
            guard let self, let session, let state else { return }
            self.playersChanged(session.players)
            self.away.players(session.players)
            self.deliver(state, seq: session.stateSeq)
        }
        session.onPlayers = { [weak self] list in
            self?.playersChanged(list)
            self?.away.players(list)
        }
        session.onInvalid = { [weak self] _ in self?.notice = String(localized: "Move refused", bundle: .app) }
        session.onDisconnected = { [weak self] in self?.away.hostLost() }
        session.onEmote = { [weak self] seat, id in self?.showEmote(seat: seat.intValue, id: id) }
        ticker = SecondTicker { [weak self] in
            guard let self else { return }
            let w = self.away.current(self.players)
            self.waiting = w.flatMap { $0.seconds > 0 ? $0 : nil }
            if let w, w.host, w.seconds == 0 {
                self.notice = String(localized: "Disconnected from the host", bundle: .app)
            }
        }
        ticker?.start()
    }

    override var mySeat: Int? { session.seat.map { Int($0.int32Value) } }
    override var canEmote: Bool { true }

    override func roll(seat: Int) -> Bool {
        active && seat == mySeat && session.sendMove(move: LudoMove(pawn: nil)) != nil
    }

    override func movePawn(seat: Int, pawn: Int) -> Bool {
        active && seat == mySeat && session.sendMove(move: LudoMove(pawn: KotlinInt(int: Int32(pawn)))) != nil
    }

    override func sendEmote(_ id: String) {
        if active { session.sendEmote(id: id) }
    }

    override func leave() {
        guard active else { return }
        super.leave()
        ticker?.stop()
        if session.finished, let code = session.roomCode { LastRoom.shared.clear(code) }
        session.leave()
    }
}

/// Ludo choices stored on this device: the seat tiles and the rule options. Same keys
/// and defaults as Android (`sixToEnter`, `sixRollsAgain` and `captureRollsAgain` on).
enum LudoSettings {
    static let seatsKey = "ludo.seats"
    static let sixToEnterKey = "ludo.sixToEnter"
    static let sixRollsAgainKey = "ludo.sixRollsAgain"
    static let captureRollsAgainKey = "ludo.captureRollsAgain"
    static let firstFinishEndsKey = "ludo.firstFinishEnds"

    private static func flag(_ key: String, _ fallback: Bool) -> Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? fallback
    }

    static var options: LudoOptions {
        LudoOptions(
            sixToEnter: flag(sixToEnterKey, true),
            sixRollsAgain: flag(sixRollsAgainKey, true),
            captureRollsAgain: flag(captureRollsAgainKey, true),
            firstFinishEnds: flag(firstFinishEndsKey, false)
        )
    }

    /// Rules for a new host session, with the chosen options.
    static var rules: LudoRules { LudoRules(options: options) }
}
