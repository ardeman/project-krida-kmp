import SwiftUI
import Shared

@main
struct KridaApp: App {
    @State private var roomCode = ""

    var body: some Scene {
        WindowGroup {
            ContentView(roomCode: $roomCode)
                // QR join links: krida://join?room=ABCD
                .onOpenURL { url in
                    if let code = RoomCode.shared.parse(text: url.absoluteString) {
                        roomCode = code
                    }
                }
        }
    }
}
