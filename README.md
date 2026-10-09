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

- CineSrc primary with a custom control bar: play/pause, −10s/+30s skip, seek, speed, volume, quality cycling, mute, sleep timer, continue-watching resume
- **11 stream mirrors + trailer**, one-tap fallback: CineSrc, VidLink, VidFast, VidSrc, VsEmbed, AutoEmbed, 2Embed, YapGrid, VidAPI, VidCore, VidRift (anime home)
- Ad Shield sandbox on supporting mirrors — unsandboxed ones are clearly labeled; the APK additionally blocks known ad networks inside the WebView
- Smart Source button: re-picks the server on CineSrc, cycles mirrors everywhere else
- YapGrid inherits your subtitle language, autoplays, and plays your loaded Wyzie/TSDB track as its default
- Auto-next episodes, cinema mode with auto-hiding HUD, volume toast for remotes

## Subtitles

- Wyzie search with built-in shared key, personal-key override (your own 1,000/day), auto-subtitles in your language and quota warnings that spare the shared key when capped
- **TSDB backup** for movies + TV when Wyzie caps — keyless, strict episode matching so packs can't mistime you
- **BetaSeries backup** for TV episodes when both are dry
- 13 subtitle languages, `.srt` / `.vtt` file + paste loading, position + sync controls, per-title memory
- One-tap CC toggle on the player bar (opens Subs settings when nothing is loaded yet)

## TV & Remote

- Android TV first-class: spatial D-pad navigation that never strands focus, glowing focus rings, 44px targets, no hover-only anything
- Cinema remote scheme: ◀ −10s, ▶ +30s, ▲ ▼ volume, OK play/pause
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
| Playback | CineSrc embed + `postMessage` command API, 7 alternate players |
| Subtitles | Wyzie → TSDB → BetaSeries cascade, overlay renderer on CineSrc |
| Android | Fullscreen WebView wrapper (phone + TV), splash, guarded URL allowlist |
| Secrets | API keys sharded in code — no plaintext, revokable, personal overrides supported |

## Polish

- Modern skin: glass cards, pill nav with active glow, switch toggles, skeleton shimmer, rating badges
- Netflix-style nav that solidifies on scroll, billboard hero with slow push-in
- Focus glow follows remote/keyboard only — taps never leave a stuck ring
- PWA: installable, offline app shell, update banner stamped with the live build number
