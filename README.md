# StreamBox — Watch Movies & TV Shows Free

![Build StreamBox APK](https://github.com/dazoarmando29/streambox/actions/workflows/android-apk.yml/badge.svg)

Fast, free streaming front-end powered by TMDB + CineSrc. Single static page for the
web, wrapped as a native-feeling app on Android phones and Android TV — one codebase,
three targets.

**Live site:** https://dazoarmando29.github.io/streambox/

Built and maintained by **dazzo** ([@dazoarmando29](https://github.com/dazoarmando29).

## Features

- Trending / popular / top-rated catalog with hero rotation (TMDB)
- Search overlay with recent searches, genre browsing with load-more
- Detail modal with season/episode steppers (remote-friendly, no dropdowns)
- CineSrc player with custom control bar: play/pause, ±skip, seek, speed, volume,
  quality, sleep timer, continue-watching with resume points
- 8 stream mirrors with one-tap fallback (CineSrc, VidLink, VidFast, VidSrc,
  VidSrc PM, Smashy, AutoEmbed, 2Embed) + official-trailer fallback
- Ad Shield sandbox on supporting mirrors; unsandboxed mirrors are labeled
- External subtitles: Wyzie search (your own free key) + `.srt`/`.vtt` file/paste with
  position and sync controls
- Android TV first-class: D-pad spatial navigation, cinema mode, smart Back
  handling, Leanback launcher entry, per-version fresh-start wipe
- PWA: installable, offline app shell, update banner when a new build ships

## Tech stack

| Layer   | Details                                                        |
| ------- | -------------------------------------------------------------- |
| Web     | Single `index.html` (Tailwind CDN), `sw.js`, `manifest.webmanifest` |
| Catalog | TMDB REST API (cached 10 min, memory + sessionStorage)         |
| Player  | CineSrc embed + `postMessage` command API                      |
| Android | `android/` — fullscreen WebView wrapper (`MainActivity`), splash (`SplashActivity`) |
| CI      | `.github/workflows/android-apk.yml` — JS syntax gate → Gradle `assembleDebug` → artifact + rolling `latest` release |

## Repo layout

```
index.html                  # the whole web app (BUILD_NUM lives here)
version.txt                 # build number — keep in sync with BUILD_NUM
sw.js / manifest.webmanifest / icon-*.png
android/                    # WebView wrapper (phone + Android TV)
.github/workflows/         # CI: validate → build APK → publish release
```

## Run locally

Just open `index.html` in a browser, or serve it (recommended — service worker
needs `http(s)`):

```
python -m http.server
```

## Deploy (GitHub Pages)

1. Push this folder as the repo root to GitHub
2. Repo → Settings → Pages → Deploy from branch → `main` / `(root)`
3. Open `https://dazoarmando29.github.io/streambox/`

## How watching works

- Catalog, search, trending: TMDB API
- Playback: CineSrc embed
  - Movie: `https://cinesrc.st/embed/movie/{tmdb_id}`
  - TV: `https://cinesrc.st/embed/tv/{tmdb_id}?s={season}&e={episode}`
- Alternate mirrors use their own players (only CineSrc answers the remote bar)
- Ad Shield: sandboxed iframe blocks popups / tab-hijacks. For full in-stream ad
  removal use Brave or uBlock Origin.

## Android APK (phone + Android TV)

Every push to `main` auto-builds via GitHub Actions:

1. Repo → **Actions** → **Build StreamBox APK** → latest green run → **Artifacts** → `streambox-apk`,
   or grab the rolling build at **Releases → `latest` → `streambox.apk`**
2. Install:
   - Phone: open the APK, allow "Install unknown apps" if asked
   - Android TV: send the APK via the "Send Files to TV" app or USB, open with a
     file manager, allow unknown sources. **Uninstall the old version first** —
     v1.3+ wipes its WebView data on first launch so updates can never serve stale pages.

The app is a fullscreen wrapper around the live site, with TV remote (D-pad) +
back-button support and a Leanback launcher entry.

## Versioning

- Web: bump `BUILD_NUM` in `index.html` **and** `version.txt` together — the
  in-app update banner and the service worker depend on them matching.
- Android: bump `versionCode` / `versionName` in `android/app/build.gradle`
  (triggers the once-per-version WebView wipe).

## Author

**dazzo** — https://github.com/dazoarmando29
