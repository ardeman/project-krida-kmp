import XCTest

/// Smoke: after the place timer hits 0, the timer-slot Autopilot button appears and tapping
/// it restores control. Run on a simulator (`xcodebuild test -scheme KridaUITests`).
final class PerangKapalAutopilotTests: XCTestCase {
    func testAutopilotButtonRestoresControl() {
        continueAfterFailure = false
        let app = XCUIApplication()
        // Opens perang kapal vs computer directly (see ContentView.onAppear).
        app.launchArguments = ["-openPerangKapal"]
        app.launch()

        let back = app.buttons["Back to menu"]
        XCTAssertTrue(back.waitForExistence(timeout: 45), "entered perang kapal")
        add(keep(XCUIScreen.main.screenshot(), name: "entered-board"))

        let auto = app.buttons["autopilotButton"]
        XCTAssertTrue(auto.waitForExistence(timeout: 90), "autopilot after place timeout")
        add(keep(XCUIScreen.main.screenshot(), name: "autopilot-button"))

        auto.tap()
        XCTAssertFalse(auto.waitForExistence(timeout: 3), "autopilot cleared")
        add(keep(XCUIScreen.main.screenshot(), name: "after-take-back"))
    }

    private func keep(_ shot: XCUIScreenshot, name: String) -> XCTAttachment {
        let a = XCTAttachment(screenshot: shot)
        a.name = name
        a.lifetime = .keepAlways
        return a
    }
}
