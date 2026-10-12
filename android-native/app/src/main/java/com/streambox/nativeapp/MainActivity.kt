package com.streambox.nativeapp

import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.streambox.nativeapp.ui.StreamBoxApp

/** StreamBox native preview: Compose shell for phone + Android TV.
    Playback still hosts the provider web embeds (the only surface the
    providers ship); everything around it is native. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppEnv.isTv = isTvDevice()
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
}
