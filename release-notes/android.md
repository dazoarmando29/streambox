The mobile player finally behaves like a real player: every control fits without scrolling, a second tap hides the bar again, double-tap seeks, and fullscreen is REALLY fullscreen everywhere.

What is new
- Double-tap to seek (phones, tablets, laptops, desktops, and both APKs): double tap the RIGHT side of the video = +30s, the LEFT side = -10s, with the same visual feedback as the skip buttons
- Real fullscreen on every device: the Fullscreen API now expands the whole player (video + our controls together) on phones, tablets, laptops and desktops - the old cinema mode was only a page overlay with the browser bars still showing. iPhones and TVs without element fullscreen keep the cinema overlay so the look matches, and leaving fullscreen (Esc / the OS back gesture) always lands you back cleanly
- Tap the video to hide the controls again: the second tap now collapses the bar (it used to stay up until an idle timer) - tap once for controls, tap again for a clean picture

Fixes
- The player control row WRAPS instead of scrolling: play, -10s, +30s, Skip, volume, speed, quality, episodes, CC, A/V and the rest are ALL visible on any width - no more horizontal scrolling to find hidden buttons (this was the "I can't see all of the buttons" bug)
- The keyboard/remote hotkeys card no longer clutters phones (there is no keyboard in your hands) - tablets, laptops, desktops and TVs keep it
- The player page tightens up on phones: compact Back button, smaller server pill row, denser settings card
- Verified again across 13 viewports (320px phones through 4K) x 5 screens: zero overflow, the control row never scrolls, the hotkeys card stays away from phone widths (tools/responsive-test.js)
