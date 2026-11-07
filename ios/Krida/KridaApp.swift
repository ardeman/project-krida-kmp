import SwiftUI
import Shared

@main
struct KridaApp: App {
    @State private var roomCode = ""
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView(roomCode: $roomCode)
                // QR join links: krida://join?room=ABCD
                .onOpenURL { url in
                    if let code = RoomCode.shared.parse(text: url.absoluteString) {
                        roomCode = code
                    }
                }
                .onChange(of: scenePhase, initial: true) { _, phase in Sounds.shared.setActive(phase == .active) }
        }
    }
}
