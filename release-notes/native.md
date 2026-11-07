Update channels for every StreamBox app + the VidCore player mapping fix.

What is new
- /api/apk-latest: the WebView apps (full + lite) get the same edge-cached, rate-limit-proof update channel the native app has - one upstream GitHub fetch per 10 minutes per edge, stale-on-error fallback, so a house full of devices can never burn the API quota and miss updates

Fixes
- The mirror-health probe now checks the VidSpark player directly (vidspark.to) for VidCore - that is the URL the site actually loads since VidCore joined the full-sync set (its embed wrapper is only an ad layer around the player)
