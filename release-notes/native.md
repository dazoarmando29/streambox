The watch-in-peace party hardening: no ghost duplicates, no stuck connecting, and source switches follow the room.

Fixes
- Ghost duplicate seats are dead: reconnecting with your resume token now REPLACES the half-open old connection on the hub instead of minting a second you (same fix as the website, hub + app together). A replaced ghost's traffic or late close can never evict the live seat
- The create/join handshake can't strand you on "connecting" anymore: a socket that died before the room answered now retries (and re-sends the request), and a failed join retries with backoff before cleaning up with an honest message - never a zombie state
- A reconnect mid-episode no longer rebuilds the player and yanks you backward: the room's content only opens when it actually differs from what's playing. A dropped content message retries on the next tick instead of being eaten silently
- Pause/play from inside the provider's own player still reaches the room, but now CONFIRMS for a beat first - a buffer stall that auto-resumes cancels itself instead of pausing everyone. Your own quick pause-then-play always lands (the echo of your first press used to swallow the second)
- Episode switching can't corrupt the room timeline: drift correction holds off for 8s after any load (stale-timeline guard) and non-hosts follow the host's episode instead of a provider's auto-next
- Switching server moves the whole room: manual picks (and the host's auto-heals) broadcast a room-wide source switch, everyone follows keeping position, and late joiners open on the room's source

Honest limits
- Two different cuts (sub vs dub) stay approximate in position sync - pause and episodes still follow exactly
- The hub update is LIVE (deployed with this release): ghost replacement, source-follow and room survival work end-to-end now; older hubs answer "bad_message" quietly and the app stays local

Locked by native QA (party relay gates, bridge mapping, and the sync rules must stay). Builds at 0.99.
