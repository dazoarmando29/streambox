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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Api
import com.streambox.nativeapp.api.Backups
import com.streambox.nativeapp.api.Cue
import com.streambox.nativeapp.api.Ep
import com.streambox.nativeapp.api.IntroDb
import com.streambox.nativeapp.api.SkipTimes
import com.streambox.nativeapp.api.SubFile
import com.streambox.nativeapp.api.Subs
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.api.Wyz
import com.streambox.nativeapp.api.WyzItem
import com.streambox.nativeapp.data.Progress
import com.streambox.nativeapp.data.Store
import com.streambox.nativeapp.data.SubPick
import com.streambox.nativeapp.data.SubStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* Mirror routing (website rules): movies/TV default CineSrc, anime AniPM.
   Korean dramas default VidBolt (0.41 request) - its /tv route carries the
   multi-audio Korean releases and our sheet drives its real audio/subtitle
   tracks. ANIME_BAD_CATALOG keeps Doraemon away from VidBolt (it serves
   FMA:B for the right id). VidRift is TMDB-direct (works for anime when the
   AniPM mapping is missing). VidZen has no anime routes. Mid-session
   switches never change the next title's primary. */
private val MIRROR_NAMES = mapOf(
    "cinesrc" to "CineSrc",
    "anipm" to "AniPM",
    "vidrift" to "VidRift",
    "vidzen" to "VidZen",
    "vidbolt" to "VidBolt",
    "vidcore" to "VidCore",
    "vidsrctw" to "VidSrc.tw",
    "vidsrcsh" to "VidSrc.sh",
    "vidsrcapi" to "VidSrc API",
    // Direct HLS slot (pre-build): named here for the bar label, but never
    // listed by mirrorsFor - it enters the sheet only under the hidden
    // hlstest flag (qa.js pins both halves of that).
    "hls" to "Direct HLS",
)

private fun mirrorsFor(title: Title, mal: Int?, ani: Int?): List<String> = when (title.type) {
    "anime" -> buildList {
        if (mal != null || ani != null) add("anipm")
        add("vidrift")
        if (!Regex("doraemon", RegexOption.IGNORE_CASE).containsMatchIn(title.name)) add("vidbolt")
        // Verified 2026-10-17: VidCore + VidSrc family all take TMDB ids and
        // cover anime via the TV endpoint (VidSrc anime guide).
        addAll(listOf("vidcore", "vidsrcsh", "vidsrctw", "vidsrcapi"))
    }
    else -> listOf(
        "cinesrc", "vidrift", "vidzen", "vidbolt",
        "vidcore", "vidsrcsh", "vidsrctw", "vidsrcapi",
    )
}

private fun defaultMirror(title: Title, mal: Int?, ani: Int?): String = when {
    title.type == "anime" -> if (mal != null || ani != null) "anipm" else "vidrift"
    // Korean dramas ride VidBolt (0.41): its TV route carries the
    // multi-audio Korean releases and our sheet drives its real tracks.
    title.lang == "ko" -> "vidbolt"
    else -> "cinesrc"
}

/** One auto-hop chain when a mirror stalls or serves its own WEBSITE instead
    of the player (AniPM's "Watch now" page is undrivable on TV) - website
    stuckFallback spirit, first entry allowed for this title wins. */
private fun fallbackChain(m: String): List<String> {
    val extras = listOf("vidcore", "vidsrcsh", "vidsrctw", "vidsrcapi")
    return when (m) {
        "anipm" -> listOf("vidrift", "vidbolt", "cinesrc") + extras
        "cinesrc" -> listOf("vidrift", "vidzen", "vidbolt") + extras
        "vidrift" -> listOf("vidzen", "vidbolt", "cinesrc", "anipm") + extras
        "vidzen" -> listOf("vidbolt", "vidrift", "cinesrc") + extras
        "vidbolt" -> listOf("vidrift", "vidzen", "cinesrc") + extras
        else -> (listOf("vidrift", "vidzen", "cinesrc", "anipm") + extras).filter { it != m }
    }
}

/* Providers ship EMBEDS: top-level loads get their website shell (AniPM
   "Watch now" page) or their anti-leech sandbox wall (VidRift). The website
   runs each one inside an iframe - so do we. CineSrc is the exception: it
   serves its bare player either way and our hook drives it directly. */
private val WRAPPED = setOf("anipm", "vidrift", "vidzen", "vidbolt")

private fun mirrorBase(m: String): String = when (m) {
    "anipm" -> "https://ani.pm/"
    "vidrift" -> "https://embed.vidrift.net/"
    "vidzen" -> "https://vidzen.fun/"
    "vidbolt" -> "https://vidbolt.pro/"
    else -> "https://cinesrc.st/"
}

/** Load the provider the way the website does: embed URL in our own shell
    page's iframe (postMessage contract flows both ways via SB_HOOK). */
private fun loadMirror(w: WebView, embedUrl: String, m: String) {
    if (m in WRAPPED) {
        val html = "<!DOCTYPE html><html><head>" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "</head><body style=\"margin:0;background:#000;overflow:hidden\">" +
            "<iframe src=\"" + embedUrl + "\" " +
            "style=\"border:0;position:fixed;inset:0;width:100%;height:100%\" " +
            "allow=\"autoplay; encrypted-media; fullscreen; picture-in-picture\" " +
            "allowfullscreen></iframe></body></html>"
        w.loadDataWithBaseURL(mirrorBase(m), html, "text/html", "utf-8", null)
    } else {
        w.loadUrl(embedUrl)
    }
}

/** Builds the provider embed URL. Contracts (verified against the live
    players): CineSrc /embed/movie/:id and /embed/tv/:id?s=&e= with
    &controls=false hiding ITS bar (ours is the player, house rule); AniPM
    /embed/mal/:mal/:ep/dub|sub (valid routes; /en is a 404 and is never
    generated here); VidRift /embed/tv|movie (mobileSheets); VidZen /tv|movie
    (autoPlay + theme + startAt); VidBolt /tv|movie|anime/mal (autoPlay +
    mobileSheets + startAt - its skip-seek can black the surface, so skips
    reload at startAt like the website). */
fun playerUrl(
    title: Title,
    season: Int,
    episode: Int,
    mal: Int?,
    ani: Int?,
    mirror: String = "",
    startAt: Float = 0f,
    langOverride: String = "",
    quality: String = "",
): String {
    val m = mirror.ifEmpty { defaultMirror(title, mal, ani) }
    val ep = episode.coerceAtLeast(1)
    val st = startAt.toInt().coerceAtLeast(0)
    val stq = if (st > 0) "&startAt=" + st else ""
    // Sanitize: the cycle contract is the only quality we ever send.
    val cq = if (quality in CINE_Q && quality.isNotEmpty()) "&quality=" + quality else ""
    return when (m) {
        "anipm" -> {
            // House rule pinned forever: Dragon Ball = English dub with the
            // Bruce Faulconer score; everything else starts on sub. A real
            // user pick (detail chips / Sub-Dub pill) may override per play -
            // and only ever as dub|sub (the routes know no other value).
            val lang = when {
                langOverride == "dub" || langOverride == "sub" -> langOverride
                Regex("dragon\\s*ball", RegexOption.IGNORE_CASE).containsMatchIn(title.name) -> "dub"
                else -> "sub"
            }
            val base = if (mal != null) {
                "https://ani.pm/embed/mal/" + mal + "/" + ep + "/" + lang
            } else {
                "https://ani.pm/embed/ani/" + (ani ?: title.id) + "/" + ep + "/" + lang
            }
            base + "?color=14b8a6" + (if (st > 0) "&start=" + st else "")
        }
        "vidrift" -> if (title.type == "movie") {
            "https://embed.vidrift.net/embed/movie/" + title.id + "?mobileSheets=true"
        } else {
            "https://embed.vidrift.net/embed/tv/" + title.id + "/" + season + "/" + ep + "?mobileSheets=true"
        }
        "vidzen" -> if (title.type == "movie") {
            "https://vidzen.fun/movie/" + title.id + "?autoPlay=true&theme=14b8a6" + stq
        } else {
            "https://vidzen.fun/tv/" + title.id + "/" + season + "/" + ep + "?autoPlay=true&theme=14b8a6" + stq
        }
        "vidbolt" -> when (title.type) {
            "movie" -> "https://vidbolt.pro/movie/" + title.id + "?autoPlay=true&mobileSheets=true&theme=14b8a6" + stq
            "anime" -> "https://vidbolt.pro/anime/mal/" + (mal ?: ani ?: title.id) + "/" + ep +
                "?autoPlay=true&mobileSheets=true&theme=14b8a6" + stq
            else -> "https://vidbolt.pro/tv/" + title.id + "/" + season + "/" + ep +
                "?autoPlay=true&mobileSheets=true&theme=14b8a6" + stq
        }
        // VidCore (docs 2026-10-17): TMDB embeds, autoplay/startAt/theme.
        "vidcore" -> {
            val base = if (title.type == "movie") {
                "https://vidcore.org/embed/movie/" + title.id
            } else {
                "https://vidcore.org/embed/tv/" + title.id + "/" + season + "/" + ep
            }
            base + "?autoplay=true&theme=14b8a6" + (if (st > 0) "&startAt=" + st else "")
        }
        // VidSrc family (docs 2026-10-17): one product, mirror domains -
        // TMDB/IMDb ids, autoplay + startAt resume. "VidSrc API" integrates
        // via its working docs host: the canonical vidsrcapi.com does not
        // resolve DNS (verified), while vidsrc3.created.app serves embeds.
        "vidsrctw" -> vidsrcUrl("vidsrc.tw", title, season, ep, st)
        "vidsrcsh" -> vidsrcUrl("vidsrc.sh", title, season, ep, st)
        "vidsrcapi" -> vidsrcUrl("vidsrc3.created.app", title, season, ep, st)
        else -> if (title.type == "movie") {
            "https://cinesrc.st/embed/movie/" + title.id + "?controls=false" + cq
        } else {
            "https://cinesrc.st/embed/tv/" + title.id + "?s=" + season + "&e=" + episode + "&controls=false" + cq
        }
    }
}

/** VidSrc-family embed (tv/movie by TMDB id - anime rides the TV route). */
private fun vidsrcUrl(host: String, title: Title, season: Int, ep: Int, st: Int): String {
    val path = if (title.type == "movie") {
        "https://" + host + "/embed/movie/" + title.id
    } else {
        "https://" + host + "/embed/tv/" + title.id + "/" + season + "/" + ep
    }
    return path + "?autoplay=1" + (if (st > 0) "&startAt=" + st else "")
}

/** Mirrors whose embed URL itself carries the resume position (startAt /
    start). Everything else (cinesrc, vidrift) gets ONE deferred seek when
    the player reports ready - so a provider switch never loses the spot. */
private fun urlCarriesStart(m: String): Boolean =
    m == "vidzen" || m == "vidbolt" || m == "anipm" || m == "vidcore" || m.startsWith("vidsrc") || m == "hls"

private fun fmt(s: Float): String {
    val total = s.toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Skip hit (TV + anime only, never movies). Priority: recap -> intro ->
    credits ("Next Ep") -> preview, same spirit as the website. The pill
    exists ONLY inside the range. */
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
    skips.preview.firstOrNull(::inRange)?.let {
        return SkipHit("preview", it.end, "Skip Preview")
    }
    return null
}

/** Media file extensions the watchdog counts as stream liveness (suffix
    match - never spelled as dotted literals, see the Ad Shield hook). */
private val MEDIA_EXTS = setOf("m3u8", "mpd", "ts", "m4s", "mp4", "webm", "aac", "m4a")

/** VidBolt's mobile-option command listener (audio/subtitles/speed) only
    registers when its NARROW-VIEWPORT gate passes - the phone works natively,
    a TV never got the listener and every tap died silently (0.44/0.45 bug).
    Their player modules get this matchMedia shim PREPENDED so the gate opens
    on every screen; the command values are proven by the phone. */
private val VB_MATCHMEDIA_SHIM =
    "try{var o=window.matchMedia;window.matchMedia=function(q){try{" +
        "if(typeof q==='string'&&q.indexOf('max-width')>=0)return{matches:true,media:q,onchange:null," +
        "addListener:function(){},removeListener:function(){},addEventListener:function(){}," +
        "removeEventListener:function(){},dispatchEvent:function(){return false}};}catch(e){}" +
        "return o.call(window,q);};}catch(e){}"

private val VB_JS_CACHE = java.util.Collections.synchronizedMap(HashMap<String, String>())

/** Serve a VidBolt player module with the gate shim prepended (cached by
    content-hashed URL). Null falls through to the network untouched. */
private fun patchedVbJs(url: String): android.webkit.WebResourceResponse? {
    return try {
        var body = VB_JS_CACHE[url]
        if (body == null) {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty(
                "user-agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
            conn.setRequestProperty("accept", "application/javascript,*/*")
            val src = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
            body = VB_MATCHMEDIA_SHIM + "\n" + src
            VB_JS_CACHE[url] = body
        }
        android.webkit.WebResourceResponse(
            "application/javascript", "utf-8",
            java.io.ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)),
        )
    } catch (e: Exception) {
        null
    }
}

/** JS shim: hooks the embed's <video>, forwards its events to Kotlin, and
    exposes window.__sb commands. Direct video driving PLUS the provider's
    own postMessage contract - belt and braces, so our bar always answers.
    AniPM gets its real protocol (website parity): {ns:"anipm.player",v:1,
    id,cmd,args} posted to the embed window, hello handshake + pre-ready
    queue (the player drops early mail), quality/subs/language/rate cmds.
    VidRift: resume/quality only (NO play/pause API - taps at the surface).
    VidZen/VidBolt: {type:"play"|"pause"|"seek"+time} with pre-ready seek
    queues; VidBolt durations ride durationchange (timeupdate has time only).
    All folds land as the same Android.event types, so Kotlin stays one code
    path regardless of who is on screen. */
private val SB_HOOK = """
(function(){
  if (window.__sbHooked) return; window.__sbHooked = true;
  function send(t, o){ try { Android.event(t, JSON.stringify(o||{})); } catch(e){} }
  var h = location.hostname;
  var ANIPM = h === "ani.pm" || h.endsWith(".ani.pm");
  var VR = h === "vidrift.net" || h.endsWith(".vidrift.net");
  var VZ = h === "vidzen.fun" || h.endsWith(".vidzen.fun");
  var VB = h === "vidbolt.pro" || h.endsWith(".vidbolt.pro") ||
           h === "vidbolt.xyz" || h.endsWith(".vidbolt.xyz");
  var VC = h === "vidcore.org" || h.endsWith(".vidcore.org");
  var VS = h === "vidsrc.tw" || h.endsWith(".vidsrc.tw") ||
           h === "vidsrc.sh" || h.endsWith(".vidsrc.sh") ||
           h === "vidsrc3.created.app";
  var seq = 0, live = !ANIPM, q = [];
  function post(m){
    try { window.postMessage(m, "*"); } catch(e){}
    try { for (var i = 0; i < window.frames.length; i++) window.frames[i].postMessage(m, "*"); } catch(e){}
  }
  function anipmSend(cmd, args){
    var m = { ns: "anipm.player", v: 1, id: ++seq, cmd: cmd, args: args || {} };
    post(m);
    if (!live) q.push(m);
  }
  function hello(){ post({ ns: "anipm.player", v: 1, id: 0, cmd: "hello", args: {} }); }
  // VidRift has no play/pause message: tap the surface (their UI toggles).
  function tapSurface(){
    try {
      var v = document.querySelector('video');
      if (!v) return;
      var r = v.getBoundingClientRect();
      var x = r.left + r.width / 2, y = r.top + r.height / 2;
      var el = document.elementFromPoint(x, y) || v;
      var ev = function(t){ el.dispatchEvent(new MouseEvent(t, {bubbles: true, cancelable: true, clientX: x, clientY: y})); };
      ev('mousedown'); ev('mouseup'); ev('click');
    } catch(e){}
  }
  function boltSeek(t){
    var cl = Math.max(0, Math.floor(t));
    if (!window.__sbBoltOn) { window.__sbBoltQ = cl; return; }
    window.__sbBoltQ = null;
    post({ type: 'seek', time: cl });
  }
  window.addEventListener("message", function(e){
    var m = e.data; if (!m || typeof m !== "object") return;
    // VidSrc family contract (docs 2026-10-17): {type:"PLAYER_EVENT", data:{
    // player_status, player_progress, player_duration}} - events OUT only.
    if (m.type === "PLAYER_EVENT") {
      var d2 = m.data || {};
      var st2 = String(d2.player_status || "").toLowerCase();
      var t2 = { currentTime: d2.player_progress || 0, duration: d2.player_duration || 0 };
      if (st2 === "playing") { send("play", {}); send("timeupdate", t2); }
      else if (st2 === "paused") { send("pause", {}); send("timeupdate", t2); }
      else if (st2 === "completed") send("ended", {});
      else if (st2 === "seeked") send("timeupdate", t2);
      return;
    }
    // VidCore contract (docs 2026-10-17): vidcore:play / vidcore:pause /
    // vidcore:ended - events OUT only, no inbound commands.
    var vt = String(m.type || "");
    if (vt === "vidcore:play" || (vt === "vidcore" && m.event === "play")) { send("play", {}); return; }
    if (vt === "vidcore:pause" || (vt === "vidcore" && m.event === "pause")) { send("pause", {}); return; }
    if (vt === "vidcore:ended" || (vt === "vidcore" && m.event === "ended")) { send("ended", {}); return; }
    if (m.ns === "anipm.player") {
      if (m.id === 0 && m.ok) { live = true; while (q.length) post(q.shift()); return; }
      var ev = m.event, d = m.data || {};
      if (ev === "ready") { live = true; while (q.length) post(q.shift()); send("ready", {}); }
      if (ev === "play" || ev === "playing") send("play", {});
      else if (ev === "pause" || ev === "paused") send("pause", {});
      else if (ev === "ended") send("ended", {});
      else if (ev === "timeupdate") send("timeupdate", {currentTime: d.currentTime||0, duration: d.duration||0});
      else if (ev === "durationchange") send("loadedmetadata", {duration: d.duration||0});
      else if (ev === "qualitychange") send("anipm:quality", {qualities: d.qualities||[], quality: d.quality});
      else if (ev === "subtitlechange") send("anipm:subtitles", {tracks: d.tracks||[]});
      else if (ev === "languagechange") send("anipm:lang", {lang: d.lang||d.language||""});
      else if (ev === "episodechange") send("anipm:episode", {episode: d.episode, season: d.season});
      else if (ev === "autoplayblocked") send("anipm:autoplay", {});
      return;
    }
    if (VR) {
      if (m.type === "vidrift:progress") send("timeupdate", {currentTime: m.currentTime||0, duration: m.duration||0});
      else if (m.type === "vidrift:paused") send("pause", {});
      else if (m.type === "vidrift:unpaused") send("play", {});
      else if (m.type === "vidrift:ended") send("ended", {});
      else if (m.type === "vidrift:episode") send("mirror:episode", {season: m.season, episode: m.episode});
      else if (m.type === "vidrift:nextup-play") send("mirror:nextup", {season: m.season, episode: m.episode});
      return;
    }
    if (VZ) {
      var evn = "", c, du;
      if (m.type === "vidzen") { evn = String(m.event||"").toLowerCase(); c = m.currentTime; du = m.duration; }
      else if (m.type === "vidzen:status") {
        if (typeof m.paused === "boolean") send(m.paused ? "pause" : "play", {});
        return;
      } else if (m.type === "PLAYER_EVENT" && m.data && typeof m.data === "object") {
        var dd = m.data;
        evn = String(dd.event || dd.player_status || "").toLowerCase();
        c = dd.currentTime != null ? dd.currentTime : dd.player_progress;
        du = dd.duration != null ? dd.duration : dd.player_duration;
      } else return;
      if (evn === "timeupdate" || evn === "progress" || evn === "seeked" || evn === "seeking") {
        window.__sbZenOn = true;
        if (window.__sbZenQ != null) {
          var zq = window.__sbZenQ; window.__sbZenQ = null;
          post({ type: 'seek', time: Math.floor(zq) });
        }
        send("timeupdate", {currentTime: c||0, duration: du||0});
      }
      else if (evn === "play" || evn === "playing" || evn === "unpaused") { window.__sbZenOn = true; send("play", {}); }
      else if (evn === "pause" || evn === "paused") send("pause", {});
      else if (evn === "ended" || evn === "complete") send("ended", {});
      return;
    }
    if (VB) {
      if (m.type === "durationchange") { window.__sbDur = m.duration||0; send("loadedmetadata", {duration: m.duration||0}); vbTracks(); return; }
      if (m.type === "timeupdate") {
        window.__sbBoltOn = true;
        if (window.__sbBoltQ != null) {
          var bq = window.__sbBoltQ; window.__sbBoltQ = null;
          post({ type: 'seek', time: Math.floor(bq) });
        }
        send("timeupdate", {currentTime: m.time||0, duration: window.__sbDur||0});
        return;
      }
      if (m.type === "ready") { send("ready", {}); vbTracks(); return; }
      if (m.type === "play" || m.type === "playing") { window.__sbBoltOn = true; send("play", {}); return; }
      if (m.type === "pause" || m.type === "paused") { send("pause", {}); return; }
      if (m.type === "ended") { send("ended", {}); return; }
      if (m.type === "episodechange") { send("mirror:episode", {season: m.season, episode: m.episode}); return; }
      // Mobile panel delegation (their ?mobileSheets contract): options
      // carry their own command values + selected flags - echo verbatim.
      if (m.type === "mobile-panel" && Array.isArray(m.options)) {
        try {
          m.options.forEach(function(o){
            if (!o || typeof o !== "object") return;
            var lab = String(o.label != null && o.label !== "" ? o.label : (o.value != null ? o.value : ""));
            var val = String(o.value != null ? o.value : lab);
            if (!lab) return;
            if (o.group === "Audio") { VBT.audios[val] = lab; if (o.selected) VBT.audioSel = val; }
            else if (o.group === "Subtitles") { VBT.subs[val] = lab; if (o.selected) VBT.subSel = val; }
          });
          vbPublish();
        } catch(e){}
        return;
      }
    }
  });
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
    play: function(){
      var v=document.querySelector('video'); if(v) v.play();
      if (ANIPM) anipmSend('play', {});
      else if (VZ || VB) post({type:'play'});
      else if (VR || VC || VS) tapSurface();
      try{window.postMessage({type:'cinesrc:command',command:'play',args:[]},'*');}catch(e){}
    },
    pause: function(){
      var v=document.querySelector('video'); if(v) v.pause();
      if (ANIPM) anipmSend('pause', {});
      else if (VZ || VB) post({type:'pause'});
      else if (VR || VC || VS) tapSurface();
      try{window.postMessage({type:'cinesrc:command',command:'pause',args:[]},'*');}catch(e){}
    },
    seek: function(t){
      var v=document.querySelector('video'); if(v) v.currentTime=t;
      if (ANIPM) anipmSend('seek', {time: t});
      else if (VR) post({type:'vidrift:resume', currentTime: t});
      else if (VZ) {
        if (!window.__sbZenOn) { window.__sbZenQ = t; }
        else post({type:'seek', time: Math.floor(t)});
      }
      else if (VB) boltSeek(t);
      try{window.postMessage({type:'cinesrc:command',command:'seek',args:[t]},'*');}catch(e){}
    },
    mute: function(m){
      var v=document.querySelector('video'); if(v) v.muted=!!m;
      if (ANIPM) anipmSend('setMuted', {muted: !!m});
      // VidBolt speaks volume (its message contract): 0 mutes, 1 restores.
      if (VB) post({type:'volume', volume: m?0:1});
      try{window.postMessage({type:'cinesrc:command',command:'setMuted',args:[!!m]},'*');}catch(e){}
    },
    rate: function(r){
      var v=document.querySelector('video'); if(v) v.playbackRate=r;
      if (ANIPM) anipmSend('setPlaybackRate', {rate: r});
      // VidBolt speaks speed through its mobile-option contract (0.42).
      if (VB) post({type:'mobile-option', option:'speed', value:r});
      try{window.postMessage({type:'cinesrc:command',command:'setPlaybackRate',args:[r]},'*');}catch(e){}
    },
    q: function(n){ if(ANIPM) anipmSend('setQuality', {height: n}); },
    lang: function(l){ if(ANIPM) anipmSend('setLanguage', {lang: l}); },
    subs: function(on, label){ if(ANIPM) anipmSend('setSubtitles', on ? {enabled: true, label: label||''} : {enabled: false}); },
    // VidBolt audio + subtitle tracks (0.41): its own mobile-option message
    // contract - audio matches by LABEL, subtitles by INDEX into its track
    // list. vbsub also flips the textTrack modes so the change SHOWS even
    // if the index lands on the lazy-loaded half of the list.
    vbaudio: function(val){
      try{ for (var i = 0; i < window.frames.length; i++) window.frames[i].postMessage({type:'mobile-option', option:'audio', value:String(val)}, '*'); }catch(e){}
    },
    vbsub: function(val){
      var idx = parseFloat(val);
      try{ for (var i = 0; i < window.frames.length; i++) window.frames[i].postMessage({type:'mobile-option', option:'subtitles', value: val}, '*'); }catch(e){}
      try{
        var fw = window.frames[0];
        var v = fw && fw.document && fw.document.querySelector('video');
        if (v && v.textTracks && Number.isFinite(idx)) {
          for (var j = 0; j < v.textTracks.length; j++) {
            v.textTracks[j].mode = (idx >= 0 && j === idx) ? 'showing' : 'hidden';
            if (idx < 0) v.textTracks[j].mode = 'disabled';
          }
        }
      }catch(e){}
    },
    autoNext: function(on){ if(ANIPM) anipmSend('setAutoNext', {on: !!on}); },
    autoSkip: function(on){ if(ANIPM) anipmSend('setAutoSkip', {on: !!on}); }
  };
  // VidBolt track discovery (0.41/0.43): the wrapper page and the player
  // iframe share origin (both vidbolt.pro), so we read the player's own
  // resolve data for its audio languages + subtitle labels - REAL names
  // only, no stream URL ever leaves the WebView. The player's own
  // mobile-panel delegation (narrow viewports) merges into the same
  // snapshot with its exact command values.
  var VBT = { audios: {}, subs: {}, audioSel: "", subSel: "" };
  function vbPublish(){
    var au = Object.keys(VBT.audios), su = Object.keys(VBT.subs);
    if (!au.length && !su.length) return;
    send("vb:tracks", {
      audios: au.map(function(v){ return { label: VBT.audios[v] || v, value: v }; }),
      subs: su.map(function(v){ return { label: VBT.subs[v] || v, value: v }; }),
      audioSel: VBT.audioSel || "",
      subSel: VBT.subSel || ""
    });
  }
  function vbLabelOf(track, i){
    try {
      var nm = track && track.label;
      if (nm && !/^(native|original|default|und|track \d+)$/i.test(nm)) return nm;
      if (track && track.language) {
        try {
          var dn = new Intl.DisplayNames(["en"], {type: "language"});
          var got = dn.of(track.language);
          if (got) return got;
        } catch(e2){}
        return track.language;
      }
    } catch(e){}
    return "Track " + (i + 1);
  }
  function vbTracks(){
    try {
      var fw = window.frames[0];
      var bt = fw && fw.__VB__ && fw.__VB__.title;
      if (!bt) return;
      var q = "type=" + encodeURIComponent(bt.type || "tv") + "&id=" + encodeURIComponent(bt.id || "");
      if (bt.type === "tv" && bt.season) q += "&season=" + encodeURIComponent(bt.season);
      if (bt.episode) q += "&episode=" + encodeURIComponent(bt.episode);
      fetch("/api/resolve?" + q).then(function(r){ return r.ok ? r.json() : {}; }).then(function(j){
        try {
          var s = (j && j.stream) || null;
          var anime = bt.type === "anime";
          ((s && s.sources) || []).forEach(function(n){
            var l = n && n.language;
            if (!l) return;
            // their audio labels: en() maps only for anime routes
            var lab = anime ? (/^japanese$/i.test(l) ? "Japanese (Sub)" : /^english$/i.test(l) ? "English (Dub)" : l) : l;
            VBT.audios[lab] = lab;
          });
          try {
            var v = fw.document && fw.document.querySelector("video");
            if (v && v.textTracks) {
              for (var i = 0; i < v.textTracks.length; i++) {
                var lb = vbLabelOf(v.textTracks[i], i);
                VBT.subs[lb] = lb;
              }
            }
          } catch(e2){}
          ((s && s.subtitles) || []).forEach(function(n){
            var l = n && n.label;
            if (l) VBT.subs[l] = l;
          });
          vbPublish();
        } catch(e2){}
      }, function(){});
    } catch(e){}
  }
  // VidBolt only REGISTER its mobile-option command listener (audio,
  // subtitles, speed) when its narrow-viewport check passes - on a TV the
  // listener never exists and every command dies silently (0.44 fix: the
  // tracks listed but taps never changed the audio). We shim matchMedia
  // INSIDE the same-origin iframe before their module evaluates Bt() -
  // the module is a network fetch, so this poll always wins the race.
  function vbNarrowShim(){
    var tries = 0;
    var iv = setInterval(function(){
      tries++;
      try {
        var fw = window.frames[0];
        var doc = fw && fw.document;
        if (doc && doc.head) {
          var s = doc.createElement("script");
          s.textContent = "try{var o=window.matchMedia;window.matchMedia=function(q){try{if(typeof q==='string'&&q.indexOf('max-width')>=0)return{matches:true,media:q,onchange:null,addListener:function(){},removeListener:function(){},addEventListener:function(){},removeEventListener:function(){},dispatchEvent:function(){return false}};}catch(e){}return o.call(window,q);};}catch(e){}";
          doc.head.appendChild(s);
          clearInterval(iv);
        }
      } catch(e){}
      if (tries > 600) clearInterval(iv);
    }, 5);
  }
  if (VB) {
    vbNarrowShim();
    vbTracks();
    setTimeout(vbTracks, 5000);
    setTimeout(vbTracks, 15000);
  }
  if (ANIPM) {
    hello();
    var tries = 0;
    var timer = setInterval(function(){ if (live || ++tries > 10) { clearInterval(timer); return; } hello(); }, 1000);
  }
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

// CineSrc quality cycle (website QLEVELS contract): ONLY ""/1080/720/480.
// "2160" is never sent anywhere - house rule (qa.js pins both sides).
private val CINE_Q = listOf("", "1080", "720", "480")

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
    // VidBolt's own injected ad rotator (seen live 0.41: do.miscoinbrassic).
    "miscoinbrassic.com",
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
        h == "ani.pm" || h.endsWith(".ani.pm") ||
        h == "vidrift.net" || h.endsWith(".vidrift.net") ||
        h == "vidzen.fun" || h.endsWith(".vidzen.fun") ||
        h == "vidbolt.pro" || h.endsWith(".vidbolt.pro") ||
        h == "vidbolt.xyz" || h.endsWith(".vidbolt.xyz") ||
        h == "vidcore.org" || h.endsWith(".vidcore.org") ||
        h == "vidsrc.tw" || h.endsWith(".vidsrc.tw") ||
        h == "vidsrc.sh" || h.endsWith(".vidsrc.sh") ||
        h == "vidsrc3.created.app"
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
    lang: String? = null,
    onHome: () -> Unit,
) {
    val ctx = LocalContext.current
    val big = AppEnv.isTv

    // Resume (Continue Watching / Detail Resume / My List): playback boots
    // at the SAVED spot for THIS episode (or this movie) - 0.35 fix, the
    // WebView boot hardcoded 0f so Continue Watching silently restarted
    // the title. Opening a different episode than the saved one starts it
    // fresh; anime rides the same seam (anipm carries start= in its URL).
    val resumeAt = run {
        val sp = Store.progressList(ctx).firstOrNull { it.type == title.type && it.id == title.id }
        if (sp != null && sp.cur > 5f &&
            (title.type == "movie" || (sp.season == season && sp.episode == episode))
        ) sp.cur else 0f
    }

    var playing by remember { mutableStateOf(true) }
    var cur by remember { mutableFloatStateOf(resumeAt) }
    var dur by remember { mutableFloatStateOf(0f) }
    var muted by remember { mutableStateOf(false) }
    var rateIdx by remember { mutableIntStateOf(0) }
    // CineSrc quality pick ("" = Auto): rides the embed URL, so a change
    // reopens at the current position like the startAt seeks.
    var cinesrcQ by remember { mutableStateOf("") }
    var scrub by remember { mutableFloatStateOf(-1f) }
    var showEps by remember { mutableStateOf(false) }
    var epSeason by remember { mutableIntStateOf(season) }
    var epNumber by remember { mutableIntStateOf(episode) }
    var skips by remember { mutableStateOf(IntroDb.Skips(listOf(), listOf(), listOf())) }
    var web by remember { mutableStateOf<WebView?>(null) }
    // Direct HLS slot (pre-build): the Media3 engine is created ONLY when
    // the hidden hlstest flag opens the slot - dormant in every normal run.
    val hlsPlayer = remember {
        if (AppEnv.hlsTest) ExoPlayer.Builder(ctx).build().also { it.playWhenReady = true } else null
    }
    var hlsLoaded by remember { mutableStateOf(false) }
    var layer by remember { mutableStateOf(LAYER_VIDEO) }
    var lastSave by remember { mutableStateOf(0L) }
    // Active mirror (website routing): per-title default; a mid-session
    // switch never changes the next title's primary.
    val bootMirror = if (AppEnv.hlsTest) "hls" else defaultMirror(title, mal, ani)
    var mirror by remember {
        mutableStateOf(bootMirror)
    }
    val serverList = remember(title.id, title.type, mal, ani) {
        mirrorsFor(title, mal, ani) + if (AppEnv.hlsTest) listOf("hls") else listOf()
    }
    // Auto-recovery: bad route / website page / dead embed gets ONE hop to
    // the next allowed mirror (never loops - one hop per title).
    var gotMedia by remember { mutableStateOf(false) }
    var hopped by remember { mutableStateOf(false) }
    // Last real media traffic (segment/manifest requests) - the watchdog's
    // patience meter (0.37: slow AniPM/CineSrc loads must never hop away).
    var lastMediaAt by remember { mutableStateOf(0L) }
    // One deferred seek for mirrors without URL startAt (provider switches
    // AND the boot resume on URL-less mirrors like cinesrc/vidrift).
    var pendingSeek by remember {
        mutableStateOf(if (resumeAt > 0f && !urlCarriesStart(bootMirror)) resumeAt else -1f)
    }
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
    // Placement + sync are settings, remembered per title (site behavior):
    // they outlive the file and every episode of the title.
    val subPref = Store.subsPref(ctx, title.type, title.id)
    var subPos by remember { mutableStateOf(subPref.pos) }
    var subOffset by remember { mutableFloatStateOf(subPref.offset) }
    // Caption style (task spec: size/color/background opacity of OUR
    // overlay - device-local taste, never the embed's captions).
    var subStyle by remember { mutableStateOf(Store.subStyle(ctx)) }
    // AniPM protocol state (website anipm.player parity): the player reports
    // its quality ladder, audio language, and its own subtitle tracks.
    var anipmQs by remember { mutableStateOf(listOf<Int>()) }
    var anipmQ by remember { mutableStateOf<Int?>(null) }
    var anipmLang by remember {
        // Start language: the detail SUB|DUB pick wins, else the pinned
        // house default (Dragon Ball dub, everything else sub).
        mutableStateOf(
            lang?.takeIf { it == "dub" || it == "sub" }
                ?: (if (title.name.contains("dragon ball", true)) "dub" else "sub")
        )
    }
    var anipmSubLabels by remember { mutableStateOf(listOf<String>()) }
    var anipmSubIdx by remember { mutableIntStateOf(-1) } // -1 = player subs off
    // VidBolt real tracks (0.41/0.43): label + command VALUE pairs the
    // player's own data reports - the value is echoed verbatim into its
    // mobile-option contract so picks always land on the right track.
    var vbAudios by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var vbSubs by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var vbAudioSel by remember { mutableStateOf("") }
    var vbSubSel by remember { mutableStateOf("") }

    fun setSub(name: String, lang: String, cues: List<Cue>) {
        if (cues.isEmpty()) {
            subMsg = "Couldn't read that file - is it .srt or .vtt?"
            return
        }
        subs = SubFile(name, lang, cues)
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
                val lang = item.display.ifEmpty { wyzLang.uppercase() }
                setSub(item.release, lang, Subs.parse(text))
                // Remember the pick for THIS episode so reopening restores it.
                Store.saveSubPick(
                    ctx, title.type, title.id, epSeason, epNumber,
                    SubPick(item.url, item.release, lang),
                )
            }
        }
    }

    val sliderFR = remember { FocusRequester() }
    val playFR = remember { FocusRequester() }
    val skipFR = remember { FocusRequester() }
    // Key sink: an invisible focus holder. Compose only routes D-pad keys
    // through onPreviewKeyEvent while SOME node keeps focus - the old
    // clearFocus() on fade/layer-reset left none, so once chrome minimized
    // the remote went dead and the chrome could never come back (TV bug).
    val keySinkFR = remember { FocusRequester() }
    // Sheet entry points: on TV an open sheet OWNS the remote - focus lands
    // inside it and stays trapped there (focusProperties exit=Cancel). It
    // used to stay on the player-bar button BEHIND the panel, so the D-pad
    // kept driving the player instead of the sheet (bug report).
    val epsFirstFR = remember { FocusRequester() }
    val serverFirstFR = remember { FocusRequester() }

    fun save(c: Float, d: Float) {
        try {
            Store.saveProgress(
                ctx,
                Progress(title.type, title.id, title.name, title.poster, epSeason, epNumber, c, d, System.currentTimeMillis(), mal, ani, title.lang),
            )
        } catch (e: Exception) {
        }
    }

    fun load(startAt: Float = 0f) {
        chromeVisible = true
        // Mirrors whose URL carries startAt (vidzen/vidbolt/anipm) keep the
        // position themselves; the rest get one seek when the player reports
        // ready - switching providers never loses your place (task rule).
        pendingSeek = if (startAt > 0f && !urlCarriesStart(mirror)) startAt else -1f
        if (mirror == "hls") {
            // The HLS slot carries its own start position - no deferred seek
            // and no WebView. hlsLoaded latches here so the surface's boot
            // effect can never race a server-switch load back to 0.
            hlsLoaded = true
            val p = hlsPlayer ?: return
            val at = (startAt * 1000).toLong().coerceAtLeast(0)
            main.post {
                try {
                    p.setMediaItem(
                        MediaItem.fromUri(AppEnv.hlsTestUrl.ifEmpty { HLS_TEST_URL }),
                        if (at > 0) at else C.TIME_UNSET,
                    )
                    p.prepare()
                    p.playWhenReady = true
                } catch (e: Exception) {
                }
            }
            return
        }
        main.post {
            try {
                val w = web ?: return@post
                loadMirror(w, playerUrl(title, epSeason, epNumber, mal, ani, mirror, startAt, anipmLang, cinesrcQ), mirror)
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

    fun hopMirror() {
        if (hopped) return
        val fb = fallbackChain(mirror).firstOrNull { it != mirror && serverList.contains(it) } ?: return
        hopped = true
        val at = cur
        mirror = fb
        playing = true
        // The auto-hop keeps the spot (task rule: switching providers never
        // loses your place) - it used to reset to 0 like a fresh play.
        load(startAt = at)
    }

    // Startup watchdog (0.37 fix): hop only after 30s of TOTAL silence -
    // the old fixed 30s timer threw slow-loading AniPM/CineSrc over to
    // VidRift while the video was already coming up. Real media traffic
    // (segment fetches) or provider protocol messages keep it patient; a
    // genuinely dead embed still hops so the TV is never stuck.
    LaunchedEffect(mirror, epSeason, epNumber) {
        gotMedia = false
        val start = System.currentTimeMillis()
        while (true) {
            delay(5000)
            if (gotMedia) break
            val idleFor = System.currentTimeMillis() - maxOf(start, lastMediaAt)
            if (idleFor >= 30000) {
                hopMirror()
                break
            }
        }
    }

    fun seekTo(t: Float) {
        val target = t.coerceAtLeast(0f)
        cur = target
        val hp = hlsPlayer
        if (mirror == "hls" && hp != null) {
            // Native seek - the HLS slot owns its playback clock.
            hp.seekTo((target * 1000).toLong())
            return
        }
        if (mirror == "vidcore" || mirror.startsWith("vidsrc")) {
            // VidCore/VidSrc publish events only (no seek command) - their
            // one documented seek is startAt, so the remote's L/R reopens at
            // the target. Real control, no fake buttons (task rule).
            load(startAt = target)
            return
        }
        cmd("window.__sb && window.__sb.seek(" + target.toInt() + ")")
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
            runCatching { keySinkFR.requestFocus() }
        }
    }

    fun seekRel(d: Float) {
        seekTo((if (scrub >= 0) scrub else cur) + d)
    }

    fun togglePlay() {
        pokeChrome()
        playing = !playing
        val hp = hlsPlayer
        if (mirror == "hls" && hp != null) {
            if (playing) hp.play() else hp.pause()
            return
        }
        cmd(if (playing) "window.__sb && window.__sb.play()" else "window.__sb && window.__sb.pause()")
    }

    fun skipNow(h: SkipHit) {
        when {
            h.kind == "credits" -> {
                epNumber += 1
                save(0f, dur)
                cur = 0f
                playing = true
                load()
            }
            mirror == "vidbolt" && h.end != null -> {
                // VidBolt's in-place skip seeks can leave a black surface -
                // reopen at the marker through startAt (website boltSkipTo).
                h.end?.let { e -> cur = e; load(startAt = e) }
            }
            (mirror == "vidcore" || mirror.startsWith("vidsrc")) && h.end != null -> {
                // VidCore/VidSrc expose no seek command (events out only) -
                // skips reopen at startAt, the only seek they document.
                h.end?.let { e -> cur = e; load(startAt = e) }
            }
            else -> h.end?.let { seekTo(it) }
        }
    }

    // Quick-select rail for the Episodes sheet (design pack): real TMDB
    // season episodes, prefetched while the video loads so the sheet opens
    // instantly (speed pass 0.32).
    var epsList by remember { mutableStateOf(listOf<Ep>()) }
    var epsBusy by remember { mutableStateOf(false) }
    LaunchedEffect(epSeason, title.id) {
        if (title.type != "movie") {
            epsBusy = true
            epsList = withContext(Dispatchers.IO) {
                runCatching { Api.season(title.id, epSeason) }.getOrDefault(listOf())
            }
            epsBusy = false
        }
    }

    // Real US certification for the HUD chip ("" when TMDB reports none -
    // the chip simply does not render, nothing is ever invented).
    var cert by remember { mutableStateOf("") }
    LaunchedEffect(title.id, title.type) {
        cert = withContext(Dispatchers.IO) {
            runCatching { Api.cert(title.type, title.id) }.getOrDefault("")
        }
    }

    // HUD clock (design pack top bar, both player HUD and the sheets).
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val f = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.US)
        while (true) {
            clock = f.format(java.util.Date())
            kotlinx.coroutines.delay(30000)
        }
    }

    // Skip timings (TV + anime only - movies never skip). Three verified
    // sources merge: AniSkip (MAL id), SkipDB (IMDb id, duration-matched)
    // and TheIntroDB (TMDB id). Empty sources add nothing; the fetch runs
    // again once real stream duration is known so duration matching works.
    LaunchedEffect(title.id, title.type, epSeason, epNumber, mal, if (dur > 30) dur.toInt() else 0) {
        if (title.type == "movie") {
            skips = IntroDb.Skips(listOf(), listOf(), listOf())
        } else {
            val d = if (dur > 30) dur else 0f
            skips = withContext(Dispatchers.IO) {
                val db = runCatching {
                    IntroDb.fetch(title.id, epSeason, epNumber)
                }.getOrDefault(IntroDb.Skips(listOf(), listOf(), listOf()))
                val ani = if (mal != null) SkipTimes.aniSkip(mal, epNumber, d) else listOf()
                val imdb = runCatching { Api.imdbId(title.type, title.id) }.getOrNull() ?: ""
                val sk = if (imdb.isNotEmpty()) {
                    SkipTimes.skipDb(imdb, epSeason, epNumber, d)
                } else {
                    listOf()
                }
                fun ranges(hits: List<SkipTimes.Hit>, kind: String): List<IntroDb.TRange> =
                    hits.filter { it.kind == kind }.map { IntroDb.TRange(it.start, it.end) }
                val all = ani + sk
                IntroDb.Skips(
                    intro = db.intro + ranges(all, "intro"),
                    recap = db.recap + ranges(all, "recap"),
                    credits = db.credits + ranges(all, "credits"),
                    preview = db.preview + ranges(all, "preview"),
                )
            }
        }
    }

    // Subtitle search chain (website rules): Wyzie first (1,000/day shared
    // key), straight to backups when it is empty/capped/missing - TSDB
    // (keyless) then BetaSeries (TV only). Manual re-run pill bumps bsTick.
    var bsTick by remember { mutableIntStateOf(0) }
    suspend fun fetchSubChain(): Pair<String, List<WyzItem>> {
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
        return note to items
    }
    LaunchedEffect(showSubs, wyzLang, title.id, epSeason, epNumber, bsTick) {
        if (showSubs) {
            wyzBusy = true
            subMsg = ""
            val (note, items) = fetchSubChain()
            wyzItems = items
            subMsg = when {
                items.isEmpty() && note.isEmpty() -> "No subtitles found - load a .srt/.vtt file below."
                items.isEmpty() -> note + " Nothing found - load a .srt/.vtt file below."
                else -> note
            }
            wyzBusy = false
        }
    }

    // Per-episode memory: restore the last URL-backed subtitle pick when the
    // episode opens (website behavior). A manual local file can't be re-read
    // after a restart, so only chain picks persist. Movies + TV shows with
    // no saved pick AUTO-LOAD the chain's first hit (0.35 request) - captions
    // on from the first frame without opening the Subs sheet. Anime keeps
    // its embed's own captioning (a Faulconer dub has no business carrying
    // forced English text).
    LaunchedEffect(title.id, epSeason, epNumber) {
        val pick = withContext(Dispatchers.IO) {
            runCatching { Store.subPick(ctx, title.type, title.id, epSeason, epNumber) }.getOrNull()
        }
        if (pick != null) {
            val text = withContext(Dispatchers.IO) {
                runCatching { Wyz.fetchFile(pick.url) }.getOrNull()
            }
            if (text != null && subs == null) {
                setSub(pick.name, pick.lang, Subs.parse(text))
                return@LaunchedEffect
            }
        }
        if (title.type == "anime" || subs != null) return@LaunchedEffect
        val first = fetchSubChain().second.firstOrNull() ?: return@LaunchedEffect
        val text = withContext(Dispatchers.IO) {
            runCatching { Wyz.fetchFile(first.url) }.getOrNull()
        } ?: return@LaunchedEffect
        if (subs == null) {
            val lang = first.display.ifEmpty { wyzLang.uppercase() }
            setSub(first.release, lang, Subs.parse(text))
            // Remember it as THIS episode's pick so reopening restores the
            // same file instead of spending the chain again.
            Store.saveSubPick(
                ctx, title.type, title.id, epSeason, epNumber,
                SubPick(first.url, first.release, lang),
            )
        }
    }

    val capCue = remember(cur, subs, subOffset) {
        val f = subs ?: return@remember null
        val t = cur + subOffset
        f.cues.firstOrNull { t >= it.s && t <= it.e }
    }

    val hit = remember(cur, skips, title.type) {
        if (title.type == "movie") null else hitAt(cur, skips)
    }
    // pill vanished under us (range ended): fall back to the video layer
    LaunchedEffect(hit == null) {
        if (hit == null && layer == LAYER_SKIP) {
            layer = LAYER_VIDEO
            runCatching { keySinkFR.requestFocus() }
        }
    }

    // Auto-skip: intro/recap/preview seek past; credits auto-advance (Next
    // Ep), one skip per range per episode. Toggle lives in the Episodes
    // sheet and persists per device.
    var autoSkip by remember { mutableStateOf(Store.autoSkip(ctx)) }
    var autoDone by remember { mutableStateOf("") }
    LaunchedEffect(cur, hit, autoSkip) {
        val h = hit
        if (autoSkip && playing && h != null && h.end != null) {
            val key = epSeason.toString() + ":" + epNumber + ":" + h.kind + ":" + h.end
            if (autoDone != key) {
                autoDone = key
                if (h.kind == "credits") {
                    epNumber += 1
                    save(0f, dur)
                    cur = 0f
                    playing = true
                    load()
                } else {
                    h.end?.let { seekTo(it) }
                }
            }
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
                runCatching { keySinkFR.requestFocus() }
            }
            else -> {
                save(if (scrub >= 0) scrub else cur, dur)
                onHome()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            save(if (scrub >= 0) scrub else cur, dur)
            try { hlsPlayer?.release() } catch (e: Exception) {}
        }
    }

    // HLS slot events fold into the same seam the SB_HOOK bridge feeds.
    // REAL playback only counts for the watchdog (Media3's isPlaying is
    // false while buffering/stalled - same rule as the web seam, where
    // metadata/preview never counted as media).
    DisposableEffect(hlsPlayer) {
        val p = hlsPlayer
        val l = if (p == null) null else object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    gotMedia = true
                    playing = true
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playing = false
            }

            override fun onPlayerError(error: PlaybackException) {
                playing = false
            }
        }
        if (p != null && l != null) p.addListener(l)
        onDispose {
            if (p != null && l != null) p.removeListener(l)
        }
    }
    LaunchedEffect(hlsPlayer, mirror) {
        val p = hlsPlayer ?: return@LaunchedEffect
        if (mirror != "hls") return@LaunchedEffect
        while (true) {
            val d = p.duration
            if (d > 0) {
                cur = p.currentPosition / 1000f
                dur = d / 1000f
                val now = System.currentTimeMillis()
                if (now - lastSave > 5000) {
                    lastSave = now
                    save(cur, dur)
                }
            }
            delay(500)
        }
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
                LAYER_VIDEO -> keySinkFR.requestFocus()
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
        // Invisible focus holder: Compose dispatches D-pad keys through
        // onPreviewKeyEvent only while some node holds focus, so the sink
        // keeps the remote alive whenever nothing else wants focus. It has
        // no pointer handlers - touch passes straight through.
        Box(
            Modifier
                .matchParentSize()
                .focusRequester(keySinkFR)
                .focusable(),
        )

        // TV: keys must work from the very first press, and closing a panel
        // must hand focus back to the active layer - never leave zero focus.
        LaunchedEffect(Unit) {
            if (big) runCatching { keySinkFR.requestFocus() }
        }
        LaunchedEffect(showEps, showSubs) {
            if (big && !showEps && !showSubs) {
                runCatching {
                    when (layer) {
                        LAYER_SKIP -> skipFR.requestFocus()
                        LAYER_SLIDER -> sliderFR.requestFocus()
                        LAYER_BUTTONS -> playFR.requestFocus()
                        else -> keySinkFR.requestFocus()
                    }
                }
            }
        }
        LaunchedEffect(showEps, showSubs) {
            if (big) {
                runCatching {
                    when {
                        showEps -> epsFirstFR.requestFocus()
                        // The merged Audio & Subtitles panel enters at its
                        // first column (Servers).
                        showSubs -> serverFirstFR.requestFocus()
                    }
                }
            }
        }

        // Direct HLS slot: our own Media3 surface. Every chrome feature
        // (skip pills, captions, fade, watchdog, saves) reads the same
        // cur/dur/playing seam, so the bar below works unchanged.
        if (mirror == "hls") {
            val hp = hlsPlayer
            if (hp != null) {
                LaunchedEffect(hp) {
                    if (!hlsLoaded) {
                        hlsLoaded = true
                        load(startAt = resumeAt)
                    }
                }
                HlsSurface(
                    player = hp,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = { off ->
                                    if (off.x > size.width / 2f) seekRel(10f) else seekRel(-10f)
                                    pokeChrome()
                                },
                                onTap = {
                                    if (big) pokeChrome()
                                    else {
                                        chromeVisible = !chromeVisible
                                        pokeTick += 1
                                    }
                                },
                            )
                        },
                )
            }
        } else {
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
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = false
                    // Bot-wall insurance (VidRift is bot-walled): look like a
                    // real Chrome, allow player subresources, never block http
                    // media on https shells.
                    settings.userAgentString = (settings.userAgentString ?: "").replace("; wv", "")
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
                                val rawPath = request?.url?.path ?: ""
                                val ext = rawPath
                                    .lowercase(java.util.Locale.US)
                                    .substringAfterLast('.', "")
                                // Media NEVER gets blocked (0.41 VidBolt
                                // rule): the shield kills ad/tracker hosts
                                // only and never sandboxes the player -
                                // playback always wins.
                                if (ext in MEDIA_EXTS) {
                                    main.post { lastMediaAt = System.currentTimeMillis() }
                                } else {
                                    val host = (request?.url?.host ?: "").lowercase(java.util.Locale.US)
                                    val lp = rawPath.lowercase(java.util.Locale.US)
                                    // VidBolt player modules get the
                                    // command-gate shim prepended (0.45) so
                                    // audio/subtitle/speed taps work on TV
                                    // exactly like on the phone.
                                    if ((host == "vidbolt.pro" || host.endsWith(".vidbolt.pro")) &&
                                        lp.startsWith("/player/") && lp.endsWith(".js")
                                    ) {
                                        val target = request?.url?.toString()
                                        if (target != null) {
                                            patchedVbJs(target)?.let { return it }
                                        }
                                    }
                                    if (isAdHost(host)) {
                                        return android.webkit.WebResourceResponse(
                                            "text/plain", "utf-8",
                                            java.io.ByteArrayInputStream(ByteArray(0)),
                                        )
                                    }
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
                                // Watchdog counts REAL playback only - an idle
                                // preview video or bare metadata must not
                                // defeat the auto-hop (Beerus lesson). Provider
                                // PROTOCOL traffic (anipm:*/mirror:* only ever
                                // comes from the live player) counts too, so a
                                // wrapped player whose events are slow to
                                // surface is never hopped away mid-load.
                                if (t == "play" || t == "timeupdate" || t == "ended" ||
                                    t.startsWith("anipm:") || t == "mirror:episode" || t == "mirror:nextup"
                                ) {
                                    gotMedia = true
                                }
                                when (t) {
                                    "play" -> playing = true
                                    "pause" -> playing = false
                                    "ended" -> playing = false
                                    "loadedmetadata" -> {
                                        dur = o.optDouble("duration", 0.0).toFloat()
                                        if (pendingSeek >= 0f) {
                                            val t = pendingSeek
                                            pendingSeek = -1f
                                            seekTo(t)
                                        }
                                    }
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
                                    "anipm:quality" -> {
                                        val arr = o.optJSONArray("qualities")
                                        val qs = mutableListOf<Int>()
                                        if (arr != null) {
                                            for (i in 0 until arr.length()) {
                                                val it = arr.opt(i)
                                                val height = when (it) {
                                                    is Number -> it.toInt()
                                                    is org.json.JSONObject -> it.optInt("height", -1)
                                                    else -> -1
                                                }
                                                if (height in 240..4320) qs.add(height)
                                            }
                                        }
                                        anipmQs = qs.distinct().sortedDescending()
                                        val curQ = o.optInt("quality", -1)
                                        if (curQ > 0) anipmQ = curQ
                                    }
                                    "anipm:subtitles" -> {
                                        val arr = o.optJSONArray("tracks")
                                        val labels = mutableListOf<String>()
                                        if (arr != null) {
                                            for (i in 0 until arr.length()) {
                                                val it = arr.opt(i)
                                                val label = when (it) {
                                                    is org.json.JSONObject ->
                                                        it.optString("label")
                                                            .ifEmpty { it.optString("name") }
                                                            .ifEmpty { it.optString("language") }
                                                    else -> it?.toString() ?: ""
                                                }
                                                if (label.isNotBlank()) labels.add(label)
                                            }
                                        }
                                        anipmSubLabels = labels.distinct()
                                    }
                                    "anipm:lang" -> {
                                        val l = o.optString("lang").lowercase()
                                        if (l == "dub" || l == "sub") anipmLang = l
                                    }
                                    "anipm:episode" -> {
                                        // The player switched episodes itself
                                        // (phone taps): sync OUR state only -
                                        // reloading would double-switch.
                                        val e = o.optInt("episode", -1)
                                        val s = o.optInt("season", -1)
                                        if (e > 0) {
                                            save(0f, dur)
                                            if (s > 0) epSeason = s
                                            epNumber = e
                                            cur = 0f
                                        }
                                    }
                                    "vb:tracks" -> {
                                        // VidBolt reports its REAL audio
                                        // languages + subtitle labels with
                                        // their exact command values (0.43).
                                        fun parseTracks(arr: org.json.JSONArray?): List<Pair<String, String>> {
                                            val out = mutableListOf<Pair<String, String>>()
                                            if (arr == null) return out
                                            for (i in 0 until arr.length()) {
                                                val t = arr.optJSONObject(i) ?: continue
                                                val v = t.optString("value", "")
                                                val l = t.optString("label", "").ifEmpty { v }
                                                if (v.isNotBlank() && v != "null") out.add(l to v)
                                            }
                                            return out
                                        }
                                        val aSel = o.optString("audioSel", "")
                                        val sSel = o.optString("subSel", "")
                                        vbAudios = parseTracks(o.optJSONArray("audios"))
                                        vbSubs = parseTracks(o.optJSONArray("subs"))
                                        if (aSel.isNotBlank() && aSel != "null") vbAudioSel = aSel
                                        if (sSel.isNotBlank() && sSel != "null") vbSubSel = sSel
                                    }
                                    "anipm:autoplay" -> {
                                        // Real playback kick - NEVER the
                                        // mute-bypass. TV hands it a play.
                                        if (big) cmd("window.__sb && window.__sb.play()")
                                    }
                                    "ready" -> {
                                        // VidBolt TV: its shell waits for a
                                        // play kick (website parity).
                                        if (big && mirror == "vidbolt") cmd("window.__sb && window.__sb.play()")
                                        if (pendingSeek >= 0f) {
                                            val t = pendingSeek
                                            pendingSeek = -1f
                                            seekTo(t)
                                        }
                                    }
                                    "mirror:episode" -> {
                                        // Provider advanced internally (Up
                                        // Next / picker): sync state only -
                                        // reloading would double-switch.
                                        val e = o.optInt("episode", -1)
                                        val s = o.optInt("season", -1)
                                        if (e > 0) {
                                            save(0f, dur)
                                            if (s > 0) epSeason = s
                                            epNumber = e
                                            cur = 0f
                                        }
                                    }
                                    "mirror:nextup" -> {
                                        // Their Up Next fired under OUR
                                        // episode list - WE advance (website
                                        // parity: nextup-play means e+1).
                                        save(0f, dur)
                                        val s = o.optInt("season", -1)
                                        if (s > 0) epSeason = s
                                        epNumber = o.optInt("episode", epNumber) + 1
                                        cur = 0f
                                        playing = true
                                        load()
                                    }
                                    else -> {}
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }), "Android")
                    // TV: keep D-pad on OUR bar, never trapped in the embed.
                    // Block descendants too - the WebView's internal views
                    // must not steal focus from the key sink.
                    isFocusable = !big
                    isFocusableInTouchMode = !big
                    if (big) descendantFocusability =
                        android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    // Phone: a tap MINIMIZES the chrome and a tap brings it
                    // back (toggle); a double-tap seeks -10s left / +10s right
                    // (Netflix mobile pattern). TV has no touch - any key
                    // wakes the chrome there instead. The tap is never eaten.
                    var lastTapMs = 0L
                    var lastTapX = 0f
                    setOnTouchListener { v, ev ->
                        if (ev.action == android.view.MotionEvent.ACTION_UP) {
                            val now = System.currentTimeMillis()
                            val x = ev.x
                            val half = v.width / 2f
                            main.post {
                                if (big) {
                                    pokeChrome()
                                } else {
                                    val isDouble = now - lastTapMs < 300 && Math.abs(x - lastTapX) < 80
                                    if (isDouble) {
                                        lastTapMs = 0
                                        if (x > half) seekRel(10f) else seekRel(-10f)
                                        pokeChrome()
                                    } else {
                                        lastTapMs = now
                                        lastTapX = x
                                        main.postDelayed({
                                            if (lastTapMs != 0L && System.currentTimeMillis() - lastTapMs >= 290) {
                                                lastTapMs = 0
                                                chromeVisible = !chromeVisible
                                                pokeTick += 1
                                            }
                                        }, 300)
                                    }
                                }
                            }
                        }
                        false
                    }
                    loadMirror(this, playerUrl(title, epSeason, epNumber, mal, ani, mirror, resumeAt, anipmLang, cinesrcQ), mirror)
                    web = this
                }
            },
        )
        }

        // Top bar (design pack TV HUD): the title card carries the show,
        // Sx:Ex "episode name", real cert + CC chips - and the right edge
        // carries the playback state + clock. Quality chips stay OFF unless
        // a provider reports them (house rule: never claim 4K/Atmos).
        if (chromeVisible) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color(0xCC0B111E), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                title.name.uppercase(),
                color = Accent,
                fontWeight = FontWeight.Black,
                fontSize = if (big) 15.sp else 12.sp,
                letterSpacing = 1.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val epTitle = epsList.firstOrNull { it.n == epNumber }?.name ?: ""
            Text(
                when {
                    mirror == "hls" -> "HLS SHELL TEST"
                    title.type == "movie" -> "MOVIE"
                    epTitle.isNotBlank() && epTitle != "Episode " + epNumber ->
                        "S" + epSeason + ":E" + epNumber + "  \u201C" + epTitle + "\u201D"
                    else -> "S" + epSeason + ":E" + epNumber
                },
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = if (big) 18.sp else 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (cert.isNotBlank() || subs != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (cert.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .background(Color(0x66000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                cert,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                    if (subs != null) {
                        Box(
                            modifier = Modifier
                                .background(Color(0x66000000), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "CC",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            if (!playing) {
                Box(
                    modifier = Modifier
                        .background(Color(0xCC0B111E), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "PAUSED",
                        color = Amber,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .background(Color(0xCC0B111E), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    clock,
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = if (big) 13.sp else 11.sp,
                    letterSpacing = 1.sp,
                )
            }
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
            val capMod = when (subPos) {
                "top" -> Modifier.align(Alignment.TopCenter).padding(top = 70.dp)
                "center" -> Modifier.align(Alignment.Center)
                else -> Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad)
            }
            val capColor = when (subStyle.color) {
                1 -> Color(0xFFFFE066)
                2 -> Color(0xFF7FE7F5)
                else -> Color.White
            }
            Text(
                capCue.text,
                color = capColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                fontSize = (if (big) subStyle.size + 4 else subStyle.size).sp,
                modifier = capMod
                    .padding(horizontal = 28.dp)
                    .background(Color(0xFF000000).copy(alpha = subStyle.bg / 100f), RoundedCornerShape(8.dp))
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
                        .focusRingCircle()
                        .glow(CircleShape)
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
                .background(Brush.verticalGradient(listOf(Color(0x000B111E), Color(0xE60B111E))))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Skip pill - visible ONLY inside intro/recap/credits. TV: its own
            // navigation layer, so the remote reaches it like Netflix's Next
            // Episode pill.
            if (hit != null) {
                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    val label = when {
                        hit.kind == "credits" -> hit.label
                        // Design pack: the pill counts DOWN the range
                        // ("Skip Intro  2:04"), not the end timestamp.
                        hit.end != null -> hit.label + "  " + fmt((hit.end - cur).coerceAtLeast(0f))
                        else -> hit.label
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .focusRequester(skipFR)
                            .focusRing(RoundedCornerShape(50))
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
                    fmt(if (scrub >= 0) scrub else cur),
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 16.sp else 13.sp,
                )
                Text(
                    "  /  " + fmt(dur),
                    color = Dim,
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
                        inactiveTrackColor = Color(0xFF243247),
                    ),
                )
                Text(
                    "-" + fmt(if (dur > 0) (dur - (if (scrub >= 0) scrub else cur)).coerceAtLeast(0f) else 0f),
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 15.sp else 12.sp,
                )
                Text(
                    "  (" + fmt(dur) + ")",
                    color = Dim,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (big) 12.sp else 10.sp,
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
                        .focusRingCircle()
                        .glow(CircleShape, elevation = 12.dp)
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
                // Next Episode (design pack bar): series/anime only - and
                // only when the next episode is real AND aired. Unaired
                // stays a Coming Soon schedule row, never a dead target.
                if (title.type != "movie") {
                    val nextEp = epsList.firstOrNull { it.n == epNumber + 1 }
                    if (epsList.isEmpty() || (nextEp != null && nextEp.aired)) {
                        BarPill(if (big) "Next Episode" else "Next Ep", big, icon = Icons.Filled.SkipNext) {
                            epNumber += 1
                            save(0f, dur)
                            cur = 0f
                            playing = true
                            load()
                        }
                    }
                }
                // Mute + rate exist only on mirrors we can actually drive
                // (website: no dead buttons - VidRift/VidZen expose neither
                // a volume nor a rate command). The HLS slot is fully ours,
                // and VidBolt speaks volume + speed through its own
                // message contract (0.41/0.42).
                if (mirror == "cinesrc" || mirror == "anipm" || mirror == "hls" || mirror == "vidbolt") {
                Box(
                    modifier = Modifier
                        .size(if (big) 56.dp else 48.dp)
                        .focusRingCircle()
                        .background(Pill, CircleShape)
                        .clickable {
                            muted = !muted
                            val hp = hlsPlayer
                            if (mirror == "hls" && hp != null) hp.volume = if (muted) 0f else 1f
                            cmd("window.__sb && window.__sb.mute(" + muted + ")")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
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
                    val hp = hlsPlayer
                    if (mirror == "hls" && hp != null) hp.playbackParameters = PlaybackParameters(RATES[rateIdx])
                    cmd("window.__sb && window.__sb.rate(" + RATES[rateIdx] + ")")
                }
                }
                // CineSrc quality changer (website QLEVELS cycle):
                // Auto -> 1080p -> 720p -> 480p. Each pick rides the embed
                // URL, so the stream reopens at the current position.
                if (mirror == "cinesrc") {
                    BarPill(if (cinesrcQ.isEmpty()) "Auto" else cinesrcQ + "p", big) {
                        cinesrcQ = CINE_Q[(CINE_Q.indexOf(cinesrcQ).let { if (it < 0) 0 else it } + 1) % CINE_Q.size]
                        val at = cur
                        load(startAt = at)
                    }
                }
                // AniPM quality ladder (only real, player-reported values -
                // never invent 4K: house rule).
                if (anipmQs.isNotEmpty()) {
                    BarPill(anipmQ?.let { it.toString() + "p" } ?: "Q", big) {
                        val i = anipmQ?.let { q -> anipmQs.indexOfFirst { it == q } } ?: -1
                        val next = anipmQs[(i + 1) % anipmQs.size]
                        anipmQ = next
                        cmd("window.__sb && window.__sb.q(" + next + ")")
                    }
                }
                // Sub/Dub pill (anime on AniPM - setLanguage live). DBZ
                // default dub is decided at URL build; this switches.
                if (title.type == "anime" && mirror == "anipm") {
                    BarPill(if (anipmLang == "dub") "Dub" else "Sub", big) {
                        val next = if (anipmLang == "dub") "sub" else "dub"
                        anipmLang = next
                        cmd("window.__sb && window.__sb.lang('" + next + "')")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // Design pack bar: one Audio & Subtitles entry - the merged
                // panel carries the SERVER picker as its first column (the
                // separate server sheet is gone: no redundant buttons).
                BarPill("Audio & Subtitles", big, icon = Icons.Filled.Subtitles) { showSubs = true }
                // Movies have no episodes - the switcher is series/anime only.
                if (title.type != "movie") {
                    BarPill("Episodes", big, icon = Icons.Filled.Menu) { showEps = true }
                }
                // No Home pill: Back already goes home (Netflix-style) - one
                // less target on the bar and one less button to hunt on TV.
            }
            // 10-foot legend (design pack): the remote map as key chips, so
            // TV users always know what OK / L-R / U-D / BACK do here.
            if (big) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    KeyChip("OK")
                    Text("Play/Pause", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(10.dp))
                    KeyChip("\u25C0  \u25B6")
                    Text("Scrub 10s", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(10.dp))
                    KeyChip("\u25B2  \u25BC")
                    Text("Navigate", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(10.dp))
                    KeyChip("BACK")
                    Text("Exit", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            }
        }
        }

        // Episodes sheet (design pack episode-picker): series line, season
        // stepper + Auto-play Next, range tabs and the real episode cards.
        // Bounded to the screen so the D-pad always lands on something
        // VISIBLE (0.38 fix: the old bottom-anchored Load pill sat below
        // the fold and focus escaped to the player behind the sheet).
        if (showEps) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .pointerInput(Unit) { detectTapGestures { showEps = false } },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(if (big) 24.dp else 12.dp)
                        .widthIn(max = if (big) 1360.dp else 560.dp)
                        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * (if (big) 0.94f else 0.96f))
                        .background(Card, RoundedCornerShape(20.dp))
                        .padding(if (big) 20.dp else 16.dp)
                        .verticalScroll(rememberScrollState())
                        .focusGroup(),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(if (big) 46.dp else 40.dp)
                                .focusRing(RoundedCornerShape(12.dp))
                                .background(Color(0x2600F0B5), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Episodes",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = if (big) 21.sp else 18.sp,
                            )
                            Text(
                                title.name +
                                    (if (title.type != "movie") "  \u2022  Season " + epSeason +
                                        (if (epsList.isNotEmpty()) "  \u2022  " + epsList.size + " Total Episodes" else "") else ""),
                                color = Dim,
                                fontFamily = BodyFont,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BarPill("Close", big) { showEps = false }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    if (title.type != "movie") {
                        // Controls row (design pack): the season stepper and
                        // the Auto-play Next toggle side by side.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BarPill("S  -", big) { epSeason = (epSeason - 1).coerceAtLeast(1) }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "Season " + epSeason,
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = if (big) 17.sp else 15.sp,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            BarPill("S  +", big) { epSeason += 1 }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                "Auto-play Next",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            BarPill(if (autoSkip) "ON" else "OFF", big) {
                                autoSkip = !autoSkip
                                Store.setAutoSkip(ctx, autoSkip)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        // Quick-select (design pack): range chips ("Episodes
                        // 1-10", "11-20"...) drive a 10-card window - the
                        // sheet stays smooth on 1,400-episode seasons.
                        val total = epsList.size
                        val rangeSize = 10
                        var rangeStart by remember(epSeason) { mutableIntStateOf(-1) }
                        val winStart = when {
                            total <= rangeSize -> 0
                            rangeStart >= 0 -> rangeStart.coerceIn(0, (total - 1).coerceAtLeast(0))
                            else -> {
                                val idx = epsList.indexOfFirst { it.n == epNumber }.let { if (it < 0) 0 else it }
                                (idx / rangeSize) * rangeSize
                            }
                        }
                        val win = if (total <= rangeSize) epsList else {
                            epsList.subList(winStart, minOf(winStart + rangeSize, total))
                        }
                        // Saved progress for THIS title: the one slot the
                        // Store keeps - cards read resume/off it honestly.
                        val savedProg = Store.progressList(ctx).firstOrNull {
                            it.type == title.type && it.id == title.id
                        }
                        if (total > rangeSize) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val rangeStarts = (0 until total step rangeSize).toList()
                                items(rangeStarts.size) { i ->
                                    val s = rangeStarts[i]
                                    val e = minOf(s + rangeSize, total)
                                    val on = s == winStart
                                    Box(
                                        modifier = Modifier
                                            .focusRing(RoundedCornerShape(50), pop = false)
                                            .background(if (on) Accent else Pill, RoundedCornerShape(50))
                                            .clickable { rangeStart = s }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            "Episodes " + epsList[s].n + "-" + epsList[e - 1].n,
                                            color = if (on) Ink else Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "SELECT AN EPISODE TO RESUME OR JUMP DIRECTLY",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 12.sp else 11.sp,
                                letterSpacing = 0.5.sp,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                if (total > 0) "Showing " + (win.firstOrNull()?.n ?: 0) + "-" + (win.lastOrNull()?.n ?: 0) + " of " + total
                                else if (epsBusy) "Loading..." else "No episode data",
                                color = Dim,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = if (big) 360.dp else 300.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            if (epsBusy && epsList.isEmpty()) {
                                CircularProgressIndicator(
                                    color = Accent,
                                    modifier = Modifier.padding(16.dp).size(if (big) 36.dp else 26.dp),
                                )
                            }
                            // Entry focus lands on the CURRENT episode's
                            // card (or the first in the window) so the D-pad
                            // starts INSIDE the cards - never on something
                            // below the fold (0.38 focus-escape fix).
                            val cols = if (big) 5 else 2
                            val entryN = if (win.any { it.n == epNumber }) epNumber
                                else win.firstOrNull()?.n ?: -1
                            win.chunked(cols).forEach { rowEps ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    rowEps.forEach { ep ->
                                        val resumeEp = savedProg?.takeIf {
                                            it.season == epSeason && it.episode == ep.n && it.cur > 5f &&
                                                !(it.dur > 0 && it.cur >= it.dur * 0.95f)
                                        }
                                        QuickEpCard(
                                            ep = ep,
                                            big = big,
                                            selected = ep.n == epNumber,
                                            upNext = ep.n == epNumber + 1 && ep.aired,
                                            resume = resumeEp,
                                            modifier = Modifier
                                                .weight(1f)
                                                .then(if (ep.n == entryN) Modifier.focusRequester(epsFirstFR) else Modifier),
                                        ) {
                                            val at = resumeEp?.cur ?: 0f
                                            epNumber = ep.n
                                            showEps = false
                                            save(0f, dur)
                                            cur = at
                                            playing = true
                                            load(startAt = at)
                                        }
                                    }
                                    if (rowEps.size < cols) {
                                        repeat(cols - rowEps.size) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    // TV legend (design pack episode-picker footer): OK on a
                    // card plays it, BACK resumes what is on screen now.
                    if (big) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KeyChip("OK")
                            Text("Play Episode", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            KeyChip("\u25C0  \u25B6")
                            Text("Navigate Episodes", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            KeyChip("BACK")
                            Text("Resume Current", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Audio & Subtitles panel (design pack TV overlay): SERVERS, AUDIO,
        // SUBTITLES and STYLING & SYNC under one glass header - the server
        // picker lives HERE now (0.36 request), no separate sheet.
        if (showSubs) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .pointerInput(Unit) { detectTapGestures { showSubs = false } },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .padding(if (big) 24.dp else 12.dp)
                        .widthIn(max = if (big) 1320.dp else 560.dp)
                        // Bounded height so the panel scrolls INSIDE the
                        // screen - an unbounded scroll column let the
                        // styling blocks run off the bottom edge (0.37 fix).
                        .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * (if (big) 0.94f else 0.96f))
                        .background(Card, RoundedCornerShape(20.dp))
                        .padding(if (big) 22.dp else 16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Audio & Subtitles",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = if (big) 22.sp else 18.sp,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        // Live status dots (design pack panel header): the
                        // active server + the active caption language.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(7.dp).background(Accent, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(MIRROR_NAMES[mirror] ?: mirror, color = Soft, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Text("  /  ", color = Dim, fontSize = 11.sp)
                            Box(modifier = Modifier.size(7.dp).background(Accent, CircleShape))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(subs?.lang ?: "Subs off", color = Soft, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        BarPill("Close", big) { showSubs = false }
                    }
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        // COLUMN 1: SERVERS (website server-list rules -
                        // Doraemon never lists VidBolt, unmapped anime never
                        // lists AniPM). Switching keeps the position.
                        Column(modifier = Modifier.weight(1f)) {
                            PanelColTitle("SERVERS")
                            Spacer(modifier = Modifier.height(6.dp))
                            serverList.forEachIndexed { i, id ->
                                OptRow(
                                    label = MIRROR_NAMES[id] ?: id,
                                    detail = if (id == mirror) "Playing now" else "Keeps your position",
                                    chip = if (id == mirror) "ACTIVE" else "",
                                    active = id == mirror,
                                    big = big,
                                    modifier = if (i == 0) Modifier.focusRequester(serverFirstFR) else Modifier,
                                ) {
                                    if (id != mirror) {
                                        val at = cur
                                        mirror = id
                                        playing = true
                                        load(startAt = at)
                                    }
                                    showSubs = false
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                        // COLUMN 2: AUDIO (honest only): AniPM drives its
                        // dub|sub route live; every other embed owns its own
                        // audio - we say so instead of shipping dead buttons.
                        Column(modifier = Modifier.weight(1f)) {
                            PanelColTitle("AUDIO TRACKS")
                            Spacer(modifier = Modifier.height(6.dp))
                            if (mirror == "anipm") {
                                listOf("dub" to "English (Dub)", "sub" to "Japanese (Sub)").forEach { (code, name) ->
                                    val on = anipmLang == code
                                    val pinned = (if (title.name.contains("dragon ball", true)) "dub" else "sub") == code
                                    OptRow(
                                        label = name,
                                        detail = if (code == "dub") "English dub track" else "Japanese audio track",
                                        chip = if (on) "ACTIVE" else if (pinned) "DEFAULT" else "",
                                        active = on,
                                        big = big,
                                    ) {
                                        anipmLang = code
                                        cmd("window.__sb && window.__sb.lang('" + code + "')")
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            } else if (mirror == "vidbolt") {
                                // VidBolt's REAL audio tracks (0.41): the
                                // labels its resolved stream reports. Until
                                // the stream lands there is nothing to pick,
                                // so we say so instead of dead buttons.
                                if (vbAudios.isEmpty()) {
                                    OptRow(
                                        label = "Original Audio",
                                        detail = "VidBolt tracks appear once the stream loads",
                                        chip = "DEFAULT",
                                        active = true,
                                        big = big,
                                    )
                                } else {
                                    vbAudios.forEach { (lbl, value) ->
                                        val on = vbAudioSel == value
                                        OptRow(
                                            label = lbl,
                                            detail = "VidBolt audio track",
                                            chip = if (on) "ACTIVE" else "",
                                            active = on,
                                            big = big,
                                        ) {
                                            vbAudioSel = value
                                            cmd("window.__sb && window.__sb.vbaudio('" + value.replace("'", "") + "')")
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }
                                }
                            } else {
                                OptRow(
                                    label = "Original Audio",
                                    detail = "The provider player owns audio",
                                    chip = "DEFAULT",
                                    active = true,
                                    big = big,
                                )
                            }
                        }
                        // COLUMN 3: SUBTITLES - the language the chain
                        // searches in, Off/active, then the real results.
                        Column(modifier = Modifier.weight(1.15f)) {
                            PanelColTitle("SUBTITLES")
                            Spacer(modifier = Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(SUBLANGS.size) { i ->
                                    val (code, label) = SUBLANGS[i]
                                    val on = code == wyzLang
                                    Box(
                                        modifier = Modifier
                                            .focusRing(RoundedCornerShape(50), pop = false)
                                            .background(if (on) Accent else Pill, RoundedCornerShape(50))
                                            .clickable { wyzLang = code }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            label,
                                            color = if (on) Ink else Color.White,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (subMsg.isNotEmpty()) {
                                Text(subMsg, color = Amber, fontFamily = BodyFont, fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            val loadedSub = subs
                            OptRow(
                                label = "Off",
                                detail = "No captions",
                                chip = if (loadedSub == null) "ACTIVE" else "",
                                active = loadedSub == null,
                                big = big,
                            ) {
                                subs = null
                                subMsg = ""
                                // Forgetting the pick keeps it gone after reopen.
                                Store.saveSubPick(ctx, title.type, title.id, epSeason, epNumber, null)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            if (loadedSub != null) {
                                OptRow(
                                    label = loadedSub.name,
                                    detail = loadedSub.lang + "  -  " + loadedSub.cues.size + " lines",
                                    chip = "ACTIVE",
                                    active = true,
                                    big = big,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            if (wyzBusy) {
                                CircularProgressIndicator(
                                    color = Accent,
                                    modifier = Modifier.padding(10.dp).size(24.dp),
                                )
                            } else {
                                wyzItems.take(10).forEach { item ->
                                    OptRow(
                                        label = item.release,
                                        detail = listOf(
                                            item.display,
                                            item.origin,
                                            if (item.downloads > 0) item.downloads.toString() else "",
                                            if (item.cc) "CC" else "",
                                        ).filter { it.isNotEmpty() }.joinToString("  -  "),
                                        chip = "",
                                        active = false,
                                        big = big,
                                    ) { loadFrom(item) }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                                if (wyzItems.isEmpty()) {
                                    OptRow(
                                        label = "Try backups (TSDB + BetaSeries)",
                                        detail = "Same title, different releases",
                                        chip = "",
                                        active = false,
                                        big = big,
                                    ) { bsTick += 1 }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                            OptRow(
                                label = "Load .srt / .vtt file",
                                detail = "Your own subtitle file",
                                chip = "",
                                active = false,
                                big = big,
                            ) {
                                runCatching { pickSub.launch(arrayOf("*/*")) }
                            }
                            // AniPM's own tracks (player-side) - real labels
                            // only, website setSubtitles parity.
                            if (anipmSubLabels.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                PanelColTitle("PLAYER TRACKS")
                                Spacer(modifier = Modifier.height(6.dp))
                                OptRow(
                                    label = "Off",
                                    detail = "Player captions off",
                                    chip = if (anipmSubIdx < 0) "ACTIVE" else "",
                                    active = anipmSubIdx < 0,
                                    big = big,
                                ) {
                                    anipmSubIdx = -1
                                    cmd("window.__sb && window.__sb.subs(false)")
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                anipmSubLabels.forEachIndexed { i, lbl ->
                                    OptRow(
                                        label = lbl,
                                        detail = "Player track",
                                        chip = if (anipmSubIdx == i) "ACTIVE" else "",
                                        active = anipmSubIdx == i,
                                        big = big,
                                    ) {
                                        anipmSubIdx = i
                                        cmd("window.__sb && window.__sb.subs(true, '" + lbl.replace("'", "") + "')")
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                            // VidBolt's own subtitle tracks (0.41/0.43): real
                            // labels + command values from the stream - Off
                            // first, then each provider track.
                            if (mirror == "vidbolt" && vbSubs.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                PanelColTitle("VIDBOLT TRACKS")
                                Spacer(modifier = Modifier.height(6.dp))
                                OptRow(
                                    label = "Off",
                                    detail = "Provider captions off",
                                    chip = if (vbSubSel.isEmpty() || vbSubSel == "-1") "ACTIVE" else "",
                                    active = vbSubSel.isEmpty() || vbSubSel == "-1",
                                    big = big,
                                ) {
                                    vbSubSel = "-1"
                                    cmd("window.__sb && window.__sb.vbsub(-1)")
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                vbSubs.forEach { (lbl, value) ->
                                    val on = vbSubSel == value
                                    OptRow(
                                        label = lbl,
                                        detail = "Provider track",
                                        chip = if (on) "ACTIVE" else "",
                                        active = on,
                                        big = big,
                                    ) {
                                        vbSubSel = value
                                        cmd("window.__sb && window.__sb.vbsub('" + value.replace("'", "") + "')")
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            }
                        }
                        // COLUMN 4: STYLING & SYNC TUNING - the existing
                        // glass island rides as the fourth column.
                        Column(modifier = Modifier.weight(1.25f)) {
                    // STYLING & SYNC TUNING (design pack blocks): every
                    // control is a label row + its own chip row so nothing
                    // wraps mid-control on the 10-foot UI (0.37 fix - the
                    // old one-line steppers squeezed the column to jelly).
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Pill, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                    ) {
                        Text(
                            "STYLING & SYNC TUNING",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TEXT SIZE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                subStyle.size.toString() + "sp",
                                color = Accent,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(14 to "Normal", 18 to "Large", 24 to "Huge").forEach { (v, name) ->
                                StyleChip(name, subStyle.size == v, big) {
                                    subStyle = subStyle.copy(size = v)
                                    Store.saveSubStyle(ctx, subStyle)
                                }
                            }
                            StyleChip("A-", on = false, big = big) {
                                subStyle = subStyle.copy(size = (subStyle.size - 2).coerceIn(12, 26))
                                Store.saveSubStyle(ctx, subStyle)
                            }
                            StyleChip("A+", on = false, big = big) {
                                subStyle = subStyle.copy(size = (subStyle.size + 2).coerceIn(12, 26))
                                Store.saveSubStyle(ctx, subStyle)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TIMING SYNC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                subOffset.let { (if (it >= 0) "+" else "") + String.format("%.1f", it) + "s" },
                                color = Accent,
                                fontWeight = FontWeight.Black,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            StyleChip("-0.5s", on = false, big = big) {
                                val o = Math.round((subOffset - 0.5f) * 10) / 10f
                                subOffset = o.coerceIn(-30f, 30f)
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                            StyleChip("<", on = false, big = big) {
                                val o = Math.round((subOffset - 0.5f) * 10) / 10f
                                subOffset = o.coerceIn(-30f, 30f)
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                            StyleChip(">", on = false, big = big) {
                                val o = Math.round((subOffset + 0.5f) * 10) / 10f
                                subOffset = o.coerceIn(-30f, 30f)
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                            StyleChip("+0.5s", on = false, big = big) {
                                val o = Math.round((subOffset + 0.5f) * 10) / 10f
                                subOffset = o.coerceIn(-30f, 30f)
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("POSITION", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("top" to "Top", "center" to "Center", "bottom" to "Bottom").forEach { (v, name) ->
                                StyleChip(name, subPos == v, big) {
                                    subPos = v
                                    Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TEXT COLOR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "White", 1 to "Yellow", 2 to "Cyan").forEach { (idx, name) ->
                                StyleChip(name, subStyle.color == idx, big) {
                                    subStyle = subStyle.copy(color = idx)
                                    Store.saveSubStyle(ctx, subStyle)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BACKGROUND PLATE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(0 to "None", 60 to "Semi (50%)", 100 to "Solid (100%)").forEach { (v, name) ->
                                StyleChip(name, subStyle.bg == v, big) {
                                    subStyle = subStyle.copy(bg = v)
                                    Store.saveSubStyle(ctx, subStyle)
                                }
                            }
                        }
                    }
                        }
                    }
                    // Live preview - the caption exactly as it rides the
                    // player (design pack's preview line).
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(7.dp).background(Accent, CircleShape))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "LIVE SUBTITLE PREVIEW",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF000000).copy(alpha = subStyle.bg / 100f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Sample: StreamBox captions ride in this exact style.",
                            color = when (subStyle.color) {
                                1 -> Color(0xFFFFE066)
                                2 -> Color(0xFF7FE7F5)
                                else -> Color.White
                            },
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            fontSize = subStyle.size.sp,
                        )
                    }
                    // TV 10-foot legend (design pack panel footer).
                    if (big) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            KeyChip("OK")
                            Text("Select", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            KeyChip("BACK")
                            Text("Return to Video", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            KeyChip("\u25C0  \u25B6")
                            Text("Switch Column", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            KeyChip("\u25B2  \u25BC")
                            Text("Navigate Options", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                "ANDROID TV 10-FOOT HUD",
                                color = Color(0xFF3B4A63),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 1.5.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Compact chip for the Styling & Sync blocks (design pack): one line,
    never wraps, ring hugs the pill. */
@Composable
private fun StyleChip(label: String, on: Boolean, big: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .focusRing(RoundedCornerShape(50), pop = false)
            .background(if (on) Accent else Card, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = if (big) 12.dp else 9.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            color = if (on) Ink else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

/** Column title for the Audio & Subtitles panel (design pack columns). */
@Composable
private fun PanelColTitle(label: String) {
    Text(
        label,
        color = Dim,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.5.sp,
    )
}

/** Radio-style option row for the Audio & Subtitles panel (design pack):
    check circle + label + detail + a right chip (ACTIVE / DEFAULT). A null
    onClick renders a display-only row - never a dead button. */
@Composable
private fun OptRow(
    label: String,
    detail: String,
    chip: String,
    active: Boolean,
    big: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.focusRing(RoundedCornerShape(12.dp), pop = false) else Modifier)
            .background(if (active) Color(0x1A00F0B5) else Pill, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(if (active) Accent else Color(0x33FFFFFF), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (big) 13.sp else 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    color = Dim,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (chip.isNotBlank()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(if (active) Color(0x3300F0B5) else Color(0x1AFFFFFF), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    chip,
                    color = if (active) Accent else Dim,
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                )
            }
        }
    }
}

@Composable
private fun CircleBtn(label: String, big: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(if (big) 68.dp else 58.dp)
            .focusRingCircle()
            .background(Color(0x990B111E), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Black, fontSize = if (big) 16.sp else 13.sp)
    }
}

@Composable
private fun BarPill(
    label: String,
    big: Boolean,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(if (big) 52.dp else 44.dp)
            .focusRing(RoundedCornerShape(50))
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

/** Tiny key cap for the TV remote legends (design pack 10-foot HUD). */
@Composable
fun KeyChip(label: String) {
    Box(
        modifier = Modifier
            .background(Glass, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
        )
    }
}

/** Episode card for the Episodes sheet (design pack): real still + NOW
    PLAYING / RESUME / UNWATCHED / COMING SOON state, runtime, synopsis and
    TMDB rating - nothing invented, unaired stays a schedule row. */
@Composable
private fun QuickEpCard(
    ep: Ep,
    big: Boolean,
    selected: Boolean,
    upNext: Boolean,
    resume: Progress?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // "NEW" is reserved for the episode of THIS WEEK - the only thing that
    // may ever carry the marker (0.38 rule).
    val weekAgo = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000))
    }
    val isNew = ep.aired && ep.air.isNotBlank() && ep.air >= weekAgo
    Column(
        modifier = modifier
            .focusRing(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x1A00F0B5) else Pill, RoundedCornerShape(14.dp))
            // Unaired episodes are schedule rows, not play targets.
            .then(if (ep.aired) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Card),
        ) {
            if (ep.still != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(ep.still).crossfade(100).build(),
                    contentDescription = ep.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("E" + ep.n, color = Dim, fontWeight = FontWeight.Black, fontSize = if (big) 20.sp else 16.sp)
                }
            }
            // Status chip (design pack card caps).
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xE60B111E), RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    when {
                        !ep.aired -> "COMING SOON"
                        selected -> "NOW PLAYING"
                        resume != null -> "RESUME"
                        upNext -> "UP NEXT"
                        isNew -> "NEW"
                        else -> "UNWATCHED"
                    },
                    color = when {
                        !ep.aired -> Amber
                        selected || resume != null -> Accent
                        upNext -> Cyan
                        isNew -> Accent
                        else -> Soft
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp,
                )
            }
            if (ep.runtime > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0xE60B111E), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        ep.runtime.toString() + "m",
                        color = Soft,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                    )
                }
            }
            if (resume != null && resume.dur > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(fraction = (resume.cur / resume.dur).coerceIn(0.05f, 1f))
                        .height(3.dp)
                        .background(Accent),
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "EPISODE " + ep.n.toString().padStart(2, '0'),
            color = Dim,
            fontWeight = FontWeight.Bold,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            if (ep.name.isNotBlank() && ep.name != "Episode " + ep.n) ep.name else "Episode " + ep.n,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = if (big) 13.sp else 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (ep.overview.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                ep.overview,
                color = Dim,
                fontFamily = BodyFont,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ep.vote > 0) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(11.dp))
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    String.format("%.1f", ep.vote),
                    color = Soft,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
            if (resume != null && resume.dur > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    fmt((resume.dur - resume.cur).coerceAtLeast(0f)) + " left",
                    color = Accent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                )
            }
        }
    }
}
