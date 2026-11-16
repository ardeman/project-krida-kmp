import XCTest

/// Smoke: open Werewolf vs computers on a simulator and reach Night with a dealt role.
/// `xcodebuild test -scheme KridaUITests -only-testing:KridaUITests/WerewolfSmokeTests`
final class WerewolfSmokeTests: XCTestCase {
    func testOpensNightWithRole() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = ["-skipAds", "-openWerewolf"]
        app.launch()

        let back = app.buttons["Back to menu"]
        XCTAssertTrue(back.waitForExistence(timeout: 45), "entered werewolf")
        add(keep(XCUIScreen.main.screenshot(), name: "werewolf-entered"))

        let night = app.staticTexts["Night"].firstMatch
        let role = app.staticTexts["Your role"].firstMatch
        XCTAssertTrue(
            night.waitForExistence(timeout: 15) || role.waitForExistence(timeout: 1),
            "night phase or role card\n\(app.debugDescription)")
        add(keep(XCUIScreen.main.screenshot(), name: "werewolf-night"))
    }

    private func keep(_ shot: XCUIScreenshot, name: String) -> XCTAttachment {
        let a = XCTAttachment(screenshot: shot)
        a.name = name
        a.lifetime = .keepAlways
        return a
    }
}
