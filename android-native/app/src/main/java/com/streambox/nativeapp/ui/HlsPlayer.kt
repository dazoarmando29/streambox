package com.streambox.nativeapp.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Hidden HLS shell test asset: Apple's official HLS example stream (multi-
    variant, adaptive bitrate, real audio + subtitle tracks). It exists ONLY
    to verify the Direct HLS shell before an authorized source exists, and is
    reachable solely through the hidden "hlstest" intent flag (AppEnv.hlsTest).
    This is the one stream URL allowed outside the provider embed builders -
    provider stream URLs are never invented or hardcoded (house rule). */
const val HLS_TEST_URL =
    "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8"

/** Dumb surface for the Direct HLS slot: hardware-decoded HLS on its own
    Media3 player (phone + weak TV boxes stay smooth where WebView MSE
    stutters). OUR bar is the only chrome (system controller off), volume is
    never touched here (no auto-mute, house rule), and the view takes no
    focus so the TV remote always stays on our layered nav. Everything
    around playback - skip pills, captions, watchdog, progress - rides the
    same cur/dur/playing seam the web embeds feed, so it works unchanged. */
@Composable
fun HlsSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { c ->
            PlayerView(c).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                isFocusable = false
                isFocusableInTouchMode = false
                this.player = player
            }
        },
        onRelease = { it.player = null },
    )
}
