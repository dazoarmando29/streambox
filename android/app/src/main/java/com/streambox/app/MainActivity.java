package com.streambox.app;

import android.app.Activity;
import android.content.Intent;
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
import android.widget.Toast;

/** StreamBox: fullscreen WebView wrapper for phone + Android TV. */
public class MainActivity extends Activity {
    private static final String HOME = BuildConfig.HOME_URL;
    private static final String APP_PREFIX = "https://dazoarmando29.github.io/streambox/";
    // ---- in-app update trust rules (the download is an APK - the sharp edge) ----
    // only these hosts are ever contacted for an update, over TLS, and EVERY
    // redirect hop is re-checked; the trigger URL must be one of our release
    // assets; the bytes MUST match the sha256 the release channel reports AND
    // be signed with THIS app's own certificate before the installer sees them;
    // only a strictly newer versionCode is ever offered (no downgrades)
    private static final String UPDATE_TRIGGER =
            "https://github.com/dazoarmando29/streambox/releases/download/";
    private static final String[] UPDATE_HOSTS = {
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
    };
    private static final String API_MIRROR =
            "https://streambox-api.dazoarmando.workers.dev/api/apk-latest";
    private static final String API_DIRECT =
            "https://api.github.com/repos/dazoarmando29/streambox/releases/tags/latest";
    private WebView web;
    private WebChromeClient chromeClient;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    // static: recreate() builds a NEW activity instance - an instance flag
    // would read false there and restoreState could resurrect the crash page
    private static boolean webviewCrashed;
    private static long rendererCrashAt;
    private static int rendererCrashBurst;
    private String partyCode;
    private volatile boolean updateRunning;

    private static boolean isHostOrSubdomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** TV boxes (Leanback / television UI mode) get the ?tv=1 remote layout;
        phones and tablets get the touch-first UI (?tv=1 would force giant
        TV chrome and break touch navigation). */
    private boolean isTvDevice() {
        try {
            if (getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_LEANBACK)) return true;
            android.app.UiModeManager um = (android.app.UiModeManager)
                    getSystemService(android.content.Context.UI_MODE_SERVICE);
            if (um != null && um.getCurrentModeType()
                    == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Weak TV sticks/boxes (Android Go, 1GB sticks): even the Full flavor
        boots the calm site — same pixels, none of the blur/glow/motion tax. */
    private boolean isLowRamDevice() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if (am != null) return am.isLowRamDevice();
        } catch (Exception ignored) {}
        return false;
    }

    /** HOME_URL carries the flavor params (?tv=1 / ?tv=1&calm=1); rebuild them
        per-device so phones never inherit the TV layout. Watch Party invites
        (streambox://party/CODE forwarded by SplashActivity) ride along. */
    private String startUrl() {
        String base = HOME;
        try {
            int q = base.indexOf('?');
            if (q >= 0) base = base.substring(0, q);
        } catch (Exception ignored) {}
        boolean calm = false;
        try { calm = HOME.contains("calm=1") || isLowRamDevice(); } catch (Exception ignored) {}
        String url = isTvDevice() ? base + (calm ? "?tv=1&calm=1" : "?tv=1")
                                  : base + (calm ? "?calm=1" : "");
        if (partyCode != null && !partyCode.isEmpty()) {
            url += (url.contains("?") ? "&" : "?") + "party="
                    + android.net.Uri.encode(partyCode);
        }
        return url;
    }

    /** streambox://party/CODE deep link (custom scheme: no https hijacking). */
    private void readPartyIntent() {
        try {
            android.content.Intent in = getIntent();
            if (in == null) return;
            String raw = in.getStringExtra("party");
            if (raw == null || raw.isEmpty()) {
                android.net.Uri d = in.getData();
                if (d != null && "streambox".equals(d.getScheme()) && "party".equals(d.getHost())) {
                    raw = d.getLastPathSegment();
                }
            }
            if (raw != null && !raw.isEmpty()) {
                String clean = raw.replaceAll("[^A-Za-z0-9]", "");
                if (!clean.isEmpty()) partyCode = clean.substring(0, Math.min(6, clean.length()));
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        // singleTask relaunch (invite tapped while the app runs): NEVER open a
        // second WebView/connection - route the code into the live page instead
        setIntent(intent);
        readPartyIntent();
        if (web != null && partyCode != null && !partyCode.isEmpty()) {
            web.evaluateJavascript(
                "try{PARTY.open('" + partyCode.replaceAll("[^A-Za-z0-9]", "") + "');}catch(e){}", null);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readPartyIntent();
        // Fresh-start guarantee: once per app version, wipe the WebView's
        // CACHE layers + service worker BEFORE creating the WebView, so the
        // first launch after every update can never serve stale pages. User
        // data (Local Storage: watch progress, My List, PIN, settings;
        // IndexedDB; cookies) is preserved across updates.
        freshStartIfNewVersion();
        wipeUpdateLeftovers(); // the installer needs the file alive while it works; leftovers never pile up
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
        s.setAllowContentAccess(false);
        // NEVER_ALLOW: the default cleartext block (targetSdk 34, no NSC)
        // already stops http subresources, so compat-mode's looseness bought
        // nothing but surface. Defense in depth.
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
                // SUBFRAME traffic must never be cancelled: players (AniPM et
                // al) navigate their own inner frames to video hosts, and the
                // old blanket allowlist killed those -> black frame, "player
                // never starts" (TV-only: the website has no allowlist). Only
                // MAIN-FRAME navigations get the allowlist - that is what
                // keeps hijacks from walking the app away. Exotic schemes are
                // blocked everywhere; ad hosts die in shouldInterceptRequest.
                if (!request.isForMainFrame()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) return true;
                    return false;
                }
                // Main-frame scheme gate FIRST: intent://, javascript:,
                // content:// and friends must never reach the host allowlist
                // (intent:// URLs carry a look-alike host and can launch
                // other apps). Same rule the subframe branch applies.
                if (!url.startsWith("http://") && !url.startsWith("https://")) return true;
                String host = "";
                try { host = request.getUrl().getHost(); if (host == null) host = ""; }
                catch (Exception ignored) { host = ""; }
                host = host.toLowerCase(java.util.Locale.US);
                // Self-update: our own WebView release assets are never
                // "browsed" - the tap starts the native verified installer
                // flow (github.com stays blocked as a browsing destination)
                if (url.startsWith(UPDATE_TRIGGER)
                        && (url.endsWith("/streambox.apk") || url.endsWith("/streambox-lite.apk"))) {
                    startUpdateFlow();
                    return true;
                }
                // Allowlist: our site + TMDB + CineSrc + all mirror players + trailer.
                // Everything else (ad popups, hijacks, non-http schemes) is cancelled
                // so a bad mirror can never navigate the app away.
                boolean allowed =
                        url.startsWith("https://dazoarmando29.github.io/")
                        || isHostOrSubdomain(host, "cinesrc.st")
                        || isHostOrSubdomain(host, "themoviedb.org") || isHostOrSubdomain(host, "image.tmdb.org")
                        || isHostOrSubdomain(host, "vidlink.pro") || isHostOrSubdomain(host, "vidfast.pro") || isHostOrSubdomain(host, "vidfast.vc")
                        || isHostOrSubdomain(host, "vidsrc.to") || isHostOrSubdomain(host, "vsembed.su")
                        || isHostOrSubdomain(host, "autoembed.co") || isHostOrSubdomain(host, "2embed.cc")
                        || isHostOrSubdomain(host, "yapgrid.com") || isHostOrSubdomain(host, "vaplayer.ru")
                        || isHostOrSubdomain(host, "vidcore.org") || isHostOrSubdomain(host, "vidrift.net")
                        || isHostOrSubdomain(host, "vidzen.fun") || isHostOrSubdomain(host, "vidbolt.pro")
                        || isHostOrSubdomain(host, "ani.pm")
                        || isHostOrSubdomain(host, "youtube-nocookie.com") || isHostOrSubdomain(host, "youtube.com")
                        || isHostOrSubdomain(host, "youtu.be");
                if (allowed) return false;
                return true;
            }

            // Ad-network blocklist: applied to every frame, so ad scripts and
            // popunder/tracker domains inside mirror players (VidCore etc.)
            // die before they ever load. Video/source hosts are untouched.
            private static final java.util.Set<String> AD_HOSTS = new java.util.HashSet<>(java.util.Arrays.asList(
                    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "google-analytics.com",
                    "googletagmanager.com", "adservice.google.com", "adnxs.com", "adsrvr.org", "adroll.com",
                    "popads.net", "popcash.net", "propellerads.com", "propellerads.net", "popundertotal.com",
                    "tapadu.com", "exoclick.com", "juicyads.com", "hilltopads.com", "highcpm.com",
                    "adsterra.com", "adsterra.net", "histats.com", "hotjar.com", "facebook.net",
                    "amazon-adsystem.com", "scorecardresearch.com", "outbrain.com", "taboola.com",
                    "trafficjunky.net", "trafficjunky.com", "a-ads.com", "ads-mrg.com", "onclickmax.com",
                    "adblokkster.com", "bc.vc", "bc-ads.com", "tsyndicate.com", "syndicat-france.com",
                    // 2.16: curated ad-tech / popunder / tracker hosts (pure
                    // advertising infrastructure - never media delivery)
                    "adform.net", "smartadserver.com", "rubiconproject.com", "pubmatic.com",
                    "criteo.com", "criteo.net", "casalemedia.com", "openx.net", "openx.com",
                    "bidswitch.net", "sharethrough.com", "moatads.com", "doubleverify.com",
                    "bluekai.com", "demdex.net", "krxd.net", "rlcdn.com", "agkn.com",
                    "teads.tv", "yieldmo.com", "revcontent.com", "mgid.com", "content-ad.net",
                    "ad-maven.com", "onclicka.com", "ads.yahoo.com", "adtech.de", "adition.com"));

            private static boolean isAdHost(String host) {
                if (host == null || host.isEmpty()) return false;
                for (String ad : AD_HOSTS) {
                    if (host.equals(ad) || host.endsWith("." + ad)) return true;
                }
                return false;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                try {
                    String host = request.getUrl().getHost();
                    String h = host == null ? "" : host.toLowerCase(java.util.Locale.US);
                    // ad hosts die FIRST - even their pre-roll media (an ad
                    // video is still an ad). Real streams never ride ad hosts.
                    if (isAdHost(h)) {
                        return new android.webkit.WebResourceResponse("text/plain", "utf-8",
                                new java.io.ByteArrayInputStream(new byte[0]));
                    }
                    // media second: a stream-shaped request (.m3u8/.mp4/.ts...)
                    // from a non-ad host ALWAYS rides - Ad Shield checks media
                    // extensions before any broader heuristic can misfire.
                    String path = request.getUrl().getPath();
                    if (path != null) {
                        String p = path.toLowerCase(java.util.Locale.US);
                        if (p.endsWith(".m3u8") || p.endsWith(".mp4") || p.endsWith(".ts")
                                || p.endsWith(".m4s") || p.endsWith(".webm") || p.endsWith(".mkv")
                                || p.endsWith(".aac") || p.endsWith(".mpd")) {
                            return super.shouldInterceptRequest(view, request);
                        }
                    }
                } catch (Exception ignored) {}
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler, android.net.http.SslError error) {
                // VidCore serves a broken cert chain (leaf without its
                // intermediate). Modern Chromium self-heals via AIA fetching;
                // strict/old WebViews reject the chain and the player dies.
                // Scope is surgical: THAT host + chain-trust errors ONLY -
                // date/hostname/expired errors and every other host abort.
                try {
                    String h = "";
                    try {
                        java.net.URI u = new java.net.URI(error.getUrl() == null ? "" : error.getUrl());
                        if (u.getHost() != null) h = u.getHost().toLowerCase(java.util.Locale.US);
                    } catch (Exception ignored) {}
                    if (isHostOrSubdomain(h, "vidcore.org")
                            && error.getPrimaryError() == android.net.http.SslError.SSL_UNTRUSTED) {
                        handler.proceed();
                        return;
                    }
                } catch (Exception ignored) {}
                handler.cancel();
            }

            @Override
            public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                // A dead renderer can never be revived (low-memory TV sticks
                // hit this under memory pressure). The default handler kills
                // the whole app - instead destroy the dead shell and rebuild,
                // so playback recovers with one reload instead of a crash.
                // Circuit breaker: a DETERMINISTIC crash must not loop
                // recreate->crash forever - after 2 rebuilds in a minute let
                // the system end the app so the user starts clean.
                try {
                    if (web != null) {
                        web.destroy();
                        web = null;
                    }
                } catch (Exception ignored) {}
                hideFullscreen();
                long now = System.currentTimeMillis();
                if (now - rendererCrashAt > 60000) rendererCrashBurst = 0;
                rendererCrashAt = now;
                rendererCrashBurst++;
                if (rendererCrashBurst > 2) return false; // give up: no crash loop
                webviewCrashed = true;
                recreate();
                return true;
            }
        });

        chromeClient = new WebChromeClient() {
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
                hideFullscreen();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        };
        web.setWebChromeClient(chromeClient);
        // second update path: JS/subframe-initiated APK downloads land here
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            if (url != null && url.startsWith(UPDATE_TRIGGER)
                    && (url.endsWith("/streambox.apk") || url.endsWith("/streambox-lite.apk"))) {
                startUpdateFlow();
            }
        });

        boolean crashed = webviewCrashed;
        webviewCrashed = false; // one-shot: the next recreate restores normally
        if (!crashed && savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            // state restored after process death
        } else {
            // restoreState failed, fresh launch, or rebuild after a renderer
            // crash: never leave a blank WebView
            web.loadUrl(startUrl());
        }
    }

    /** Wipes the WebView's cache layers + service worker once per app version
        (first launch only). Local Storage / IndexedDB / Databases / Cookies
        are NEVER deleted: they hold watch progress, My List, the adult PIN
        and every user preference — an update must never lose user data. */
    private void freshStartIfNewVersion() {
        String ver;
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            ver = "0";
        }
        android.content.SharedPreferences prefs = getSharedPreferences("streambox", MODE_PRIVATE);
        if (ver.equals(prefs.getString("fresh_cleared_for", ""))) return;
        java.io.File wv = new java.io.File(getApplicationInfo().dataDir, "app_webview");
        // caches + the service worker (registration AND its CacheStorage):
        // deleting "Service Worker" is what guarantees no stale page/script
        // can survive an update; everything else here is rebuildable cache.
        for (String d : new String[] {"Cache", "Code Cache", "GPUCache", "Service Worker", "Session Storage"}) {
            deleteRecursive(new java.io.File(wv, d));
        }
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

    /** Tears down the HTML5 fullscreen custom view (idempotent). */
    private void hideFullscreen() {
        if (fullscreenView == null) return;
        FrameLayout decor = (FrameLayout) getWindow().getDecorView();
        decor.removeView(fullscreenView);
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        fullscreenView = null;
        WebChromeClient.CustomViewCallback cb = fullscreenCallback;
        fullscreenCallback = null;
        if (web != null) {
            web.setVisibility(View.VISIBLE);
            // refocus the page so remote/keyboard input lands in it again
            web.requestFocus();
        }
        // The WebChromeClient contract requires onCustomViewHidden() on EVERY
        // exit path. The page-initiated path (video end -> exitFullscreen)
        // used to skip it and the next fullscreen request never fired.
        if (cb != null) {
            try { cb.onCustomViewHidden(); } catch (Exception ignored) {}
        }
    }

    /** Media transport keys from remotes/headsets -> the site's player. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getAction() == android.view.KeyEvent.ACTION_UP && web != null) {
            int code = event.getKeyCode();
            String js = null;
            if (code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                js = "try{ctlToggle();}catch(e){}";
            } else if (code == android.view.KeyEvent.KEYCODE_MEDIA_PLAY) {
                js = "try{if(pState.paused)ctlToggle();}catch(e){}";
            } else if (code == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE) {
                js = "try{if(!pState.paused)ctlToggle();}catch(e){}";
            }
            if (js != null) {
                web.evaluateJavascript(js, null);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (fullscreenView != null) {
            // contract-correct dismissal (the callback fires inside hideFullscreen)
            hideFullscreen();
            return;
        }
        if (web == null) { super.onBackPressed(); return; }
        // The site is a single-page app (no history entries), so route Back
        // through its own navigation: player -> browse -> close popup -> exit.
        web.evaluateJavascript(
            "(function(){try{"
            + "var ae=document.activeElement;"
            + "if(ae&&ae.tagName==='IFRAME'){ae.blur();var mb=document.querySelector('#mirrorBar:not(.hidden) button');if(mb){mb.focus();return 'refocus';}var b=document.getElementById('ctlPlay');if(b)b.focus();return 'refocus';}"
            + "if(document.body.classList.contains('video-fs')){exitVideoFs();goHome();return 'home';}"
            + "var pn=document.getElementById('pinPad');"
            + "if(pn&&!pn.classList.contains('hidden')){pinClose();return 'panel';}"
            + "var g=document.getElementById('tvGuide');"
            + "if(g&&!g.classList.contains('hidden')){closeTvGuide();return 'guide';}"
            + "var q=document.getElementById('searchPanel');"
            + "if(q&&!q.classList.contains('hidden')){closeSearch();return 'panel';}"
            + "var p=document.getElementById('epDrawer');"
            + "if(p&&!p.classList.contains('hidden')){closePanels();return 'panel';}"
            + "var a=document.getElementById('avSheet');"
            + "if(a&&!a.classList.contains('hidden')){closePanels();return 'panel';}"
            + "var v=document.getElementById('vrSheet');"
            + "if(v&&!v.classList.contains('hidden')){closePanels();return 'panel';}"
            + "var s=document.getElementById('setPanel');"
            + "if(s&&!s.classList.contains('hidden')){closePanels();return 'panel';}"
            + "var u=document.getElementById('upNextCard');"
            + "if(u&&!u.classList.contains('hidden')){hideUpNext();return 'panel';}"
            + "var rd=document.getElementById('resumeDlg');"
            + "if(rd&&!rd.classList.contains('hidden')){resumePick(true);return 'panel';}"
            + "var m=document.getElementById('modal');"
            + "if(m&&!m.classList.contains('hidden')){closeModal();return 'modal';}"
            + "var w=document.getElementById('watchView');"
            + "if(w&&!w.classList.contains('hidden')){goHome();return 'home';}"
            + "return 'exit';}catch(e){return 'exit';}})();",
            value -> {
                // Fail-safe: a JS error (site and APK version-decoupled) or a
                // missing value must NEVER swallow the Back press — exit.
                String v = value == null ? "" : value.replace("\"", "");
                if ("exit".equals(v) || "null".equals(v) || v.isEmpty()) {
                    // On an allowlisted NON-SPA page (a mirror navigated the
                    // main frame) the SPA cascade knows nothing - consult the
                    // WebView history before killing the app (one Back on a
                    // mirror page used to close the whole app on TV).
                    try {
                        String cur = web != null && web.getUrl() != null ? web.getUrl() : "";
                        if (web != null && web.canGoBack() && !cur.startsWith(APP_PREFIX)) {
                            web.goBack();
                            return;
                        }
                    } catch (Exception ignored) {}
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
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // memory pressure: drop the WebView's RAM cache (disk cache kept, so
        // no reload storm). Page state lives in DOM/localStorage, unaffected.
        try {
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE && web != null) {
                web.clearCache(false);
            }
        } catch (Exception ignored) {}
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

    /* ---------- in-app updates: verified download -> package installer ----------
       Trust chain (in order): trigger URL must be one of OUR release assets ->
       release channel (edge-cached worker mirror, GitHub API fallback) reports
       the asset's sha256 -> every download redirect hop re-checked against the
       release hosts -> bytes match the published digest -> the archive is
       signed with THIS app's own certificate -> strictly newer versionCode ->
       content:// hand-off to the system installer. Any failure deletes the
       file and says so; nothing unverified ever reaches the installer. */

    private void wipeUpdateLeftovers() {
        // the installer needs the file alive while it works and the install
        // kills this process - leftovers are wiped on boot and before downloads
        try { deleteRecursive(new java.io.File(getFilesDir(), "updates")); } catch (Exception ignored) {}
    }

    private void updateMsg(final String msg) {
        try {
            runOnUiThread(() -> {
                try { Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    /** github.com is only ever OUR release-asset path; the CDN redirect hosts
        are accepted bare. Nothing else can ever be an update source. */
    private static boolean updateSourceAllowed(String u) {
        try {
            java.net.URI uri = new java.net.URI(u);
            String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.US);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (h.equals("github.com")) {
                return path.startsWith("/dazoarmando29/streambox/releases/download/");
            }
            return isHostOrSubdomain(h, "objects.githubusercontent.com")
                    || isHostOrSubdomain(h, "release-assets.githubusercontent.com");
        } catch (Exception ignored) { return false; }
    }

    /** The release METADATA channel rides two hardcoded, TLS-only endpoints. */
    private static boolean metaSourceAllowed(String u) {
        try {
            java.net.URI uri = new java.net.URI(u);
            String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.US);
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (isHostOrSubdomain(h, "streambox-api.dazoarmando.workers.dev")) {
                return path.startsWith("/api/apk-latest");
            }
            if (h.equals("api.github.com")) {
                return path.startsWith("/repos/dazoarmando29/streambox/releases/tags/");
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String wantedAsset() {
        // the flavor is known from OUR identity - never from a clicked link
        return getPackageName().contains("lite") ? "streambox-lite.apk" : "streambox.apk";
    }

    private void startUpdateFlow() {
        if (updateRunning) return; // one update at a time
        updateRunning = true;
        new Thread(() -> {
            try {
                runUpdate();
            } catch (Exception e) {
                updateMsg("Update failed: " + ((e != null && e.getMessage() != null) ? e.getMessage() : "unknown error"));
            } finally {
                updateRunning = false;
            }
        }, "sb-update").start();
    }

    private void runUpdate() throws Exception {
        updateMsg("Checking for updates\u2026");
        final String asset = wantedAsset();
        org.json.JSONObject rel = fetchJson(API_MIRROR);
        if (rel == null) rel = fetchJson(API_DIRECT);
        if (rel == null) { updateMsg("Could not reach the update server - try again later."); return; }
        String wantDigest = null, wantUrl = null;
        org.json.JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                org.json.JSONObject a = assets.optJSONObject(i);
                if (a != null && asset.equals(a.optString("name"))) {
                    wantDigest = a.optString("digest", "");
                    wantUrl = a.optString("browser_download_url", "");
                    break;
                }
            }
        }
        if (wantUrl == null || wantUrl.isEmpty() || wantDigest == null || !wantDigest.startsWith("sha256:")) {
            updateMsg("The update could not be verified - nothing was installed.");
            return;
        }
        if (!updateSourceAllowed(wantUrl)) {
            updateMsg("The update source looks wrong - nothing was installed.");
            return;
        }
        wipeUpdateLeftovers();
        java.io.File dir = new java.io.File(getFilesDir(), "updates");
        if (!dir.mkdirs() && !dir.isDirectory()) { updateMsg("No space for the update."); return; }
        java.io.File out = new java.io.File(dir, asset);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        java.net.HttpURLConnection conn = openValidated(wantUrl);
        if (conn == null) { updateMsg("The update download was blocked or failed - nothing was installed."); return; }
        try {
            java.io.InputStream in = conn.getInputStream();
            java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) { md.update(buf, 0, n); fo.write(buf, 0, n); }
            fo.close();
            in.close();
        } finally { conn.disconnect(); }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(java.util.Locale.US, "%02x", b));
        if (!("sha256:" + sb.toString()).equals(wantDigest)) {
            deleteQuietly(out);
            updateMsg("The downloaded update did not match the published checksum - nothing was installed.");
            return;
        }
        if (!sameSignerAsInstalled(out)) {
            deleteQuietly(out);
            updateMsg("The update is not signed with this app's key - nothing was installed.");
            return;
        }
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.pm.PackageInfo arch = pm.getPackageArchiveInfo(out.getAbsolutePath(), 0);
        android.content.pm.PackageInfo me = pm.getPackageInfo(getPackageName(), 0);
        if (arch == null || arch.versionCode <= me.versionCode) {
            deleteQuietly(out);
            updateMsg("You are already up to date.");
            return;
        }
        updateMsg("Update ready (v" + arch.versionName + ") - confirm the install.");
        android.net.Uri uri = android.net.Uri.parse("content://" + UpdateFileProvider.authority(this) + "/" + asset);
        Intent it = new Intent(Intent.ACTION_VIEW);
        it.setDataAndType(uri, "application/vnd.android.package-archive");
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(it);
    }

    /** Follow redirects by hand so EVERY hop is re-checked against the
        update-source rules (a CDN hop to anywhere else is refused). */
    private java.net.HttpURLConnection openValidated(String url) {
        try {
            String u = url;
            for (int hop = 0; hop < 5; hop++) {
                if (!updateSourceAllowed(u)) return null;
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(false);
                c.setRequestProperty("user-agent", "StreamBox-Update");
                int code = c.getResponseCode();
                if (code / 100 == 3) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) return null;
                    u = new java.net.URL(new java.net.URL(u), loc).toString();
                    continue;
                }
                if (code != 200) { c.disconnect(); return null; }
                return c;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private org.json.JSONObject fetchJson(String url) {
        try {
            if (!metaSourceAllowed(url)) return null;
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("accept", "application/vnd.github+json");
            c.setRequestProperty("user-agent", "StreamBox-Update");
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(c.getInputStream(), "utf-8"));
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            r.close();
            c.disconnect();
            return new org.json.JSONObject(b.toString());
        } catch (Exception e) { return null; }
    }

    /** The downloaded archive must carry THIS app's signing certificate -
        the pin that makes a tampered or cross-signed APK impossible to
        install through this flow (the installer enforces it for updates too,
        but here we can fail honestly BEFORE the prompt). */
    private boolean sameSignerAsInstalled(java.io.File apk) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.PackageInfo arch = pm.getPackageArchiveInfo(apk.getAbsolutePath(), android.content.pm.PackageManager.GET_SIGNATURES);
            android.content.pm.PackageInfo me = pm.getPackageInfo(getPackageName(), android.content.pm.PackageManager.GET_SIGNATURES);
            if (arch == null || me == null) return false;
            if (arch.signatures == null || arch.signatures.length == 0
                    || me.signatures == null || me.signatures.length == 0) return false;
            return java.util.Arrays.equals(arch.signatures[0].toByteArray(), me.signatures[0].toByteArray());
        } catch (Exception e) { return false; }
    }

    private static void deleteQuietly(java.io.File f) {
        try { if (f != null && !f.delete()) f.deleteOnExit(); } catch (Exception ignored) {}
    }
}
