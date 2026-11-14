import RealityKit
import Shared
import SwiftUI

/// Gaple on the voxel table, driven by a `GapleTable`: your hand along the bottom, tap a tile
/// that fits (and an end when it fits both), 15 s a turn, a round card between rounds. Same
/// flow as Android's `GapleScreen`.
@MainActor
final class GapleGameModel: ObservableObject {
    @Published private(set) var state: GapleState
    @Published private(set) var animating = false
    @Published private(set) var viewFrame: CGRect = .zero
    /// A tile that fits both open ends, waiting for the player to tap one.
    @Published private(set) var picking: GapleTile? {
        didSet { syncEndHighlight() }
    }
    @Published private(set) var turnSeconds = 0
    @Published private(set) var roundSeconds = 0

    let table: GapleTable
    let renderer = GapleRenderer()
    /// The loading screen still covers the board; computers wait for it.
    var loading = true
    private let sounds = Sounds.shared
    private let game = GapleGame.shared
    private let lifetime = GameLifetime()
    private var queue: [GapleUpdate] = []
    private var turnKey: String?
    private var roundKey: String?
    /// Set once this round end deals (button or countdown), so an ad never shows twice.
    private var dealing = false

    private static let botPause: Double = 0.65
    private static let settle: Double = 0.25

    /// Seconds for a turn and for the round card (`Krida.PLAY_TURN_MS`).
    var total: Int { Int(Krida.shared.PLAY_TURN_MS / 1000) }

    init(table: GapleTable) {
        self.table = table
        state = table.latest
        table.onUpdate = { [weak self] update in
            guard let self, self.lifetime.active else { return }
            self.queue.append(update)
            self.drain()
        }
        queue.append(GapleUpdate(state: table.latest, fresh: false))
        drain()
    }

    var me: Int { table.mySeat }
    var settled: Bool { !animating && queue.isEmpty }
    var myTurn: Bool {
        lifetime.active && settled && !state.roundOver && !state.finished && Int(state.turn) == me
    }
    var playable: [GapleTile] { myTurn ? game.playable(state: state, seat: Int32(me)) : [] }
    var hand: [GapleTile] { state.hands.indices.contains(me) ? state.hands[me] : [] }
    var roundCard: Bool { settled && state.roundOver && !state.finished }

    func layout(size: CGSize, free: CGRect) {
        viewFrame = renderer.layout(size: size, free: free)
    }

    // MARK: Updates

    private func drain() {
        guard lifetime.active, !animating else { return }
        guard !queue.isEmpty else {
            idle()
            syncTimers()
            return
        }
        let next = queue.removeFirst()
        animating = true
        // Clear pick (and zoom) before the tile flies in.
        picking = nil
        syncTimers()
        lifetime.launch("animation") { [self] in
            renderer.apply(next.state, viewer: me, animate: next.fresh)
            if next.fresh {
                sounds.play(.step)
                try await Task.sleep(for: .seconds(GapleRenderer.flightSeconds + Self.settle))
            }
            let before = state
            state = next.state
            // Seats that couldn't play after this move passed: "Pas!".
            if next.fresh && !next.state.lastPasses.isEmpty { sounds.say(.pass) }
            if next.state.finished && !before.finished {
                let mine = next.state.winner.map { next.state.team(seat: $0.int32Value) == next.state.team(seat: Int32(me)) } ?? false
                sounds.play(mine ? .win : .lose)
            } else if !next.state.roundOver && Int(next.state.turn) == me && Int(before.turn) != me {
                sounds.play(.turn)
            }
            animating = false
            drain()
        }
    }

    /// Lets a computer play once the table is still.
    private func idle() {
        lifetime.launch("bot") { [self] in
            while loading { try await Task.sleep(for: .milliseconds(100)) }
            try await Task.sleep(for: .seconds(Self.botPause))
            if settled { table.stepBot() }
        }
    }

    /// A dropped guest handed to the computer may have to act right away.
    func botsChanged() { if settled { idle() } }

    /// Once your timer runs out, the phone plays your turns after a short pause
    /// (`Krida.AUTO_PLAY_MS`) instead of a countdown, until you tap a tile or autopilot.
    @Published private(set) var auto = false

    /// Leave autopilot without playing: the next local turn gets a normal countdown again.
    func resumeManual() {
        guard auto else { return }
        auto = false
        guard myTurn else { return }
        turnKey = nil
        syncTimers()
    }

    /// The turn countdown (at 0 the phone plays what the easy computer would,
    /// `GapleGame.timeoutMove`) and the round card's (at 0 the host deals on by itself).
    private func syncTimers() {
        let turn = myTurn ? "\(state.turn)-\(state.line.count)-\(state.round)" : nil
        if turn != turnKey {
            turnKey = turn
            lifetime.cancel("turn")
            turnSeconds = 0
            if turn != nil {
                let wasAuto = auto
                turnSeconds = wasAuto ? 0 : total
                lifetime.launch("turn") { [self] in
                    if wasAuto {
                        try await Task.sleep(for: .seconds(Double(Krida.shared.AUTO_PLAY_MS) / 1000))
                    } else {
                        while turnSeconds > 0 {
                            try await Task.sleep(for: .seconds(1))
                            guard myTurn else { turnSeconds = 0; return }
                            turnSeconds -= 1
                        }
                        auto = true
                    }
                    guard myTurn, auto else { return }
                    picking = nil
                    if let move = game.timeoutMove(state: state, seat: Int32(me), random: KotlinRandom.companion), let tile = move.tile {
                        _ = table.play(tile, end: move.end?.intValue)
                    }
                }
            }
        }
        let round = roundCard ? "\(state.round)" : nil
        if round != roundKey {
            roundKey = round
            dealing = false
            lifetime.cancel("round")
            roundSeconds = 0
            if round != nil {
                roundSeconds = total
                lifetime.launch("round") { [self] in
                    while roundSeconds > 0 {
                        try await Task.sleep(for: .seconds(1))
                        roundSeconds -= 1
                    }
                    nextRound()
                }
            }
        }
    }

    // MARK: Playing

    func play(_ tile: GapleTile) {
        guard myTurn, playable.contains(tile) else { return }
        auto = false
        // Opening tile, or a tile that fits only one end: play straight away.
        if state.line.isEmpty {
            picking = nil
            if table.play(tile, end: nil) { sounds.play(.tap) }
            return
        }
        let ends = game.endsFor(state: state, tile: tile).map(\.intValue)
        if ends.count == 1 {
            picking = nil
            sounds.play(.tap)
            _ = table.play(tile, end: ends[0])
            return
        }
        // Fits both ends: pick / unpick, then tap the lit ring.
        picking = picking == tile ? nil : tile
        if picking != nil { sounds.play(.tap) }
    }

    func playEnd(_ end: Int) {
        guard myTurn, let tile = picking else { return }
        let ends = game.endsFor(state: state, tile: tile).map(\.intValue)
        guard ends.contains(end) else { return }
        picking = nil
        if table.play(tile, end: end) { sounds.play(.tap) }
    }

    /// Only the ends this tile can join light up; zoom onto them while picking.
    private func syncEndHighlight() {
        let ends = picking.map { game.endsFor(state: state, tile: $0).map(\.intValue) } ?? []
        renderer.highlightEnds(ends)
        renderer.watchPicking(!ends.isEmpty)
    }

    /// A tap near one of the two pulsing rings plays the picked tile there.
    func tapBoard(_ point: CGPoint, reach: CGFloat) {
        guard picking != nil else { return }
        let hit = (0..<2).compactMap { end in renderer.endOnScreen(end).map { (end, $0) } }
            .min { hypot($0.1.x - point.x, $0.1.y - point.y) < hypot($1.1.x - point.x, $1.1.y - point.y) }
        guard let hit, hypot(hit.1.x - point.x, hit.1.y - point.y) <= reach else { return }
        playEnd(hit.0)
    }

    /// Deals the next round; against computers a (frequency-capped) interstitial may run first.
    func nextRound() {
        guard lifetime.active, table.isHost, !dealing else { return }
        dealing = true
        let deal = { [weak self] in
            guard let self, self.lifetime.active else { return }
            _ = self.table.nextRound()
        }
        if table.isLocal { Ads.shared.afterGame(then: deal) } else { deal() }
    }

    func restart() {
        guard lifetime.active else { return }
        table.restart()
    }

    func stop() {
        guard lifetime.active else { return }
        lifetime.stop()
        table.onUpdate = nil
        queue.removeAll()
        picking = nil
        turnSeconds = 0
        roundSeconds = 0
        renderer.stop()
        table.leave()
        sounds.stopGameAudio()
    }
}

/// The game screen: the loading screen goes up first, the table is built under it, and the
/// loading screen fades out once its first frames are drawn (as perang kapal).
struct GapleView: View {
    let table: GapleTable
    let onExit: () -> Void
    @State private var mounted = false
    @State private var loading = true

    var body: some View {
        ZStack {
            if mounted { GapleBoardView(table: table, onExit: onExit, loading: loading, onDrawn: finishLoading) }
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

private let ivory = Color(hex: 0xFBF3E0)
private let pipColor = Color(hex: 0x2A211E)
private let pipRed = Color(hex: 0xBF2A2E)

/// Rows of the round table: per seat, or per team with partners.
private struct ScoreRow {
    let seats: [Int]
    let pips: Int
    let points: Int
    let total: Int
}

private func scoreRows(_ state: GapleState) -> [ScoreRow] {
    let n = Int(state.players)
    let groups = state.partners ? [[0, 2], [1, 3]] : (0..<n).map { [$0] }
    func at(_ list: [KotlinInt], _ i: Int) -> Int { list.indices.contains(i) ? list[i].intValue : 0 }
    return groups.map { g in
        ScoreRow(
            seats: g,
            pips: g.reduce(0) { $0 + at(state.roundPips, $1) },
            points: at(state.roundPoints, g[0]),
            total: at(state.scores, g[0])
        )
    }
}

private struct GapleBoardView: View {
    @ObservedObject private var table: GapleTable
    @StateObject private var model: GapleGameModel
    @ObservedObject private var ads = Ads.shared
    @State private var showRules = false
    @State private var handSpace: CGFloat = 0
    @State private var size: CGSize = .zero
    @State private var boxes: [String: CGRect] = [:]
    @State private var sideInsets = EdgeInsets()
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole
    let onExit: () -> Void
    let loading: Bool
    let onDrawn: () -> Void

    init(table: GapleTable, onExit: @escaping () -> Void, loading: Bool, onDrawn: @escaping () -> Void) {
        self.table = table
        _model = StateObject(wrappedValue: GapleGameModel(table: table))
        self.onExit = onExit
        self.loading = loading
        self.onDrawn = onDrawn
    }

    private static let space = "gaple"

    var body: some View {
        GeometryReader { outer in
            board
                .onAppear { sideInsets = outer.safeAreaInsets }
                .onChange(of: outer.safeAreaInsets) { _, new in sideInsets = new }
        }
    }

    private var state: GapleState { model.state }
    /// The draw rule is on and this table has face-down tiles (2–3 seats).
    private var drawing: Bool { state.options.draw && state.players < 4 }

    private var board: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                // Picking an end: tap near one of the two pulsing rings.
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(coordinateSpace: .named(Self.space)) { model.tapBoard($0, reach: 64 * s) }
                    .allowsHitTesting(model.picking != nil)
                    .accessibilityElement()
                    .accessibilityHidden(model.picking == nil)
                    .accessibilityLabel(Text("Tap an end"))
                    .accessibilityAction(named: Text("Left end")) { model.playEnd(Int(GapleGame.shared.LEFT)) }
                    .accessibilityAction(named: Text("Right end")) { model.playEnd(Int(GapleGame.shared.RIGHT)) }
                hud
                notices
                if model.roundCard { roundCard }
                if model.settled && state.finished { resultCard }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .background(alignment: .topLeading) {
                GapleBoardContainer(view: model.renderer.view)
                    .frame(width: model.viewFrame.width, height: model.viewFrame.height)
                    .offset(x: model.viewFrame.minX, y: model.viewFrame.minY)
                    .allowsHitTesting(false)
            }
            .coordinateSpace(name: Self.space)
            .onAppear {
                model.renderer.whenDrawn(onDrawn)
                size = geo.size
            }
            .onChange(of: geo.size) { _, new in size = new }
        }
        .ignoresSafeArea()
        .background(Theme.backdropGradient)
        .onDisappear { model.stop() }
        .onChange(of: boxes) { _, _ in relayout() }
        .onChange(of: size) { _, _ in relayout() }
        .onChange(of: loading) { _, now in model.loading = now }
        .onChange(of: table.players.filter(\.bot).count) { _, _ in model.botsChanged() }
        .sheet(isPresented: $showRules) { GapleRulesSheet(partners: state.partners, drawing: drawing) }
    }

    /// The table takes the space the HUD leaves: between the side columns, above the hand.
    private func relayout() {
        guard size.width > 0, let room = boxes["room"],
              let left = boxes["left"], left.width > 0,
              let right = boxes["right"], right.width > 0,
              let hand = boxes["hand"], let pills = boxes["pills"] else { return }
        let margin = 8 * s
        let x0 = left.maxX + margin
        let y0 = max(room.minY, pills.maxY + margin)
        let x1 = right.minX - margin
        let y1 = min(room.maxY, hand.minY - margin)
        guard x1 > x0, y1 > y0 else { return }
        model.layout(size: size, free: CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0))
    }

    private func exit() {
        model.stop()
        onExit()
    }

    private func hudBox(_ key: String) -> some ViewModifier {
        GapleFrameReader(space: Self.space, frame: Binding(get: { boxes[key] ?? .zero }, set: { boxes[key] = $0 }))
    }

    /// "You" or a player's name.
    private func seatName(_ seat: Int) -> String {
        seat == table.mySeat ? String(localized: "You", bundle: .app) : table.name(seat)
    }

    private func teamLetter(_ seat: Int) -> String { state.team(seat: Int32(seat)) == 0 ? "A" : "B" }

    private func seatOn(_ seat: Int) -> Bool {
        seat == Int(state.turn) && !state.roundOver && !state.finished
    }

    // MARK: HUD

    /// Left-column tiles for every seat count (2, 3 or 4), two a row like ular tangga / ludo.
    private var hud: some View {
        let count = Int(state.players)
        let rows = stride(from: 0, to: count, by: 2).map { Array($0..<min($0 + 2, count)) }
        return HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 8 * s) {
                CircleIconButton(systemImage: "chevron.left", label: "Back to menu", action: exit)
                    .clearPunchHole(hole, .topLeading, already: 24 * s)
                Text("Gaple").font(Theme.font(18 * s, .black))
                Text("Round \(Int(state.round)) · to \(Int(GapleGame.shared.TARGET))")
                    .font(Theme.font(12 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .lineLimit(1)
                if drawing {
                    Text("\(Int(GapleGame.shared.asideCount(state: state))) face down")
                        .font(Theme.font(12 * s, .bold))
                        .foregroundStyle(Theme.muted)
                        .lineLimit(1)
                }
                VStack(spacing: 8 * s) {
                    ForEach(rows, id: \.self) { pair in
                        HStack(spacing: 8 * s) {
                            ForEach(pair, id: \.self) { seat in
                                playerTile(seat, on: seatOn(seat))
                            }
                            if pair.count == 1 { Spacer(minLength: 0) }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(width: 150 * s)
            .frame(maxHeight: .infinity)
            .modifier(hudBox("left"))
            VStack(spacing: 0) {
                pills
                    .frame(minHeight: 32 * s)
                    .modifier(hudBox("pills"))
                Spacer(minLength: 0)
                handRow.modifier(hudBox("hand"))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { handSpace = $0 }
            VStack(alignment: .trailing, spacing: 0) {
                HStack(spacing: 8 * s) {
                    SoundToggles()
                    CircleIconButton(systemImage: "questionmark", label: "How to play") { showRules = true }
                }
                if table.canEmote { Spacer().frame(height: 8 * s) }
                EmoteHud(
                    canEmote: table.canEmote,
                    emotes: table.emotes,
                    playerName: seatName,
                    onSend: table.sendEmote,
                    s: s
                )
                .frame(maxWidth: .infinity, alignment: .trailing)
                Spacer(minLength: 0)
                HStack(spacing: 8 * s) {
                    if model.auto || model.myTurn {
                        Text("Your turn").font(Theme.font(13 * s, .bold))
                    }
                    TurnTimer(
                        seconds: model.myTurn ? model.turnSeconds : 0,
                        total: model.total,
                        auto: model.auto,
                        size: 40,
                        onResume: model.resumeManual
                    )
                }
                .padding(.top, 10 * s)
                .clearPunchHole(hole, .bottomTrailing, already: 24 * s)
            }
            .frame(width: 160 * s)
            .frame(maxHeight: .infinity)
            .modifier(hudBox("right"))
        }
        .modifier(hudBox("room"))
        .padding(.horizontal, 24 * s)
        .padding(.leading, max(sideInsets.leading - 24 * s, 0))
        .padding(.trailing, max(sideInsets.trailing - 24 * s, 0))
        .padding(.vertical, 14 * s)
        .foregroundStyle(Theme.ink)
    }

    /// Whose turn it is and the last thing that happened.
    private var pills: some View {
        let turn: String? = if state.roundOver || state.finished {
            nil
        } else if model.picking != nil {
            String(localized: "Tap an end", bundle: .app)
        } else if model.myTurn {
            // "Your turn" itself sits by the countdown ring.
            state.mustPlay.map { String(localized: "Open with \(Int($0.a))|\(Int($0.b))", bundle: .app) }
        } else {
            String(localized: "\(seatName(Int(state.turn)))'s turn", bundle: .app)
        }
        let played: String? = state.lastSeat.flatMap { seat in
            state.lastTile.map { t in
                Int(seat.int32Value) == table.mySeat
                    ? String(localized: "You play \(Int(t.left))|\(Int(t.right))", bundle: .app)
                    : String(localized: "\(table.name(Int(seat.int32Value))) plays \(Int(t.left))|\(Int(t.right))", bundle: .app)
            }
        }
        let draws = state.lastDraws.enumerated().filter { $0.element.intValue > 0 }
            .map { String(localized: "\(seatName($0.offset)) drew \($0.element.intValue)", bundle: .app) }
        let passes = draws + state.lastPasses.map { String(localized: "\(seatName($0.intValue)): Pass", bundle: .app) }
        let events = Array(([played].compactMap { $0 } + passes).suffix(2))
        return HStack(spacing: 8 * s) {
            ForEach(events.indices, id: \.self) { pill(events[$0], color: Theme.muted) }
            if let turn { pill(turn, color: Theme.ink) }
        }
    }

    private func pill(_ text: String, color: Color) -> some View {
        Text(text)
            .font(Theme.font(12 * s, .bold))
            .foregroundStyle(color)
            .lineLimit(1)
            .padding(.horizontal, 12 * s)
            .padding(.vertical, 6 * s)
            .background(Theme.surface.opacity(0.9), in: Capsule())
            .overlay(Capsule().stroke(Theme.line, lineWidth: 1))
    }

    /// Seat tile for every seat count, two a row like ludo / ular tangga: score inside the colour circle.
    private func playerTile(_ seat: Int, on: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: 12 * s)
        let count = state.handCounts.indices.contains(seat) ? state.handCounts[seat].intValue : 0
        let score = state.scores.indices.contains(seat) ? state.scores[seat].intValue : 0
        let tiles = String(localized: "\(count) tiles", bundle: .app)
        let team = state.partners ? " · \(teamLetter(seat))" : ""
        let color = pawnColor(seat)
        // Dark digits on a light pawn colour (yellow), white on the rest.
        let rgb = UlarTanggaBoard.shared.pieceColor(color: Int32(seat))
        let light = rgb.get(index: 0) * 0.3 + rgb.get(index: 1) * 0.59 + rgb.get(index: 2) * 0.11 > 0.6
        return VStack(spacing: 2 * s) {
            ZStack(alignment: .bottomTrailing) {
                Text(verbatim: "\(score)")
                    .font(Theme.font(14 * s, .black))
                    .foregroundStyle(light ? Theme.inkOnLight : .white)
                    .lineLimit(1)
                    .frame(width: 30 * s, height: 30 * s)
                    .background(color, in: Circle())
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
                    AvatarBadge(avatar: avatar, size: 18)
                        .overlay(Circle().stroke(.white, lineWidth: 1.5 * s))
                }
            }
            Text(seatName(seat)).font(Theme.font(11 * s, .bold)).lineLimit(1).minimumScaleFactor(0.7)
            Text(verbatim: tiles + team)
                .font(Theme.font(10 * s, .semibold))
                .foregroundStyle(Theme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(6 * s)
        .frame(maxWidth: .infinity)
        .background(on ? Theme.chip : Theme.surface, in: shape)
        .overlay(shape.stroke(on ? color : Theme.line, lineWidth: on ? 2 : 1.5))
        .animation(.easeOut(duration: 0.2), value: on)
        .accessibilityElement(children: .combine)
    }

    /// Your hand, upright. Tiles that fit are raised with a gold ring.
    private var handRow: some View {
        let playable = model.playable
        // Seven tiles at full size; a hand that grew by drawing shrinks to fit the width.
        let count = CGFloat(model.hand.count)
        let gap = (count > 9 ? 3 : 6) * s
        let width = count > 0 && handSpace > 0 ? min(34 * s, (handSpace - gap * (count - 1)) / count) : 34 * s
        return HStack(alignment: .bottom, spacing: gap) {
            ForEach(model.hand.indices, id: \.self) { i in
                let tile = model.hand[i]
                let fits = playable.contains(tile)
                let ring: Color? = tile == model.picking ? Theme.accent : fits ? Theme.gold : nil
                GapleTileFace(tile: tile, width: width, ring: ring, dim: !playable.isEmpty && !fits)
                    .offset(y: fits ? -10 * s : 0)
                    .animation(.easeOut(duration: 0.2), value: fits)
                    .onTapGesture { if fits { model.play(tile) } }
                    .accessibilityElement()
                    .accessibilityLabel(Text("Tile \(Int(tile.a))|\(Int(tile.b))"))
                    .accessibilityAddTraits(fits ? .isButton : [])
                    .accessibilityAction { if fits { model.play(tile) } }
            }
        }
        .padding(.top, 10 * s)
    }

    @ViewBuilder
    private var notices: some View {
        Group {
            if let away = table.waiting {
                CountdownNotice(text: away.text, seconds: away.seconds, total: Int(Krida.shared.HANDOFF_WAIT_MS / 1000), fontSize: 14)
            } else if let notice = table.notice {
                Text(notice)
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.error)
                    .padding(.horizontal, 14 * s)
                    .padding(.vertical, 8 * s)
                    .background(Theme.surface.opacity(0.92), in: RoundedRectangle(cornerRadius: 12 * s))
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 16 * s)
        .allowsHitTesting(false)
    }

    // MARK: Cards

    private func cardFrame<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ZStack {
            Color.black.opacity(0.28).ignoresSafeArea()
            VStack(spacing: 10 * s) { content() }
                .padding(20 * s)
                .frame(maxWidth: 520 * s)
                .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
                .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
                .padding(.horizontal, 24 * s)
                .foregroundStyle(Theme.ink)
                .accessibilityElement(children: .contain)
                .accessibilityAddTraits(.isModal)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .transition(.opacity)
    }

    private func scoreTable(_ rows: [ScoreRow]) -> some View {
        func cell(_ text: String, bold: Bool = false, color: Color = Theme.ink) -> some View {
            Text(verbatim: text)
                .font(Theme.font(13 * s, bold ? .black : .bold))
                .foregroundStyle(color)
                .frame(width: 52 * s, alignment: .trailing)
        }
        return VStack(spacing: 2 * s) {
            HStack(spacing: 6 * s) {
                Spacer(minLength: 0)
                cell(String(localized: "Pips", bundle: .app), color: Theme.muted)
                cell(String(localized: "Points", bundle: .app), color: Theme.muted)
                cell(String(localized: "Total", bundle: .app), color: Theme.muted)
            }
            .padding(.horizontal, 8 * s)
            ForEach(rows.indices, id: \.self) { i in
                let row = rows[i]
                let won = state.roundWinner.map { row.seats.contains($0.intValue) } ?? false
                let name = state.partners
                    ? String(localized: "Team \(teamLetter(row.seats[0]))", bundle: .app) + " · " + row.seats.map(seatName).joined(separator: " + ")
                    : seatName(row.seats[0])
                HStack(spacing: 6 * s) {
                    ForEach(row.seats, id: \.self) { Circle().fill(pawnColor($0)).frame(width: 12 * s, height: 12 * s) }
                    Text(verbatim: name).font(Theme.font(13 * s, .bold)).lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    cell("\(row.pips)")
                    cell(row.points > 0 ? "+\(row.points)" : "0")
                    cell("\(row.total)", bold: true)
                }
                .padding(.horizontal, 8 * s)
                .padding(.vertical, 5 * s)
                .background(won ? Theme.gold.opacity(0.28) : .clear, in: RoundedRectangle(cornerRadius: 10 * s))
                .accessibilityElement(children: .combine)
            }
        }
    }

    private var roundCard: some View {
        let winner = state.roundWinner?.intValue
        let title: String = if state.roundEnd == "gaple" {
            String(localized: "Gaple! Nobody can play", bundle: .app)
        } else if let winner, winner == table.mySeat {
            String(localized: "Domino! You went out", bundle: .app)
        } else if let winner {
            String(localized: "Domino! \(table.name(winner)) went out", bundle: .app)
        } else {
            ""
        }
        return cardFrame {
            Text(title).font(Theme.font(20 * s, .black)).multilineTextAlignment(.center)
            scoreTable(scoreRows(state))
            HStack(spacing: 12 * s) {
                CountdownRing(seconds: model.roundSeconds, total: model.total, size: 40)
                if table.isHost {
                    Button(action: model.nextRound) { Text("Next round").frame(maxWidth: .infinity) }
                        .buttonStyle(PrimaryButtonStyle(height: 44))
                        .frame(width: 200 * s)
                } else {
                    Text("Waiting for the host").font(Theme.font(14 * s, .bold)).foregroundStyle(Theme.muted)
                }
            }
        }
    }

    private var resultCard: some View {
        let me = table.mySeat
        let winner = state.winner?.intValue
        let title: String = if let winner, state.partners, state.team(seat: Int32(winner)) == state.team(seat: Int32(me)) {
            String(localized: "Your team wins!", bundle: .app)
        } else if let winner, state.partners {
            String(localized: "Team \(teamLetter(winner)) wins", bundle: .app)
        } else if let winner, winner == me {
            String(localized: "You win!", bundle: .app)
        } else if let winner {
            String(localized: "\(table.name(winner)) wins", bundle: .app)
        } else {
            String(localized: "Gaple", bundle: .app)
        }
        return cardFrame {
            Text(title).font(Theme.font(22 * s, .black))
            Text("First to 100 points").font(Theme.font(13 * s, .bold)).foregroundStyle(Theme.muted)
            scoreTable(scoreRows(state).sorted { $0.total > $1.total })
            HStack(spacing: 10 * s) {
                Button { ads.afterGame(then: exit) } label: { Text("Menu").frame(maxWidth: .infinity) }
                    .buttonStyle(SecondaryButtonStyle(height: 48, tint: Theme.muted))
                if table.isHost {
                    Button { ads.afterGame(then: model.restart) } label: { Text("Play again").frame(maxWidth: .infinity) }
                        .buttonStyle(PrimaryButtonStyle(height: 48))
                }
            }
        }
    }
}

/// A domino face, upright: `tile.a` on top.
struct GapleTileFace: View {
    let tile: GapleTile
    let width: CGFloat
    let ring: Color?
    let dim: Bool

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 6)
        Canvas { context, size in
            let half = size.height / 2
            context.fill(
                Path(CGRect(x: size.width * 0.15, y: half - 0.75, width: size.width * 0.7, height: 1.5)),
                with: .color(pipColor.opacity(0.6)))
            Self.pips(Int(tile.a), CGRect(x: 0, y: 0, width: size.width, height: half), dim, &context)
            Self.pips(Int(tile.b), CGRect(x: 0, y: half, width: size.width, height: half), dim, &context)
        }
        .padding(3)
        .frame(width: width, height: width * 2)
        .background(ivory.opacity(dim ? 0.7 : 1), in: shape)
        .overlay(shape.stroke(ring ?? Color(hex: 0xCBB89A), lineWidth: ring != nil ? 2.5 : 1))
    }

    private static func pips(_ value: Int, _ area: CGRect, _ dim: Bool, _ context: inout GraphicsContext) {
        let d: CGFloat = 0.28
        let spots: [(CGFloat, CGFloat)] = switch value {
        case 1: [(0, 0)]
        case 2: [(-d, -d), (d, d)]
        case 3: [(-d, -d), (0, 0), (d, d)]
        case 4: [(-d, -d), (d, -d), (-d, d), (d, d)]
        case 5: [(-d, -d), (d, -d), (0, 0), (-d, d), (d, d)]
        case 6: [(-d, -d), (-d, 0), (-d, d), (d, -d), (d, 0), (d, d)]
        default: []
        }
        let r = min(area.width, area.height) * 0.09
        let color = (value == 1 ? pipRed : pipColor).opacity(dim ? 0.55 : 1)
        for (x, y) in spots {
            let c = CGPoint(x: area.minX + area.width * (0.5 + x), y: area.minY + area.height * (0.5 + y))
            context.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)), with: .color(color))
        }
    }
}

private struct GapleFrameReader: ViewModifier {
    let space: String
    @Binding var frame: CGRect

    func body(content: Content) -> some View {
        content.onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: { frame = $0 }
    }
}

/// "How to play". Same lines as Android's rules sheet.
struct GapleRulesSheet: View {
    let partners: Bool
    let drawing: Bool
    @Environment(\.dismiss) private var dismiss

    private var lines: [LocalizedStringKey] {
        var list: [LocalizedStringKey] = [
            "A double-six set of 28 tiles. Everyone gets 7; with 2 or 3 players the rest stay face down.",
            "The first round opens with balak enam [6|6], or the highest double if nobody holds it. Later rounds: the last winner leads with any tile.",
            "A tile must match an open end. Tap one that fits only one end to play it; if it fits both, the matching ends light up — tap the one you want.",
            drawing
                ? "No tile fits? You draw from the face-down tiles until one fits, and pass only when none are left."
                : "No tile fits? You pass. There is no drawing.",
            "The round ends when someone empties their hand (domino) or nobody can play (gaple): then the lowest pips in hand wins.",
            "The round winner scores the pips left in the other hands. First to 100 wins.",
        ]
        if partners { list.append("Partners: seats across play as a team and share one score.") }
        list.append("15 seconds a turn: when time runs out the phone plays your heaviest fitting tile.")
        return list
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(lines.indices, id: \.self) { i in
                        HStack(alignment: .firstTextBaseline, spacing: 10) {
                            Circle().fill(Theme.accent).frame(width: 7, height: 7)
                            Text(lines[i]).font(Theme.font(16, .semibold)).foregroundStyle(Theme.ink)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
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
}

private struct GapleBoardContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
