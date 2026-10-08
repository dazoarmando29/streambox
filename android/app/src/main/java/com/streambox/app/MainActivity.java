package com.streambox.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/** StreamBox: fullscreen WebView wrapper for phone + Android TV. */
public class MainActivity extends Activity {
    private static final String HOME = BuildConfig.HOME_URL;
    private WebView web;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fresh-start guarantee: once per app version, wipe WebView data
        // (cache, DOM storage, service workers) BEFORE creating the WebView,
        // so the first launch after every update can never serve stale pages.
        freshStartIfNewVersion();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setAllowFileAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        // TV remotes + keyboards navigate page focus; keep default UA so site tv-mode detection works
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        // explicit hardware layer: video + page composite on the GPU, not the CPU
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String host = "";
                try { host = request.getUrl().getHost(); if (host == null) host = ""; }
                catch (Exception ignored) { host = ""; }
                host = host.toLowerCase();
                // Allowlist: our site + TMDB + CineSrc + all mirror players + trailer.
                // Everything else (ad popups, hijacks, non-http schemes) is cancelled
                // so a bad mirror can never navigate the app away.
                boolean allowed =
                        url.startsWith("https://dazoarmando29.github.io/")
                        || host.equals("cinesrc.st") || host.endsWith(".cinesrc.st")
                        || host.endsWith("themoviedb.org") || host.endsWith("image.tmdb.org")
                        || host.endsWith("vidlink.pro") || host.endsWith("vidfast.pro")
                        || host.endsWith("vidsrc.to") || host.endsWith("vsembed.su")
                        || host.endsWith("autoembed.co") || host.endsWith("2embed.cc")
                        || host.endsWith("yapgrid.com")
                        || host.endsWith("youtube-nocookie.com") || host.endsWith("youtube.com")
                        || host.endsWith("youtu.be");
                if (allowed) return false;
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (fullscreenView != null) { callback.onCustomViewHidden(); return; }
                fullscreenView = view;
                fullscreenCallback = callback;
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                // immersive fullscreen so player controls are never clipped by system bars
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                decor.addView(fullscreenView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
            }

            @Override
            public void onHideCustomView() {
                if (fullscreenView == null) return;
                FrameLayout decor = (FrameLayout) getWindow().getDecorView();
                decor.removeView(fullscreenView);
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                fullscreenView = null;
                fullscreenCallback = null;
                web.setVisibility(View.VISIBLE);
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
    }

    /** Wipes WebView profile + cache once per app version (first launch only). */
    private void freshStartIfNewVersion() {
        String ver;
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "0";
        }
        android.content.SharedPreferences prefs = getSharedPreferences("streambox", MODE_PRIVATE);
        if (ver.equals(prefs.getString("fresh_cleared_for", ""))) return;
        deleteRecursive(new java.io.File(getApplicationInfo().dataDir, "app_webview"));
        deleteRecursive(getCacheDir());
        prefs.edit().putString("fresh_cleared_for", ver != null ? ver : "0").apply();
    }

    private static void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        }
        try { f.delete(); } catch (Exception ignored) {}
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            web.getWebChromeClient().onHideCustomView();
            return;
        }
        if (web == null) { super.onBackPressed(); return; }
        // The site is a single-page app (no history entries), so route Back
        // through its own navigation: player -> browse -> close popup -> exit.
        web.evaluateJavascript(
            "(function(){"
            + "var ae=document.activeElement;"
            + "if(ae&&ae.tagName==='IFRAME'){ae.blur();var mb=document.querySelector('#mirrorBar:not(.hidden) button');if(mb){mb.focus();return 'refocus';}var b=document.getElementById('ctlPlay');if(b)b.focus();return 'refocus';}"
            + "if(document.body.classList.contains('video-fs')){exitVideoFs();goHome();return 'home';}"
            + "var q=document.getElementById('searchPanel');"
            + "if(q&&!q.classList.contains('hidden')){closeSearch();return 'panel';}"
            + "var p=document.getElementById('epDrawer');"
            + "if(p&&!p.classList.contains('hidden')){closePanels();return 'panel';}"
            + "var s=document.getElementById('setPanel');"
            + "if(s&&!s.classList.contains('hidden')){closePanels();return 'panel';}"
            + "var g=document.getElementById('tvGuide');"
            + "if(g&&!g.classList.contains('hidden')){closeTvGuide();return 'guide';}"
            + "var m=document.getElementById('modal');"
            + "if(m&&!m.classList.contains('hidden')){closeModal();return 'modal';}"
            + "var w=document.getElementById('watchView');"
            + "if(w&&!w.classList.contains('hidden')){goHome();return 'home';}"
            + "return 'exit';})();",
            value -> {
                String v = value == null ? "" : value.replace("\"", "");
                if ("exit".equals(v)) {
                    MainActivity.super.onBackPressed();
                }
            });
    }

    @Override
    protected void onPause() {
        super.onPause();
        // freeze page timers in background so a paused app burns no CPU
        if (web != null) { web.onPause(); web.pauseTimers(); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); }
    }

    @Override
    protected void onDestroy() {
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }
}
