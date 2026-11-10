Browsing no longer gets stuck, browsing stops the video, and watching together feels together.

Fixes (two found from your night, three found proactively while auditing those)
- Browse "not loading more" is fixed: changing a filter while a page was still loading used to SWALLOW the new request (the load-guard ate it) - the grid stayed empty with no Load More button, exactly "it's stuck there". Loads now always start and the older request loses the grid cleanly; the loading flag can never wedge
- "It keeps playing the video we watched earlier": going to My List (or anywhere) never actually unloaded the player - clearing the frame with an empty URL doesn't unload it on Android browsers, so the old video kept playing behind the browse grid. Every exit path now blanks the player properly (and so does Home)
- Proactive: the party kept driving the hidden player while you browsed (seeks/pauses into a parked frame) - hidden players are now left alone; coming back re-syncs to the room
- Proactive: room events realign your player instantly (within a beat) instead of waiting for the next tick
- Watching together feels together now: drift corrects on a 2s cadence with 10% nudges (1s of drift closes in seconds, not half a minute), pause/play/seek land with a 0.35s tolerance, and every room event triggers an immediate realign

Quality of life
- When the last other person leaves the room, you get one gentle reminder: "You're alone in the party now - tap Party to end it" (no more forgetting the room open after a series finale)
- The Party button in the header now shows a live ring while a room is running (with the room code in its tooltip) - you can browse anywhere and still see the party is alive at a glance

Locked: QA 159 pins the browse fix, the player-stop path, the hidden-player sync guard + constants, and the QoL pieces - all of it fails the build if it regresses
