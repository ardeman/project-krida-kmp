import SwiftUI
import Shared

@main
struct KridaApp: App {
    @State private var roomCode = ""
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var language = Language.shared

    var body: some Scene {
        WindowGroup {
            // Every screen inside the landscape stage: it sets `uiScale` and `punchHole`.
            LandscapeStage { ContentView(roomCode: $roomCode) }
                // A game: no clock over the cards (iPad windows, the unfolded Duo).
                .statusBarHidden()
                // The app language, switched in-app without a relaunch (see Language).
                .environment(\.locale, language.locale)
                // QR join links: krida://join?room=ABCD
                .onOpenURL { url in
                    if let code = RoomCode.shared.parse(text: url.absoluteString) {
                        roomCode = code
                    }
                }
                .onAppear { Ads.shared.start() }
                .onChange(of: scenePhase, initial: true) { _, phase in
                    Sounds.shared.setActive(phase == .active)
                    if phase == .active { Ads.shared.refresh() }
                }
        }
    }
}
