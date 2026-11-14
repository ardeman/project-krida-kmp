import Combine
import RealityKit
import Shared
import UIKit

/// Draws the shared voxel perang kapal grids (`PerangKapalBoard` in shared/) with RealityKit:
/// your ocean while placing, then the radar to fire at with your ocean small beside it. Port of Android's
/// `PerangKapalRenderer`. Nothing animates, so there is no per-frame work.
///
/// The view is as large as the screen and is offset so the board sits in the middle of the
/// space the HUD leaves free (`viewFrame`); the field of view is chosen so the grids fill it.
@MainActor
final class PerangKapalScene {
    let view: ARView
    /// Where the view goes, in the screen's coordinates; set by `layout`.
    private(set) var viewFrame: CGRect = .zero
    /// Notified when `viewFrame` changes without a `layout` call (placing turns into battle).
    var onViewFrame: ((CGRect) -> Void)?

    private let anchor = AnchorEntity(world: .zero)
    private let camera = PerspectiveCamera()
    private let board = PerangKapalBoard.shared
    private let material: UnlitMaterial
    /// Ship meshes by kind and direction: `kind * 2`, plus 1 when it runs up the grid.
    private let shipMeshes: [MeshResource]
    private let hitMesh: MeshResource
    private let missMesh: MeshResource
    /// The ocean and everything on it; shrunk and moved aside in battle.
    private let ocean = Entity()
    private let radar: ModelEntity
    private let oceanRing: ModelEntity
    private let radarRing: ModelEntity
    private var myShips: [ModelEntity] = []
    private var sunkShips: [ModelEntity] = []
    private var oceanHits: [ModelEntity] = []
    private var oceanMisses: [ModelEntity] = []
    private var radarHits: [ModelEntity] = []
    private var radarMisses: [ModelEntity] = []
    /// Cell 0 of the ocean: the ship, peg and ring meshes are built there and moved from it.
    private let base: SIMD3<Float>
    private var battle = false
    /// Ocean cell under the finger while placing; nil when none. Reuses the gold ocean ring.
    private var hoverCell: Int?
    private var frameWatch: Cancellable?
    private var screen: CGSize = .zero
    private var room: CGRect = .zero
    private var obstacles: [CGRect] = []
    /// Tangent of half the vertical field of view in use.
    private var tanHalf: Float

    init() {
        view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        view.renderOptions = [.disableHDR, .disableMotionBlur, .disableDepthOfField, .disableGroundingShadows, .disableCameraGrain]
        view.environment.background = .color(.clear)
        view.backgroundColor = .clear
        view.isOpaque = false
        view.isUserInteractionEnabled = false

        let palette = Palette()
        palette.add(board.gridMesh(originX: 0))
        palette.add(board.gridMesh(originX: board.RADAR_OFFSET_X))
        palette.add(board.pegMesh(cell: 0, hit: true, originX: 0))
        palette.add(board.pegMesh(cell: 0, hit: false, originX: 0))
        palette.add(board.ringMesh(cell: 0, originX: 0))
        let kinds = Int(PerangKapalGame.shared.KINDS)
        for kind in 0..<kinds {
            palette.add(board.shipMesh(kind: Int32(kind), horizontal: true))
            palette.add(board.shipMesh(kind: Int32(kind), horizontal: false))
        }
        let (descriptors, material) = palette.finish()
        let resources = descriptors.map { try! MeshResource.generate(from: [$0]) }
        self.material = material
        hitMesh = resources[2]
        missMesh = resources[3]
        shipMeshes = Array(resources[5...])
        base = Self.vec(board.cellCenter(index: 0, originX: 0))
        tanHalf = tan(board.CAMERA_VERTICAL_FOV_DEGREES * .pi / 360)

        let radar = ModelEntity(mesh: resources[1], materials: [material])
        let oceanRing = ModelEntity(mesh: resources[4], materials: [material])
        let radarRing = ModelEntity(mesh: resources[4], materials: [material])
        self.radar = radar
        self.oceanRing = oceanRing
        self.radarRing = radarRing
        radar.isEnabled = false
        oceanRing.isEnabled = false
        radarRing.isEnabled = false
        ocean.addChild(ModelEntity(mesh: resources[0], materials: [material]))
        ocean.addChild(oceanRing)
        anchor.addChild(ocean)
        anchor.addChild(radar)
        anchor.addChild(radarRing)
        for _ in 0..<kinds {
            let mine = ModelEntity(mesh: shipMeshes[0], materials: [material])
            let sunk = ModelEntity(mesh: shipMeshes[0], materials: [material])
            mine.isEnabled = false
            sunk.isEnabled = false
            ocean.addChild(mine)
            anchor.addChild(sunk)
            myShips.append(mine)
            sunkShips.append(sunk)
        }
        anchor.addChild(camera)
        view.scene.addAnchor(anchor)
        applyCamera()
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

    // MARK: State

    /// Shows `state` as `mySeat` sees it: own ships and incoming shots on the ocean; own shots
    /// and the opponent's sunk ships on the radar. A gold ring marks the last shot.
    func apply(_ state: PerangKapalState, mySeat: Int) {
        let foe = 1 - mySeat
        setBattle(!state.placing)
        let radarX = board.RADAR_OFFSET_X
        let mine = state.fleets.indices.contains(mySeat) ? state.fleets[mySeat] : []
        let sunk = battle && state.fleets.indices.contains(foe) ? state.fleets[foe] : []
        show(mine, on: myShips, originX: 0)
        show(sunk, on: sunkShips, originX: radarX)

        // A peg on a ship stands on its deck, or the hull would hide it.
        let incoming = state.shots.indices.contains(foe) ? state.shots[foe] : []
        let outgoing = state.shots.indices.contains(mySeat) ? state.shots[mySeat] : []
        let onOcean = Set(mine.flatMap { $0.cells.map(\.intValue) })
        let onRadar = Set(sunk.flatMap { $0.cells.map(\.intValue) })
        func pegs(_ shots: [PerangKapalShot], hit: Bool, _ originX: Float, _ decks: Set<Int>) -> [SIMD3<Float>] {
            shots.filter { $0.hit == hit }.map { spot(Int($0.cell), originX, lift: decks.contains(Int($0.cell))) }
        }
        fill(&oceanHits, hitMesh, pegs(incoming, hit: true, 0, onOcean), in: ocean)
        fill(&oceanMisses, missMesh, pegs(incoming, hit: false, 0, onOcean), in: ocean)
        fill(&radarHits, hitMesh, pegs(outgoing, hit: true, radarX, onRadar), in: anchor)
        fill(&radarMisses, missMesh, pegs(outgoing, hit: false, radarX, onRadar), in: anchor)

        radarRing.isEnabled = false
        if battle, state.lastHit != nil, let cell = state.lastCell?.intValue, let seat = state.lastSeat?.intValue {
            hoverCell = nil
            oceanRing.isEnabled = false
            let ring = seat == mySeat ? radarRing : oceanRing
            ring.position = spot(cell, seat == mySeat ? radarX : 0)
            ring.isEnabled = true
        } else {
            radarRing.isEnabled = false
            applyHoverRing()
        }
    }

    /// Gold ring on the ocean cell under the finger while placing, so a mistap next to a ship
    /// is obvious (tap a ship cell to turn it). Cleared when `cell` is nil or in battle.
    func setHover(_ cell: Int?) {
        let next = battle ? nil : cell
        guard hoverCell != next else { return }
        hoverCell = next
        applyHoverRing()
    }

    private func applyHoverRing() {
        guard !battle, let cell = hoverCell else {
            oceanRing.isEnabled = false
            return
        }
        oceanRing.position = spot(cell, 0)
        oceanRing.isEnabled = true
    }

    /// One entity per ship kind: the mesh for the ship's direction, moved to its first cell.
    private func show(_ fleet: [PerangKapalShip], on entities: [ModelEntity], originX: Float) {
        entities.forEach { $0.isEnabled = false }
        for ship in fleet {
            let kind = Int(ship.kind)
            guard entities.indices.contains(kind) else { continue }
            entities[kind].model?.mesh = shipMeshes[kind * 2 + (ship.horizontal ? 0 : 1)]
            entities[kind].position = spot(Int(ship.bow), originX)
            entities[kind].isEnabled = true
        }
    }

    /// Where a mesh built on cell 0 of the ocean goes to sit on `cell` of the grid at `originX`.
    private func spot(_ cell: Int, _ originX: Float, lift: Bool = false) -> SIMD3<Float> {
        let c = Self.vec(board.cellCenter(index: Int32(cell), originX: originX))
        return SIMD3(c.x - base.x, lift ? board.SHIP_H : 0, c.z - base.z)
    }

    /// Shows one entity of `pool` at each of `spots`, growing the pool as needed.
    private func fill(_ pool: inout [ModelEntity], _ mesh: MeshResource, _ spots: [SIMD3<Float>], in parent: Entity) {
        while pool.count < spots.count {
            let entity = ModelEntity(mesh: mesh, materials: [material])
            parent.addChild(entity)
            pool.append(entity)
        }
        for (i, entity) in pool.enumerated() {
            entity.isEnabled = i < spots.count
            if i < spots.count { entity.position = spots[i] }
        }
    }

    private func setBattle(_ on: Bool) {
        guard battle != on else { return }
        battle = on
        radar.isEnabled = on
        // In battle the ocean is only looked at: small, left of the radar.
        ocean.scale = SIMD3(repeating: on ? board.MINI_SCALE : 1)
        ocean.position = on ? Self.vec(board.miniOffset) : .zero
        applyLayout()
    }

    // MARK: Fitting the board

    /// Points (x, y, z) to fit instead of the whole grid, for the menu preview. The fit is
    /// centred on the camera's target, so keep them around the grid's centre.
    var focus: [SIMD3<Float>]?

    /// Fits the play grid into `room` as large as ludo does, dodging `obstacles` (HUD boxes).
    /// In battle the radar clears obstacles; the mini ocean only has to stay in the room.
    @discardableResult
    func layout(size: CGSize, room: CGRect, obstacles: [CGRect] = []) -> CGRect {
        guard size.width > 0, size.height > 0, room.width > 0, room.height > 0 else { return viewFrame }
        screen = size
        self.room = room
        self.obstacles = obstacles
        return applyLayout()
    }

    @discardableResult
    private func applyLayout() -> CGRect {
        applyCamera()
        guard screen.width > 0, room.width > 0 else { return viewFrame }
        let baseTan = tan(board.CAMERA_VERTICAL_FOV_DEGREES * .pi / 360)
        let play = focus ?? board.bounds(originX: battle ? board.RADAR_OFFSET_X : 0).map(Self.vec)
        let c = CGPoint(x: screen.width / 2, y: screen.height / 2)
        let playShot = play.map { project($0, tanV: baseTan) }
        let roomShot: [CGPoint] = if battle, focus == nil {
            playShot + board.miniBounds().map { project(Self.vec($0), tanV: baseTan) }
        } else {
            playShot
        }
        func place(_ k: CGFloat) -> CGPoint? {
            func scaled(_ pts: [CGPoint]) -> [CGPoint] {
                pts.map { CGPoint(x: c.x + ($0.x - c.x) * k, y: c.y + ($0.y - c.y) * k) }
            }
            let roomPts = scaled(roomShot)
            let x0 = roomPts.map(\.x).min()!, x1 = roomPts.map(\.x).max()!
            let y0 = roomPts.map(\.y).min()!, y1 = roomPts.map(\.y).max()!
            let loX = room.minX - x0, hiX = room.maxX - x1
            let loY = room.minY - y0, hiY = room.maxY - y1
            guard loX <= hiX, loY <= hiY else { return nil }
            let playPts = scaled(playShot)
            let mid = CGPoint(x: (loX + hiX) / 2, y: (loY + hiY) / 2)
            var best: CGPoint?
            var bestD = CGFloat.greatestFiniteMagnitude
            for i in 0...Self.fitSteps {
                for j in 0...Self.fitSteps {
                    let d = CGPoint(
                        x: loX + (hiX - loX) * CGFloat(i) / CGFloat(Self.fitSteps),
                        y: loY + (hiY - loY) * CGFloat(j) / CGFloat(Self.fitSteps)
                    )
                    let dist = (d.x - mid.x) * (d.x - mid.x) + (d.y - mid.y) * (d.y - mid.y)
                    guard dist < bestD else { continue }
                    let px0 = playPts.map(\.x).min()! + d.x, px1 = playPts.map(\.x).max()! + d.x
                    let py0 = playPts.map(\.y).min()! + d.y, py1 = playPts.map(\.y).max()! + d.y
                    let playBox = CGRect(x: px0, y: py0, width: px1 - px0, height: py1 - py0)
                    if obstacles.contains(where: { $0.intersects(playBox) }) { continue }
                    best = d
                    bestD = dist
                }
            }
            return best
        }
        var lo = log(0.05), hi = log(20.0)
        for _ in 0..<20 {
            let mid = (lo + hi) / 2
            if place(exp(mid)) != nil { lo = mid } else { hi = mid }
        }
        let k = exp(lo)
        let shift = place(k) ?? .zero
        tanHalf = baseTan / Float(k)
        camera.camera.fieldOfViewInDegrees = 2 * atan(tanHalf) * 180 / .pi
        viewFrame = CGRect(x: shift.x, y: shift.y, width: screen.width, height: screen.height)
        onViewFrame?(viewFrame)
        return viewFrame
    }

    private static let fitSteps = 24

    /// The cell of the radar (in battle) or of the ocean (while placing) under `point`, in
    /// the screen's coordinates.
    func pickCell(at point: CGPoint, onRadar: Bool) -> Int? {
        guard screen.width > 0, onRadar == battle else { return nil }
        let originX = onRadar ? board.RADAR_OFFSET_X : 0
        let local = CGPoint(x: point.x - viewFrame.minX, y: point.y - viewFrame.minY)
        func center(_ cell: Int) -> CGPoint {
            project(Self.vec(board.cellCenter(index: Int32(cell), originX: originX)), tanV: tanHalf)
        }
        // Within three quarters of a cell of its centre, so a tap between the grids is ignored.
        let a = center(0), b = center(1)
        var bestDistance = hypot(b.x - a.x, b.y - a.y) * 0.75
        var best: Int?
        for cell in 0..<Int(PerangKapalGame.shared.CELLS) {
            let p = center(cell)
            let d = hypot(p.x - local.x, p.y - local.y)
            if d < bestDistance {
                bestDistance = d
                best = cell
            }
        }
        return best
    }

    /// Screen point of `p` inside the view (which is as large as the screen).
    private func project(_ p: SIMD3<Float>, tanV: Float) -> CGPoint {
        let eye = eyePosition()
        let fwd = simd_normalize(target() - eye)
        let right = simd_normalize(simd_cross(fwd, SIMD3(0, 1, 0)))
        let up = simd_cross(right, fwd)
        let d = p - eye
        let z = simd_dot(d, fwd)
        let aspect = Float(screen.width / screen.height)
        let x = simd_dot(d, right) / (z * tanV * aspect)
        let y = simd_dot(d, up) / (z * tanV)
        return CGPoint(x: screen.width / 2 * CGFloat(1 + x), y: screen.height / 2 * CGFloat(1 - y))
    }

    /// In battle the camera looks at the radar.
    private var lookX: Float { battle ? board.RADAR_OFFSET_X : 0 }
    private func eyePosition() -> SIMD3<Float> { Self.vec(board.cameraEye) + SIMD3(lookX, 0, 0) }
    private func target() -> SIMD3<Float> { Self.vec(board.cameraTarget) + SIMD3(lookX, 0, 0) }

    private func applyCamera() {
        camera.look(at: target(), from: eyePosition(), relativeTo: nil)
    }

    static func vec(_ a: KotlinFloatArray) -> SIMD3<Float> {
        SIMD3(a.get(index: 0), a.get(index: 1), a.get(index: 2))
    }
}
