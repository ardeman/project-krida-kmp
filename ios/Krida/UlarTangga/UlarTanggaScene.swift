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
    /// Vertical field of view of the screen-sized picture, before the view is enlarged.
    private var tanHalf: Float = 0

    /// Fits the whole board into `room` (in the screen's coordinates), as large as fits and
    /// centered in it, or, with `focus`, frames those points (x, y, z) to fill most of `room`.
    /// Returns the frame the view must take.
    @discardableResult
    func layout(size: CGSize, room: CGRect, focus: [SIMD3<Float>]? = nil) -> CGRect {
        guard size.width > 0, size.height > 0, room.width > 0, room.height > 0 else { return viewFrame }
        screen = size
        let base = tan(board.CAMERA_VERTICAL_FOV_DEGREES * .pi / 360)
        let points = focus ?? board.bounds().map { Self.vec($0) }
        let shot = points.map { project($0, tanV: base, size: size) }
        let x0 = shot.map(\.x).min()!, x1 = shot.map(\.x).max()!
        let y0 = shot.map(\.y).min()!, y1 = shot.map(\.y).max()!
        let fill: CGFloat = focus == nil ? 1 : 0.8
        let k = fill * min(room.width / (x1 - x0), room.height / (y1 - y0))
        tanHalf = base / Float(k)
        // Scaling is about the screen's center; then shift the picture's middle to the room's.
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let dx = room.midX - (c.x + ((x0 + x1) / 2 - c.x) * k)
        let dy = room.midY - (c.y + ((y0 + y1) / 2 - c.y) * k)
        let w = size.width + 2 * abs(dx), h = size.height + 2 * abs(dy)
        viewFrame = CGRect(x: c.x + dx - w / 2, y: c.y + dy - h / 2, width: w, height: h)
        // RealityKit's field of view is vertical and spans the whole (enlarged) view.
        camera.camera.fieldOfViewInDegrees = 2 * atan(tanHalf * Float(h / size.height)) * 180 / .pi
        updateHome()
        return viewFrame
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
        let to: SIMD3<Float>
        let q0: simd_quatf
        let q1: simd_quatf
        let axis: SIMD3<Float>
        let turns: Int
        let seconds: Float
        /// Arc height of each bounce, and its share of the time.
        let heights: [Float]
        let shares: [Float]
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
    private var idleTime: Float = 0
    private var rockAmt: Float = 0

    /// Where the die waits between rolls, in the screen's coordinates: its center and size.
    /// It is drawn there, in front of the board, so it reads as part of the HUD.
    var dieSpot: (center: CGPoint, size: CGFloat)? {
        didSet { updateHome() }
    }

    /// Throws the die from the screen onto the board so it lands showing `value`; returns how
    /// long until it rests, in seconds. It stays on the board until `recallDie`.
    func throwDie(_ value: Int, toss: Toss) -> Float {
        shown = drawnPos
        dieRot = drawnRot
        rockAmt = 0
        dieValue = value
        let area = Self.floats(board.dieLanding)
        let long = toss == .long
        // A tap lands on the near (right) side of the board, a throw carries across it.
        let midX = (area[0] + area[2]) / 2
        let tx = long ? area[0] + .random(in: 0...1) * (midX - area[0]) : midX + .random(in: 0...1) * (area[2] - midX)
        let tz = area[1] + .random(in: 0...1) * (area[3] - area[1])
        let dx = tx - shown.x, dz = tz - shown.z
        let len = max(hypot(dx, dz), 0.01)
        let heights: [Float] = long ? [2.2, 0.9, 0.3] : [1.2, 0.4]
        let weights = heights.map { $0.squareRoot() }
        let total = weights.reduce(0, +)
        let yaw = (Float(Int.random(in: 0..<4)) * 90 + (.random(in: 0...1) - 0.5) * 40) * .pi / 180
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
            shares: weights.map { $0 / total }
        )
        dieMode = .thrown
        dieShadow.isEnabled = true
        return seconds
    }

    /// Lifts the die off the board back to its place on screen, still showing its value.
    func recallDie() {
        guard dieMode == .landed else { return }
        flight = Flight(from: shown, to: home, q0: dieRot, q1: Self.homeRotation(dieValue),
                        axis: [1, 0, 0], turns: 0, seconds: 0.45, heights: [0], shares: [1])
        dieMode = .returning
    }

    private func stepDie(_ dt: Float) {
        if var f = flight {
            f.elapsed += dt
            let k = min(f.elapsed / f.seconds, 1)
            if dieMode == .returning {
                let e = 1 - (1 - k) * (1 - k)
                shown = f.from + (f.to - f.from) * e
                dieRot = simd_slerp(f.q0, f.q1, e)
            } else {
                // Across, slowing down; down in bounces, the first falling from the screen.
                let glide = 1 - (1 - k) * (1 - k)
                shown.x = f.from.x + (f.to.x - f.from.x) * glide
                shown.z = f.from.z + (f.to.z - f.from.z) * glide
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
                }
                idleTime = 0
            }
            applyDie()
            return
        }
        guard dieMode == .home else { return }
        shown = home
        rockAmt = Self.approach(rockAmt, dieReady ? 1 : 0, dt, 0.15)
        if dieReady || rockAmt > 0.002 { idleTime += dt }
        applyDie()
    }

    /// Exponential ease of `value` toward `target` with time constant `tau` seconds.
    private static func approach(_ value: Float, _ target: Float, _ dt: Float, _ tau: Float) -> Float {
        let v = target + (value - target) * exp(-dt / tau)
        return abs(v - target) < 0.001 ? target : v
    }

    /// Sets the die and its shadow; it rocks while it waits on screen.
    private func applyDie() {
        var q = dieRot
        if dieMode == .home && rockAmt > 0 {
            q = simd_quatf(angle: rockAmt * 0.18 * sin(idleTime * 2 * .pi / 1.6), axis: [0, 1, 0]) * q
        }
        drawnRot = q
        drawnPos = shown
        die.transform = Transform(scale: .one, rotation: q, translation: shown)
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
        let depth = board.DIE * (h / 2) / (tanV * Float(spot.size))
        home = eye + (fwd + right * nx * tanH + up * ny * tanV) * depth
        if dieMode == .home {
            shown = home
            applyDie()
        }
        die.isEnabled = true
    }

    // MARK: Frame step

    private func step(_ dt: Float) {
        let dt = min(dt, 0.05)
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
