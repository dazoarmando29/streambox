The phone player is finally designed like a real player - and this time it was checked by LOOKING at it on phone screenshots before shipping.

What is new
- One slim, centered control row on phones: play, -10s, +30s, CC, settings, fullscreen - all visible at once, centered under the seek bar (speed / quality / mute live in the settings sheet and the quality pills below). No more two tall button rows eating the picture, no more odd gaps between buttons
- The video owns the screen on phones: full-bleed edge-to-edge with the bar as a small glass overlay at its bottom, and fullscreen is truly edge-to-edge (no colored sliver down the side)
- tools/phone-shots.js: the design is now VERIFIED VISUALLY - real screenshots at iPhone and 320px widths (home, player, fullscreen) are captured and inspected before shipping, so "it looks wrong on my phone" is caught by us first

Fixes
- Root-caused the "page looks too big / clipped on small phones" bug that made everything feel off: narrow phones EXPAND the layout viewport when an element's min-content is wider than the screen (the player iframe's 300px intrinsic width, the unwrapped quality-pill row, the Motion Judder pill). The iframe now sits out of flow, both pill rows wrap, and the grid columns can shrink - at 320px the layout viewport now equals the screen exactly (verified)
- The global 44px touch-target rule was clamping every player button (even !important widths) - the compact phone bar now exempts it so buttons can actually shrink
- A stale duplicate CSS rule from the first pass pinned 10px button padding and broke the compact sizing - consolidated all phone bar rules into one place
- The floating TV-guide chip on phones is now a small glass button instead of a solid teal circle overlapping content
- The remote-cursor button (a TV tool) is gone from the phone control row
- The subtitle coach card compacts on phones (buttons fit one row)

Never-again: QA 155 locks the button-floor exemption and the min-content fixes; responsive-test now DETECTS layout-viewport expansion (it compares against the emulated screen width - the exact blind spot that let this through) and fails the build; phone-shots.js keeps the design inspectable.
