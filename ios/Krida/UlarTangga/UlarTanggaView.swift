import RealityKit
import Shared
import SwiftUI

/// Pawn color `color` (0...3), for the HUD.
func pawnColor(_ color: Int) -> Color {
    let c = UlarTanggaBoard.shared.pieceColor(color: Int32(color))
    return Color(red: Double(c.get(index: 0)), green: Double(c.get(index: 1)), blue: Double(c.get(index: 2)))
}

/// Ular tangga on a voxel board, driven by an `UlarTanggaTable`. Every snapshot is animated in
/// order: a roll is replayed from the shown state (die throw, a hop per square, then the snake
/// or ladder), then the pawns settle; a new game starts with the dice-off for the play order.
/// Same flow as Android's `UlarTanggaScreen`.
@MainActor
final class UlarTanggaGameModel: ObservableObject {
    @Published private(set) var state: UlarTanggaState
    @Published private(set) var animating = false
    /// Just after the dice-off: everyone's throws stay on the tiles for a moment.
    @Published private(set) var offHold = false
    @Published private(set) var viewFrame: CGRect = .zero
    /// Seconds left on the throw countdown (`Krida.ROLL_TURN_MS`); 0 when not counting.
    @Published private(set) var rollSeconds = 0

    let table: UlarTanggaTable
    let scene = UlarTanggaScene()
    var playerOrigins: [Int: CGPoint] = [:]
    private let sounds = Sounds.shared
    private let game = UlarTanggaGame.shared
    private let board = UlarTanggaBoard.shared
    private var queue: [UlarTanggaUpdate] = []
    private let lifetime = GameLifetime()
    /// How the local player threw the die, for the roll the table hasn't sent back yet.
    private var pendingToss: UlarTanggaScene.Toss?
    /// The loading screen still covers the board; computers wait for it.
    var loading = true
    /// Total seconds for the roll countdown (`Krida.ROLL_TURN_MS`).
    var rollTotal: Int { Int(Krida.shared.ROLL_TURN_MS / 1000) }

    private static let hop: Double = 0.23
    private static let jumpUnitsPerSecond: Float = 9
    private static let settle: Double = 0.18
    private static let botPause: Double = 0.7

    init(table: UlarTanggaTable) {
        self.table = table
        state = table.latest
        scene.onDieBounce = { [weak self] in
            guard let self, self.lifetime.active else { return }
            self.sounds.play(.die)
        }
        table.onUpdate = { [weak self] update in
            guard let self, self.lifetime.active else { return }
            self.queue.append(update)
            self.drain()
        }
        // The first state runs through the queue too, so the pawns are placed.
        queue.append(UlarTanggaUpdate(state: table.latest, fresh: false))
        drain()
    }

    var players: Int { state.positions.count }
    var turn: Int { Int(state.turn) }
    var queueEmpty: Bool { queue.isEmpty }

    var canRoll: Bool { lifetime.active && !animating && queue.isEmpty && !state.finished && table.isLocal(turn) }
    var ready: Bool { canRoll && !offHold && !loading }

    func stop() {
        guard lifetime.active else { return }
        lifetime.stop()
        table.onUpdate = nil
        scene.onDieBounce = nil
        scene.holdDie(false)
        scene.dieReady = false
        queue.removeAll()
        pressing = false
        pendingToss = nil
        rollSeconds = 0
        table.leave()
        sounds.stopGameAudio()
    }

    func layout(size: CGSize, room: CGRect, obstacles: [CGRect], dieSpot: CGRect) {
        scene.onViewFrame = { [weak self] frame in self?.viewFrame = frame }
        viewFrame = scene.layout(size: size, room: room, obstacles: obstacles)
        scene.dieSpot = (CGPoint(x: dieSpot.midX, y: dieSpot.midY), dieSpot.width * 0.45)
    }

    /// Ease in on the board centre while the die flies.
    private func watchDie() {
        let land = board.dieLanding
        scene.watch([
            SIMD3(land.get(index: 0), board.TOP, land.get(index: 1)),
            SIMD3(land.get(index: 2), board.TOP, land.get(index: 3)),
        ])
    }

    func updateReady() { scene.dieReady = ready }

    private func spot(_ s: UlarTanggaState, _ seat: Int) -> SIMD3<Float> {
        let square = s.positions[seat]
        let sharing = s.positions.filter { $0 == square }.count > 1
        return UlarTanggaScene.vec(board.pieceSpot(square: square.int32Value, seat: Int32(seat), shared: sharing))
    }

    private func placeAll(_ s: UlarTanggaState) {
        scene.setSeats((0..<s.positions.count).map { Int(s.color(seat: Int32($0))) })
        for seat in 0..<s.positions.count { scene.place(seat, spot(s, seat)) }
    }

    /// Throws the die for the local player whose turn it is; it stays pressed (spinning, for a
    /// long throw) until the roll comes back.
    func roll(_ toss: UlarTanggaScene.Toss, byPlayer: Bool = true) {
        guard canRoll, pendingToss == nil else { scene.holdDie(false); return }
        if byPlayer { autoSeats.remove(turn) }
        rollSeconds = 0
        lifetime.cancel("rollTurn")
        pendingToss = toss
        scene.holdDie(true, shake: toss == .long)
        if !table.roll(seat: turn) {
            pendingToss = nil
            scene.holdDie(false)
        }
        updateReady()
        lifetime.launch("rollTimeout") { [self] in
            try await Task.sleep(for: .seconds(4))
            if pendingToss == toss && !animating {
                pendingToss = nil
                scene.holdDie(false)
            }
        }
    }

    /// Seats on this phone whose timer ran out: the phone throws for them after a short pause
    /// (`Krida.AUTO_PLAY_MS`) instead of a countdown, until that player throws themselves.
    private var autoSeats = Set<Int>()

    /// Starts or clears the throw countdown whenever roll availability changes (same as ludo).
    func syncRollTimer() {
        lifetime.cancel("rollTurn")
        guard ready else {
            rollSeconds = 0
            return
        }
        if autoSeats.contains(turn) {
            rollSeconds = 0
            lifetime.launch("rollTurn") { [self] in
                try await Task.sleep(for: .seconds(Double(Krida.shared.AUTO_PLAY_MS) / 1000))
                if lifetime.active, ready, !pressing, autoSeats.contains(turn) { roll(.short, byPlayer: false) }
            }
            return
        }
        rollSeconds = rollTotal
        lifetime.launch("rollTurn") { [self] in
            while rollSeconds > 0 {
                try await Task.sleep(for: .seconds(1))
                guard lifetime.active, ready else {
                    rollSeconds = 0
                    return
                }
                if pressing { continue }
                let next = rollSeconds - 1
                rollSeconds = next
                if next == 0, ready, !pressing {
                    autoSeats.insert(turn)
                    roll(.short, byPlayer: false)
                    return
                }
            }
        }
    }

    /// Call after state / animation changes that affect `ready`.
    func afterReadyChange() {
        updateReady()
        syncRollTimer()
    }

    // MARK: Holding the die

    private var pressing = false
    private var spinning = false

    /// A finger went down on the die: press it, and after a moment start spinning it.
    func pressDie() {
        guard !pressing, canRoll, pendingToss == nil else { return }
        pressing = true
        autoSeats.remove(turn)
        scene.holdDie(true)
        lifetime.launch("hold") { [self] in
            try await Task.sleep(for: .seconds(0.5))
            guard !Task.isCancelled, pressing else { return }
            spinning = true
            scene.holdDie(true, shake: true)
            sounds.play(.tap)
        }
    }

    func moveDie(_ offset: CGSize) {
        guard pressing, canRoll, pendingToss == nil else { return }
        scene.moveHeldDie(offset)
    }

    /// The finger lifted: a quick release tosses, a hold throws long; cancellation lets go.
    func releaseDie(completed: Bool) {
        guard pressing else { return }
        pressing = false
        lifetime.cancel("hold")
        let long = spinning
        spinning = false
        guard canRoll, pendingToss == nil else { scene.holdDie(false); return }
        guard completed else { scene.holdDie(false); return }
        roll(long ? .long : .short)
    }

    func restart() { if lifetime.active { table.restart() } }

    /// Once the board is still, give a computer player its turn after a short pause.
    private func idle() {
        afterReadyChange()
        lifetime.launch("bot") { [self] in
            while loading { try await Task.sleep(for: .milliseconds(100)) }
            try await Task.sleep(for: .seconds(Self.botPause))
            if !animating && queue.isEmpty { table.stepBot() }
        }
    }

    private func toss(for seat: Int) -> UlarTanggaScene.Toss {
        defer { pendingToss = nil }
        return table.isLocal(seat) ? pendingToss ?? .short : .short
    }

    private func recallDie(for next: UlarTanggaState) {
        let seat = Int(next.turn)
        scene.recallDie(origin: table.isLocal(seat) ? nil : playerOrigins[seat])
    }

    private func drain() {
        guard lifetime.active, !animating else { return }
        guard !queue.isEmpty else { return idle() }
        let update = queue.removeFirst()
        lifetime.cancel("bot")
        animating = true
        afterReadyChange()
        lifetime.launch("animation") { [self] in
            defer {
                // Always ease back out after a throw or hop, even if cancelled mid-move.
                scene.watch(nil)
                animating = false
                if lifetime.active { drain() }
            }
            let next = update.state
            let offThrow = update.fresh ? game.diceOffThrowBetween(before: state, after: next) : nil
            if let offThrow, let thrower = offThrow.first?.intValue, let value = offThrow.second?.intValue {
                // A dice-off throw: the die lands on the throw, which shows on the seat's tile.
                let origin = table.isLocal(thrower) ? nil : playerOrigins[thrower]
                watchDie()
                let seconds = scene.throwDie(value, toss: toss(for: thrower), origin: origin, seat: thrower)
                try await Task.sleep(for: .seconds(Double(seconds) + 0.15))
                state = next
                try await Task.sleep(for: .seconds(0.45))
                recallDie(for: next)
                scene.watch(nil)
                if !next.opening {
                    // The order is set: show every throw a moment, then the tiles line up in order.
                    offHold = true
                    try await Task.sleep(for: .seconds(1.8))
                    offHold = false
                    announceTurn(Int(next.turn))
                }
            } else if update.fresh, let seat = next.lastSeat?.intValue, let roll = next.lastRoll?.intValue {
                let origin = table.isLocal(seat) ? nil : playerOrigins[seat]
                watchDie()
                let seconds = scene.throwDie(roll, toss: toss(for: seat), origin: origin, seat: seat)
                try await Task.sleep(for: .seconds(Double(seconds) + 0.2))
                let play = game.play(state: state, seat: Int32(seat), roll: Int32(roll))
                for square in play.steps {
                    let to = UlarTanggaScene.vec(board.pieceSpot(square: square.int32Value, seat: Int32(seat), shared: false))
                    scene.watch([to])
                    scene.move(seat, [to], seconds: Float(Self.hop * 0.9), hop: 0.7)
                    sounds.play(.step)
                    try await Task.sleep(for: .seconds(Self.hop))
                }
                let landed = play.steps.last?.int32Value
                if let landed, play.jumpTo != nil {
                    try await Task.sleep(for: .seconds(0.16))
                    let ladder = game.LADDERS[KotlinInt(int: landed)] != nil
                    let path = (ladder ? board.ladderPath(foot: landed) : board.snakePath(head: landed)).map { UlarTanggaScene.vec($0) }
                    let seconds = Self.length(path) / Self.jumpUnitsPerSecond + 0.2
                    sounds.play(ladder ? .store : .capture)
                    scene.watch(path)
                    scene.move(seat, Array(path.dropFirst()), seconds: seconds)
                    try await Task.sleep(for: .seconds(Double(seconds) + 0.08))
                }
                for s in 0..<next.positions.count { scene.move(s, [spot(next, s)], seconds: Float(Self.settle)) }
                try await Task.sleep(for: .seconds(Self.settle))
                recallDie(for: next)
                scene.watch(nil)
                try await Task.sleep(for: .seconds(0.28))
                state = next
                let last = game.LAST
                announce(play.state, reached: landed == last || play.jumpTo?.int32Value == last)
            } else {
                // A new game (or the screen's first state): pawns to the start; the dice-off
                // waits for the players' throws.
                state = next
                placeAll(next)
                recallDie(for: next)
            }
        }
    }

    private static func length(_ path: [SIMD3<Float>]) -> Float {
        zip(path, path.dropFirst()).reduce(0) { $0 + hypot($1.1.x - $1.0.x, $1.1.z - $1.0.z) }
    }

    /// Voice for whose turn it is; pass-and-play gets the chime only.
    private func announceTurn(_ seat: Int) {
        if let me = table.mySeat { sounds.say(seat == me ? .yourTurn : .theirTurn) } else { sounds.play(.turn) }
    }

    /// After a roll: the result when the game ends, a fanfare when someone reaches 100, else the next turn.
    private func announce(_ after: UlarTanggaState, reached: Bool) {
        let me = table.mySeat
        if after.finished {
            let won = me == nil || after.winner?.intValue == me
            sounds.play(won ? .win : .lose)
            if me != nil { sounds.say(won ? .win : .lose) }
        } else if reached {
            sounds.play(.win)
        } else if after.turn != after.lastSeat?.int32Value {
            announceTurn(Int(after.turn))
        }
    }
}

/// The game screen: the loading screen goes up first, the board is built under it, and the
/// loading screen fades out once the board's first frames are drawn (as congklak).
struct UlarTanggaView: View {
    let table: UlarTanggaTable
    let onExit: () -> Void
    @State private var mounted = false
    @State private var loading = true

    var body: some View {
        ZStack {
            if mounted { UlarTanggaBoardView(table: table, onExit: onExit, loading: loading, onDrawn: finishLoading) }
            if loading { LoadingScreen().transition(.opacity) }
        }
        .task {
            do { try await Task.sleep(for: .milliseconds(50)) }
            catch { return }
            mounted = true
            do { try await Task.sleep(for: .seconds(4)) }
            catch { return }
            finishLoading()
        }
    }

    private func finishLoading() {
        guard loading else { return }
        withAnimation(.easeOut(duration: 0.25)) { loading = false }
    }
}

private struct UlarTanggaBoardView: View {
    @ObservedObject private var table: UlarTanggaTable
    @StateObject private var model: UlarTanggaGameModel
    @ObservedObject private var ads = Ads.shared
    @State private var showRules = false
    @State private var size: CGSize = .zero
    /// HUD boxes the board stays clear of, and "room" for where it may go, in `space`.
    @State private var boxes: [String: CGRect] = [:]
    /// True while a finger is on the die; resets on a cancelled gesture too, unlike `onEnded`.
    @GestureState private var dieDown = false
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole
    let onExit: () -> Void
    let loading: Bool
    let onDrawn: () -> Void

    init(table: UlarTanggaTable, onExit: @escaping () -> Void, loading: Bool, onDrawn: @escaping () -> Void) {
        self.table = table
        _model = StateObject(wrappedValue: UlarTanggaGameModel(table: table))
        self.onExit = onExit
        self.loading = loading
        self.onDrawn = onDrawn
    }

    private static let space = "ularTangga"

    /// Horizontal safe-area insets (the Dynamic Island's strip), read before the board view
    /// ignores the safe area so the HUD can still keep clear of the island.
    @State private var sideInsets = EdgeInsets()

    var body: some View {
        GeometryReader { outer in
            board
                .onAppear { sideInsets = outer.safeAreaInsets }
                .onChange(of: outer.safeAreaInsets) { _, new in sideInsets = new }
        }
    }

    private var board: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                hud
                if model.state.finished && !model.animating { gameOver }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            // In the background: the view is larger than the screen and must not size the HUD.
            .background(alignment: .topLeading) {
                BoardContainer(view: model.scene.view)
                    .frame(width: model.viewFrame.width, height: model.viewFrame.height)
                    .offset(x: model.viewFrame.minX, y: model.viewFrame.minY)
                    .allowsHitTesting(false)
            }
            .coordinateSpace(name: Self.space)
            .onAppear {
                model.scene.whenDrawn(onDrawn)
                size = geo.size
            }
            .onChange(of: geo.size) { _, new in size = new }
        }
        .ignoresSafeArea()
        .background(Theme.backdropGradient)
        .onDisappear { model.stop() }
        .onChange(of: boxes) { _, _ in relayout() }
        .onChange(of: size) { _, _ in relayout() }
        .onChange(of: dieDown) { _, down in
            // A cancelled gesture never reaches onEnded: let go of the die without a throw.
            // After onEnded has had its turn, where it's a no-op.
            if !down { DispatchQueue.main.async { model.releaseDie(completed: false) } }
        }
        .onChange(of: loading) { _, now in
            model.loading = now
            model.afterReadyChange()
        }
        .onChange(of: model.animating) { _, _ in model.afterReadyChange() }
        .onChange(of: model.offHold) { _, _ in model.afterReadyChange() }
        .sheet(isPresented: $showRules) { UlarTanggaRulesSheet(options: model.state.options) }
    }

    private static let hudKeys = ["back", "players", "buttons", "die", "status"]

    /// The board takes the largest place in the room clear of every HUD box (with a margin);
    /// the die sits on its spot.
    private func relayout() {
        guard size.width > 0, let room = boxes["room"], let die = boxes["die"], die.width > 0,
              Self.hudKeys.allSatisfy({ boxes[$0] != nil }) else { return }
        let margin = 12 * s
        // The notice (a dropped player, the host lost) only while it shows.
        var keys = Self.hudKeys
        if model.ready && model.rollSeconds > 0 { keys.append("rollTimer") }
        if table.waiting != nil || table.notice != nil { keys.append("notice") }
        if table.canEmote { keys.append("emote") }
        let obstacles = keys.compactMap { boxes[$0]?.insetBy(dx: -margin, dy: -margin) }
        model.layout(size: size, room: room, obstacles: obstacles, dieSpot: die)
        for seat in 0..<model.players {
            if let tile = boxes["seat-\(seat)"] {
                model.playerOrigins[seat] = CGPoint(x: tile.midX, y: tile.midY)
            }
        }
    }

    private func exit() {
        model.stop()
        onExit()
    }

    private func hudBox(_ key: String) -> some ViewModifier {
        FrameReader(space: Self.space, frame: Binding(get: { boxes[key] ?? .zero }, set: { boxes[key] = $0 }))
    }

    // MARK: Seats

    private func displayName(_ seat: Int) -> String {
        table.mySeat == seat ? String(localized: "You", bundle: .app) : table.name(seat)
    }

    private var highlighted: Int? { model.state.finished || !model.queueEmpty ? nil : model.turn }
    private var off: Bool { model.state.opening || model.offHold }

    /// Seats throwing in this round of the dice-off.
    private var throwing: [Int] {
        guard model.state.opening else { return [] }
        let rounds = Array(model.state.openingRolls.dropLast())
        return UlarTanggaGame.shared.diceOffThrowers(rounds: rounds, players: Int32(model.players)).map(\.intValue)
    }

    /// During the dice-off (and a moment after) the tiles show throws: this round's for the
    /// seats throwing, the last one for seats already placed.
    private func offRoll(_ seat: Int) -> Int? {
        guard off else { return nil }
        let rounds = model.state.openingRolls
        if throwing.contains(seat) {
            let v = rounds.last?[seat].intValue ?? 0
            return v > 0 ? v : nil
        }
        return rounds.last { $0[seat].intValue > 0 }?[seat].intValue
    }

    /// Once the order is set, the tiles stand in play order.
    private var tileOrder: [Int] {
        off ? Array(0..<model.players) : model.state.playOrder.map(\.intValue)
    }

    /// What is happening, under the die.
    private var status: String {
        let st = model.state
        let seat = model.turn
        if model.offHold { return String(localized: "Order set · \(displayName(seat)) goes first", bundle: .app) }
        if st.finished || model.animating { return "" }
        let bot = table.botSeats.contains(seat)
        if st.opening {
            if bot { return String(localized: "thinking…", bundle: .app) }
            if st.openingRolls.count > 1 { return String(localized: "A tie · \(displayName(seat)) throws again", bundle: .app) }
            if table.mySeat == seat { return String(localized: "Throw the die to set the playing order", bundle: .app) }
            return String(localized: "\(table.name(seat)), throw the die to set the playing order", bundle: .app)
        }
        if st.lastSeat?.intValue == seat && st.lastRoll?.intValue == 6 && st.options.sixRollsAgain {
            return String(localized: "A six · roll again", bundle: .app)
        }
        if bot { return String(localized: "thinking…", bundle: .app) }
        if table.mySeat == seat { return String(localized: "Your turn · roll the die", bundle: .app) }
        return String(localized: "\(table.name(seat))'s turn", bundle: .app)
    }

    // MARK: HUD

    private var hud: some View {
        let throwing = throwing
        return HStack(alignment: .top, spacing: 0) {
            // Left: back, then the players, two tiles a row.
            VStack(alignment: .leading, spacing: 10 * s) {
                CircleIconButton(systemImage: "chevron.left", label: "Back to menu", action: exit)
                    .modifier(hudBox("back"))
                Spacer().frame(height: 4 * s)
                let rows = stride(from: 0, to: tileOrder.count, by: 2).map { Array(tileOrder[$0..<min($0 + 2, tileOrder.count)]) }
                VStack(spacing: 8 * s) {
                    ForEach(rows, id: \.self) { pair in
                        HStack(spacing: 8 * s) {
                            ForEach(pair, id: \.self) { seat in
                                tile(seat, on: seat == highlighted, roll: offRoll(seat), out: model.state.opening && !throwing.contains(seat))
                                    .modifier(hudBox("seat-\(seat)"))
                            }
                            if pair.count == 1 { Color.clear.aspectRatio(1, contentMode: .fit) }
                        }
                    }
                }
                .modifier(hudBox("players"))
                Spacer(minLength: 0)
            }
            .frame(width: 136 * s)
            .frame(maxHeight: .infinity)
            Spacer(minLength: 0)
            // Right: sound and rules, then the die and whose turn it is.
            VStack(spacing: 0) {
                HStack(spacing: 8 * s) {
                    SoundToggles()
                    CircleIconButton(systemImage: "questionmark", label: "How to play") { showRules = true }
                }
                .modifier(hudBox("buttons"))
                .frame(maxWidth: .infinity, alignment: .trailing)
                Spacer(minLength: 0)
                EmoteHud(
                    canEmote: table.canEmote,
                    emote: table.emote,
                    playerName: { seat in
                        if table.mySeat == seat { return String(localized: "You", bundle: .app) }
                        return table.name(seat)
                    },
                    onSend: table.sendEmote,
                    s: s
                )
                .modifier(hudBox("emote"))
                if table.canEmote { Spacer().frame(height: 10 * s) }
                if model.ready && model.rollSeconds > 0 {
                    CountdownRing(seconds: model.rollSeconds, total: model.rollTotal, size: 40)
                        .modifier(hudBox("rollTimer"))
                        .padding(.bottom, 6 * s)
                }
                // The die itself is 3D, drawn by the scene over this spot; tap it to toss it,
                // hold it to spin it and let go to throw it.
                Color.clear
                    .frame(width: 104 * s, height: 104 * s)
                    .contentShape(Rectangle())
                    .gesture(dieGesture)
                    .modifier(hudBox("die"))
                    .accessibilityElement()
                    .accessibilityLabel(Text("Roll"))
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction { model.roll(.short) }
                Text(status)
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    // Two lines kept even for a short status, so the die and the board don't move.
                    .lineLimit(2, reservesSpace: true)
                    .frame(maxWidth: .infinity)
                    .modifier(hudBox("status"))
                    .padding(.top, 10 * s)
                if let away = table.waiting {
                    CountdownNotice(text: away.text, seconds: away.seconds, total: Int(Krida.shared.HANDOFF_WAIT_MS / 1000), fontSize: 13)
                        .frame(maxWidth: .infinity)
                        .modifier(hudBox("notice"))
                } else if let notice = table.notice {
                    Text(notice)
                        .font(Theme.font(13 * s, .bold))
                        .foregroundStyle(Theme.error)
                        .multilineTextAlignment(.center)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity)
                        .modifier(hudBox("notice"))
                }
            }
            .frame(width: 148 * s)
            .frame(maxHeight: .infinity)
        }
        .modifier(hudBox("room"))
        .padding(.horizontal, 24 * s)
        // Clear a corner punch hole or the Dynamic Island's strip.
        .padding(.leading, max(max(hole?.leading ?? 0, sideInsets.leading) - 24 * s, 0))
        .padding(.trailing, max(max(hole?.trailing ?? 0, sideInsets.trailing) - 24 * s, 0))
        .padding(.vertical, 14 * s)
        .foregroundStyle(Theme.ink)
    }

    /// Down presses the die; up tosses it, or throws it long after a hold.
    private var dieGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .updating($dieDown) { _, down, _ in down = true }
            .onChanged { value in
                model.pressDie()
                model.moveDie(value.translation)
            }
            .onEnded { _ in model.releaseDie(completed: true) }
    }

    /// A seat as a square tile: its square (or finishing place, or dice-off throw) inside a
    /// circle in its color, the name below.
    private func tile(_ seat: Int, on: Bool, roll: Int?, out: Bool) -> some View {
        let st = model.state
        let place = st.finishOrder.firstIndex { $0.intValue == seat }
        let square = st.positions[seat].intValue
        let color = Int(st.color(seat: Int32(seat)))
        let c = UlarTanggaBoard.shared.pieceColor(color: Int32(color))
        // Dark digits on a light pawn color (yellow), white on the rest.
        let light = c.get(index: 0) * 0.3 + c.get(index: 1) * 0.59 + c.get(index: 2) * 0.11 > 0.6
        let start = place == nil && square == 0 && roll == nil
        let text: String = if let roll { "\(roll)" } else if let place { "#\(place + 1)" } else if start { String(localized: "Start", bundle: .app) } else { "\(square)" }
        let shape = RoundedRectangle(cornerRadius: 14 * s)
        return VStack(spacing: 3 * s) {
            ZStack(alignment: .bottomTrailing) {
                Text(text)
                    .font(Theme.font((start ? 9 : 14) * s, .black))
                    .foregroundStyle(light ? Theme.inkOnLight : .white)
                    .lineLimit(1)
                    .frame(width: 30 * s, height: 30 * s)
                    .background(pawnColor(color), in: Circle())
                    .padding(2 * s)
                    .background(.white, in: Circle())
                if table.botSeats.contains(seat) {
                    Image(systemName: "desktopcomputer")
                        .font(.system(size: 8 * s, weight: .bold))
                        .foregroundStyle(Theme.ink)
                        .frame(width: 16 * s, height: 16 * s)
                        .background(Theme.surface, in: Circle())
                        .overlay(Circle().stroke(Theme.line, lineWidth: 1))
                } else if let avatar = table.players.avatar(of: seat) {
                    // Multi-device players with an account show their avatar in the same corner.
                    AvatarBadge(avatar: avatar, size: 18)
                        .overlay(Circle().stroke(.white, lineWidth: 1.5 * s))
                }
            }
            Text(displayName(seat))
                .font(Theme.font(11 * s, .bold))
                .foregroundStyle(on ? .white : Theme.ink)
                .lineLimit(1)
        }
        .padding(4 * s)
        .frame(maxWidth: .infinity)
        .aspectRatio(1, contentMode: .fit)
        .background(on ? Theme.accent : Theme.surface, in: shape)
        .overlay(shape.stroke(on ? .clear : Theme.line, lineWidth: 1.5))
        .shadow(color: on ? Theme.gold.opacity(0.6) : .clear, radius: 6 * s)
        .opacity(out ? 0.4 : 1)
        .animation(.easeOut(duration: 0.2), value: on)
        .accessibilityElement(children: .combine)
    }

    // MARK: End of game

    private var gameOver: some View {
        let st = model.state
        let ranking = UlarTanggaGame.shared.ranking(state: st).map(\.intValue)
        let winner = ranking.first ?? 0
        let title = table.mySeat == winner ? String(localized: "You win!", bundle: .app) : String(localized: "\(table.name(winner)) wins!", bundle: .app)
        return ZStack {
            Theme.ink.opacity(0.45).ignoresSafeArea()
            VStack(spacing: 10 * s) {
                Image(systemName: "trophy")
                    .font(.system(size: 34 * s, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xC98A1B))
                    .accessibilityHidden(true)
                Text(title).font(Theme.font(24 * s, .black))
                VStack(spacing: 4 * s) {
                    ForEach(Array(ranking.enumerated()), id: \.offset) { i, seat in
                        let square = st.positions[seat].intValue
                        HStack(spacing: 10 * s) {
                            Text("\(i + 1)").font(Theme.font(16 * s, .black)).foregroundStyle(Theme.muted).frame(width: 18 * s)
                            let avatar = table.players.avatar(of: seat)
                            SeatAvatar(avatar: avatar, color: pawnColor(Int(st.color(seat: Int32(seat)))), size: avatar != nil ? 22 : 14)
                            Text(displayName(seat)).font(Theme.font(16 * s)).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                            Text(square == 0 ? String(localized: "Start", bundle: .app) : "\(square)")
                                .font(Theme.font(15 * s, .bold)).foregroundStyle(Theme.muted)
                        }
                        .padding(.horizontal, 12 * s)
                        .padding(.vertical, 6 * s)
                        .background(i == 0 ? Theme.highlight : Theme.surfaceSoft, in: RoundedRectangle(cornerRadius: 12 * s))
                    }
                }
                HStack(spacing: 10 * s) {
                    Button { ads.afterGame(then: exit) } label: { Text("Menu").frame(maxWidth: .infinity) }
                        .buttonStyle(SecondaryButtonStyle(height: 48, tint: Theme.ink))
                    if table.canRestart {
                        Button { ads.afterGame(then: model.restart) } label: { Text("Play again").frame(maxWidth: .infinity) }
                            .buttonStyle(PrimaryButtonStyle(height: 48))
                    } else {
                        Text("Waiting for the host to start again…")
                            .font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.top, 4 * s)
            }
            .padding(.horizontal, 24 * s)
            .padding(.vertical, 20 * s)
            .frame(width: 380 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 28 * s))
            .shadow(color: Theme.ink.opacity(0.35), radius: 25 * s, y: 20 * s)
            .foregroundStyle(Theme.ink)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
        .transition(.opacity)
    }
}

/// Reports a view's frame in a named coordinate space.
private struct FrameReader: ViewModifier {
    let space: String
    @Binding var frame: CGRect

    func body(content: Content) -> some View {
        content.onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: { frame = $0 }
    }
}

/// "How to play" with this game's options.
struct UlarTanggaRulesSheet: View {
    let options: UlarTanggaOptions
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    rule("Everyone throws the die to set the playing order, highest first; tied players throw again between themselves. Turns then go round in that order.")
                    if options.sixToEnter {
                        rule("You need a 6 to get on the board; it puts you on square 1.")
                    } else {
                        rule("Everyone starts off the board; a roll of 4 puts you on square 4.")
                    }
                    rule("Land on the foot of a ladder: climb to its top.")
                    rule("Land on a snake's head: slide down to its tail.")
                    if options.bounceBack {
                        rule("You must land on 100 exactly; a roll that goes past it walks back down the extra squares.")
                    } else {
                        rule("You must land on 100 exactly; a roll that goes past it leaves you where you are.")
                    }
                    if options.sixRollsAgain { rule("A 6 rolls again.") }
                    if options.firstFinishEnds {
                        rule("The first player to reach 100 wins and the game ends.")
                    } else {
                        rule("Players who reach 100 leave the board in order; play goes on until one player is left.")
                    }
                }
                .padding(24)
            }
            .background(Theme.surface)
            .navigationTitle(Text("How to play"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() } }
            }
        }
    }

    private func rule(_ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(Theme.accent).frame(width: 7, height: 7)
            Text(text).font(Theme.font(16, .semibold)).foregroundStyle(Theme.ink)
        }
    }
}

/// Hosts the RealityKit view owned by the model.
private struct BoardContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
