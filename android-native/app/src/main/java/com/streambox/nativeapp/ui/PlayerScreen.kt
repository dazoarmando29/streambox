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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Title
import com.streambox.nativeapp.data.Progress
import com.streambox.nativeapp.data.Store

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
        on(type, json)
    }
}

private val RATES = listOf(1f, 1.25f, 1.5f, 2f, 0.5f)

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
    // House rule: Back from the player goes straight home (Netflix-style).
    BackHandler { onHome() }

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
    var web by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }
    var lastSave by remember { mutableStateOf(0L) }

    fun save(c: Float, d: Float) {
        Store.saveProgress(
            ctx,
            Progress(title.type, title.id, title.name, title.poster, epSeason, epNumber, c, d, System.currentTimeMillis(), mal, ani),
        )
    }

    fun load() {
        main.post {
            web?.loadUrl(playerUrl(title, epSeason, epNumber, mal, ani))
        }
    }

    fun cmd(js: String) {
        main.post { web?.evaluateJavascript(js, null) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
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
                            view?.evaluateJavascript(SB_HOOK, null)
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
                    isFocusable = !AppEnv.isTv
                    isFocusableInTouchMode = !AppEnv.isTv
                    loadUrl(playerUrl(title, epSeason, epNumber, mal, ani))
                    web = this
                }
            },
        )

        // Top-left title card (website style).
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

        // Center overlay while paused (website style).
        if (!playing && !showEps) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier.align(Alignment.Center),
            ) {
                CircleBtn("-10s", big) {
                    val t = (cur - 10f).coerceAtLeast(0f)
                    cmd("window.__sb && window.__sb.seek(" + t.toInt() + ")")
                    cur = t
                }
                Box(
                    modifier = Modifier
                        .size(if (big) 92.dp else 76.dp)
                        .focusRing()
                        .background(Accent, CircleShape)
                        .clickable {
                            playing = true
                            cmd("window.__sb && window.__sb.play()")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Ink, modifier = Modifier.size(40.dp))
                }
                CircleBtn("+30s", big) {
                    val t = (cur + 30f).coerceAtMost(if (dur > 0) dur else Float.MAX_VALUE)
                    cmd("window.__sb && window.__sb.seek(" + t.toInt() + ")")
                    cur = t
                }
            }
        }

        // Bottom control bar (website style): time row + button row.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0x000B1426), Color(0xE60B1426))))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
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
                            cmd("window.__sb && window.__sb.seek(" + scrub.toInt() + ")")
                            cur = scrub
                            scrub = -1f
                        }
                    },
                    valueRange = 0f..(if (dur > 0) dur else 1f),
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
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
                        .focusRing()
                        .background(Accent, CircleShape)
                        .clickable {
                            playing = !playing
                            cmd(if (playing) "window.__sb && window.__sb.play()" else "window.__sb && window.__sb.pause()")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Ink,
                        modifier = Modifier.size(if (playing) 22.dp else 28.dp),
                    )
                }
                BarPill("-10s", big) {
                    val t = ((if (scrub >= 0) scrub else cur) - 10f).coerceAtLeast(0f)
                    cmd("window.__sb && window.__sb.seek(" + t.toInt() + ")")
                    cur = t
                }
                BarPill("+30s", big) {
                    val t = ((if (scrub >= 0) scrub else cur) + 30f).coerceAtMost(if (dur > 0) dur else Float.MAX_VALUE)
                    cmd("window.__sb && window.__sb.seek(" + t.toInt() + ")")
                    cur = t
                }
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
                BarPill(RATES[rateIdx].let { if (it == it.toInt().toFloat()) it.toInt().toString() + "x" else it.toString() + "x" }, big) {
                    rateIdx = (rateIdx + 1) % RATES.size
                    cmd("window.__sb && window.__sb.rate(" + RATES[rateIdx] + ")")
                }
                Spacer(modifier = Modifier.weight(1f))
                BarPill("Episodes", big, icon = Icons.Filled.Menu) { showEps = true }
                BarPill("Home", big) {
                    save(if (scrub >= 0) scrub else cur, dur)
                    onHome()
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
                            load()
                        }
                        PillButton("Close", big) { showEps = false }
                    }
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
private fun BarPill(label: String, big: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
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
