The controls minimize themselves again when you stop touching them, and fullscreen now turns the phone sideways so the movie actually fills the screen.

What is new
- Auto-minimize is back and reliable: stop touching the screen for a few seconds and the control bar tucks itself away (it used to stay up forever after you left fullscreen - the idle timer was being cleared and never re-armed)
- Fullscreen rotates to LANDSCAPE on phones and tablets (web + both APKs): tap fullscreen and the movie fills the screen sideways, the way a 16:9 picture is meant to be watched. Leaving fullscreen returns the device to its own orientation. The app shell locks it natively (web rotation locks are unreliable inside Android WebViews), the website uses the Screen Orientation API - and on iPhone Safari (which can't rotate from a page) everything else still works
- The native Kotlin app rotates to landscape for the playback session too, and returns to the previous orientation when you leave the player (TVs keep their own orientation - their panel is landscape already)

Fixes
- The auto-minimize timer now re-arms after EVERY path that shows the bar (leaving fullscreen included) - verified by test: wake the bar, stop touching, it hides on its own
