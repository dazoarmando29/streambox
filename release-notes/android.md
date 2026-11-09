Tap-to-show controls never vanish mid-reach anymore - and tapping hides only YOUR bar.

What was wrong (the reported bug): tap the video and the controls showed for about a second then minimized themselves, so the buttons could never be pressed. Root cause: the tap toggle decided what to do at TIMER time (300ms later) instead of tap time - so any tap on the bar that was ALREADY up (the one shown when the player loads, or a quick second tap before the first one landed) scheduled a HIDE. Your tap literally hid the bar 300ms later.

Fixes
- Waking is instant and unconditional: tap the video and the controls appear immediately - the tap can never hide them
- A tap on the load-time auto-bar now EXTENDS it (restarts the idle clock) instead of dismissing it - the bar is yours to press
- Tapping hides the bar only when you summoned it yourself (the requested tap-again-to-hide), and the hide is delayed slightly so a double-tap seek never also hides the bar
- Touch devices get 6 seconds of idle before the bar auto-hides (was 4 - too fast to aim at buttons), and every tap on a button keeps it alive
- Regression-locked in the click-test: instant wake, load-bar taps extend, summoned-bar tap hides, double-tap seeks, bar survives seeks
