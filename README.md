<div align="center">

# StreamBox

**Fast, free movies & TV shows — one codebase, three screens.**

[![Build](https://github.com/dazoarmando29/streambox/actions/workflows/android-apk.yml/badge.svg)](https://github.com/dazoarmando29/streambox/actions/workflows/android-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/dazoarmando29/streambox?include_prereleases&label=apk&color=14b8a6)](https://github.com/dazoarmando29/streambox/releases/tag/latest)

**Live site:** https://dazoarmando29.github.io/streambox/

*By **dazzo** — [@dazoarmando29](https://github.com/dazoarmando29)*

</div>

---

## Catalog

- Trending, popular & top-rated rows powered by TMDB, with a rotating billboard hero (`#1 Trending` badge, rating, overview, one-tap Watch)
- Search overlay with recent searches and instant results
- Genre browsing for movies, TV & anime with load-more
- PIN-locked 18+ filter: adult titles vanish from every list until an adult unlocks them
- Detail modal with remote-friendly season / episode steppers — no dropdowns anywhere
- Continue Watching with resume points, progress bars and one-tap remove, plus My List
- Mislabel guard: warns before you waste 20 minutes on the wrong cut of 16 famous remake/original pairs, with a Wrong-version jump
- Official-trailer fallback for every title

## Player

- Netflix-grade control bar on every screen: full-width seek row with live timecodes and a glowing progress track, one scrollable controls row, glass panel that never hides while a remote is parked on it
- **14 stream mirrors + trailer**, one-tap fallback: CineSrc, VidLink, VidFast, VidSrc, VsEmbed, AutoEmbed, 2Embed, YapGrid, VidAPI, VidCore, VidRift, VidZen, VidBolt, AniPM (anime home, bar-driven)
- One bar, zero overlap: VidBolt + VidZen ride our full bar (play/pause/seek/quality/subs), VidRift pairs via handoff, CineSrc + AniPM speak full command APIs
- **Up Next card** at every episode end: 10s auto-countdown where a player can't advance alone, Play Next everywhere else — unaired episodes never queue
- Netflix title cards: show + episode + saga arc on every episode, title + year on every movie
- Smart Source + adaptive fallback: a dead mirror hops itself to the last server that actually played
- Ad Shield sandbox on supporting mirrors — unsandboxed ones are clearly labeled; the APK additionally blocks known ad networks inside the WebView
- YapGrid inherits your subtitle language, autoplays, and plays your loaded Wyzie/TSDB track as its default
- Auto-next, cinema mode with auto-hiding HUD, floating Skip Intro / Recap / Credits pill, volume toast for remotes

## Subtitles

- Wyzie search with built-in shared key, personal-key override (your own 1,000/day), auto-subtitles in your language and quota warnings that spare the shared key when capped
- **TSDB backup** for movies + TV when Wyzie caps — keyless, strict episode matching so packs can't mistime you
- **BetaSeries backup** for TV episodes when both are dry
- 13 subtitle languages, `.srt` / `.vtt` file + paste loading, position + sync controls, per-title memory
- One-tap CC toggle on the player bar (opens Subs settings when nothing is loaded yet)

## TV & Remote

- Android TV first-class: spatial D-pad navigation that never strands focus, glowing focus rings, 44px targets, no hover-only anything
- Cinema remote scheme: ◀ −10s, ▶ +30s, ▲ reaches Skip/Up Next, OK play/pause — volume stays on your TV remote's own keys
- Genuine TV boxes auto-limit to the remote-drivable CineSrc + YapGrid (overridable in Playback Settings)
- TV app hands the D-pad straight to mirror players; native Back always returns
- Leanback launcher entry, per-version fresh-start wipe — updates can never serve stale pages

## Apps

| Edition | File | Feel |
|---|---|---|
| **StreamBox** | `streambox.apk` | Full motion: hero zoom, sliding bar, springy buttons |
| **StreamBox Lite** | `streambox-lite.apk` | Calm mode: same pixels, zero motion, max smoothness + battery |

Both install side-by-side. Every push to `main` rebuilds both and republishes the rolling `latest` release.

## Under the hood

| Layer | Details |
|---|---|
| Web | Single `index.html`, `sw.js` (offline shell, network-first pages), `manifest.webmanifest` |
| Catalog | TMDB REST (10-min memory + session cache) |
| Playback | CineSrc embed + `postMessage` command API, paired VidRift/VidZen/VidBolt + 9 more mirrors |
| Subtitles | Wyzie → TSDB → BetaSeries cascade, our own caption overlay on CineSrc + VidBolt |
| Android | Fullscreen WebView wrapper (phone + TV), splash, guarded URL allowlist |
| Secrets | API keys sharded in code — no plaintext, revokable, personal overrides supported |

## Polish

- Every screen tuned: phones (instant taps, iOS-safe inputs), tablets (4-col grids, big posters), desktop, Android TV (10-foot type, 230px row posters, Netflix-size browse grids)
- Responsive poster ladder (w185/w342/w500): every device downloads exactly the pixels it can show
- Modern skin: glass cards, pill nav with active glow, switch toggles, skeleton shimmer, rating badges
- Netflix-style nav that solidifies on scroll, billboard hero with slow push-in
- Focus glow follows remote/keyboard only — taps never leave a stuck ring
- PWA: installable, offline app shell, update banner stamped with the live build number
