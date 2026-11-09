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

    let table: UlarTanggaTable
    let scene = UlarTanggaScene()
    private let sounds = Sounds.shared
    private let game = UlarTanggaGame.shared
    private let board = UlarTanggaBoard.shared
    private var queue: [UlarTanggaUpdate] = []
    /// How the local player threw the die, for the roll the table hasn't sent back yet.
    private var pendingToss: UlarTanggaScene.Toss?
    /// The loading screen still covers the board; computers wait for it.
    var loading = true

    private static let hop: Double = 0.23
    private static let jumpUnitsPerSecond: Float = 9
    private static let settle: Double = 0.18
    private static let botPause: Double = 0.7

    init(table: UlarTanggaTable) {
        self.table = table
        state = table.latest
        scene.onDieBounce = { Sounds.shared.play(.die) }
        table.onUpdate = { [weak self] update in
            self?.queue.append(update)
            self?.drain()
        }
        // The first state runs through the queue too, so the pawns are placed.
        queue.append(UlarTanggaUpdate(state: table.latest, fresh: false))
        drain()
    }

    var players: Int { state.positions.count }
    var turn: Int { Int(state.turn) }
    var queueEmpty: Bool { queue.isEmpty }

    var canRoll: Bool { !animating && queue.isEmpty && !state.finished && table.isLocal(turn) }
    var ready: Bool { canRoll && !offHold && !loading }

    func layout(size: CGSize, room: CGRect, dieSpot: CGRect) {
        viewFrame = scene.layout(size: size, room: room)
        scene.dieSpot = (CGPoint(x: dieSpot.midX, y: dieSpot.midY), dieSpot.width * 0.45)
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

    /// Throws the die for the local player whose turn it is.
    func roll(_ toss: UlarTanggaScene.Toss) {
        guard canRoll, pendingToss == nil else { return }
        pendingToss = toss
        if !table.roll(seat: turn) { pendingToss = nil }
        updateReady()
    }

    func restart() { table.restart() }

    /// Once the board is still, give a computer player its turn after a short pause.
    private func idle() {
        updateReady()
        Task {
            while loading { try? await Task.sleep(for: .milliseconds(100)) }
            try? await Task.sleep(for: .seconds(Self.botPause))
            if !animating && queue.isEmpty { table.stepBot() }
        }
    }

    private func toss(for seat: Int) -> UlarTanggaScene.Toss {
        defer { pendingToss = nil }
        return table.isLocal(seat) ? pendingToss ?? .short : .short
    }

    private func drain() {
        guard !animating else { return }
        guard !queue.isEmpty else { return idle() }
        let update = queue.removeFirst()
        animating = true
        updateReady()
        Task {
            let next = update.state
            let offThrow = update.fresh ? game.diceOffThrowBetween(before: state, after: next) : nil
            if let offThrow, let thrower = offThrow.first?.intValue, let value = offThrow.second?.intValue {
                // A dice-off throw: the die lands on the throw, which shows on the seat's tile.
                let seconds = scene.throwDie(value, toss: toss(for: thrower))
                try? await Task.sleep(for: .seconds(Double(seconds) + 0.15))
                state = next
                try? await Task.sleep(for: .seconds(0.45))
                scene.recallDie()
                if !next.opening {
                    // The order is set: show every throw a moment, then the tiles line up in order.
                    offHold = true
                    try? await Task.sleep(for: .seconds(1.8))
                    offHold = false
                    announceTurn(Int(next.turn))
                }
            } else if update.fresh, let seat = next.lastSeat?.intValue, let roll = next.lastRoll?.intValue {
                let seconds = scene.throwDie(roll, toss: toss(for: seat))
                try? await Task.sleep(for: .seconds(Double(seconds) + 0.2))
                let play = game.play(state: state, seat: Int32(seat), roll: Int32(roll))
                for square in play.steps {
                    let to = UlarTanggaScene.vec(board.pieceSpot(square: square.int32Value, seat: Int32(seat), shared: false))
                    scene.move(seat, [to], seconds: Float(Self.hop * 0.9), hop: 0.7)
                    sounds.play(.step)
                    try? await Task.sleep(for: .seconds(Self.hop))
                }
                let landed = play.steps.last?.int32Value
                if let landed, play.jumpTo != nil {
                    try? await Task.sleep(for: .seconds(0.16))
                    let ladder = game.LADDERS[KotlinInt(int: landed)] != nil
                    let path = (ladder ? board.ladderPath(foot: landed) : board.snakePath(head: landed)).map { UlarTanggaScene.vec($0) }
                    let seconds = Self.length(path) / Self.jumpUnitsPerSecond + 0.2
                    sounds.play(ladder ? .store : .capture)
                    scene.move(seat, Array(path.dropFirst()), seconds: seconds)
                    try? await Task.sleep(for: .seconds(Double(seconds) + 0.08))
                }
                for s in 0..<next.positions.count { scene.move(s, [spot(next, s)], seconds: Float(Self.settle)) }
                try? await Task.sleep(for: .seconds(Self.settle))
                scene.recallDie()
                state = next
                let last = game.LAST
                announce(play.state, reached: landed == last || play.jumpTo?.int32Value == last)
            } else {
                // A new game (or the screen's first state): pawns to the start; the dice-off
                // waits for the players' throws.
                state = next
                placeAll(next)
                scene.recallDie()
            }
            animating = false
            drain()
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
            try? await Task.sleep(for: .milliseconds(50))
            mounted = true
            try? await Task.sleep(for: .seconds(4))
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
    @State private var leftColumn: CGRect = .zero
    @State private var rightColumn: CGRect = .zero
    @State private var dieBox: CGRect = .zero
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

    var body: some View {
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
        .onChange(of: layoutKey) { _, _ in relayout() }
        .onChange(of: loading) { _, now in
            model.loading = now
            model.updateReady()
        }
        .onChange(of: model.animating) { _, _ in model.updateReady() }
        .onChange(of: model.offHold) { _, _ in model.updateReady() }
        .sheet(isPresented: $showRules) { RulesSheet(options: model.state.options) }
    }

    /// Everything the board's fit depends on.
    private var layoutKey: [CGFloat] {
        [size.width, size.height, leftColumn.maxX, rightColumn.minX, leftColumn.minY, leftColumn.maxY, dieBox.midX, dieBox.midY, dieBox.width]
    }

    /// The board takes the room between the two HUD columns, the die sits on its spot.
    private func relayout() {
        guard size.width > 0, leftColumn.width > 0, rightColumn.width > 0, dieBox.width > 0 else { return }
        let margin = 12 * s
        let room = CGRect(x: leftColumn.maxX + margin, y: leftColumn.minY, width: rightColumn.minX - leftColumn.maxX - 2 * margin, height: leftColumn.height)
        model.layout(size: size, room: room, dieSpot: dieBox)
    }

    private func exit() {
        table.leave()
        onExit()
    }

    private func frame(_ binding: Binding<CGRect>) -> some ViewModifier {
        FrameReader(space: Self.space, frame: binding)
    }

    // MARK: Seats

    private func displayName(_ seat: Int) -> String {
        table.mySeat == seat ? String(localized: "Kamu", bundle: .app) : table.name(seat)
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
        if model.offHold { return String(localized: "Urutan siap · \(displayName(seat)) duluan", bundle: .app) }
        if st.finished || model.animating { return "" }
        let bot = table.botSeats.contains(seat)
        if st.opening {
            if bot { return String(localized: "berpikir…", bundle: .app) }
            if st.openingRolls.count > 1 { return String(localized: "Seri · \(displayName(seat)) lempar lagi", bundle: .app) }
            if table.mySeat == seat { return String(localized: "Lempar dadu untuk urutan main", bundle: .app) }
            return String(localized: "\(table.name(seat)), lempar dadu untuk urutan main", bundle: .app)
        }
        if st.lastSeat?.intValue == seat && st.lastRoll?.intValue == 6 && st.options.sixRollsAgain {
            return String(localized: "Dapat 6 · lempar lagi", bundle: .app)
        }
        if bot { return String(localized: "berpikir…", bundle: .app) }
        if table.mySeat == seat { return String(localized: "Giliranmu · lempar dadu", bundle: .app) }
        return String(localized: "Giliran \(table.name(seat))", bundle: .app)
    }

    // MARK: HUD

    private var hud: some View {
        let throwing = throwing
        return HStack(alignment: .top, spacing: 0) {
            // Left: back, then the players, two tiles a row.
            VStack(alignment: .leading, spacing: 10 * s) {
                CircleIconButton(systemImage: "chevron.left", label: "Kembali ke menu", action: exit)
                Spacer().frame(height: 4 * s)
                let rows = stride(from: 0, to: tileOrder.count, by: 2).map { Array(tileOrder[$0..<min($0 + 2, tileOrder.count)]) }
                VStack(spacing: 8 * s) {
                    ForEach(rows, id: \.self) { pair in
                        HStack(spacing: 8 * s) {
                            ForEach(pair, id: \.self) { seat in
                                tile(seat, on: seat == highlighted, roll: offRoll(seat), out: model.state.opening && !throwing.contains(seat))
                            }
                            if pair.count == 1 { Color.clear.aspectRatio(1, contentMode: .fit) }
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(width: 136 * s)
            .frame(maxHeight: .infinity)
            .modifier(frame($leftColumn))
            Spacer(minLength: 0)
            // Right: sound and rules, then the die and whose turn it is.
            VStack(spacing: 0) {
                HStack(spacing: 8 * s) {
                    SoundToggles()
                    CircleIconButton(systemImage: "questionmark", label: "Cara main") { showRules = true }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
                Spacer(minLength: 0)
                // The die itself is 3D, drawn by the scene over this spot; tap it to throw.
                Color.clear
                    .frame(width: 104 * s, height: 104 * s)
                    .contentShape(Rectangle())
                    .onTapGesture { model.roll(.short) }
                    .modifier(frame($dieBox))
                    .accessibilityElement()
                    .accessibilityLabel(Text("Lempar"))
                    .accessibilityAddTraits(.isButton)
                Text(status)
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
                    .multilineTextAlignment(.center)
                    // Two lines kept even for a short status, so the die and the board don't move.
                    .lineLimit(2, reservesSpace: true)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 10 * s)
            }
            .frame(width: 148 * s)
            .frame(maxHeight: .infinity)
            .modifier(frame($rightColumn))
        }
        .padding(.horizontal, 24 * s)
        .padding(.leading, max((hole?.leading ?? 0) - 24 * s, 0))
        .padding(.trailing, max((hole?.trailing ?? 0) - 24 * s, 0))
        .padding(.vertical, 14 * s)
        .foregroundStyle(Theme.ink)
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
        let title = table.mySeat == winner ? String(localized: "Kamu menang!", bundle: .app) : String(localized: "\(table.name(winner)) menang!", bundle: .app)
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
                            Circle().fill(pawnColor(Int(st.color(seat: Int32(seat))))).frame(width: 14 * s, height: 14 * s)
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
                    Button { ads.afterGame(then: exit) } label: { Text("Ke menu").frame(maxWidth: .infinity) }
                        .buttonStyle(SecondaryButtonStyle(height: 48, tint: Theme.ink))
                    Button { ads.afterGame(then: model.restart) } label: { Text("Main lagi").frame(maxWidth: .infinity) }
                        .buttonStyle(PrimaryButtonStyle(height: 48))
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

/// "Cara main" with this game's options.
private struct RulesSheet: View {
    let options: UlarTanggaOptions
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    rule("Semua melempar dadu untuk menentukan urutan main, angka tertinggi duluan; yang seri melempar lagi di antara mereka. Lalu giliran berjalan sesuai urutan itu.")
                    if options.sixToEnter {
                        rule("Perlu dadu 6 untuk masuk papan; 6 itu membawamu ke kotak 1.")
                    } else {
                        rule("Semua mulai di luar papan; dadu 4 membawamu ke kotak 4.")
                    }
                    rule("Berhenti di kaki tangga: naik ke ujung atasnya.")
                    rule("Berhenti di kepala ular: turun ke ekornya.")
                    if options.bounceBack {
                        rule("Harus pas di 100; sisa langkah yang lewat berjalan mundur.")
                    } else {
                        rule("Harus pas di 100; lemparan yang lewat membuat bidak diam di tempat.")
                    }
                    if options.sixRollsAgain { rule("Dadu 6 boleh melempar lagi.") }
                    if options.firstFinishEnds {
                        rule("Yang pertama sampai 100 menang dan permainan selesai.")
                    } else {
                        rule("Yang sampai 100 keluar dari papan berurutan; permainan lanjut sampai tersisa satu pemain.")
                    }
                }
                .padding(24)
            }
            .background(Theme.surface)
            .navigationTitle(Text("Cara main"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Tutup") { dismiss() } }
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
