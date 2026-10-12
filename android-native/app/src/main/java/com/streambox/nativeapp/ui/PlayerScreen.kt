package com.streambox.nativeapp.ui

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.streambox.nativeapp.api.Title

/** Builds the provider embed URL. Contracts (verified against the live
    players): CineSrc /embed/movie/:id and /embed/tv/:id?s=&e=; AniPM
    /embed/mal/:mal/:ep/dub|sub (valid routes; /en is a 404 and is never
    generated here). */
fun playerUrl(title: Title, season: Int, episode: Int, mal: Int?, ani: Int?): String {
    return when (title.type) {
        "movie" -> "https://cinesrc.st/embed/movie/" + title.id
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
        else -> "https://cinesrc.st/embed/tv/" + title.id + "?s=" + season + "&e=" + episode
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

    val url = remember(title, season, episode, mal, ani) {
        playerUrl(title, season, episode, mal, ani)
    }

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
                // Never intercept subframe navigation: players navigate their
                // own inner frames to video hosts (wrapper lesson - blanket
                // allowlists kill playback inside embeds).
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                loadUrl(url)
            }
        },
    )
}
