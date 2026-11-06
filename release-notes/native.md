Watch Party sync rebuilt: cinema-like shared playback with scheduled episode starts, reliable late-join sync, and smooth drift correction - plus cleaner party doors and broader ad protection.

What is new
- Episode changes start TOGETHER: the server schedules one shared start for everyone (no more each-device start times or one participant stuck on the old episode)
- Joining a party lands you on the room timeline the moment your player is actually ready - if the host is 35 minutes in, you join at 35 minutes, never from 00:00
- The home Party button now joins only (code + password), while the movie page button creates the room and attaches that movie/episode automatically
- Sync diagnostics on the party status pill: live latency and drift so you can see the room is together

Fixes
- Subtitles land on time again: provider clocks are thinned to ~4Hz for weak boxes, which made caption text arrive a beat late - the lookup now extrapolates between updates and repaints every 120ms, so text appears exactly when it should (fine-tune anything left with the sync slider)
- A new episode always shows ITS OWN subtitles: the loaded file used to ride along after Next Episode (the last episode's text over the new episode) - episode changes now clear the inherited file, stamp which episode every file serves, and a slow provider fetch can no longer land on the wrong episode
- AniPM's OWN English subtitles now render in our styled overlay on anime: the raw player tracks (which carry the subtitle file URLs the player's shell strips) are captured by our bridge, and the provider's English track is auto-loaded as the default caption - the exact anime translation and timing, with the floating pill; picking any other provider track with a file does the same, and a track without a file honestly stays in the player's own rendering
- AniPM captions finally look like CineSrc: the subtitle chain serves anime too (it used to skip anime entirely so only the player's own in-frame text showed - no floating, no background); our styled captions now load for anime, float UP when the control bar shows and settle when it hides, and wear the semi-transparent pill; picking one of AniPM's/VidBolt's own tracks switches cleanly (no double captions) and Off hands the screen back to ours
- The Thank You screen is fully remote-drivable again: the invisible full-screen key sink used to steal focus while the card was up (a screen-sized focusable sits in every direction - the D-pad could never leave it); the sink now stands down while the card shows and the remote walks Replay / Back to Browse / More Like This freely
- More Like This labels are honest again: rows now type themselves from their own content, so TV shows and movies are no longer stamped ANIME just because you finished an anime
- The update dialog (and What's New) can no longer trap you: the WHOLE card scrolls within the screen now, so Download & Install and Start Watching are always reachable with the remote (long notes used to push the buttons off a TV screen and leave the remote dead - the reason some TVs stayed on 0.87)
- Drift no longer causes constant seeking: small differences are corrected with subtle 4% speed nudges, and only real drift over 2 seconds seeks (the old code seeked for every 0.35s wobble and stuttered)
- Clock estimates use the cleanest network samples (lowest-latency heartbeats anchor the room clock) so correction chases the true timeline
- Pause/resume/seek land tighter on every participant (corrections trigger at 0.6s instead of 3s) and commands stay ordered through the room's sequence numbers
- Player initialization differences can no longer desync a join - everyone waits for readiness and then lands on the shared timeline
- The built-in ad shield's blocklist grew with curated advertising/popunder/tracking hosts (pure ad infrastructure only - media always rides, and the player-compatibility exceptions stay in place so sandbox-breaking sources keep playing)
