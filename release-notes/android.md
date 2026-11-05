Watch Party is here - watch together from any device - plus a major stability and security pass across the app.

What is new
- Watch Party: create a room, share the link, and watch movies and episodes together with synced play/pause/seek on CineSrc/AniPM/VidBolt/VidZen (other sources honestly show coordinated/manual mode)
- Room codes and invite links, live participant list, host controls, and real-time chat with remote-friendly quick messages on Android TV
- The room server runs on our own Cloudflare edge (streambox-api) - no accounts, and no video ever passes through it
- streambox://party/CODE invites open straight into the app on phones and TVs
- Watch Party join door in the header (outside the details screens): enter a room code and jump straight into the movie your friends are watching
- About page in the header: builder, the exact build number, and an honest update checker (Checking / You are up to date / Update available)

Fixes
- The build number now sits beside the builder name in the footer (no floating labels)
- Continue Watching opens the details page first - the resume point is there and Watch continues from where you left

Fixes
- App updates no longer erase Continue Watching, My List, your PIN or settings - the fresh-start wipe now clears caches only
- Fixed a crash when leaving fullscreen video on Android 7.x and a Back-button deadlock when the site updated ahead of the app
- The app now survives WebView renderer crashes on low-memory TV sticks (rebuilds instead of closing)
- Mirror auto-fallback can no longer loop between dead sources, and stale timers can no longer reload the player in the background
- Cross-site scripting hardening across titles, overviews and chat; anime browse paging fixed; subtitle episode races fixed
- Every build signs with the same key now - updates install over the old app in place (one-time: builds from before this need one manual uninstall)
