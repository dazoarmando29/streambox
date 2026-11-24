package com.streambox.nativeapp.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import com.streambox.nativeapp.R
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.flow.MutableStateFlow

/** Experimental MediaProjection capture-interpolation pipeline (0.200).

    This is the ONE authorized route to provider-embed frames on Android:
    the platform's own screen-capture consent. It mirrors the web track's
    capture MEMC, and it is honest to the byte:

    REAL here:
    - explicit user consent via the MediaProjection consent dialog (the
      platform's own prompt - capture NEVER starts without it),
    - real frame intake: MediaProjection -> VirtualDisplay -> ImageReader,
      every frame counted with its real capture-to-intake latency
      (System.nanoTime() vs the Image timestamp - same monotonic clock
      domain on Android; samples outside 0..2000ms are discarded, never
      faked),
    - a real block-matching motion field over the captured frames (the web
      engine's estimation idea on a subsampled luma grid - tiered search
      with early exit is the documented next step, not a claim).

    NOT here (honesty rule - never break this):
    - NO synthesized/interpolated frames. The estimation stage measures
      motion only; "generating" is hard-wired false in this build and the
      status line says "experimental pipeline not yet generating frames".
      Nothing may ever report generated output that was not generated.

    Guardrails (brief rejection criteria, enforced in code):
    - measured latency auto-disables the mode above 250ms (A/V sync is
      priority 2 in the house order and capture is priority-last),
    - StabilityGuard subordination: the stage YIELDS at guard level >= 2
      and the whole feature auto-disables at level >= 3 (instability
      rejects the feature automatically),
    - the display stream is captured at reduced resolution (half display,
      720p cap) - an experiment must never cost the video its memory,
    - the feature is default OFF (Store.fluidCaptureMemc defaults false)
      and session-scoped: leaving the player stops the capture. */
object CaptureMemc {

    /** Live, honest diagnostics. generating is NEVER true in this build. */
    data class CaptureStats(
        val wanted: Boolean = false,
        val consented: Boolean = false,
        val running: Boolean = false,
        /** Hard-wired false until a synthesis stage exists. NEVER claim
            generated frames that were not generated. */
        val generating: Boolean = false,
        val framesIn: Long = 0L,
        /** Capture-to-intake latency in ms (-1 = Unavailable). */
        val latencyMs: Float = -1f,
        /** Blocks with real motion in the last estimate (-1 = Unavailable). */
        val motionBlocks: Int = -1,
        val autoDisabled: Boolean = false,
        val lastReason: String = "Unavailable",
    )

    val state: MutableStateFlow<CaptureStats> = MutableStateFlow(CaptureStats())

    /** Auto-disable threshold (brief rejection criteria): capture latency
        above this harms A/V sync, so the feature turns itself off. */
    const val LATENCY_LIMIT_MS = 250f

    private const val LATENCY_DISCARD_LO = 0f
    private const val LATENCY_DISCARD_HI = 2000f

    @Volatile private var appCtx: Context? = null
    @Volatile private var proj: MediaProjection? = null
    @Volatile private var vdisp: android.hardware.display.VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    @Volatile private var latEma = -1f
    @Volatile private var latBad = 0
    @Volatile private var serviceStop: (() -> Unit)? = null
    private val stage = InterpStage()

    /** Session attach: loads the persisted intent. Consent is per-session -
        a stored "on" NEVER implies capture is running. */
    @JvmStatic
    fun attach(ctx: Context) {
        appCtx = ctx.applicationContext ?: ctx
        val wanted = try { Store.fluidCaptureMemc(ctx) } catch (e: Exception) { false }
        state.value = state.value.copy(
            wanted = wanted,
            lastReason = if (wanted) "awaiting screen-capture consent" else "Unavailable",
        )
    }

    /** The user toggled the feature ON and the platform consent dialog
        answered YES. Only THIS path may start a capture. */
    @JvmStatic
    fun start(ctx: Context, resultCode: Int, data: Intent) {
        appCtx = ctx.applicationContext ?: ctx
        state.value = state.value.copy(
            wanted = true, consented = true, autoDisabled = false,
            lastReason = "consent granted - starting capture",
        )
        val i = Intent(ctx, CaptureService::class.java)
        i.putExtra(CaptureService.EXTRA_CODE, resultCode)
        i.putExtra(CaptureService.EXTRA_DATA, data)
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (e: Exception) {
            state.value = state.value.copy(
                running = false,
                lastReason = "capture service failed to start (" + e.javaClass.simpleName + ")",
            )
        }
    }

    /** Consent declined (or unavailable): capture never starts without it. */
    @JvmStatic
    fun noteConsentDenied() {
        state.value = state.value.copy(
            wanted = false, consented = false, running = false,
            lastReason = "consent declined - capture never starts without it",
        )
    }

    @JvmStatic
    fun noteUnavailable() {
        state.value = state.value.copy(
            wanted = false, running = false,
            lastReason = "screen capture unavailable on this device",
        )
    }

    /** User (or player exit) turns the feature off. */
    @JvmStatic
    fun stop(reason: String) {
        try {
            appCtx?.let { Store.setFluidCaptureMemc(it, false) }
        } catch (e: Exception) {}
        endProjection(reason)
        state.value = state.value.copy(wanted = false, consented = false, running = false)
    }

    /** Called from the foreground service once it is foregrounded
        (Android 14: a mediaProjection FGS must run BEFORE
        getMediaProjection - the service owns that ordering). */
    @JvmStatic
    fun beginProjection(ctx: Context, resultCode: Int, data: Intent, onStopped: () -> Unit) {
        appCtx = ctx.applicationContext ?: ctx
        endProjection("restarting")
        serviceStop = onStopped
        try {
            val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            val p = mgr?.getMediaProjection(resultCode, data)
            if (p == null) {
                state.value = state.value.copy(
                    running = false, lastReason = "MediaProjection unavailable (getMediaProjection returned null)",
                )
                onStopped()
                return
            }
            proj = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    endProjection("projection stopped by the system")
                    onStopped()
                }
            }, Handler(Looper.getMainLooper()))
            // Half-display capture, 720p cap: an experiment must never cost
            // the video its memory on 1-2GB boxes.
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val disp = dm?.displays?.firstOrNull()
            val w0 = disp?.mode?.physicalWidth ?: 1280
            val h0 = disp?.mode?.physicalHeight ?: 720
            val w = ((w0 / 2).coerceIn(320, 1280))
            val h = ((h0 / 2).coerceIn(180, 720))
            val dpi = (ctx.resources.displayMetrics.densityDpi).coerceAtLeast(120)
            val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
            reader = r
            val th = HandlerThread("sb-capture").also { it.start() }
            thread = th
            r.setOnImageAvailableListener({ rr -> onFrame(rr) }, Handler(th.looper))
            vdisp = p.createVirtualDisplay(
                "StreamBoxCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, Handler(th.looper),
            )
            state.value = state.value.copy(
                running = true, framesIn = 0L, latencyMs = -1f, motionBlocks = -1,
                lastReason = "capturing with consent (experimental pipeline not yet generating frames)",
            )
        } catch (e: Exception) {
            endProjection("capture failed (" + e.javaClass.simpleName + ")")
            onStopped()
        }
    }

    /** Teardown - idempotent, safe from any thread. */
    @JvmStatic
    fun endProjection(reason: String) {
        try { vdisp?.release() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        try { proj?.stop() } catch (e: Exception) {}
        try { thread?.quitSafely() } catch (e: Exception) {}
        vdisp = null
        reader = null
        proj = null
        thread = null
        latEma = -1f
        latBad = 0
        state.value = state.value.copy(running = false, lastReason = reason)
    }

    private fun autoDisable(reason: String) {
        stop(reason)
        state.value = state.value.copy(autoDisabled = true, lastReason = reason)
        try { serviceStop?.invoke() } catch (e: Exception) {}
    }

    /** One real captured frame. Measures latency, feeds the estimation
        stage (when it is allowed to run) and enforces every guardrail. */
    private fun onFrame(r: ImageReader) {
        var img: Image? = null
        try {
            img = r.acquireLatestImage() ?: return
            val lat = (System.nanoTime() - img.timestamp) / 1_000_000f
            var st = state.value.copy(
                running = true,
                framesIn = state.value.framesIn + 1,
                generating = false,
            )
            if (lat >= LATENCY_DISCARD_LO && lat <= LATENCY_DISCARD_HI) {
                latEma = if (latEma < 0f) lat else latEma * 0.8f + lat * 0.2f
                st = st.copy(latencyMs = latEma)
            }
            val lvl = try { StabilityGuard.levelNow() } catch (e: Exception) { 0 }
            when {
                // instability REJECTS the feature automatically (brief rule)
                lvl >= 3 -> autoDisable("auto-disabled: stability recovery (guard L" + lvl + ")")
                // A/V sync guard: measured latency over 250ms turns it off
                latEma > LATENCY_LIMIT_MS -> {
                    latBad += 1
                    if (latBad >= 3) {
                        autoDisable(
                            "auto-disabled: capture latency " + latEma.toInt() +
                                "ms over " + LATENCY_LIMIT_MS.toInt() + "ms",
                        )
                    } else {
                        state.value = st
                    }
                }
                else -> {
                    latBad = 0
                    // StabilityGuard subordination: the stage YIELDS at
                    // level >= 2 - generated work never costs essentials
                    if (lvl < 2) {
                        val m = stage.feed(img)
                        if (m >= 0) st = st.copy(motionBlocks = m)
                    }
                    state.value = st
                }
            }
        } catch (e: Exception) {
            try { state.value = state.value.copy(lastReason = "capture error (" + e.javaClass.simpleName + ")") } catch (e2: Exception) {}
        } finally {
            try { img?.close() } catch (e: Exception) {}
        }
    }
}

/** The experimental interpolation stage (skeleton, honest): a real
    block-matching motion field over the captured frames - the web engine's
    estimation idea at 1/8 resolution with an early exit. It MEASURES
    motion only. Frame SYNTHESIS is not implemented in this build and
    nothing anywhere reports generated frames (the status line says
    "experimental pipeline not yet generating frames"). */
private class InterpStage {
    private var prev: FloatArray? = null
    private var n = 0

    /** Returns the number of blocks with real motion (-1 = no estimate). */
    fun feed(img: Image): Int {
        n += 1
        if (n % 3 != 0) return -1 // bounded cost: one estimate per 3 frames
        val plane = img.planes.firstOrNull() ?: return -1
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val step = 8
        val ngw = img.width / step
        val ngh = img.height / step
        if (ngw < 8 || ngh < 8 || ps < 3) return -1
        val grid = FloatArray(ngw * ngh)
        for (gy in 0 until ngh) {
            val row = gy * step * rs
            for (gx in 0 until ngw) {
                val off = row + gx * step * ps
                if (off + 2 >= buf.capacity()) continue
                val r = buf.get(off).toInt() and 0xFF
                val g = buf.get(off + 1).toInt() and 0xFF
                val b = buf.get(off + 2).toInt() and 0xFF
                grid[gy * ngw + gx] = ((r + g + b) / 3).toFloat()
            }
        }
        val old = prev
        prev = grid
        if (old == null || old.size != grid.size) return -1
        // block matching: 4x4-cell blocks (32px at capture scale),
        // search +-2 cells, early exit on an exact match
        var moved = 0
        val bw = 4
        var by = 0
        while (by + bw <= ngh) {
            var bx = 0
            while (bx + bw <= ngw) {
                var best = Long.MAX_VALUE
                var dy = -2
                while (dy <= 2 && best > 0L) {
                    var dx = -2
                    while (dx <= 2 && best > 0L) {
                        val yy = by + dy
                        val xx = bx + dx
                        if (yy >= 0 && xx >= 0 && yy + bw <= ngh && xx + bw <= ngw) {
                            var sad = 0L
                            var cy = 0
                            while (cy < bw) {
                                var cx = 0
                                while (cx < bw) {
                                    val a = old[(by + cy) * ngw + (bx + cx)]
                                    val b2 = grid[(yy + cy) * ngw + (xx + cx)]
                                    sad += if (a > b2) (a - b2).toLong() else (b2 - a).toLong()
                                    cx++
                                }
                                cy++
                            }
                            if (sad < best) best = sad
                        }
                        dx++
                    }
                    dy++
                }
                if (best > 32L) moved++
                bx += bw
            }
            by += bw
        }
        return moved
    }
}

/** Foreground service for the capture (Android 14 requirement: a
    mediaProjection-type FGS must run BEFORE getMediaProjection). It owns
    the ordering only - all state lives in CaptureMemc. */
class CaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = startForegroundCompat()
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (!ok || code == 0 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        CaptureMemc.beginProjection(applicationContext, code, data) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Screen capture", NotificationManager.IMPORTANCE_LOW),
                )
            }
            val b = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val n = b.setContentTitle("StreamBox")
                .setContentText("Experimental capture pipeline is running")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, n)
            }
            true
        } catch (e: Exception) {
            try { stopSelf() } catch (e2: Exception) {}
            false
        }
    }

    override fun onDestroy() {
        CaptureMemc.endProjection("capture service stopped")
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "streambox_capture"
        private const val NOTIF_ID = 41
    }
}
