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

    /// The app's own question is up (`PushAskLayer`): Not now or Allow.
    @Published private(set) var asking = false

    private var deviceToken: String?
    /// The player answered Not now; only their own tap on a friend or invite screen asks again.
    private static let laterKey = "push.later"

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

    /// Offers notifications to a signed-in player who was never asked by iOS: the app's own
    /// card first, then the system question. Called right after sign-in, when a friend shows
    /// in the list and after an invite was answered Not now; after a Not now only `byPlayer` asks again
    /// (the player added a friend or opened Invite friends). Never at launch.
    func ask(byPlayer: Bool = false) {
        guard Account.shared.sessionToken != nil, !asking else { return }
        guard byPlayer || !UserDefaults.standard.bool(forKey: Self.laterKey) else { return }
        Task {
            switch await UNUserNotificationCenter.current().notificationSettings().authorizationStatus {
            case .notDetermined: asking = true
            case .authorized: UIApplication.shared.registerForRemoteNotifications()
            default: break
            }
        }
    }

    /// A sign-in: the question may come again, then asks.
    func signedIn() {
        UserDefaults.standard.removeObject(forKey: Self.laterKey)
        ask()
    }

    /// Allow on the app's card: iOS asks, and with a yes the phone registers.
    func allow() {
        asking = false
        Task {
            let allowed = (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
            if allowed { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// Not now on the app's card: iOS stays unasked.
    func later() {
        asking = false
        UserDefaults.standard.set(true, forKey: Self.laterKey)
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
