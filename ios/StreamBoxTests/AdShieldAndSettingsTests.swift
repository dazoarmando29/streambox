import XCTest
@testable import StreamBox

/// Ad Shield + settings store: the two pure-data pieces of the shell.
final class AdShieldAndSettingsTests: XCTestCase {

    // MARK: - Ad Shield rule list

    func testRuleJSONParsesAndBlocksDomains() throws {
        let json = AdShield.ruleJSON(hosts: ["doubleclick.net", "popads.net"])
        let data = try XCTUnwrap(json.data(using: .utf8))
        let parsed = try JSONSerialization.jsonObject(with: data)
        let rules = try XCTUnwrap(parsed as? [[String: Any]])
        XCTAssertEqual(rules.count, 1)
        let trigger = try XCTUnwrap(rules[0]["trigger"] as? [String: Any])
        XCTAssertEqual(trigger["url-filter"] as? String, ".*")
        let domains = try XCTUnwrap(trigger["if-domain"] as? [String])
        XCTAssertEqual(domains, ["*doubleclick.net", "*popads.net"])
        let action = try XCTUnwrap(rules[0]["action"] as? [String: Any])
        XCTAssertEqual(action["type"] as? String, "block")
    }

    func testRuleJSONHandlesFullHostList() throws {
        let json = AdShield.ruleJSON(hosts: AppConfig.adHosts)
        let data = try XCTUnwrap(json.data(using: .utf8))
        let parsed = try JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        XCTAssertNotNil(parsed)
        let domains = ((parsed?[0]["trigger"] as? [String: Any])?["if-domain"] as? [String]) ?? []
        XCTAssertEqual(domains.count, AppConfig.adHosts.count)
    }

    func testRuleJSONSkipsEmptyHosts() {
        let json = AdShield.ruleJSON(hosts: ["", "doubleclick.net"])
        XCTAssertFalse(json.contains("\"**\""))
        XCTAssertTrue(json.contains("*doubleclick.net"))
    }

    // MARK: - Settings store (hermetic suite)

    func testLiteModeRoundTrips() {
        let suiteName = "sb.tests.settings.\(UUID().uuidString)"
        let suite = UserDefaults(suiteName: suiteName)!
        defer { suite.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(defaults: suite)
        XCTAssertFalse(store.liteMode)
        store.liteMode = true
        XCTAssertTrue(store.liteMode)
    }

    func testNativePlayerDefaultsOn() {
        let suiteName = "sb.tests.player.\(UUID().uuidString)"
        let suite = UserDefaults(suiteName: suiteName)!
        defer { suite.removePersistentDomain(forName: suiteName) }
        let store = SettingsStore(defaults: suite)
        XCTAssertTrue(store.nativePlayerEnabled)
        store.nativePlayerEnabled = false
        XCTAssertFalse(store.nativePlayerEnabled)
    }
}
