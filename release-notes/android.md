Stability and remote-control release: Watch Party rooms survive kicks and reconnects, Android TV remote navigation can no longer lose focus or get trapped, playback auto-healing is bounded and honest, and the APK closes its back-button and fullscreen holes.

What is new
- Watch Party rooms are finally robust across devices: removing a participant no longer ends the party for EVERYONE, repeated reconnects reattach to the same seat (no ghost duplicates, no lost host role, no password lockout), and a returning host's role change is announced to the whole room
- Party clock sync is honest again: a poisoned heartbeat no longer freezes latency estimates, so drift correction follows the real timeline even after a network change (Wi-Fi to cellular and back)
- Room passwords survive reconnects and page refreshes - rejoin with the password you typed, silently
- TV remotes get working media transport keys (play/pause/ff/rw) in the app and on the website
- Quality labels are verified, never faked: the chip shows the rendition the player actually reports (VidBolt selected row, AniPM qualitychange), and "4K" only appears as "4K avail" for Auto when the stream really carries a 2160p rendition - no more 4K badge over a 1080p picture
- VidBolt quality pins now speak the player's own language (its real HLS level values), so the requested quality actually applies instead of silently doing nothing

Fixes
- Android TV focus no longer evaporates: the hero billboard stops rotating while you are on its buttons, invisible toast buttons stop catching arrow presses, Back/OK repeats no longer double-fire or close two layers at once, and closing the PIN pad / About / player returns focus to where you were
- Remote Back is never swallowed: text fields (episode filter, party inputs) release the remote on the Back key, and Back during a slow details load cancels it instead of popping the modal later
- Back inside the app on a mirror page now goes back instead of closing the whole app (the WebView history fallback the shell never had)
- Video fullscreen exits cleanly every time (page-initiated exits used to leave the player stuck so the next fullscreen never worked), and the remote focus returns to the page
- Auto-switch self-healing is bounded everywhere: dead titles can no longer ping-pong between mirrors forever (one heal + one health hop per title), and tapping the active server pill no longer restarts the video at 0:00
- Resume position survives mirror hops: a rescued position is never wiped when switching back to CineSrc, and stale VidRift resume timers can no longer seek a fresh frame
- Rapid episode taps can no longer land two racing loads (stale loads lose cleanly instead of fighting over the player)
- Auto-next can no longer skip an episode when a duplicate next-up event arrives late
- Party rejoin toasts, silent stale-playback drops, and resync chat gaps are cleaned up (resync now shows the chat you missed)
- Unaired episodes are visibly unavailable instead of focusable dead buttons; the Audio/Subtitles and Player sheets open on a real option, not the close button
- Website icons/manifests refresh themselves (stale-while-revalidate) instead of rotting until a cache bump
- The APK no longer participates in Android auto-backup (the adult PIN and watch history never leave the device), and renderer crashes recover twice before giving up cleanly instead of crash-looping
