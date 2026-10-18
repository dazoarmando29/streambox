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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
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
   ANIME_BAD_CATALOG keeps Doraemon away from VidBolt (it serves FMA:B for
   the right id). VidRift is TMDB-direct (works for anime when the AniPM
   mapping is missing). VidZen has no anime routes. Mid-session switches
   never change the next title's primary. */
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

private fun defaultMirror(title: Title, mal: Int?, ani: Int?): String = when (title.type) {
    "anime" -> if (mal != null || ani != null) "anipm" else "vidrift"
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
): String {
    val m = mirror.ifEmpty { defaultMirror(title, mal, ani) }
    val ep = episode.coerceAtLeast(1)
    val st = startAt.toInt().coerceAtLeast(0)
    val stq = if (st > 0) "&startAt=" + st else ""
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
            "https://cinesrc.st/embed/movie/" + title.id + "?controls=false"
        } else {
            "https://cinesrc.st/embed/tv/" + title.id + "?s=" + season + "&e=" + episode + "&controls=false"
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
      if (m.type === "durationchange") { window.__sbDur = m.duration||0; send("loadedmetadata", {duration: m.duration||0}); return; }
      if (m.type === "timeupdate") {
        window.__sbBoltOn = true;
        if (window.__sbBoltQ != null) {
          var bq = window.__sbBoltQ; window.__sbBoltQ = null;
          post({ type: 'seek', time: Math.floor(bq) });
        }
        send("timeupdate", {currentTime: m.time||0, duration: window.__sbDur||0});
        return;
      }
      if (m.type === "ready") { send("ready", {}); return; }
      if (m.type === "play" || m.type === "playing") { window.__sbBoltOn = true; send("play", {}); return; }
      if (m.type === "pause" || m.type === "paused") { send("pause", {}); return; }
      if (m.type === "ended") { send("ended", {}); return; }
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
      try{window.postMessage({type:'cinesrc:command',command:'setMuted',args:[!!m]},'*');}catch(e){}
    },
    rate: function(r){
      var v=document.querySelector('video'); if(v) v.playbackRate=r;
      if (ANIPM) anipmSend('setPlaybackRate', {rate: r});
      try{window.postMessage({type:'cinesrc:command',command:'setPlaybackRate',args:[r]},'*');}catch(e){}
    },
    q: function(n){ if(ANIPM) anipmSend('setQuality', {height: n}); },
    lang: function(l){ if(ANIPM) anipmSend('setLanguage', {lang: l}); },
    subs: function(on, label){ if(ANIPM) anipmSend('setSubtitles', on ? {enabled: true, label: label||''} : {enabled: false}); },
    autoNext: function(on){ if(ANIPM) anipmSend('setAutoNext', {on: !!on}); },
    autoSkip: function(on){ if(ANIPM) anipmSend('setAutoSkip', {on: !!on}); }
  };
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
    var mirror by remember {
        mutableStateOf(if (AppEnv.hlsTest) "hls" else defaultMirror(title, mal, ani))
    }
    var showServers by remember { mutableStateOf(false) }
    val serverList = remember(title.id, title.type, mal, ani) {
        mirrorsFor(title, mal, ani) + if (AppEnv.hlsTest) listOf("hls") else listOf()
    }
    // Auto-recovery: bad route / website page / dead embed gets ONE hop to
    // the next allowed mirror (never loops - one hop per title).
    var gotMedia by remember { mutableStateOf(false) }
    var hopped by remember { mutableStateOf(false) }
    // One deferred seek for mirrors without URL startAt (provider switches).
    var pendingSeek by remember { mutableStateOf(-1f) }
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
    val subsFirstFR = remember { FocusRequester() }
    val serverFirstFR = remember { FocusRequester() }

    fun save(c: Float, d: Float) {
        try {
            Store.saveProgress(
                ctx,
                Progress(title.type, title.id, title.name, title.poster, epSeason, epNumber, c, d, System.currentTimeMillis(), mal, ani),
            )
        } catch (e: Exception) {
        }
    }

    fun load(startAt: Float = 0f) {
        chromeVisible = true
        // Mirrors whose URL carries startAt (vidzen/vidbolt/anipm) keep the
        // position themselves; the rest get one seek when the player reports
        // ready - switching providers never loses your place (task rule).
        val urlCarriesStart = mirror == "vidzen" || mirror == "vidbolt" || mirror == "anipm" ||
            mirror == "vidcore" || mirror.startsWith("vidsrc") || mirror == "hls"
        pendingSeek = if (startAt > 0f && !urlCarriesStart) startAt else -1f
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
                loadMirror(w, playerUrl(title, epSeason, epNumber, mal, ani, mirror, startAt, anipmLang), mirror)
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
        mirror = fb
        cur = 0f
        playing = true
        load()
    }

    // Startup watchdog: no media in 30s (bad route, website page, dead
    // embed, weak-box stall) -> hop once so the TV is never stuck.
    LaunchedEffect(mirror, epSeason, epNumber) {
        gotMedia = false
        delay(30000)
        if (!gotMedia) hopMirror()
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
    LaunchedEffect(chromeVisible, playing, showEps, showSubs, showServers, pokeTick) {
        if (chromeVisible && playing && !showEps && !showSubs && !showServers) {
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
    // season episodes, fetched when the sheet opens.
    var epsList by remember { mutableStateOf(listOf<Ep>()) }
    var epsBusy by remember { mutableStateOf(false) }
    LaunchedEffect(showEps, epSeason, title.id) {
        if (showEps && title.type != "movie") {
            epsBusy = true
            epsList = withContext(Dispatchers.IO) {
                runCatching { Api.season(title.id, epSeason) }.getOrDefault(listOf())
            }
            epsBusy = false
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

    // Per-episode memory: restore the last URL-backed subtitle pick when the
    // episode opens (website behavior). A manual local file can't be re-read
    // after a restart, so only chain picks persist.
    LaunchedEffect(title.id, epSeason, epNumber) {
        val pick = withContext(Dispatchers.IO) {
            runCatching {
                Store.subPick(ctx, title.type, title.id, epSeason, epNumber)
            }.getOrNull()
        } ?: return@LaunchedEffect
        val text = withContext(Dispatchers.IO) {
            runCatching { Wyz.fetchFile(pick.url) }.getOrNull()
        }
        if (text != null && subs == null) {
            setSub(pick.name, pick.lang, Subs.parse(text))
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
            showServers -> showServers = false
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
                if (!big || showEps || showSubs || showServers || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
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
        LaunchedEffect(showEps, showSubs, showServers) {
            if (big && !showEps && !showSubs && !showServers) {
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
        LaunchedEffect(showEps, showSubs, showServers) {
            if (big) {
                runCatching {
                    when {
                        showEps -> epsFirstFR.requestFocus()
                        showSubs -> subsFirstFR.requestFocus()
                        showServers -> serverFirstFR.requestFocus()
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
                        load()
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
                                // Watchdog counts REAL playback only - an idle
                                // preview video or bare metadata must not
                                // defeat the auto-hop (Beerus lesson).
                                if (t == "play" || t == "timeupdate" || t == "ended") {
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
                    loadMirror(this, playerUrl(title, epSeason, epNumber, mal, ani, mirror, 0f, anipmLang), mirror)
                    web = this
                }
            },
        )
        }

        // Top-left title card (website style) - fades with the chrome.
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
            Text(
                when {
                    mirror == "hls" -> "HLS SHELL TEST"
                    title.type == "movie" -> "MOVIE"
                    else -> "S" + epSeason + " E" + epNumber
                },
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
                        .focusRing(CircleShape)
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
                        .focusRing(CircleShape)
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
                // Mute + rate exist only on mirrors we can actually drive
                // (website: no dead buttons - VidRift/VidZen/VidBolt expose
                // neither a volume nor a rate command). The HLS slot is
                // fully ours, so it gets both.
                if (mirror == "cinesrc" || mirror == "anipm" || mirror == "hls") {
                Box(
                    modifier = Modifier
                        .size(if (big) 56.dp else 48.dp)
                        .focusRing(CircleShape)
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
                // Live server dot + name (design pack: "● AniPM").
                BarPill("\u25CF  " + (MIRROR_NAMES[mirror] ?: mirror), big) { showServers = true }
                BarPill("Subs", big) { showSubs = true }
                BarPill("Episodes", big, icon = Icons.Filled.Menu) { showEps = true }
                BarPill("Home", big) {
                    save(if (scrub >= 0) scrub else cur, dur)
                    onHome()
                }
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
                    Text("Seek", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(10.dp))
                    KeyChip("\u25B2  \u25BC")
                    Text("Navigate", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(10.dp))
                    KeyChip("BACK")
                    Text("Home", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            }
        }
        }

        // Episodes sheet (design pack "Go to episode"): glass modal with the
        // labeled S/E HUD steppers, a quick-select rail of real season
        // episodes, and the auto-skip toggle.
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
                        .padding(24.dp)
                        .widthIn(max = if (big) 880.dp else 560.dp)
                        .background(Card, RoundedCornerShape(20.dp))
                        .padding(20.dp),
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
                                "Go to episode",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = if (big) 21.sp else 18.sp,
                            )
                            Text(
                                title.name + (if (title.type != "movie") "  \u2022  Season " + epSeason else ""),
                                color = Dim,
                                fontFamily = BodyFont,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BarPill("Close", big) { showEps = false }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    if (title.type != "movie") {
                        // Labeled HUD steppers (design pack): tiny tracked
                        // label over the control pair.
                        Text(
                            "CURRENT SEASON",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BarPill("S  -", big) { epSeason = (epSeason - 1).coerceAtLeast(1) }
                            Text(
                                "Season " + epSeason,
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = if (big) 17.sp else 15.sp,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                            )
                            BarPill("S  +", big) { epSeason += 1 }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "SELECTED",
                            color = Dim,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            BarPill("E  -", big) { epNumber = (epNumber - 1).coerceAtLeast(1) }
                            Text(
                                "Episode " + epNumber,
                                color = Accent,
                                fontWeight = FontWeight.Black,
                                fontSize = if (big) 17.sp else 15.sp,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                            )
                            BarPill("E  +", big) { epNumber += 1 }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Quick-select window: 12 episodes around the current
                        // one - the sheet stays smooth on 1,400-episode seas.
                        val total = epsList.size
                        val win = if (total <= 12) epsList else {
                            val idx = epsList.indexOfFirst { it.n == epNumber }.let { if (it < 0) 0 else it }
                            val start = (idx - 3).coerceIn(0, (total - 12).coerceAtLeast(0))
                            epsList.subList(start, start + 12)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Quick Select Episodes" + if (total > 0) " (" + total + ")" else "",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (big) 14.sp else 12.sp,
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
                            win.chunked(2).forEach { pair ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    pair.forEach { ep ->
                                        QuickEpCard(
                                            ep = ep,
                                            big = big,
                                            selected = ep.n == epNumber,
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            epNumber = ep.n
                                            showEps = false
                                            save(0f, dur)
                                            cur = 0f
                                            playing = true
                                            load()
                                        }
                                    }
                                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Pill, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text("Auto-skip", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            BarPill(if (autoSkip) "ON" else "OFF", big) {
                                autoSkip = !autoSkip
                                Store.setAutoSkip(ctx, autoSkip)
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "intros/recaps skip themselves",
                                color = Dim,
                                fontFamily = BodyFont,
                                fontSize = 11.sp,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Load", big, accent = true, modifier = Modifier.focusRequester(epsFirstFR)) {
                            showEps = false
                            save(0f, dur)
                            cur = 0f
                            playing = true
                            load()
                        }
                        PillButton("Close", big) { showEps = false }
                    }
                    if (big) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            KeyChip("\u25C0  \u25B6")
                            Text("Navigate", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            KeyChip("OK")
                            Text("Play Episode", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(10.dp))
                            KeyChip("BACK")
                            Text("Resume Current", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
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
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .pointerInput(Unit) { detectTapGestures { showSubs = false } },
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
                        items(SUBLANGS.size) { i ->
                            val (code, label) = SUBLANGS[i]
                            val on = code == wyzLang
                            Box(
                                modifier = Modifier
                                    .then(if (i == 0) Modifier.focusRequester(subsFirstFR) else Modifier)
                                    .focusRing(CircleShape)
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
                        Text(subMsg, color = Amber, fontFamily = BodyFont, fontSize = 11.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                    } else {
                        Text(
                            "Same title, different releases - pick yours for exact sync.",
                            color = Dim,
                            fontFamily = BodyFont,
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
                            // Forgetting the pick keeps it gone after reopen.
                            Store.saveSubPick(ctx, title.type, title.id, epSeason, epNumber, null)
                        }
                        BarPill("Try backups", big) { bsTick += 1 }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    // STYLING & SYNC TUNING (design pack panel): one glass
                    // island for every caption taste, all device-local.
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
                            Text("TEXT SIZE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(96.dp))
                            BarPill("A -", big) {
                                subStyle = subStyle.copy(size = (subStyle.size - 2).coerceIn(12, 26))
                                Store.saveSubStyle(ctx, subStyle)
                            }
                            Text(
                                subStyle.size.toString() + "sp",
                                color = Accent,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            BarPill("A +", big) {
                                subStyle = subStyle.copy(size = (subStyle.size + 2).coerceIn(12, 26))
                                Store.saveSubStyle(ctx, subStyle)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TIMING SYNC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(96.dp))
                            BarPill(" - ", big) {
                                val o = Math.round((subOffset - 0.5f) * 10) / 10f
                                subOffset = o.coerceIn(-30f, 30f)
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                            Text(
                                subOffset.let { (if (it >= 0) "+" else "") + String.format("%.1f", it) + "s" },
                                color = Accent,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            BarPill(" + ", big) {
                                val o = Math.round((subOffset + 0.5f) * 10) / 10f
                                subOffset = o.coerceIn(-30f, 30f)
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("POSITION", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(96.dp))
                            BarPill("<", big) {
                                // "<" moves captions UP the screen (site rule)
                                val order = listOf("bottom", "center", "top")
                                val i = order.indexOf(subPos).let { if (it < 0) 0 else it }
                                subPos = order[(i + 1) % order.size]
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                            Text(
                                subPos.replaceFirstChar { it.uppercase() },
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            BarPill(">", big) {
                                val order = listOf("bottom", "center", "top")
                                val i = order.indexOf(subPos).let { if (it < 0) 0 else it }
                                subPos = order[(i + 2) % order.size]
                                Store.saveSubsPref(ctx, title.type, title.id, subPos, subOffset)
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("TEXT COLOR", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(96.dp))
                            listOf(0 to "White", 1 to "Yellow", 2 to "Cyan").forEach { (idx, name) ->
                                val on = subStyle.color == idx
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .focusRing(RoundedCornerShape(50), pop = false)
                                        .background(if (on) Accent else Card, RoundedCornerShape(50))
                                        .clickable {
                                            subStyle = subStyle.copy(color = idx)
                                            Store.saveSubStyle(ctx, subStyle)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                ) {
                                    Text(
                                        name,
                                        color = if (on) Ink else when (idx) {
                                            1 -> Color(0xFFFFE066)
                                            2 -> Color(0xFF7FE7F5)
                                            else -> Color.White
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("BG PLATE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp, modifier = Modifier.width(96.dp))
                            listOf(0 to "None", 60 to "Semi", 100 to "Solid").forEach { (v, name) ->
                                val on = subStyle.bg == v
                                Box(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .focusRing(RoundedCornerShape(50), pop = false)
                                        .background(if (on) Accent else Card, RoundedCornerShape(50))
                                        .clickable {
                                            subStyle = subStyle.copy(bg = v)
                                            Store.saveSubStyle(ctx, subStyle)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                ) {
                                    Text(
                                        name,
                                        color = if (on) Ink else Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                    )
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
                    Spacer(modifier = Modifier.height(12.dp))
                    // AniPM's own subtitle tracks (player-side), website
                    // setSubtitles parity: Off + reported labels, cycled.
                    if (anipmSubLabels.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Player subs", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.width(74.dp))
                            BarPill("<", big) {
                                val n = anipmSubLabels.size
                                anipmSubIdx = if (anipmSubIdx <= 0) n - 1 else anipmSubIdx - 1
                                val label = anipmSubLabels[anipmSubIdx]
                                cmd("window.__sb && window.__sb.subs(true, '" + label.replace("'", "") + "')")
                            }
                            Text(
                                anipmSubLabels.getOrNull(anipmSubIdx) ?: "Off",
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                            BarPill(">", big) {
                                val n = anipmSubLabels.size
                                anipmSubIdx = if (anipmSubIdx >= n - 1) -1 else anipmSubIdx + 1
                                if (anipmSubIdx < 0) {
                                    cmd("window.__sb && window.__sb.subs(false)")
                                } else {
                                    val label = anipmSubLabels[anipmSubIdx]
                                    cmd("window.__sb && window.__sb.subs(true, '" + label.replace("'", "") + "')")
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    PillButton("Close", big, accent = true) { showSubs = false }
                }
            }
        }

        // Server sheet: mirror picker (website server-list rules - Doraemon
        // never lists VidBolt, anime without mapping never lists AniPM).
        if (showServers) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .pointerInput(Unit) { detectTapGestures { showServers = false } },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .background(Ink, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Server", color = Accent, fontWeight = FontWeight.Black, fontSize = if (big) 20.sp else 17.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    serverList.forEachIndexed { i, id ->
                        BarPill(
                            (MIRROR_NAMES[id] ?: id) + if (id == mirror) "  (current)" else "",
                            big,
                            modifier = if (i == 0) Modifier.focusRequester(serverFirstFR) else Modifier,
                        ) {
                            if (id != mirror) {
                                val at = cur
                                mirror = id
                                playing = true
                                load(startAt = at)
                            }
                            showServers = false
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    PillButton("Close", big, accent = true) { showServers = false }
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
            .focusRing(CircleShape)
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

/** Quick-select episode card for the Episodes sheet (design pack): E-cap +
    real name/meta, selection ring in accent tint. Clicking plays it. */
@Composable
private fun QuickEpCard(
    ep: Ep,
    big: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .focusRing(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x1A00F0B5) else Pill, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(if (big) 44.dp else 38.dp)
                .background(Card, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "E" + ep.n,
                color = Accent,
                fontWeight = FontWeight.Black,
                fontSize = if (big) 13.sp else 11.sp,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (ep.name.isNotBlank() && ep.name != "Episode " + ep.n) ep.name else "Episode " + ep.n,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (big) 13.sp else 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    ep.runtime > 0 -> ep.runtime.toString() + "m"
                    ep.year > 0 -> ep.year.toString()
                    else -> ""
                },
                color = Dim,
                fontSize = 10.sp,
                maxLines = 1,
            )
        }
        if (selected) {
            Text(
                "SELECTED",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}
