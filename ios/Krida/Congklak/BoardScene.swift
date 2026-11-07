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
    private var placed = false

    init() {
        view = ARView(frame: .zero, cameraMode: .nonAR, automaticallyConfigureSession: false)
        view.renderOptions = [.disableHDR, .disableMotionBlur, .disableDepthOfField, .disableGroundingShadows, .disableCameraGrain]
        let bg = Self.floats(CongklakBoard.shared.background)
        view.environment.background = .color(UIColor(red: CGFloat(bg[0]), green: CGFloat(bg[1]), blue: CGFloat(bg[2]), alpha: 1))

        let palette = Palette()
        palette.add(CongklakBoard.shared.boardMesh())
        for v in 0..<Int(CongklakBoard.shared.SEED_VARIANTS) {
            palette.add(CongklakBoard.shared.seedMesh(variant: Int32(v)))
        }
        let (descriptors, material) = palette.finish()
        let resources = descriptors.map { try! MeshResource.generate(from: [$0]) }

        anchor.addChild(ModelEntity(mesh: resources[0], materials: [material]))
        let seedResources = Array(resources.dropFirst())
        for i in 0..<Int(CongklakBoard.shared.SEED_COUNT) {
            let variant = Int(CongklakBoard.shared.seedVariant(seed: Int32(i)))
            let seed = ModelEntity(mesh: seedResources[variant], materials: [material])
            seeds.append(seed)
            anchor.addChild(seed)
        }

        let eye = Self.floats(CongklakBoard.shared.cameraEye)
        let target = Self.floats(CongklakBoard.shared.cameraTarget)
        anchor.addChild(camera)
        camera.look(at: SIMD3(target[0], target[1], target[2]), from: SIMD3(eye[0], eye[1], eye[2]), relativeTo: nil)
        view.scene.addAnchor(anchor)
    }

    /// Call when the view size changes: keeps the board's width in frame and re-projects pits.
    func layout(size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        // RealityKit's field of view is vertical; the shared camera is defined horizontally.
        // Widen it so the board fits between the safe-area insets (Dynamic Island, corners).
        let insets = view.safeAreaInsets
        let safeWidth = max(size.width - 2 * max(insets.left, insets.right), size.width * 0.5)
        let half = Float(CongklakBoard.shared.CAMERA_HORIZONTAL_FOV_DEGREES) * .pi / 360
        let tanHalf = tan(half) * Float(size.width / safeWidth)
        let aspect = Float(size.width / size.height)
        camera.camera.fieldOfViewInDegrees = 2 * atan(tanHalf / aspect) * 180 / .pi
        pitPoints = (0..<Int(CongklakGame.shared.PITS)).map { pit in
            let p = Self.floats(CongklakBoard.shared.pitAnchor(pit: Int32(pit)))
            return view.project(SIMD3(p[0], p[1], p[2])) ?? .zero
        }
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
@MainActor
private final class Palette {
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
