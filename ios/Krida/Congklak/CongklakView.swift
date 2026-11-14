import RealityKit
import Shared
import SwiftUI

/// Congklak on a voxel board, driven by a `CongklakTable`. The board is turned so this
/// device's seat sits on the near side (seat 0 for pass-and-play). Every snapshot from the
/// table is animated in order: the move it carries is replayed from the shown state, then
/// whatever still differs (captures, the end sweep, a restart) settles into place.
/// Rules, board geometry and seed bookkeeping all come from shared/.
@MainActor
final class CongklakGameModel: ObservableObject {
    @Published private(set) var state: CongklakState
    @Published private(set) var counts: [Int]
    @Published private(set) var pitPoints: [CGPoint] = []
    @Published private(set) var animating = false
    /// A guest's move is on its way to the host.
    @Published private(set) var waiting = false
    /// Seeds in the sowing hand while a move animates.
    @Published private(set) var inHand = 0
    /// Hole suggested for the current turn, paid for with a rewarded ad; cleared by the next update.
    @Published private(set) var hint: Int?
    /// Seconds left for the local player to pick a pit (`Krida.PLAY_TURN_MS`); 0 when not counting.
    @Published private(set) var turnSeconds = 0

    let table: CongklakTable
    let board = BoardScene()
    private let sounds = Sounds.shared
    private let seeds: CongklakSeeds
    private var queue: [CongklakUpdate] = []
    private let lifetime = GameLifetime()
    private var turnKey: String?
    /// Counts state changes, so an extra turn (same seat) restarts the countdown.
    private var version = 0

    private static let pickUpDelay: UInt64 = 260_000_000
    private static let sowDelay: UInt64 = 170_000_000
    private static let settleDelay: UInt64 = 320_000_000
    private static let botPause: UInt64 = 600_000_000

    init(table: CongklakTable) {
        self.table = table
        let start = table.latest!
        state = start
        counts = start.pits.map { $0.intValue }
        seeds = CongklakSeeds(pits: start.pits)
        board.viewSeat = table.mySeat ?? 0
        board.setSeedTargets(seeds.targets())
        board.onPitsProjected = { [weak self] points in self?.pitPoints = points }
        table.onUpdate = { [weak self] update in
            guard let self, self.lifetime.active else { return }
            self.waiting = false
            self.queue.append(update)
            self.drain()
        }
        drain()
    }

    var game: CongklakGame { CongklakGame.shared }
    var turn: Int { Int(state.turn) }
    var finished: Bool { state.finished }
    var viewSeat: Int32 { board.viewSeat }

    /// Seconds for a turn (`Krida.PLAY_TURN_MS`).
    var turnTotal: Int { Int(Krida.shared.PLAY_TURN_MS / 1000) }

    var canPlay: Bool {
        lifetime.active && !animating && !waiting && queue.isEmpty && !finished && (table.mySeat == nil || table.mySeat == state.turn)
    }

    /// Seat on this phone (pass-and-play or own seat), not a computer.
    private func isLocalSeat(_ seat: Int) -> Bool {
        let bot = table.botLevel != nil ? 1 - Int(table.mySeat ?? 0) : -1
        return seat != bot && (table.mySeat == nil || Int(table.mySeat!) == seat)
    }

    /// Stop work owned by this board before navigation releases it.
    func stop() {
        guard lifetime.active else { return }
        lifetime.stop()
        table.onUpdate = nil
        queue.removeAll()
        table.leave()
        sounds.stopGameAudio()
    }

    func layout(size: CGSize) {
        board.layout(size: size)
        pitPoints = board.pitPoints
    }

    /// A notice arrived (refusal, disconnect): stop waiting for the host's answer.
    func noticeChanged() {
        waiting = false
        updateRings()
    }

    /// The hard bot's choice for the player to move, after a rewarded ad (free without ads).
    func showHint() {
        Ads.shared.rewarded { [weak self] in
            guard let self, canPlay else { return }
            hint = Int(CongklakBot(level: .hard).chooseMove(state: state, seat: state.turn, random: KotlinRandom.companion).hole)
            updateRings()
        }
    }

    /// Gold rings on the holes the mover may start from, only while this device can play;
    /// a hint narrows them to the suggested hole.
    private func updateRings() {
        syncTimer()
        guard canPlay else { return board.showRings([]) }
        let seat = state.turn
        if let hint { return board.showRings([Int(game.pit(seat: seat, hole: Int32(hint)))]) }
        let holes = (0..<Int32(game.HOLES)).filter { game.validate(state: state, seat: seat, move: CongklakMove(hole: $0)) == nil }
        board.showRings(Set(holes.map { Int(game.pit(seat: seat, hole: $0)) }))
    }

    /// The seat to move is picked from this device (not the computer) and may play now.
    private var localTurn: Bool {
        let bot: Int? = table.botLevel == nil ? nil : 1 - Int(table.mySeat ?? 0)
        return canPlay && turn != bot
    }

    /// The turn countdown: at 0 the phone plays the easy computer's pick for the player
    /// (`CongklakGame.timeoutMove`).
    /// Sowing and waiting on the host stop it; the next still board starts a new one.
    private func syncTimer() {
        let key = localTurn ? "\(state.turn)-\(version)" : nil
        guard key != turnKey else { return }
        turnKey = key
        lifetime.cancel("turn")
        turnSeconds = 0
        guard key != nil else { return }
        let auto = autoSeats.contains(turn)
        turnSeconds = auto ? 0 : turnTotal
        lifetime.launch("turn") { [self] in
            if auto {
                try await Task.sleep(for: .seconds(Double(Krida.shared.AUTO_PLAY_MS) / 1000))
            } else {
                while turnSeconds > 0 {
                    try await Task.sleep(for: .seconds(1))
                    guard localTurn else { turnSeconds = 0; return }
                    turnSeconds -= 1
                }
                autoSeats.insert(turn)
            }
            guard localTurn, autoSeats.contains(turn) else { return }
            if let move = game.timeoutMove(state: state, seat: state.turn, random: KotlinRandom.companion) { play(hole: Int(move.hole), byPlayer: false) }
        }
    }

    /// Seats on this phone whose timer ran out: the phone picks for them after a short pause
    /// (`Krida.AUTO_PLAY_MS`) instead of a countdown, until that player picks a pit or taps autopilot.
    @Published private(set) var autoSeats = Set<Int>()

    /// Leave autopilot without picking: the next local turn gets a normal countdown again.
    func resumeManual(seat: Int? = nil) {
        let seat = seat ?? turn
        guard autoSeats.contains(seat) else { return }
        autoSeats.remove(seat)
        guard localTurn, self.turn == seat else { return }
        turnKey = nil
        syncTimer()
    }

    /// A tap at a view point: plays the nearest hole on the mover's side, if close enough.
    func tap(at point: CGPoint) {
        guard canPlay, pitPoints.count == Int(game.PITS) else { return }
        let reach = hypot(pitPoints[1].x - pitPoints[0].x, pitPoints[1].y - pitPoints[0].y) * 0.6
        let seat = state.turn
        let hole = (0..<Int(game.HOLES)).min { a, b in
            distance(point, hole: a, seat: seat) < distance(point, hole: b, seat: seat)
        }!
        if distance(point, hole: hole, seat: seat) <= reach { play(hole: hole) }
    }

    private func distance(_ p: CGPoint, hole: Int, seat: Int32) -> CGFloat {
        let q = pitPoints[Int(game.pit(seat: seat, hole: Int32(hole)))]
        return hypot(q.x - p.x, q.y - p.y)
    }

    private func play(hole: Int, byPlayer: Bool = true) {
        let seat = state.turn
        guard canPlay, game.validate(state: state, seat: seat, move: CongklakMove(hole: Int32(hole))) == nil else { return }
        if byPlayer { autoSeats.remove(Int(seat)) }
        // Pass-and-play and the host answer synchronously; a guest waits for the host's snapshot.
        if table.play(seat: seat, hole: Int32(hole)), table.isRemote {
            waiting = true
            board.showRings([])
            syncTimer()
        }
    }

    /// Once the board is still, give a computer player its turn after a short pause.
    private func idle() {
        lifetime.launch("bot") { [weak self] in
            do { try await Task.sleep(nanoseconds: Self.botPause) }
            catch { return }
            guard let self, self.lifetime.active, !Task.isCancelled else { return }
            if !animating && queue.isEmpty { table.stepBot() }
        }
    }

    private func drain() {
        guard lifetime.active, !animating else { return }
        guard !queue.isEmpty else {
            updateRings()
            return idle()
        }
        let update = queue.removeFirst()
        lifetime.cancel("bot")
        animating = true
        board.showRings([])
        syncTimer()
        lifetime.launch("animation") { [self] in
            defer {
                animating = false
                if lifetime.active { drain() }
            }
            var sowed = false
            var shot = false
            if let seat = update.seat, let move = update.move, game.validate(state: state, seat: seat, move: move) == nil {
                let sowing = game.play(state: state, seat: seat, move: move)
                let steps = CongklakSeeds.companion.steps(before: state.pits, startPit: game.pit(seat: seat, hole: move.hole), path: sowing.path)
                for step in steps {
                    guard lifetime.active, !Task.isCancelled else { return }
                    seeds.apply(step: step)
                    push()
                    if !step.pickUp { sounds.play(game.isStore(pit: step.pit) ? .store : .sow) }
                    do { try await Task.sleep(nanoseconds: step.pickUp ? Self.pickUpDelay : Self.sowDelay) }
                    catch { return }
                }
                sowed = true
                shot = sowing.capturedFrom != nil
            }
            // Captures, the end-of-game sweep, a restart: move whatever differs to match.
            if seeds.counts() != update.state.pits {
                if sowed {
                    do { try await Task.sleep(nanoseconds: Self.settleDelay) }
                    catch { return }
                }
                guard lifetime.active, !Task.isCancelled else { return }
                if shot {
                    sounds.play(.capture)
                    sounds.say(.shoot)
                }
                seeds.settle(pits: update.state.pits)
                push()
            }
            guard lifetime.active, !Task.isCancelled else { return }
            if sowed {
                announce(before: state, after: update.state)
                do { try await Task.sleep(for: .seconds(0.28)) }
                catch { return }
            }
            state = update.state
            version += 1
            hint = nil
        }
    }

    /// After a move: the result when the game ends, otherwise whose turn it is when it changed.
    /// Voice lines need a seat on this device; pass-and-play gets the chime and effects only.
    private func announce(before: CongklakState, after: CongklakState) {
        let me = table.mySeat
        if after.finished {
            let winner = game.winner(state: after)?.intValue ?? -1
            if winner < 0 {
                sounds.play(.win)
                sounds.say(.draw)
            } else if me == nil || Int(me!) == winner {
                sounds.play(.win)
                if me != nil { sounds.say(.win) }
            } else {
                sounds.play(.lose)
                sounds.say(.lose)
            }
        } else if after.turn != before.turn {
            if let me { sounds.say(after.turn == me ? .yourTurn : .theirTurn) } else { sounds.play(.turn) }
        }
    }

    private func push() {
        board.setSeedTargets(seeds.targets())
        counts = seeds.counts().map { $0.intValue }
        inHand = Int(seeds.handCount())
    }
}

/// The game screen. Building the RealityKit board blocks the main thread for a moment, so the
/// loading screen goes up first, the board is built under it, and the loading screen fades out
/// once the board's first frames are drawn (or after a few seconds, if they never come).
struct CongklakView: View {
    let table: CongklakTable
    let onExit: () -> Void
    @State private var mounted = false
    @State private var loading = true

    var body: some View {
        ZStack {
            if mounted { CongklakBoardView(table: table, onExit: onExit, onDrawn: finishLoading) }
            if loading { LoadingScreen().transition(.opacity) }
        }
        .task {
            // Let the loading screen reach the display before the board is built.
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

private struct CongklakBoardView: View {
    @ObservedObject private var table: CongklakTable
    @StateObject private var model: CongklakGameModel
    @State private var showRules = false
    @ObservedObject private var ads = Ads.shared
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole
    /// Board size relative to the phone design: the board fills the view's width.
    @State private var boardScale: CGFloat = 1
    let onExit: () -> Void
    let onDrawn: () -> Void

    init(table: CongklakTable, onExit: @escaping () -> Void, onDrawn: @escaping () -> Void) {
        self.table = table
        _model = StateObject(wrappedValue: CongklakGameModel(table: table))
        self.onExit = onExit
        self.onDrawn = onDrawn
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                BoardContainer(view: model.board.view)
                    .ignoresSafeArea()
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(coordinateSpace: .local) { model.tap(at: $0) }

                ForEach(Array(model.pitPoints.enumerated()), id: \.offset) { pit, point in
                    countLabel(pit: pit).position(labelPosition(pit: pit, at: point))
                }

                hud
                if model.finished && !model.animating { gameOver }
            }
            .onAppear {
                model.board.whenDrawn(onDrawn)
                DispatchQueue.main.async { layout(geo.size) }
            }
            .onChange(of: geo.size) { _, size in DispatchQueue.main.async { layout(size) } }
        }
        .ignoresSafeArea()
        .background(Theme.backdropGradient)
        .onDisappear { model.stop() }
        .onChange(of: table.notice) { _, notice in if notice != nil { model.noticeChanged() } }
        .sheet(isPresented: $showRules) { CongklakRulesSheet(tembakAfterStore: model.state.tembakAfterStore) }
    }

    private func layout(_ size: CGSize) {
        model.layout(size: size)
        boardScale = size.width / LandscapeStage<EmptyView>.designSize.width
    }

    private func leave() {
        model.stop()
        onExit()
    }

    /// Back / Menu: stop the board (and its audio) first, then interstitial, then navigate.
    private func exit() {
        model.stop()
        ads.afterGame(then: onExit)
    }

    /// Interstitials only where nobody waits on this device: local games, or leaving to the menu.
    private func restart() {
        if table.mySeat == nil || table.botLevel != nil {
            ads.afterGame(then: table.restart)
        } else {
            table.restart()
        }
    }

    // MARK: Seats

    private var nearSeat: Int { Int(model.viewSeat) }
    private var farSeat: Int { 1 - nearSeat }
    private var botSeat: Int? { table.botLevel == nil ? nil : 1 - Int(table.mySeat ?? 0) }

    private func name(_ seat: Int) -> String {
        table.players.first { $0.seat == Int32(seat) }?.name ?? "\(seat + 1)"
    }

    /// "You" for this device's own seat; the player's name otherwise.
    private func displayName(_ seat: Int) -> String {
        table.mySeat.map(Int.init) == seat ? String(localized: "You", bundle: .app) : name(seat)
    }

    /// The seat can be played from this device (own seat, or anyone in pass-and-play).
    private func isLocal(_ seat: Int) -> Bool {
        seat != botSeat && (table.mySeat == nil || Int(table.mySeat!) == seat)
    }

    private func active(_ seat: Int) -> Bool { !model.finished && model.turn == seat }

    private func status(_ seat: Int) -> String {
        guard active(seat) else { return String(localized: "waiting", bundle: .app) }
        if model.animating && model.inHand > 0 { return String(localized: "Sowing · \(model.inHand) seeds in hand", bundle: .app) }
        if isLocal(seat) { return String(localized: "Your turn · pick a glowing hole", bundle: .app) }
        return seat == botSeat ? String(localized: "thinking…", bundle: .app) : String(localized: "their turn", bundle: .app)
    }

    // MARK: HUD

    private var hud: some View {
        ZStack(alignment: .bottomTrailing) {
            VStack(spacing: 0 * s) {
                PillTopBar(gap: 8 * s) {
                    CircleIconButton(systemImage: "chevron.left", label: "Back to menu", action: exit)
                        .clearPunchHole(hole, .topLeading, already: 24 * s)
                    opponentPill
                    HStack(spacing: 8 * s) {
                        // Pass-and-play: far seat's timer sits by the speaker on the far right.
                        if isLocal(farSeat) {
                            TurnTimer(
                                seconds: model.turn == farSeat ? model.turnSeconds : 0,
                                total: model.turnTotal,
                                auto: model.autoSeats.contains(farSeat),
                                size: 40,
                                onResume: { model.resumeManual(seat: farSeat) }
                            )
                        }
                        SoundToggles()
                        if model.canPlay && isLocal(model.turn) && model.hint == nil {
                            CircleIconButton(systemImage: "lightbulb", label: ads.removed ? "Hint" : "Hint (watch an ad)", action: model.showHint)
                        }
                        CircleIconButton(systemImage: "questionmark", label: "How to play") { showRules = true }
                    }
                }
                .padding(.horizontal, 24 * s)
                .padding(.top, 14 * s)
                Spacer()
                VStack(spacing: 8 * s) {
                    if let away = table.waiting {
                        CountdownNotice(text: away.text, seconds: away.seconds, total: Int(Krida.shared.HANDOFF_WAIT_MS / 1000))
                    } else if let notice = table.notice {
                        Text(notice).font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.error)
                    } else {
                        Label("Clockwise · your store is on the left", systemImage: "arrow.clockwise")
                            .font(Theme.font(13 * s, .bold))
                            .foregroundStyle(Theme.muted)
                    }
                    // Pill stays centred; near seat's timer / autopilot sits on the far right.
                    ZStack {
                        PlayerPill(seat: pill(nearSeat), on: active(nearSeat), status: status(nearSeat), s: s)
                        if isLocal(nearSeat) {
                            TurnTimer(
                                seconds: model.turn == nearSeat ? model.turnSeconds : 0,
                                total: model.turnTotal,
                                auto: model.autoSeats.contains(nearSeat),
                                size: 40,
                                onResume: { model.resumeManual(seat: nearSeat) }
                            )
                            .clearPunchHole(hole, .bottomTrailing, already: 24 * s)
                            .frame(maxWidth: .infinity, alignment: .trailing)
                        }
                    }
                    .frame(maxWidth: .infinity)
                }
                .padding(.horizontal, 24 * s)
                .padding(.bottom, 14 * s)
            }
            EmoteHud(
                canEmote: table.canEmote,
                emotes: table.emotes,
                playerName: { seat in
                    if let mine = table.mySeat.map(Int.init), mine == seat {
                        return String(localized: "You", bundle: .app)
                    }
                    return table.players.first { Int($0.seat) == seat }?.name ?? "\(seat + 1)"
                },
                onSend: table.sendEmote,
                s: s,
                opensUp: true
            )
            .padding(.trailing, 24 * s)
            .padding(.bottom, 68 * s)
        }
        .foregroundStyle(Theme.ink)
    }

    /// How the seat shows in its pill.
    private func pill(_ seat: Int) -> PillSeat {
        PillSeat(name: displayName(seat), avatar: seat == botSeat ? nil : table.players.avatar(of: seat), bot: seat == botSeat)
    }

    /// The far seat, above the board: who you play against and what they're doing.
    private var opponentPill: some View {
        let seat = farSeat
        let detail = [table.botLevel.flatMap { seat == botSeat ? $0 : nil }, status(seat)].compactMap { $0 }.joined(separator: " · ")
        return OpponentPill(seat: pill(seat), on: active(seat), detail: detail, s: s)
    }

    // MARK: Counts on the rim

    private func countLabel(pit: Int) -> some View {
        let store = model.game.isStore(pit: Int32(pit))
        let near = Int(model.game.ownerOf(pit: Int32(pit))) == nearSeat
        return Group {
            if store {
                VStack(spacing: 3 * s) {
                    Text("\(model.counts[pit])").font(Theme.font(22 * s, .black)).foregroundStyle(Color(hex: 0xFCEBD2))
                    Text(storeTag(near ? nearSeat : farSeat))
                        .font(Theme.font(11 * s, .black))
                        .foregroundStyle(near ? .white : Theme.inkOnLight)
                        .padding(.horizontal, 7 * s)
                        .padding(.vertical, 2 * s)
                        .background(near ? Theme.accent : Theme.tan, in: RoundedRectangle(cornerRadius: 8 * s))
                }
            } else {
                Text("\(model.counts[pit])")
                    .font(Theme.font(15 * s))
                    .foregroundStyle(near ? Color(hex: 0xFFF6E8) : Theme.tan)
            }
        }
        .shadow(color: .black.opacity(0.35), radius: 1 * s, y: 1 * s)
        .allowsHitTesting(false)
    }

    /// Short label on a store: KAMU, KOM, or the player's name.
    private func storeTag(_ seat: Int) -> String {
        if seat == botSeat { return String(localized: "CPU", bundle: .app) }
        return String(displayName(seat).uppercased().prefix(8))
    }

    private func labelPosition(pit: Int, at p: CGPoint) -> CGPoint {
        let store = model.game.isStore(pit: Int32(pit))
        let near = model.game.ownerOf(pit: Int32(pit)) == model.viewSeat
        let offset = 34 * boardScale
        if store { return CGPoint(x: p.x, y: p.y + offset) }
        return CGPoint(x: p.x, y: p.y + (near ? offset : -offset))
    }

    // MARK: End of game

    private var gameOver: some View {
        let winner = model.game.winner(state: model.state)?.intValue
        return ZStack {
            Theme.ink.opacity(0.45).ignoresSafeArea()
            VStack(spacing: 12 * s) {
                Image(systemName: winner == nil || winner! < 0 ? "equal.circle" : "trophy")
                    .font(.system(size: 36 * s, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xC98A1B))
                    .accessibilityHidden(true)
                Text(resultTitle(winner)).font(Theme.font(26 * s, .black))
                HStack(spacing: 10 * s) {
                    scoreTile(nearSeat, winner: winner)
                    scoreTile(farSeat, winner: winner)
                }
                HStack(spacing: 10 * s) {
                    Button(action: exit) { Text("Menu").frame(maxWidth: .infinity) }
                        .buttonStyle(SecondaryButtonStyle(height: 48, tint: Theme.ink))
                    if table.canRestart {
                        Button(action: restart) { Text("Play again").frame(maxWidth: .infinity) }
                            .buttonStyle(PrimaryButtonStyle(height: 48))
                    } else {
                        Text("Waiting for the host to start again…")
                            .font(Theme.font(14 * s, .bold))
                            .foregroundStyle(Theme.muted)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.top, 4 * s)
            }
            .padding(.horizontal, 24 * s)
            .padding(.top, 22 * s)
            .padding(.bottom, 20 * s)
            .frame(width: 380 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 28 * s))
            .shadow(color: Theme.ink.opacity(0.35), radius: 25 * s, y: 20 * s)
            .foregroundStyle(Theme.ink)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
        .transition(.opacity)
    }

    private func resultTitle(_ winner: Int?) -> String {
        guard let winner else { return "" }
        if winner < 0 { return String(localized: "Draw!", bundle: .app) }
        if table.mySeat.map(Int.init) == winner { return String(localized: "You win!", bundle: .app) }
        return String(localized: "\(name(winner)) wins!", bundle: .app)
    }

    private func scoreTile(_ seat: Int, winner: Int?) -> some View {
        let won = winner == seat
        return VStack(spacing: 0 * s) {
            Text(displayName(seat)).font(Theme.font(14 * s)).foregroundStyle(Theme.muted).lineLimit(1)
            Text("\(score(seat))").font(Theme.font(30 * s, .black)).foregroundStyle(won ? Theme.ink : Theme.muted)
        }
        .padding(.vertical, 8 * s)
        .frame(maxWidth: .infinity)
        .background(won ? Theme.highlight : Theme.surfaceSoft, in: RoundedRectangle(cornerRadius: 16 * s))
        .overlay(RoundedRectangle(cornerRadius: 16 * s).stroke(won ? Theme.accent : .clear, lineWidth: 2))
    }

    private func score(_ seat: Int) -> Int { Int(model.game.score(state: model.state, seat: Int32(seat))) }
}

/// "How to play": the rule variant from shared/ CongklakGame, in short.
struct CongklakRulesSheet: View {
    /// The tembak rule this game is played with.
    let tembakAfterStore: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    rule("Take all the seeds from one hole on your side and sow them one by one, clockwise.")
                    rule("Your store on the left gets a seed too; your opponent's store is skipped.")
                    rule("Last seed lands in your store: play again.")
                    rule("Lands in a hole with seeds: pick them all up and keep sowing.")
                    if tembakAfterStore {
                        rule("Lands in an empty hole on your side after passing your store: shoot! That seed and the seeds in the opposite hole go to your store.")
                        rule("Haven't passed your store this turn? The seed stays in that hole and your turn ends.")
                    } else {
                        rule("Lands in an empty hole on your side: shoot! That seed and the seeds in the opposite hole go to your store.")
                    }
                    rule("Lands in an empty hole on your opponent's side: your turn ends.")
                    rule("If all your holes are empty on your turn, the game ends. Most seeds in the store wins.")
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
