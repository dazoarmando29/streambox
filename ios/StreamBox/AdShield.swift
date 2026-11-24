import WebKit

/// Ad Shield: the Android wrapper blocks ad-tech hosts in
/// shouldInterceptRequest; the iOS equivalent is WKContentRuleList - the
/// platform's own content blocker (no proxy, no MITM, no private API).
/// The host list is shared verbatim (AppConfig.adHosts).
enum AdShield {

    /// Content-rule JSON: block every request whose domain matches an ad host
    /// (with subdomains). Pure function - unit-tested.
    static func ruleJSON(hosts: [String]) -> String {
        let domains = hosts
            .map { $0.lowercased() }
            .filter { !$0.isEmpty }
            .map { "\"*\($0)\"" }
            .joined(separator: ",")
        return "[{\"trigger\":{\"url-filter\":\".*\",\"if-domain\":[" + domains
            + "]},\"action\":{\"type\":\"block\"}}]"
    }

    static let ruleListIdentifier = "streambox-adshield-v1"

    /// Compile the blocklist (async, cached by WebKit after first run).
    static func prepare(completion: @escaping (WKContentRuleList?) -> Void) {
        let json = ruleJSON(hosts: AppConfig.adHosts)
        WKContentRuleListStore.default().compileContentRuleList(
            forIdentifier: ruleListIdentifier,
            encodedContentRuleList: json) { list, _ in
                DispatchQueue.main.async { completion(list) }
            }
    }
}
