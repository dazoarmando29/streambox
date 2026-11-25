The StreamBox Smart Subtitle Update - the right subtitles, at the right time, automatically.

What's new
- Smart Subtitle Chooser: subtitle selection is no longer "most downloads wins". Candidates are ranked by release and edition match (WEB-DL/BluRay/HDTV, REPACK/PROPER, Extended/Director's Cut...), verified history and real timing evidence - download popularity is only a tiebreaker. Wrong episodes, wrong languages, forced-only and commentary tracks are rejected outright. Confidence shows as honest labels (Recommended Match / Previously Confirmed / Different Release / Timing Unverified) - never an invented accuracy percentage
- Adaptive Auto-Sync: constant delays, slow drift across a two-hour movie, and different cuts/intros are corrected automatically - constant offset, two-point drift calibration (frame-rate mismatches like 23.976 vs 25 snap only with real evidence), and segmented piecewise correction for material with added intros or recaps. Corrections are remembered PER RELEASE: your sync for one cut never leaks onto another. Manual taps always win over automatic guesses
- Your controls in Subs > Smart Subtitles: Find Better Match, Resync Subtitles, Subs Too Early / Too Late, Sync This Line Now (one-tap fix while a line is on screen), Remember This Match, Reset Synchronization, Dialogue Sync (optional, consent-gated, owned media only - honestly reports "unavailable" on provider players where audio cannot be reached)
- Candidate files are validated before applying (corrupt, overlapping, duplicate or ad-filled files fall through to the next choice), with a strict budget of two downloads per automatic attempt and full quota protection across Wyzie/TSDB/BetaSeries

Honest limits
- Dialogue-listening sync needs a speech service you configure yourself (off by default, nothing transmitted otherwise) and cannot run inside third-party player frames - the app says so instead of pretending
- Automated verification: 23/23 subtitle suite tests, plus the earlier subtitle-clock and stability suites, all green before release

Also in this train (native app 0.210): the native subtitle clock now matches the web estimator exactly - rate-correct, immune to late provider messages, never yanks captions backward.
