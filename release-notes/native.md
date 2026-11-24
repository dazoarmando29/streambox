The update-flow rescue: the popup stays until you choose, the same update can never come back, and the cards fit on screen with their buttons always visible.

Fixes
- The startup update popup no longer flashes away after a second: the offer was being hidden by a state flip while the frame was still drawing. It now appears once per pending update and stays until you pick Download & Install or Later (verified on TV and phone: still up at 18s)
- The update loop is dead: after you install, the same update with the same description can never be offered again. The version check refuses stale offers, a just-updated app clears the previous install's saved offer on first open, and a release-parsing bug in the digest fallback (a "sha256:" prefix compared against a bare hash, never equal) is fixed so a release without its version marker can't re-offer the same build forever
- What's New and Update cards are compact now: only the release notes scroll inside their own bounded box while the buttons stay pinned on screen at every size - the old cards scrolled Download & Install and Start Watching out of reach on TVs and small phones
- The install-permission button no longer wraps three lines and clips its own text, and it flips back to "Download & Install" the moment the system grant lands (it used to keep warning about a permission you already gave)

Honest limits
- Google's own gates (Play Protect scan prompt, install-unknown-apps permission) still appear on a first install - those screens are Android's, not StreamBox's, and are walked in order with clear prompts
- This fix reaches you with THIS update: 0.101 and older can still flash or re-offer until every device lands on 0.102

Locked by native QA (update offer rules, dialog shapes, and the party gates must stay). Builds at 0.102.
