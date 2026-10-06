package com.streambox.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** Branded launch screen: shows the logo instantly, then hands off to the app. */
public class SplashActivity extends Activity {
    private static final long DELAY_MS = 1400;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable go = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } catch (Exception ignored) {}
        handler.postDelayed(go, DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(go);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        handler.removeCallbacks(go);
        super.onBackPressed();
    }
}
