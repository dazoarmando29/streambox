The StreamBox Subtitle Speed Fix - captions now move at the speed of the voices.

What is fixed
- "Subtitles so fast / ahead of the dialogue" on movies, TV shows, anime (AniPM) and K-dramas (VidBolt) - FIXED. Root cause: the caption clock free-ran at the CLAIMED playback speed and, worse, whenever the video buffered or the provider reported a wrong speed, the clock kept racing ahead while the picture was frozen. The clock now MEASURES the video's true speed from the provider's own position updates (measured beats claimed - verified: a provider claiming 1.5x while playing at 1x now holds captions at 1x), FREEZES with the picture the instant buffering stalls it (verified: zero drift through a 5-second stall), and re-measures instantly after seeks or speed changes (verified: real 1.5x playback tracks at 1.5x). Subtitle timing corrections remain remembered per release, and Sync This Line Now still fine-tunes anything left
- The same clock logic is QA-locked (four locks on the estimator) so this class can never regress on any title, and it is being mirrored to the native Android app

Also included in this train
- The full Smart Subtitle suite stays green (23/23), plus the anti-stutter StabilityGuard and the anti-delay clock suites

Honest limits
- Captions can only be as accurate as the position data a provider sends; where a provider embeds its own burned-in captions, those are the provider's own (the app says so) - our styled captions take over the moment a subtitle file is loaded
