package com.streambox.nativeapp.playback

/** Caption media-clock ESTIMATOR (0.210) - the native port of the web
    build's capNow() fix for the user-reported "subtitles delayed, and
    sometimes a flash of the next line early" bug.

    Root cause (identical on both platforms): provider timeupdates are
    THINNED (to ~4Hz here - the 0.74 weak-box rule) and often transport-
    late: their data describes where the video WAS. A caption clock that
    hard-resets to every message YANKS captions backward (visible delay),
    while pure wall-clock extrapolation between messages jumps ahead (the
    early flash). This estimator instead free-runs at the REAL playback
    rate and corrects toward staleness-compensated measurements:
      - it learns the transport lag (est - msg) and aims at msg + lag
      - late messages therefore never pull the timeline backward
      - a genuine slow media clock is corrected SMOOTHLY (never per-message)
      - seeks / episode jumps snap instantly (|innov| > 2.5s) - and the
        app's own seek/scrub state snaps explicitly through snap()
      - paused / scrubbing / seeking: the reported position IS the truth
      - ABSOLUTE monotonic clamp while playing: a rogue or echoed message
        can never move captions backward (only a real seek can)

    The subtitle OFFSET is added AFTER this clock (settings adjust it) -
    same seam as the web's `capNow() + (subFile.offset || 0)`. */
class CaptionClock {

    private var est = -1f
    private var at = 0L
    private var raw = -1f
    private var lag = 0f
    private var last = -1f

    /** The caption timeline position for NOW. [truth] is the reported
        (provider/UI) position, [rate] the real playback speed, [paused]/
        [seeking] force truth mode, [nowMs] the wall clock this estimate is
        for. Never throws - falls back to the reported position. */
    fun now(truth: Float, rate: Float, paused: Boolean, seeking: Boolean, nowMs: Long): Float {
        return try {
            val cur = if (truth > 0f) truth else 0f
            val r = if (rate > 0f) rate else 1f
            // truth while paused/seeking (web parity): the reported value
            // IS the timeline - and the estimator re-seeds cleanly
            if (paused || seeking || at == 0L) {
                est = cur
                at = nowMs
                raw = cur
                lag = 0f
                last = cur
                return cur
            }
            // free-run between measurements, scaled by the actual
            // playback rate (a 1.5x stream advances captions at 1.5x)
            var e = est + ((nowMs - at) / 1000f) * r
            if (cur != raw) {
                // apparent transport staleness: how far ahead our clock
                // already is - learned, never assumed
                val l = if (e > cur) e - cur else 0f
                lag = lag * 0.85f + l * 0.15f
                val target = cur + lag // where the media really is now
                val innov = target - e
                if (kotlin.math.abs(innov) > 2.5f) {
                    // seek / episode jump: snap to truth instantly
                    e = cur
                    lag = 0f
                    last = e
                } else {
                    // smooth correction, never a yank
                    e += innov * 0.3f
                }
                raw = cur
            }
            // ABSOLUTE monotonic guarantee while playing: a single rogue
            // or echoed message can never pull captions backward (only a
            // real seek can)
            if (last >= 0f && e < last) e = last
            last = e
            est = e
            at = nowMs
            e
        } catch (e: Exception) {
            if (truth > 0f) truth else 0f
        }
    }

    /** A REAL seek (the app's own seek command or scrub): snap instantly.
        This is the ONLY way the caption timeline ever moves backward. */
    fun snap(pos: Float) {
        est = pos
        raw = pos
        lag = 0f
        last = pos
        at = System.currentTimeMillis()
    }
}
