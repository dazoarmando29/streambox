package com.streambox.nativeapp

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.streambox.nativeapp.ui.HLS_TEST_URL
import com.streambox.nativeapp.ui.StreamBoxApp

/** StreamBox native preview: Compose shell for phone + Android TV.
    Playback still hosts the provider web embeds (the only surface the
    providers ship) plus the dormant Direct HLS slot; everything around it
    is native. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppEnv.isTv = isTvDevice()
        // Hidden Direct HLS debug flag: adb --es hlstest 1 (Apple test
        // asset) or --es hlstest <HLS manifest URL>. Force-stop the app
        // first - a warm launch reuses the old state and skips this.
        val hls = intent?.getStringExtra("hlstest")
        if (hls != null) {
            AppEnv.hlsTest = true
            AppEnv.hlsTestUrl = if (hls == "1" || hls.equals("true", true)) HLS_TEST_URL else hls
        }
        // Watch Party invite: streambox://party/CODE (custom scheme). The code
        // rides into StreamBoxApp which opens the room screen.
        readPartyIntent()
        setContent { StreamBoxApp() }
    }

    /** singleTask relaunch (invite tapped while the app runs): route the code
        into the LIVE session - never a second activity/connection. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPartyIntent()
    }

    private fun readPartyIntent() {
        try {
            var raw: String? = intent?.getStringExtra("party")
            if (raw.isNullOrBlank()) {
                val d = intent?.data
                if (d != null && d.scheme == "streambox" && d.host == "party") raw = d.lastPathSegment
            }
            if (!raw.isNullOrBlank()) {
                val clean = raw.replace(Regex("[^A-Za-z0-9]"), "").take(6)
                if (clean.isNotEmpty()) AppEnv.partyCode.value = clean
            }
        } catch (e: Exception) {
        }
    }

    private fun isTvDevice(): Boolean {
        return try {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) true
            else {
                val um = getSystemService(UI_MODE_SERVICE) as UiModeManager
                um.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
            }
        } catch (e: Exception) {
            false
        }
    }

    // Memory pressure valve (0.74): weak TV boxes kill the WebView render
    // process first when memory runs out ("stutters, then mutes, then the
    // picture freezes"). Drop our cached shim pages the moment the system
    // flags pressure - they refetch from the CDN instantly - so the render
    // process keeps its headroom.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.streambox.nativeapp.ui.vbTrimMemory()
        }
    }
}

object AppEnv {
    var isTv: Boolean = false
    /** Hidden Direct HLS slot flag (debug only, off in normal runs): opens
        the native Media3 surface + the "Direct HLS" server row so the shell
        can be verified before an authorized source exists. */
    var hlsTest: Boolean = false
    var hlsTestUrl: String = ""
    /** Watch Party invite code from streambox://party/CODE (Compose-observed;
        consumed once by StreamBoxApp which opens the room screen). */
    val partyCode = androidx.compose.runtime.mutableStateOf<String?>(null)
}
