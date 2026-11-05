Watch Party arrives on Android TV and phones: create a room from any title, share the link, and watch movies and episodes together with synced playback, live participants and chat - remote-friendly from the couch. Plus a redesigned update experience.

What is new
- Watch Party rooms from every title page: room codes, invite links, live participants, host controls (guest playback permission, chat toggle, kick) and real-time chat
- Synced play/pause/seek through the player's own controls on the paired sources (CineSrc/AniPM/VidBolt/VidZen = full sync), honest partial/coordinated labels everywhere else
- Chat with D-pad quick messages on TV - typing is never required on the remote
- Invites open in-app through streambox://party/CODE and the room survives app switches, sleep and reconnects by itself (resume tokens restore your seat, even the host seat)
- The room server runs on our own Cloudflare edge - no accounts, no ads, and no video ever passes through it
- Update popup redesigned: a real what-is-new card with the version jump, size, date and verified-install cues
- A version stamp sits in the bottom corner (tap it any time to check for updates) and an accent Update ready pill keeps a pending update one tap away
- About page (Party/About doors live in the TV status strip and the phone top bar): builder, the exact installed version, and an honest update checker (Checking / You are up to date / Update available)
- Watch Party join door OUTSIDE the details screens - enter a room code and jump straight into the movie your friends are watching
- The version number sits beside the builder name at the bottom of every screen
- Watch Party and About now live in the LEFT NAV BAR beside Browse, and the rail is compact so every button fits a TV screen

Fixes
- Updates can no longer be missed: the check now rides our own edge-cached channel (the GitHub API's 60-requests/hour limit could silence every check and the app wrongly said "You are up to date"), a found update is REMEMBERED so the popup appears instantly even offline or on a bad connection, and "You are up to date" only shows when we really reached the update server (failures now say "Could not reach the update server - will retry")
- The update offer can no longer be missed: it re-appears on every reopen while an update is pending (Later only buys quiet for the session), it queues behind the What's New card instead of being swallowed by it, and the remote lands directly on the dialog buttons
- Fixed TV remote navigation: the corner version stamp was focusable and trapped the D-pad on the detail screens - the version now lives beside the builder name and About carries the checker
- Continue Watching opens the DETAILS first: the resume bar is right there and Play continues from where you left
- The update popup could appear only once per install and vanish forever on restart (a TV launcher restart reuses the app process, and failed lookups still silenced the next check) - it now re-offers on every reopen, retries through slow Wi-Fi wake-ups, Later only silences it for a few minutes, and the corner pill can always bring it back
- Room joins, rejoin-after-sleep and kicked-user permanence hardened and live-tested against the edge server
- Room playback state is authoritative on the server: stale or duplicate events can never affect the current episode, and late joiners sync to the party without resetting anyone
- Non-hosts never double-advance episodes - the host drives the room, everyone follows
- Previous episodes can no longer leak skip marks or subtitle files into the next one
