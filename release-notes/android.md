Every door opens something - guaranteed. The Watch Party buttons in the details modal (and everywhere else) had a silent bug that opened nothing; fixed at the root, covered by a new click-through test that clicks every door and fails if the screen does not change.

What is new
- Watch Party works from the movie/TV details modal again: the button used to call a bare open() that silently resolved to the browser's window.open (a blank tab on the web, a dead tap in the app) instead of opening the party panel - now every Party door (details modal, player sidebar, header) opens the room panel
- tools/click-test.js: a dead-click regression harness that boots the real site in headless Edge, clicks every interactive door (header, nav, hero, details modal, party panel), and asserts a visible state change each time - a button that opens nothing now fails the build
- The app's Back button now handles the party panel and About panel before the details modal (Back used to close the modal BEHIND an open party panel, then a second Back closed the app with the panel still up)
- QA locks the full contract: the party doors wiring, the no-window.open rule, and every JavaScript hook the APK injects must exist on the site (a missing hook used to swallow Back presses inside the app)

Fixes
- Closing the party panel returns focus to the visible party button (it used to target the hidden player-page button and strand focus)
- The click-test also validated the Escape layering: closing the party panel over a details modal keeps the modal open underneath
