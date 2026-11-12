import RealityKit
import Shared
import SwiftUI

/// Ludo on a voxel board, driven by a `LudoTable`. Every snapshot is animated in order: a roll
/// throws the die, then a token hops along its path and captures fly home. Same flow as
/// Android's `LudoScreen`. The board fits the free space around the HUD the way ular tangga does.
@MainActor
final class LudoGameModel: ObservableObject {
    @Published private(set) var state: LudoState
    @Published private(set) var animating = false
    @Published private(set) var viewFrame: CGRect = .zero
    /// Seconds left to throw while it is the local player's roll; 0 when not counting.
    @Published private(set) var rollSeconds = 0

    let table: LudoTable
    let scene = LudoScene()
    var playerOrigins: [Int: CGPoint] = [:]
    private let sounds = Sounds.shared
    private let game = LudoGame.shared
    private let board = LudoBoard.shared
    private var queue: [LudoUpdate] = []
    private let lifetime = GameLifetime()
    /// How the local player threw the die, for the roll the table hasn't sent back yet.
    private var pendingToss: LudoScene.Toss?
    /// The loading screen still covers the board; computers wait for it.
    var loading = true
    /// Total seconds for the roll countdown (`Krida.ROLL_TURN_MS`).
    var rollTotal: Int { Int(Krida.shared.ROLL_TURN_MS / 1000) }

    private static let hop: Double = 0.2
    private static let botPause: Double = 0.7

    init(table: LudoTable) {
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
        queue.append(LudoUpdate(state: table.latest, fresh: false))
        drain()
    }

    var players: Int { state.tokens.count }
    var turn: Int { Int(state.turn) }
    var queueEmpty: Bool { queue.isEmpty }

    var canRoll: Bool {
        lifetime.active && !animating && queue.isEmpty && !state.finished && state.roll == nil && table.isLocal(turn)
    }
    var canPick: Bool {
        lifetime.active && !animating && queue.isEmpty && !state.finished && state.roll != nil && table.isLocal(turn)
    }
    var legal: [Int] {
        guard let roll = state.roll?.intValue else { return [] }
        return game.legalPawns(state: state, seat: Int32(turn), roll: Int32(roll)).map(\.intValue)
    }

    func stop() {
        guard lifetime.active else { return }
        lifetime.stop()
        table.onUpdate = nil
        scene.onDieBounce = nil
        scene.showPickRings([])
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

    func updateReady() { scene.dieReady = canRoll }

    private func spot(_ s: LudoState, _ seat: Int, _ pawn: Int) -> SIMD3<Float> {
        LudoScene.vec(board.tokenAnchor(color: s.color(seat: Int32(seat)), pawn: Int32(pawn), pos: s.tokens[seat][pawn].int32Value))
    }

    private func placeAll(_ s: LudoState) {
        // Your colour (or seat 0 when passing the phone) sits bottom-left.
        let viewSeat = table.mySeat ?? 0
        let clamped = min(max(viewSeat, 0), s.tokens.count - 1)
        scene.setViewColor(Int(s.color(seat: Int32(clamped))))
        scene.setSeats((0..<s.tokens.count).map { Int(s.color(seat: Int32($0))) })
        for seat in 0..<s.tokens.count {
            for pawn in 0..<Int(game.PAWNS) { scene.place(seat, pawn, spot(s, seat, pawn)) }
        }
    }

    func roll(_ toss: LudoScene.Toss) {
        guard canRoll, pendingToss == nil else { scene.holdDie(false); return }
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

    /// Starts or clears the throw countdown whenever roll availability changes.
    func syncRollTimer() {
        lifetime.cancel("rollTurn")
        guard canRoll else {
            rollSeconds = 0
            return
        }
        // Recover if a prior recall raced the throw frame and left the die on the board.
        recallDie(for: state)
        rollSeconds = rollTotal
        lifetime.launch("rollTurn") { [self] in
            while rollSeconds > 0 {
                try await Task.sleep(for: .seconds(1))
                guard lifetime.active, canRoll else {
                    rollSeconds = 0
                    return
                }
                if pressing { continue }
                let next = rollSeconds - 1
                rollSeconds = next
                if next == 0, canRoll, !pressing {
                    roll(.short)
                    return
                }
            }
        }
    }

    func movePawn(_ pawn: Int) {
        guard canPick, legal.contains(pawn) else { return }
        _ = table.movePawn(seat: turn, pawn: pawn)
    }

    /// Gold rings under legal tokens; zoom onto them; ease back out when pick ends.
    func syncWatch() {
        guard !animating else { return }
        if canPick, !legal.isEmpty {
            let spots = legal.map { spot(state, turn, $0) }
            scene.showPickRings(spots)
            scene.watch(spots)
        } else {
            scene.showPickRings([])
            scene.watch(nil)
        }
    }

    /// Zoom onto the tokens the player may tap.
    func watchLegal() {
        syncWatch()
    }

    /// Pick the nearest legal token under `point` (screen / named space coords).
    func tapToken(at point: CGPoint) {
        guard canPick else { return }
        let reach: CGFloat = 72
        let hit = legal
            .map { pawn in (pawn, scene.tokenOnScreen(turn, pawn)) }
            .min { a, b in
                hypot(a.1.x - point.x, a.1.y - point.y) < hypot(b.1.x - point.x, b.1.y - point.y)
            }
        guard let hit, hypot(hit.1.x - point.x, hit.1.y - point.y) <= reach else { return }
        movePawn(hit.0)
    }

    // MARK: Holding the die

    private var pressing = false
    private var spinning = false

    func pressDie() {
        guard !pressing, canRoll, pendingToss == nil else { return }
        pressing = true
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

    /// Call after state / animation changes that affect `canRoll`.
    func afterReadyChange() {
        updateReady()
        syncRollTimer()
        syncWatch()
    }

    func restart() { if lifetime.active { table.restart() } }

    private func idle() {
        afterReadyChange()
        lifetime.launch("bot") { [self] in
            while loading { try await Task.sleep(for: .milliseconds(100)) }
            try await Task.sleep(for: .seconds(Self.botPause))
            if !animating && queue.isEmpty { table.stepBot() }
        }
    }

    private func toss(for seat: Int) -> LudoScene.Toss {
        defer { pendingToss = nil }
        return table.isLocal(seat) ? pendingToss ?? .short : .short
    }

    private func recallDie(for next: LudoState) {
        let seat = Int(next.turn)
        scene.recallDie(origin: table.isLocal(seat) ? nil : playerOrigins[seat])
    }

    private func watchDie() {
        let land = board.dieLanding
        scene.watch([
            SIMD3(land.get(index: 0), board.TOP, land.get(index: 1)),
            SIMD3(land.get(index: 2), board.TOP, land.get(index: 3)),
        ])
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
                // Always ease back out, even if the animation is cancelled mid-hop.
                scene.watch(nil)
                animating = false
                if lifetime.active { drain() }
            }
            let before = state
            let next = update.state
            if update.fresh, let seat = next.lastSeat?.intValue, let roll = next.lastRoll?.intValue {
                scene.showPickRings([])
                let origin = table.isLocal(seat) ? nil : playerOrigins[seat]
                // A pick after a multi-choice roll keeps lastRoll; only throw when this update is
                // the roll itself (or an auto-move that applied the roll in one step).
                let throwNow = before.roll == nil
                if throwNow {
                    watchDie()
                    let seconds = scene.throwDie(roll, toss: toss(for: seat), origin: origin, seat: seat)
                    // Small buffer so the last throw frame can mark landed before we hop (and recall).
                    try await Task.sleep(for: .seconds(Double(seconds) + 0.2))
                } else {
                    _ = toss(for: seat)
                }
                if let pawn = next.lastPawn?.intValue, let from = next.lastFrom?.intValue, let to = next.lastTo?.intValue {
                    let color = next.color(seat: Int32(seat))
                    let path = game.walk(from: Int32(from), to: Int32(to)).map { pos in
                        LudoScene.vec(board.tokenAnchor(color: color, pawn: Int32(pawn), pos: pos.int32Value))
                    }
                    // Frame only the mover (start + hops). Capture yards used to pull the
                    // camera across the board away from the piece that moves.
                    let start = LudoScene.vec(board.tokenAnchor(color: color, pawn: Int32(pawn), pos: Int32(from)))
                    scene.watch([start] + path)
                    try await Task.sleep(for: .seconds(0.18))
                    scene.move(seat, pawn, path, seconds: Float(Double(path.count) * Self.hop), hop: 0.35)
                    // The same tok on each square as ular tangga.
                    for _ in path {
                        sounds.play(.step)
                        try await Task.sleep(for: .seconds(Self.hop))
                    }
                    try await Task.sleep(for: .seconds(0.08))
                    for cap in next.lastCaptures {
                        let fromSpot = spot(state, Int(cap.seat), Int(cap.pawn))
                        let back = spot(next, Int(cap.seat), Int(cap.pawn))
                        scene.watch([fromSpot, back])
                        scene.move(Int(cap.seat), Int(cap.pawn), [back], seconds: 0.35, hop: 0.5)
                    }
                    if !next.lastCaptures.isEmpty {
                        sounds.play(.capture)
                        try await Task.sleep(for: .seconds(0.4))
                    }
                }
                placeAll(next)
                recallDie(for: next)
                try await Task.sleep(for: .seconds(0.18))
                scene.watch(nil)
                try await Task.sleep(for: .seconds(0.28))
                state = next
                announce(next)
            } else {
                state = next
                placeAll(next)
                recallDie(for: next)
            }
        }
    }

    /// Voice for whose turn it is; pass-and-play gets the chime only.
    private func announceTurn(_ seat: Int) {
        if let me = table.mySeat { sounds.say(seat == me ? .yourTurn : .theirTurn) } else { sounds.play(.turn) }
    }

    private func announce(_ after: LudoState) {
        let me = table.mySeat
        if after.finished {
            let won = me == nil || after.winner?.intValue == me
            sounds.play(won ? .win : .lose)
            if me != nil { sounds.say(won ? .win : .lose) }
        } else if after.turn != after.lastSeat?.int32Value {
            announceTurn(Int(after.turn))
        }
    }
}

/// The game screen: the loading screen goes up first, the board is built under it, and the
/// loading screen fades out once the board's first frames are drawn (as ular tangga).
struct LudoView: View {
    let table: LudoTable
    let onExit: () -> Void
    @State private var mounted = false
    @State private var loading = true

    var body: some View {
        ZStack {
            if mounted { LudoBoardView(table: table, onExit: onExit, loading: loading, onDrawn: finishLoading) }
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

private struct LudoBoardView: View {
    @ObservedObject private var table: LudoTable
    @StateObject private var model: LudoGameModel
    @ObservedObject private var ads = Ads.shared
    @State private var showRules = false
    @State private var size: CGSize = .zero
    @State private var boxes: [String: CGRect] = [:]
    @GestureState private var dieDown = false
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole
    let onExit: () -> Void
    let loading: Bool
    let onDrawn: () -> Void

    init(table: LudoTable, onExit: @escaping () -> Void, loading: Bool, onDrawn: @escaping () -> Void) {
        self.table = table
        _model = StateObject(wrappedValue: LudoGameModel(table: table))
        self.onExit = onExit
        self.loading = loading
        self.onDrawn = onDrawn
    }

    private static let space = "ludo"

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
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(coordinateSpace: .named(Self.space)) { model.tapToken(at: $0) }
                    .allowsHitTesting(model.canPick)
                hud
                if model.state.finished && !model.animating { gameOver }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .background(alignment: .topLeading) {
                LudoBoardContainer(view: model.scene.view)
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
        .onChange(of: model.canPick) { _, _ in
            model.syncWatch()
            relayout()
        }
        .onChange(of: model.legal) { _, _ in
            model.syncWatch()
        }
        .onChange(of: dieDown) { _, down in
            if !down { DispatchQueue.main.async { model.releaseDie(completed: false) } }
        }
        .onChange(of: loading) { _, now in
            model.loading = now
            model.afterReadyChange()
        }
        .onChange(of: model.animating) { _, _ in
            model.afterReadyChange()
        }
        .onChange(of: model.canRoll) { _, _ in
            model.syncRollTimer()
        }
        .sheet(isPresented: $showRules) { LudoRulesSheet(options: model.state.options) }
    }

    private static let hudKeys = ["back", "players", "buttons", "die", "status"]

    private func relayout() {
        guard size.width > 0, let room = boxes["room"], let die = boxes["die"], die.width > 0,
              Self.hudKeys.allSatisfy({ boxes[$0] != nil }) else { return }
        let margin = 12 * s
        var keys = Self.hudKeys
        if model.canRoll && model.rollSeconds > 0 { keys.append("rollTimer") }
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
        LudoFrameReader(space: Self.space, frame: Binding(get: { boxes[key] ?? .zero }, set: { boxes[key] = $0 }))
    }

    private func displayName(_ seat: Int) -> String {
        table.mySeat == seat ? String(localized: "You", bundle: .app) : table.name(seat)
    }

    private var status: String {
        let st = model.state
        if st.finished || model.animating { return "" }
        let seat = model.turn
        if st.burnedSix && st.lastSeat?.intValue == seat {
            return String(localized: "Three sixes · turn ends", bundle: .app)
        }
        if model.canPick { return String(localized: "Tap a token to move", bundle: .app) }
        if table.isLocal(seat) && st.roll == nil {
            return String(localized: "Your turn · roll the die", bundle: .app)
        }
        if st.lastRoll?.intValue == 6 && st.lastSeat?.intValue == seat {
            return String(localized: "A six · roll again", bundle: .app)
        }
        if !st.lastCaptures.isEmpty && st.lastSeat?.intValue == seat {
            return String(localized: "Capture · roll again", bundle: .app)
        }
        return String(localized: "\(displayName(seat))'s turn", bundle: .app)
    }

    private var hud: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 10 * s) {
                CircleIconButton(systemImage: "chevron.left", label: "Back to menu", action: exit)
                    .modifier(hudBox("back"))
                Spacer().frame(height: 4 * s)
                let count = model.players
                let rows = stride(from: 0, to: count, by: 2).map { Array($0..<min($0 + 2, count)) }
                VStack(spacing: 8 * s) {
                    ForEach(rows, id: \.self) { pair in
                        HStack(spacing: 8 * s) {
                            ForEach(pair, id: \.self) { seat in
                                tile(seat, on: seat == model.turn && !model.state.finished && model.queueEmpty)
                                    .modifier(hudBox("seat-\(seat)"))
                            }
                            if pair.count == 1 { Spacer(minLength: 0) }
                        }
                    }
                }
                .modifier(hudBox("players"))
                Spacer(minLength: 0)
            }
            .frame(width: 136 * s)
            .frame(maxHeight: .infinity)
            Spacer(minLength: 0)
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
                if model.canRoll && model.rollSeconds > 0 {
                    CountdownRing(seconds: model.rollSeconds, total: model.rollTotal, size: 40)
                        .modifier(hudBox("rollTimer"))
                        .padding(.bottom, 6 * s)
                }
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
                    .font(Theme.font(13 * s, .bold))
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2, reservesSpace: true)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .modifier(hudBox("status"))
                    .padding(.top, 8 * s)
                if let away = table.waiting {
                    CountdownNotice(text: away.text, seconds: away.seconds, total: Int(Krida.shared.HANDOFF_WAIT_MS / 1000), fontSize: 13)
                        .frame(maxWidth: .infinity)
                        .modifier(hudBox("notice"))
                        .padding(.top, 8 * s)
                } else if let notice = table.notice {
                    Text(notice)
                        .font(Theme.font(13 * s, .bold))
                        .foregroundStyle(Theme.error)
                        .multilineTextAlignment(.center)
                        .lineLimit(3)
                        .padding(.horizontal, 8 * s)
                        .padding(.vertical, 6 * s)
                        .background(Theme.surface.opacity(0.92), in: RoundedRectangle(cornerRadius: 12 * s))
                        .frame(maxWidth: .infinity)
                        .modifier(hudBox("notice"))
                        .padding(.top, 8 * s)
                }
            }
            .frame(width: 148 * s)
            .frame(maxHeight: .infinity)
        }
        .modifier(hudBox("room"))
        .padding(.horizontal, 24 * s)
        .padding(.leading, max(max(hole?.leading ?? 0, sideInsets.leading) - 24 * s, 0))
        .padding(.trailing, max(max(hole?.trailing ?? 0, sideInsets.trailing) - 24 * s, 0))
        .padding(.vertical, 14 * s)
        .foregroundStyle(Theme.ink)
    }

    private var dieGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .local)
            .updating($dieDown) { _, down, _ in down = true }
            .onChanged { value in
                model.pressDie()
                model.moveDie(value.translation)
            }
            .onEnded { _ in model.releaseDie(completed: true) }
    }

    private func tile(_ seat: Int, on: Bool) -> some View {
        let color = Int(model.state.color(seat: Int32(seat)))
        let shape = RoundedRectangle(cornerRadius: 12 * s)
        return VStack(spacing: 3 * s) {
            Circle().fill(pawnColor(color)).frame(width: 22 * s, height: 22 * s)
            Text(displayName(seat))
                .font(Theme.font(11 * s, .bold))
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(6 * s)
        .frame(maxWidth: .infinity)
        .background(on ? Theme.chip : Theme.surface, in: shape)
        .overlay(shape.stroke(on ? pawnColor(color) : Theme.line, lineWidth: on ? 2 : 1.5))
        .animation(.easeOut(duration: 0.2), value: on)
        .accessibilityElement(children: .combine)
    }

    private var gameOver: some View {
        let st = model.state
        let ranking = LudoGame.shared.ranking(state: st).map(\.intValue)
        let you = table.mySeat
        let title: String = if let you, ranking.first == you {
            String(localized: "You win!", bundle: .app)
        } else if let winner = ranking.first {
            String(localized: "\(table.name(winner)) wins", bundle: .app)
        } else {
            String(localized: "Ludo", bundle: .app)
        }
        return ZStack {
            Color.black.opacity(0.28).ignoresSafeArea()
            VStack(spacing: 12 * s) {
                Text(title).font(Theme.font(22 * s, .black))
                Text("All four tokens home.")
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
                VStack(spacing: 6 * s) {
                    ForEach(Array(ranking.enumerated()), id: \.offset) { i, seat in
                        HStack(spacing: 10 * s) {
                            Circle().fill(pawnColor(Int(st.color(seat: Int32(seat))))).frame(width: 18 * s, height: 18 * s)
                            Text("\(i + 1). \(displayName(seat))")
                                .font(Theme.font(16 * s, .bold))
                                .lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
                HStack(spacing: 10 * s) {
                    Button { ads.afterGame(then: exit) } label: { Text("Menu").frame(maxWidth: .infinity) }
                        .buttonStyle(SecondaryButtonStyle(height: 48, tint: Theme.muted))
                    if table.canRestart {
                        Button { ads.afterGame(then: model.restart) } label: { Text("Play again").frame(maxWidth: .infinity) }
                            .buttonStyle(PrimaryButtonStyle(height: 48))
                    }
                }
            }
            .padding(24 * s)
            .frame(maxWidth: 480 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
            .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
            .padding(.horizontal, 24 * s)
            .foregroundStyle(Theme.ink)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
        .transition(.opacity)
    }
}

private struct LudoFrameReader: ViewModifier {
    let space: String
    @Binding var frame: CGRect

    func body(content: Content) -> some View {
        content.onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: { frame = $0 }
    }
}

/// "How to play" with this game's options. Same lines as Android's rules sheet.
struct LudoRulesSheet: View {
    let options: LudoOptions
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(lines.indices, id: \.self) { i in rule(lines[i]) }
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

    private var lines: [LocalizedStringKey] {
        var lines: [LocalizedStringKey] = [
            options.sixToEnter
                ? "A 6 leaves the yard onto your colour's start square."
                : "Any roll can leave the yard and walk that many steps.",
            "Tokens walk clockwise around the board, then up your home stretch.",
            "You must finish exactly; a roll that goes past stays put.",
            "Landing on a lone opponent sends it to the yard.",
            "Two of your tokens on one square are safe; others cannot land there.",
            "Three 6s in a row burn the third roll and end the turn.",
        ]
        if options.sixRollsAgain { lines.append("A 6 rolls again.") }
        if options.captureRollsAgain { lines.append("A capture rolls again.") }
        lines.append(options.firstFinishEnds
            ? "The first player to get all four home wins and the game ends."
            : "Players who finish leave the board in order; play goes on until one is left.")
        return lines
    }

    private func rule(_ text: LocalizedStringKey) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Circle().fill(Theme.accent).frame(width: 7, height: 7)
            Text(text).font(Theme.font(16, .semibold)).foregroundStyle(Theme.ink)
        }
    }
}

private struct LudoBoardContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
