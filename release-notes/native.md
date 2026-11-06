Watch Party sync rebuilt: cinema-like shared playback with scheduled episode starts, reliable late-join sync, and smooth drift correction - plus cleaner party doors and broader ad protection.

What is new
- Episode changes start TOGETHER: the server schedules one shared start for everyone (no more each-device start times or one participant stuck on the old episode)
- Joining a party lands you on the room timeline the moment your player is actually ready - if the host is 35 minutes in, you join at 35 minutes, never from 00:00
- The home Party button now joins only (code + password), while the movie page button creates the room and attaches that movie/episode automatically
- Sync diagnostics on the party status pill: live latency and drift so you can see the room is together

Fixes
- The update dialog (and What's New) can no longer trap you: the WHOLE card scrolls within the screen now, so Download & Install and Start Watching are always reachable with the remote (long notes used to push the buttons off a TV screen and leave the remote dead - the reason some TVs stayed on 0.87)
- Drift no longer causes constant seeking: small differences are corrected with subtle 4% speed nudges, and only real drift over 2 seconds seeks (the old code seeked for every 0.35s wobble and stuttered)
- Clock estimates use the cleanest network samples (lowest-latency heartbeats anchor the room clock) so correction chases the true timeline
- Pause/resume/seek land tighter on every participant (corrections trigger at 0.6s instead of 3s) and commands stay ordered through the room's sequence numbers
- Player initialization differences can no longer desync a join - everyone waits for readiness and then lands on the shared timeline
- The built-in ad shield's blocklist grew with curated advertising/popunder/tracking hosts (pure ad infrastructure only - media always rides, and the player-compatibility exceptions stay in place so sandbox-breaking sources keep playing)
