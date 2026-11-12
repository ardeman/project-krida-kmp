import RealityKit
import Shared
import SwiftUI

/// The real ludo board as a still picture, for the menu's game card: zoomed in on one
/// corner: the first colour's yard and the start of its track, with one pawn out. Drawn once with RealityKit, snapshotted and shown as an
/// image, like `UlarTanggaPreview`. Same framing as Android's `LudoPreview`.
struct LudoPreview: View {
    var onReady: () -> Void = {}
    @State private var image: UIImage?
    @State private var scene: LudoScene?
    @State private var frame: CGRect = .zero
    @MainActor private static var cached: (size: CGSize, frame: CGRect, image: UIImage)?

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                if let image {
                    Image(uiImage: image).resizable()
                        .frame(width: frame.width, height: frame.height)
                        .offset(x: frame.minX, y: frame.minY)
                } else if let scene {
                    LudoPreviewContainer(view: scene.view)
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
        let board = scene ?? LudoScene()
        let colors = Int(LudoGame.shared.COLORS)
        let pawns = Int(LudoGame.shared.PAWNS)
        board.setSeats(Array(0..<colors))
        for color in 0..<colors {
            for pawn in 0..<pawns {
                let spot = LudoScene.vec(LudoBoard.shared.tokenAnchor(
                    color: Int32(color), pawn: Int32(pawn), pos: LudoGame.shared.YARD))
                board.place(color, pawn, spot)
            }
        }
        // One pawn already out on the track.
        board.place(0, 0, LudoScene.vec(LudoBoard.shared.tokenAnchor(color: 0, pawn: 0, pos: 2)))
        // Zoomed in on one corner: the first colour's yard and the start of its track.
        let focus = (0..<pawns).map {
            LudoScene.vec(LudoBoard.shared.tokenAnchor(color: 0, pawn: Int32($0), pos: LudoGame.shared.YARD))
        } + (0...5).map { LudoScene.vec(LudoBoard.shared.tokenAnchor(color: 0, pawn: 0, pos: Int32($0))) }
        frame = board.layout(size: size, room: CGRect(origin: .zero, size: size), focus: focus)
        board.view.frame = CGRect(origin: .zero, size: frame.size)
        scene = board
        snapshot(board, size: size, tries: 4)
    }

    /// RealityKit needs a few frames on screen before the snapshot has the board in it.
    private func snapshot(_ board: LudoScene, size: CGSize, tries: Int) {
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
private struct LudoPreviewContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
