import SwiftUI
import Shared

@main
struct KridaApp: App {
    @StateObject private var model = RoomModel()

    var body: some Scene {
        WindowGroup {
            ContentView(model: model)
                // QR join links: krida://join?room=ABCD
                .onOpenURL { url in
                    if let code = RoomCode.shared.parse(text: url.absoluteString) {
                        model.roomCode = code
                    }
                }
        }
    }
}
