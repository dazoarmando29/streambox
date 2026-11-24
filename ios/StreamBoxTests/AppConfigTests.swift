import XCTest
@testable import StreamBox

/// Contract tests for the shared shell configuration: URLs, allowlist,
/// party deep links, the document.title marker channel, and the native
/// playback gate. Pure logic - no network, no simulator UI.
final class AppConfigTests: XCTestCase {

    // MARK: - Home URL / editions

    func testHomeURLPlain() {
        let url = AppConfig.homeURL(lite: false)
        XCTAssertEqual(url.absoluteString, "https://dazoarmando29.github.io/streambox/")
    }

    func testHomeURLLiteMirrorsCalmParam() {
        let url = AppConfig.homeURL(lite: true)
        XCTAssertTrue(url.absoluteString.contains("calm=1"))
    }

    func testHomeURLCarriesPartyCode() {
        let url = AppConfig.homeURL(lite: false, partyCode: "AB12CD")
        XCTAssertTrue(url.absoluteString.contains("party=AB12CD"))
    }

    func testHomeURLRejectsEmptyPartyCode() {
        let url = AppConfig.homeURL(lite: false, partyCode: "!!!")
        XCTAssertFalse(url.absoluteString.contains("party="))
    }

    // MARK: - Party code sanitization (same rule as Android)

    func testPartyCodeSanitization() {
        XCTAssertEqual(AppConfig.sanitizedPartyCode("ab12cd"), "ab12cd")
        XCTAssertEqual(AppConfig.sanitizedPartyCode("AB-12_CD!"), "AB12CD")
        XCTAssertEqual(AppConfig.sanitizedPartyCode("1234567890"), "123456")
        XCTAssertNil(AppConfig.sanitizedPartyCode("!!!"))
        XCTAssertNil(AppConfig.sanitizedPartyCode(nil))
    }

    // MARK: - Main-frame allowlist

    func testAllowlistExactAndSubdomains() {
        XCTAssertTrue(AppConfig.isHostAllowed("dazoarmando29.github.io"))
        XCTAssertTrue(AppConfig.isHostAllowed("cinesrc.st"))
        XCTAssertTrue(AppConfig.isHostAllowed("embed.vidrift.net"))
        XCTAssertTrue(AppConfig.isHostAllowed("www.youtube.com"))
    }

    func testAllowlistRejectsLookalikes() {
        XCTAssertFalse(AppConfig.isHostAllowed("evilcinesrc.st"))
        XCTAssertFalse(AppConfig.isHostAllowed("cinesrc.st.evil.com"))
        XCTAssertFalse(AppConfig.isHostAllowed("attacker.example"))
        XCTAssertFalse(AppConfig.isHostAllowed(""))
    }

    // MARK: - document.title marker channel

    func testTitleMarkersParse() {
        XCTAssertEqual(AppConfig.titleMarker("sb:landscape"), .landscape)
        XCTAssertEqual(AppConfig.titleMarker("sb:portrait"), .portrait)
        XCTAssertEqual(AppConfig.titleMarker("sb:awake"), .awake)
        XCTAssertEqual(AppConfig.titleMarker("sb:sleep"), .sleep)
        XCTAssertNil(AppConfig.titleMarker("StreamBox - Watch"))
        XCTAssertNil(AppConfig.titleMarker(""))
    }

    // MARK: - Native playback gate

    func testDirectMediaURLGate() {
        XCTAssertTrue(AppConfig.isDirectMediaURL(URL(string: "https://cdn.example.com/movie.m3u8")!))
        XCTAssertTrue(AppConfig.isDirectMediaURL(URL(string: "https://cdn.example.com/movie.mp4")!))
        // never http, never non-media extensions (embed pages stay in WebKit)
        XCTAssertFalse(AppConfig.isDirectMediaURL(URL(string: "http://cdn.example.com/movie.mp4")!))
        XCTAssertFalse(AppConfig.isDirectMediaURL(URL(string: "https://cinesrc.st/embed/movie/550")!))
        XCTAssertFalse(AppConfig.isDirectMediaURL(URL(string: "https://cdn.example.com/manifest.mpd")!))
    }
}
