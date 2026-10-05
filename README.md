# StreamBox — Watch Movies & TV Shows Free

Fast, free streaming front-end powered by TMDB + CineSrc.

## Run locally
Just open `index.html` in a browser, or:
```
python -m http.server
```

## Free hosting (GitHub Pages)
1. Push this folder as the repo root to GitHub
2. Repo → Settings → Pages → Deploy from branch → `main` / `(root)`
3. Open `https://YOURNAME.github.io/REPO/`

## How watching works
- Catalog, search, trending: TMDB API
- Playback: CineSrc embed
  - Movie: `https://cinesrc.st/embed/movie/{tmdb_id}`
  - TV: `https://cinesrc.st/embed/tv/{tmdb_id}?s={season}&e={episode}`
- Ad Shield: sandboxed iframe blocks popups / tab-hijacks. For full in-stream ad removal use Brave or uBlock Origin.

## Android APK (phone + Android TV)
This repo auto-builds an installable APK via GitHub Actions.
1. Push to `main` (already wired: `.github/workflows/android-apk.yml`)
2. Repo → **Actions** → **Build StreamBox APK** → latest run → **Artifacts** → download `streambox-apk`
3. Install:
   - Phone: open the APK, allow "Install unknown apps" if asked
   - Android TV: send the APK via "Send Files to TV" app or USB, open with a file manager, allow unknown sources
The app is a fullscreen wrapper around your live site, with TV remote (D-pad) + back-button support and Leanback launcher entry.
