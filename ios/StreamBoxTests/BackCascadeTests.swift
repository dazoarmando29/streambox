import XCTest
@testable import StreamBox

/// The Back cascade is a cross-platform contract: Android runs the same
/// sequence from MainActivity.onBackPressed. These tests lock the iOS port
/// to the same order (z-order) and the same sentinel vocabulary.
final class BackCascadeTests: XCTestCase {

    private var source: String = ""

    override func setUpWithError() throws {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // StreamBoxTests
            .deletingLastPathComponent()   // ios
            .appendingPathComponent("StreamBox/BackCascade.js")
        source = try String(contentsOf: url, encoding: .utf8)
    }

    func testSentinelVocabulary() {
        for sentinel in ["'refocus'", "'home'", "'panel'", "'modal'", "'exit'"] {
            XCTAssertTrue(source.contains(sentinel), "cascade must return " + sentinel)
        }
    }

    func testCascadeOrderMatchesZOrder() {
        // Every popup must be checked before the ones layered behind it.
        // (Anchors below are single-occurrence strings; the shared goHome()
        // helper appears in several branches and is asserted separately.)
        let chain = ["pinClose", "closeTvGuide", "closeSearch", "closePanels",
                     "hideUpNext", "resumePick(true)", "partyClose", "aboutClose",
                     "closeModal", "watchView"]
        var last = -1
        for symbol in chain {
            let idx = source.range(of: symbol)?.lowerBound
            XCTAssertNotNil(idx, "cascade missing " + symbol)
            let pos = source.distance(from: source.startIndex, to: idx!)
            XCTAssertGreaterThan(pos, last, symbol + " must come after the previous layer")
            last = pos
        }
    }

    func testWatchPageExitsToHomeThenSentinel() {
        XCTAssertTrue(source.contains("w.classList.contains('hidden')) { goHome(); return 'home'; }"),
                      "the watch page must fall through to goHome()")
        XCTAssertTrue(source.contains("return 'exit';"),
                      "the cascade must end with the 'exit' sentinel")
    }

    func testVideoFullscreenExitsBeforePopups() {
        let fs = source.range(of: "exitVideoFs")?.lowerBound
        let pin = source.range(of: "pinClose")?.lowerBound
        XCTAssertNotNil(fs)
        XCTAssertNotNil(pin)
        XCTAssertTrue(fs! < pin!, "cinema mode must exit before any popup is considered")
    }

    func testIframeRefocusFirst() {
        let iframe = source.range(of: "IFRAME")?.lowerBound
        let fs = source.range(of: "exitVideoFs")?.lowerBound
        XCTAssertNotNil(iframe)
        XCTAssertNotNil(fs)
        XCTAssertTrue(iframe! < fs!, "remote focus inside a player iframe is reclaimed first")
    }
}
