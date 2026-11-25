The StreamBox Player Fix - VidBolt plays again, and the ad tabs are gone.

What is fixed
- VidBolt "Playback disabled - remove the sandbox attribute" on the website - FIXED. Root cause found by running VidBolt's own gate code in a live test rig: the page refuses to play inside ANY sandboxed frame - even one with allow-same-origin - so the old "sandbox first, retry raw later" path could never work. VidBolt now loads raw first time, every time, on every title
- Ad pop-up tabs ("every often") - FIXED at every layer we control. The shield's fallback mode used to allow pop-ups "just in case a server breaks" - that permission is what let popup ad engines (one server literally runs a 10-second ad timer) open tabs on you. No sandboxed frame can open a pop-up anymore, in any mode. The page's own pop-up guards now also cover middle-click and form submits, and the APK WebView can no longer open windows at all (multi-window disabled app-wide, every provider, raw frames included)
- Every other server was checked against the same detector: only VidBolt actually refuses sandboxed frames; CineSrc, VidZen, VidSrc, VsEmbed and friends keep their sandbox and its pop-up blocking. The player's status line now tells the truth about each source

What was NOT changed
- Ad blockers stay ON. Nothing in this fix disables, weakens or bypasses an ad blocker - the app-wide ad-host filter, the sandbox pop-up block and your browser's own blockers all keep working. VidBolt simply never needed them off to play

Honest limits
- A handful of sources must run raw to play at all (VidBolt, VidFast, VidLink, YapGrid, AniPM, VidRift). Inside a raw frame the website cannot block that server's own pop-ups - that is a browser security boundary, not a setting. Those same pop-ups are fully blocked in the APK, and your browser's pop-up blocker covers the web. VidBolt's embed ships no pop-up code of its own
- Playback stability still outranks everything else: no server was changed to sandboxed without proof it still plays
