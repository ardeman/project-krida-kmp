import Foundation
import Shared

/// A new snapshot; `fresh` when it follows a throw or roll that can be animated from the one before.
struct UlarTanggaUpdate {
    let state: UlarTanggaState
    let fresh: Bool
}

/// What the ular tangga screen plays on: pass-and-play or against computers on this device,
/// hosting a nearby room, or a guest in one. Session callbacks arrive on the main thread.
/// Same as Android's `UlarTanggaTable`.
@MainActor
class UlarTanggaTable: ObservableObject {
    @Published fileprivate(set) var players: [PlayerInfo] = []
    /// Shown under the status (disconnects, refusals), or nil.
    @Published fileprivate(set) var notice: String?
    /// Latest snapshot; the screen starts from it.
    private(set) var latest: UlarTanggaState
    /// Receives every snapshot after `latest`, in order.
    var onUpdate: ((UlarTanggaUpdate) -> Void)?

    private var seq: Int32

    /// This device's seat; nil for pass-and-play (whoever's turn it is).
    var mySeat: Int? { nil }
    var canRestart: Bool { false }

    /// Seats the computer plays (including dropped guests handed to it).
    var botSeats: Set<Int> { Set(players.filter(\.bot).map { Int($0.seat) }) }

    fileprivate init(first: UlarTanggaState, seq: Int32) {
        latest = first
        self.seq = seq
    }

    fileprivate func deliver(_ state: UlarTanggaState, seq: Int32) {
        // Rebroadcasts keep seq, and a restart's first state has no throw yet; only a throw
        // or roll right after the shown state animates.
        let thrown = state.lastRoll != nil || state.openingRolls.contains { $0.contains { $0.intValue != 0 } }
        let fresh = seq == self.seq + 1 && thrown
        self.seq = seq
        latest = state
        onUpdate?(UlarTanggaUpdate(state: state, fresh: fresh))
    }

    fileprivate func playersChanged(_ list: [PlayerInfo]) {
        players = list
        notice = list.contains { !$0.connected }
            ? String(localized: "Ada pemain yang terputus. Komputer menggantikannya setelah satu menit.", bundle: .app)
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
    /// Lets a computer roll if it is its turn. Called once the board is idle.
    func stepBot() {}
    func restart() {}
    func leave() {}
}

/// The authoritative table: on this device only, or hosting a nearby room.
final class UlarTanggaHostTable: UlarTanggaTable {
    private let session: HostSession<UlarTanggaState, UlarTanggaMove>
    private let transport: Transport
    private let seat: Int?
    /// Hands guests gone past the reconnect grace to the computer, so nobody waits on them.
    private var watch: Timer?

    init(session: HostSession<UlarTanggaState, UlarTanggaMove>, mySeat: Int?, transport: Transport) {
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
        if !(transport is LocalTransport) {
            watch = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak session] _ in
                MainActor.assumeIsolated {
                    guard let session else { return }
                    for s in session.expiredSeats() {
                        _ = session.replaceWithBot(seat: s.int32Value, bot: UlarTanggaBot.shared)
                    }
                }
            }
        }
    }

    override var mySeat: Int? { seat }
    override var canRestart: Bool { true }

    override func roll(seat: Int) -> Bool {
        session.submitLocal(seat: Int32(seat), move: UlarTanggaMove()) == nil
    }

    override func stepBot() { _ = session.stepBot() }

    override func restart() {
        if latest.finished { session.restart() }
    }

    override func leave() {
        watch?.invalidate()
        transport.close()
    }

    /// A game on this phone: one seat per `players` entry (a name, and whether a computer plays
    /// it) with that entry's pawn colour; the colours must rise with the seat. With a single
    /// person their seat is "you"; with more the phone goes round. Same as Android.
    static func local(players: [LocalPlayer]) -> UlarTanggaTable {
        let transport = LocalTransport()
        let session = HostSession<UlarTanggaState, UlarTanggaMove>(rules: UlarTanggaSettings.rules, transport: transport, roomCode: "LOCAL")
        for p in players {
            if p.computer {
                _ = session.addBot(name: p.name, platform: .ios, bot: UlarTanggaBot.shared)
            } else {
                _ = session.addLocalPlayer(name: p.name, platform: .ios)
            }
        }
        // Seat i joined with colour i. The chosen colours rise with the seat (each tile has its
        // own colour, empty tiles are skipped), so setting them from the last seat down never
        // asks for a colour another seat still holds.
        for seat in players.indices.reversed() { _ = session.setColor(seat: Int32(seat), color: Int32(players[seat].color)) }
        session.start()
        let people = players.indices.filter { !players[$0].computer }
        return UlarTanggaHostTable(session: session, mySeat: people.count == 1 ? people[0] : nil, transport: transport)
    }
}

/// A guest in a nearby room: rolls go to the host, snapshots come back.
final class UlarTanggaGuestTable: UlarTanggaTable {
    private let session: GuestSession<UlarTanggaState, UlarTanggaMove>

    init(session: GuestSession<UlarTanggaState, UlarTanggaMove>) {
        self.session = session
        super.init(first: session.state!, seq: session.stateSeq)
        players = session.players
        session.onState = { [weak self, weak session] state in
            guard let self, let session, let state else { return }
            self.playersChanged(session.players)
            self.deliver(state, seq: session.stateSeq)
        }
        session.onPlayers = { [weak self] list in self?.playersChanged(list) }
        session.onInvalid = { [weak self] _ in self?.notice = String(localized: "Langkah ditolak", bundle: .app) }
        session.onDisconnected = { [weak self] in self?.notice = String(localized: "Terputus dari host", bundle: .app) }
    }

    override var mySeat: Int? { session.seat.map { Int($0.int32Value) } }

    override func roll(seat: Int) -> Bool {
        seat == mySeat && session.sendMove(move: UlarTanggaMove()) != nil
    }

    override func leave() { session.leave() }
}

/// One seat of a game on this phone, for `UlarTanggaHostTable.local`.
struct LocalPlayer {
    let name: String
    let computer: Bool
    let color: Int
}

/// Ular tangga choices stored on this device: the seat tiles and the rule options. Same keys
/// and defaults as Android.
enum UlarTanggaSettings {
    static let playersKey = "ularTangga.players"
    static let seatsKey = "ularTangga.seats"
    static let bounceBackKey = "ularTangga.bounceBack"
    static let sixToEnterKey = "ularTangga.sixToEnter"
    static let firstFinishEndsKey = "ularTangga.firstFinishEnds"
    static let sixRollsAgainKey = "ularTangga.sixRollsAgain"

    private static func flag(_ key: String, _ fallback: Bool) -> Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? fallback
    }

    /// The player count stored before the seat tiles, 2...4; read once to seed the tiles.
    static var players: Int {
        min(max(UserDefaults.standard.object(forKey: playersKey) as? Int ?? 2, 2), 4)
    }

    static var options: UlarTanggaOptions {
        UlarTanggaOptions(
            bounceBack: flag(bounceBackKey, false),
            sixToEnter: flag(sixToEnterKey, false),
            firstFinishEnds: flag(firstFinishEndsKey, false),
            sixRollsAgain: flag(sixRollsAgainKey, true)
        )
    }

    /// Rules for a new host session, with the chosen options.
    static var rules: UlarTanggaRules { UlarTanggaRules(options: options) }
}
