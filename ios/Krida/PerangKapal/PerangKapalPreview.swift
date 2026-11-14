import RealityKit
import Shared
import SwiftUI

/// The real perang kapal ocean as a still picture, for the menu's game card: the middle of the
/// grid, zoomed in on three ships of a fleet of five. Drawn once with RealityKit, snapshotted and shown as an image, like
/// `LudoPreview`. Same picture as Android's `PerangKapalPreview`.
struct PerangKapalPreview: View {
    var onReady: () -> Void = {}
    @State private var image: UIImage?
    @State private var scene: PerangKapalScene?
    @State private var frame: CGRect = .zero
    @MainActor private static var cached: (size: CGSize, frame: CGRect, image: UIImage)?

    /// A fixed fleet (bow cell, across), one ship per kind, same as Android: the carrier,
    /// battleship and a cruiser sit near the centre, where the preview zooms in.
    private static let fleet: [(bow: Int, horizontal: Bool)] = [
        (33, true), (54, true), (42, false), (7, false), (90, true),
    ]

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                if let image {
                    Image(uiImage: image).resizable()
                        .frame(width: frame.width, height: frame.height)
                        .offset(x: frame.minX, y: frame.minY)
                } else if let scene {
                    PerangKapalPreviewContainer(view: scene.view)
                        .frame(width: frame.width, height: frame.height)
                        .offset(x: frame.minX, y: frame.minY)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .clipped()
            .onAppear { draw(geo.size) }
            .onChange(of: geo.size) { _, size in draw(size) }
        }
        .background(Theme.backdropGradient)
        .accessibilityHidden(true)
    }

    private func draw(_ size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        if let cached = Self.cached, cached.size == size {
            frame = cached.frame
            image = cached.image
            onReady()
            return
        }
        image = nil
        let board = scene ?? PerangKapalScene()
        let game = PerangKapalGame.shared
        let ships = Self.fleet.enumerated().compactMap { kind, spot -> PerangKapalShip? in
            let length = game.FLEET.get(index: Int32(kind))
            guard let cells = game.cellsFor(bow: Int32(spot.bow), length: length, horizontal: spot.horizontal) else { return nil }
            return PerangKapalShip(kind: Int32(kind), cells: cells)
        }
        let state = PerangKapalState(
            placing: true, turn: 0, fleets: [ships, []],
            ready: [KotlinBoolean(bool: false), KotlinBoolean(bool: false)],
            shots: [[], []], finished: false, winner: nil,
            lastSeat: nil, lastCell: nil, lastHit: nil, lastSunk: nil
        )
        board.apply(state, mySeat: 0)
        // Zoomed in on the middle of the grid: rows 3–6, columns 2–7.
        let cell = PerangKapalBoard.shared.CELL
        let x = 3 * cell, z = 2 * cell
        board.focus = [PerangKapalBoard.shared.TOP, PerangKapalBoard.shared.TOP + PerangKapalBoard.shared.SHIP_H].flatMap { y in
            [SIMD3(-x, 0, -z), SIMD3(x, 0, -z), SIMD3(-x, 0, z), SIMD3(x, 0, z)].map { $0 + SIMD3(0, y, 0) }
        }
        frame = board.layout(size: size, room: CGRect(origin: .zero, size: size))
        board.view.frame = CGRect(origin: .zero, size: frame.size)
        scene = board
        snapshot(board, size: size, tries: 4)
    }

    /// RealityKit needs a few frames on screen before the snapshot has the board in it.
    private func snapshot(_ board: PerangKapalScene, size: CGSize, tries: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            guard scene === board else { return }
            board.view.snapshot(saveToHDR: false) { shot in
                guard scene === board else { return }
                if let shot {
                    Self.cached = (size, frame, shot)
                    image = shot
                    onReady()
                    scene = nil
                } else if tries > 1 {
                    snapshot(board, size: size, tries: tries - 1)
                }
            }
        }
    }
}

/// Hosts the preview's RealityKit view.
private struct PerangKapalPreviewContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
