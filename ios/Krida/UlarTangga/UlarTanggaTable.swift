import Foundation
import Shared

/// A new snapshot; `fresh` when it follows a throw or roll that can be animated from the one before.
struct UlarTanggaUpdate {
    let state: UlarTanggaState
    let fresh: Bool
}

/// What the ular tangga screen plays on: pass-and-play or against computers on this device.
/// (Nearby rooms come with the 4-player lobby.) Session callbacks arrive on the main thread.
/// Same as Android's `UlarTanggaTable`.
@MainActor
final class UlarTanggaTable: ObservableObject {
    @Published private(set) var players: [PlayerInfo]
    /// Latest snapshot; the screen starts from it.
    private(set) var latest: UlarTanggaState
    /// Receives every snapshot after `latest`, in order.
    var onUpdate: ((UlarTanggaUpdate) -> Void)?

    /// This device's seat against computers; nil for pass-and-play (whoever's turn it is).
    let mySeat: Int?
    /// Seats the computer plays.
    let botSeats: Set<Int>

    private let session: HostSession<UlarTanggaState, UlarTanggaMove>
    private let transport: Transport
    private var seq: Int32

    private init(session: HostSession<UlarTanggaState, UlarTanggaMove>, mySeat: Int?, botSeats: Set<Int>, transport: Transport) {
        self.session = session
        self.mySeat = mySeat
        self.botSeats = botSeats
        self.transport = transport
        seq = session.seq
        players = session.players
        latest = session.state!
        session.onPlayersChanged = { [weak self] list in self?.players = list }
        session.onStateChanged = { [weak self, weak session] state in
            guard let self, let session, let state else { return }
            // A restart's first state has no throw yet; only a throw or roll right after the
            // shown state animates.
            let thrown = state.lastRoll != nil || state.openingRolls.contains { $0.contains { $0.intValue != 0 } }
            let fresh = session.seq == self.seq + 1 && thrown
            self.seq = session.seq
            self.latest = state
            self.onUpdate?(UlarTanggaUpdate(state: state, fresh: fresh))
        }
    }

    func name(_ seat: Int) -> String {
        players.first { Int($0.seat) == seat }?.name ?? "\(seat + 1)"
    }

    /// The seat can be played from this device (own seat, or any person in pass-and-play).
    func isLocal(_ seat: Int) -> Bool {
        !botSeats.contains(seat) && (mySeat == nil || mySeat == seat)
    }

    /// Rolls for `seat`. Returns false when nothing happened.
    func roll(seat: Int) -> Bool {
        session.submitLocal(seat: Int32(seat), move: UlarTanggaMove()) == nil
    }

    /// Lets a computer roll if it is its turn. Called once the board is idle.
    func stepBot() { _ = session.stepBot() }

    func restart() {
        if latest.finished { session.restart() }
    }

    func leave() { transport.close() }

    static func passAndPlay(names: [String]) -> UlarTanggaTable {
        let transport = LocalTransport()
        let session = HostSession<UlarTanggaState, UlarTanggaMove>(rules: UlarTanggaSettings.rules, transport: transport, roomCode: "LOCAL")
        for name in names { _ = session.addLocalPlayer(name: name, platform: .ios) }
        session.start()
        return UlarTanggaTable(session: session, mySeat: nil, botSeats: [], transport: transport)
    }

    /// You at seat 0 against `computers` (one name per computer seat).
    static func vsComputer(you: String, computers: [String]) -> UlarTanggaTable {
        let transport = LocalTransport()
        let session = HostSession<UlarTanggaState, UlarTanggaMove>(rules: UlarTanggaSettings.rules, transport: transport, roomCode: "LOCAL")
        _ = session.addLocalPlayer(name: you, platform: .ios)
        for name in computers { _ = session.addBot(name: name, platform: .ios, bot: UlarTanggaBot.shared) }
        session.start()
        return UlarTanggaTable(session: session, mySeat: 0, botSeats: Set(1...computers.count), transport: transport)
    }
}

/// Ular tangga choices stored on this device: player count and the rule options. Same keys
/// and defaults as Android.
enum UlarTanggaSettings {
    static let playersKey = "ularTangga.players"
    static let bounceBackKey = "ularTangga.bounceBack"
    static let sixToEnterKey = "ularTangga.sixToEnter"
    static let firstFinishEndsKey = "ularTangga.firstFinishEnds"
    static let sixRollsAgainKey = "ularTangga.sixRollsAgain"

    private static func flag(_ key: String, _ fallback: Bool) -> Bool {
        UserDefaults.standard.object(forKey: key) as? Bool ?? fallback
    }

    /// Seats at the table, 2...4 (you plus computers when playing the computer).
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
