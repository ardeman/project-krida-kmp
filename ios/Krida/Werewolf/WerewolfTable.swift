import Foundation
import Shared

struct WerewolfUpdate {
    let state: WerewolfState
    let fresh: Bool
}

/// Werewolf session bridge: host or guest, fog-of-war via [WerewolfGame.viewFor]. Same shape as
/// `GapleTable` / `PerangKapalTable`.
@MainActor
class WerewolfTable: ObservableObject {
    @Published fileprivate(set) var players: [PlayerInfo] = []
    @Published fileprivate(set) var notice: String?
    @Published fileprivate(set) var waiting: Waiting?
    @Published fileprivate(set) var emotes: [EmoteEvent] = []
    @Published fileprivate(set) var claims: [ClaimEvent] = []
    private(set) var latest: WerewolfState
    var onUpdate: ((WerewolfUpdate) -> Void)?

    fileprivate(set) var active = true
    private var seq: Int32

    var mySeat: Int { 0 }
    var isHost: Bool { false }
    var canEmote: Bool { false }
    var canClaim: Bool { false }
    /// Full authoritative state for phase timers on the host; nil for guests.
    var authority: WerewolfState? { nil }

    fileprivate init(first: WerewolfState, seq: Int32) {
        latest = first
        self.seq = seq
    }

    fileprivate func deliver(_ state: WerewolfState, seq: Int32) {
        guard active else { return }
        let fresh = seq == self.seq + 1
        self.seq = seq
        latest = state
        onUpdate?(WerewolfUpdate(state: state, fresh: fresh))
    }

    fileprivate func showEmote(seat: Int, id: String) {
        guard active else { return }
        emotes = emotes.plusEmote(seat: seat, id: id)
    }

    fileprivate func showClaim(seat: Int, id: String, target: KotlinInt?) {
        guard active else { return }
        claims = claims.plusClaim(seat: seat, id: id, target: target?.intValue)
    }

    fileprivate func playersChanged(_ list: [PlayerInfo]) {
        players = list
        notice = list.contains { !$0.connected }
            ? String(localized: "A player disconnected. The computer takes over after 15 seconds.", bundle: .app)
            : nil
    }

    var botSeats: Set<Int> { Set(players.filter(\.bot).map { Int($0.seat) }) }

    func name(_ seat: Int) -> String {
        players.first { Int($0.seat) == seat }?.name ?? "\(seat + 1)"
    }

    func act(seat: Int, move: WerewolfMove) -> Bool { false }
    func stepBot() {}
    func leave() { active = false; onUpdate = nil; emotes = []; claims = [] }
    func sendEmote(_ id: String) {}
    func sendClaim(_ id: String, target: Int? = nil) {}
}

struct ClaimEvent: Identifiable, Equatable {
    let seat: Int
    let claimId: String
    let target: Int?
    let n: Int
    var id: Int { n }
    private static var next = 1
    init(seat: Int, claimId: String, target: Int?) {
        self.seat = seat
        self.claimId = claimId
        self.target = target
        n = ClaimEvent.next
        ClaimEvent.next += 1
    }
}

extension Array where Element == ClaimEvent {
    func plusClaim(seat: Int, id: String, target: Int?) -> [ClaimEvent] {
        Array((self + [ClaimEvent(seat: seat, claimId: id, target: target)]).suffix(4))
    }
}

final class WerewolfHostTable: WerewolfTable {
    private let session: HostSession<WerewolfState, WerewolfMove>
    private let transport: Transport
    private let seat: Int
    private var ticker: SecondTicker?

    init(session: HostSession<WerewolfState, WerewolfMove>, mySeat: Int, transport: Transport) {
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
        session.onClaim = { [weak self] seat, id, target in
            self?.showClaim(seat: seat.intValue, id: id, target: target)
        }
        ticker = SecondTicker { [weak self, weak session] in
            guard let self, let session else { return }
            for s in session.expiredSeats() {
                _ = session.replaceWithBot(seat: s.int32Value, bot: WerewolfSettings.bot())
            }
            self.waiting = hostWaiting(players: session.players) { session.handOffInMs(seat: $0) }
        }
        ticker?.start()
    }

    override var mySeat: Int { seat }
    override var isHost: Bool { true }
    override var canEmote: Bool { true }
    override var canClaim: Bool { true }
    override var authority: WerewolfState? { session.state }

    private static func view(_ state: WerewolfState, _ seat: Int) -> WerewolfState {
        WerewolfGame.shared.viewFor(state: state, seat: Int32(seat))
    }

    override func act(seat: Int, move: WerewolfMove) -> Bool {
        active && session.submitLocal(seat: Int32(seat), move: move) == nil
    }

    override func stepBot() { if active { _ = session.stepBot() } }

    override func sendEmote(_ id: String) {
        guard active else { return }
        _ = session.sendEmote(seat: Int32(seat), id: id)
    }

    override func sendClaim(_ id: String, target: Int? = nil) {
        guard active else { return }
        let t = target.map { KotlinInt(int: Int32($0)) }
        _ = session.sendClaim(seat: Int32(seat), id: id, target: t)
    }

    override func leave() {
        guard active else { return }
        super.leave()
        ticker?.stop()
        transport.close()
    }

    /// You in seat 0 against enough computers to reach [players] (5–8). For simulator smoke /
    /// local practice without opening an Online room.
    static func vsComputer(you: String, players: Int = 5, computerName: (Int) -> String) -> WerewolfTable {
        let total = min(max(players, WerewolfSettings.minPlayers), WerewolfSettings.maxPlayers)
        let transport = LocalTransport()
        let session = HostSession<WerewolfState, WerewolfMove>(
            rules: StandardRules.shared.werewolf, transport: transport, roomCode: "LOCAL")
        _ = session.addLocalPlayer(name: you, platform: .ios)
        for i in 1..<total {
            _ = session.addBot(name: computerName(i), platform: .ios, bot: WerewolfSettings.bot())
        }
        session.start()
        return WerewolfHostTable(session: session, mySeat: 0, transport: transport)
    }
}

final class WerewolfGuestTable: WerewolfTable {
    private let session: GuestSession<WerewolfState, WerewolfMove>
    private var away = GuestWaiting()
    private var ticker: SecondTicker?

    init(session: GuestSession<WerewolfState, WerewolfMove>) {
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
        session.onClaim = { [weak self] seat, id, target in
            self?.showClaim(seat: seat.intValue, id: id, target: target)
        }
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
    override var canClaim: Bool { true }

    override func act(seat: Int, move: WerewolfMove) -> Bool {
        guard active, seat == mySeat else { return false }
        return session.sendMove(move: move) != nil
    }

    override func sendEmote(_ id: String) {
        if active { session.sendEmote(id: id) }
    }

    override func sendClaim(_ id: String, target: Int? = nil) {
        guard active else { return }
        let t = target.map { KotlinInt(int: Int32($0)) }
        session.sendClaim(id: id, target: t)
    }

    override func leave() {
        guard active else { return }
        super.leave()
        ticker?.stop()
        if session.finished, let code = session.roomCode { LastRoom.shared.clear(code) }
        session.leave()
    }
}

/// Target seats (5–8) and computer difficulty for This phone / quick-match practice.
enum WerewolfSettings {
    static let targetKey = "werewolf.target"
    static let levelKey = "werewolf.level"
    static let minPlayers = 5
    static let maxPlayers = 8

    static var target: Int {
        let v = UserDefaults.standard.integer(forKey: targetKey)
        return (minPlayers...maxPlayers).contains(v) ? v : maxPlayers
    }

    static func setTarget(_ seats: Int) {
        UserDefaults.standard.set(min(max(seats, minPlayers), maxPlayers), forKey: targetKey)
    }

    static var botLevel: WerewolfBot.Level {
        get {
            let raw = UserDefaults.standard.integer(forKey: levelKey)
            let entries = WerewolfBot.Level.entries
            if raw >= 0 && raw < entries.count { return entries[raw] }
            return .normal
        }
        set { UserDefaults.standard.set(Int(newValue.ordinal), forKey: levelKey) }
    }

    static func bot() -> WerewolfBot { WerewolfBot(level: botLevel) }
}