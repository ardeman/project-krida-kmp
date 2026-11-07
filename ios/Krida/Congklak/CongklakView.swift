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

    let table: CongklakTable
    let board = BoardScene()
    private let seeds: CongklakSeeds
    private var queue: [CongklakUpdate] = []

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
        table.onUpdate = { [weak self] update in
            guard let self else { return }
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

    var canPlay: Bool {
        !animating && !waiting && queue.isEmpty && !finished && (table.mySeat == nil || table.mySeat == state.turn)
    }

    func layout(size: CGSize) {
        board.layout(size: size)
        pitPoints = board.pitPoints
    }

    /// A notice arrived (refusal, disconnect): stop waiting for the host's answer.
    func noticeChanged() { waiting = false }

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

    private func play(hole: Int) {
        let seat = state.turn
        guard canPlay, game.validate(state: state, seat: seat, move: CongklakMove(hole: Int32(hole))) == nil else { return }
        // Pass-and-play and the host answer synchronously; a guest waits for the host's snapshot.
        if table.play(seat: seat, hole: Int32(hole)), table.isRemote { waiting = true }
    }

    /// Once the board is still, give a computer player its turn after a short pause.
    private func idle() {
        Task {
            try? await Task.sleep(nanoseconds: Self.botPause)
            if !animating && queue.isEmpty { table.stepBot() }
        }
    }

    private func drain() {
        guard !animating else { return }
        guard !queue.isEmpty else { return idle() }
        let update = queue.removeFirst()
        animating = true
        Task {
            var sowed = false
            if let seat = update.seat, let move = update.move, game.validate(state: state, seat: seat, move: move) == nil {
                let sowing = game.play(state: state, seat: seat, move: move)
                let steps = CongklakSeeds.companion.steps(before: state.pits, startPit: game.pit(seat: seat, hole: move.hole), path: sowing.path)
                for step in steps {
                    seeds.apply(step: step)
                    push()
                    try? await Task.sleep(nanoseconds: step.pickUp ? Self.pickUpDelay : Self.sowDelay)
                }
                sowed = true
            }
            // Captures, the end-of-game sweep, a restart: move whatever differs to match.
            if seeds.counts() != update.state.pits {
                if sowed { try? await Task.sleep(nanoseconds: Self.settleDelay) }
                seeds.settle(pits: update.state.pits)
                push()
            }
            state = update.state
            animating = false
            drain()
        }
    }

    private func push() {
        board.setSeedTargets(seeds.targets())
        counts = seeds.counts().map { $0.intValue }
    }
}

struct CongklakView: View {
    @ObservedObject private var table: CongklakTable
    @StateObject private var model: CongklakGameModel
    let onExit: () -> Void

    init(table: CongklakTable, onExit: @escaping () -> Void) {
        self.table = table
        _model = StateObject(wrappedValue: CongklakGameModel(table: table))
        self.onExit = onExit
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
                    badge(pit: pit).position(badgePosition(pit: pit, at: point))
                }

                hud
            }
            .onAppear { DispatchQueue.main.async { model.layout(size: geo.size) } }
            .onChange(of: geo.size) { _, size in DispatchQueue.main.async { model.layout(size: size) } }
        }
        .ignoresSafeArea()
        .background(Color(red: 0.96, green: 0.93, blue: 0.86))
        .onChange(of: table.notice) { _, notice in if notice != nil { model.noticeChanged() } }
    }

    private func exit() {
        table.leave()
        onExit()
    }

    private var hud: some View {
        VStack {
            HStack(spacing: 12) {
                Button("Kembali", action: exit)
                Text("Congklak").font(.title2.bold())
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            Spacer()
            VStack(spacing: 8) {
                Text(statusLine).font(.title2)
                Text("\(name(0)) \(score(0)) · \(name(1)) \(score(1))")
                if let notice = table.notice {
                    Text(notice).foregroundStyle(.red)
                }
                if model.finished && !model.animating {
                    if table.canRestart {
                        Button("Main lagi", action: table.restart).buttonStyle(.borderedProminent)
                    } else {
                        Text("Menunggu host memulai lagi…").font(.subheadline)
                    }
                }
            }
            .padding(.bottom, 24)
        }
        .foregroundStyle(.black)
    }

    private func name(_ seat: Int) -> String {
        table.players.first { $0.seat == Int32(seat) }?.name ?? "\(seat + 1)"
    }

    private var statusLine: String {
        let mine = table.mySeat.map(Int.init)
        guard let winner = model.game.winner(state: model.state)?.intValue else {
            if model.turn == mine { return String(localized: "Giliranmu") }
            return String(localized: "Giliran \(name(model.turn))")
        }
        if winner < 0 { return String(localized: "Seri!") }
        if winner == mine { return String(localized: "Kamu menang!") }
        return String(localized: "\(name(winner)) menang!")
    }

    private func score(_ seat: Int) -> Int { Int(model.game.score(state: model.state, seat: Int32(seat))) }

    private func badge(pit: Int) -> some View {
        let store = model.game.isStore(pit: Int32(pit))
        let active = !model.finished && !store && Int(model.game.ownerOf(pit: Int32(pit))) == model.turn
        let color: Color = active ? Color(red: 0.18, green: 0.49, blue: 0.20) : store ? Color(red: 0.36, green: 0.25, blue: 0.22) : .black.opacity(0.6)
        return Text("\(model.counts[pit])")
            .font(store ? .headline : .subheadline.bold())
            .foregroundStyle(.white)
            .padding(.horizontal, 9)
            .padding(.vertical, 2)
            .background(color, in: Capsule())
            .allowsHitTesting(false)
    }

    private func badgePosition(pit: Int, at p: CGPoint) -> CGPoint {
        let store = model.game.isStore(pit: Int32(pit))
        let near = model.game.ownerOf(pit: Int32(pit)) == model.viewSeat
        if store { return CGPoint(x: p.x + (near ? -48 : 48), y: p.y) } // your store is on your left
        return CGPoint(x: p.x, y: p.y + (near ? 34 : -34))
    }
}

/// Hosts the RealityKit view owned by the model.
private struct BoardContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
