import Combine
import RealityKit
import Shared
import UIKit

/// Draws the shared voxel gaple table (`GapleScene` in shared/) with RealityKit: the line of
/// play on the felt, the other hands face down on the rim (face up at a round end), and dashed
/// gold rings on the open ends. Your own hand is drawn by the screen. Port of Android's
/// `GapleRenderer`.
///
/// The view is as large as the screen and is offset so the table sits in the middle of the
/// space the HUD leaves free (`viewFrame`); the field of view is chosen so the table fills it.
@MainActor
final class GapleRenderer {
    let view: ARView
    private(set) var viewFrame: CGRect = .zero

    /// How long a played tile takes to fly in.
    static let flightSeconds: Double = 0.35

    private let anchor = AnchorEntity(world: .zero)
    private let camera = PerspectiveCamera()
    private let scene = GapleScene.shared
    private let set = GapleGame.shared.SET
    private var tiles: [ModelEntity] = []
    private var backs: [ModelEntity] = []
    private var rings: [ModelEntity] = []
    /// Ring centres on the felt, for taps; nil when an end has no ring.
    private var ringAt: [SIMD3<Float>?] = [nil, nil]
    private var pulsing = false
    private var pulseTime: Float = 0

    /// A tile flying from its player's side to its place in the line.
    private struct Flight {
        let entity: ModelEntity
        let from: SIMD3<Float>
        let to: simd_float4x4
        var t: Float = 0
    }
    private var flight: Flight?

    /// Points to frame instead of the whole table (the menu card zooms on a few tiles).
    var focus: [SIMD3<Float>]?

    private var updates: Cancellable?
    private var frameWatch: Cancellable?
    private var screen: CGSize = .zero
    private var free: CGRect = .zero
    /// Tangent of half the vertical field of view in use.
    private var tanHalf: Float

    /// Face-down tiles: up to three other hands of seven.
    private static let backCount = 3 * Int(GapleGame.shared.HAND)

    init() {
        view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        view.renderOptions = [.disableHDR, .disableMotionBlur, .disableDepthOfField, .disableGroundingShadows, .disableCameraGrain]
        view.environment.background = .color(.clear)
        view.backgroundColor = .clear
        view.isOpaque = false
        view.isUserInteractionEnabled = false

        let palette = Palette()
        palette.add(scene.tableMesh())
        for t in set { palette.add(scene.tileMesh(a: t.a, b: t.b)) }
        palette.add(scene.backMesh())
        palette.add(scene.ringMesh())
        let (descriptors, material) = palette.finish()
        let resources = descriptors.map { try! MeshResource.generate(from: [$0]) }
        tanHalf = tan(scene.CAMERA_VERTICAL_FOV_DEGREES * .pi / 360)

        anchor.addChild(ModelEntity(mesh: resources[0], materials: [material]))
        for i in set.indices {
            let e = ModelEntity(mesh: resources[1 + i], materials: [material])
            e.isEnabled = false
            anchor.addChild(e)
            tiles.append(e)
        }
        let backMesh = resources[1 + set.count]
        for _ in 0..<Self.backCount {
            let e = ModelEntity(mesh: backMesh, materials: [material])
            e.isEnabled = false
            anchor.addChild(e)
            backs.append(e)
        }
        let ringMesh = resources[2 + set.count]
        for _ in 0..<2 {
            let e = ModelEntity(mesh: ringMesh, materials: [material])
            e.isEnabled = false
            anchor.addChild(e)
            rings.append(e)
        }
        anchor.addChild(camera)
        view.scene.addAnchor(anchor)
        applyCamera()
        updates = view.scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            self?.step(Float(event.deltaTime))
        }
    }

    /// Stops the per-frame work; the scene draws nothing more after this.
    func stop() {
        updates = nil
        frameWatch = nil
        flight = nil
        pulsing = false
    }

    /// Calls `done` once the scene has updated `frames` times, i.e. the table is on screen.
    func whenDrawn(frames: Int = 3, _ done: @escaping () -> Void) {
        var left = frames
        frameWatch = view.scene.subscribe(to: SceneEvents.Update.self) { [weak self] _ in
            left -= 1
            guard left == 0 else { return }
            self?.frameWatch = nil
            done()
        }
    }

    // MARK: State

    /// Shows `state` as `viewer` sees it. With `animate`, the last played tile flies in from
    /// its player's side.
    func apply(_ state: GapleState, viewer: Int, animate: Bool) {
        flight = nil
        var shown = [Bool](repeating: false, count: tiles.count)
        let line = state.line
        let layout: GapleLayout? = line.isEmpty ? nil
            : GapleBoard.shared.layout(line: line, origin: state.origin, halfWidth: GapleBoard.shared.HALF_WIDTH)
        let players = Int(state.players)
        if let layout {
            let last = lastIndex(state)
            for (i, placed) in line.enumerated() {
                let tile = GapleTile(a: min(placed.left, placed.right), b: max(placed.left, placed.right))
                guard let index = set.firstIndex(of: tile) else { continue }
                let m = Self.matrix(scene.tileMatrix(tile: tile, placed: placed, pose: layout.tiles[i], lift: 0))
                tiles[index].transform = Transform(matrix: m)
                shown[index] = true
                if animate, i == last, let seat = state.lastSeat?.intValue {
                    let side = scene.side(seat: Int32(seat), viewer: Int32(viewer), players: Int32(players))
                    let from = Self.vec(scene.handCenter(side: side))
                    flight = Flight(entity: tiles[index], from: from, to: m)
                    tiles[index].transform = Transform(matrix: Self.moved(m, SIMD3(from.x, 1.5, from.z)))
                }
            }
        }
        // Other hands on the rim: face down while the round runs, face up at its end.
        var b = 0
        for seat in 0..<players where seat != viewer {
            let side = scene.side(seat: Int32(seat), viewer: Int32(viewer), players: Int32(players))
            let hand = state.hands.indices.contains(seat) ? state.hands[seat] : []
            if state.roundOver && !hand.isEmpty {
                for (i, tile) in hand.enumerated() {
                    guard let index = set.firstIndex(of: tile), !shown[index] else { continue }
                    tiles[index].transform = Transform(matrix: Self.matrix(scene.handMatrix(side: side, index: Int32(i), count: Int32(hand.count))))
                    shown[index] = true
                }
            } else {
                let count = state.handCounts.indices.contains(seat) ? state.handCounts[seat].intValue : 0
                for i in 0..<count where b < backs.count {
                    backs[b].transform = Transform(matrix: Self.matrix(scene.handMatrix(side: side, index: Int32(i), count: Int32(count))))
                    backs[b].isEnabled = true
                    b += 1
                }
            }
        }
        for i in b..<backs.count { backs[i].isEnabled = false }
        for (i, e) in tiles.enumerated() { e.isEnabled = shown[i] }

        let ends: [GapleEndPose?] = state.roundOver || layout == nil ? [nil, nil] : [layout?.left, layout?.right]
        for (end, pose) in ends.enumerated() {
            let c = pose.map { Self.vec(scene.ringCenter(end: $0)) }
            ringAt[end] = c
            if let c {
                rings[end].transform = Self.ring(c, scale: 1)
                rings[end].isEnabled = true
            } else {
                rings[end].isEnabled = false
            }
        }
    }

    /// Pulses both end rings while the player picks an end.
    func pulseEnds(_ on: Bool) {
        guard pulsing != on else { return }
        pulsing = on
        pulseTime = 0
        if !on {
            for (end, c) in ringAt.enumerated() { if let c { rings[end].transform = Self.ring(c, scale: 1) } }
        }
    }

    /// Screen point of `end`'s ring (0 left, 1 right), or nil when it has none.
    func endOnScreen(_ end: Int) -> CGPoint? {
        guard screen.width > 0, let c = ringAt[end] else { return nil }
        let p = project(c, tanV: tanHalf)
        return CGPoint(x: p.x + viewFrame.minX, y: p.y + viewFrame.minY)
    }

    private func step(_ dt: Float) {
        if var f = flight {
            f.t = min(f.t + dt / Float(Self.flightSeconds), 1)
            let e = 1 - (1 - f.t) * (1 - f.t)
            let to = SIMD3(f.to.columns.3.x, f.to.columns.3.y, f.to.columns.3.z)
            let x = f.from.x + (to.x - f.from.x) * e
            let z = f.from.z + (to.z - f.from.z) * e
            let y = 1.5 * (1 - e) + sin(e * .pi) * 0.6
            if f.t >= 1 {
                f.entity.transform = Transform(matrix: f.to)
                flight = nil
            } else {
                f.entity.transform = Transform(matrix: Self.moved(f.to, SIMD3(x, y, z)))
                flight = f
            }
        }
        if pulsing {
            pulseTime += dt
            let s = 1 + 0.12 * sin(pulseTime * 6)
            for (end, c) in ringAt.enumerated() { if let c { rings[end].transform = Self.ring(c, scale: s) } }
        }
    }

    private func lastIndex(_ state: GapleState) -> Int {
        switch state.lastEnd?.intValue {
        case Int(GapleGame.shared.LEFT): 0
        case Int(GapleGame.shared.RIGHT): state.line.count - 1
        default: Int(state.origin)
        }
    }

    // MARK: Fitting the table

    /// Fits the table into `free` (the screen without the HUD). Returns the frame the view must take.
    @discardableResult
    func layout(size: CGSize, free: CGRect) -> CGRect {
        guard size.width > 0, size.height > 0, free.width > 0, free.height > 0 else { return viewFrame }
        screen = size
        self.free = free
        applyCamera()
        let baseTan = tan(scene.CAMERA_VERTICAL_FOV_DEGREES * .pi / 360)
        let points = focus ?? scene.bounds().map(Self.vec)
        let c = CGPoint(x: size.width / 2, y: size.height / 2)
        let shot = points.map { project($0, tanV: baseTan) }
        let halfW = max(shot.map { abs($0.x - c.x) }.max() ?? 1, 1)
        let halfH = max(shot.map { abs($0.y - c.y) }.max() ?? 1, 1)
        // Scaling the picture by k around the centre is the same as dividing the tangent by k.
        let k = min(free.width / 2 / halfW, free.height / 2 / halfH)
        tanHalf = baseTan / Float(k)
        camera.camera.fieldOfViewInDegrees = 2 * atan(tanHalf) * 180 / .pi
        viewFrame = CGRect(x: free.midX - c.x, y: free.midY - c.y, width: size.width, height: size.height)
        return viewFrame
    }

    /// Screen point of `p` inside the view (which is as large as the screen).
    private func project(_ p: SIMD3<Float>, tanV: Float) -> CGPoint {
        let eye = Self.vec(scene.cameraEye)
        let fwd = simd_normalize(Self.vec(scene.cameraTarget) - eye)
        let right = simd_normalize(simd_cross(fwd, SIMD3(0, 1, 0)))
        let up = simd_cross(right, fwd)
        let d = p - eye
        let z = simd_dot(d, fwd)
        let aspect = Float(screen.width / screen.height)
        let x = simd_dot(d, right) / (z * tanV * aspect)
        let y = simd_dot(d, up) / (z * tanV)
        return CGPoint(x: screen.width / 2 * CGFloat(1 + x), y: screen.height / 2 * CGFloat(1 - y))
    }

    private func applyCamera() {
        camera.look(at: Self.vec(scene.cameraTarget), from: Self.vec(scene.cameraEye), relativeTo: nil)
    }

    // MARK: Matrices

    /// A shared column-major 4 × 4 matrix.
    static func matrix(_ a: KotlinFloatArray) -> simd_float4x4 {
        func col(_ i: Int32) -> SIMD4<Float> {
            SIMD4(a.get(index: i * 4), a.get(index: i * 4 + 1), a.get(index: i * 4 + 2), a.get(index: i * 4 + 3))
        }
        return simd_float4x4(columns: (col(0), col(1), col(2), col(3)))
    }

    private static func moved(_ m: simd_float4x4, _ p: SIMD3<Float>) -> simd_float4x4 {
        var out = m
        out.columns.3 = SIMD4(p.x, p.y, p.z, 1)
        return out
    }

    private static func ring(_ c: SIMD3<Float>, scale s: Float) -> Transform {
        Transform(scale: SIMD3(s, 1, s), rotation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1), translation: SIMD3(c.x, 0, c.z))
    }

    static func vec(_ a: KotlinFloatArray) -> SIMD3<Float> {
        SIMD3(a.get(index: 0), a.get(index: 1), a.get(index: 2))
    }
}
