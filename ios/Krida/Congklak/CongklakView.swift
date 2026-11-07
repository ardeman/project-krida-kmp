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
    func noticeChanged() {
        waiting = false
        updateRings()
    }

    /// Gold rings on the holes the mover may start from, only while this device can play.
    private func updateRings() {
        guard canPlay else { return board.showRings([]) }
        let seat = state.turn
        let holes = (0..<Int32(game.HOLES)).filter { game.validate(state: state, seat: seat, move: CongklakMove(hole: $0)) == nil }
        board.showRings(Set(holes.map { Int(game.pit(seat: seat, hole: $0)) }))
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

    private func play(hole: Int) {
        let seat = state.turn
        guard canPlay, game.validate(state: state, seat: seat, move: CongklakMove(hole: Int32(hole))) == nil else { return }
        // Pass-and-play and the host answer synchronously; a guest waits for the host's snapshot.
        if table.play(seat: seat, hole: Int32(hole)), table.isRemote {
            waiting = true
            board.showRings([])
        }
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
        guard !queue.isEmpty else {
            updateRings()
            return idle()
        }
        let update = queue.removeFirst()
        animating = true
        board.showRings([])
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
        inHand = Int(seeds.handCount())
    }
}

struct CongklakView: View {
    @ObservedObject private var table: CongklakTable
    @StateObject private var model: CongklakGameModel
    @State private var showRules = false
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
                    countLabel(pit: pit).position(labelPosition(pit: pit, at: point))
                }

                hud
                if model.finished && !model.animating { gameOver }
            }
            .onAppear { DispatchQueue.main.async { model.layout(size: geo.size) } }
            .onChange(of: geo.size) { _, size in DispatchQueue.main.async { model.layout(size: size) } }
        }
        .ignoresSafeArea()
        .background(Theme.cream)
        .onChange(of: table.notice) { _, notice in if notice != nil { model.noticeChanged() } }
        .sheet(isPresented: $showRules) { RulesSheet() }
    }

    private func exit() {
        table.leave()
        onExit()
    }

    // MARK: Seats

    private var nearSeat: Int { Int(model.viewSeat) }
    private var farSeat: Int { 1 - nearSeat }
    private var botSeat: Int? { table.botLevel == nil ? nil : 1 - Int(table.mySeat ?? 0) }

    private func name(_ seat: Int) -> String {
        table.players.first { $0.seat == Int32(seat) }?.name ?? "\(seat + 1)"
    }

    /// "Kamu" for this device's own seat; the player's name otherwise.
    private func displayName(_ seat: Int) -> String {
        table.mySeat.map(Int.init) == seat ? String(localized: "Kamu") : name(seat)
    }

    /// The seat can be played from this device (own seat, or anyone in pass-and-play).
    private func isLocal(_ seat: Int) -> Bool {
        seat != botSeat && (table.mySeat == nil || Int(table.mySeat!) == seat)
    }

    private func active(_ seat: Int) -> Bool { !model.finished && model.turn == seat }

    private func status(_ seat: Int) -> String {
        guard active(seat) else { return String(localized: "menunggu") }
        if model.animating && model.inHand > 0 { return String(localized: "Menabur · \(model.inHand) biji di tangan") }
        if isLocal(seat) { return String(localized: "Giliranmu · pilih lubang yang menyala") }
        return seat == botSeat ? String(localized: "berpikir…") : String(localized: "gilirannya")
    }

    // MARK: HUD

    private var hud: some View {
        VStack(spacing: 0) {
            HStack {
                CircleIconButton(systemImage: "chevron.left", label: "Kembali ke menu", action: exit)
                Spacer()
                CircleIconButton(systemImage: "questionmark", label: "Cara main") { showRules = true }
            }
            .overlay { opponentPill }
            .padding(.horizontal, 24)
            .padding(.top, 14)
            Spacer()
            VStack(spacing: 8) {
                if let notice = table.notice {
                    Text(notice).font(Theme.font(14, .bold)).foregroundStyle(Color(hex: 0xB3261E))
                } else {
                    Label("Searah jarum jam · lumbungmu di kiri", systemImage: "arrow.clockwise")
                        .font(Theme.font(13, .bold))
                        .foregroundStyle(Theme.muted)
                }
                playerPill(nearSeat)
            }
            .padding(.bottom, 14)
        }
        .foregroundStyle(Theme.ink)
    }

    /// The far seat, above the board: who you play against and what they're doing.
    private var opponentPill: some View {
        let seat = farSeat
        let on = active(seat)
        let detail = [table.botLevel.flatMap { seat == botSeat ? $0 : nil }, status(seat)].compactMap { $0 }.joined(separator: " · ")
        return HStack(spacing: 10) {
            avatar(seat, on: on, size: 30)
            Text(displayName(seat)).font(Theme.font(16))
            Text(detail).font(Theme.font(13, .bold)).foregroundStyle(on ? Color(hex: 0xFBE7D3) : Theme.muted)
        }
        .lineLimit(1)
        .padding(.leading, 6)
        .padding(.trailing, 16)
        .frame(height: 40)
        .foregroundStyle(on ? .white : Theme.ink)
        .background(on ? Theme.accent : .white, in: Capsule())
        .overlay(Capsule().stroke(on ? .clear : Theme.line, lineWidth: 1.5))
        .shadow(color: on ? Theme.gold.opacity(0.6) : .clear, radius: 6)
        .padding(.horizontal, 60)
        .accessibilityElement(children: .combine)
    }

    /// The near seat, below the board; glows on its turn.
    private func playerPill(_ seat: Int) -> some View {
        let on = active(seat)
        return HStack(spacing: 10) {
            avatar(seat, on: on, size: 34)
            Text(displayName(seat)).font(Theme.font(17, .black))
            Text(status(seat)).font(Theme.font(15, .bold)).foregroundStyle(on ? Color(hex: 0xFBE7D3) : Theme.muted)
        }
        .lineLimit(1)
        .padding(.leading, 6)
        .padding(.trailing, 20)
        .frame(height: 46)
        .foregroundStyle(on ? .white : Theme.ink)
        .background(on ? Theme.accent : .white, in: Capsule())
        .overlay(Capsule().stroke(on ? Theme.gold.opacity(0.45) : Theme.line, lineWidth: on ? 4 : 1.5).padding(on ? -2 : 0))
        .animation(.easeOut(duration: 0.2), value: on)
        .accessibilityElement(children: .combine)
    }

    private func avatar(_ seat: Int, on: Bool, size: CGFloat) -> some View {
        Group {
            if seat == botSeat {
                Image(systemName: "desktopcomputer").font(.system(size: size * 0.5, weight: .semibold))
            } else {
                Text(String(displayName(seat).prefix(1)).uppercased()).font(Theme.font(size * 0.45, .black))
            }
        }
        .foregroundStyle(on ? Theme.accent : Theme.muted)
        .frame(width: size, height: size)
        .background(on ? Color.white : Color(hex: 0xEFE3D4), in: Circle())
        .accessibilityHidden(true)
    }

    // MARK: Counts on the rim

    private func countLabel(pit: Int) -> some View {
        let store = model.game.isStore(pit: Int32(pit))
        let near = Int(model.game.ownerOf(pit: Int32(pit))) == nearSeat
        return Group {
            if store {
                VStack(spacing: 3) {
                    Text("\(model.counts[pit])").font(Theme.font(22, .black)).foregroundStyle(Color(hex: 0xFCEBD2))
                    Text(storeTag(near ? nearSeat : farSeat))
                        .font(Theme.font(11, .black))
                        .foregroundStyle(near ? .white : Theme.ink)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background(near ? Theme.accent : Theme.tan, in: RoundedRectangle(cornerRadius: 8))
                }
            } else {
                Text("\(model.counts[pit])")
                    .font(Theme.font(15))
                    .foregroundStyle(near ? Color(hex: 0xFFF6E8) : Theme.tan)
            }
        }
        .shadow(color: .black.opacity(0.35), radius: 1, y: 1)
        .allowsHitTesting(false)
    }

    /// Short label on a store: KAMU, KOM, or the player's name.
    private func storeTag(_ seat: Int) -> String {
        if seat == botSeat { return String(localized: "KOM") }
        return String(displayName(seat).uppercased().prefix(8))
    }

    private func labelPosition(pit: Int, at p: CGPoint) -> CGPoint {
        let store = model.game.isStore(pit: Int32(pit))
        let near = model.game.ownerOf(pit: Int32(pit)) == model.viewSeat
        if store { return CGPoint(x: p.x, y: p.y + 34) }
        return CGPoint(x: p.x, y: p.y + (near ? 34 : -34))
    }

    // MARK: End of game

    private var gameOver: some View {
        let winner = model.game.winner(state: model.state)?.intValue
        return ZStack {
            Theme.ink.opacity(0.45).ignoresSafeArea()
            VStack(spacing: 12) {
                Image(systemName: winner == nil || winner! < 0 ? "equal.circle" : "trophy")
                    .font(.system(size: 36, weight: .semibold))
                    .foregroundStyle(Color(hex: 0xC98A1B))
                    .accessibilityHidden(true)
                Text(resultTitle(winner)).font(Theme.font(26, .black))
                HStack(spacing: 10) {
                    scoreTile(nearSeat, winner: winner)
                    scoreTile(farSeat, winner: winner)
                }
                HStack(spacing: 10) {
                    Button(action: exit) { Text("Ke menu").frame(maxWidth: .infinity) }
                        .buttonStyle(SecondaryButtonStyle(height: 48, tint: Theme.ink))
                    if table.canRestart {
                        Button(action: table.restart) { Text("Main lagi").frame(maxWidth: .infinity) }
                            .buttonStyle(PrimaryButtonStyle(height: 48))
                    } else {
                        Text("Menunggu host memulai lagi…")
                            .font(Theme.font(14, .bold))
                            .foregroundStyle(Theme.muted)
                            .multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, 24)
            .padding(.top, 22)
            .padding(.bottom, 20)
            .frame(width: 380)
            .background(.white, in: RoundedRectangle(cornerRadius: 28))
            .shadow(color: Theme.ink.opacity(0.35), radius: 25, y: 20)
            .foregroundStyle(Theme.ink)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
        .transition(.opacity)
    }

    private func resultTitle(_ winner: Int?) -> String {
        guard let winner else { return "" }
        if winner < 0 { return String(localized: "Seri!") }
        if table.mySeat.map(Int.init) == winner { return String(localized: "Kamu menang!") }
        return String(localized: "\(name(winner)) menang!")
    }

    private func scoreTile(_ seat: Int, winner: Int?) -> some View {
        let won = winner == seat
        return VStack(spacing: 0) {
            Text(displayName(seat)).font(Theme.font(14)).foregroundStyle(Theme.muted).lineLimit(1)
            Text("\(score(seat))").font(Theme.font(30, .black)).foregroundStyle(won ? Theme.ink : Theme.muted)
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(won ? Color(hex: 0xFBEFE3) : Color(hex: 0xF6F1EA), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(won ? Theme.accent : .clear, lineWidth: 2))
    }

    private func score(_ seat: Int) -> Int { Int(model.game.score(state: model.state, seat: Int32(seat))) }
}

/// "Cara main": the rule variant from shared/ CongklakGame, in short.
private struct RulesSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    rule("Ambil semua biji dari satu lubang di sisimu, lalu tabur satu per satu searah jarum jam.")
                    rule("Lumbungmu di kiri ikut diisi; lumbung lawan dilewati.")
                    rule("Biji terakhir jatuh di lumbungmu: kamu main lagi.")
                    rule("Jatuh di lubang yang berisi: ambil semuanya dan terus menabur.")
                    rule("Jatuh di lubang kosong di sisimu: tembak! Biji itu dan isi lubang lawan di depannya masuk ke lumbungmu.")
                    rule("Jatuh di lubang kosong di sisi lawan: giliranmu selesai.")
                    rule("Kalau semua lubangmu kosong saat giliranmu, permainan selesai. Biji terbanyak di lumbung menang.")
                }
                .padding(24)
            }
            .background(Theme.cream)
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
