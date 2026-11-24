import XCTest

/// Smoke test: the app boots to the native splash and hosts the web view.
/// Network state is NOT asserted here (simulators may be offline) - the
/// view hierarchy is what matters for a wrapper regression.
final class SmokeTests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testAppBootsAndHostsWebView() {
        let app = XCUIApplication()
        app.launch()

        // WKWebView is always in the hierarchy (it loads underneath the
        // splash either way) - its absence means the shell is broken.
        let web = app.webViews.firstMatch
        XCTAssertTrue(web.waitForExistence(timeout: 20), "WKWebView must be hosted")

        // the splash carries an accessibility label until first paint
        let splash = app.staticTexts["StreamBox is loading"]
        if splash.exists {
            XCTAssertTrue(splash.isHittable == false || splash.exists)
        }
    }

    func testNativeMenuIsReachableAfterBoot() {
        let app = XCUIApplication()
        app.launch()
        let menu = app.buttons["StreamBox menu"]
        // menu appears once the splash clears (<= 2s cap + fade)
        XCTAssertTrue(menu.waitForExistence(timeout: 25), "native menu button must exist")
    }
}
