import Combine
import RealityKit
import Shared
import UIKit

/// Draws the shared voxel ular tangga board (`UlarTanggaBoard` in shared/) with RealityKit.
/// Pawns move along paths given to `move` (a hop per square, a slide down a snake, a climb up
/// a ladder). The die waits on screen at `dieSpot`; `throwDie` throws it onto the board to land
/// on a value and `recallDie` brings it back. Port of Android's `UlarTanggaRenderer`.
///
/// RealityKit's camera has no projection shift, so the board is moved on screen by making the
/// view larger than the screen and offsetting it (`viewFrame`): the same picture as a shifted
/// projection, cropped by the screen.
@MainActor
final class UlarTanggaScene {
    let view: ARView
    /// Where the view goes, in the screen's coordinates; set by `layout`.
    private(set) var viewFrame: CGRect = .zero

    private let anchor = AnchorEntity(world: .zero)
    private let camera = PerspectiveCamera()
    private let board = UlarTanggaBoard.shared
    private var byColor: [ModelEntity] = []
    private var seatColors: [Int] = []
    private var positions: [SIMD3<Float>]
    private var motions: [Motion?]
    private let die: ModelEntity
    private let dieShadow: ModelEntity
    private var updates: Cancellable?
    private var frameWatch: Cancellable?

    /// Called each time a thrown die lands on the board.
    var onDieBounce: (() -> Void)?

    /// When true and the die waits on screen, it rocks gently to say it can be tapped.
    var dieReady = false {
        didSet { if dieReady != oldValue { idleTime = 0 } }
    }

    init() {
        view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        view.renderOptions = [.disableHDR, .disableMotionBlur, .disableDepthOfField, .disableGroundingShadows, .disableCameraGrain]
        // Transparent: the screen's backdrop gradient (SwiftUI) shows around the board.
        view.environment.background = .color(.clear)
        view.backgroundColor = .clear
        view.isOpaque = false
        view.isUserInteractionEnabled = false

        let pieces = Int(board.PIECES)
        let palette = Palette()
        palette.add(board.boardMesh())
        for color in 0..<pieces { palette.add(board.pieceMesh(color: Int32(color))) }
        palette.add(board.dieShadowMesh())
        palette.add(board.dieMesh())
        let (descriptors, material) = palette.finish()
        let resources = descriptors.map { try! MeshResource.generate(from: [$0]) }

        anchor.addChild(ModelEntity(mesh: resources[0], materials: [material]))
        for color in 0..<pieces {
            let pawn = ModelEntity(mesh: resources[1 + color], materials: [material])
            byColor.append(pawn)
            anchor.addChild(pawn)
        }
        dieShadow = ModelEntity(mesh: resources[1 + pieces], materials: [material])
        die = ModelEntity(mesh: resources[2 + pieces], materials: [material])
        anchor.addChild(dieShadow)
        anchor.addChild(die)
        positions = Array(repeating: .zero, count: pieces)
        motions = Array(repeating: nil, count: pieces)
        seatColors = Array(0..<pieces)
        dieRot = Self.homeRotation(6)
        drawnRot = dieRot

        anchor.addChild(camera)
        camera.look(at: Self.vec(board.cameraTarget), from: Self.vec(board.cameraEye), relativeTo: nil)
        view.scene.addAnchor(anchor)

        // No die until the HUD gives it a spot (the menu's preview never does).
        die.isEnabled = false
        dieShadow.isEnabled = false
        updates = view.scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            self?.step(Float(event.deltaTime))
        }
    }

    /// Calls `done` once the scene has updated `frames` times, i.e. the board is on screen.
    func whenDrawn(frames: Int = 3, _ done: @escaping () -> Void) {
        var left = frames
        frameWatch = view.scene.subscribe(to: SceneEvents.Update.self) { [weak self] _ in
            left -= 1
            guard left == 0 else { return }
            self?.frameWatch = nil
            done()
        }
    }

    // MARK: Fitting the board

    private var screen: CGSize = .zero
    private var room: CGRect = .zero
    private var obstacles: [CGRect] = []
    private var menuFocus: [SIMD3<Float>]? = nil
    private var watchFocus: [SIMD3<Float>]? = nil
    private var targetFrame: CGRect = .zero
    private var targetFov: Float = 0
    private var camSettled = true
    /// Notified whenever [viewFrame] changes during a watch ease (SwiftUI offsets the view).
    var onViewFrame: ((CGRect) -> Void)?
    /// Vertical field of view of the screen-sized picture, before the view is enlarged.
    private var tanHalf: Float = 0
    /// Scale of the waiting die. Zoomed in, `home` stays at the normal-view depth (farther
    /// would put it behind the board), so the die shrinks to keep its size on screen.
    private var homeScale: Float = 1
    /// Scale of the die in flight, eased between 1 on the board and `homeScale`.
    private var flyScale: Float = 1

    /**
     * Gameplay: ease the camera onto `points` (die landing, pawn hops), or back to the whole
     * board when nil. Call before a move and again with nil when it has settled.
     */
    func watch(_ points: [SIMD3<Float>]?) {
        menuFocus = nil
        watchFocus = points.map { Self.padded($0) }
        guard screen.width > 0 else { return }
        applyLayout(snap: false)
    }

    /// Fits the whole board into `room` (in the screen's coordinates) as large as it goes
    /// without covering any of `obstacles` (HUD boxes), as near the room's middle as it can,
    /// or, with `focus`, frames those points (x, y, z) to fill most of `room` (menu preview snaps).
    /// Returns the frame the view must take.
    @discardableResult
    func layout(size: CGSize, room: CGRect, obstacles: [CGRect] = [], focus: [SIMD3<Float>]? = nil) -> CGRect {
        guard size.width > 0, size.height > 0, room.width > 0, room.height > 0 else { return viewFrame }
        screen = size
        self.room = room
        self.obstacles = obstacles
        if let focus {
            menuFocus = focus
            watchFocus = nil
        }
        return applyLayout(snap: menuFocus != nil || camSettled)
    }

    @discardableResult
    private func applyLayout(snap: Bool) -> CGRect {
        let size = screen
        let room = self.room
        let focus = menuFocus ?? watchFocus
        guard size.width > 0, size.height > 0, room.width > 0, room.height > 0 else { return viewFrame }
        let base = tan(board.CAMERA_VERTICAL_FOV_DEGREES * .pi / 360)
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        /// The whole-board fit: as large as it goes in `room` without covering `obstacles`.
        func overview() -> (CGFloat, CGFloat, CGFloat) {
            // A narrower view scales the picture about the screen's center and the view's offset
            // moves it, so the board's outline is projected once, then scaled and moved: find the
            // largest scale with a place in the room clear of the HUD, nearest the room's middle.
            // One convex outline per part (board, start pad), so the pad may sit beside a HUD box.
            let hulls = board.bounds().map { part in Self.convexHull(part.map { project(Self.vec($0), tanV: base, size: size) }) }
            func place(_ k: CGFloat) -> CGPoint? {
                let shapes = hulls.map { hull in hull.map { CGPoint(x: c.x + ($0.x - c.x) * k, y: c.y + ($0.y - c.y) * k) } }
                // Room size follows the main board only (like ludo). The start pad may tuck
                // under the left HUD; counting it in the room AABB shrank the board.
                guard let boardShape = shapes.first else { return nil }
                let x0 = boardShape.map(\.x).min()!, x1 = boardShape.map(\.x).max()!
                let y0 = boardShape.map(\.y).min()!, y1 = boardShape.map(\.y).max()!
                let loX = room.minX - x0, hiX = room.maxX - x1
                let loY = room.minY - y0, hiY = room.maxY - y1
                guard loX <= hiX, loY <= hiY else { return nil }
                let mid = CGPoint(x: (loX + hiX) / 2, y: (loY + hiY) / 2)
                var best: CGPoint?
                var bestDistance = CGFloat.greatestFiniteMagnitude
                for i in 0...Self.fitSteps {
                    for j in 0...Self.fitSteps {
                        let d = CGPoint(x: loX + (hiX - loX) * CGFloat(i) / CGFloat(Self.fitSteps),
                                        y: loY + (hiY - loY) * CGFloat(j) / CGFloat(Self.fitSteps))
                        let distance = (d.x - mid.x) * (d.x - mid.x) + (d.y - mid.y) * (d.y - mid.y)
                        guard distance < bestDistance else { continue }
                        // Only the main board must clear the HUD; the start pad may sit under it.
                        if obstacles.contains(where: { Self.overlaps(boardShape, d, $0) }) { continue }
                        best = d
                        bestDistance = distance
                    }
                }
                return best
            }
            // Search the scale on a log scale; `lo` always fits (or is the smallest tried).
            var lo: CGFloat = log(0.05), hi: CGFloat = log(20)
            for _ in 0..<20 {
                let mid = (lo + hi) / 2
                if place(exp(mid)) != nil { lo = mid } else { hi = mid }
            }
            let k = exp(lo)
            let best = place(k) ?? .zero
            return (k, best.x, best.y)
        }
        var k: CGFloat
        var dx: CGFloat
        var dy: CGFloat
        if let focus, !focus.isEmpty {
            let shot = focus.map { project($0, tanV: base, size: size) }
            let x0 = shot.map(\.x).min()!, x1 = shot.map(\.x).max()!
            let y0 = shot.map(\.y).min()!, y1 = shot.map(\.y).max()!
            let spanX = max(x1 - x0, 1)
            let spanY = max(y1 - y0, 1)
            // Soft fill + a max zoom so a tight die pad cannot crop the landing off-screen.
            let raw = 0.62 * min(room.width / spanX, room.height / spanY)
            let maxK = CGFloat(base / tan(12 * Float.pi / 360))
            k = min(raw, maxK)
            // Scaling is about the screen's center; then shift the picture's middle to the room's.
            dx = room.midX - (c.x + ((x0 + x1) / 2 - c.x) * k)
            dy = room.midY - (c.y + ((y0 + y1) / 2 - c.y) * k)
            // Never zoom out past the whole-board view: a wide shot uses that view instead.
            let whole = overview()
            if k <= whole.0 { (k, dx, dy) = whole }
        } else {
            (k, dx, dy) = overview()
        }
        let nextTan = base / Float(k)
        let w = size.width + 2 * abs(dx), h = size.height + 2 * abs(dy)
        let nextFrame = CGRect(x: c.x + dx - w / 2, y: c.y + dy - h / 2, width: w, height: h)
        let nextFov = 2 * atan(nextTan * Float(h / size.height)) * 180 / .pi
        targetFrame = nextFrame
        targetFov = nextFov
        if snap {
            tanHalf = nextTan
            viewFrame = nextFrame
            camera.camera.fieldOfViewInDegrees = nextFov
            camSettled = true
            updateHome()
            onViewFrame?(viewFrame)
        } else {
            camSettled = false
        }
        return viewFrame
    }

    /// Corners around `points` so a short hop still has air to breathe.
    private static func padded(_ points: [SIMD3<Float>]) -> [SIMD3<Float>] {
        guard !points.isEmpty else { return points }
        let pad: Float = 2.8
        let x0 = points.map(\.x).min()! - pad, x1 = points.map(\.x).max()! + pad
        let z0 = points.map(\.z).min()! - pad, z1 = points.map(\.z).max()! + pad
        let y0 = points.map(\.y).min()!
        let y1 = points.map(\.y).max()! + 1.5
        return [
            SIMD3(x0, y0, z0), SIMD3(x1, y0, z0),
            SIMD3(x0, y0, z1), SIMD3(x1, y0, z1),
            SIMD3(x0, y1, z0), SIMD3(x1, y1, z0),
            SIMD3(x0, y1, z1), SIMD3(x1, y1, z1),
        ]
    }

    /// Places tried along each axis when fitting the board.
    private static let fitSteps = 24
    private static let cameraTau: Float = 0.38
    private static let maxCameraDt: Float = 1 / 30

    /// Convex outline of `points`, counter-clockwise on screen (monotone chain).
    private static func convexHull(_ points: [CGPoint]) -> [CGPoint] {
        let sorted = points.sorted { $0.x != $1.x ? $0.x < $1.x : $0.y < $1.y }
        func turn(_ o: CGPoint, _ a: CGPoint, _ b: CGPoint) -> CGFloat { (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x) }
        func chain(_ ps: [CGPoint]) -> [CGPoint] {
            var out: [CGPoint] = []
            for p in ps {
                while out.count >= 2 && turn(out[out.count - 2], out[out.count - 1], p) <= 0 { out.removeLast() }
                out.append(p)
            }
            return out.dropLast()
        }
        return chain(sorted) + chain(sorted.reversed())
    }

    /// Whether convex `shape`, moved by `d`, overlaps `box` (separating axis test).
    private static func overlaps(_ shape: [CGPoint], _ d: CGPoint, _ box: CGRect) -> Bool {
        if shape.map(\.x).max()! + d.x <= box.minX || shape.map(\.x).min()! + d.x >= box.maxX { return false }
        if shape.map(\.y).max()! + d.y <= box.minY || shape.map(\.y).min()! + d.y >= box.maxY { return false }
        let corners = [CGPoint(x: box.minX, y: box.minY), CGPoint(x: box.maxX, y: box.minY),
                       CGPoint(x: box.maxX, y: box.maxY), CGPoint(x: box.minX, y: box.maxY)]
        for i in shape.indices {
            let a = shape[i], b = shape[(i + 1) % shape.count]
            let nx = b.y - a.y, ny = a.x - b.x
            let s = shape.map { ($0.x + d.x) * nx + ($0.y + d.y) * ny }
            let q = corners.map { $0.x * nx + $0.y * ny }
            if s.max()! <= q.min()! || q.max()! <= s.min()! { return false }
        }
        return true
    }

    /// Screen point of `p` for a camera at the board's eye with vertical half-angle tangent `tanV`.
    private func project(_ p: SIMD3<Float>, tanV: Float, size: CGSize) -> CGPoint {
        let (fwd, right, up) = Self.basis()
        let eye = Self.vec(board.cameraEye)
        let d = p - eye
        let z = simd_dot(d, fwd)
        let aspect = Float(size.width / size.height)
        let x = simd_dot(d, right) / (z * tanV * aspect)
        let y = simd_dot(d, up) / (z * tanV)
        return CGPoint(x: size.width / 2 * CGFloat(1 + x), y: size.height / 2 * CGFloat(1 - y))
    }

    private static func basis() -> (SIMD3<Float>, SIMD3<Float>, SIMD3<Float>) {
        let eye = vec(UlarTanggaBoard.shared.cameraEye)
        let at = vec(UlarTanggaBoard.shared.cameraTarget)
        let fwd = simd_normalize(at - eye)
        let right = simd_normalize(simd_cross(fwd, SIMD3(0, 1, 0)))
        return (fwd, right, simd_cross(right, fwd))
    }

    // MARK: Pawns

    private struct Motion {
        let path: [SIMD3<Float>]
        let lengths: [Float]
        let seconds: Float
        let hop: Float
        var elapsed: Float = 0

        init(path: [SIMD3<Float>], seconds: Float, hop: Float) {
            self.path = path
            var l: [Float] = [0]
            for i in 1..<path.count { l.append(l[i - 1] + simd_distance(path[i - 1], path[i])) }
            lengths = l
            self.seconds = max(seconds, 0.01)
            self.hop = hop
        }
    }

    /// One seat per entry of `colors`, each moving the pawn of that color; other pawns hide.
    func setSeats(_ colors: [Int]) {
        guard colors != seatColors else { return }
        for (color, pawn) in byColor.enumerated() { pawn.isEnabled = colors.contains(color) }
        seatColors = colors
        for seat in motions.indices { motions[seat] = nil }
        for seat in colors.indices { apply(seat) }
    }

    /// Puts `seat`'s pawn at `xyz` at once.
    func place(_ seat: Int, _ xyz: SIMD3<Float>) {
        motions[seat] = nil
        positions[seat] = xyz
        apply(seat)
    }

    /// Moves `seat`'s pawn from where it is along `path` in `seconds`, at an even speed,
    /// lifting it `hop` units at the middle of the move.
    func move(_ seat: Int, _ path: [SIMD3<Float>], seconds: Float, hop: Float = 0) {
        motions[seat] = Motion(path: [positions[seat]] + path, seconds: seconds, hop: hop)
    }

    private func apply(_ seat: Int) {
        guard seat < seatColors.count else { return }
        byColor[seatColors[seat] % byColor.count].position = positions[seat]
    }

    // MARK: Die

    /// How hard a throw is: a short toss lands on the near side, a long throw carries across.
    enum Toss { case short, long }

    private enum DieMode { case home, thrown, landed, returning }

    private struct Flight {
        let from: SIMD3<Float>
        var to: SIMD3<Float>
        let q0: simd_quatf
        let q1: simd_quatf
        let axis: SIMD3<Float>
        let turns: Int
        let seconds: Float
        /// Arc height of each bounce, and its share of the time.
        let heights: [Float]
        let shares: [Float]
        /// The die's scale when the flight starts (see `homeScale`).
        var s0: Float = 1
        var elapsed: Float = 0
        var landed = 0
    }

    private var restY: Float { board.DIE_REST_Y }
    private var dieMode = DieMode.home
    private var dieValue = 6
    /// Where the die waits on screen, in world space; set from `dieSpot`.
    private var home = SIMD3<Float>(0, 20, 8)
    private var shown = SIMD3<Float>(0, 20, 8)
    private var dieRot: simd_quatf
    private var drawnRot: simd_quatf
    private var drawnPos = SIMD3<Float>(0, 20, 8)
    private var flight: Flight?
    private var pressed = false
    private var shaking = false
    private var heldOffset = CGSize.zero
    private var idleOrigin: CGPoint?
    private var shakeTime: Float = 0
    /// Radians the held die has spun; it settles back to a whole turn when the hold ends.
    private var spinAngle: Float = 0
    private var idleTime: Float = 0
    /// Eased 0...1 amounts of the press, the shake and the idle rocking, so none of them snaps.
    private var pressAmt: Float = 0
    private var shakeAmt: Float = 0
    private var rockAmt: Float = 0

    /// Where the die waits between rolls, in the screen's coordinates: its center and size.
    /// It is drawn there, in front of the board, so it reads as part of the HUD.
    var dieSpot: (center: CGPoint, size: CGFloat)? {
        didSet { updateHome() }
    }

    /// Presses the die where it waits; `shake` spins it, as in a hand, until `throwDie`.
    func holdDie(_ held: Bool, shake: Bool = false) {
        guard dieMode == .home else { return }
        if shake && !shaking { shakeTime = 0; spinAngle = 0 }
        pressed = held
        shaking = held && shake
        if !held { heldOffset = .zero }
    }

    /// Follows the finger in screen points, keeping the die inside the viewport.
    func moveHeldDie(_ offset: CGSize) {
        guard pressed, dieMode == .home, let spot = dieSpot else { return }
        let left = -spot.center.x + spot.size
        let top = -spot.center.y + spot.size
        heldOffset = CGSize(
            width: min(max(offset.width, left), max(left, screen.width - spot.center.x - spot.size)),
            height: min(max(offset.height, top), max(top, screen.height - spot.center.y - spot.size))
        )
        applyDie()
    }

    /// Throws the die from the screen onto the board so it lands showing `value`; returns how
    /// long until it rests, in seconds. A long throw flies farther, tumbles and bounces more.
    /// It stays on the board until `recallDie`.
    /// `seat` is the throwing player's seat, used to seed deterministic animation so all clients
    /// see the same trajectory and rotation.
    func throwDie(_ value: Int, toss: Toss, origin: CGPoint? = nil, seat: Int = 0) -> Float {
        pressed = false
        shaking = false
        // Leave from the pose on screen, mid-spin or not.
        shown = drawnPos
        heldOffset = .zero
        dieRot = drawnRot
        // Opponents throw from their player tile, at the same depth as the held die.
        if let origin { shown = diePosition(origin) }
        pressAmt = 0; shakeAmt = 0; rockAmt = 0; spinAngle = 0
        dieValue = value
        let long = toss == .long

        // Compute deterministic landing position and rotation from seat and roll value.
        // This ensures all clients show the same animation without protocol changes.
        let animation = UlarTanggaDiceAnimation.companion.compute(seat: Int32(seat), value: Int32(value), isLongToss: long)
        let tx = animation.landX
        let tz = animation.landZ
        let yaw = animation.spinYaw

        let dx = tx - shown.x, dz = tz - shown.z
        let len = max(hypot(dx, dz), 0.01)
        let heights: [Float] = long ? [2.2, 0.9, 0.3] : [1.2, 0.4]
        let weights = heights.map { $0.squareRoot() }
        let total = weights.reduce(0, +)
        let seconds: Float = long ? 1.5 : 1.0
        flight = Flight(
            from: shown,
            to: SIMD3(tx, restY, tz),
            q0: dieRot,
            q1: simd_quatf(angle: yaw, axis: [0, 1, 0]) * Self.quat(board.dieRest(value: Int32(value))),
            // Rolling forward: about the level axis square to the travel.
            axis: SIMD3(dz / len, 0, -dx / len),
            turns: long ? 4 : 2,
            seconds: seconds,
            heights: heights,
            shares: weights.map { $0 / total },
            s0: homeScale
        )
        flyScale = homeScale
        dieMode = .thrown
        dieShadow.isEnabled = true
        return seconds
    }

    private func diePosition(_ origin: CGPoint?) -> SIMD3<Float> {
        guard let origin, let spot = dieSpot else { return home }
        let (_, right, up) = Self.basis()
        let units = board.DIE * homeScale / Float(spot.size)
        return home + (right * Float(origin.x - spot.center.x) - up * Float(origin.y - spot.center.y)) * units
    }

    /// Returns to the next player's box, or the local control when `origin` is nil.
    func recallDie(origin: CGPoint? = nil) {
        idleOrigin = origin
        switch dieMode {
        case .home:
            shown = diePosition(idleOrigin)
            applyDie()
        case .returning:
            if let f = flight {
                flight = Flight(from: shown, to: diePosition(idleOrigin), q0: dieRot, q1: f.q1,
                                axis: f.axis, turns: 0, seconds: max(0.15, f.seconds - f.elapsed),
                                heights: [0], shares: [1], s0: flyScale)
            }
        case .thrown, .landed:
            // Bring home even if the throw frame hasn't flipped to landed yet (delay race).
            flight = Flight(from: shown, to: diePosition(idleOrigin), q0: dieRot, q1: Self.homeRotation(dieValue),
                            axis: [1, 0, 0], turns: 0, seconds: 0.45, heights: [0], shares: [1], s0: flyScale)
            dieMode = .returning
        }
    }

    private func stepDie(_ dt: Float) {
        if var f = flight {
            f.elapsed += dt
            let k = min(f.elapsed / f.seconds, 1)
            if dieMode == .returning {
                let e = 1 - (1 - k) * (1 - k)
                shown = f.from + (f.to - f.from) * e
                dieRot = simd_slerp(f.q0, f.q1, e)
                flyScale = f.s0 + (homeScale - f.s0) * e
            } else {
                // Across, slowing down; down in bounces, the first falling from the screen.
                let glide = 1 - (1 - k) * (1 - k)
                shown.x = f.from.x + (f.to.x - f.from.x) * glide
                shown.z = f.from.z + (f.to.z - f.from.z) * glide
                flyScale = f.s0 + (1 - f.s0) * glide
                var start: Float = 0
                for i in f.shares.indices {
                    let end = start + f.shares[i]
                    if k <= end || i == f.shares.count - 1 {
                        let u = min(max((k - start) / f.shares[i], 0), 1)
                        let base = i == 0 ? f.from.y + (restY - f.from.y) * u * u : restY
                        shown.y = base + 4 * f.heights[i] * u * (1 - u)
                        while f.landed < i { f.landed += 1; onDieBounce?() }
                        break
                    }
                    start = end
                }
                // Tumble: whole turns about the roll axis, unwinding onto the resting face.
                let settle = min(1, k / 0.9)
                let angle = -Float(f.turns) * 2 * .pi * (1 - settle) * (1 - settle)
                dieRot = simd_quatf(angle: angle, axis: f.axis) * simd_slerp(f.q0, f.q1, settle)
            }
            flight = f
            if k >= 1 {
                flight = nil
                shown = f.to
                dieRot = f.q1
                if dieMode == .returning {
                    dieMode = .home
                    dieShadow.isEnabled = false
                } else {
                    for _ in f.landed..<f.shares.count { onDieBounce?() }
                    dieMode = .landed
                    flyScale = 1
                }
                idleTime = 0
            }
            applyDie()
            return
        }
        guard dieMode == .home else { return }
        shown = diePosition(idleOrigin)
        // Ease toward the targets: quick to press, a little slower to build up a shake.
        pressAmt = Self.approach(pressAmt, pressed ? 1 : 0, dt, 0.06)
        shakeAmt = Self.approach(shakeAmt, shaking ? 1 : 0, dt, shaking ? 0.18 : 0.08)
        rockAmt = Self.approach(rockAmt, dieReady && !pressed ? 1 : 0, dt, 0.15)
        if shaking || shakeAmt > 0.002 { shakeTime += dt }
        let turn = 2 * Float.pi
        if shaking {
            // Spin up from about one turn a second to three over the first moment of the hold.
            spinAngle += dt * turn * shakeAmt * (1 + 2 * min(1, shakeTime / 0.8))
        } else if spinAngle != 0 {
            // Let go without a throw: finish the turn it is on and come to rest.
            let rest = (spinAngle / turn).rounded(.up) * turn
            spinAngle = Self.approach(spinAngle, rest, dt, 0.12)
            if spinAngle == rest { spinAngle = 0 }
        }
        if dieReady || rockAmt > 0.002 { idleTime += dt }
        applyDie()
    }

    /// Exponential ease of `value` toward `target` with time constant `tau` seconds.
    private static func approach(_ value: Float, _ target: Float, _ dt: Float, _ tau: Float) -> Float {
        let v = target + (value - target) * exp(-dt / tau)
        return abs(v - target) < 0.001 ? target : v
    }

    /// Sets the die and its shadow: pressed, spun or rocking while it waits on screen.
    private func applyDie() {
        var q = dieRot
        var pos = shown
        var scale = flyScale
        if dieMode == .home {
            if let spot = dieSpot {
                let (_, right, up) = Self.basis()
                let units = board.DIE * homeScale / Float(spot.size)
                pos += (right * Float(heldOffset.width) - up * Float(heldOffset.height)) * units
            }
            if spinAngle != 0 {
                // Spinning in the air before the throw: whole turns about a tilted axis that
                // slowly sweeps round, so every face flashes past.
                let p = shakeTime * 1.3
                let axis = simd_normalize(SIMD3<Float>(0.7 * sin(p), 0.6, 0.7 * cos(p)))
                q = simd_quatf(angle: spinAngle, axis: axis) * q
            }
            if shakeAmt > 0 {
                // A small toss up and down while it spins.
                let s = shakeTime * 2 * .pi
                let bob = board.DIE * homeScale * 0.12 * shakeAmt
                pos.x += bob * 0.5 * sin(s * 2.3)
                pos.y += bob * abs(sin(s * 3.1))
            }
            if rockAmt > 0 {
                q = simd_quatf(angle: rockAmt * 0.18 * sin(idleTime * 2 * .pi / 1.6), axis: [0, 1, 0]) * q
            }
            scale = homeScale * (1 - 0.1 * pressAmt)
        }
        drawnRot = q
        drawnPos = pos
        die.transform = Transform(scale: SIMD3(repeating: scale), rotation: q, translation: pos)
        // The shadow on the tiles shrinks as the die rises.
        let sc = min(max(1 - (shown.y - restY) * 0.08, 0.3), 1)
        dieShadow.transform = Transform(scale: SIMD3(sc, 1, sc), rotation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1), translation: SIMD3(shown.x, board.TOP, shown.z))
    }

    /// Puts `home` on the line of sight through `dieSpot`, at the depth that gives the die its size.
    private func updateHome() {
        guard let spot = dieSpot, viewFrame.width > 0, screen.height > 0 else { return }
        let (fwd, right, up) = Self.basis()
        let eye = Self.vec(board.cameraEye)
        // In the enlarged view: same angle per point as the screen-sized picture.
        let w = Float(viewFrame.width), h = Float(viewFrame.height)
        let tanV = tanHalf * h / Float(screen.height)
        let tanH = tanV * w / h
        let nx = Float(spot.center.x - viewFrame.minX) * 2 / w - 1
        let ny = 1 - Float(spot.center.y - viewFrame.minY) * 2 / h
        // A narrower (zoomed-in) FOV would need the die farther away, past the board; keep the
        // normal-view depth and shrink the die instead.
        homeScale = min(1, tanHalf / tan(board.CAMERA_VERTICAL_FOV_DEGREES * .pi / 360))
        let depth = board.DIE * (h / 2) / (tanV * Float(spot.size)) * homeScale
        home = eye + (fwd + right * nx * tanH + up * ny * tanV) * depth
        if dieMode == .home {
            shown = diePosition(idleOrigin)
            applyDie()
        } else if dieMode == .returning, flight != nil {
            // Follow the spot while the camera eases, so the die lands where it is drawn.
            flight?.to = diePosition(idleOrigin)
        }
        die.isEnabled = true
    }

    // MARK: Frame step

    private func step(_ dt: Float) {
        let dt = min(dt, 0.05)
        stepCamera(dt)
        stepDie(dt)
        for seat in motions.indices {
            guard var m = motions[seat] else { continue }
            m.elapsed += dt
            let t = min(m.elapsed / m.seconds, 1)
            let total = m.lengths.last!
            let at = t * total
            var i = 1
            while i < m.path.count - 1 && m.lengths[i] < at { i += 1 }
            let a = m.path[i - 1], b = m.path[i]
            let span = m.lengths[i] - m.lengths[i - 1]
            let u = span <= 0 ? 1 : min(max((at - m.lengths[i - 1]) / span, 0), 1)
            var p = a + (b - a) * u
            p.y += m.hop * 4 * t * (1 - t)
            positions[seat] = p
            apply(seat)
            motions[seat] = t >= 1 ? nil : m
        }
    }

    /// Ease the view frame and FOV toward the watch target.
    private func stepCamera(_ dt: Float) {
        guard !camSettled, screen.width > 0 else { return }
        let k = 1 - exp(-min(dt, Self.maxCameraDt) / Self.cameraTau)
        let from = viewFrame
        let to = targetFrame
        let next = CGRect(
            x: from.minX + (to.minX - from.minX) * CGFloat(k),
            y: from.minY + (to.minY - from.minY) * CGFloat(k),
            width: from.width + (to.width - from.width) * CGFloat(k),
            height: from.height + (to.height - from.height) * CGFloat(k)
        )
        let fov = camera.camera.fieldOfViewInDegrees + (targetFov - camera.camera.fieldOfViewInDegrees) * k
        viewFrame = next
        camera.camera.fieldOfViewInDegrees = fov
        tanHalf = tan(fov * .pi / 360) * Float(screen.height / max(next.height, 1))
        updateHome()
        onViewFrame?(viewFrame)
        let done = abs(to.minX - next.minX) < 0.5 && abs(to.minY - next.minY) < 0.5
            && abs(to.width - next.width) < 0.5 && abs(targetFov - fov) < 0.05
        if done {
            viewFrame = to
            camera.camera.fieldOfViewInDegrees = targetFov
            tanHalf = tan(targetFov * .pi / 360) * Float(screen.height / max(to.height, 1))
            camSettled = true
            updateHome()
            onViewFrame?(viewFrame)
        }
    }

    // MARK: Helpers

    /// The die as it waits on screen: `value` up, turned to show two more faces.
    private static func homeRotation(_ value: Int) -> simd_quatf {
        simd_quatf(angle: 0.2, axis: [1, 0, 0]) * simd_quatf(angle: 0.6, axis: [0, 1, 0]) * quat(UlarTanggaBoard.shared.dieRest(value: Int32(value)))
    }

    /// Shared quaternions are x, y, z, w.
    static func quat(_ a: KotlinFloatArray) -> simd_quatf {
        simd_quatf(ix: a.get(index: 0), iy: a.get(index: 1), iz: a.get(index: 2), r: a.get(index: 3))
    }

    static func vec(_ a: KotlinFloatArray) -> SIMD3<Float> {
        SIMD3(a.get(index: 0), a.get(index: 1), a.get(index: 2))
    }

    static func floats(_ a: KotlinFloatArray) -> [Float] {
        (0..<Int(a.size)).map { a.get(index: Int32($0)) }
    }
}
