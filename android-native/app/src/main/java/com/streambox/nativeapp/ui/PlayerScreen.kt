package com.streambox.nativeapp.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Backups
import com.streambox.nativeapp.api.Cue
import com.streambox.nativeapp.api.IntroDb
import com.streambox.nativeapp.api.SubFile
import com.streambox.nativeapp.api.Subs
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.api.Wyz
import com.streambox.nativeapp.api.WyzItem
import com.streambox.nativeapp.data.Progress
import com.streambox.nativeapp.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Builds the provider embed URL. Contracts (verified against the live
    players): CineSrc /embed/movie/:id and /embed/tv/:id?s=&e= with
    &controls=false hiding ITS bar (ours is the player, house rule); AniPM
    /embed/mal/:mal/:ep/dub|sub (valid routes; /en is a 404 and is never
    generated here). */
fun playerUrl(title: Title, season: Int, episode: Int, mal: Int?, ani: Int?): String {
    return when (title.type) {
        "movie" -> "https://cinesrc.st/embed/movie/" + title.id + "?controls=false"
        "anime" -> {
            // House rule pinned forever: Dragon Ball = English dub with the
            // Bruce Faulconer score; everything else starts on sub.
            val lang = if (Regex("dragon\\s*ball", RegexOption.IGNORE_CASE).containsMatchIn(title.name)) {
                "dub"
            } else {
                "sub"
            }
            val ep = episode.coerceAtLeast(1)
            if (mal != null) "https://ani.pm/embed/mal/" + mal + "/" + ep + "/" + lang
            else "https://ani.pm/embed/ani/" + (ani ?: title.id) + "/" + ep + "/" + lang
        }
        else -> "https://cinesrc.st/embed/tv/" + title.id + "?s=" + season + "&e=" + episode + "&controls=false"
    }
}

private fun fmt(s: Float): String {
    val total = s.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Skip hit (TV + anime only, never movies). Priority: recap -> intro ->
    credits, same as the website. The pill exists ONLY inside the range. */
private data class SkipHit(val kind: String, val end: Float?, val label: String)

private fun hitAt(cur: Float, skips: IntroDb.Skips): SkipHit? {
    fun inRange(r: IntroDb.TRange): Boolean =
        cur >= r.start && (r.end == null || cur < r.end!! - 1f)
    skips.recap.firstOrNull(::inRange)?.let {
        return SkipHit("recap", it.end, "Skip Recap")
    }
    skips.intro.firstOrNull(::inRange)?.let {
        return SkipHit("intro", it.end, "Skip Intro")
    }
    skips.credits.firstOrNull(::inRange)?.let {
        return SkipHit("credits", it.end, "Next Ep")
    }
    return null
}

/** JS shim: hooks the embed's <video>, forwards its events to Kotlin, and
    exposes window.__sb commands. Direct video driving PLUS the provider's
    own postMessage contract - belt and braces, so our bar always answers. */
private val SB_HOOK = """
(function(){
  if (window.__sbHooked) return; window.__sbHooked = true;
  function send(t, o){ try { Android.event(t, JSON.stringify(o||{})); } catch(e){} }
  function hook(){
    var v = document.querySelector('video');
    if (!v || v.__sb) return;
    v.__sb = true;
    v.addEventListener('play', function(){ send('play', {}); });
    v.addEventListener('pause', function(){ send('pause', {}); });
    v.addEventListener('ended', function(){ send('ended', {}); });
    v.addEventListener('error', function(){ send('error', {}); });
    v.addEventListener('loadedmetadata', function(){ send('loadedmetadata', {duration: v.duration||0}); });
    v.addEventListener('timeupdate', function(){ send('timeupdate', {currentTime: v.currentTime||0, duration: v.duration||0}); });
    send('ready', {});
  }
  hook();
  setInterval(hook, 1000);
  window.__sb = {
    play: function(){ var v=document.querySelector('video'); if(v) v.play(); try{window.postMessage({type:'cinesrc:command',command:'play',args:[]},'*');}catch(e){} },
    pause: function(){ var v=document.querySelector('video'); if(v) v.pause(); try{window.postMessage({type:'cinesrc:command',command:'pause',args:[]},'*');}catch(e){} },
    seek: function(t){ var v=document.querySelector('video'); if(v) v.currentTime=t; try{window.postMessage({type:'cinesrc:command',command:'seek',args:[t]},'*');}catch(e){} },
    mute: function(m){ var v=document.querySelector('video'); if(v) v.muted=!!m; try{window.postMessage({type:'cinesrc:command',command:'setMuted',args:[!!m]},'*');}catch(e){} },
    rate: function(r){ var v=document.querySelector('video'); if(v) v.playbackRate=r; try{window.postMessage({type:'cinesrc:command',command:'setPlaybackRate',args:[r]},'*');}catch(e){} }
  };
})();
"""

/** Receives player events forwarded by the injected JS hook. */
class SBBridge(private val on: (String, String) -> Unit) {
    @JavascriptInterface
    fun event(type: String, json: String) {
        try {
            on(type, json)
        } catch (e: Exception) {
        }
    }
}

private val RATES = listOf(1f, 1.25f, 1.5f, 2f, 0.5f)

private val SUBLANGS = listOf(
    "en" to "EN", "es" to "ES", "fr" to "FR", "de" to "DE", "ar" to "AR",
    "hi" to "HI", "tl" to "TL", "pt" to "PT", "it" to "IT", "nl" to "NL",
    "ru" to "RU", "tr" to "TR", "id" to "ID",
)

/* Ad Shield (same battle-tested list as the Android wrapper): ad-network
   hosts die in shouldInterceptRequest on EVERY frame - ad scripts and
   popunders inside the embeds never load. Video/source hosts untouched. */
private val AD_HOSTS = setOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
    "googletagmanager.com", "adservice.google.com", "adnxs.com", "adsrvr.org", "adroll.com",
    "popads.net", "popcash.net", "propellerads.com", "propellerads.net", "popundertotal.com",
    "tapadu.com", "exoclick.com", "juicyads.com", "hilltopads.com", "highcpm.com",
    "adsterra.com", "adsterra.net", "histats.com", "hotjar.com", "facebook.net",
    "amazon-adsystem.com", "scorecardresearch.com", "outbrain.com", "taboola.com",
    "trafficjunky.net", "trafficjunky.com", "a-ads.com", "ads-mrg.com", "onclickmax.com",
    "adblokkster.com", "bc.vc", "bc-ads.com", "tsyndicate.com", "syndicat-france.com",
)

private fun isAdHost(host: String): Boolean {
    if (host.isEmpty()) return false
    for (ad in AD_HOSTS) {
        if (host == ad || host.endsWith(".$ad")) return true
    }
    return false
}

private fun isProviderHost(host: String): Boolean {
    val h = host.lowercase(java.util.Locale.US)
    return h == "cinesrc.st" || h.endsWith(".cinesrc.st") ||
        h == "ani.pm" || h.endsWith(".ani.pm")
}

/* TV navigation layers, Netflix-style: at VIDEO the L/R keys seek and OK
   toggles play; Up/Down walk skip pill -> slider -> buttons where L/R
   navigates and OK clicks. Back walks layers first, then home. */
private const val LAYER_VIDEO = "video"
private const val LAYER_SKIP = "skip"
private const val LAYER_SLIDER = "slider"
private const val LAYER_BUTTONS = "buttons"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PlayerScreen(
    title: Title,
    season: Int,
    episode: Int,
    mal: Int?,
    ani: Int?,
    onHome: () -> Unit,
) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val big = AppEnv.isTv

    var playing by remember { mutableStateOf(true) }
    var cur by remember { mutableFloatStateOf(0f) }
    var dur by remember { mutableFloatStateOf(0f) }
    var muted by remember { mutableStateOf(false) }
    var rateIdx by remember { mutableIntStateOf(0) }
    var scrub by remember { mutableFloatStateOf(-1f) }
    var showEps by remember { mutableStateOf(false) }
    var epSeason by remember { mutableIntStateOf(season) }
    var epNumber by remember { mutableIntStateOf(episode) }
    var skips by remember { mutableStateOf(IntroDb.Skips(listOf(), listOf(), listOf())) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var layer by remember { mutableStateOf(LAYER_VIDEO) }
    var lastSave by remember { mutableStateOf(0L) }
    // Netflix behavior: chrome fades while playing after idle; any touch/key
    // wakes it; it STAYS up while paused or while you are using it.
    var chromeVisible by remember { mutableStateOf(true) }
    var pokeTick by remember { mutableIntStateOf(0) }
    val main = remember { Handler(Looper.getMainLooper()) }

    // Subtitles (website Subs panel): file + Wyzie search, position + sync.
    var showSubs by remember { mutableStateOf(false) }
    var subs by remember { mutableStateOf<SubFile?>(null) }
    var wyzLang by remember { mutableStateOf("en") }
    var wyzItems by remember { mutableStateOf(listOf<WyzItem>()) }
    var subMsg by remember { mutableStateOf("") }
    var wyzBusy by remember { mutableStateOf(false) }

    fun setSub(name: String, lang: String, cues: List<Cue>) {
        if (cues.isEmpty()) {
            subMsg = "Couldn't read that file - is it .srt or .vtt?"
            return
        }
        subs = SubFile(name, lang, cues, subs?.pos ?: "bottom", subs?.offset ?: 0f)
        subMsg = "Loaded " + cues.size + " lines."
    }

    val pickSub = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val text = ctx.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                val name = uri.lastPathSegment ?: "picked.srt"
                setSub(name, Subs.guessLang(name), Subs.parse(text))
            }.onFailure {
                subMsg = "Couldn't open that file."
            }
        }
    }

    val scope = rememberCoroutineScope()
    fun loadFrom(item: WyzItem) {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { Wyz.fetchFile(item.url) }.getOrNull()
            }
            if (text == null) {
                subMsg = "Download failed - try another release."
            } else {
                setSub(item.release, item.display.ifEmpty { wyzLang.uppercase() }, Subs.parse(text))
            }
        }
    }

    val sliderFR = remember { FocusRequester() }
    val playFR = remember { FocusRequester() }
    val skipFR = remember { FocusRequester() }

    fun save(c: Float, d: Float) {
        try {
            Store.saveProgress(
                ctx,
                Progress(title.type, title.id, title.name, title.poster, epSeason, epNumber, c, d, System.currentTimeMillis(), mal, ani),
            )
        } catch (e: Exception) {
        }
    }

    fun load() {
        chromeVisible = true
        main.post {
            try {
                web?.loadUrl(playerUrl(title, epSeason, epNumber, mal, ani))
            } catch (e: Exception) {
            }
        }
    }

    fun cmd(js: String) {
        main.post {
            try {
                web?.evaluateJavascript(js, null)
            } catch (e: Exception) {
            }
        }
    }

    fun seekTo(t: Float) {
        val target = t.coerceAtLeast(0f)
        cmd("window.__sb && window.__sb.seek(" + target.toInt() + ")")
        cur = target
    }

    fun pokeChrome() {
        chromeVisible = true
        pokeTick += 1
    }

    // Idle fade: only while actually playing with no panel open. On fade the
    // TV focus resets to the video layer so keys keep working blind.
    LaunchedEffect(chromeVisible, playing, showEps, showSubs, pokeTick) {
        if (chromeVisible && playing && !showEps && !showSubs) {
            delay(4500)
            chromeVisible = false
            layer = LAYER_VIDEO
            runCatching { focus.clearFocus() }
        }
    }

    fun seekRel(d: Float) {
        seekTo((if (scrub >= 0) scrub else cur) + d)
    }

    fun togglePlay() {
        pokeChrome()
        playing = !playing
        cmd(if (playing) "window.__sb && window.__sb.play()" else "window.__sb && window.__sb.pause()")
    }

    fun skipNow(h: SkipHit) {
        when (h.kind) {
            "credits" -> {
                epNumber += 1
                save(0f, dur)
                cur = 0f
                playing = true
                load()
            }
            else -> h.end?.let { seekTo(it) }
        }
    }

    // Skip timings (TV + anime only - movies never skip).
    LaunchedEffect(title.id, epSeason, epNumber, title.type) {
        skips = if (title.type == "movie") {
            IntroDb.Skips(listOf(), listOf(), listOf())
        } else {
            withContext(Dispatchers.IO) { IntroDb.fetch(title.id, epSeason, epNumber) }
        }
    }

    // Subtitle search chain (website rules): Wyzie first (1,000/day shared
    // key), straight to backups when it is empty/capped/missing - TSDB
    // (keyless) then BetaSeries (TV only). Manual re-run pill bumps bsTick.
    var bsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(showSubs, wyzLang, title.id, epSeason, epNumber, bsTick) {
        if (showSubs) {
            wyzBusy = true
            subMsg = ""
            val isTv = title.type != "movie"
            var note = ""
            var items = try {
                withContext(Dispatchers.IO) {
                    Wyz.search(
                        title.id,
                        wyzLang,
                        if (isTv) epSeason else null,
                        if (isTv) epNumber else null,
                    )
                }
            } catch (e: Exception) {
                note = when {
                    (e.message ?: "").contains("429") -> "Wyzie is capped (1,000/day) - showing backups."
                    (e.message ?: "").contains("503") -> "Wyzie key not set - showing backups."
                    else -> "Wyzie failed - showing backups."
                }
                listOf()
            }
            if (items.isEmpty()) {
                items = withContext(Dispatchers.IO) {
                    val tsdb = runCatching {
                        Backups.tsdbFind(
                            title.id, isTv, wyzLang,
                            if (isTv) epSeason else null,
                            if (isTv) epNumber else null,
                        )
                    }.getOrDefault(listOf())
                    if (tsdb.isNotEmpty()) tsdb
                    else if (isTv) runCatching {
                        Backups.bsFind(title.id, wyzLang, epSeason, epNumber)
                    }.getOrDefault(listOf())
                    else listOf()
                }
                if (items.isNotEmpty() && note.isEmpty()) note = "No Wyzie results - showing backups."
            }
            wyzItems = items
            subMsg = when {
                items.isEmpty() && note.isEmpty() -> "No subtitles found - load a .srt/.vtt file below."
                items.isEmpty() -> note + " Nothing found - load a .srt/.vtt file below."
                else -> note
            }
            wyzBusy = false
        }
    }

    val capCue = remember(cur, subs) {
        val f = subs ?: return@remember null
        val t = cur + f.offset
        f.cues.firstOrNull { t >= it.s && t <= it.e }
    }

    val hit = remember(cur, skips, title.type) {
        if (title.type == "movie") null else hitAt(cur, skips)
    }
    // pill vanished under us (range ended): fall back to the video layer
    LaunchedEffect(hit == null) {
        if (hit == null && layer == LAYER_SKIP) {
            layer = LAYER_VIDEO
            runCatching { focus.clearFocus() }
        }
    }

    // House rule: Back from the player goes home (Netflix-style), but first
    // unwind the UI layers like the TV apps do.
    BackHandler {
        when {
            showSubs -> showSubs = false
            showEps -> showEps = false
            big && layer != LAYER_VIDEO -> {
                layer = LAYER_VIDEO
                runCatching { focus.clearFocus() }
            }
            else -> {
                save(if (scrub >= 0) scrub else cur, dur)
                onHome()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { save(if (scrub >= 0) scrub else cur, dur) }
    }

    fun layerList(): List<String> = buildList {
        add(LAYER_VIDEO)
        if (hit != null) add(LAYER_SKIP)
        add(LAYER_SLIDER)
        add(LAYER_BUTTONS)
    }

    fun goLayer(target: String) {
        layer = target
        runCatching {
            when (target) {
                LAYER_VIDEO -> focus.clearFocus()
                LAYER_SKIP -> skipFR.requestFocus()
                LAYER_SLIDER -> sliderFR.requestFocus()
                LAYER_BUTTONS -> playFR.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (!big || showEps || showSubs || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                pokeChrome()
                val layers = layerList()
                val i = layers.indexOf(layer).coerceAtLeast(0)
                when (e.key) {
                    Key.DirectionDown -> {
                        if (i < layers.size - 1) goLayer(layers[i + 1])
                        true
                    }
                    Key.DirectionUp -> {
                        if (i > 0) goLayer(layers[i - 1])
                        true
                    }
                    Key.DirectionLeft -> {
                        if (layers[i] == LAYER_VIDEO) {
                            seekRel(-10f); true
                        } else false
                    }
                    Key.DirectionRight -> {
                        if (layers[i] == LAYER_VIDEO) {
                            seekRel(30f); true
                        } else false
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (layers[i] == LAYER_VIDEO) {
                            togglePlay(); true
                        } else false
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctxWeb ->
                WebView(ctxWeb).apply {
                    // House rule: NEVER auto-mute. Volume stays exactly as the
                    // user left it; the remote controls volume (TV keys).
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.setMediaPlaybackRequiresUserGesture(false)
                    settings.setSupportMultipleWindows(false)
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = false
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    // Never intercept subframe navigation: players navigate
                    // their own inner frames to video hosts (wrapper lesson).
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, u: String?) {
                            try {
                                view?.evaluateJavascript(SB_HOOK, null)
                            } catch (e: Exception) {
                            }
                        }

                        // Ad Shield: ad/tracker hosts return empty - every
                        // frame, so in-embed popunders die before loading.
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): android.webkit.WebResourceResponse? {
                            try {
                                val host = (request?.url?.host ?: "").lowercase(java.util.Locale.US)
                                if (isAdHost(host)) {
                                    return android.webkit.WebResourceResponse(
                                        "text/plain", "utf-8",
                                        java.io.ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                            } catch (e: Exception) {
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // Tab-hijack guard: main frame stays on the provider
                        // embed; subframes (video hosts) flow untouched.
                        // Exotic schemes are blocked everywhere (wrapper rule).
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): Boolean {
                            try {
                                val r = request ?: return false
                                val scheme = (r.url?.scheme ?: "").lowercase(java.util.Locale.US)
                                if (scheme != "http" && scheme != "https" && scheme.isNotEmpty()) return true
                                if (!r.isForMainFrame) return false
                                val host = r.url?.host ?: ""
                                return !isProviderHost(host)
                            } catch (e: Exception) {
                                return false
                            }
                        }

                        // VidCore-style broken cert chains self-heal in modern
                        // Chromium; strict WebViews need the surgical exception
                        // (chain-trust ONLY on that host - wrapper rule).
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: android.webkit.SslErrorHandler?,
                            error: android.net.http.SslError?,
                        ) {
                            try {
                                var h = ""
                                try {
                                    val u = java.net.URI(error?.url ?: "")
                                    if (u.host != null) h = u.host.lowercase(java.util.Locale.US)
                                } catch (e: Exception) {
                                }
                                if ((h == "vidcore.org" || h.endsWith(".vidcore.org")) &&
                                    error?.primaryError == android.net.http.SslError.SSL_UNTRUSTED
                                ) {
                                    handler?.proceed()
                                    return
                                }
                            } catch (e: Exception) {
                            }
                            handler?.cancel()
                        }
                    }
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(SBBridge({ t, j ->
                        main.post {
                            try {
                                val o = org.json.JSONObject(j)
                                when (t) {
                                    "play" -> playing = true
                                    "pause" -> playing = false
                                    "ended" -> playing = false
                                    "loadedmetadata" -> dur = o.optDouble("duration", 0.0).toFloat()
                                    "timeupdate" -> {
                                        cur = o.optDouble("currentTime", 0.0).toFloat()
                                        val d = o.optDouble("duration", 0.0).toFloat()
                                        if (d > 0) dur = d
                                        val now = System.currentTimeMillis()
                                        if (now - lastSave > 5000) {
                                            lastSave = now
                                            save(cur, dur)
                                        }
                                    }
                                    else -> {}
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }), "Android")
                    // TV: keep D-pad on OUR bar, never trapped in the embed
                    isFocusable = !big
                    isFocusableInTouchMode = !big
                    // Phone: a tap MINIMIZES the chrome and a tap brings it
                    // back (toggle). TV has no touch - any key wakes the
                    // chrome there instead. The tap is never eaten.
                    setOnTouchListener { _, ev ->
                        if (ev.action == android.view.MotionEvent.ACTION_UP) {
                            main.post {
                                if (big) {
                                    pokeChrome()
                                } else {
                                    chromeVisible = !chromeVisible
                                    pokeTick += 1
                                }
                            }
                        }
                        false
                    }
                    loadUrl(playerUrl(title, epSeason, epNumber, mal, ani))
                    web = this
                }
            },
        )

        // Top-left title card (website style) - fades with the chrome.
        if (chromeVisible) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xCC0B1426), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                title.name.uppercase(),
                color = Accent,
                fontWeight = FontWeight.Black,
                fontSize = if (big) 15.sp else 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (title.type == "movie") "MOVIE" else "S" + epSeason + " E" + epNumber,
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = if (big) 18.sp else 15.sp,
            )
        }
        }

        // Caption overlay (website-style): cue window is cur + sync offset.
        // Captions track the chrome on BOTH phone and TV - they ride up
        // while the bar is showing (TV bar is taller, so higher there) and
        // sink toward the bottom when it minimizes (Netflix feel).
        if (capCue != null && !showEps && !showSubs) {
            val bottomPad by animateDpAsState(
                targetValue = if (chromeVisible) (if (big) 156.dp else 112.dp) else (if (big) 38.dp else 30.dp),
                label = "capBottom",
            )
            val capMod = when (subs?.pos ?: "bottom") {
                "top" -> Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
                "center" -> Modifier.align(Alignment.Center)
                else -> Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad)
            }
            Text(
                capCue.text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontSize = if (big) 20.sp else 16.sp,
                modifier = capMod
                    .padding(horizontal = 28.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Center overlay while paused (website style).
        if (!playing && !showEps) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircleBtn("-10s", big) { seekRel(-10f) }
                Box(
                    modifier = Modifier
                        .size(if (big) 92.dp else 76.dp)
                        .focusRing()
                        .background(Accent, CircleShape)
                        .clickable { if (!playing) togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Ink, modifier = Modifier.size(40.dp))
                }
                CircleBtn("+30s", big) { seekRel(30f) }
            }
        }

        // Bottom control bar (website style): skip pill + time row + buttons.
        // Chrome fades when idle; the skip pill stays while in its range.
        if (chromeVisible || hit != null) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x000B1426), Color(0xE60B1426))))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Skip pill - visible ONLY inside intro/recap/credits. TV: its own
            // navigation layer, so the remote reaches it like Netflix's Next
            // Episode pill.
            if (hit != null) {
                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    val label = when {
                        hit.kind == "credits" -> hit.label
                        hit.end != null -> hit.label + " - " + fmt(hit.end)
                        else -> hit.label
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .focusRequester(skipFR)
                            .focusRing()
                            .background(Accent, RoundedCornerShape(50))
                            .clickable { skipNow(hit) }
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            label,
                            color = Ink,
                            fontWeight = FontWeight.Black,
                            fontSize = if (big) 16.sp else 13.sp,
                        )
                    }
                }
            }

            if (chromeVisible) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmt(if (scrub >= 0) scrub else cur) + " / " + fmt(dur),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 15.sp else 12.sp,
                )
                Slider(
                    value = if (scrub >= 0) scrub else cur,
                    onValueChange = { scrub = it },
                    onValueChangeFinished = {
                        if (scrub >= 0) {
                            seekTo(scrub)
                            scrub = -1f
                        }
                    },
                    valueRange = 0f..(if (dur > 0) dur else 1f),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                        .focusRequester(sliderFR),
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Color(0xFF24374F),
                    ),
                )
                Text(
                    "-" + fmt(if (dur > 0) (dur - (if (scrub >= 0) scrub else cur)).coerceAtLeast(0f) else 0f),
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 15.sp else 12.sp,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(if (big) 56.dp else 48.dp)
                        .focusRequester(playFR)
                        .focusRing()
                        .background(Accent, CircleShape)
                        .clickable { togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Ink,
                        modifier = Modifier.size(if (playing) 22.dp else 28.dp),
                    )
                }
                BarPill("-10s", big) { seekRel(-10f) }
                BarPill("+30s", big) { seekRel(30f) }
                Box(
                    modifier = Modifier
                        .size(if (big) 56.dp else 48.dp)
                        .focusRing()
                        .background(Pill, CircleShape)
                        .clickable {
                            muted = !muted
                            cmd("window.__sb && window.__sb.mute(" + muted + ")")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = "Mute",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
                BarPill(
                    RATES[rateIdx].let { if (it == it.toInt().toFloat()) it.toInt().toString() + "x" else it.toString() + "x" },
                    big,
                ) {
                    rateIdx = (rateIdx + 1) % RATES.size
                    cmd("window.__sb && window.__sb.rate(" + RATES[rateIdx] + ")")
                }
                Spacer(modifier = Modifier.weight(1f))
                BarPill("Subs", big) { showSubs = true }
                BarPill("Episodes", big, icon = Icons.Filled.Menu) { showEps = true }
                BarPill("Home", big) {
                    save(if (scrub >= 0) scrub else cur, dur)
                    onHome()
                }
            }
            }
        }
        }

        // Episodes sheet (website-style episode switcher).
        if (showEps) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable { showEps = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(24.dp)
                        .background(Card, RoundedCornerShape(18.dp))
                        .padding(20.dp),
                ) {
                    Text("Go to episode", color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    if (title.type != "movie") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            BarPill("S -", big) { epSeason = (epSeason - 1).coerceAtLeast(1) }
                            Text("Season " + epSeason, color = Color.White, fontWeight = FontWeight.Bold)
                            BarPill("S +", big) { epSeason += 1 }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            BarPill("E -", big) { epNumber = (epNumber - 1).coerceAtLeast(1) }
                            Text("Episode " + epNumber, color = Color.White, fontWeight = FontWeight.Bold)
                            BarPill("E +", big) { epNumber += 1 }
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Load", big, accent = true) {
                            showEps = false
                            save(0f, dur)
                            cur = 0f
                            playing = true
                            load()
                        }
                        PillButton("Close", big) { showEps = false }
                    }
                }
            }
        }

        // Subtitles panel (website Subs tab): language chips + provider chain
        // results + own file + Position/Sync to match the captions.
        if (showSubs) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .clickable { showSubs = false },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .background(Card, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Subtitles",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f),
                        )
                        BarPill("Close", big) { showSubs = false }
                    }
                    Spacer(modifier = Modifier.height(10.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SUBLANGS) { (code, label) ->
                            val on = code == wyzLang
                            Box(
                                modifier = Modifier
                                    .focusRing()
                                    .background(if (on) Accent else Pill, CircleShape)
                                    .clickable { wyzLang = code }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    label,
                                    color = if (on) Ink else Color.White,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (subMsg.isNotEmpty()) {
                        Text(subMsg, color = Color(0xFFF6C177), fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                    } else {
                        Text(
                            "Same title, different releases - pick yours for exact sync.",
                            color = Dim,
                            fontSize = 11.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                        if (wyzBusy) {
                            CircularProgressIndicator(
                                color = Accent,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        } else {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                wyzItems.forEach { item ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .background(Pill, RoundedCornerShape(12.dp))
                                            .padding(10.dp),
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                item.release,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                listOf(
                                                    item.display,
                                                    item.origin,
                                                    if (item.downloads > 0) item.downloads.toString() else "",
                                                    if (item.cc) "CC" else "",
                                                ).filter { it.isNotEmpty() }.joinToString("  -  "),
                                                color = Dim,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        BarPill("Load", big) { loadFrom(item) }
                                    }
                                }
                                if (wyzItems.isEmpty() && !wyzBusy) {
                                    BarPill("Try backups (TSDB + BetaSeries)", big) { bsTick += 1 }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text("MY SUBTITLES (YOUR FILE)", color = Dim, fontWeight = FontWeight.Black, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Pill, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Text(
                            subs?.let {
                                it.name + "  -  " + it.lang + "  -  " + it.cues.size + " lines"
                            } ?: "None loaded",
                            color = if (subs != null) Color.White else Dim,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BarPill("Load .srt / .vtt", big) {
                            runCatching { pickSub.launch(arrayOf("*/*")) }
                        }
                        BarPill("Clear", big) {
                            subs = null
                            subMsg = ""
                        }
                        BarPill("Try backups", big) { bsTick += 1 }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Position", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(74.dp))
                        BarPill("<", big) {
                            // "<" moves captions UP the screen (site rule)
                            val order = listOf("bottom", "center", "top")
                            val i = order.indexOf(subs?.pos ?: "bottom").let { if (it < 0) 0 else it }
                            subs = subs?.copy(pos = order[(i + 1) % order.size])
                        }
                        Text(
                            (subs?.pos ?: "bottom").replaceFirstChar { it.uppercase() },
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        BarPill(">", big) {
                            val order = listOf("bottom", "center", "top")
                            val i = order.indexOf(subs?.pos ?: "bottom").let { if (it < 0) 0 else it }
                            subs = subs?.copy(pos = order[(i + 2) % order.size])
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sync", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(74.dp))
                        BarPill("-", big) {
                            val o = Math.round(((subs?.offset ?: 0f) - 0.5f) * 10) / 10f
                            subs = subs?.copy(offset = o.coerceIn(-30f, 30f))
                        }
                        Text(
                            ((subs?.offset ?: 0f)).let { (if (it >= 0) "+" else "") + String.format("%.1f", it) + "s" },
                            color = Accent,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        BarPill("+", big) {
                            val o = Math.round(((subs?.offset ?: 0f) + 0.5f) * 10) / 10f
                            subs = subs?.copy(offset = o.coerceIn(-30f, 30f))
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    PillButton("Close", big, accent = true) { showSubs = false }
                }
            }
        }
    }
}

@Composable
private fun CircleBtn(label: String, big: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (big) 68.dp else 58.dp)
            .focusRing()
            .background(Color(0x990B1426), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Black, fontSize = if (big) 16.sp else 13.sp)
    }
}

@Composable
private fun BarPill(label: String, big: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(if (big) 52.dp else 44.dp)
            .focusRing()
            .background(Pill, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 16.dp else 13.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (big) 15.sp else 12.sp)
    }
}
