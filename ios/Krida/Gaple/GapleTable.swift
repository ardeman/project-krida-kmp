import Foundation
import Shared

/// A new snapshot; `fresh` when it directly follows a tile being played, so it can animate.
struct GapleUpdate {
    let state: GapleState
    let fresh: Bool
}

/// What the gaple screen plays on: this phone (you against computers), hosting a room, or a
/// guest. Session callbacks arrive on the main thread. `latest` is what this phone may see: a
/// host's full state is shown through the screen, which only draws `mySeat`'s hand. Same as
/// Android's `GapleTable`.
@MainActor
class GapleTable: ObservableObject {
    @Published fileprivate(set) var players: [PlayerInfo] = []
    @Published fileprivate(set) var notice: String?
    @Published fileprivate(set) var waiting: Waiting?
    @Published fileprivate(set) var emotes: [EmoteEvent] = []
    private(set) var latest: GapleState
    var onUpdate: ((GapleUpdate) -> Void)?

    fileprivate(set) var active = true
    private var seq: Int32

    var mySeat: Int { 0 }
    /// Deals the next round and restarts a finished match.
    var isHost: Bool { false }
    var canEmote: Bool { false }

    fileprivate init(first: GapleState, seq: Int32) {
        latest = first
        self.seq = seq
    }

    fileprivate func deliver(_ state: GapleState, seq: Int32) {
        guard active else { return }
        let fresh = seq == self.seq + 1 && state.lastTile != nil && state.round == latest.round
        self.seq = seq
        latest = state
        onUpdate?(GapleUpdate(state: state, fresh: fresh))
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

    /// Plays `tile` on `end` (nil when it fits only one end or opens the line).
    func play(_ tile: GapleTile, end: Int?) -> Bool { false }
    func nextRound() -> Bool { false }
    func stepBot() {}
    func restart() {}
    func leave() { active = false; onUpdate = nil; emotes = [] }
    func sendEmote(_ id: String) {}
}

final class GapleHostTable: GapleTable {
    private let session: HostSession<GapleState, GapleMove>
    private let transport: Transport
    private let seat: Int
    private var ticker: SecondTicker?

    init(session: HostSession<GapleState, GapleMove>, mySeat: Int, transport: Transport) {
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
                    _ = session.replaceWithBot(seat: s.int32Value, bot: GapleBot(level: .normal))
                }
                self.waiting = hostWaiting(players: session.players) { session.handOffInMs(seat: $0) }
            }
            ticker?.start()
        }
    }

    override var mySeat: Int { seat }
    override var isHost: Bool { true }
    override var canEmote: Bool { !(transport is LocalTransport) }

    override func play(_ tile: GapleTile, end: Int?) -> Bool {
        let move = GapleMove(tile: tile, end: end.map { KotlinInt(int: Int32($0)) }, next: false)
        return active && session.submitLocal(seat: Int32(seat), move: move) == nil
    }

    override func nextRound() -> Bool {
        active && session.submitLocal(seat: Int32(seat), move: GapleMove(tile: nil, end: nil, next: true)) == nil
    }

    override func stepBot() { if active { _ = session.stepBot() } }

    override func restart() {
        if active && latest.finished { session.restart() }
    }

    override func sendEmote(_ id: String) {
        guard active, canEmote else { return }
        _ = session.sendEmote(seat: Int32(seat), id: id)
    }

    override func leave() {
        guard active else { return }
        super.leave()
        ticker?.stop()
        transport.close()
    }

    /// You in seat 0 against `computers` computers, named by `computerName` (seat 1...).
    static func vsComputer(you: String, computers: Int, computerName: (Int) -> String) -> GapleTable {
        let transport = LocalTransport()
        let session = HostSession<GapleState, GapleMove>(rules: GapleSettings.rules, transport: transport, roomCode: "LOCAL")
        _ = session.addLocalPlayer(name: you, platform: .ios)
        for i in 1...max(computers, 1) {
            _ = session.addBot(name: computerName(i), platform: .ios, bot: GapleBot(level: .normal))
        }
        session.start()
        return GapleHostTable(session: session, mySeat: 0, transport: transport)
    }
}

/// A guest in a room: tiles go to the host, snapshots (with only this hand) come back.
final class GapleGuestTable: GapleTable {
    private let session: GuestSession<GapleState, GapleMove>
    private var away = GuestWaiting()
    private var ticker: SecondTicker?

    init(session: GuestSession<GapleState, GapleMove>) {
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

    override var mySeat: Int { session.seat.map { Int($0.int32Value) } ?? 0 }
    override var canEmote: Bool { true }

    override func play(_ tile: GapleTile, end: Int?) -> Bool {
        let move = GapleMove(tile: tile, end: end.map { KotlinInt(int: Int32($0)) }, next: false)
        return active && session.sendMove(move: move) != nil
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

/// Gaple choices on this device, same keys as Android. On this phone the first seat is you and
/// the other three are a computer or empty (at least one computer); partners only apply with
/// four seats.
enum GapleSettings {
    static let seatsKey = "gaple.seats"
    static let partnersKey = "gaple.partners"
    static let size = 4

    private static let fallback: [LocalSeat] = [.person, .computer, .computer, .computer]

    /// The seat tiles stored as `LocalSeats.encode` text, sanitised.
    static func seats(_ text: String?) -> [LocalSeat] {
        let list = LocalSeats.shared.decode(text: text?.isEmpty == false ? text : nil, size: Int32(size), fallback: fallback)
        let fixed = list.enumerated().map { i, s in i == 0 ? LocalSeat.person : s == .person ? LocalSeat.computer : s }
        return fixed.contains(.computer) ? fixed : fallback
    }

    /// Seats 1–3 switch between a computer and empty; one computer always stays.
    static func tap(_ seats: [LocalSeat], index: Int) -> [LocalSeat] {
        guard (1..<size).contains(index) else { return seats }
        var next = seats
        next[index] = next[index] == .computer ? .empty : .computer
        return next.contains(.computer) ? next : seats
    }

    static var seats: [LocalSeat] { seats(UserDefaults.standard.string(forKey: seatsKey)) }

    static var options: GapleOptions {
        GapleOptions(partners: UserDefaults.standard.bool(forKey: partnersKey))
    }

    static var rules: GapleRules { GapleRules(options: options) }
}
