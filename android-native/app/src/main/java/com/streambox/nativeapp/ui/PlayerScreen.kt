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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.api.Title

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
    mute: function(m){ var v=document.querySelector('video'); if(v) v.muted=!!m; try{window.postMessage({type:'cinesrc:command',command:'setMuted',args:[!!m]},'*');}catch(e){} }
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

    var playing by remember { mutableStateOf(true) }
    var cur by remember { mutableFloatStateOf(0f) }
    var dur by remember { mutableFloatStateOf(0f) }
    var muted by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(-1f) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val main = remember { Handler(Looper.getMainLooper()) }

    val url = remember(title, season, episode, mal, ani) {
        playerUrl(title, season, episode, mal, ani)
    }
    val big = AppEnv.isTv
    val btn = if (big) 56.dp else 48.dp

    fun cmd(js: String) {
        main.post { web?.evaluateJavascript(js, null) }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
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
                                    "ready" -> {}
                                    "ended" -> playing = false
                                    "error" -> {}
                                    "loadedmetadata" -> dur = o.optDouble("duration", 0.0).toFloat()
                                    "timeupdate" -> {
                                        cur = o.optDouble("currentTime", 0.0).toFloat()
                                        val d = o.optDouble("duration", 0.0).toFloat()
                                        if (d > 0) dur = d
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }), "Android")
                    // TV: keep D-pad on OUR bar, never trapped in the embed
                    isFocusable = !AppEnv.isTv
                    isFocusableInTouchMode = !AppEnv.isTv
                    loadUrl(url)
                    web = this
                }
            },
        )

        // Our bar (house rule: ours is the player). Bottom scrim + controls.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0x000B1426), Color(0xE60B1426)))
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                title.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = if (big) 18.sp else 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fmt(if (scrub >= 0) scrub else cur), color = Color(0xFFB9C7DA), fontSize = 12.sp)
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
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF14B8A6),
                        activeTrackColor = Color(0xFF14B8A6),
                        inactiveTrackColor = Color(0xFF24374F),
                    ),
                )
                Text(fmt(dur), color = Color(0xFFB9C7DA), fontSize = 12.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BarBtn(label = if (playing) "Pause" else "Play", big = big, h = btn) {
                    playing = !playing
                    cmd(if (playing) "window.__sb && window.__sb.play()" else "window.__sb && window.__sb.pause()")
                }
                BarBtn(label = "-10s", big = big, h = btn) {
                    val t = ((if (scrub >= 0) scrub else cur) - 10f).coerceAtLeast(0f)
                    cmd("window.__sb && window.__sb.seek(" + t.toInt() + ")")
                    cur = t
                }
                BarBtn(label = "+30s", big = big, h = btn) {
                    val t = ((if (scrub >= 0) scrub else cur) + 30f).coerceAtMost(if (dur > 0) dur else Float.MAX_VALUE)
                    cmd("window.__sb && window.__sb.seek(" + t.toInt() + ")")
                    cur = t
                }
                BarBtn(label = if (muted) "Unmute" else "Mute", big = big, h = btn) {
                    muted = !muted
                    cmd("window.__sb && window.__sb.mute(" + muted + ")")
                }
                Spacer(modifier = Modifier.weight(1f))
                BarBtn(label = "Home", big = big, h = btn, accent = true) { onHome() }
            }
        }
    }
}

@Composable
private fun BarBtn(label: String, big: Boolean, h: androidx.compose.ui.unit.Dp, accent: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (accent) Color(0xFF14B8A6) else Color(0xFF1D2C44),
            contentColor = if (accent) Color(0xFF0B1426) else Color.White,
        ),
        modifier = Modifier.focusRing().height(h),
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = if (big) 16.sp else 13.sp)
    }
}
