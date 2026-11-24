# Security & Privacy Audit - StreamBox iOS/iPadOS

Scope: the `ios/` shell, its bridges, and how it wraps the StreamBox web
app. Complements (does not replace) the web app's own posture: TMDB keys
live server-side in the API worker (QA lock: no keys in client code), and
the Android wrapper carries a parallel allowlist/blocklist that this shell
mirrors deliberately.

## 1. WebView configuration

| Control | Setting | Rationale |
| --- | --- | --- |
| JavaScript | enabled (required) | The SPA is the app |
| Persistent data store | `WKWebsiteDataStore.default()` | localStorage (Continue Watching, My List, PIN hash, settings) must survive updates; the shell NEVER wipes it (same rule as Android QA lock 126) |
| Inline media autoplay | `allowsInlineMediaPlayback = true`, `mediaTypesRequiringUserActionForPlayback = []` | parity with Android `setMediaPlaybackRequiresUserGesture(false)`; playback is user-initiated by UI anyway |
| PiP / AirPlay | enabled | legitimate media features of the platform |
| File/content access | none requested; no file scheme loads | content stays remote-https |
| ATS | default (strict HTTPS) | no `NSAllowsArbitraryLoads`; mixed content impossible |
| New windows | `createWebViewWith` loads into the SAME shell after allowlist check | popups cannot spawn unvetted browsers |
| JS dialogs | surfaced as native alert/confirm/prompt | callbacks can never hang silently |

## 2. Navigation allowlist (mirrors Android `shouldOverrideUrlLoading`)

- Main-frame navigations: http(s) ONLY (scheme gate first - `intent://`,
  `javascript:`, `content://` and friends are cancelled before any host
  check, exactly like the Android scheme-first rule that QA lock 125 pins),
  then host-or-subdomain allowlist: our site, TMDB image host, the mirror
  player hosts, YouTube (nocookie) for trailers. Everything else cancels.
- Subframes: http(s) passes (players navigate inner frames to video hosts;
  blocking these kills playback), non-http(s) cancels.
- `streambox://party/CODE` links route to the deep-link handler (code
  sanitized to `[A-Za-z0-9]{1,6}` before it ever touches page JS).

## 3. JavaScript bridge (the sharp edge)

- One message handler (`native`), injected as `window.SB_NATIVE` with two
  verbs: `share` and `playNative`. Outbound only: the page cannot read
  native state.
- Every message is re-validated in Swift: `playNative` accepts only
  `https` URLs with direct-media extensions (`m3u8 mp4 m4v mov aac`) -
  embed/iframe URLs are refused by design. `share` accepts a URL + text
  and hands them to the system share sheet (no navigation side effects).
- No `evaluateJavaScript` of page-supplied strings: the shell only runs its
  own fixed snippets (back cascade, `PARTY.open('<sanitized>')`, media key
  JS taken verbatim from the Android shell).
- The handler is registered through a weak proxy to avoid retain cycles.

## 4. Ad Shield (WKContentRuleList)

- ~60 ad-tech/popunder/tracker domains blocked at the WebKit level in every
  frame - the platform's own content blocking (no proxy, no MITM, no
  private API). List is shared verbatim with the Android blocklist
  (`AppConfig.adHosts`), covered by unit tests.
- Media extensions are never blocked (the rule matches ad DOMAINS only),
  so streams cannot be collateral damage.

## 5. TLS policy

- Default ATS = strict TLS validation everywhere. Unlike Android (which
  contains a surgical exception for vidcore.org's broken chain), the iOS
  shell makes NO TLS exception: if that provider's chain is broken on iOS,
  the app fails honestly and the user switches mirrors. Weakening platform
  TLS for one mirror is not an acceptable trade.

## 6. Sensitive data

- No API keys in the binary. The TMDB key lives in the Cloudflare Worker
  secret, same as the web.
- PIN: salted SHA-256 hash in web storage, local-only.
- UserDefaults: non-sensitive settings only (declared: CA92.1).
- `PrivacyInfo.xcprivacy`: NSPrivacyTracking=false, zero tracking domains,
  zero collected data types.

## 7. Supply chain

- Zero third-party dependencies (no CocoaPods/SPM packages): the Xcode
  project compiles only first-party Swift + Apple frameworks + 2 bundled
  JS files.
- CI builds from a clean checkout on GitHub-hosted macOS runners; signing
  material only ever enters via Actions secrets, never the repository.

## 8. Threats explicitly out of scope (and honest)

- Third-party embed providers can track viewers inside their frames to the
  extent their own scripts do (mitigated, not eliminated, by Ad Shield).
  We do not control their code; we select and allowlist them.
- The `sb:landscape`/`sb:awake` title-marker channel is a documented,
  shared contract. A compromised page could flip orientation or keep the
  screen awake - availability nuisance only, no data exposure.
- Free-Apple-ID builds expire every 7 days; that is Apple policy.

## 9. Checklist result

| Item | Status |
| --- | --- |
| HTTPS + normal cert validation | PASS (strict ATS, no exceptions) |
| Main-frame allowlist | PASS (scheme gate + host list) |
| Untrusted content blocked from privileged bridges | PASS (validated verbs, sanitized inputs) |
| No client-side API keys | PASS |
| User data preserved across updates | PASS (persistent store, no wipes) |
| Privacy manifest present | PASS |
| No tracking / no collection | PASS |
| No DRM / embed / geo bypasses | PASS (native player is for authorized direct URLs only) |
| No unauthorized enterprise signing | PASS (only free personal signing or real certs) |
