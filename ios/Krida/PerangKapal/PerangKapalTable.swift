import Foundation
import Shared

/// A new snapshot; `fresh` when it directly follows the one shown.
struct PerangKapalUpdate {
    let state: PerangKapalState
    let fresh: Bool
}

/// What the perang kapal screen plays on: this phone against the computer, hosting a room, or
/// a guest. Session callbacks arrive on the main thread. The host screen gets a per-seat
/// fog-of-war view; the session keeps the full state for the bots. Same as Android's
/// `PerangKapalTable`.
@MainActor
class PerangKapalTable: ObservableObject {
    @Published fileprivate(set) var players: [PlayerInfo] = []
    /// Shown under the status (disconnects, refusals), or nil.
    @Published fileprivate(set) var notice: String?
    /// A dropped player the game waits for, with the seconds left; shown instead of `notice`.
    @Published fileprivate(set) var waiting: Waiting?
    /// Latest preset reaction to show as a bubble; nil when none is up.
    @Published fileprivate(set) var emotes: [EmoteEvent] = []
    /// Latest snapshot; the screen starts from it.
    private(set) var latest: PerangKapalState
    /// Receives every snapshot after `latest`, in order.
    var onUpdate: ((PerangKapalUpdate) -> Void)?

    fileprivate(set) var active = true
    private var seq: Int32

    /// This device's seat; nil when two people pass one phone (whoever's turn it is).
    var mySeat: Int? { nil }
    var canRestart: Bool { false }
    /// Nearby/online rooms can send preset reactions; games on this phone only cannot.
    var canEmote: Bool { false }

    /// Seats the computer plays (including dropped guests handed to it).
    var botSeats: Set<Int> { Set(players.filter(\.bot).map { Int($0.seat) }) }

    fileprivate init(first: PerangKapalState, seq: Int32) {
        latest = first
        self.seq = seq
    }

    fileprivate func deliver(_ state: PerangKapalState, seq: Int32) {
        guard active else { return }
        let fresh = seq == self.seq + 1
        self.seq = seq
        latest = state
        onUpdate?(PerangKapalUpdate(state: state, fresh: fresh))
    }

    fileprivate func showEmote(seat: Int, id: String) {
        guard active else { return }
        emotes = emotes.plusEmote(seat: seat, id: id)
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

    /// The seat can be played from this device.
    func isLocal(_ seat: Int) -> Bool {
        !botSeats.contains(seat) && (mySeat == nil || mySeat == seat)
    }

    /// Puts ship `kind` with its bow on `cell`. Returns false when nothing was sent.
    func place(seat: Int, kind: Int, cell: Int, horizontal: Bool) -> Bool { false }
    /// Locks the fleet in. Returns false when nothing was sent.
    func ready(seat: Int) -> Bool { false }
    /// Fires at `cell` on the opponent's grid. Returns false when nothing was sent.
    func fire(seat: Int, cell: Int) -> Bool { false }
    /// Lets a computer place or fire if it may act.
    func stepBot() {}
    func restart() {}
    func leave() { active = false; onUpdate = nil; emotes = [] }
    /// Sends a preset reaction from this device's seat. No-op when `canEmote` is false.
    func sendEmote(_ id: String) {}

    fileprivate static func placeMove(kind: Int, cell: Int, horizontal: Bool) -> PerangKapalMove {
        PerangKapalMove(kind: KotlinInt(int: Int32(kind)), cell: KotlinInt(int: Int32(cell)),
                        horizontal: horizontal, ready: false, fire: nil, fleet: nil)
    }
    fileprivate static var readyMove: PerangKapalMove {
        PerangKapalMove(kind: nil, cell: nil, horizontal: true, ready: true, fire: nil, fleet: nil)
    }
    fileprivate static func fireMove(_ cell: Int) -> PerangKapalMove {
        PerangKapalMove(kind: nil, cell: nil, horizontal: true, ready: false, fire: KotlinInt(int: Int32(cell)), fleet: nil)
    }
}

/// The authoritative table: on this device only, or hosting a room.
final class PerangKapalHostTable: PerangKapalTable {
    private let session: HostSession<PerangKapalState, PerangKapalMove>
    private let transport: Transport
    private let seat: Int?
    private var ticker: SecondTicker?

    init(session: HostSession<PerangKapalState, PerangKapalMove>, mySeat: Int?, transport: Transport) {
        self.session = session
        self.transport = transport
        seat = mySeat
        super.init(first: Self.view(session.state!, mySeat), seq: session.seq)
        players = session.players
        session.onPlayersChanged = { [weak self] list in self?.playersChanged(list) }
        session.onStateChanged = { [weak self, weak session] state in
            guard let self, let session, let state else { return }
            self.playersChanged(session.players)
            self.deliver(Self.view(state, mySeat), seq: session.seq)
        }
        session.onEmote = { [weak self] seat, id in self?.showEmote(seat: seat.intValue, id: id) }
        if !(transport is LocalTransport) {
            ticker = SecondTicker { [weak self, weak session] in
                guard let self, let session else { return }
                for s in session.expiredSeats() {
                    _ = session.replaceWithBot(seat: s.int32Value, bot: PerangKapalBot())
                }
                self.waiting = hostWaiting(players: session.players) { session.handOffInMs(seat: $0) }
            }
            ticker?.start()
        }
    }

    /// What this device may see: its own fleet, and of the opponent's only what was sunk.
    /// With two people on one phone the screen gets everything and hides per turn itself.
    private static func view(_ state: PerangKapalState, _ seat: Int?) -> PerangKapalState {
        guard let seat else { return state }
        return PerangKapalGame.shared.viewFor(state: state, seat: Int32(seat))
    }

    override var mySeat: Int? { seat }
    override var canRestart: Bool { true }
    override var canEmote: Bool { !(transport is LocalTransport) }

    override func place(seat: Int, kind: Int, cell: Int, horizontal: Bool) -> Bool {
        active && session.submitLocal(seat: Int32(seat), move: Self.placeMove(kind: kind, cell: cell, horizontal: horizontal)) == nil
    }

    override func ready(seat: Int) -> Bool {
        active && session.submitLocal(seat: Int32(seat), move: Self.readyMove) == nil
    }

    override func fire(seat: Int, cell: Int) -> Bool {
        active && session.submitLocal(seat: Int32(seat), move: Self.fireMove(cell)) == nil
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

    /// This phone: you against the computer.
    static func vsComputer(you: String, computer: String) -> PerangKapalTable {
        let transport = LocalTransport()
        let session = HostSession<PerangKapalState, PerangKapalMove>(
            rules: StandardRules.shared.perangKapal, transport: transport, roomCode: "LOCAL")
        _ = session.addLocalPlayer(name: you, platform: .ios)
        _ = session.addBot(name: computer, platform: .ios, bot: PerangKapalBot())
        session.start()
        return PerangKapalHostTable(session: session, mySeat: 0, transport: transport)
    }

    /// This phone: two people, passing it for each turn.
    static func passAndPlay(names: [String]) -> PerangKapalTable {
        let transport = LocalTransport()
        let session = HostSession<PerangKapalState, PerangKapalMove>(
            rules: StandardRules.shared.perangKapal, transport: transport, roomCode: "LOCAL")
        for name in names { _ = session.addLocalPlayer(name: name, platform: .ios) }
        session.start()
        return PerangKapalHostTable(session: session, mySeat: nil, transport: transport)
    }
}

/// A guest in a room: placements and shots go to the host, fog-of-war snapshots come back.
final class PerangKapalGuestTable: PerangKapalTable {
    private let session: GuestSession<PerangKapalState, PerangKapalMove>
    private var away = GuestWaiting()
    private var ticker: SecondTicker?

    init(session: GuestSession<PerangKapalState, PerangKapalMove>) {
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

    override func place(seat: Int, kind: Int, cell: Int, horizontal: Bool) -> Bool {
        active && seat == mySeat && session.sendMove(move: Self.placeMove(kind: kind, cell: cell, horizontal: horizontal)) != nil
    }

    override func ready(seat: Int) -> Bool {
        active && seat == mySeat && session.sendMove(move: Self.readyMove) != nil
    }

    override func fire(seat: Int, cell: Int) -> Bool {
        active && seat == mySeat && session.sendMove(move: Self.fireMove(cell)) != nil
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
