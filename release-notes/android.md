The StreamBox FluidMotion Update - next-generation smooth playback, engineered for old and cheap Android TVs as hard as for flagships.

What's new
- FluidMotion Engine: adaptive playback optimization across the whole ecosystem (web app, PWA, both WebView APKs). Real capability detection (requestVideoFrameCallback, WebGL2, memory/cores, TV profile), four auto performance tiers with watchdog + hysteresis, and a motion-enhancement section in player settings (Auto / Smooth 60 / Balanced / Low-End TV Boost / Cinematic / Off) that is fully wired to the live pipeline - no decorative switches
- Genuine motion-compensated frame interpolation on owned streams: block-matching motion estimation on GPU (WebGL2), bidirectional motion-compensated warp synthesis with scene-change + match-confidence gating and occlusion-safe fallback. Intermediates render at their true timestamps - audio, duration and subtitle timing never change. Runs where frames are legally readable (the owned video shell with CORS-clean sources); on capable hardware 24/30fps content presents as smooth 60fps-class motion
- Honest limits where they belong: provider embeds are opaque cross-origin iframes - nobody can read their frames - so they report "interpolation unavailable - frames not accessible" and get what actually helps: frame pacing, stall/quality watchdog and background suspension instead of fake FPS numbers. FluidMotion Stats shows real measurements only (rVFC frame counts, getVideoPlaybackQuality drops, WebGL backend, tier) and labels estimates as estimates
- Playback-active mode: while video plays, decorative animation, hero motion and blur work pause (real CPU/GPU relief on 1-2GB TV sticks - the "Low-End TV Boost" tier additionally flattens blur entirely). Layout and playback are untouched
- Adaptive quality (opt-in, default off): repeated decoder/network stalls step quality down one rung when the pill is on Auto - a manually picked quality is never overridden
- Owned-stream loader (window._hlsSource / hlsLoad) with graceful degradation: a source without CORS consent falls back to plain playback instead of dying

Housekeeping
- Build 241; wrapper 2.40 (versionCode 39). QA gate extended with 4 FluidMotion locks (settings wiring, honesty, genuine MEMC, performance posture) - 0 failures
- Native track: FluidMotion frame-rate matching (Surface.setFrameRate) + dropped-frame watchdog on the native branch (native 0.110)

Honest limits
- Interpolation exists only where frames are legally readable; embed providers cannot be interpolated by anyone (browser security), and we never pretend otherwise
- Display refresh-rate switching from the web is not possible (no browser API) - the native track handles true refresh matching on capable TVs
- Physical-device verification of interpolation smoothness is pending user testing; the pipeline, math and gating are verified in automated browser tests against real moving content
