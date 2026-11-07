Watch Party rooms that survive the real world: kicks no longer end the party for everyone, repeated reconnects keep your seat and host role, and room passwords survive reconnects - plus the remote-clock fixes behind it all.

What is new
- Removing a participant from a Watch Party no longer ends the party for EVERYONE (the "kicked" message used to reach the whole room and each client treated it as its own removal)
- Reconnecting twice in a row now lands you back in your own seat: no ghost duplicate of yourself, no lost host role, no "wrong password" lockout on your own room (the resume token used to rotate on every reconnect and strand the next one)
- A returning host reclaims the host role publicly: everyone in the room is told who is hosting now instead of showing a stale host and rejecting the real one's controls
- Room passwords survive reconnects and app restarts - rejoin with the password you typed, silently
- Party rate limits follow your seat across reconnects (they used to reset every time, and the bookkeeping leaked)

Fixes
- Party clock sync no longer freezes on real networks: a synthetic heartbeat echo used to pin the latency estimate at zero and lock out every later sample (drift correction chased bad math after any Wi-Fi/cellular change); echoes without a real client timestamp are ignored and the anchor re-ages
- A re-synced room adopts the chat you missed and the newest event sequence (older replayed events can no longer roll the room state back)
- "You were removed from the party" only appears for the person actually removed
- The app's identity now always comes from the room snapshot, so host/kick checks are right after any reconnect
