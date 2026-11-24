package com.streambox.nativeapp.playback

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/** FluidMotion Engine (0.110) - the native motion pipeline.

    What is REAL here:
    - Frame-rate matching on the owned Media3 surface: Surface.setFrameRate
      (API 30+) hands the display the content's own rate (23.976/24/25/29.97)
      so a fixed-rate source runs an even cadence instead of 3:2 pulldown
      judder. The call is CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS - a display that
      cannot switch without a visible transition keeps its current rate (the
      0.78 lesson: never force a mode switch that can wedge a TV surface).
    - A dropped-frame watchdog over Media3's real drop reports, with a
      hysteresis tier ladder (A->B->C->D down under sustained drops, one
      step back up only after 120s of clean playback).

    What is NOT here (honesty rule, never break this):
    - There is NO frame interpolation. v1 reports
      "native frame-rate matching (no interpolation)" on the owned Media3
      path, and "provider embed (frames not accessible)" on the WebView
      path - provider embeds render inside opaque cross-origin iframes, so
      their frames cannot be read, sampled or synthesized. The only fps
      numbers this engine ever reports are ones the platform or the
      player actually measured. */
object FluidMotion {

    /** User motion modes (persisted keys stay lowercase strings). */
    enum class Profile(val key: String, val label: String) {
        AUTO("auto", "Auto"),
        SMOOTH("smooth", "Smooth"),
        BALANCED("balanced", "Balanced"),
        BOOST("boost", "Boost"),
        CINEMATIC("cinematic", "Cinematic"),
        OFF("off", "Off"),
        ;

        companion object {
            fun fromKey(k: String?): Profile =
                entries.firstOrNull { it.key == k } ?: AUTO
        }
    }

    /** Internal capability/pressure tier. A is the device's probed
        capability ceiling; the watchdog downgrades one step at a time and
        never recovers above the ceiling. */
    enum class Tier { A, B, C, D }

    /** Honest live stats. interpolationAvailable is FALSE in v1 - both
        reason strings say exactly why instead of implying a feature that
        does not exist. */
    data class FluidStats(
        val droppedFrames: Long,
        val presentedApprox: Long,
        val contentFps: Float,
        val displayRefreshRate: Float,
        val decoderName: String,
        val tier: Tier,
        val mode: Profile,
        val interpolationAvailable: Boolean = false,
        val interpolationReason: String = "",
        // StabilityGuard fields (0.120): live stability state on the same
        // honest stats surface - real counters only, "Unavailable" where
        // nothing is known yet.
        val stabilityLevel: Int = 0,
        val stabilityRepairs: Int = 0,
        val stabilityNudges: Int = 0,
        val stabilityKicks: Int = 0,
        val stabilityRestarts: Int = 0,
        val stabilityLastReason: String = "Unavailable",
        val audioFocus: String = "Unavailable",
    )

    /** Compose suspension seam: decorative animations may observe this and
        stand down while playback owns the screen (set by PlayerScreen). */
    @get:JvmStatic
    val active: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** True while the device is under playback pressure (tier C/D):
        decorative animation should be suspended. */
    @get:JvmStatic
    val suspendDecorations: MutableStateFlow<Boolean> = MutableStateFlow(false)

    // ---- persisted mode -------------------------------------------------
    @Volatile
    var mode: Profile = Profile.AUTO
        private set

    @Volatile
    var tier: Tier = Tier.B
        private set

    /** Priority override (0.120): StabilityGuard owns the ladder and this
        engine yields BEFORE audio/subtitles ever suffer. While set, the
        engine behaves at tier-D policy (decorations suspended, the
        interpolation/quality work stays off) and never upgrades. Cleared
        only after sustained recovery; the 120s-per-step hysteresis then
        climbs back gradually. */
    @Volatile
    private var stabilityYield = false

    /** Last real reason a demotion fired (diagnostics, never invented). */
    @Volatile
    var lastDemoteReason: String = ""
        private set

    @Volatile
    var stats: FluidStats = FluidStats(
        droppedFrames = 0L,
        presentedApprox = 0L,
        contentFps = 0f,
        displayRefreshRate = 0f,
        decoderName = "",
        tier = Tier.B,
        mode = Profile.AUTO,
        interpolationAvailable = false,
        interpolationReason = "provider embed (frames not accessible)",
    )
        private set

    /** Which pipeline is on screen - decides the honest reason line. */
    private enum class Kind { PROVIDER_EMBED, NATIVE_MEDIA3 }

    @Volatile
    private var kind: Kind = Kind.PROVIDER_EMBED

    // ---- device probe (capability profile A/B/C/D) ----------------------
    @Volatile private var appCtx: Context? = null
    @Volatile private var probed = false
    @Volatile private var memoryClass = 0
    @Volatile private var apiLevel = Build.VERSION.SDK_INT
    @Volatile private var socModel = ""
    @Volatile private var hardwareDecoder = false
    @Volatile private var displayRefresh = 0f
    @Volatile private var decoderName = ""
    @Volatile private var capabilityTier = Tier.B

    // ---- Media3 wiring --------------------------------------------------
    @Volatile private var player: ExoPlayer? = null
    @Volatile private var playerView: PlayerView? = null
    @Volatile private var surfaceView: SurfaceView? = null
    private var surfaceCb: SurfaceHolder.Callback? = null

    // ---- watchdog window ------------------------------------------------
    @Volatile private var contentFps = 0f
    @Volatile private var droppedTotal = 0L
    @Volatile private var presentedTotal = 0L
    @Volatile private var adaptiveQuality = false
    private var winStartMs = 0L
    private var winDropped = 0L
    private var winPresented = 0f
    private var lastDropMs = 0L
    private var lastTierChangeMs = 0L
    private var cleanPlayMs = 0L
    private var lastTickMs = 0L

    private const val WINDOW_MS = 10_000L
    private const val DROP_RATE_LIMIT = 0.05f // >5% dropped of presented
    private const val DOWNGRADE_COOLDOWN_MS = 30_000L
    private const val CLEAN_UPGRADE_MS = 120_000L
    private const val TICK_MS = 2_000L

    /** Standard broadcast/cinema rates - a raw measured rate snaps to the
        nearest real one (23.9 -> 23.976) so the display gets a rate it can
        actually hold. */
    private val STD_RATES = floatArrayOf(
        23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f,
    )

    fun standardRate(fps: Float): Float =
        if (fps <= 0f) 0f else STD_RATES.minByOrNull { kotlin.math.abs(it - fps) } ?: fps

    /** Idempotent device probe - safe to call from every entry point. */
    @JvmStatic
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext ?: ctx
        if (probed) return
        probed = true
        val c = appCtx ?: return
        try {
            val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            memoryClass = am?.memoryClass ?: 0
        } catch (e: Exception) {
        }
        apiLevel = Build.VERSION.SDK_INT
        try {
            // Build.SOC_MODEL is API 31+; older runtimes keep the empty probe.
            if (Build.VERSION.SDK_INT >= 31) socModel = Build.SOC_MODEL ?: ""
        } catch (e: Exception) {
        }
        displayRefresh = readRefresh(c)
        decoderName = findDecoderName()
        hardwareDecoder = isHardwareDecoder(decoderName)
        capabilityTier = deriveTier()
        tier = capabilityTier
        rebuildStats()
    }

    private fun readRefresh(c: Context): Float {
        return try {
            val dm = c.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
            dm?.displays?.firstOrNull()?.refreshRate ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    /** Real MediaCodecList query for the video MIME - the platform's own
        answer for "what would decode this", never thrown, never faked. */
    private fun findDecoderName(): String {
        return try {
            val fmt = MediaFormat.createVideoFormat("video/avc", 1280, 720)
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(fmt) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun isHardwareDecoder(name: String): Boolean {
        if (name.isEmpty()) return false
        return try {
            val info = MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos.firstOrNull { it.name == name }
            when {
                info == null -> name.startsWith("OMX.qcom.") || name.startsWith("OMX.MTK.") ||
                    name.startsWith("c2.qti.") || name.startsWith("c2.mtk.") ||
                    name.startsWith("OMX.Nvidia.") || name.startsWith("OMX.Exynos.")
                // isHardwareAccelerated is API 29+; below it the platform
                // software decoders are the known prefixes.
                Build.VERSION.SDK_INT >= 29 -> info.isHardwareAccelerated
                else -> !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Capability profile from real signals only: memory class, hardware
        decoder presence, display refresh, SoC/API level. */
    private fun deriveTier(): Tier {
        var score = 0
        if (memoryClass >= 512) score += 2 else if (memoryClass >= 256) score += 1
        if (hardwareDecoder) score += 1
        if (displayRefresh >= 100f) score += 2 else if (displayRefresh >= 55f) score += 1
        if (socModel.isNotEmpty() || apiLevel >= 31) score += 1
        return when {
            score >= 5 -> Tier.A
            score >= 3 -> Tier.B
            score >= 1 -> Tier.C
            else -> Tier.D
        }
    }

    // ---- mode handling --------------------------------------------------

    /** Persists and applies a mode. OFF stops every frame-rate call. */
    @JvmStatic
    fun setMode(ctx: Context, profile: Profile) {
        mode = profile
        try {
            Store.setFluidMode(ctx, profile.key)
        } catch (e: Exception) {
        }
        val p = player
        if (p != null) {
            applyAdaptiveQuality()
            applyFrameRate(currentSurface(), contentFps)
        }
        rebuildStats()
    }

    /** The settings row: AUTO -> SMOOTH -> BALANCED -> BOOST -> CINEMATIC ->
        OFF -> AUTO. Returns the new mode. */
    @JvmStatic
    fun cycleMode(ctx: Context): Profile {
        val next = Profile.entries[(mode.ordinal + 1) % Profile.entries.size]
        setMode(ctx, next)
        return next
    }

    /** Opt-in knob (default OFF): when true, the watchdog may cap the
        requested max video size - but ONLY in BOOST, or AUTO on a decoder-
        limited device. A user's manual quality pick is never overridden:
        this stays false until something explicitly enables it. */
    @JvmStatic
    fun setAdaptiveQuality(enabled: Boolean) {
        adaptiveQuality = enabled
        try {
            appCtx?.let { Store.setFluidAdaptiveQuality(it, enabled) }
        } catch (e: Exception) {
        }
        applyAdaptiveQuality()
    }

    /** StabilityGuard L0 (0.120): demote ONE tier immediately - optional
        work is the FIRST sacrifice when the box is choking (dropped-frame
        bursts, JS frame gaps, event-loop lag). Bypasses the 30s watchdog
        cooldown on purpose: prevention runs before anything essential
        suffers. Real reason string, kept for diagnostics. */
    @JvmStatic
    fun demote(reason: String) {
        lastDemoteReason = reason
        val next = Tier.entries[(tier.ordinal + 1).coerceAtMost(Tier.entries.size - 1)]
        if (next != tier) {
            tier = next
            lastTierChangeMs = System.currentTimeMillis()
            cleanPlayMs = 0
        }
        applyAdaptiveQuality()
        rebuildStats()
    }

    /** Priority override (0.120): essential playback outranks motion work.
        While yielded the engine runs tier-D policy (decorations suspended,
        interpolation/quality work off) regardless of the live tier. */
    @JvmStatic
    fun yieldToStability(yield: Boolean) {
        if (stabilityYield == yield) return
        stabilityYield = yield
        applyAdaptiveQuality()
        rebuildStats()
    }

    // ---- pipeline binding ----------------------------------------------

    /** Attach to the owned Media3 player (direct HLS slot). Adds the
        analytics listener (content fps + dropped-frame watchdog) and starts
        the rolling-window ticker. */
    @JvmStatic
    fun start(p: ExoPlayer) {
        stop(p)
        player = p
        kind = Kind.NATIVE_MEDIA3
        try {
            adaptiveQuality = appCtx?.let { Store.fluidAdaptiveQuality(it) } ?: false
        } catch (e: Exception) {
        }
        winStartMs = System.currentTimeMillis()
        lastTickMs = winStartMs
        winDropped = 0
        winPresented = 0f
        cleanPlayMs = 0
        p.addAnalyticsListener(listener)
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, TICK_MS)
        applyAdaptiveQuality()
        rebuildStats()
    }

    /** Detach and stop the watchdog. Safe to call unattached. */
    @JvmStatic
    fun stop(p: ExoPlayer?) {
        try {
            p?.removeAnalyticsListener(listener)
        } catch (e: Exception) {
        }
        handler.removeCallbacks(ticker)
        if (player === p) player = null
    }

    /** The PlayerView owns the real playback surface - wire its SurfaceView
        (SurfaceHolder.Callback fires after creation, so the frame-rate call
        lands on a live surface). */
    @JvmStatic
    fun bindPlayerView(view: PlayerView) {
        playerView = view
        try {
            val sv = view.videoSurfaceView as? SurfaceView ?: return
            surfaceView = sv
            val cb = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    applyFrameRate(holder.surface, contentFps)
                }

                override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                }
            }
            surfaceCb = cb
            sv.holder.addCallback(cb)
            val existing = sv.holder.surface
            if (existing != null && existing.isValid) applyFrameRate(existing, contentFps)
        } catch (e: Exception) {
        }
    }

    @JvmStatic
    fun unbindPlayerView() {
        try {
            surfaceCb?.let { cb -> surfaceView?.holder?.removeCallback(cb) }
        } catch (e: Exception) {
        }
        surfaceCb = null
        surfaceView = null
        playerView = null
    }

    private fun currentSurface(): Surface? {
        return try {
            val direct = surfaceView?.holder?.surface
            if (direct != null && direct.isValid) return direct
            val sv = playerView?.videoSurfaceView as? SurfaceView
            val viaView = sv?.holder?.surface
            if (viaView != null && viaView.isValid) viaView else null
        } catch (e: Exception) {
            null
        }
    }

    // ---- honesty notes --------------------------------------------------

    /** WebView playback: frames live inside opaque cross-origin iframes and
        are NOT accessible - interpolation can never exist here. */
    @JvmStatic
    fun noteProviderEmbed() {
        kind = Kind.PROVIDER_EMBED
        rebuildStats()
    }

    /** Owned Media3 playback: frame-rate matching is real, interpolation
        still does not exist (v1 truth). */
    @JvmStatic
    fun noteNativeMedia3() {
        kind = Kind.NATIVE_MEDIA3
        rebuildStats()
    }

    /** The WebView fps probe (rVFC, PlayerScreen's SB_HOOK) reports the
        REAL measured content rate - recorded for stats, never invented. */
    @JvmStatic
    fun noteContentFps(fps: Float) {
        if (fps in 15f..121f) {
            contentFps = standardRate(fps)
            rebuildStats()
        }
    }

    // ---- frame-rate matching -------------------------------------------

    /** Real judder elimination on API 30+: hand the display the content's
        own rate. FIXED_SOURCE = the display switches to the source rate
        (an even cadence, no 3:2 pulldown); ONLY_IF_SEAMLESS = a display
        that would have to visibly transition keeps its current rate (the
        0.78 freeze lesson - never force a wedge-prone mode switch).
        CINEMATIC pins the source rate; SMOOTH prefers 60 where the display
        supports it; OFF never calls at all. */
    @JvmStatic
    fun applyFrameRate(surface: Surface?, fps: Float) {
        if (mode == Profile.OFF) return
        if (Build.VERSION.SDK_INT < 30) return
        val s = surface ?: return
        try {
            if (!s.isValid) return
            val rate = targetRate(fps) ?: return
            if (rate <= 0f) return
            s.setFrameRate(
                rate,
                Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
            )
        } catch (e: Exception) {
        }
    }

    private fun targetRate(fps: Float): Float? {
        if (mode == Profile.OFF) return null
        if (fps <= 0f) return null
        val std = standardRate(fps)
        return when (mode) {
            // 60 where the display can hold it; otherwise the source rate.
            Profile.SMOOTH -> if (displayRefresh >= 59.5f) 60f else std
            // The display switches to the SOURCE rate - no judder.
            Profile.CINEMATIC -> std
            Profile.AUTO, Profile.BALANCED, Profile.BOOST -> std
            Profile.OFF -> null
        }
    }

    // ---- dropped-frame watchdog ----------------------------------------

    private val listener = object : AnalyticsListener {
        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            try {
                val fr = format.frameRate
                if (fr > 0f) {
                    contentFps = standardRate(fr)
                    applyFrameRate(currentSurface(), contentFps)
                }
                val dn = decoderReuseEvaluation?.decoderName
                if (!dn.isNullOrEmpty()) {
                    decoderName = dn
                    hardwareDecoder = isHardwareDecoder(dn)
                }
                rebuildStats()
            } catch (e: Exception) {
            }
        }

        override fun onDroppedVideoFrames(
            eventTime: AnalyticsListener.EventTime,
            droppedFrames: Int,
            elapsedMs: Long,
        ) {
            try {
                val now = System.currentTimeMillis()
                droppedTotal += droppedFrames
                winDropped += droppedFrames
                lastDropMs = now
                cleanPlayMs = 0
                // A real drop BURST is an L0 pressure signal (0.120):
                // StabilityGuard demotes optional work before anything
                // essential suffers - never the other way around.
                if (droppedFrames >= 8) {
                    com.streambox.nativeapp.playback.StabilityGuard.notePressure("dropped frames")
                }
                rebuildStats()
            } catch (e: Exception) {
            }
        }

        override fun onRenderedFirstFrame(
            eventTime: AnalyticsListener.EventTime,
            output: Any,
            renderTimeMs: Long,
        ) {
            try {
                // A fresh picture restarts the window - the drop budget
                // belongs to this stretch of playback, not the last seek.
                val now = System.currentTimeMillis()
                winStartMs = now
                winDropped = 0
                winPresented = 0f
                (output as? Surface)?.let { applyFrameRate(it, contentFps) }
            } catch (e: Exception) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val dt = now - lastTickMs
        lastTickMs = now
        if (dt <= 0) return
        if (active.value) {
            if (contentFps > 0f) {
                val shown = dt * contentFps / 1000f
                winPresented += shown
                presentedTotal += shown.toLong()
            }
            cleanPlayMs += dt
        }
        // Sustained drops over the window -> one tier down (30s cooldown).
        if (now - winStartMs >= WINDOW_MS) {
            val rate = if (winPresented >= 100f) winDropped / winPresented else 0f
            if (winDropped > 0 && rate > DROP_RATE_LIMIT &&
                now - lastTierChangeMs >= DOWNGRADE_COOLDOWN_MS &&
                mode != Profile.OFF
            ) {
                downgrade()
            }
            winStartMs = now
            winDropped = 0
            winPresented = 0f
        }
        // Hysteresis: one tier back up only after 120s of clean playback,
        // never above the device's probed capability tier, and NEVER while
        // StabilityGuard has the engine yielded (0.120 priority override -
        // recovery of essentials comes first, motion work climbs back one
        // step at a time only after sustained recovery).
        if (cleanPlayMs >= CLEAN_UPGRADE_MS && tier != capabilityTier && mode != Profile.OFF &&
            !stabilityYield
        ) {
            upgrade()
        }
    }

    private fun downgrade() {
        val next = Tier.entries[(tier.ordinal + 1).coerceAtMost(Tier.entries.size - 1)]
        if (next != tier) {
            tier = next
            lastTierChangeMs = System.currentTimeMillis()
            cleanPlayMs = 0
            applyAdaptiveQuality()
            rebuildStats()
        }
    }

    private fun upgrade() {
        val floor = capabilityTier.ordinal
        val next = Tier.entries[(tier.ordinal - 1).coerceAtLeast(floor)]
        if (next != tier) {
            tier = next
            lastTierChangeMs = System.currentTimeMillis()
            cleanPlayMs = 0
            applyAdaptiveQuality()
            rebuildStats()
        }
    }

    /** Tier policy:
        (a) interpolation-quality work is attempted only from tier A - and
            v1 honestly says it does not exist (see describe());
        (b) the max-video-size request is only ever made in BOOST or AUTO on
            a decoder-limited device, and only with adaptive quality ON;
        (c) decorative animations suspend while the tier is C/D - or while
            StabilityGuard has the engine yielded (0.120: tier-D behavior,
            essentials outrank every motion concern);
        (d) while yielded the cap policy runs at tier D regardless of the
            live tier (and only bites at all with adaptive quality ON). */
    private fun applyAdaptiveQuality() {
        suspendDecorations.value = stabilityYield || tier == Tier.C || tier == Tier.D
        val p = player ?: return
        try {
            val decoderLimited = !hardwareDecoder || capabilityTier >= Tier.C
            val permitted = adaptiveQuality && mode != Profile.OFF &&
                (mode == Profile.BOOST || (mode == Profile.AUTO && decoderLimited))
            val eff = if (stabilityYield) Tier.D else tier
            val cap = when {
                !permitted -> null
                eff == Tier.D -> intArrayOf(960, 540)
                eff == Tier.C -> intArrayOf(1280, 720)
                eff == Tier.B -> intArrayOf(1920, 1080)
                else -> null // tier A keeps the full ladder
            }
            val b = p.trackSelectionParameters.buildUpon()
            if (cap != null) b.setMaxVideoSize(cap[0], cap[1])
            else b.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            p.trackSelectionParameters = b.build()
        } catch (e: Exception) {
        }
    }

    private fun rebuildStats() {
        val g = try {
            StabilityGuard.state.value
        } catch (e: Exception) {
            null
        }
        stats = FluidStats(
            droppedFrames = droppedTotal,
            presentedApprox = presentedTotal,
            contentFps = contentFps,
            displayRefreshRate = displayRefresh,
            decoderName = decoderName,
            tier = tier,
            mode = mode,
            interpolationAvailable = false,
            interpolationReason = when (kind) {
                Kind.PROVIDER_EMBED -> "provider embed (frames not accessible)"
                Kind.NATIVE_MEDIA3 -> "native frame-rate matching (no interpolation)"
            },
            stabilityLevel = g?.level ?: 0,
            stabilityRepairs = g?.repairs ?: 0,
            stabilityNudges = g?.nudges ?: 0,
            stabilityKicks = g?.kicks ?: 0,
            stabilityRestarts = g?.restarts ?: 0,
            stabilityLastReason = g?.lastReason ?: "Unavailable",
            audioFocus = g?.audioFocus ?: "Unavailable",
        )
    }

    /** One honest status line for the UI. Never claims interpolation and
        never reports a fps nobody measured. Carries the live StabilityGuard
        fields (0.120): level, repair/nudge/kick/restart counts, lastReason
        and audio focus - real values, "Unavailable" where unknown. */
    @JvmStatic
    fun describe(): String {
        val fps = if (contentFps > 0f)
            String.format(Locale.US, "%.3f", contentFps) + " fps"
        else "fps pending"
        val drops = "drops " + droppedTotal + "/" + presentedTotal
        val guard = "guard L" + stats.stabilityLevel +
            " " + stats.stabilityRepairs + "/" + stats.stabilityNudges + "/" +
            stats.stabilityKicks + "/" + stats.stabilityRestarts +
            " " + stats.stabilityLastReason + " - focus " + stats.audioFocus
        return "Motion: " + mode.label + " - tier " + tier.name + " - " + fps +
            " - " + stats.interpolationReason + " - " + drops + " - " + guard
    }
}
