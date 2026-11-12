import RealityKit
import Shared
import SwiftUI

/// Perang kapal on voxel grids, driven by a `PerangKapalTable`: place the fleet on your ocean
/// (tap or drag the ships; tap a placed ship to turn it), then fire at the radar one shot a
/// turn. Two people on one phone pass it for each turn behind a cover. Same flow as Android's
/// `PerangKapalScreen`.
@MainActor
final class PerangKapalGameModel: ObservableObject {
    /// Latest snapshot from the table: this seat's view, or everything when passing the phone.
    @Published private(set) var state: PerangKapalState
    @Published private(set) var viewFrame: CGRect = .zero
    /// The seat whose side of the game is on screen.
    @Published private(set) var viewer: Int
    /// Passing the phone: the seat to hand it to; the cover hides the board until they continue.
    @Published private(set) var cover: Int?
    /// Ship kind the next tap on an empty ocean cell places; nil once all are placed.
    @Published private(set) var selectedKind: Int? = 0
    /// A ship dragged from the tray that is not over the ocean yet, under the finger.
    @Published private(set) var ghost: (kind: Int, point: CGPoint)?
    /// Seconds left to place the fleet (`Krida.PLACE_FLEET_MS`); 0 when not counting.
    @Published private(set) var placeSeconds = 0
    /// Seconds left to fire (`Krida.PLAY_TURN_MS`); 0 when not counting.
    @Published private(set) var fireSeconds = 0

    let table: PerangKapalTable
    let scene = PerangKapalScene()
    /// The loading screen still covers the board; computers wait for it.
    var loading = true
    private let sounds = Sounds.shared
    private let game = PerangKapalGame.shared
    private let lifetime = GameLifetime()
    /// Finger down on the ocean: the ship under it (if any) and whether it has become a drag.
    private var grab: (kind: Int?, dragging: Bool)?
    private var dragCell: Int?
    /// The seat whose placement countdown runs, nil when none does.
    private var placeKey: Int?
    /// The firing countdown's seat and snapshot, nil when none runs.
    private var fireKey: String?
    /// Counts snapshots, so each new shot to make restarts the firing countdown.
    private var version = 0
    /// Seats on this phone whose timer ran out: the phone fires for them after a short pause
    /// (`Krida.AUTO_PLAY_MS`) instead of a countdown, until that player fires themselves.
    private var autoSeats = Set<Int>()

    private static let botFirst: Double = 0.32
    private static let botNext: Double = 0.26
    /// How long the result of a shot stays up before the phone is passed on.
    private static let passPause: Double = 1.5
    /// A finger that moved less than this tapped.
    private static let slop: CGFloat = 10

    init(table: PerangKapalTable) {
        self.table = table
        state = table.latest
        viewer = table.mySeat ?? 0
        scene.onViewFrame = { [weak self] frame in self?.viewFrame = frame }
        table.onUpdate = { [weak self] update in
            guard let self, self.lifetime.active else { return }
            self.show(update.state)
        }
        show(table.latest)
    }

    /// Two people share this phone.
    var passing: Bool { table.mySeat == nil }
    var turn: Int { Int(state.turn) }

    private func isReady(_ seat: Int) -> Bool {
        state.ready.indices.contains(seat) ? state.ready[seat].boolValue : true
    }
    private var myFleet: [PerangKapalShip] {
        state.fleets.indices.contains(viewer) ? state.fleets[viewer] : []
    }
    /// Ship kinds already on the ocean.
    var placedKinds: Set<Int> { Set(myFleet.map { Int($0.kind) }) }

    var canPlace: Bool {
        lifetime.active && cover == nil && state.placing && !state.finished && table.isLocal(viewer) && !isReady(viewer)
    }
    var canReady: Bool { canPlace && game.fleetComplete(fleet: myFleet) }
    var canFire: Bool {
        lifetime.active && cover == nil && !state.placing && !state.finished && viewer == turn && table.isLocal(turn)
    }
    /// Ship kinds of `seat`'s fleet that the other side has sunk.
    func lost(_ seat: Int) -> Set<Int> {
        let foe = 1 - seat
        guard state.shots.indices.contains(foe) else { return [] }
        return Set(state.shots[foe].compactMap { $0.sunk?.intValue })
    }

    /// The seat still has something to do: its fleet while placing, its shot in battle.
    func acting(_ seat: Int) -> Bool {
        if state.finished { return false }
        return state.placing ? !isReady(seat) : seat == turn
    }

    private func show(_ next: PerangKapalState) {
        state = next
        version += 1
        draw()
        syncViewer()
        syncPlaceTimer()
        syncFireTimer()
        scheduleBot()
    }

    // MARK: Placement timer

    /// Seconds to place the fleet (`Krida.PLACE_FLEET_MS`).
    var placeTotal: Int { Int(Krida.shared.PLACE_FLEET_MS / 1000) }

    /// While a player on this phone places their fleet a countdown runs; at 0 the phone places
    /// the ships still missing (`PerangKapalGame.timeoutFleet`) and taps Ready. The pass cover
    /// and waiting on the other side stop it; each placing turn starts afresh.
    private func syncPlaceTimer() {
        let key = canPlace ? viewer : nil
        guard key != placeKey else { return }
        placeKey = key
        lifetime.cancel("place")
        placeSeconds = 0
        guard let seat = key else { return }
        placeSeconds = placeTotal
        lifetime.launch("place") { [self] in
            while placeSeconds > 0 {
                try await Task.sleep(for: .seconds(1))
                guard canPlace, viewer == seat else { placeSeconds = 0; return }
                placeSeconds -= 1
            }
            let latest = table.latest
            guard let fleet = game.timeoutFleet(state: latest, seat: Int32(seat), random: KotlinRandom.companion) else { return }
            ghost = nil
            grab = nil
            dragCell = nil
            let placed = latest.fleets.indices.contains(seat) ? latest.fleets[seat] : []
            if !game.fleetComplete(fleet: placed) { _ = table.setFleet(seat: seat, fleet: fleet) }
            if table.ready(seat: seat) { sounds.play(.tap) }
            autoSeats.insert(seat)
        }
    }

    // MARK: Firing timer

    /// Seconds to fire (`Krida.PLAY_TURN_MS`).
    var fireTotal: Int { Int(Krida.shared.PLAY_TURN_MS / 1000) }

    /// On a local player's shot a countdown runs; at 0 the phone fires the easy computer's shot
    /// (`PerangKapalGame.timeoutMove`), and keeps firing for that player after a short pause
    /// until they tap the radar themselves.
    private func syncFireTimer() {
        let key = canFire ? "\(viewer)-\(version)" : nil
        guard key != fireKey else { return }
        fireKey = key
        lifetime.cancel("fire")
        fireSeconds = 0
        guard key != nil else { return }
        let seat = viewer
        let auto = autoSeats.contains(seat)
        fireSeconds = auto ? 0 : fireTotal
        lifetime.launch("fire") { [self] in
            if auto {
                try await Task.sleep(for: .seconds(Double(Krida.shared.AUTO_PLAY_MS) / 1000))
            } else {
                while fireSeconds > 0 {
                    try await Task.sleep(for: .seconds(1))
                    guard canFire, viewer == seat else { fireSeconds = 0; return }
                    fireSeconds -= 1
                }
                autoSeats.insert(seat)
            }
            guard canFire, viewer == seat, autoSeats.contains(seat),
                  let cell = game.timeoutMove(state: table.latest, seat: Int32(seat), random: KotlinRandom.companion)?.fire
            else { return }
            if table.fire(seat: seat, cell: Int(cell.int32Value)) { sounds.play(.tap) }
        }
    }

    private func draw() {
        scene.apply(game.viewFor(state: state, seat: Int32(viewer)), mySeat: viewer)
    }

    // MARK: Passing the phone

    /// The seat that has to act on this phone now.
    private var wanted: Int {
        state.placing ? (0..<2).first { !isReady($0) } ?? viewer : turn
    }

    /// When the other person has to act, the cover goes up; after a shot its result shows first.
    private func syncViewer() {
        guard passing, !state.finished, wanted != viewer else {
            lifetime.cancel("pass")
            if !passing || state.finished { cover = nil }
            return
        }
        if cover != nil || state.placing || state.lastHit == nil {
            cover = wanted
        } else {
            lifetime.launch("pass") { [self] in
                try await Task.sleep(for: .seconds(Self.passPause))
                if passing, !state.finished, wanted != viewer { cover = wanted }
            }
        }
    }

    /// The next person holds the phone: show their side.
    func continuePass() {
        guard lifetime.active, let next = cover else { return }
        sounds.play(.tap)
        viewer = next
        cover = nil
        selectNext()
        draw()
        syncPlaceTimer()
        syncFireTimer()
    }

    // MARK: Computers

    /// Computers place and fire a moment after each change, one step at a time.
    func scheduleBot() {
        guard lifetime.active, !state.finished else { return lifetime.cancel("bot") }
        lifetime.launch("bot") { [self] in
            while loading { try await Task.sleep(for: .milliseconds(100)) }
            try await Task.sleep(for: .seconds(Self.botFirst))
            var steps = 0
            while lifetime.active, botToMove, steps < 40 {
                steps += 1
                table.stepBot()
                try await Task.sleep(for: .seconds(Self.botNext))
            }
        }
    }

    private var botToMove: Bool {
        let bots = table.botSeats
        return game.actingSeats(state: table.latest).contains { bots.contains($0.intValue) }
    }

    func layout(size: CGSize, free: CGRect, left: CGFloat) {
        viewFrame = scene.layout(size: size, free: free, left: left)
    }

    // MARK: Placing and firing

    private func ship(at cell: Int) -> PerangKapalShip? {
        myFleet.first { $0.cells.contains { $0.intValue == cell } }
    }

    /// A placed ship keeps its direction when moved; a new one starts across.
    private func horizontal(_ kind: Int) -> Bool {
        myFleet.first { Int($0.kind) == kind }?.horizontal ?? true
    }

    /// Sends the placement if the rules allow it here (checked first, so a guest's drag over
    /// another ship is not a refused move).
    private func place(_ kind: Int, bow: Int, horizontal: Bool) -> Bool {
        let move = PerangKapalMove(kind: KotlinInt(int: Int32(kind)), cell: KotlinInt(int: Int32(bow)),
                                   horizontal: horizontal, ready: false, fire: nil, fleet: nil)
        guard game.validate(state: table.latest, seat: Int32(viewer), move: move) == nil else { return false }
        return table.place(seat: viewer, kind: kind, cell: bow, horizontal: horizontal)
    }

    /// On to the first ship that is not on the ocean yet.
    private func selectNext(placed extra: Int? = nil) {
        var placed = placedKinds
        if let extra { placed.insert(extra) }
        selectedKind = (0..<Int(game.KINDS)).first { !placed.contains($0) }
    }

    /// Finger on the board at `point`, down since `start`.
    func boardTouch(_ point: CGPoint, start: CGPoint) {
        guard canPlace else { return }
        if grab == nil {
            let under = scene.pickCell(at: start, onRadar: false).flatMap { ship(at: $0) }
            grab = (under.map { Int($0.kind) }, false)
        }
        guard let kind = grab?.kind else { return }
        if grab?.dragging != true && hypot(point.x - start.x, point.y - start.y) < Self.slop { return }
        grab?.dragging = true
        drag(kind, to: point)
    }

    func boardRelease(start: CGPoint) {
        let dragged = grab?.dragging == true
        grab = nil
        if dragged { endDrag() } else { tap(at: start) }
    }

    /// Finger that went down on tray ship `kind` is at `point`, `moved` from where it started.
    func trayTouch(_ kind: Int, at point: CGPoint, moved: CGFloat) {
        guard canPlace, dragCell != nil || ghost != nil || moved >= Self.slop else { return }
        selectedKind = kind
        ghost = scene.pickCell(at: point, onRadar: false) == nil ? (kind, point) : nil
        drag(kind, to: point)
    }

    func trayRelease(_ kind: Int) {
        if dragCell != nil || ghost != nil { endDrag() } else { select(kind) }
    }

    /// The dragged ship follows the finger cell by cell, wherever it fits.
    private func drag(_ kind: Int, to point: CGPoint) {
        guard canPlace, let cell = scene.pickCell(at: point, onRadar: false), cell != dragCell else { return }
        dragCell = cell
        let across = horizontal(kind)
        _ = place(kind, bow: Int(game.bowFor(cell: Int32(cell), kind: Int32(kind), horizontal: across)), horizontal: across)
    }

    private func endDrag() {
        ghost = nil
        guard dragCell != nil else { return }
        dragCell = nil
        sounds.play(.tap)
        selectNext()
    }

    private func tap(at point: CGPoint) {
        if canFire {
            guard let cell = scene.pickCell(at: point, onRadar: true) else { return }
            if table.fire(seat: viewer, cell: cell) {
                sounds.play(.tap)
                autoSeats.remove(viewer)
            }
        } else if canPlace {
            guard let cell = scene.pickCell(at: point, onRadar: false) else { return }
            if let ship = ship(at: cell) {
                // A tap on a placed ship turns it: around its middle, or on another of its
                // cells when the middle is crowded.
                guard let bow = game.turnedBow(fleet: myFleet, ship: ship, tapped: Int32(cell)) else { return }
                if place(Int(ship.kind), bow: Int(bow.int32Value), horizontal: !ship.horizontal) { sounds.play(.tap) }
            } else if let kind = selectedKind {
                let across = horizontal(kind)
                guard place(kind, bow: Int(game.bowFor(cell: Int32(cell), kind: Int32(kind), horizontal: across)), horizontal: across) else { return }
                sounds.play(.tap)
                selectNext(placed: kind)
            }
        }
    }

    func select(_ kind: Int) {
        guard canPlace else { return }
        sounds.play(.tap)
        selectedKind = kind
    }

    func ready() {
        if canReady && table.ready(seat: viewer) { sounds.play(.tap) }
    }

    func restart() {
        guard lifetime.active else { return }
        selectedKind = 0
        table.restart()
    }

    func stop() {
        guard lifetime.active else { return }
        lifetime.stop()
        table.onUpdate = nil
        scene.onViewFrame = nil
        grab = nil
        dragCell = nil
        ghost = nil
        table.leave()
        sounds.stopGameAudio()
    }
}

/// The game screen: the loading screen goes up first, the board is built under it, and the
/// loading screen fades out once the board's first frames are drawn (as ludo).
struct PerangKapalView: View {
    let table: PerangKapalTable
    let onExit: () -> Void
    @State private var mounted = false
    @State private var loading = true

    var body: some View {
        ZStack {
            if mounted { PerangKapalBoardView(table: table, onExit: onExit, loading: loading, onDrawn: finishLoading) }
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

/// Ship names by kind, with the length. Same lines as Android.
private let shipNames: [LocalizedStringKey] = [
    "Carrier · 5", "Battleship · 4", "Cruiser · 3", "Submarine · 3", "Destroyer · 2",
]

private func shipName(_ kind: Int) -> String {
    switch kind {
    case 0: String(localized: "Carrier · 5", bundle: .app)
    case 1: String(localized: "Battleship · 4", bundle: .app)
    case 2: String(localized: "Cruiser · 3", bundle: .app)
    case 3: String(localized: "Submarine · 3", bundle: .app)
    default: String(localized: "Destroyer · 2", bundle: .app)
    }
}

/// A ship seen from above, drawn from the boxes of its voxel model (`shipParts` in shared/),
/// bow to the left. `unit` is the points one board unit takes.
struct ShipPicture: View {
    let kind: Int
    let unit: CGFloat

    private static let board = PerangKapalBoard.shared
    /// Nine floats a box, lowest first; see `PerangKapalBoard.shipParts`.
    private static let parts: [[Float]] = (0..<Int(PerangKapalGame.shared.KINDS)).map { kind in
        let p = board.shipParts(kind: Int32(kind))
        return (0..<Int(p.size)).map { p.get(index: Int32($0)) }
    }

    var body: some View {
        let parts = Self.parts[kind]
        Canvas { context, size in
            for i in stride(from: 0, to: parts.count, by: 9) {
                let rect = CGRect(
                    x: CGFloat(parts[i]) * unit, y: size.height / 2 + CGFloat(parts[i + 2]) * unit,
                    width: CGFloat(parts[i + 1] - parts[i]) * unit, height: CGFloat(parts[i + 3] - parts[i + 2]) * unit)
                let color = Color(red: Double(parts[i + 6]), green: Double(parts[i + 7]), blue: Double(parts[i + 8]))
                context.fill(Path(rect), with: .color(color))
            }
        }
        .frame(width: CGFloat(Self.board.shipLength(kind: Int32(kind))) * unit, height: CGFloat(Self.board.CELL) * unit)
    }
}

/// Seat colours on the tiles (red, blue), as on Android.
private func seatColor(_ seat: Int) -> Color {
    Color(hex: seat == 0 ? 0xD6453D : 0x2F6FD1)
}

private struct PerangKapalBoardView: View {
    @ObservedObject private var table: PerangKapalTable
    @StateObject private var model: PerangKapalGameModel
    @ObservedObject private var ads = Ads.shared
    @State private var showRules = false
    @State private var size: CGSize = .zero
    @State private var boxes: [String: CGRect] = [:]
    @State private var sideInsets = EdgeInsets()
    @Environment(\.uiScale) private var s
    @Environment(\.punchHole) private var hole
    let onExit: () -> Void
    let loading: Bool
    let onDrawn: () -> Void

    init(table: PerangKapalTable, onExit: @escaping () -> Void, loading: Bool, onDrawn: @escaping () -> Void) {
        self.table = table
        _model = StateObject(wrappedValue: PerangKapalGameModel(table: table))
        self.onExit = onExit
        self.loading = loading
        self.onDrawn = onDrawn
    }

    private static let space = "perangKapal"

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
                    .gesture(boardGesture)
                    .allowsHitTesting(model.canPlace || model.canFire)
                hud
                if let ghost = model.ghost {
                    ShipPicture(kind: ghost.kind, unit: trayUnit)
                        .position(ghost.point)
                        .allowsHitTesting(false)
                }
                if let next = model.cover { passCover(next) }
                if model.state.finished { gameOver }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .background(alignment: .topLeading) {
                PerangKapalBoardContainer(view: model.scene.view)
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
        .onChange(of: loading) { _, now in model.loading = now }
        // A dropped guest handed to the computer may have to act right away.
        .onChange(of: table.botSeats) { _, _ in model.scheduleBot() }
        .sheet(isPresented: $showRules) { PerangKapalRulesSheet() }
    }

    /// A tap fires or places; a drag that starts on a placed ship moves it.
    private var boardGesture: some Gesture {
        DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space))
            .onChanged { model.boardTouch($0.location, start: $0.startLocation) }
            .onEnded { model.boardRelease(start: $0.startLocation) }
    }

    /// Points per board unit in the tray: the longest ship fills the column.
    private var trayUnit: CGFloat { 152 * s / CGFloat(PerangKapalBoard.shared.shipLength(kind: 0)) }

    /// The grids take the space between the two HUD columns.
    private func relayout() {
        guard size.width > 0, let room = boxes["room"], let left = boxes["left"], let right = boxes["right"],
              left.width > 0, right.width > 0 else { return }
        let margin = 12 * s
        let x0 = left.maxX + margin, x1 = right.minX - margin
        guard x1 > x0 else { return }
        model.layout(size: size, free: CGRect(x: x0, y: room.minY, width: x1 - x0, height: room.height), left: room.minX)
    }

    private func exit() {
        model.stop()
        onExit()
    }

    private func hudBox(_ key: String) -> some ViewModifier {
        PerangKapalFrameReader(space: Self.space, frame: Binding(get: { boxes[key] ?? .zero }, set: { boxes[key] = $0 }))
    }

    private func displayName(_ seat: Int) -> String {
        table.mySeat == seat ? String(localized: "You", bundle: .app) : table.name(seat)
    }

    private var status: String {
        let st = model.state
        if st.finished { return "" }
        if st.placing {
            return model.canPlace
                ? String(localized: "Drag ships onto your ocean · tap one to turn it", bundle: .app)
                : String(localized: "Waiting for the other player…", bundle: .app)
        }
        // The last shot's result first; before the first shot, whose turn it is.
        if let hit = st.lastHit?.boolValue {
            if hit, let sunk = st.lastSunk?.intValue {
                return String(localized: "\(shipName(sunk)) sunk!", bundle: .app)
            }
            return hit ? String(localized: "Hit!", bundle: .app) : String(localized: "Miss", bundle: .app)
        }
        if model.canFire { return String(localized: "Your turn · tap a radar cell", bundle: .app) }
        return String(localized: "\(displayName(model.turn))'s turn", bundle: .app)
    }

    private var hud: some View {
        HStack(alignment: .top, spacing: 0) {
            VStack(alignment: .leading, spacing: 10 * s) {
                CircleIconButton(systemImage: "chevron.left", label: "Back to menu", action: exit)
                Spacer().frame(height: 4 * s)
                VStack(spacing: 8 * s) {
                    ForEach(0..<2, id: \.self) { seat in tile(seat, on: model.acting(seat)) }
                }
                Spacer(minLength: 0)
            }
            .frame(width: 136 * s)
            .frame(maxHeight: .infinity)
            .modifier(hudBox("left"))
            Spacer(minLength: 0)
            VStack(spacing: 0) {
                HStack(spacing: 8 * s) {
                    SoundToggles()
                    CircleIconButton(systemImage: "questionmark", label: "How to play") { showRules = true }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
                if table.canEmote { Spacer().frame(height: 8 * s) }
                EmoteHud(
                    canEmote: table.canEmote,
                    emotes: table.emotes,
                    playerName: displayName,
                    onSend: table.sendEmote,
                    s: s
                )
                .frame(maxWidth: .infinity, alignment: .trailing)
                Spacer(minLength: 0)
                if model.canPlace { tray }
                if !model.state.placing {
                    if model.fireSeconds > 0 {
                        CountdownRing(seconds: model.fireSeconds, total: model.fireTotal, size: 40 * s)
                            .padding(.bottom, 8 * s)
                    }
                    fleetStatus("Enemy fleet", sunk: model.lost(1 - model.viewer))
                    fleetStatus("Your fleet", sunk: model.lost(model.viewer))
                        .padding(.top, 8 * s)
                }
                Text(status)
                    .font(Theme.font(13 * s, .bold))
                    .foregroundStyle(Theme.ink)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2, reservesSpace: true)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .padding(.top, 8 * s)
                if let away = table.waiting {
                    CountdownNotice(text: away.text, seconds: away.seconds, total: Int(Krida.shared.HANDOFF_WAIT_MS / 1000), fontSize: 13)
                        .frame(maxWidth: .infinity)
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
                        .padding(.top, 8 * s)
                }
            }
            .frame(width: 168 * s)
            .frame(maxHeight: .infinity)
            .modifier(hudBox("right"))
        }
        .modifier(hudBox("room"))
        .padding(.horizontal, 24 * s)
        .padding(.leading, max(max(hole?.leading ?? 0, sideInsets.leading) - 24 * s, 0))
        .padding(.trailing, max(max(hole?.trailing ?? 0, sideInsets.trailing) - 24 * s, 0))
        .padding(.vertical, 14 * s)
        .foregroundStyle(Theme.ink)
    }

    /// The five ships themselves: tap one then a cell, or drag it onto the ocean. Then Ready.
    private var tray: some View {
        let placed = model.placedKinds
        return VStack(alignment: .trailing, spacing: 4 * s) {
            HStack(spacing: 8 * s) {
                if model.placeSeconds > 0 {
                    CountdownRing(seconds: model.placeSeconds, total: model.placeTotal, size: 30 * s)
                }
                Text("Place your fleet")
                    .font(Theme.font(13 * s, .bold))
                    .lineLimit(1)
            }
            ForEach(shipNames.indices, id: \.self) { kind in
                let on = kind == model.selectedKind
                let shape = RoundedRectangle(cornerRadius: 10 * s)
                ShipPicture(kind: kind, unit: trayUnit)
                    .opacity(placed.contains(kind) ? 0.35 : 1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 8 * s)
                    .padding(.vertical, 2 * s)
                    .background(on ? Theme.chip : Theme.surface, in: shape)
                    .overlay(shape.stroke(on ? Theme.accent : Theme.line, lineWidth: 1.5))
                    .contentShape(shape)
                    .gesture(
                        DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.space))
                            .onChanged { model.trayTouch(kind, at: $0.location, moved: hypot($0.translation.width, $0.translation.height)) }
                            .onEnded { _ in model.trayRelease(kind) }
                    )
                    .accessibilityElement()
                    .accessibilityLabel(Text(shipNames[kind]))
                    .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton)
                    .accessibilityAction { model.select(kind) }
            }
            Button(action: model.ready) { Text("Ready").frame(maxWidth: .infinity) }
                .buttonStyle(PrimaryButtonStyle(height: 38))
                .disabled(!model.canReady)
                .padding(.top, 2 * s)
        }
    }

    /// How the battle stands for one side: its five ships, the sunk ones faded and struck out.
    private func fleetStatus(_ title: LocalizedStringKey, sunk: Set<Int>) -> some View {
        let unit = 13 * s
        return VStack(alignment: .trailing, spacing: 4 * s) {
            HStack(spacing: 4 * s) {
                Text(title)
                Text(verbatim: "\(shipNames.count - sunk.count)/\(shipNames.count)").foregroundStyle(Theme.muted)
            }
            .font(Theme.font(12 * s, .bold))
            .lineLimit(1)
            ForEach([[0, 1], [2, 3, 4]], id: \.self) { row in
                HStack(spacing: 6 * s) {
                    ForEach(row, id: \.self) { kind in
                        ShipPicture(kind: kind, unit: unit)
                            .opacity(sunk.contains(kind) ? 0.3 : 1)
                            .overlay {
                                if sunk.contains(kind) {
                                    Capsule().fill(Theme.error).frame(height: 2 * s)
                                }
                            }
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(title))
        .accessibilityValue(Text(verbatim: "\(shipNames.count - sunk.count)/\(shipNames.count)"))
    }

    /// Two people on one phone: hides the board while it changes hands.
    private func passCover(_ seat: Int) -> some View {
        ZStack {
            Theme.backdropGradient.ignoresSafeArea()
            VStack(spacing: 14 * s) {
                Circle().fill(seatColor(seat)).frame(width: 28 * s, height: 28 * s)
                Text("Pass the phone to \(table.name(seat))")
                    .font(Theme.font(22 * s, .black))
                    .multilineTextAlignment(.center)
                Button(action: model.continuePass) { Text("Continue").frame(maxWidth: .infinity) }
                    .buttonStyle(PrimaryButtonStyle(height: 48))
            }
            .padding(24 * s)
            .frame(maxWidth: 420 * s)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 20 * s))
            .overlay(RoundedRectangle(cornerRadius: 20 * s).stroke(Theme.line, lineWidth: 1.5))
            .padding(.horizontal, 24 * s)
            .foregroundStyle(Theme.ink)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func tile(_ seat: Int, on: Bool) -> some View {
        let shape = RoundedRectangle(cornerRadius: 12 * s)
        return VStack(spacing: 3 * s) {
            SeatAvatar(avatar: table.players.avatar(of: seat), color: seatColor(seat), size: 22)
            Text(displayName(seat))
                .font(Theme.font(11 * s, .bold))
                .foregroundStyle(Theme.ink)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .padding(6 * s)
        .frame(maxWidth: .infinity)
        .background(on ? Theme.chip : Theme.surface, in: shape)
        .overlay(shape.stroke(on ? seatColor(seat) : Theme.line, lineWidth: on ? 2 : 1.5))
        .animation(.easeOut(duration: 0.2), value: on)
        .accessibilityElement(children: .combine)
    }

    private var gameOver: some View {
        let winner = model.state.winner?.intValue
        let title: String = if let winner, table.mySeat == winner {
            String(localized: "You win!", bundle: .app)
        } else if let winner {
            String(localized: "\(table.name(winner)) wins", bundle: .app)
        } else {
            String(localized: "Perang kapal", bundle: .app)
        }
        return ZStack {
            Color.black.opacity(0.28).ignoresSafeArea()
            VStack(spacing: 12 * s) {
                Text(title).font(Theme.font(22 * s, .black))
                Text("Fleet sunk")
                    .font(Theme.font(14 * s, .bold))
                    .foregroundStyle(Theme.muted)
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

private struct PerangKapalFrameReader: ViewModifier {
    let space: String
    @Binding var frame: CGRect

    func body(content: Content) -> some View {
        content.onGeometryChange(for: CGRect.self) { $0.frame(in: .named(space)) } action: { frame = $0 }
    }
}

/// "How to play". Same lines as Android's rules sheet; the game has no options in v1.
struct PerangKapalRulesSheet: View {
    @Environment(\.dismiss) private var dismiss

    private let lines: [LocalizedStringKey] = [
        "Each side has five ships: lengths 5, 4, 3, 3 and 2 on a 10×10 grid.",
        "Place every ship before firing. Ships are straight, on the board, and may touch.",
        "Players take turns firing one shot. A hit does not grant another shot.",
        "Sink the whole enemy fleet to win.",
    ]

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

private struct PerangKapalBoardContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
