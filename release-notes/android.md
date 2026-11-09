Phones can drive the player again, the layout is verified clean from 320px phones to 4K, and joining a party drops you straight into the movie.

What is new
- Touch the video on a phone or tablet and the controls appear: the player video is a cross-origin iframe that swallows every touch (the reported "can't skip / can't pause" bug), so a transparent tap layer now sits over the video while the bar sleeps - one tap wakes play/pause, seek, skip and the rest of the controls, and it stands down the moment the bar is up so the provider player's own taps still work
- Joining or creating a Watch Party takes you straight to the movie: the room panel closes itself the moment there is something to watch (the room's content on join, the movie you created it for from the details modal) - no extra taps
- Verified across every screen size: 13 viewports from 320px phones through tablets, laptops, desktops and 4K TVs, on five screens each (home, details, party, search, player) - zero overflow, zero clipped layouts (tools/responsive-test.js runs this in the build)

Fixes
- The header no longer overflows on any phone: search/party/about/install collapse to icons on phones and in the tight laptop band, the wordmark shrinks away on the narrowest screens, the PWA Install button steps aside below 520px (browser menu / About remain the install paths), and the hero action row wraps instead of stretching the page
- The desktop tab strip only appears where it fits (xl+) - phones AND tablets use the bottom tab bar, which now covers the tablet sizes that used to squeeze the desktop nav off-screen
- The hero buttons wrap on small screens instead of forcing horizontal scrolling
