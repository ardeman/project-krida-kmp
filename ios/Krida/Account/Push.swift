import UIKit
import UserNotifications

/// iPhone notifications for friend invites (online step 10c-2): while the app is closed or
/// in the background, "NAME invites you to play GAME" arrives through APNs and a tap opens
/// the room. The relay sends it to the device token this phone stored with its session
/// (`POST /v1/push-token`, "Accounts" in docs/protocol.md). Android has no counterpart
/// (decision "No Firebase").
@MainActor
final class Push: NSObject, ObservableObject {
    static let shared = Push()

    /// The invite behind a tapped notification.
    struct Opened: Equatable {
        let code: String
        let invite: String
    }

    /// Set by a tap on a notification; ContentView joins the room and clears it.
    @Published var opened: Opened?

    private var deviceToken: String?

    private override init() {}

    /// At launch: takes notification taps and, when the player allowed notifications
    /// before, fetches the device token again (it can change between launches).
    func start() {
        UNUserNotificationCenter.current().delegate = self
        Task {
            let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
            if status == .authorized { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// Asks for notifications. iOS shows its question only the first time; the app calls
    /// this when a signed-in player opens Invite friends, adds a friend, sees a friend in
    /// the list or gets an invite, never at launch.
    func ask() {
        guard Account.shared.sessionToken != nil else { return }
        Task {
            let allowed = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
            if allowed { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// iOS handed over the device token.
    func registered(_ token: Data) {
        deviceToken = token.map { String(format: "%02x", $0) }.joined()
        upload()
    }

    /// Stores the device token with this session on the relay. Called again after a sign-in
    /// and a language change; the relay writes the notification in the app's language.
    func upload() {
        guard let deviceToken, let session = Account.shared.sessionToken else { return }
        #if DEBUG
        let environment = "sandbox"
        #else
        let environment = "production"
        #endif
        let body = ["token": deviceToken, "env": environment, "lang": Language.shared.code.rawValue]
        Task { _ = await Account.shared.send("POST", "/v1/push-token", token: session, body: body) }
    }

    /// The app is in front: invites show as the in-app card, so old notifications go.
    func clearDelivered() {
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
    }
}

extension Push: UNUserNotificationCenterDelegate {
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let code = info["code"] as? String, let invite = info["invite"] as? String else { return }
        await MainActor.run { opened = Opened(code: code, invite: invite) }
    }
}

/// UIKit hands the APNs device token to the app delegate only.
final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        Push.shared.start()
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        Push.shared.registered(deviceToken)
    }
}
