Watch Party subtitles follow the episode, AniPM returns to the party server list (native), and the outside Party door is join-only.

Fixes
- Watch Party episode changes now carry the RIGHT subtitles: when the room (or auto-next, or an episode pick) switches episodes, the old episode's subtitle file is cleared and the new episode's subtitles load - the reported bug showed an "S01E01" caption file playing over episode 5. Every episode-switch path (party switches, Next Episode, the watch grid, the episodes drawer) now refreshes the subtitle chain
- Native app: AniPM is back in the server list during Watch Party - party content opens anime titles WITHOUT their MAL/AniList mapping, and the server list only offers AniPM when that mapping resolves (so it was invisible). The party route now resolves the mapping before opening the player
- The Watch Party button OUTSIDE the player (movie/TV details) now shows the JOIN view (code + password) - creating a room only makes sense where a video is playing, which stays on the player page's button

Locked so it never happens again: all 4 episode-switch sites must refresh subs (QA 157), the details door must stay join-only and the player door create-only, and the native party route must pass the anime mapping (native QA 90)
