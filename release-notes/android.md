Watch Party sync rebuilt for the WebView apps too: shared scheduled episode starts, reliable late-join sync, smooth drift correction, and cleaner party doors - plus broader built-in ad protection.

What is new
- Episode changes start TOGETHER on a shared server-clock gate (no more different start times per device, no one left on the previous episode)
- Joining a party from the home screen's Party button opens the host's movie and lands you on the room timeline the moment your player is ready - never from 00:00
- Home Party button joins only (code + password); the movie page's Watch Party button creates the room and fills in the movie/episode automatically
- The party panel shows live sync diagnostics (latency + drift) so you can see the room is together

Fixes
- Drift correction rebuilt: subtle 4% speed nudges for small differences, one clean seek only past 2 seconds (the old logic seeked constantly and stuttered), and no corrections at all before a scheduled start opens
- Clock estimates anchor on the lowest-latency heartbeats so corrections follow the true timeline instead of network noise
- Pause/resume/seek reach every participant tighter (0.6s threshold) with ordered events and echo suppression
- Late joiners wait for their player to be ready, then sync - loading speed no longer decides where you start
- The built-in ad shield keeps growing: curated advertising/popunder/tracking domains are blocked while media always rides first, and the per-source compatibility exceptions stay so sandbox-breaking players keep working (AdGuard-style protection without the playback breakage)
