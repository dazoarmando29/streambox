Watch Party sync rebuilt for the WebView apps too: shared scheduled episode starts, reliable late-join sync, smooth drift correction, and cleaner party doors - plus broader built-in ad protection.

What is new
- Subtitles now behave like CineSrc on VidBolt and AniPM too: our styled captions float UP when the player bar shows and settle back down when it hides
- The Audio & Subtitles panel is now the full switchboard: your loaded StreamBox subtitles AND every track VidBolt/AniPM actually provide (their real subtitle + audio lists) - switch between them any time without leaving our player
- Servers that are down are now detected and avoided: the app probes every streaming server through our own edge service (the browser cannot see a 502 inside a frame) and hops to a healthy mirror immediately with a clear message - no more staring at a dead error page
- VidBolt picture quality fixed: your quality choice is now actually SENT to the player (it never was - their Auto picked a low rendition while the label said 1080p), the label now shows the quality the player ACTUALLY reports, and the smooth-playback auto-drop is real, visible and undoable
- Episode changes start TOGETHER on a shared server-clock gate (no more different start times per device, no one left on the previous episode)
- Joining a party from the home screen's Party button opens the host's movie and lands you on the room timeline the moment your player is ready - never from 00:00
- Home Party button joins only (code + password); the movie page's Watch Party button creates the room and fills in the movie/episode automatically
- The party panel shows live sync diagnostics (latency + drift) so you can see the room is together

Fixes
- Quality labels are honest again on VidBolt - the chip shows what is really playing, and struggling playback lightens quality with a clear notice (one tap to restore) instead of silently
- Drift correction rebuilt: subtle 4% speed nudges for small differences, one clean seek only past 2 seconds (the old logic seeked constantly and stuttered), and no corrections at all before a scheduled start opens
- Clock estimates anchor on the lowest-latency heartbeats so corrections follow the true timeline instead of network noise
- Pause/resume/seek reach every participant tighter (0.6s threshold) with ordered events and echo suppression
- Late joiners wait for their player to be ready, then sync - loading speed no longer decides where you start
- The built-in ad shield keeps growing: curated advertising/popunder/tracking domains are blocked while media always rides first, and the per-source compatibility exceptions stay so sandbox-breaking players keep working (AdGuard-style protection without the playback breakage)
