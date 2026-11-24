# tvOS Evaluation - dedicated Apple TV app?

Decision: **evaluate now, build later.** Per the project's own rule, Apple TV
development stays separate from the iPhone/iPad release so it cannot delay
it. This file records the honest feasibility picture.

## Can the current app run on tvOS?

No, not as-is. **tvOS has no WKWebView** (and no Safari). The entire
"web shell" strategy that powers the iPhone/iPad and Android apps is
unavailable. A tvOS app must be fully native: SwiftUI + TVUIKit + AVKit,
talking directly to the API worker.

## What can be reused

| Piece | Reusable on tvOS? | Notes |
| --- | --- | --- |
| API worker (`/api/tmdb/*`, `/api/anime-*`, intro ranges) | YES | JSON over HTTPS, exactly what a native tvOS catalog needs |
| Watch Party hub (`/party` WebSocket) | YES | URLSessionWebSocketTask on tvOS; protocol shared |
| `party-server/protocol.js` sync math | YES | port `expectedPosition`/`planCorrection` as Swift |
| Catalog models (TMDB shapes) | YES | SwiftCodable structs, shared via a small SPM-free source folder |
| Provider embed players | NO | iframes don't exist on tvOS; playback needs direct sources |
| Web subtitle chain (Wyzie fetch + VTT rendering) | PARTIAL | fetching works natively; rendering needs `AVMediaSelection`/custom overlays |
| Native Android Kotlin code | NO | different language; ideas transfer |

## The hard problem: media sources

The web app plays provider embeds in iframes. tvOS cannot. Options, in
honest order:

1. **Licensed/owned/public-domain streams** - the legitimate default. The
   app's AVKit path already plays direct HLS (this is what the iOS native
   player does). A tvOS app built around licensed content (or the user's
   own direct URLs) is App Store safe.
2. **Provider APIs exposing direct media URLs** - only where the provider's
   terms explicitly allow it. Never scraped, never bypassed.
3. If none of the above, the tvOS app can still ship catalog + My List +
   Party "remote" features with playback delegated to AirPlay from iPhone.

## Effort sketch (when prioritized)

- Catalog + details + My List + Continue Watching: ~1-2 weeks of focused work.
- Watch Party client (sync math + roles/queue): ~1 week (protocol proven).
- Player + subtitles + Up Next: ~1-2 weeks depending on source strategy.
- Focus-engine polish (Siri Remote, top shelf, TV icon): ~1 week.

## Not blocking anything

Nothing in `ios/` or the website depends on tvOS. Android TV support lives
in the Android wrapper and the site's `?tv=1` 10-foot mode and is
unaffected. When a tvOS build happens it starts as a new `tvos/` directory
with shared logic factored out of this evaluation.
