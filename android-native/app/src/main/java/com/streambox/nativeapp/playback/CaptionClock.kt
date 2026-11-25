package com.streambox.nativeapp.playback

/** Caption media-clock ESTIMATOR v4 (0.220) - the native port of the web
    build's capNow v4 fix for "subtitles so fast / ahead of the voices".

    History (same on both platforms):
      - the old clock hard-reset to every (thinned, transport-late) provider
        message: each late message YANKED captions backward (visible delay)
        while wall-clock extrapolation between messages jumped ahead (the
        early flash) - fixed in 0.210 with a free-run estimator;
      - v2's free-run then trusted the CLAIMED playback rate and its lag
        compensation could mask runaway drift during buffering stalls -
        captions raced ahead of the voices. v4 composes the proven parts:
          - MEASURED speed: d(cur)/d(wall) from provider position deltas
            wins over any claimed rate (kills rate-lie runaway; follows the
            real 1.5x/2x speed changes). Claimed rate is FALLBACK only.
          - EVIDENCE-BASED stall freeze: provider pulses ARE arriving yet
            the position does not move (1.2s) = real stall -> the clock
            freezes with the picture; total position silence > 8s also
            freezes. Sparse-but-healthy providers (quiet between messages)
            NEVER false-freeze.
          - MESSAGE-GATED correction: the transport-lag learning/correction
            runs ONLY when a new position measurement arrives (every-tick
            correction fights free-run). Lag is capped at 2.0s so it can
            never mask real drift (beyond the cap the snap self-heals).
          - snap-to-truth only for real jumps (|innov| > 2.5s) with a full
            speed re-measurement reset afterwards; absolute monotonic clamp
            while playing (only a real seek moves captions backward).

    The subtitle OFFSET is added AFTER this clock (settings adjust it) -
    same seam as the web's `subMapTime(capNow())`. */
class CaptionClock {

    private var est = -1f
    private var at = 0L
    private var raw = -1f
    private var rawAt = 0L
    private var lastMsgAt = 0L
    private var lag = 0f
    private var last = -1f
    // MEASURED speed from provider position deltas (wins over claims)
    private var rateEst = 0f
    private var rateN = 0

    /** The caption timeline position for NOW. [truth] is the reported
        (provider/UI) position, [rate] the CLAIMED playback speed (fallback
        only - the measured rate wins once one exists), [paused]/[seeking]
        force truth mode, [nowMs] the wall clock this estimate is for, and
        [provMsgAt] the arrival time of the last provider message pulse
        (stall evidence - 0 means no pulse tracking). Never throws. */
    fun now(
        truth: Float,
        rate: Float,
        paused: Boolean,
        seeking: Boolean,
        nowMs: Long,
        provMsgAt: Long = 0L,
    ): Float {
        return try {
            val cur = if (truth > 0f) truth else 0f
            // truth while paused/seeking (web parity): the reported value
            // IS the timeline - and the estimator re-seeds cleanly
            if (paused || seeking || at == 0L) {
                est = cur
                at = nowMs
                raw = cur
                rawAt = nowMs
                lastMsgAt = nowMs
                lag = 0f
                last = cur
                rateEst = 0f
                rateN = 0
                return cur
            }
            // MEASURED speed wins over any claimed rate (claimed = fallback)
            val rateBefore =
                if (rateN > 0 && rateEst >= 0f) rateEst
                else (if (rate > 0f) rate else 1f)
            // stall evidence (never fires for sparse-but-healthy providers):
            //   fast: provider pulses ARE arriving but the position is
            //         frozen (1.2s) - a real stall
            //   slow: total position silence for 8s
            val staleFor = nowMs - (if (rawAt != 0L) rawAt else nowMs)
            val msgFresh = provMsgAt > 0L && (nowMs - provMsgAt) < 1500L
            val stalled = (msgFresh && staleFor > 1200L) || staleFor > 8000L
            // free-run between measurements at the winning rate - frozen
            // solid while the evidence says the picture is stalled
            var e = if (stalled) est else est + ((nowMs - at) / 1000f) * rateBefore
            if (cur != raw) {
                // ---- a REAL provider measurement arrived (the ONLY place
                // ---- correction may run - message-gated, never per-tick)
                val dw = (nowMs - (if (rawAt != 0L) rawAt else nowMs)) / 1000f
                val dc = cur - raw
                // implausible samples never teach the rate (seek echoes,
                // clock jumps, impossible speeds)
                if (dw > 0.05f && dw < 10f && dc >= 0f && dc / dw < 8f) {
                    val inst = dc / dw
                    rateEst = when {
                        rateN == 0 -> inst
                        // fast adoption when the speed really changed
                        kotlin.math.abs(inst - rateEst) > 0.25f ->
                            rateEst * 0.2f + inst * 0.8f
                        // steady state: slow EMA
                        else -> rateEst * 0.6f + inst * 0.4f
                    }
                    rateN += 1
                }
                // transport-lag learning: the residual of free-run ahead of
                // this stale message is the transport delay - absorbed
                // smoothly and CAPPED at 2.0s so it can never mask real
                // drift (beyond the cap the snap self-heals)
                val lagGuess = (e - cur).coerceIn(0f, 2.0f)
                lag = lag * 0.8f + lagGuess * 0.2f
                val target = cur + lag // where the media really is now
                val innov = target - e
                if (kotlin.math.abs(innov) > 2.5f) {
                    // seek / jump / drift beyond repair: snap to truth and
                    // re-measure the speed from scratch
                    e = cur
                    lag = 0f
                    rateEst = 0f
                    rateN = 0
                    last = e
                } else {
                    // smooth correction, never a yank
                    e += innov * 0.3f
                }
                raw = cur
                rawAt = nowMs
                lastMsgAt = nowMs
            }
            // ABSOLUTE monotonic guarantee while playing: a rogue or echoed
            // message can never pull captions backward (only a real seek
            // snap can)
            if (last >= 0f && e < last) e = last
            last = e
            est = e
            at = nowMs
            e
        } catch (e: Exception) {
            if (truth > 0f) truth else 0f
        }
    }

    /** A REAL seek (the app's own seek command or scrub): snap instantly
        and reset the speed measurement (a seek invalidates it). This is
        the ONLY way the caption timeline ever moves backward. */
    fun snap(pos: Float) {
        est = pos
        raw = pos
        rawAt = 0L
        lag = 0f
        last = pos
        rateEst = 0f
        rateN = 0
        at = System.currentTimeMillis()
    }
}
