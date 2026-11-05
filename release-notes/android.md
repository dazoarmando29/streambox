Stability and polish release: the website your app runs got a deep reliability pass - the player, the catalog, Watch Party and the TV experience all benefit. Updates install over the current app in place (same key, same versionCode).

What is new
- The sleep timer now really pauses the server you are watching. Before, only one of the five servers could be paused from the timer - the rest kept playing while the app said "paused". VidRift (which has no pause control here) now says so honestly instead of pretending.
- Sleep when video ends now stops the binge on EVERY server. It used to keep auto-playing the whole night on four of them.
- Watch Party actions survive slow connections: Create, Join and the after-refresh rejoin now wait for the connection instead of silently disappearing when the handshake took longer than 350ms (common on TV Wi-Fi).
- Errors you can act on: a failed search says "Search failed - check your connection" instead of searching forever, and a title that hits a network blip while opening shows a clear retry message instead of a spinner that never stops.
- Your device's Reduce Motion setting is now honored - the same calm treatment as the Lite edition.
- The billboard is back to its designed motion: the slow push-in and the pulsing Trending dot were being run at high speed by the motion tuner. The tuner now only scales interface pops, never the designed keyframes.

Fixes
- Resume no longer leaks between episodes: choosing Continue used to leave the old position behind and start the NEXT episode at the previous episode's spot.
- The details window always opens now - a failed season lookup used to swallow the whole popup with nothing on screen.
- Auto-next can no longer step into an episode that does not exist or has not aired (a failed episode lookup was read as "no limit").
- 21 buttons lost their hover feedback (the base and the hover color were the same) - they lighten on hover again, like the Watch button always did.
- The top bar no longer slides under the notch or status bar on phones; the update and warning banners clear it too.
- Chat chip remove buttons keep their small look but carry a proper 44px touch area - they used to balloon into giant circles inside the chip. Party quick buttons meet the same 44px floor.
- Watch Party server hardening: message size now counts real bytes (the old gate let roughly four times the limit through), room creation is rate-limited per source and capped globally (one noisy client could exhaust the room server for everyone), and stale connection state is cleaned up on disconnect.
- The 18+ keyword filter can no longer get silently switched off for the whole session after one failed lookup.
- Subtitle files with out-of-order cues show every line now.
- A single failed catalog lookup can no longer blank the Anime area for a full hour.
- Browse paging can no longer splice a slow page into a filter you just changed, and long sessions no longer grow memory without bound in Browse.
- Poster corners, skeleton corners and glass panel corners are consistent again; accent shades unified across the app.
- The Up Next card and the Skip button no longer overlap on phones; long titles clamp cleanly in the hero instead of clipping out of it.
- Keyboard fix: pressing Up/Down in cinema mode on VidRift threw an error that killed the controls.
- Watch Party invite and resume flows no longer race the player; chat and room state survive the app being backgrounded and reopened.
