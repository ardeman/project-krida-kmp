import XCTest

/// End to end: a friend's invite reaches this phone as the in-app card, as a notification
/// while the app is in the background, and as one while it is closed; tapping the
/// notification joins the room. Runs on a simulator against the live relay through
/// `scripts/test-ios-invite-push.mjs`, which makes the two test accounts, hosts the rooms
/// and passes them in the environment. Skipped when run any other way.
final class InvitePushTests: XCTestCase {
    private var api = ""
    private var hostSession = ""
    private var friendId = ""
    private var hostKey = ""

    func testInviteNotifications() throws {
        let env = ProcessInfo.processInfo.environment
        guard let session = env["KRIDA_SESSION"], let hostSession = env["KRIDA_HOST_SESSION"],
              let friendId = env["KRIDA_FRIEND_ID"], let hostKey = env["KRIDA_HOST_KEY"],
              let api = env["KRIDA_API"], let rooms = env["KRIDA_ROOMS"]?.split(separator: ",").map(String.init),
              rooms.count == 3 else {
            throw XCTSkip("Run through scripts/test-ios-invite-push.mjs")
        }
        (self.api, self.hostSession, self.friendId, self.hostKey) = (api, hostSession, friendId, hostKey)
        continueAfterFailure = false
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let app = XCUIApplication()
        app.launchArguments = ["-testSession", session]
        app.launch()

        // The account screen shows a friend, so the app offers notifications: its own card
        // first, then the system question.
        let account = app.buttons["Account"]
        XCTAssertTrue(account.waitForExistence(timeout: 30), "menu")
        account.tap()
        XCTAssertTrue(app.staticTexts["Get invites from friends"].waitForExistence(timeout: 20), "the app's notification card")
        app.buttons["Allow"].tap()
        let allow = springboard.buttons["Allow"]
        XCTAssertTrue(allow.waitForExistence(timeout: 10), "the system notification question")
        allow.tap()
        // The device token goes to the relay.
        sleep(6)
        app.buttons["Back"].firstMatch.tap()

        // App in front on the menu: the invite is the in-app card.
        try invite(rooms[0])
        XCTAssertTrue(app.staticTexts["E2E Host invites you"].waitForExistence(timeout: 20), "in-app invite card")
        app.buttons["Not now"].tap()

        // App in the background: a notification; tapping it joins the room.
        XCUIDevice.shared.press(.home)
        sleep(2)
        try invite(rooms[1])
        tapNotification(springboard)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 20), "app opens from the notification")
        sleep(8)

        // App closed: the same.
        app.terminate()
        sleep(2)
        try invite(rooms[2])
        tapNotification(springboard)
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "app launches from the notification")
        sleep(10)
    }

    private func tapNotification(_ springboard: XCUIApplication) {
        let text = NSPredicate(format: "label CONTAINS[c] %@", "invites you to play")
        let banner = springboard.descendants(matching: .any).matching(text).firstMatch
        XCTAssertTrue(banner.waitForExistence(timeout: 45), "invite notification")
        banner.tap()
    }

    /// The host account invites this phone's account to `room`.
    private func invite(_ room: String) throws {
        var request = URLRequest(url: URL(string: api + "/v1/invites")!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(hostSession)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(["friendId": friendId, "code": room, "key": hostKey])
        let done = expectation(description: "invite \(room)")
        var status = 0
        var body = ""
        URLSession.shared.dataTask(with: request) { data, response, _ in
            status = (response as? HTTPURLResponse)?.statusCode ?? 0
            body = String(data: data ?? Data(), encoding: .utf8) ?? ""
            done.fulfill()
        }.resume()
        wait(for: [done], timeout: 20)
        XCTAssertEqual(status, 200, "POST /v1/invites for \(room): \(body)")
    }
}
