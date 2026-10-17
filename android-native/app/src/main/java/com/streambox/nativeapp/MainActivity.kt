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
        setContent { StreamBoxApp() }
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
}

object AppEnv {
    var isTv: Boolean = false
    /** Hidden Direct HLS slot flag (debug only, off in normal runs): opens
        the native Media3 surface + the "Direct HLS" server row so the shell
        can be verified before an authorized source exists. */
    var hlsTest: Boolean = false
    var hlsTestUrl: String = ""
}
