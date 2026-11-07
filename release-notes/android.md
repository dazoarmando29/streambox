Self-updating app, a real third full-sync player, and one static stylesheet: StreamBox now installs its own verified updates on TV, VidCore joins CineSrc/AniPM/VidBolt/VidZen as a bar-driven full-sync source (through its real VidSpark player, not the ad-wrapper), and the site boots without the Tailwind CDN.

What is new
- In-app updates: when a new version exists, About shows Download & Install and the app downloads, verifies and hands the APK straight to the system installer - no sideload, no browser, no "reinstall / clear data". Every update is pinned twice (the published sha256 checksum AND this app's own signing certificate) and only a strictly newer version is ever installed; anything that fails verification is deleted and says why
- VidCore is now a fully driven source: play/pause/seek run from our bar with verified events (play/pause/seeked/timeupdate/ended) from its real player, so it joins the FULL SYNC Watch Party set (5 full-sync mirrors now) and saves your resume position - and because we talk to the player directly, its wrapper's pop-under ads are gone
- The website and app boot with ONE static stylesheet (32KB, 6.5KB gzipped) instead of the runtime Tailwind CDN - no JIT compile flash on slow TVs, no CDN dependency, verified pixel-identical to the old rendering (1677 class tokens + full-page computed-style diff = zero drift)
- Media transport keys (play/pause/ff/rw on remotes and keyboards) work on the website too
- A rate-limit-proof update channel (edge-cached worker mirror) so a whole house of boxes can never exhaust the GitHub API quota

Fixes
- The Quality label is verified everywhere: it shows the rendition the player actually reports (VidBolt's selected row, AniPM's qualitychange events) and "4K" only appears as "Auto · 4K avail" when the stream really carries a 2160p rendition - never a bare 4K badge over a 1080p picture
- VidCore/VidAPI honesty: VidAPI is labeled coordinated-only (its wrapper relays no player events - it never did), and the VidCore integration documents and uses the real player protocol
- Update-check copy inside the app now points at the real flow (About → Download & Install)
