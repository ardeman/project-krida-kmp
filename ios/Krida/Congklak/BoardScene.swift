import Combine
import RealityKit
import Shared
import SwiftUI
import UIKit

/// Draws the shared voxel congklak board (`CongklakBoard` in shared/) with RealityKit.
///
/// The shared meshes carry baked per-vertex colors. RealityKit's built-in materials don't read
/// vertex colors on iOS 17, so every distinct color becomes one texel of a small palette
/// texture and each vertex points at its texel. One unlit material draws everything.
@MainActor
final class BoardScene {
    let view: ARView
    /// Pit anchors projected to view points; refreshed by `layout()`.
    private(set) var pitPoints: [CGPoint] = []

    private let anchor = AnchorEntity(world: .zero)
    private let camera = PerspectiveCamera()
    private var seeds: [ModelEntity] = []
    private var frameWatch: Cancellable?

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

    /// Gold rings marking playable holes, keyed by pit.
    private var rings: [Int: ModelEntity] = [:]
    private var placed = false
    private var updates: Cancellable?
    private var viewSize: CGSize = .zero
    private var baseFov: Float = 0
    private var targetFov: Float = 0
    private var currentLook = SIMD3<Float>.zero
    private var targetLook = SIMD3<Float>.zero
    private var cameraEye = SIMD3<Float>.zero
    private var camSettled = true
    /// Notified when pit projections change after a camera ease.
    var onPitsProjected: (([CGPoint]) -> Void)?

    init() {
        view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        view.renderOptions = [.disableHDR, .disableMotionBlur, .disableDepthOfField, .disableGroundingShadows, .disableCameraGrain]
        // Transparent: the screen's backdrop gradient (SwiftUI) shows around the board.
        view.environment.background = .color(.clear)
        view.backgroundColor = .clear
        view.isOpaque = false

        let palette = Palette()
        palette.add(CongklakBoard.shared.boardMesh())
        palette.add(CongklakBoard.shared.ringMesh())
        for v in 0..<Int(CongklakBoard.shared.SEED_VARIANTS) {
            palette.add(CongklakBoard.shared.seedMesh(variant: Int32(v)))
        }
        let (descriptors, material) = palette.finish()
        let resources = descriptors.map { try! MeshResource.generate(from: [$0]) }

        anchor.addChild(ModelEntity(mesh: resources[0], materials: [material]))
        for pit in 0..<Int(CongklakGame.shared.PITS) where !CongklakGame.shared.isStore(pit: Int32(pit)) {
            let ring = ModelEntity(mesh: resources[1], materials: [material])
            let o = Self.floats(CongklakBoard.shared.ringOrigin(pit: Int32(pit)))
            ring.position = SIMD3(o[0], o[1], o[2])
            ring.isEnabled = false
            rings[pit] = ring
            anchor.addChild(ring)
        }
        let seedResources = Array(resources.dropFirst(2))
        for i in 0..<Int(CongklakBoard.shared.SEED_COUNT) {
            let variant = Int(CongklakBoard.shared.seedVariant(seed: Int32(i)))
            let seed = ModelEntity(mesh: seedResources[variant], materials: [material])
            seeds.append(seed)
            anchor.addChild(seed)
        }

        anchor.addChild(camera)
        aimCamera()
        view.scene.addAnchor(anchor)
        updates = view.scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            self?.stepCamera(Float(event.deltaTime))
        }
    }

    /// The seat on the near side of the board (0 or 1). Call `layout()` after changing it.
    var viewSeat: Int32 = 0 {
        didSet { aimCamera(); if viewSize.width > 0 { layout(size: viewSize) } }
    }

    private func aimCamera() {
        // Seat 1 looks from the other long side: turn the camera 180 degrees around the y axis.
        let flip: Float = viewSeat == 1 ? -1 : 1
        let eye = Self.floats(CongklakBoard.shared.cameraEye)
        let target = Self.floats(CongklakBoard.shared.cameraTarget)
        cameraEye = SIMD3(eye[0] * flip, eye[1], eye[2] * flip)
        currentLook = SIMD3(target[0] * flip, target[1], target[2] * flip)
        targetLook = currentLook
        camera.look(at: currentLook, from: cameraEye, relativeTo: nil)
    }

    /**
     * Gameplay: ease the camera onto `points` (the pit being sown), or back to the whole board
     * when nil.
     */
    func watch(_ points: [SIMD3<Float>]?) {
        guard viewSize.width > 0, baseFov > 0 else { return }
        if let points, !points.isEmpty {
            let pad: Float = 1.1
            let x0 = points.map(\.x).min()! - pad, x1 = points.map(\.x).max()! + pad
            let z0 = points.map(\.z).min()! - pad, z1 = points.map(\.z).max()! + pad
            let y = points.map(\.y).reduce(0, +) / Float(points.count)
            targetLook = SIMD3((x0 + x1) / 2, y, (z0 + z1) / 2)
            targetFov = max(baseFov * 0.55, 18)
        } else {
            let flip: Float = viewSeat == 1 ? -1 : 1
            let target = Self.floats(CongklakBoard.shared.cameraTarget)
            targetLook = SIMD3(target[0] * flip, target[1], target[2] * flip)
            targetFov = baseFov
        }
        camSettled = false
    }

    /// Call when the view size changes: keeps the board's width in frame and re-projects pits.
    func layout(size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        viewSize = size
        // RealityKit's field of view is vertical; the shared camera is defined horizontally.
        // Widen it so the board fits between the safe-area insets (Dynamic Island, corners).
        // An inset on one side only is a corner punch hole (see `PunchHole`): the board's
        // ends sit at mid-height, clear of it, so the board keeps the full width.
        let insets = view.safeAreaInsets
        let side = insets.left > 0 && insets.right > 0 ? max(insets.left, insets.right) : 0
        let safeWidth = max(size.width - 2 * side, size.width * 0.5)
        let half = Float(CongklakBoard.shared.CAMERA_HORIZONTAL_FOV_DEGREES) * .pi / 360
        let tanHalf = tan(half) * Float(size.width / safeWidth)
        let aspect = Float(size.width / size.height)
        baseFov = 2 * atan(tanHalf / aspect) * 180 / .pi
        if camSettled {
            targetFov = baseFov
            camera.camera.fieldOfViewInDegrees = baseFov
        }
        projectPits()
    }

    private func stepCamera(_ dt: Float) {
        guard !camSettled else { return }
        let k = 1 - exp(-min(dt, 0.05) / Self.cameraTau)
        currentLook += (targetLook - currentLook) * k
        let fov = camera.camera.fieldOfViewInDegrees + (targetFov - camera.camera.fieldOfViewInDegrees) * k
        camera.camera.fieldOfViewInDegrees = fov
        camera.look(at: currentLook, from: cameraEye, relativeTo: nil)
        projectPits()
        let done = abs(targetFov - fov) < 0.05 && length(targetLook - currentLook) < 0.01
        if done {
            currentLook = targetLook
            camera.camera.fieldOfViewInDegrees = targetFov
            camera.look(at: currentLook, from: cameraEye, relativeTo: nil)
            camSettled = true
            projectPits()
        }
    }

    private func projectPits() {
        pitPoints = (0..<Int(CongklakGame.shared.PITS)).map { pit in
            let p = Self.floats(CongklakBoard.shared.pitAnchor(pit: Int32(pit)))
            return view.project(SIMD3(p[0], p[1], p[2])) ?? .zero
        }
        onPitsProjected?(pitPoints)
    }

    private static let cameraTau: Float = 0.28

    /// Shows gold rings around exactly these holes.
    func showRings(_ pits: Set<Int>) {
        for (pit, ring) in rings { ring.isEnabled = pits.contains(pit) }
    }

    /// `targets` holds x, y, z per seed (`CongklakSeeds.targets()`). The first call places
    /// seeds directly; later calls glide them.
    func setSeedTargets(_ targets: KotlinFloatArray) {
        for (i, seed) in seeds.enumerated() {
            let pos = SIMD3(targets.get(index: Int32(i * 3)), targets.get(index: Int32(i * 3 + 1)), targets.get(index: Int32(i * 3 + 2)))
            let yaw = CongklakBoard.shared.yaw(seed: Int32(i))
            let transform = Transform(scale: .one, rotation: simd_quatf(angle: yaw, axis: [0, 1, 0]), translation: pos)
            if !placed {
                seed.transform = transform
            } else if seed.transform.translation != pos {
                seed.move(to: transform, relativeTo: anchor, duration: 0.16, timingFunction: .easeOut)
            }
        }
        placed = true
    }

    static func floats(_ a: KotlinFloatArray) -> [Float] {
        (0..<Int(a.size)).map { a.get(index: Int32($0)) }
    }
}

/// Collects the distinct vertex colors of several meshes into a 1-pixel-high texture.
/// Shared by every voxel scene (congklak, ular tangga).
@MainActor
final class Palette {
    private var index: [UInt32: Int] = [:]
    private var colors: [UInt32] = []
    private var pending: [(descriptor: MeshDescriptor, texel: [Int])] = []

    /// Converts a shared mesh. Texture coordinates are set in `finish()`, once the palette
    /// width is known.
    func add(_ mesh: VoxelMesh) {
        let pos = BoardScene.floats(mesh.positionArray())
        let rgb = BoardScene.floats(mesh.colorArray())
        let idx = mesh.indexArray()
        let count = Int(mesh.vertexCount)

        var positions = [SIMD3<Float>]()
        positions.reserveCapacity(count)
        var texel = [Int]()
        texel.reserveCapacity(count)
        for v in 0..<count {
            positions.append(SIMD3(pos[v * 3], pos[v * 3 + 1], pos[v * 3 + 2]))
            func byte(_ c: Float) -> UInt32 { UInt32(max(0, min(255, (c * 255).rounded()))) }
            let key = byte(rgb[v * 3]) << 16 | byte(rgb[v * 3 + 1]) << 8 | byte(rgb[v * 3 + 2])
            if let i = index[key] {
                texel.append(i)
            } else {
                index[key] = colors.count
                texel.append(colors.count)
                colors.append(key)
            }
        }

        // Both windings: the voxel faces aren't consistently wound and iOS 17's unlit
        // material can't turn off culling.
        var triangles = [UInt32]()
        triangles.reserveCapacity(Int(idx.size) * 2)
        for t in stride(from: 0, to: Int(idx.size), by: 3) {
            let a = UInt32(idx.get(index: Int32(t))), b = UInt32(idx.get(index: Int32(t + 1))), c = UInt32(idx.get(index: Int32(t + 2)))
            triangles += [a, b, c, a, c, b]
        }

        var d = MeshDescriptor()
        d.positions = MeshBuffers.Positions(positions)
        d.primitives = .triangles(triangles)
        pending.append((d, texel))
    }

    /// The meshes in the order they were added, and the one material that draws them all.
    func finish() -> ([MeshDescriptor], UnlitMaterial) {
        let width = Float(max(colors.count, 1))
        let descriptors = pending.map { item in
            var d = item.descriptor
            d.textureCoordinates = MeshBuffers.TextureCoordinates(item.texel.map { SIMD2((Float($0) + 0.5) / width, 0.5) })
            return d
        }
        var material = UnlitMaterial()
        if let texture = makeTexture() {
            material.color = .init(tint: .white, texture: .init(texture))
        }
        return (descriptors, material)
    }

    private func makeTexture() -> TextureResource? {
        let width = max(colors.count, 1)
        var pixels = [UInt8](repeating: 255, count: width * 4)
        for (i, c) in colors.enumerated() {
            pixels[i * 4] = UInt8(c >> 16 & 0xff)
            pixels[i * 4 + 1] = UInt8(c >> 8 & 0xff)
            pixels[i * 4 + 2] = UInt8(c & 0xff)
        }
        guard let provider = CGDataProvider(data: Data(pixels) as CFData),
              let image = CGImage(
                  width: width, height: 1, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                  space: CGColorSpace(name: CGColorSpace.sRGB)!,
                  bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                  provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
              )
        else { return nil }
        return try? TextureResource.generate(from: image, options: .init(semantic: .color, mipmapsMode: .none))
    }
}
