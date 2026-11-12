import XCTest

/// End to end: two simulators share a Nearby Wi‑Fi room (Bonjour `_krida._tcp`).
/// Run through `scripts/test-ios-lan.mjs` (host and guest roles, coordinated via temp files).
final class LanRoomTests: XCTestCase {
    private let codeFile = "/tmp/krida-lan-e2e-code"
    private let okFile = "/tmp/krida-lan-e2e-ok"

    func testLanHost() throws {
        try run(role: "host")
    }

    func testLanGuest() throws {
        try run(role: "guest")
    }

    private func run(role: String) throws {
        let env = ProcessInfo.processInfo.environment
        guard env["KRIDA_LAN_ROLE"] == role else {
            throw XCTSkip("Run through scripts/test-ios-lan.mjs (role \(role))")
        }
        continueAfterFailure = false
        let app = XCUIApplication()
        var args = ["-skipAds"]
        if let host = env["KRIDA_E2E_LAN_HOST"], let port = env["KRIDA_E2E_LAN_PORT"] {
            args += ["-e2eLanHost", host, "-e2eLanPort", port]
        }
        app.launchArguments = args
        app.launch()
        waitForMenu(app)

        app.buttons["Nearby"].firstMatch.tap()
        XCTAssertTrue(app.buttons["Wi‑Fi"].waitForExistence(timeout: 5), "Link control")
        app.buttons["Wi‑Fi"].firstMatch.tap()
        XCTAssertTrue(
            app.staticTexts["Wi‑Fi or hotspot, no internet"].waitForExistence(timeout: 5),
            "Wi‑Fi detail on play card",
        )

        if role == "host" {
            try host(app)
        } else {
            try guest(app)
        }
    }

    private func host(_ app: XCUIApplication) throws {
        try? FileManager.default.removeItem(atPath: codeFile)
        try? FileManager.default.removeItem(atPath: okFile)

        app.buttons["Create room"].firstMatch.tap()
        allowSystemAlerts()
        let lobby = app.staticTexts["Room code"].waitForExistence(timeout: 20)
            || app.staticTexts["Nearby · Wi‑Fi, no internet"].waitForExistence(timeout: 1)
            || app.staticTexts["Players · 1 of 2"].waitForExistence(timeout: 1)
        if !lobby {
            XCTFail("lobby missing after Create room\n\(app.debugDescription)")
        }
        XCTAssertTrue(
            app.staticTexts["Nearby · Wi‑Fi, no internet"].waitForExistence(timeout: 5),
            "Wi‑Fi place line",
        )

        let code = roomCode(from: app)
        XCTAssertEqual(code.count, 4, "nearby code \(code)")
        try code.write(toFile: codeFile, atomically: true, encoding: .utf8)

        // Stay in the lobby until the guest confirms it joined (don't tear down first).
        XCTAssertTrue(waitForText(app, "Players · 2 of 2", timeout: 90), "guest in lobby")
        XCTAssertTrue(app.buttons["Start"].isEnabled, "ready to start")
        XCTAssertTrue(waitForFileExists(okFile, timeout: 30), "guest reached You're in")
    }

    private func guest(_ app: XCUIApplication) throws {
        let code = try waitForFile(codeFile, timeout: 90)
        app.buttons["Join"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Type the code"].waitForExistence(timeout: 10), "join screen")

        let field = app.textFields["Room code from your friend"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "code field")
        field.tap()
        field.typeText(code)

        allowSystemAlerts()
        app.buttons["Join"].firstMatch.tap()
        allowSystemAlerts()

        let joined = app.staticTexts["You're in"].waitForExistence(timeout: 60)
            || app.staticTexts["Waiting for the host to start…"].waitForExistence(timeout: 1)
            || app.staticTexts["Players · 2 of 2"].waitForExistence(timeout: 1)
        if !joined {
            XCTFail("guest lobby missing after Join\n\(app.debugDescription)")
        }
        try "ok".write(toFile: okFile, atomically: true, encoding: .utf8)
    }

    private func waitForMenu(_ app: XCUIApplication) {
        // Loading cover, then the play card (Congklak is the default game).
        XCTAssertTrue(app.buttons["Nearby"].waitForExistence(timeout: 60), "menu play card")
    }

    private func roomCode(from app: XCUIApplication) -> String {
        // Host lobby exposes the code as spaced letters for VoiceOver ("A B C D").
        let label = NSPredicate(format: "label MATCHES %@", "[A-Z0-9]( [A-Z0-9]){3}")
        let code = app.descendants(matching: .any).matching(label).firstMatch
        XCTAssertTrue(code.waitForExistence(timeout: 10), "room code a11y label")
        return code.label.replacingOccurrences(of: " ", with: "")
    }

    private func allowSystemAlerts() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for title in ["Allow", "OK"] {
            let button = springboard.buttons[title]
            if button.waitForExistence(timeout: 2) { button.tap() }
        }
    }

    private func waitForText(_ app: XCUIApplication, _ text: String, timeout: TimeInterval) -> Bool {
        app.staticTexts[text].waitForExistence(timeout: timeout)
    }

    private func waitForFile(_ path: String, timeout: TimeInterval) throws -> String {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let text = try? String(contentsOfFile: path, encoding: .utf8), !text.isEmpty {
                return text.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            Thread.sleep(forTimeInterval: 0.4)
        }
        XCTFail("timed out waiting for \(path)")
        return ""
    }

    private func waitForFileExists(_ path: String, timeout: TimeInterval) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if FileManager.default.fileExists(atPath: path) { return true }
            Thread.sleep(forTimeInterval: 0.4)
        }
        return false
    }
}
