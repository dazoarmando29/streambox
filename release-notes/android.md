The StreamBox Subtitle Sync Fix - captions finally keep time.

What is fixed
- Subtitles no longer sit delayed behind the dialogue and no longer flash ahead of it (the "delayed and sometimes early" bug on CineSrc and every other provider). Root cause: the caption clock hard-reset to every provider timeupdate, and those messages are often a second or two STALE - each one yanked captions backward while the extrapolation between them jumped forward. The clock is now a proper media-clock estimator: it free-runs at your real playback rate (fixes growing delay at 1.5x/2x too), learns the message transport lag so late news never pulls captions backward, corrects a genuinely slow stream smoothly instead of jittering, and snaps instantly only on real seeks. Absolute rule: while playing, the displayed timeline can never move backward
- Verified against a simulated 1.2s-late message stream (the exact reported scenario): zero backward movements, correct cue boundaries, correct speed scaling, correct seek behavior, frozen-while-paused - and permanently QA-locked so no future change can reintroduce it on any movie or show

The StreamBox Provider Compatibility Update - FluidMotion grows where it legally can, and tells the truth everywhere else.

What is new
- Universal Playback Adapter: every source automatically gets the most capable authorized playback path, with a live status that never overstates it - Genuine MEMC on owned streams, adaptive frame pacing + StabilityGuard on provider embeds, and an explicit "frames not accessible" honesty line (provider iframes are browser-locked to everyone; we do not bypass that)
- Experimental Capture MEMC (OFF by default): with YOUR explicit screen-share consent, captured frames run through the genuine motion-compensation engine - live latency is shown and the mode disables itself before it could ever hurt A/V sync
- Owned-stream track pills (embedded audio/subtitle tracks), smarter low-end interpolation (half-cost motion search on weak devices, early-exit matching), one shared engine across all platforms

Honest numbers
- Engine sustained interpolation measured on worst-case software GL: ~1.1 generated frames/sec at 360p (floor value; hardware GPUs on real devices are far faster). No 60fps claim until physical devices prove it - and StabilityGuard still yields generated frames first whenever playback needs the headroom

The StreamBox StabilityGuard Update - P0 playback reliability for old and cheap Android TVs. This one is about never losing audio or subtitles again.

What's new
- StabilityGuard: a repair-first stability ladder that kills the "stutter -> video continues but audio dies and subtitles freeze" failure class. It PREVENTS the failure (early degradation signals demote optional work before anything essential suffers), REPAIRS individual components (re-queries real playback state through the documented player commands, clears stuck pause/seek flags, re-anchors subtitles instantly - no reload), NUDGES audio back through documented controls (never overriding your own mute), and only as a LAST RESORT does a controlled pause/play kick or a budgeted keep-position restart (max once per 15 minutes, max 3 per hour - no more restart loops)
- Root cause fixed at the source: the subtitle freeze was a stuck-state failure in our media clock (a pause/seek flag whose clearing event was lost in the stutter). StabilityGuard detects the exact contradiction and repairs against provider truth. Reproduced and fixed in automated tests end-to-end
- Audio continuity protection: audio restored after overload/focus loss through documented commands; intentional mutes are always respected; on the native app, real AudioManager focus + headphone/HDMI route handling
- Stability settings (all on by default, all wired to real subsystems): Auto stability, Audio continuity, Subtitle resync. Essential playback never depends on flipping a switch
- Stability beats smoothness: generated motion frames are the FIRST thing sacrificed whenever playback stability is recovering (QA-locked priority order)

Honest limits
- Provider iframe decoders cannot be reached by anyone (browser security): we prevent, detect, nudge and budget - we do not claim to repair a provider's internal decoder
- Automated tests prove the failure mechanics and the repair path; 60-120 minute sessions on physical 1-2GB Android TVs are still required to certify device-level audio recovery
