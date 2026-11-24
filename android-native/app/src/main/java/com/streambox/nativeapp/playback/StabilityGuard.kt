package com.streambox.nativeapp.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.flow.MutableStateFlow

/** StabilityGuard (0.120) - the P0 playback stability engine, ported 1:1
    from the web build (same ladder, same cooldowns, same suppression).

    The production bug this exists to kill (worst on 1-2GB Android TV boxes):
    overload -> stutter -> video resumes but AUDIO dies and SUBTITLES FREEZE
    at the stutter timestamp -> the old recovery then fully restarts -> loop.
    Root mechanics (verified in both codebases): during the stutter a
    provider 'pause' flag is set (or the bridge clock stream dies) and the
    clearing event is lost - the caption clock then pins subtitles to the
    stutter timestamp forever while the media element keeps rendering.
    Nothing revalidated state, and nothing re-kicked audio.

    Priority override: reliable playback, continuous audio, continuous
    subtitles, stable video, no unnecessary restarts - in that order,
    ALWAYS above interpolation/effects (FluidMotion yields at level >= 2).

    Recovery ladder (never restart-first):
    L0 prevent  - early signals (event-loop lag, position-cadence decay,
                  dropped frames) demote optional work BEFORE failure
    L1 repair   - re-query authoritative provider state via the documented
                  command API (getPaused/getMuted/getPlaybackRate), clear
                  stuck flags, re-anchor subtitles. NO reload. NEVER reload
                  for L1.
    L2 nudge    - audio continuity (documented setMuted(false) ONLY without
                  user mute intent; on the owned Media3 path a controlled
                  playWhenReady + seekTo(currentPosition) nudge that
                  preserves the position).
    L3 kick     - controlled pause/play through documented commands
                  (~600ms apart); position, tracks and settings untouched.
    L4 restart  - the EXISTING reopen-at-position machinery only (the
                  PlayerScreen webGen rebuild for provider embeds, the
                  existing load(startAt) for the owned HLS slot). Budgeted:
                  15min cooldown + max 3 per hour. Never restarts from zero.

    False-trigger suppression (critical): never acts on a user pause, a
    user mute (for mute nudges), an in-flight seek (<2s), an episode /
    provider transition (the app's sync-grace window), or a short stall
    (every observation-based signal must be SUSTAINED across ticks).

    Honest limits (never break these): provider iframe decoders and their
    audio pipelines are not directly controllable - every action goes
    through documented provider commands or state the app owns. A pause
    pressed inside the provider's own embed UI is indistinguishable from a
    source-issued pause; the ladder treats an uncommanded pause as suspect
    (web parity) but every action stays rate-limited. */
object StabilityGuard {

    /** Ladder rung of the LAST action taken. 0 = normal/prevention armed,
        1 = sync repair, 2 = audio nudge (FluidMotion yields from here),
        3 = controlled kick, 4 = full restart. */
    enum class Action { REANCHOR, SYNC_REPAIR, AUDIO_NUDGE, KICK, RESTART }

    /** Real, live diagnostics - "Unavailable" where a value is unknown. */
    data class GuardState(
        val level: Int = 0,
        val repairs: Int = 0,
        val nudges: Int = 0,
        val kicks: Int = 0,
        val restarts: Int = 0,
        val lastReason: String = "Unavailable",
        val audioFocus: String = "Unavailable",
        val protectionOn: Boolean = true,
        val audioOn: Boolean = true,
        val subResyncOn: Boolean = true,
    )

    /** One tick's worth of player truth. The PlayerScreen watch loop builds
        this from its own state + the authoritative WebView/Media3 read. */
    data class GuardInput(
        /** Local playing FLAG (may go stale - that is what L1 repairs). */
        val playingFlag: Boolean,
        /** Explicit user pause intent (app toggle / room pause only). */
        val userPaused: Boolean,
        /** Explicit user mute intent (app toggle only). */
        val userMuted: Boolean,
        /** Real media is live (watchdog's "gotMedia" seam). */
        val mediaLive: Boolean,
        /** Episode/provider transition grace window (never act inside). */
        val inTransition: Boolean,
        /** Seek command / scrub younger than 2s (never act inside). */
        val seekCmdRecent: Boolean,
        /** The UI scrub handle position (-1 = none). */
        val scrub: Float,
        /** WebView provider embed (false = the owned Media3 HLS slot). */
        val webPath: Boolean,
        /** "" + season + episode + mirror - resets the state machine. */
        val sessionKey: String,
        /** App-side clock (pm.cur) and provider-reported clock. */
        val localClock: Float,
        val providerClock: Float,
        /** Authoritative element read (WebView eval or Media3 state). */
        val readOk: Boolean,
        /** WebView evals stopped answering (silent zombie renderer). */
        val readDead: Boolean,
        val readPos: Float,
        val readPaused: Boolean,
        val readMuted: Boolean,
        val readVolume: Float,
        val readRate: Float,
        /** The user's own rate pick - a warped provider rate is restored. */
        val expectedRate: Float,
        /** webkitAudioDecodedByteCount (-1 = the property is unavailable). */
        val audioDecoded: Long,
        /** Media3 reports playWhenReady but is not playing (stalled). */
        val media3Stalled: Boolean,
    )

    val state: MutableStateFlow<GuardState> = MutableStateFlow(GuardState())

    /** PlayerScreen installs an executor so async events (audio focus,
        route changes, Media3 audio errors) can run ladder actions on the
        live player. (action, authoritativePos) - pos may be -1. */
    @Volatile
    var executor: ((Action, Float) -> Unit)? = null

    // ---- settings (persisted, each gates its own subsystem) -------------
    @Volatile var protectionOn = true; private set
    @Volatile var audioOn = true; private set
    @Volatile var subResyncOn = true; private set

    @JvmStatic
    fun setProtection(ctx: Context, on: Boolean) {
        protectionOn = on
        try { Store.setStabilityGuard(ctx, on) } catch (e: Exception) {}
        publish()
    }

    @JvmStatic
    fun setAudio(ctx: Context, on: Boolean) {
        audioOn = on
        try { Store.setStabilityAudio(ctx, on) } catch (e: Exception) {}
        if (!on) abandonFocus() else if (lastPlayingIntent) requestFocus()
        publish()
    }

    @JvmStatic
    fun setSubResync(ctx: Context, on: Boolean) {
        subResyncOn = on
        try { Store.setStabilitySubResync(ctx, on) } catch (e: Exception) {}
        publish()
    }

    // ---- ladder state ---------------------------------------------------
    @Volatile private var level = 0
    @Volatile private var repairs = 0
    @Volatile private var nudges = 0
    @Volatile private var kicks = 0
    @Volatile private var restarts = 0
    @Volatile private var lastReason = ""
    private var lastLevelAt = 0L

    // Cooldowns (web parity): repair 20s, nudge 5min, kick 5min,
    // restart 15min + max 3 per clock hour.
    private var coolRepair = 0L
    private var coolNudge = 0L
    private var coolKick = 0L
    private var coolRestart = 0L
    private var restartHour = -1
    private var restartHourN = 0

    // ---- signal window (cross-tick memory) ------------------------------
    private var lastMoveAt = 0L
    private var prevProviderClock = -1f
    private var prevReadPos = -1f
    private var prevScrub = -1f
    private var prevScrubAt = 0L
    private var subsStale = false
    private var driftObs = 0
    private var mutedObs = 0
    private var readDeadObs = 0
    private var pausedStuckObs = 0
    private var loopLatencyEma = 0f
    @Volatile private var lastTickAt = 0L
    @Volatile private var lastReadPos = -1f
    @Volatile private var lastPlayingIntent = false
    @Volatile private var kickUntil = 0L
    @Volatile private var media3AudioErrorAt = 0L
    @Volatile private var sessionKey = ""

    /** True while our own L3 kick's pause/play echoes are in flight - the
        bridge must not adopt them as real state changes. */
    @JvmStatic
    fun kickEchoActive(): Boolean = System.currentTimeMillis() < kickUntil

    /** Media3 audio-session errors (renderer init/write/decode). Latches
        the signal and asks for the L2 recovery immediately (position-
        preserving nudge on the owned path). */
    @JvmStatic
    fun noteMedia3AudioError(code: Int): Action? {
        media3AudioErrorAt = System.currentTimeMillis()
        if (!protectionOn || !audioOn) return null
        if (!lastPlayingIntent) return null
        if (System.currentTimeMillis() - coolNudge < NUDGE_EVENT_MS) return null
        record(2, "L2 Media3 audio error " + code)
        coolNudge = System.currentTimeMillis()
        return Action.AUDIO_NUDGE
    }

    // ---- L0 prevention: demote optional work BEFORE anything essential --

    /** Frame pressure (Media3 drop bursts, JS frame-gap detector) or
        sustained event-loop lag: FluidMotion demotes first and decorative
        work stands down - interpolation/effects are the FIRST sacrifice. */
    @JvmStatic
    fun notePressure(reason: String) {
        if (!protectionOn) return
        try {
            FluidMotion.demote(reason)
            FluidMotion.suspendDecorations.value = true
        } catch (e: Exception) {}
        if (level == 0) {
            lastReason = "L0 " + reason
            publish()
        }
    }

    /** The watch loop reports its own scheduling lateness - a smoothed
        lag over 400ms is the box choking (web parity: loopMs > 400). */
    @JvmStatic
    fun noteLag(latencyMs: Long) {
        loopLatencyEma = loopLatencyEma * 0.8f + latencyMs * 0.2f
    }

    // ---- async audio events (focus + route changes) ---------------------

    /** Audio focus gained after a loss / route change while playing: run
        the L2 nudge instead of leaving dead audio. Own 30s event spacing
        (an event must always be able to nudge; the tick cooldown covers
        observation-driven nudges). */
    @JvmStatic
    fun onAudioRouteEvent(reason: String): Action? {
        if (!protectionOn || !audioOn) return null
        if (!lastPlayingIntent) return null
        val now = System.currentTimeMillis()
        if (now - coolNudge < NUDGE_EVENT_MS) return null
        coolNudge = now
        record(2, "L2 " + reason)
        return Action.AUDIO_NUDGE
    }

    // ---- audio focus (API-guarded, lifecycle-safe) ----------------------
    @Volatile private var appCtx: Context? = null
    private var audioManager: AudioManager? = null
    private var focusReq: AudioFocusRequest? = null
    @Volatile private var focusState = "Unavailable"
    @Volatile private var focusLostAt = 0L

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        try {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    val lost = focusLostAt
                    focusLostAt = 0L
                    focusState = "held"
                    if (lost > 0) {
                        val a = onAudioRouteEvent("focus regained after loss")
                        if (a != null) executor?.invoke(a, lastReadPos)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    focusState = "lost"
                    if (focusLostAt == 0L) focusLostAt = System.currentTimeMillis()
                }
            }
            publish()
        } catch (e: Exception) {}
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            try {
                val a = onAudioRouteEvent("route change (audio becoming noisy)")
                if (a != null) executor?.invoke(a, lastReadPos)
            } catch (e: Exception) {}
        }
    }

    /** Attach per play session (PlayerScreen's DisposableEffect). Registers
        the noisy/headset route receiver; nothing else starts until asked. */
    @JvmStatic
    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext ?: ctx
        audioManager = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            protectionOn = Store.stabilityGuard(ctx)
            audioOn = Store.stabilityAudio(ctx)
            subResyncOn = Store.stabilitySubResync(ctx)
        } catch (e: Exception) {}
        try {
            val f = IntentFilter()
            f.addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            f.addAction(Intent.ACTION_HEADSET_PLUG)
            f.addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG)
            val a = appCtx ?: return
            // System broadcasts are exempt from the API 33 receiver-export
            // rule, but pass the flag where it exists (lifecycle-safe).
            if (Build.VERSION.SDK_INT >= 33) {
                a.registerReceiver(noisyReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                a.registerReceiver(noisyReceiver, f)
            }
        } catch (e: Exception) {}
        publish()
    }

    @JvmStatic
    fun detach(ctx: Context) {
        try { appCtx?.unregisterReceiver(noisyReceiver) } catch (e: Exception) {}
        abandonFocus()
        executor = null
    }

    /** Request audio focus while playing (API-guarded). */
    @JvmStatic
    fun requestFocus() {
        if (!audioOn) return
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusListener)
                    .build()
                focusReq = req
                @Suppress("DEPRECATION")
                val r = am.requestAudioFocus(req)
                focusState = if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "held" else "denied"
            } else {
                @Suppress("DEPRECATION")
                val r = am.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN,
                )
                focusState = if (r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) "held" else "denied"
            }
        } catch (e: Exception) {
            focusState = "Unavailable"
        }
        publish()
    }

    /** Abandon on stop (the player screen leaves / playback ends). */
    @JvmStatic
    fun abandonFocus() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                focusReq?.let { am.abandonAudioFocusRequest(it) }
                focusReq = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) {}
        focusState = "none"
        focusLostAt = 0L
        publish()
    }

    // ---- the ladder driver ----------------------------------------------

    /** Called once per watch tick with the freshest player truth. Returns
        the ONE action to run now, or null. Cooldowns, sustained-signal
        requirements and every suppression gate live here (never in the UI). */
    @JvmStatic
    @Synchronized
    fun tick(i: GuardInput): Action? {
        val now = System.currentTimeMillis()
        // event-loop lag probe (web parity: 1s-tick drift smoothed)
        if (lastTickAt > 0L) noteLag((now - lastTickAt) - TICK_MS)
        lastTickAt = now
        lastPlayingIntent = i.mediaLive && !i.userPaused

        // a new episode/provider/mirror is a fresh state machine (web parity)
        if (i.sessionKey != sessionKey) {
            sessionKey = i.sessionKey
            level = 0
            subsStale = false
            driftObs = 0
            mutedObs = 0
            readDeadObs = 0
            pausedStuckObs = 0
            lastMoveAt = now
            publish()
        }

        // cadence: ms since the LAST real position change on either clock.
        val moved = (i.providerClock != prevProviderClock && i.providerClock >= 0f) ||
            (i.readOk && i.readPos >= 0f && kotlin.math.abs(i.readPos - prevReadPos) > 0.05f) ||
            (i.readOk && prevReadPos < 0f && i.readPos >= 0f)
        if (moved) lastMoveAt = now
        if (i.providerClock >= 0f) prevProviderClock = i.providerClock
        if (i.readOk && i.readPos >= 0f) {
            prevReadPos = i.readPos
            lastReadPos = i.readPos
        }
        if (lastMoveAt == 0L) lastMoveAt = now
        val stalled = now - lastMoveAt

        // scrub recency (an ACTIVE drag is a seek in flight; a scrub handle
        // frozen for over 5s is a STUCK flag L1 must clear)
        if (i.scrub != prevScrub) {
            prevScrub = i.scrub
            prevScrubAt = now
        }
        val scrubLive = i.scrub >= 0f && now - prevScrubAt < 2000L
        val seekInFlight = i.seekCmdRecent || scrubLive

        val playingIntent = lastPlayingIntent
        // caption clock staleness (web parity: cadence decay marks it)
        if (playingIntent && stalled > 4000L) subsStale = true

        if (!protectionOn) {
            // even with the ladder OFF, subtitle auto-resync keeps its own
            // subsystem alive (no decorative toggles - each gates its own)
            FluidMotion.yieldToStability(false)
            publish()
            return reanchorOnly(i, now)
        }

        // ---- stuck-seek flag (the frozen-caption mechanism) -------------
        if (i.scrub >= 0f && !scrubLive && playingIntent) {
            if (coolOk(coolRepair, REPAIR_COOLDOWN_MS, now)) {
                coolRepair = now
                record(1, "L1 stuck-seek flag")
                return Action.SYNC_REPAIR
            }
        }

        // ---- L0 prevention: yield optional work FIRST -------------------
        if (playingIntent && (loopLatencyEma > 400f || (stalled > 2000L && stalled <= 6000L))) {
            notePressure("early degradation")
            if (level == 0) {
                level = 1
                lastLevelAt = now
                lastReason = "L0 early degradation"
            }
            publish()
            return null
        }

        // ---- contradiction: position advancing while flagged paused -----
        // (a stutter dropped the clearing 'play' event - the flag is stale)
        if (i.webPath && playingIntent && !i.playingFlag && !i.userPaused &&
            !seekInFlight && !i.inTransition && stalled < 2200L
        ) {
            if (coolOk(coolRepair, REPAIR_COOLDOWN_MS, now)) {
                coolRepair = now
                record(1, "L1 paused-flag-stale")
                return Action.SYNC_REPAIR
            }
        }

        // ---- THE P0 CASE: playback expected, paused flag likely stuck
        // (the clearing event was lost in the stutter) and clock frozen ----
        if (i.webPath && playingIntent && !i.playingFlag && !i.userPaused &&
            !seekInFlight && !i.inTransition && stalled > 8000L
        ) {
            if (coolOk(coolRepair, REPAIR_COOLDOWN_MS, now)) {
                coolRepair = now
                record(1, "L1 paused-stuck-after-stutter")
                return Action.SYNC_REPAIR
            }
        }

        // ---- vice versa: flagged PLAYING while the element reports
        // paused (the stale flag runs the other way). The repair re-queries
        // truth and clears stuck flags - it NEVER guesses and never resumes
        // on its own; two sustained observations before it may act. ----
        if (i.webPath && playingIntent && i.playingFlag && !i.userPaused &&
            !seekInFlight && !i.inTransition && i.readOk && i.readPaused && stalled > 4000L
        ) {
            pausedStuckObs += 1
            if (pausedStuckObs >= 2 && coolOk(coolRepair, REPAIR_COOLDOWN_MS, now)) {
                coolRepair = now
                pausedStuckObs = 0
                record(1, "L1 playing-flag-stale")
                return Action.SYNC_REPAIR
            }
        } else {
            pausedStuckObs = 0
        }

        // ---- native P0 twin: the bridge clock stream died while the
        // element keeps rendering (subtitles pinned at the stutter point).
        // The authoritative read is AHEAD of the app clock - re-anchor. ----
        if (i.webPath && playingIntent && !seekInFlight && !i.inTransition &&
            i.readOk && i.readPos >= 0f
        ) {
            val drift = kotlin.math.abs(i.readPos - i.localClock)
            if (drift > 1.2f && stalled < 2500L) {
                driftObs += 1
                if (driftObs >= 2) {
                    if (coolOk(coolRepair, REPAIR_COOLDOWN_MS, now)) {
                        coolRepair = now
                        record(1, "L1 element clock ahead of provider stream")
                        return Action.SYNC_REPAIR
                    }
                    return reanchorOnly(i, now)
                }
            } else {
                driftObs = 0
            }
        }

        // ---- silent zombie: the WebView stopped answering evals ----------
        if (i.webPath && i.readDead && playingIntent && !i.inTransition) {
            readDeadObs += 1
            if (readDeadObs >= 3 && restartAllowed(now)) {
                record(4, "L4 WebView not answering (silent renderer death)")
                return Action.RESTART
            }
        } else {
            readDeadObs = 0
        }

        // ---- audio appears dead (observed, SUSTAINED) --------------------
        // muted under us: the element says muted while the user did not
        if (playingIntent && !i.userMuted && !seekInFlight && !i.inTransition &&
            i.readOk && i.readMuted && i.readPos >= 0f && stalled < 2500L
        ) {
            mutedObs += 1
            if (mutedObs >= 2 && audioOn) {
                mutedObs = 0
                if (coolOk(coolNudge, NUDGE_COOLDOWN_MS, now)) {
                    coolNudge = now
                    record(2, "L2 muted without user intent")
                    return Action.AUDIO_NUDGE
                }
            }
        } else {
            mutedObs = 0
        }
        // audio pipeline silent: decoded-audio counter frozen while the
        // picture advances (the real P0 "audio gone" - real values only)
        if (playingIntent && audioOn && !i.userMuted && !seekInFlight && !i.inTransition &&
            i.audioDecoded >= 0L && i.readPos >= 0f && stalled < 2500L && !i.readMuted
        ) {
            audioFrozen(i.audioDecoded, now)
            if (audioDeadSince > 0L && now - audioDeadSince >= 8000L &&
                coolOk(coolNudge, NUDGE_COOLDOWN_MS, now)
            ) {
                coolNudge = now
                audioDeadSince = 0L
                record(2, "L2 audio pipeline silent")
                return Action.AUDIO_NUDGE
            }
        }

        // ---- sustained stall while the user expects playback (web
        // parity thresholds: 12s nudge, 25s kick, 60s restart) -----------
        if (playingIntent && !i.userPaused && !seekInFlight && !i.inTransition) {
            if (stalled > 60000L && audioOn && restartAllowed(now)) {
                record(4, "L4 position stalled 60s")
                return Action.RESTART
            }
            if (stalled > 25000L && coolOk(coolKick, KICK_COOLDOWN_MS, now)) {
                coolKick = now
                kickUntil = now + 2500L
                record(3, "L3 position stalled 25s")
                return Action.KICK
            }
            if (stalled > 12000L && audioOn && coolOk(coolNudge, NUDGE_COOLDOWN_MS, now)) {
                coolNudge = now
                record(2, "L2 audio/position suspect")
                return Action.AUDIO_NUDGE
            }
            if (stalled > 6000L && subsStale) {
                if (coolOk(coolRepair, REPAIR_COOLDOWN_MS, now)) {
                    coolRepair = now
                    record(1, "L1 caption clock stale")
                    return Action.SYNC_REPAIR
                }
                return reanchorOnly(i, now)
            }
        }

        // ---- Media3 stall (owned path): same ladder, real recovery ------
        if (!i.webPath && playingIntent && !seekInFlight && !i.inTransition && i.media3Stalled) {
            if (restartAllowed(now) && stalled > 60000L) {
                record(4, "L4 Media3 stalled 60s")
                return Action.RESTART
            }
            if (coolOk(coolNudge, NUDGE_COOLDOWN_MS, now)) {
                coolNudge = now
                record(2, "L2 Media3 stalled (controlled nudge)")
                return Action.AUDIO_NUDGE
            }
        }

        // ---- recovery: cadence is fresh again (web parity hysteresis) ---
        if (playingIntent && stalled < 1200L) {
            subsStale = false
            if (level > 0 && now - lastLevelAt > 60_000L) {
                level = 0
                lastReason = "recovered"
            }
        }
        // priority override: FluidMotion work yields at level >= 2 and is
        // only restored after sustained recovery (its own 120s-per-step
        // hysteresis then climbs back gradually)
        FluidMotion.yieldToStability(level >= 2)
        publish()
        return null
    }

    // ---- internals ------------------------------------------------------

    private const val TICK_MS = 2000L
    private const val REPAIR_COOLDOWN_MS = 20_000L
    private const val NUDGE_COOLDOWN_MS = 300_000L
    private const val KICK_COOLDOWN_MS = 300_000L
    private const val RESTART_COOLDOWN_MS = 900_000L
    private const val RESTART_MAX_PER_HOUR = 3
    /** Event-driven nudges (focus/route) keep their own spacing so an
        event can always reach the player. */
    private const val NUDGE_EVENT_MS = 30_000L

    private var prevAudioDecoded = -1L
    @Volatile private var audioDeadSince = 0L

    private fun coolOk(last: Long, cd: Long, now: Long): Boolean = now - last >= cd

    private fun restartAllowed(now: Long): Boolean {
        if (!coolOk(coolRestart, RESTART_COOLDOWN_MS, now)) return false
        val hour = (now / 3_600_000L).toInt()
        if (hour != restartHour) {
            restartHour = hour
            restartHourN = 0
        }
        if (restartHourN >= RESTART_MAX_PER_HOUR) return false
        coolRestart = now
        restartHourN += 1
        return true
    }

    private fun audioFrozen(sample: Long, now: Long) {
        if (sample != prevAudioDecoded) {
            prevAudioDecoded = sample
            audioDeadSince = 0L
        } else if (audioDeadSince == 0L) {
            audioDeadSince = now
        }
    }

    /** Subtitle auto-resync ONLY (its own subsystem): re-anchor the caption
        clock at the authoritative position - no poke, no counters. Fires
        only when the clock is actually stale or the app clock drifted from
        the authoritative one (never a per-tick no-op). */
    private fun reanchorOnly(i: GuardInput, now: Long): Action? {
        if (!subResyncOn) return null
        if (!i.readOk || i.readPos < 0f) return null
        if (!lastPlayingIntent && !i.playingFlag) return null
        val drift = kotlin.math.abs(i.readPos - i.localClock)
        if (drift > 1.2f || subsStale) {
            subsStale = false
            return Action.REANCHOR
        }
        return null
    }

    private fun record(lvl: Int, reason: String) {
        when (lvl) {
            1 -> repairs += 1
            2 -> nudges += 1
            3 -> kicks += 1
            4 -> restarts += 1
        }
        level = lvl
        lastLevelAt = System.currentTimeMillis()
        lastReason = reason
        publish()
    }

    private fun publish() {
        val s = GuardState(
            level = level,
            repairs = repairs,
            nudges = nudges,
            kicks = kicks,
            restarts = restarts,
            lastReason = if (lastReason.isEmpty()) "Unavailable" else lastReason,
            audioFocus = focusState,
            protectionOn = protectionOn,
            audioOn = audioOn,
            subResyncOn = subResyncOn,
        )
        if (state.value != s) state.value = s
    }

    // ---- diagnostics ----------------------------------------------------

    /** One honest status line for the UI. Real counts only; "Unavailable"
        where a value is not known. */
    @JvmStatic
    fun describe(): String {
        val s = state.value
        return "Guard L" + s.level + " - fixes " + s.repairs + "/" + s.nudges + "/" +
            s.kicks + " - restarts " + s.restarts + " - last: " + s.lastReason +
            " - focus: " + s.audioFocus
    }

    /** Compact stability line for the FluidMotion stats surface. */
    @JvmStatic
    fun summaryLine(): String {
        val s = state.value
        return "guard L" + s.level + " " + s.repairs + "/" + s.nudges + "/" + s.kicks +
            "/" + s.restarts + " " + s.lastReason + " focus " + s.audioFocus
    }

    /** Real audio-focus state for the diagnostics surface ("Unavailable"
        before the first request). */
    @JvmStatic
    fun focusStateName(): String = focusState

    @JvmStatic
    fun levelNow(): Int = level
}
