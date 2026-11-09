Fullscreen REALLY rotates now - the "Rotate Your Phone" hint is gone on Android.

What was wrong (from your screenshot): tapping fullscreen showed the player's own "Rotate Your Phone - flip to landscape" hint instead of rotating. Root cause: Chrome/Brave only honor the screen.orientation.lock() from INSIDE fullscreen - our lock ran BEFORE the fullscreen was granted (silently rejected) and never ran again. And inside the APK WebView the JS lock is unreliable at all.

Fixes
- The landscape lock now runs at every moment it is ALLOWED to work: on entering cinema, again the instant the fullscreen promise resolves, again on the fullscreenchange event, with a 500ms retry ladder while cinema is active (the pre-fullscreen attempt is kept only as a cheap first try)
- The app (WebView APK) gets a second, independent channel: the page signals cinema orientation through a brief "sb:landscape" title marker that the shell watches natively (and "sb:portrait" to restore) - this covers WebViews that refuse the JS lock AND the CSS-cinema path where no native fullscreen event ever fires. The tab title is restored right after so it never stays "sb:..."
- Leaving fullscreen restores the device orientation through both channels and re-arms the auto-minimize (locked from the previous release)
- The native Kotlin app already rotates for the whole playback session (0.96) - verified and its QA lock stays

Verified by test: fullscreen signals landscape through the API + the shell marker, exit unlocks and restores the title. QA 156 now locks the re-run wiring (fullscreen promise + fullscreenchange + retry ladder) and the title-marker channel in both the page and the shell - a future change that reintroduces the pre-fullscreen-only lock fails the build.

Note for iPhone Safari: iOS does not allow pages to rotate the device - the player's rotate hint is the fallback there. Android phones/tablets (web + both APKs) now rotate automatically.
