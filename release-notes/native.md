The FluidMotion Engine: real frame-rate matching kills judder at the surface, a dropped-frame watchdog keeps weak boxes smooth, and six motion modes are one tap away - with honest limits about what motion processing can and cannot do inside a provider embed.

Fixes
- FluidMotion frame-rate matching: on the owned playback surface the display is handed the content's own rate (23.976 / 24 / 25 / 29.97 and friends, snapped to real broadcast rates) through Surface.setFrameRate, so fixed-rate sources run an even cadence instead of 3:2 pulldown judder. Cinematic pins the source rate, Smooth prefers 60 where the display holds it, and a display that would have to visibly switch keeps its current rate - never the wedge-prone forced mode switch that froze TV panels in 0.78
- The dropped-frame watchdog watches the real Media3 drop reports over a rolling 10s window: when more than 5% of presented frames are dropped it steps the engine down one internal tier (A to B to C to D), and a tier only steps back up after 120 seconds of clean playback. Under pressure the engine may ask for a smaller video ladder (only in Boost mode, or Auto on a decoder-limited device, and only if adaptive quality is explicitly enabled - never over a manual pick) and decorative UI animation stands down
- Motion modes: Auto, Smooth, Balanced, Boost, Cinematic and Off - one compact "Motion" row in the Audio & Subtitles panel cycles all six on tap and remembers the pick. Off means exactly that: no frame-rate call is ever made
- The status line on the Motion row is honest to the byte: the frame rate it shows is one the platform or player actually measured, and the tier and drop counts are live

Honest limits
- There is NO frame interpolation anywhere in this build, and none is claimed. On provider embeds the video renders inside opaque cross-origin iframes - the frames are not accessible, so motion processing cannot exist there and the app says "provider embed (frames not accessible)". On our own HLS surface it says "native frame-rate matching (no interpolation)" - frame-rate matching is real judder elimination, but synthesized in-between frames are not a thing this build does
- Frame-rate switching is Android 11+ (API 30). Older devices keep their current display rate exactly as before
- Provider embeds keep the display-mode matching path (measured fps matched to an integer-multiple refresh at native resolution, restored on exit); that path stays off on TV, where a display-mode switch can wedge the video surface

Locked by native QA (motion, honesty and player-shape rules must stay). Builds at 0.110.
