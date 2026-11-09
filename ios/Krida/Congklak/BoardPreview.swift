import RealityKit
import Shared
import SwiftUI

/// The real voxel board as a still picture, for the menu's congklak card: the start position
/// from the near side, cropped to the left end (your store and the first holes). It is drawn
/// once with RealityKit, snapshotted, and then shown as an image, so the menu doesn't keep a
/// second renderer running. The snapshot is kept for the next visit at the same size.
/// `onReady` is called once the snapshot is shown.
struct BoardPreview: View {
    var onReady: () -> Void = {}
    @State private var image: UIImage?
    @State private var scene: BoardScene?

    /// How wide the rendered game view is, in card heights: the board then fills about the
    /// card's height and only its left end shows.
    private static let widthPerHeight: CGFloat = 4.4
    @MainActor private static var cached: (size: CGSize, image: UIImage)?

    var body: some View {
        GeometryReader { geo in
            let render = CGSize(width: geo.size.height * Self.widthPerHeight, height: geo.size.height)
            ZStack(alignment: .leading) {
                if let image {
                    Image(uiImage: image).resizable().frame(width: render.width, height: render.height)
                } else if let scene {
                    PreviewContainer(view: scene.view).frame(width: render.width, height: render.height)
                }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .leading)
            .clipped()
            .onAppear { draw(render) }
            .onChange(of: render) { _, size in draw(size) }
        }
        .background(Theme.backdropGradient)
        .accessibilityHidden(true)
    }

    private func draw(_ size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        if let cached = Self.cached, cached.size == size {
            image = cached.image
            onReady()
            return
        }
        image = nil
        let board = scene ?? BoardScene()
        let start = CongklakGame.shared.initialState(players: 2, random: KotlinRandom.companion)
        board.setSeedTargets(CongklakSeeds(pits: start.pits).targets())
        board.view.frame = CGRect(origin: .zero, size: size)
        board.layout(size: size)
        scene = board
        snapshot(board, size: size, tries: 4)
    }

    /// RealityKit needs a few frames on screen before the snapshot has the board in it.
    private func snapshot(_ board: BoardScene, size: CGSize, tries: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            guard scene === board else { return }
            board.view.snapshot(saveToHDR: false) { shot in
                guard scene === board else { return }
                if let shot {
                    Self.cached = (size, shot)
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
private struct PreviewContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
