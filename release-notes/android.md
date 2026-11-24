The StreamBox StabilityGuard Update - P0 playback reliability for old and cheap Android TVs. This one is about never losing audio or subtitles again.

What's new
- StabilityGuard: a repair-first stability ladder that kills the "stutter -> video continues but audio dies and subtitles freeze" failure class. It PREVENTS the failure (early degradation signals demote optional work before anything essential suffers), REPAIRS individual components (re-queries real playback state through the documented player commands, clears stuck pause/seek flags, re-anchors subtitles instantly - no reload), NUDGES audio back through documented controls (never overriding your own mute), and only as a LAST RESORT does a controlled pause/play kick or a budgeted keep-position restart (max once per 15 minutes, max 3 per hour - no more restart loops)
- Root cause fixed at the source: the subtitle freeze was a stuck-state failure in our media clock (a pause/seek flag whose clearing event was lost in the stutter). StabilityGuard detects the exact contradiction and repairs against provider truth. Reproduced and fixed in automated tests end-to-end
- Audio continuity protection: audio restored after overload/focus loss through documented commands; intentional mutes are always respected; on the native app, real AudioManager focus + headphone/HDMI route handling
- Stability settings (all on by default, all wired to real subsystems): Auto stability, Audio continuity, Subtitle resync. Essential playback never depends on flipping a switch
- StabilityGuard diagnostics in FluidMotion Stats: health, recovery level, JS pressure, position cadence, subtitle clock, repair/nudge/kick/restart counters - real numbers only
- Stability beats smoothness: generated motion frames are the FIRST thing sacrificed whenever playback stability is recovering (QA-locked priority order)

Honest limits
- Provider iframe decoders cannot be reached by anyone (browser security): we prevent, detect, nudge and budget - we do not claim to repair a provider's internal decoder
- Automated tests prove the failure mechanics and the repair path; 60-120 minute sessions on physical 1-2GB Android TVs are still required to certify device-level audio recovery
