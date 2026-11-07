import RealityKit
import Shared
import SwiftUI

/// Pass-and-play congklak on one device: seat 0 on the near side, seat 1 across.
/// Rules, board geometry and seed bookkeeping all come from shared/.
@MainActor
final class CongklakGameModel: ObservableObject {
    @Published private(set) var state: CongklakState
    @Published private(set) var counts: [Int]
    @Published private(set) var pitPoints: [CGPoint] = []
    @Published private(set) var animating = false

    let names: [String]
    let board = BoardScene()
    private var session: HostSession<CongklakState, CongklakMove>
    private let seeds: CongklakSeeds

    private static let pickUpDelay: UInt64 = 260_000_000
    private static let sowDelay: UInt64 = 170_000_000
    private static let settleDelay: UInt64 = 320_000_000

    init(names: [String]) {
        self.names = names
        session = Self.newSession(names)
        let start = session.state!
        state = start
        counts = start.pits.map { $0.intValue }
        seeds = CongklakSeeds(pits: start.pits)
        board.setSeedTargets(seeds.targets())
    }

    var game: CongklakGame { CongklakGame.shared }
    var turn: Int { Int(state.turn) }
    var finished: Bool { state.finished }

    func layout(size: CGSize) {
        board.layout(size: size)
        pitPoints = board.pitPoints
    }

    /// A tap at a view point: plays the nearest hole on the mover's side, if close enough.
    func tap(at point: CGPoint) {
        guard pitPoints.count == Int(game.PITS) else { return }
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

    func play(hole: Int) {
        let seat = state.turn
        let move = CongklakMove(hole: Int32(hole))
        guard !animating, !finished, game.validate(state: state, seat: seat, move: move) == nil else { return }
        let sowing = game.play(state: state, seat: seat, move: move)
        guard session.submitLocal(seat: seat, move: move) == nil, let next = session.state else { return }
        let steps = CongklakSeeds.companion.steps(before: state.pits, startPit: game.pit(seat: seat, hole: Int32(hole)), path: sowing.path)

        animating = true
        Task {
            for step in steps {
                seeds.apply(step: step)
                push()
                try? await Task.sleep(nanoseconds: step.pickUp ? Self.pickUpDelay : Self.sowDelay)
            }
            // Captures and the end-of-game sweep: move whatever differs to match the rules.
            if seeds.counts() != next.pits {
                try? await Task.sleep(nanoseconds: Self.settleDelay)
                seeds.settle(pits: next.pits)
                push()
            }
            state = next
            animating = false
        }
    }

    func restart() {
        session = Self.newSession(names)
        state = session.state!
        seeds.reset(pits: state.pits)
        push()
    }

    private func push() {
        board.setSeedTargets(seeds.targets())
        counts = seeds.counts().map { $0.intValue }
    }

    private static func newSession(_ names: [String]) -> HostSession<CongklakState, CongklakMove> {
        let s = HostSession<CongklakState, CongklakMove>(rules: CongklakGame.shared, transport: LocalTransport(), roomCode: "LOCAL")
        for name in names { _ = s.addLocalPlayer(name: name, platform: .ios) }
        s.start()
        return s
    }
}

struct CongklakView: View {
    @StateObject private var model = CongklakGameModel(names: [
        String(localized: "Pemain 1"),
        String(localized: "Pemain 2"),
    ])
    let onExit: () -> Void

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
    }

    private var hud: some View {
        VStack {
            HStack(spacing: 12) {
                Button("Kembali", action: onExit)
                Text("Congklak").font(.title2.bold())
                Spacer()
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)
            Spacer()
            VStack(spacing: 8) {
                Text(statusLine).font(.title2)
                Text("\(model.names[0]) \(score(0)) · \(model.names[1]) \(score(1))")
                if model.finished {
                    Button("Main lagi", action: model.restart).buttonStyle(.borderedProminent)
                }
            }
            .padding(.bottom, 24)
        }
        .foregroundStyle(.black)
    }

    private var statusLine: String {
        guard let winner = model.game.winner(state: model.state)?.intValue else {
            return String(localized: "Giliran \(model.names[model.turn])")
        }
        return winner < 0 ? String(localized: "Seri!") : String(localized: "\(model.names[winner]) menang!")
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
        let owner = model.game.ownerOf(pit: Int32(pit))
        if store { return CGPoint(x: p.x + (owner == 0 ? 48 : -48), y: p.y) }
        return CGPoint(x: p.x, y: p.y + (owner == 0 ? 34 : -34))
    }
}

/// Hosts the RealityKit view owned by the model.
private struct BoardContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
