import RealityKit
import Shared
import SwiftUI

/// The real ular tangga board as a still picture, for the menu's game card: zoomed in on the
/// first rows, with ladder 4→25 and snake 27→5 both whole (as Android). Drawn once with RealityKit, snapshotted and shown as an image, like `BoardPreview`.
struct UlarTanggaPreview: View {
    var onReady: () -> Void = {}
    @State private var image: UIImage?
    @State private var scene: UlarTanggaScene?
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
                    PreviewContainer(view: scene.view)
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
        let board = scene ?? UlarTanggaScene()
        let pieces = Int(UlarTanggaBoard.shared.PIECES)
        board.setSeats(Array(0..<pieces))
        let pawns = (0..<pieces).map { UlarTanggaScene.vec(UlarTanggaBoard.shared.pieceSpot(square: 0, seat: Int32($0), shared: true)) }
        for (seat, spot) in pawns.enumerated() { board.place(seat, spot) }
        let squares = [4, 5, 25, 27].map { UlarTanggaScene.vec(UlarTanggaBoard.shared.squareAnchor(square: Int32($0))) }
        frame = board.layout(size: size, room: CGRect(origin: .zero, size: size), focus: squares)
        board.view.frame = CGRect(origin: .zero, size: frame.size)
        scene = board
        snapshot(board, size: size, tries: 4)
    }

    /// RealityKit needs a few frames on screen before the snapshot has the board in it.
    private func snapshot(_ board: UlarTanggaScene, size: CGSize, tries: Int) {
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
private struct PreviewContainer: UIViewRepresentable {
    let view: ARView
    func makeUIView(context: Context) -> ARView { view }
    func updateUIView(_ uiView: ARView, context: Context) {}
}
