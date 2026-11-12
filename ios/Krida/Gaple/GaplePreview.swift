import RealityKit
import Shared
import SwiftUI

/// The menu card: an upright [6|6] with two tiles laid against it on the felt. Drawn once with
/// RealityKit, snapshotted and shown as an image, like `PerangKapalPreview`. Same picture as
/// Android's `GaplePreview`.
struct GaplePreview: View {
    var onReady: () -> Void = {}
    @State private var image: UIImage?
    @State private var renderer: GapleRenderer?
    @State private var frame: CGRect = .zero
    @MainActor private static var cached: (size: CGSize, frame: CGRect, image: UIImage)?

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .topLeading) {
                if let image {
                    Image(uiImage: image).resizable()
                        .frame(width: frame.width, height: frame.height)
                        .offset(x: frame.minX, y: frame.minY)
                } else if let renderer {
                    GaplePreviewContainer(view: renderer.view)
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
        let board = renderer ?? GapleRenderer()
        let line = [GaplePlaced(left: 4, right: 6), GaplePlaced(left: 6, right: 6), GaplePlaced(left: 6, right: 3)]
        let state = GapleState(
            players: 2, hands: [[], []], handCounts: [0, 5], aside: [], line: line, origin: 1, turn: 0,
            mustPlay: nil, round: 1, scores: [0, 0], partners: false, passedOn: [[], []],
            roundOver: false, roundEnd: nil, roundWinner: nil, roundPips: [], roundPoints: [],
            finished: false, winner: nil, lastSeat: nil, lastTile: nil, lastEnd: nil, lastPasses: [], lastDraws: [],
            options: GapleOptions(partners: false, draw: false)
        )
        board.apply(state, viewer: 0, animate: false)
        // Zoomed in on the three tiles, with a little felt around them.
        board.focus = [SIMD3(-3.4, 0, -2), SIMD3(3.4, 0, 2)]
        frame = board.layout(size: size, free: CGRect(origin: .zero, size: size))
        board.view.frame = CGRect(origin: .zero, size: frame.size)
        renderer = board
        snapshot(board, size: size, tries: 4)
    }

    /// RealityKit needs a few frames on screen before the snapshot has the table in it.
    private func snapshot(_ board: GapleRenderer, size: CGSize, tries: Int) {
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            guard renderer === board else { return }
            board.view.snapshot(saveToHDR: false) { shot in
                guard renderer === board else { return }
                if let shot {
                    Self.cached = (size, frame, shot)
                    image = shot
                    onReady()
                    board.stop()
                    renderer = nil
                } else if tries > 1 {
                    snapshot(board, size: size, tries: tries - 1)
                }
            }
        }
    }
}

/// Hosts the preview's RealityKit view.
private struct GaplePreviewContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
