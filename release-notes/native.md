The StreamBox Subtitle Match Fix - the right subtitles, and one tap to perfect their timing.

What's fixed
- "Subtitles are one step faster than the voices" - FIXED. Root cause: the app trusted the most-downloaded subtitle file without checking whether it was timed for the same video cut (a file with an extra recap, logo or ad break runs a few seconds off - exactly one line ahead). The auto-picker now validates up to 3 candidate files against the REAL video duration, rejects corrupt or wrong-cut files, and keeps the most plausible one (downloads only break ties now)
- New: "Sync This Line" in the player's Subs panel - tap it at the moment the words on screen match what is being said, and any constant lead or lag is corrected instantly and remembered for the whole show. The existing -0.5s / +0.5s nudges remain for fine tuning
- When a file looks like a different cut, the app says so and points you at Sync This Line instead of leaving you guessing

Honest limits
- Captions embedded inside a provider's own player (some anime/K-drama sources) are the provider's own and cannot be re-timed by the app - our styled captions take over whenever a subtitle file is loaded
- The measured-speed subtitle clock (0.220) is unchanged and still fixes the speed/buffering classes
