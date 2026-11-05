package com.streambox.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

/** Branded launch screen: shows the logo instantly, then hands off to the app.
    Also the streambox://party/CODE deep-link entry (keeps MainActivity
    exported=false): the code rides along as an intent extra. */
public class SplashActivity extends Activity {
    private static final long DELAY_MS = 1400;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean handedOff;
    private String partyCode;

    private void readPartyIntent() {
        try {
            android.net.Uri d = getIntent() == null ? null : getIntent().getData();
            if (d != null && "streambox".equals(d.getScheme()) && "party".equals(d.getHost())) {
                String seg = d.getLastPathSegment();
                if (seg != null) {
                    String clean = seg.replaceAll("[^A-Za-z0-9]", "");
                    if (!clean.isEmpty()) partyCode = clean.substring(0, Math.min(6, clean.length()));
                }
            }
        } catch (Exception ignored) {}
    }

    private final Runnable go = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || handedOff) return;
            handedOff = true;
            Intent i = new Intent(SplashActivity.this, MainActivity.class);
            if (partyCode != null && !partyCode.isEmpty()) i.putExtra("party", partyCode);
            startActivity(i);
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
        // countdown armed in onResume (only while visible)
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Start the countdown only while visible: a delayed start from a
        // backgrounded splash is a restricted background activity start on
        // Android 10+ (dropped launch / dead task on return). Invite taps go
        // straight in - the party is waiting.
        if (!handedOff) {
            readPartyIntent();
            handler.postDelayed(go, (partyCode != null && !partyCode.isEmpty()) ? 0 : DELAY_MS);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(go);
        super.onPause();
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
