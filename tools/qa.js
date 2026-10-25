#!/usr/bin/env node
"use strict";
/* StreamBox QA gate. Run before every push and in CI (android-apk.yml).
   Checks the invariants that have bitten us before so they can never
   silently regress:
   1  inline scripts + sw.js syntax (node --check)
   2  BUILD_NUM == version.txt
   3  no raw emoji codepoints in index.html (\u escapes in JS, entities in
      markup, CSS escapes in <style> -- TV fonts render them as tofu)
   4  never auto-mute
   5  CineSrc quality values are only ""/1080/720/480 -- "2160" never sent
   6  PIN is SHA-256 only (no weak fallback, no plaintext storage)
   7  OpenSubtitles stays gone
   8  AniPM routes are /dub|/sub only (never /en); Dragon Ball = dub
   9  ANIME_BAD_CATALOG keeps Doraemon away from VidBolt
   10 landmarks present
   11 DOM: no duplicate ids; every literal id reference resolves
   12 inline handlers call functions that exist
   13 workflow YAML stays pure ASCII (console/log safety)
   14 manifest.webmanifest parses
   15 native Kotlin: volume icons stay AutoMirrored (deprecated Icons.Filled
      .Volume* warned in build 0.8 QA - mirror-safe forever)
   16 native SB_HOOK bridge JS parses (node --check on the extracted shim)
   17 native HLS slot stays dormant (pre-build): the debug test asset is
      the ONLY stream URL in native code and lives in HlsPlayer.kt, the
      website never references it, mirrorsFor never lists "hls", every way
      into the slot is gated on the hidden AppEnv.hlsTest flag, and the
      build never opts in to media3's UnstableApi (doc marker - the opt-in
      flag itself warns)
   18 native sources stay free of the C-to-o corruption signature (a 0.23
      bulk-replace flattened its pairs into characters and rewrote every
      capital C in App.kt - Column->oolumn, Color->oolor)
   19 native CineSrc quality cycle matches the QLEVELS contract: only
      ""/1080/720/480 - 2160 is never sent anywhere
   20 circular focus targets draw circles only: round buttons use
      focusRingCircle (pure drawCircle traces) and the app root keeps the
      default indication off (NoIndication) - the 0.34 TV bug where square
      highlight plates showed behind round rail/X buttons
   21 player resume boots at the SAVED position: the WebView/HLS boots pass
      resumeAt (derived from Store progress) - the 0.34 bug where the boot
      hardcoded 0f and Continue Watching restarted the title
   22 native never hardcodes unreported quality claims: the design mocks
      carry 4K/Atmos/HDR/Dolby chips but the house rule is "never label 4K
      unless reported", so those strings may never appear as native labels
      (real player-reported ladders like AniPM heights still render)
   23 the no-media watchdog hops only after 30s of TOTAL silence (media
      traffic + provider protocol keep it patient) - the 0.36 bug where a
      fixed 30s timer threw slow AniPM/CineSrc loads over to VidRift
   24 every TMDB date read goes through dateOf: org.json optString renders
      JSON null as the literal "null", which sorts AFTER today and stamped
      old One Piece seasons as COMING SOON (0.38 fix)
    25 VidBolt plays unsandboxed and media is never blocked: the wrapper
       iframe must never carry a sandbox attribute (VidBolt renders
       "Playback disabled" when sandboxed) and the Ad Shield must check
       media extensions BEFORE any ad-host block - playback always wins
    26 VidBolt gate shim is document-level and player JS bodies are NEVER
       rewritten: 0.45/0.46 intercepted the player MODULE and the video
       stuck on the play screen (poster never went away). The shim rides
       vbShimmedDoc (inline, before their module evaluates) plus the shell
       injector - both race-safe (real-http-document guard + __sbGate
       marker; the iframe's transient about:blank doc has a head too and
       swallowed the old poll)
    27 SB_HOOK rides the wrapped shell MARKUP so it is attached before the
       iframe can emit anything (onPageFinished injection could land after
       the player was already talking)
    28 silent-start kicks: a VidBolt/VidZen frame that reports nothing gets
       an explicit play nudge before the 30s idle watchdog hops away
       (website _playKickT parity - TV WebViews can swallow autoplay)
    29 Japanese animation types as ANIME everywhere and the label always
       wins over row overrides: search/multi, trending AND home's Popular
       Shows can never stamp an anime as TV, while a Korean drama is never
       anime (the Anime-tab Lie to Me mistake)
    30 screens are keyed per title / play session: un-keyed remember kept
       the PREVIOUS title's season/episode/saved/ids across detail-similar
       navigation and replays ('episode 1' opened S2 E1), the episode rail
       clears before every season fetch (no stale rail), and a season that
       does not exist on the title snaps back to a real one
    31 the TV hero tells the whole story: overview never clamps, the box
       grows with its content, and focusing any CTA brings the WHOLE
       details block into view (the pill-only scroll left the title cut
       above the fold - 'details in full')
    32 poster look = artwork only: artworkRing traces the poster while the
       WHOLE card (artwork + name) is the focus/click target, so the default
       scroll carries names into view - no full-card ring, no reveal calls
    33 Continue Watching is tab-scoped: the anime tab never shows movie/TV
       progress and the movie/TV tabs never show the others - Home and My
       List show everything
    34 search state is hoisted into StreamBoxApp: Back from a detail lands
       in the search results again (BrowseScreen's own remember died with
       the composition), while tab switches and the player's Netflix-style
       exit clear it on purpose
    35 VidBolt quality plumbing is provider-reported only: rows come from
       its mobile-panel delegation (option + value echoed verbatim) or its
       resolve files per language (sourceQuality by the exact label) - no
       invented ladder is ever built (qa #22 keeps fake 4K chips dead)
    36 every preview build signs with the SAME committed keystore: CI used
       to mint a fresh debug key per run, so each APK had a new signature
       and 'update' failed with an install error
    37 native versionCode stays FROZEN at 50 so any build installs over any
       other one - forward updates AND rolling back to an older APK work
       from the package installer (the feature version lives in versionName)
    38 the app boots through a cinematic splash and opens a What's New card
       ONCE per fresh update (Store.lastSeenVersion vs VERSION_NAME)
    39 browse speed: rows load in parallel and paint as they land, and a
       disk feed cache (30 min TTL) paints a cold start instantly while
       the network pass revalidates
    40 player speed: the injected VidBolt page is session-cached, the shell
       preconnects every provider origin (server hops land on warm TLS),
       and track discovery fetches resolve ONCE per episode
    41 catalog calls ride a short-lived response cache (120s) so re-opening
       a detail or re-browsing a section never pays the network twice
    42 motion clarity + judder: the hook measures the REAL content frame
       rate from decoded frames and the player matches the display mode to
       an integer multiple of it (24fps on 120Hz runs 5:5 - no 3:2 pulldown)
       at native resolution, highest matching refresh wins (fast motion
       stays fluid), and the display's own mode is restored on exit
    43 smooth rows: rows/grids NEVER bringIntoView - an oversized reveal
       rect aligned the OPPOSITE edge on every press (the up-then-down
       zigzag + shake); names ride the whole-card target, hero reveal is
       one-shot per hero
    44 VidBolt subtitle rows carry their command INDEX (engine tracks ++
       resolve subtitles, matching their H list) - label values made both
       command paths no-op and captions never appeared
    45 skip pills speak plainly and honestly: names are "Skip Intro" /
       "Skip Outro" / "Next Episode" with no countdown suffix, and
       credits/preview ranges carry a TAIL guard - one open-ended start=0
       entry matched every timestamp and the Next Episode pill sat over the
       S1E1 intro. 0.73: a RANGED credits entry is the ending song ("Skip
       Outro" seeks past it) and only OPEN-ENDED credits is "Next Episode"
    46 server auto-switch leads with VidBolt on every chain and never hops
       to VidRift (no pause button there - hopping strands the viewer)
    47 the Browse tab: pick the KIND (Movies / TV Shows / Anime / K-Dramas),
       then genre + year chips (years start at 2026 and go below) over the
       FULL paginated catalog
    48 anime can never render as TV: the hero badge derives from content,
       saved titles keep their genreIds, and a restored Japanese-animation
       title re-types itself anime
    49 player routing honors isAnime - Japanese animation rides the anime
       route even when an old stored label says tv
    50 episode status lives in ONE pinned right column: the title row is
       full width so every runtime / NEW / COMING SOON label shares the
       same right edge (straight down), runtime rides one line and Coming
       Soon keeps its stacked PH air date + countdown
    51 Korean dramas live in their own KDrama tab beside Anime - the TV
       Shows strip never carries a K-Drama chip again
    52 the zigzag lock: Right may never leave a card row or chip rail (focus
       used to yank up to the chips and back - every screen with a rail had
       it while Home never did); Left leaves ONLY to the registered TV nav
       rail (NavEscape) so the menu is always one press away; Up/Down stay
       free
    53 one All chip per genre strip (GenreBar prepends it) and a compact
       rail so every section tab fits on screen (Browse was falling off)
    54 anime season routing: split franchises resolve the season's OWN
       entry by its air year (Hajime no Ippo S2 = MAL 5258, not 263) and
       episodes number against that entry; combined entries keep absolute
       numbering. The old code sent the show's first-year entry with the
       per-season number - season 2 episode 1 played S1E1
    55 next episode never steps past a season's real end (blind +1 built
       dead routes on finales) and never into an unaired episode - a
       finale rolls to the next season's E1 when it exists
    56 K-drama VidBolt defaults: Korean dramas play the TAGALOG dub by
       default (Korean when the release carries none) and ride VidBolt's
       OWN English subtitles - the Wyzie/TSDB/BetaSeries chain never
       auto-loads or restores over them, and a manual pick stands the
       auto-behavior down
    57 Left escapes rows/grids to the TV nav rail: rowNavLock's Left exit
       resolves to the NavEscape target the shell registers (the active
       tab) instead of dead-ending or yanking to a chip strip - Right stays
       locked so the zigzag can never return
    58 the Browse tab is ONE scroll surface (chrome rides the grid as
       spanning headers - the fixed chrome + 200dp grid window clipped every
       poster row mid-artwork on TV), poster names render white/bold with a
       meta line, and the builder's "dazzo" credit sits bottom right
    59 provider episode events never steer the load (0.65): load() captures
       the target season/episode BEFORE the posted navigation (a queued
       episodechange used to swap the URL under advanceEpisode - K-drama
       Next Episode opened a different video), and every provider switch
       (VidBolt/VidRift/AniPM episodechange, any Up Next) enters state ONLY
       through adoptProviderEpisode: echoes of the tracked pair are no-ops,
       settling noise inside EP_SYNC_GRACE_MS after our load/boot is
       dropped, a number outside the real season range is dropped, and
       nextup runs advanceEpisode() (season-end + unaired guards) instead
       of trusting raw provider numbers
    60 the player UI stays OUT of PlayerScreen's method (0.65): Box/Column/
       Row are inline composables, so trailing content lambdas compile into
       the caller's method and the whole player tree hit the JVM 64KB
       method limit (Method too large). Every big layer (topHud, caption,
       paused, bar, episodes, subs) lives in a named composable lambda VAL
       whose body compiles to its own class - never inline big UI trees
       back into the function
    61 auto-next is OURS (0.66): the embed's own Up Next used to resolve a
       different video when it auto-advanced (K-Drama VidBolt case) - so
       Auto-play Next takes the transition on ended (advanceEpisode through
       the canonical route, one per load, grace-guarded) and any provider
       switch that IS adopted re-opens through our own load() - the same
       deterministic /tv/{id}/{s}/{e} as the Next Episode pill. nextup and
       ended share the EP_SYNC_GRACE_MS guard so racing events never
       double-step
    62 auto-next runs on EVERY series (0.67): the embeds do NOT reliably
       self-advance - episodes sat stuck at the end (Lie to Me) - so the
       ended takeover covers all series mirrors incl. AniPM (only the HLS
       test slot and movies are excluded), Auto-play Next defaults ON on a
       fresh pref key (a stale saved OFF predates working auto-next), and
       adoptProviderEpisode restarts the sync clock so any provider that
       DOES self-advance can never double-step with ours
    63 the stored language heals before routing (0.68): a legacy Continue
       Watching record predates the lang field - its Title routed a Korean
       drama to CineSrc and BOTH symptoms appeared (Tagalog never selected
       AND the Tagalog audio option gone). contentLang is authoritative and
       heals from TMDB original_language before the first load picks a
       mirror (lang-less TV boots wait for it), the mirror realigns to
       VidBolt for ko, save() writes the healed value so the record
       self-heals, and the K-Drama rules read contentLang - never the raw
       title.lang
    64 VidBolt track discovery always finds the REAL tracks (0.69): the old
       0/5/15s polls all depended on fw.__VB__ surfacing inside the iframe -
       on a slow TV every poll missed and the audio list came up empty (the
       Tagalog dub "disappeared" on Lie to Me ep 8). vbRoute() reads the
       EMBED URL first (known the moment the wrapper exists) and a retry
       loop keeps asking until the tracks land - the resolve fetch itself
       stays once-per-episode
    65 resolve hardening + weak-TV speed (0.70): the 0.69 discovery queried
       /api/resolve BEFORE their player bootstrap and CACHED the empty
       answer - the Tagalog dub vanished everywhere. The fetch now waits
       for fw.__VB__ (the session that fills the sources), runs ONE in
       flight per episode (VB_PENDING - no slow-wifi fetch storms that also
       slowed video starts), caches ONLY answers with real sources (empties
       retry), publishes only on real changes (no recomposition spam), the
       splash beat is 700ms not 1200, and TV image crossfades are off (the
       weak box decodes, it does not need to blend)
    66 Tagalog-first for K-Dramas (0.71): VidBolt's catalog rotates (the
       Tagalog dub was there and later gone from every server - verified:
       the servers carry English/Korean/Spanish/Portuguese and Filipino
       SUBTITLES only). The boot now asks for Tagalog via their documented
       audio option, the hook SWEEPS every server (only=) and switches to
       the first one carrying a Tagalog/Filipino dub so the app snaps back
       the moment any server has it, Korean stays the house fallback, and
       the panel says so honestly instead of leaving the user hunting
    67 playback stutter + nav-bar lag (0.72): the player timeline runs in
        its OWN recomposition scope (the timeupdate heartbeat redraws only
        the time text + slider - the old whole-bar recomposition over the
        live video stuttered on weak TV boxes), the caption lookup binary-
        searches the sorted cue list instead of scanning it every tick, and
        the tab feeds warm in the background behind the home screen (the
        first-visit fetch behind a spinner was the laggish nav) - pausing
        whenever the player owns the screen so playback keeps the line
    68 skip timestamps are REAL (0.73): AniSkip is keyed by the MAL ENTRY -
        split franchises carry one entry per season (Hajime no Ippo S2 =
        MAL 5258, not 263) and combined entries number absolutely (epBase).
        The old call sent the title's first-year entry with the per-season
        number, so the timings belonged to a different episode on every
        other anime. The three sources merge into ONE clean set per kind:
        duration-calibrated answers (episodeLength / duration matching) win,
        overlapping losers are dropped, junk fails the real-stream sanity
        checks - a stale first-listed range can never shadow the real one
    69 auto-skip SEEKS past (0.73): recap/intro/outro/preview skip forward
        inside their ranges and auto-skip NEVER auto-advances - an open-
        ended credits range is the manual Next Episode pill and the chaining
        happens through auto-next at ended, so post-credits scenes always
        survive the skipped outro
    70 the timeline HIGHLIGHTS the skip marks (0.73): recap/intro/outro/
        preview ranges paint as colored segments on the track plus a legend
        line, so the timestamps are visible before they arrive
    71 seek reliability (0.73/0.74): a burst of back-seeks buried VidBolt
        in in-place seek commands - its player blacked the surface, dropped
        audio and froze until the video was exited. Bursts COALESCE to one
        seek per 450ms window (trailing target wins) - the stall class
        itself is owned by the watchdog ladder now (75/76)
    72 the Another World genre (0.73): the one VIRTUAL chip leads every
        Browse kind and rides real TMDB KEYWORDS (isekai / reverse isekai /
        reincarnation / another world / other world / parallel world -
        verified ids) through discover with_keywords - never invented ids
        and never a fake provider route
    73 the detail screen shows the season's shape (0.73): the last episode
        wears a SEASON FINALE line and a note above the episodes names the
        finale episode and the NEXT season (with its count, or Coming Soon)
        - and when no next season exists nothing is invented
    74 thank-you screen + Continue Watching lifecycle (0.73): when the
        season has nothing left to play the player says "Thank you for
        watching" with More Like This (real TMDB similar) and the record
        goes DONE - out of Continue Watching - and the row COMES BACK
        rolled to the first newer aired episode the moment one exists, so
        the row always opens the new episode itself
    75 the FREEZE self-heals (0.74): "after some minutes it stutters, then
        mutes, then the picture freezes and seeks never move it" hit every
        provider (AniPM, CineSrc, VidBolt) because the shared stack is our
        WebView shell. While we think the video is PLAYING, the provider-
        REPORTED position must keep moving - a frozen position walks a
        ladder: nudge play + re-send the seek, then reopen at startAt with
        the user's VOLUME restored (a dying page may have muted itself -
        never auto-mute), then rebuild. The JS frame-gap detector
        ("video:stall": frames stop for 5s while unpaused) jumps the queue
        when the clock races on over dead frames. Pause, load windows and
        seekTo-written positions are never stalls
    76 a killed render process is a ZOMBIE (0.74): Chromium kills the
        render process under memory pressure and the WebView can never be
        revived - evaluateJavascript becomes a no-op and even load() dies
        silently. onRenderProcessGone is handled: the view leaves through
        onRelease (destroy), key(webGen) builds a NEW WebView, the factory
        auto-boot is first-generation only, and the reopen effect resumes
        at the live position with the user's volume
    77 weak-box pressure stays low (0.74): the 1s hook slowed to every 5th
        tick once a video is live, the fps scan retires after it attaches
        (the rVFC loop stays only as the frame pump), timeupdates thin to
        ~4Hz on BOTH sides of the JS bridge, and onTrimMemory drops the
        shim page cache so the render process keeps its headroom
    78 the slow-WiFi autopilot (0.75): a stall or a slow first start
        lightens the stream ONE rung - and ONLY through ladders the
        provider itself reported (AniPM's own heights, VidBolt's resolve
        quality labels with their real MP4 source kinds, CineSrc's
        documented ""/1080/720/480 cycle). A real progressive MP4 at or
        below the rung is preferred over same-quality HLS segments (one
        steady stream instead of many small fetches). Session-only (never
        persisted - a stall may lighten playback but never pins a viewer
        below HD forever), one honest note, and an unknown ladder is a
        clean no-op
    79 the Crisp picture pass (0.75): always-on perceptual sharpening +
        vividness - an SVG unsharp convolve and a CSS filter injected as
        STYLE ONLY into the page and its same-origin player frames (the
        0.45/0.46 lesson: provider player JS bodies are NEVER touched).
        It stands down while the slow-network autopilot owns the pipeline
        and never carries unreported quality claims (qa 26)
    80 the next-episode warm start (0.75): the VidBolt embed page
        prefetches into the shim cache ONCE per episode after 45s of
        stable playback - never on slow sessions (the line belongs to the
        video), so the auto-next hop lands on a cache hit
    81 the player keeps its states in ONE holder (0.76): the0.65 lesson
        (JVM 64KB method limit) moved the big layers out, but by 0.75 the
        remaining local remember() preambles + per-state captures still
        overflowed PlayerScreen()'s register file (311 registers) - D8
        emitted a primitive move over a boxed Integer and ART rejected the
        whole CLASS ("copy-cat" VerifyError). The TV died at BOOT because
        TvLegend pulls KeyChip from this class, so any future VerifyError
        there is an instant TV black-screen. The states live on one
        remembered PlayerMem (val pm) and the method stays small (77
        registers) - never reintroduce a field of bare `var x by remember`
        inside PlayerScreen
    82 anime availability = the AniPM DROP (0.77): the old model flipped
        playability at 00:00 PH of the TMDB air date - up to a day BEFORE
        the Japanese release that AniPM follows (Dragon Ball Super: the row
        went playable while the provider still 404d). The embed route the
        player already navigates to answers HEAD 200 when the episode
        exists and 404 before the drop, so anime boundary episodes start
        LOCKED and unlock only when the provider has them ("Waiting for the
        AniPM drop" is the honest line), an early drop unlocks early, the
        unreachable-probe fallback flips the day AFTER the date, and the CW
        new-episode revival never returns a 404 episode. Non-anime keeps
        its TMDB dates. Metadata only - a status code, never a stream */
 const fs = require("fs");
const os = require("os");
const path = require("path");
const cp = require("child_process");
const ROOT = path.resolve(__dirname, "..");
// Normalize line endings (0.68 pitfall): git autocrlf checkouts deliver
// CRLF and every \n-anchored regex silently fails on \r\n - the gate must
// judge CONTENT, never the checkout's line endings. Every file read in this
// gate goes through qaRead for exactly that reason.
const _readFileSync = fs.readFileSync.bind(fs);
const qaRead = (p) => _readFileSync(p, "utf8").replace(/\r\n/g, "\n");
const read = (f) => qaRead(path.join(ROOT, f));
// The 0.76 state-holder renamed the player states to pm.x - the behavioral
// invariants below still judge the same shapes, so player reads normalize
// the qualifier away (checks that assert the HOLDER itself use raw qaRead).
const qaPlayer = (f) => qaRead(f, "utf8").replace(/\bpm\./g, "");

let fails = 0, warns = 0;
const bad = (m) => { console.log("FAIL  " + m); fails++; };
const ok = (m) => console.log("ok    " + m);
const warn = (m) => { console.log("WARN  " + m); warns++; };

const html = read("index.html");
const sw = read("sw.js");

/* ---------- 1. syntax: node --check every inline script + sw.js ---------- */
function inlineScripts(src) {
  const out = [];
  const re = /<script\b([^>]*)>([\s\S]*?)<\/script>/gi;
  let m;
  while ((m = re.exec(src))) {
    const attrs = m[1] || "";
    if (/\bsrc\s*=/.test(attrs)) continue;
    out.push({ code: m[2], line: src.slice(0, m.index).split("\n").length });
  }
  return out;
}
const scripts = inlineScripts(html);
if (!scripts.length) bad("no inline scripts found in index.html");
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "sbqa-"));
scripts.forEach((s, i) => {
  const p = path.join(tmp, "inline" + i + ".js");
  fs.writeFileSync(p, s.code, "utf8");
  const r = cp.spawnSync(process.execPath, ["--check", p], { encoding: "utf8" });
  if (r.status === 0) ok("node --check inline script " + i + " (line " + s.line + ")");
  else bad("inline script " + i + " (line " + s.line + ") syntax error:\n" + r.stderr);
});
{
  const p = path.join(tmp, "sw.js");
  fs.writeFileSync(p, sw, "utf8");
  const r = cp.spawnSync(process.execPath, ["--check", p], { encoding: "utf8" });
  if (r.status === 0) ok("node --check sw.js");
  else bad("sw.js syntax error:\n" + r.stderr);
}

/* ---------- 2. build numbers in sync ---------- */
const bn = html.match(/BUILD_NUM\s*=\s*(\d+)/);
const vt = read("version.txt").trim();
if (bn && bn[1] === vt) ok("BUILD_NUM " + bn[1] + " == version.txt");
else bad("BUILD_NUM (" + (bn && bn[1]) + ") != version.txt (" + vt + ")");
if (!/^\d+$/.test(vt)) bad("version.txt is not a plain integer: " + vt);

/* ---------- 3. emoji discipline ---------- */
const EMOJI = /[\u{1F000}-\u{1FAFF}\u{2190}-\u{21FF}\u{2300}-\u{23FF}\u{25A0}-\u{25FF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{FE0F}\u{200D}]/u;
const rawEmoji = [];
html.split("\n").forEach((L, i) => { if (EMOJI.test(L)) rawEmoji.push(i + 1); });
if (!rawEmoji.length) ok("no raw emoji codepoints in index.html");
else bad("raw emoji in index.html at lines " + rawEmoji.slice(0, 25).join(",") + (rawEmoji.length > 25 ? " (+" + (rawEmoji.length - 25) + " more)" : "") + " -- use \\u{} in JS, entities in markup");

/* ---------- 4. never auto-mute ---------- */
if (/\.muted\s*=\s*true|muted\s*:\s*true/.test(html)) bad("auto-mute pattern found (.muted = true / muted: true)");
else ok("no auto-mute patterns");

/* ---------- 5. CineSrc quality values ---------- */
const ql = html.match(/const QLEVELS\s*=\s*(\[[^\]]*\])/);
if (ql) {
  const vals = [...ql[1].matchAll(/v:\s*"([^"]*)"/g)].map((m) => m[1]);
  const allow = ["", "1080", "720", "480"];
  const badVals = vals.filter((v) => !allow.includes(v));
  if (!badVals.length && vals.length === 4) ok("QLEVELS is only \"\"/1080/720/480");
  else bad("QLEVELS carries unexpected values: [" + vals.join(",") + "]");
} else bad("QLEVELS constant not found");
const cineUrls = [...html.matchAll(/cinesrc\.st[^\"'\n]*/g)].map((m) => m[0]);
if (cineUrls.some((u) => /2160/.test(u))) bad("a cinesrc.st URL contains 2160 -- never send 2160 to CineSrc");
else ok("no 2160 in cinesrc.st URLs");
if (/"quality=2160|quality=\$\{[^}]*2160/.test(html)) bad("quality=2160 sent on an embed URL");
else ok("no quality=2160 on embed URLs");

/* ---------- 6. PIN is SHA-256 only ---------- */
if (/djb2/.test(html)) bad("weak djb2 PIN hash fallback present -- PIN must be SHA-256 only");
else ok("no weak PIN hash fallback");
if (!/function _sbSha256Hex/.test(html)) bad("_sbSha256Hex fallback missing (PIN must hash even without crypto.subtle)");
else ok("_sbSha256Hex fallback present");
if (/DEFAULT_PIN_HASH\s*=\s*"[0-9a-f]{64}"/.test(html)) ok("DEFAULT_PIN_HASH is a 64-hex SHA-256");
else bad("DEFAULT_PIN_HASH is not a 64-hex SHA-256 hash");
const pinSets = [...html.matchAll(/localStorage\.setItem\(\s*"streambox_pin"\s*,\s*([^)]+)\)/g)].map((m) => m[1].trim());
const pinBad = pinSets.filter((x) => !/pinHash\(|DEFAULT_PIN_HASH/.test(x));
if (pinBad.length) bad("streambox_pin stored with non-hash value: " + pinBad.join(" | "));
else ok("streambox_pin stored only via pinHash()/DEFAULT_PIN_HASH (" + pinSets.length + " sites)");
if (/\d{6}/.test((html.match(/DEFAULT_PIN_HASH[^\n]*/) || [""])[0].replace(/[0-9a-f]{64}/, ""))) warn("six digits near DEFAULT_PIN_HASH");

/* ---------- 7. OpenSubtitles gone ---------- */
if (/opensubtitles/i.test(html + sw)) bad("OpenSubtitles reference found -- it is retired");
else ok("OpenSubtitles absent");

/* ---------- 8. AniPM routes + house dub rule ---------- */
const anipmUrls = [...html.matchAll(/ani\.pm\/embed\/[^`\"'\n]*/g)].map((m) => m[0]);
if (anipmUrls.some((u) => /\/en\b/.test(u))) bad("AniPM /en route present -- /en 404s, only /dub and /sub are valid");
else ok("no AniPM /en routes");
const langDef = html.match(/function animeLangDefault\([\s\S]*?\n\}/);
const avRules = html.match(/const ANIME_AV_RULES\s*=\s*\[([\s\S]*?)\];/);
if (langDef && avRules) {
  const rets = [...langDef[0].matchAll(/return\s+"([^"]*)"/g)].map((m) => m[1]);
  const hitLangs = [...avRules[1].matchAll(/lang:\s*"([^"]*)"/g)].map((m) => m[1]);
  const all = rets.concat(hitLangs);
  if (all.length && all.every((r) => r === "dub" || r === "sub")) ok("AniPM route lang values are only dub/sub");
  else bad("AniPM route lang value outside dub/sub: " + all.join(","));
} else bad("animeLangDefault or ANIME_AV_RULES not found");
if (/dragon\\s\*ball[\s\S]{0,120}lang:\s*"dub"/.test(html)) ok("Dragon Ball = English dub house rule pinned");
else bad("Dragon Ball dub house rule missing from ANIME_AV_RULES");

/* ---------- 9. catalog-fault pins ---------- */
const badCat = html.match(/const ANIME_BAD_CATALOG\s*=\s*\[([\s\S]*?)\];/);
if (badCat && /doraemon/i.test(badCat[1]) && /vidbolt/.test(badCat[1])) ok("ANIME_BAD_CATALOG reroutes Doraemon away from VidBolt");
else bad("ANIME_BAD_CATALOG missing the Doraemon -> AniPM pin");

/* ---------- 10. landmarks ---------- */
const LANDMARKS = ["MAIN_PLAYERS", "TV_REMOTE_OK", "NO_SANDBOX", "loadPlayer", "setMirror", "updateMirrorUI",
  "paintBarGroups", "ANIPM_ORIGIN", "anipmSend", "anipmHello", "anipmArmStartRetry", "loadAnipmPlayer",
  "anipmProgress", "BOLT_ORIGIN", "boltSend", "boltSeekTo", "boltSkipTo", "boltArmStartRetry",
  "loadVidboltAnime", "armStuckWatchdogs", "stuckFallback", "stuckRecommend", "midPlayRescue", "qDropAuto",
  "saveProgress", "anipmIds", "scorePick", "pickYear", "animeRematch", "ANIME_AV_RULES", "ANIME_BAD_CATALOG",
  "seekRow", "btnRow", "ctlBar", "epDrawerBtn", "ctlMute", "ctlHome", "ctlFs"];
const missL = LANDMARKS.filter((l) => !html.includes(l));
if (!missL.length) ok("all " + LANDMARKS.length + " landmarks present");
else bad("missing landmarks: " + missL.join(", "));

/* ---------- 11. DOM id hygiene ---------- */
const ids = new Set();
for (const m of html.matchAll(/\bid\s*=\s*"([^"]+)"/g)) ids.add(m[1]);
for (const m of html.matchAll(/\bid\s*=\s*'([^']+)'/g)) ids.add(m[1]);
for (const m of html.matchAll(/\.id\s*=\s*["']([^"']+)["']/g)) ids.add(m[1]);
for (const m of html.matchAll(/setAttribute\(\s*["']id["']\s*,\s*["']([^"']+)["']/g)) ids.add(m[1]);
const refs = new Map();
for (const m of html.matchAll(/getElementById\(\s*["']([^"']+)["']\s*\)/g)) refs.set(m[1], (refs.get(m[1]) || 0) + 1);
for (const m of html.matchAll(/querySelector(?:All)?\(\s*["']#([A-Za-z][\w-]*)["']\s*\)/g)) refs.set(m[1], (refs.get(m[1]) || 0) + 1);
const missingIds = [...refs.keys()].filter((x) => !ids.has(x)).sort();
if (!missingIds.length) ok("all " + refs.size + " literal id references resolve");
else missingIds.forEach((x) => bad("id referenced but never defined: #" + x + " (x" + refs.get(x) + ")"));
const idCount = {};
for (const m of html.matchAll(/\bid\s*=\s*["']([^"']+)["']/g)) idCount[m[1]] = (idCount[m[1]] || 0) + 1;
const dups = Object.entries(idCount).filter(([, c]) => c > 1);
if (!dups.length) ok("no duplicate ids");
else dups.forEach(([k, c]) => bad("duplicate id " + k + " (x" + c + ")"));

/* ---------- 12. inline handlers call defined functions ---------- */
const fnDefs = new Set();
for (const m of html.matchAll(/function\s+([A-Za-z_$][\w$]*)\s*\(/g)) fnDefs.add(m[1]);
for (const m of html.matchAll(/(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?(?:function|\()/g)) fnDefs.add(m[1]);
const handlers = new Set();
for (const m of html.matchAll(/on(?:click|input|change|mouseenter|mouseleave|keydown|keyup|load|error)\s*=\s*"([^"]+)"/g)) {
  for (const h of m[1].matchAll(/([A-Za-z_$][\w$]*)\s*\(/g)) {
    const idx = h.index;
    const prev = idx > 0 ? m[1][idx - 1] : "";
    if (prev === "." || /[\w$]/.test(prev)) continue; // method call or property
    handlers.add(h[1]);
  }
}
const HOOKS = new Set(["document", "window", "this", "if", "for", "while", "switch", "catch", "return",
  "typeof", "new", "Math", "Number", "String", "Array", "Object", "JSON", "parseInt", "parseFloat",
  "setTimeout", "clearTimeout", "alert", "confirm", "event", "e"]);
const missingFn = [...handlers].filter((f) => !fnDefs.has(f) && !HOOKS.has(f)).sort();
if (!missingFn.length) ok("all " + handlers.size + " inline handler functions defined");
else missingFn.forEach((f) => bad("inline handler calls undefined function: " + f + "()"));

/* ---------- 13. workflow ASCII (every workflow, not just android-apk) ---------- */
const wfDir = path.join(ROOT, ".github/workflows");
for (const wf of fs.readdirSync(wfDir).filter((f) => /\.ya?ml$/.test(f)).sort()) {
  const src = qaRead(path.join(wfDir, wf), "utf8");
  const nonAscii = [...new Set([...src].filter((c) => c.charCodeAt(0) > 127))];
  if (!nonAscii.length) ok("workflow YAML pure ASCII: " + wf);
  else bad("workflow " + wf + " has non-ASCII chars: " + nonAscii.map((c) => "U+" + c.charCodeAt(0).toString(16).toUpperCase()).join(" "));
}

/* ---------- 14. manifest parses ---------- */
try { JSON.parse(read("manifest.webmanifest")); ok("manifest.webmanifest parses"); }
catch (e) { bad("manifest.webmanifest invalid JSON: " + e.message); }

/* ---------- 15. native volume icons stay AutoMirrored ---------- */
const natDir = path.join(ROOT, "android-native/app/src/main/java");
const natBad = [];
const walkKt = (d) => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walkKt(p);
    else if (e.name.endsWith(".kt")) {
      const src = qaRead(p, "utf8");
      for (const m of src.matchAll(/Icons\.Filled\.Volume\w*/g)) {
        natBad.push(path.relative(ROOT, p).replace(/\\/g, "/") + ": " + m[0]);
      }
    }
  }
};
if (fs.existsSync(natDir)) walkKt(natDir);
if (!natBad.length) ok("native: volume icons are AutoMirrored only");
else natBad.forEach((x) => bad("deprecated volume icon (use Icons.AutoMirrored.Filled.Volume*): " + x));

/* ---------- 16. native SB_HOOK bridge JS parses ---------- */
const psPath = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/PlayerScreen.kt");
if (fs.existsSync(psPath)) {
  const ps = qaPlayer(psPath);
  const m = ps.match(/private val SB_HOOK = """\n([\s\S]*?)\n?"""/);
  if (!m) bad("SB_HOOK raw string not found in PlayerScreen.kt");
  else {
    const tmp = path.join(os.tmpdir(), "sb_hook_check.js");
    fs.writeFileSync(tmp, m[1]);
    const r = cp.spawnSync(process.execPath, ["--check", tmp], { encoding: "utf8" });
    if (r.status === 0) ok("node --check native SB_HOOK bridge JS");
    else bad("SB_HOOK JS syntax error:\n" + (r.stderr || r.stdout));
  }
} else {
  warn("PlayerScreen.kt not found - SB_HOOK check skipped");
}

/* ---------- 17. native HLS slot stays dormant (pre-build rules) ---------- */
const hlsPath = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HlsPlayer.kt");
if (!fs.existsSync(hlsPath)) bad("HlsPlayer.kt missing - the HLS slot file is gone");
else {
  const hlsSrc = qaRead(hlsPath, "utf8");
  if (/const val HLS_TEST_URL\s*=/.test(hlsSrc)) ok("HLS_TEST_URL defined in the slot file");
  else bad("HLS_TEST_URL definition missing from HlsPlayer.kt");
}
// The slot's debug test asset is the ONLY stream URL allowed in native
// code - a provider stream URL anywhere else would be invented routing
// (house rule: never hardcode stream URLs).
const m3u8Hits = [];
const walkM3u8 = (d) => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walkM3u8(p);
    else if (e.name.endsWith(".kt")) {
      const src = qaRead(p, "utf8");
      if (/\.m3u8/.test(src)) m3u8Hits.push(path.relative(ROOT, p).replace(/\\/g, "/"));
    }
  }
};
const natSrcRoot = path.join(ROOT, "android-native/app/src/main/java");
if (fs.existsSync(natSrcRoot)) walkM3u8(natSrcRoot);
const strayM3u8 = m3u8Hits.filter((f) => !/HlsPlayer\.kt$/.test(f));
if (!strayM3u8.length) ok("the only stream URL in native code is the HLS test asset (slot file)");
else strayM3u8.forEach((f) => bad("stream URL outside the HLS slot file: " + f));
if (/devstreaming-cdn|test-streams\.mux|bipbop/.test(html + sw)) bad("HLS test asset referenced on the website - it is native-debug only");
else ok("HLS test asset never touches the website");
// The slot stays OUT of the normal server sheet and out of every default -
// only the hidden flag opens it (dormant pre-build rule).
if (!fs.existsSync(psPath)) bad("PlayerScreen.kt missing - HLS slot gates cannot be verified");
else {
  const ps2 = qaPlayer(psPath);
  const mf = ps2.match(/private fun mirrorsFor[\s\S]*?\n\}/);
  if (mf && !/"hls"/.test(mf[0])) ok("mirrorsFor never lists the HLS slot");
  else bad("mirrorsFor lists \"hls\" - the slot must stay out of the normal server sheet");
  if (/if \(AppEnv\.hlsTest\) "hls" else defaultMirror/.test(ps2)) ok("HLS default-mirror entry gated on AppEnv.hlsTest");
  else bad("HLS slot entered as default mirror without the AppEnv.hlsTest gate");
  if (/mirrorsFor\(title, mal, ani\) \+ if \(AppEnv\.hlsTest\)/.test(ps2)) ok("HLS server-sheet row gated on AppEnv.hlsTest");
  else bad("HLS server-sheet row missing the AppEnv.hlsTest gate");
}
// UnstableApi is a doc marker, NOT an opt-in requirement marker - an
// -opt-in flag for it only produces "not an opt-in requirement marker"
// compiler warnings (seen in the 0.22 build). The slot builds warning-free
// (zero-warning rule): never re-add that flag.
{
  const bg = read("android-native/app/build.gradle.kts");
  if (!/opt-in=androidx\.media3/.test(bg)) ok("no media3 opt-in flag (UnstableApi is not an opt-in marker)");
  else bad("build.gradle.kts opts in to androidx.media3.* - that annotation is not an opt-in marker and warns");
}

/* ---------- 18. native sources free of the C-to-o corruption signature ---------- */
const corruptHits = [];
const walkCorrupt = (d) => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walkCorrupt(p);
    else if (e.name.endsWith(".kt")) {
      const src = qaRead(p, "utf8");
      if (/oolumn|oolor|oomposable|darkoolor|Roundedoorner|oompositionLocalProvider|Streamoolors|oenterVertically/.test(src)) {
        corruptHits.push(path.relative(ROOT, p).replace(/\\/g, "/"));
      }
    }
  }
};
if (fs.existsSync(natSrcRoot)) walkCorrupt(natSrcRoot);
if (!corruptHits.length) ok("native sources free of the C-to-o corruption signature");
else corruptHits.forEach((f) => bad("corrupted identifiers (C->o) in " + f + " - rewrite the file, never bulk-replace identifiers"));

/* ---------- 19. native quality cycle matches QLEVELS ---------- */
{
  const psq = qaPlayer(psPath);
  const m = psq.match(/private val CINE_Q = listOf\(([^)]*)\)/);
  const vals = m ? [...m[1].matchAll(/"([^"]*)"/g)].map((x) => x[1]) : null;
  if (vals && vals.join(",") === ",1080,720,480") ok("native CineSrc quality cycle is only \"\"/1080/720/480");
  else bad("native CINE_Q drifted from QLEVELS \"\"/1080/720/480 (never send 2160)");
}

/* ---------- 20. circular focus targets draw circles only ---------- */
{
  const boxyRings = [];
  const walkRing = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walkRing(p);
      else if (e.name.endsWith(".kt")) {
        const src = qaRead(p, "utf8");
        if (/focusRing\(\s*CircleShape/.test(src)) {
          boxyRings.push(path.relative(ROOT, p).replace(/\\/g, "/"));
        }
      }
    }
  };
  if (fs.existsSync(natSrcRoot)) walkRing(natSrcRoot);
  if (!boxyRings.length) ok("round targets use focusRingCircle (drawCircle traces only)");
  else boxyRings.forEach((f) => bad("focusRing(CircleShape) in " + f + " - round targets must use focusRingCircle (shape traces render boxy plates)"));
  const themeSrc = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"))
    ? read("android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt") : "";
  if (/fun Modifier\.focusRingCircle\(/.test(themeSrc)) ok("focusRingCircle defined in Theme.kt");
  else bad("focusRingCircle missing from Theme.kt");
  const appSrc = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? read("android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt") : "";
  if (/LocalIndication provides NoIndication/.test(appSrc) && /object NoIndication : IndicationNodeFactory/.test(appSrc)) {
    ok("default indication disabled (no square state-layer plates)");
  } else {
    bad("NoIndication missing - the default indication draws square plates behind round buttons on TV");
  }
}

/* ---------- 21. player resume boots at the saved position ---------- */
{
  const psResume = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psResume) bad("PlayerScreen.kt missing - resume boot cannot be verified");
  else {
    if (/val resumeAt = run \{[\s\S]*?Store\.progressList\(ctx\)/.test(psResume)) ok("resumeAt derives from saved Store progress");
    else bad("resumeAt missing - the player must boot at the saved position");
    if (/playerUrl\(title, epSeason, epNumber, mal, ani, mirror, resumeAt,/.test(psResume)) ok("WebView boot loads at resumeAt");
    else bad("WebView boot does not pass resumeAt to playerUrl - Continue Watching would restart at 0");
    if (/mirror, 0f, anipmLang/.test(psResume)) bad("playerUrl boot call hardcodes 0f - pass resumeAt");
    else ok("no hardcoded 0f start at the player boot");
    if (/load\(startAt = resumeAt\)/.test(psResume)) ok("HLS slot boots at resumeAt");
    else bad("HLS boot missing resumeAt");
  }
}

/* ---------- 22. no hardcoded unreported quality claims in native ---------- */
{
  const claimHits = [];
  const walkClaim = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) walkClaim(p);
      else if (e.name.endsWith(".kt")) {
        const src = qaRead(p, "utf8");
        for (const m of src.matchAll(/"[^"\n]*\b(4K|Atmos|HDR10|HDR|Dolby)\b[^"\n]*"/g)) {
          claimHits.push(path.relative(ROOT, p).replace(/\\/g, "/") + ": " + m[0]);
        }
      }
    }
  };
  if (fs.existsSync(natSrcRoot)) walkClaim(natSrcRoot);
  if (!claimHits.length) ok("native labels never hardcode 4K/Atmos/HDR/Dolby claims");
  else claimHits.forEach((x) => bad("unreported quality claim in native UI: " + x + " (never label 4K unless reported)"));
}

/* ---------- 23. watchdog hops only on real silence ---------- */
{
  const psW = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psW) bad("PlayerScreen.kt missing - watchdog cannot be verified");
  else {
    if (/lastMediaAt/.test(psW) && /idleFor/.test(psW)) ok("watchdog is idle-based (media traffic keeps it patient)");
    else bad("watchdog lost its media-traffic patience - slow loads must never hop mirrors");
    if (/delay\(30000\)[\s\S]{0,80}if \(!gotMedia\) hopMirror\(\)/.test(psW)) bad("fixed 30s hop timer is back - hop only after 30s of silence");
    else ok("no fixed 30s hop timer");
    if (/t\.startsWith\("anipm:"\)/.test(psW)) ok("provider protocol traffic counts as media life");
    else bad("anipm protocol traffic must count as media life (wrapped players hop away otherwise)");
  }
}

/* ---------- 24. TMDB date reads are null-normalized ---------- */
{
  const apiPath = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt");
  const api = fs.existsSync(apiPath) ? read("android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt") : "";
  if (!api) bad("Api.kt missing - date normalization cannot be verified");
  else {
    const rawDates = [...api.matchAll(/optString\("(?:air_date|release_date|first_air_date)"/g)];
    if (/private fun dateOf\(/.test(api) && !rawDates.length) {
      ok("every TMDB date read goes through dateOf (JSON null never becomes \"null\")");
    } else {
      rawDates.forEach((m) => bad("raw date read in Api.kt: " + m[0] + " - use dateOf() or dates sort as the string null"));
    }
  }
}

/* ---------- 25. VidBolt unsandboxed + media never blocked ---------- */
{
  const psVb = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psVb) bad("PlayerScreen.kt missing - VidBolt rules cannot be verified");
  else {
    if (/sandbox\s*=/.test(psVb)) bad("sandbox attribute in PlayerScreen - VidBolt renders Playback disabled when sandboxed");
    else ok("no sandbox attribute in the wrapper (VidBolt plays)");
    if (/ext in MEDIA_EXTS[\s\S]{0,3200}isAdHost/.test(psVb)) ok("Ad Shield checks media before ad blocks (playback always wins)");
    else bad("Ad Shield must check media extensions BEFORE ad-host blocks");
    if (/vb:tracks/.test(psVb) && /VB_MATCHMEDIA_SHIM/.test(psVb)) {
      ok("VidBolt track plumbing present (labels + command gate shim)");
    } else {
      bad("VidBolt track plumbing missing (vb:tracks / VB_MATCHMEDIA_SHIM - the command listener is narrow-viewport gated)");
    }
  }
}

/* ---------- 26. VidBolt gate shim is document-level, JS bodies untouched ---------- */
{
  const psG = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psG) bad("PlayerScreen.kt missing - VidBolt gate rules cannot be verified");
  else {
    if (/patchedVbJs|VB_JS_CACHE/.test(psG)) {
      bad("player JS body rewriting is BACK (patchedVbJs/VB_JS_CACHE) - intercepting the VidBolt module froze the play screen (0.45/0.46); the gate shim must ride the DOCUMENT");
    } else {
      ok("player JS bodies are never rewritten (module patch stays dead)");
    }
    if (/private fun vbShimmedDoc\(/.test(psG) && /<script>" \+ VB_MATCHMEDIA_SHIM \+ "<\/script>"/.test(psG)) {
      ok("gate shim rides vbShimmedDoc (inline in the player document, before their module)");
    } else {
      bad("vbShimmedDoc gate-shim document injector missing - TV audio/subtitle taps die without it");
    }
    if (/document\.__sbGate=1/.test(psG) &&
        /!d\.__sbGate&&\(''\+\(d\.location&&d\.location\.href\|\|''\)\)\.indexOf\('http'\)===0/.test(psG)) {
      ok("gate shim injections are race-safe (real-http-document guard + __sbGate marker)");
    } else {
      bad("gate shim lost the about:blank race guard - the iframe's transient doc has a head and swallows the shim");
    }
  }
}

/* ---------- 27. SB_HOOK rides the wrapped shell markup ---------- */
{
  const psH = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psH) bad("PlayerScreen.kt missing - shell hook embed cannot be verified");
  else if (/"<script>" \+ SB_HOOK \+ "<\/script>"/.test(psH)) {
    ok("SB_HOOK rides the wrapped shell markup (attached before the iframe speaks)");
  } else {
    bad("SB_HOOK must be embedded in loadMirror's shell HTML - onPageFinished injection can land after the player is already talking");
  }
}

/* ---------- 28. silent-start kicks before the watchdog hops ---------- */
{
  const psK = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psK) bad("PlayerScreen.kt missing - start kicks cannot be verified");
  else if (/mirror == "vidbolt" \|\| mirror == "vidzen"\) && kicked < 2[\s\S]{0,240}window\.__sb && window\.__sb\.play\(\)/.test(psK)) {
    ok("silent-start kicks nudge VidBolt/VidZen before the 30s idle hop (website parity)");
  } else {
    bad("silent-start kicks missing - a TV WebView that swallows autoplay strands the player until the watchdog hops");
  }
}

/* ---------- 29. anime typing: content decides, overrides never lie ---------- */
{
  const apiP = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt");
  const apiS = fs.existsSync(apiP) ? qaRead(apiP, "utf8") : "";
  if (!apiS) bad("Api.kt missing - anime typing cannot be verified");
  else if (/val isAnime = 16 in genIds && lang == "ja"/.test(apiS) &&
           /else if \(isAnime\) "anime" else "tv"/.test(apiS) &&
           /val type = if \(content == "anime"\) "anime" else \(typeOverride \?: content\)/.test(apiS)) {
    ok("anime typing always wins: ja+16 is anime everywhere and never stamps as TV");
  } else {
    bad("titlesFrom must let the anime label win over every row override (home 'reads as tv' / Lie to Me mistakes)");
  }
}

/* ---------- 30. screens keyed per title / play session ---------- */
{
  const appP = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt");
  const appS = fs.existsSync(appP) ? qaRead(appP, "utf8") : "";
  const detPath = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt");
  const det = fs.existsSync(detPath) ? qaRead(detPath, "utf8") : "";
  if (!appS || !det) bad("App.kt/DetailScreen.kt missing - stale-state rules cannot be verified");
  else {
    if (/is Screen\.Detail -> key\(s\.title\.id, s\.title\.type\)/.test(appS) &&
        /is Screen\.Player -> key\(s\.title\.id, s\.title\.type, s\.season, s\.episode/.test(appS)) {
      ok("Detail/Player compose under key() per title and play session");
    } else {
      bad("Detail/Player must wrap in key(...) - un-keyed remember kept the previous title's season/episode ('episode 1' opened S2 E1)");
    }
    if (/episodes = listOf\(\)\n[\s\S]{0,200}Api\.season\(title\.id, season\)/.test(det) &&
        /det\.seasons\.none \{ it\.n == season \}/.test(det)) {
      ok("episode rail clears on season switch and stale seasons snap back");
    } else {
      bad("DetailScreen must clear the episode rail before every season fetch and snap invalid seasons back to a real one");
    }
  }
}

/* ---------- 31. TV hero tells the whole story ---------- */
{
  const hsP = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt");
  const hs = fs.existsSync(hsP) ? qaRead(hsP, "utf8") : "";
  if (!hs) bad("HomeScreen.kt missing - hero rules cannot be verified");
  else if (/heightIn\(min = if \(big\) 420\.dp else 260\.dp\)/.test(hs) &&
           /maxLines = if \(big\) Int\.MAX_VALUE else 3/.test(hs) &&
           /heroBring\.bringIntoView\(\)/.test(hs) &&
           /onFocusChange = heroReveal/.test(hs)) {
    ok("TV hero grows with its content, overview unclamped, and a CTA focus reveals the whole block");
  } else {
    bad("TV hero must use heightIn(min) + unclamped overview + heroReveal bringIntoView on its CTAs (title stayed cut above the fold)");
  }
}

/* ---------- 32. poster captions visible, artwork-only ring ---------- */
{
  const hs2 = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!hs2) bad("HomeScreen.kt missing - poster caption rules cannot be verified");
  else {
    const th2 = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"))
      ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"), "utf8") : "";
    const good = /fun Modifier\.artworkRing\(/.test(th2) &&
      /\.artworkRing\(plate\)/.test(hs2) &&
      /\.clip\(plate\)\s*\.background\(Card\)/.test(hs2) &&
      !/\.focusRing\(plate\)/.test(hs2) &&
      !/cardBring/.test(hs2);
    if (good) {
      ok("poster look stays artwork-only (artworkRing) while the whole card is the target - names scroll in by default");
    } else {
      bad("PosterCard must use artworkRing on the whole card (ring traces the poster only) - 0.48's full-card ring and per-card reveals are banned");
    }
  }
}

/* ---------- 43. smooth row navigation: idempotent reveals only ---------- */
{
  const hsN = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const dsN = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"), "utf8") : "";
  if (!hsN || !dsN) bad("HomeScreen.kt/DetailScreen.kt missing - row navigation rules cannot be verified");
  else if (!/rowBring/.test(hsN) &&
           !/cellBring/.test(hsN) &&
           !/rowBring/.test(dsN) &&
           !/cellBring/.test(dsN) &&
           /heroShown/.test(hsN) &&
           /rowNavLock\(\)/.test(hsN) &&
           /rowNavLock\(\)/.test(dsN)) {
    ok("smooth rows: zero reveal requests in rows/grids (hero reveal one-shot) and L/R stays locked");
  } else {
    bad("rows must NEVER bringIntoView - an oversized reveal rect oscillated its edges on every press (the zigzag); names ride the whole-card target");
  }
}

/* ---------- 53. one All chip + a rail every tab fits on ---------- */
{
  const hsA = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!hsA) bad("HomeScreen.kt missing - rail/chip rules cannot be verified");
  else if (!/listOf\("kdrama" to "All"\)/.test(hsA) &&
           /\.size\(46\.dp\)/.test(hsA) &&
           /Section\.values\(\)\.forEach/.test(hsA)) {
    ok("one All chip per genre strip and a compact rail every section fits on");
  } else {
    bad("KDrama must not add a second All chip (GenreBar prepends one) and RailBtn must stay compact so Browse stays visible");
  }
}

/* ---------- 54. anime season routing ---------- */
{
  const psA = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psA) bad("PlayerScreen.kt missing - anime season routing cannot be verified");
  else if (/Api\.animeMap\(title\.name, airYear\)/.test(psA) &&
           /val sameEntry/.test(psA) &&
           /epBase: Int = 0,/.test(psA) &&
           /val aep = \(epBase \+ episode\)\.coerceAtLeast\(1\)/.test(psA) &&
           /resolvedSeason != epSeason/.test(psA)) {
    ok("anime season routing: season-air-year entry resolution + entry-relative episodes (deferred boot)");
  } else {
    bad("anime must resolve the season's own entry by air year and number episodes against THAT entry (S2E1 played S1E1)");
  }
}

/* ---------- 55. next episode never steps past the season ---------- */
{
  const psN = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psN) bad("PlayerScreen.kt missing - episode advance rules cannot be verified");
  else if (/fun advanceEpisode\(\)/.test(psN) &&
           /epNumber >= count/.test(psN) &&
           /it\.n == epSeason \+ 1/.test(psN) &&
           !/epNumber \+= 1\s*\n\s*save\(0f, dur\)/.test(psN)) {
    ok("next episode: season-end guard + next-season roll + unaired guard (no blind +1)");
  } else {
    bad("episode advancing must go through advanceEpisode() - blind +1 built dead routes on season finales");
  }
}

/* ---------- 44. VidBolt subtitles carry their command index ---------- */
{
  const psSub = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psSub) bad("PlayerScreen.kt missing - VidBolt subtitle rules cannot be verified");
  else if (/VBT\.subs\[String\(k\)\]/.test(psSub) &&
           /String\(nat\.length \+ j\)/.test(psSub) &&
           /option:'subtitles', value: String\(val\)/.test(psSub) &&
           /var idx = parseFloat\(val\)/.test(psSub) &&
           /idx < 0/.test(psSub)) {
    ok("VidBolt subtitle rows mirror their H index (engine ++ resolve) - picks land and captions show");
  } else {
    bad("VidBolt subtitle rows must carry the numeric H index - labels no-op both command paths (0.54)");
  }
}

/* ---------- 45. skip pills: plain names + tail-guarded credits/outro ---------- */
{
  const psSkip = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psSkip) bad("PlayerScreen.kt missing - skip pill rules cannot be verified");
  else if (/fun hitAt\(cur: Float, dur: Float/.test(psSkip) &&
           /fun inTail\(r: IntroDb\.TRange\)/.test(psSkip) &&
           /inRange\(it\) && inTail\(it\) && it\.end != null/.test(psSkip) &&
           /SkipHit\("outro", it\.end, "Skip Outro"\)/.test(psSkip) &&
           /inRange\(it\) && inTail\(it\) && it\.end == null/.test(psSkip) &&
           /SkipHit\("credits", null, "Next Episode"\)/.test(psSkip) &&
           !/hit\.label \+ "  " \+ fmt/.test(psSkip)) {
    ok("skip pills: Skip Recap / Skip Intro / Skip Outro over real ranges, Next Episode only for open-ended credits, all tail-guarded");
  } else {
    bad("skip pills must use plain names (no countdown), show Skip Outro for RANGED credits (seek past the ending song) and Next Episode only for OPEN-ENDED credits, all tail-guarded (bogus start=0 ranges sat over the intro)");
  }
}

/* ---------- 46. server auto-switch leads with VidBolt ---------- */
{
  const psHop = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psHop) bad("PlayerScreen.kt missing - auto-switch rules cannot be verified");
  else {
    const chain = (psHop.match(/private fun fallbackChain[\s\S]*?\n}\n/) || [""])[0];
    if (chain.includes('"anipm" -> listOf("vidbolt"') &&
        chain.includes('"cinesrc" -> listOf("vidbolt"') &&
        chain.includes('"vidzen" -> listOf("vidbolt"') &&
        !/listOf\("vidrift"/.test(chain)) {
      ok("auto-switch leads with VidBolt on every chain and never hops to VidRift");
    } else {
      bad("fallbackChain must lead with vidbolt and never auto-hop to vidrift (no pause button there)");
    }
  }
}

/* ---------- 47. Browse tab: kind + genre + year over the full catalog ---------- */
{
  const appBr = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const hsBr = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const apiBr = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"), "utf8") : "";
  if (!appBr || !hsBr || !apiBr) bad("App.kt/HomeScreen.kt/Api.kt missing - Browse tab cannot be verified");
  else if (/BROWSE\("Browse", Icons\.Filled\.Explore\)/.test(appBr) &&
           /private fun BrowseAllView\(/.test(hsBr) &&
           /"movie" to "Movies"/.test(hsBr) &&
           /"tv" to "TV Shows"/.test(hsBr) &&
           /"anime" to "Anime"/.test(hsBr) &&
           /"kdrama" to "K-Dramas"/.test(hsBr) &&
           /\(2026 downTo 1980\)/.test(hsBr) &&
           /fun browsePage\(/.test(apiBr) &&
           /"kdrama" -> \{[\s\S]{0,320}with_original_language=ko/.test(apiBr)) {
    ok("Browse tab: kind pick (Movies / TV Shows / Anime / K-Dramas) + genre + year chips (2026 down) over the paginated catalog");
  } else {
    bad("Browse tab must offer the kind pick (incl. K-Dramas), genre chips, year chips from 2026 down, and Api.browsePage paging");
  }
}

/* ---------- 48. anime can never render as TV ---------- */
{
  const hsT = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const stT = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"), "utf8") : "";
  if (!hsT || !stT) bad("HomeScreen.kt/Store.kt missing - anime label rules cannot be verified");
  else if (/\(if \(t\.isAnime\) "anime" else t\.type\)\.uppercase\(\)/.test(hsT) &&
           /put\("genres", JSONArray\(t\.genreIds\)\)/.test(stT) &&
           /t\.copy\(type = "anime"\)/.test(stT)) {
    ok("anime never renders as TV: content-driven badges + genreIds survive saves + restores re-type anime");
  } else {
    bad("hero badge must derive from isAnime, Store must persist genreIds, and restores must re-type anime (home 'reads as tv')");
  }
}

/* ---------- 49. routing honors isAnime ---------- */
{
  const psR = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psR) bad("PlayerScreen.kt missing - routing rules cannot be verified");
  else if (/title\.type == "anime" \|\| title\.isAnime -> if \(mal != null \|\| ani != null\) "anipm"/.test(psR)) {
    ok("player routing honors isAnime - Japanese animation rides the anime route");
  } else {
    bad("defaultMirror must treat isAnime titles as anime even when an old stored label says tv");
  }
}

/* ---------- 50. episode status zone + PH Coming Soon clock ---------- */
{
  const dsP = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"), "utf8") : "";
  if (!dsP) bad("DetailScreen.kt missing - episode status rules cannot be verified");
  else if (/private fun phAirLabel\(/.test(dsP) &&
           /private fun phCountdown\(/.test(dsP) &&
           /Asia\/Manila/.test(dsP) &&
           /phCountdown\(ep\.air, tickMs\)/.test(dsP) &&
           /val primary = when \{/.test(dsP) &&
           /Row\(verticalAlignment = Alignment\.Top, modifier = Modifier\.fillMaxWidth\(\)\)/.test(dsP) &&
           /Column\(horizontalAlignment = Alignment\.End\)/.test(dsP) &&
           !/weight\(1f, fill = false\)/.test(dsP)) {
    ok("episode status sits in one pinned right column (full-width row, title the only flexible child)");
  } else {
    bad("episode rows need the full-width title row with the title as the ONLY weighted child - weight(fill=false)+spacer dragged the label inward on short titles");
  }
}

/* ---------- 51. Korean dramas live in the KDrama tab ---------- */
{
  const appK = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const hsK = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!appK || !hsK) bad("App.kt/HomeScreen.kt missing - KDrama tab cannot be verified");
  else if (/KDRAMA\("KDrama"/.test(appK) &&
           /Section\.KDRAM[A-Z]* -> \{[\s\S]{0,220}Popular K-Dramas/.test(hsK) &&
           !/listOf\("kdrama" to "K-Drama"\)/.test(hsK)) {
    ok("KDrama tab beside Anime holds the K-Drama rows; the TV Shows strip is clean");
  } else {
    bad("Korean dramas must live in the KDrama tab (Section.KDRAMA rows) with no K-Drama chip left on the TV strip");
  }
}

/* ---------- 52. the zigzag lock ---------- */
{
  const thZ = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"), "utf8") : "";
  const hsZ = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const dsZ = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"), "utf8") : "";
  const uses = ((hsZ + dsZ).match(/rowNavLock\(\)/g) || []).length;
  if (!thZ || !hsZ || !dsZ) bad("Theme/HomeScreen/DetailScreen missing - zigzag lock cannot be verified");
  else if (/fun Modifier\.rowNavLock\(\)/.test(thZ) &&
           /FocusDirection\.Right -> FocusRequester\.Cancel/.test(thZ) &&
           /FocusDirection\.Left -> NavEscape\.left \?: FocusRequester\.Cancel/.test(thZ) &&
           uses >= 10) {
    ok("zigzag lock: Right never leaves a card row or chip rail (applied at " + uses + " containers); Left escapes to the TV rail");
  } else {
    bad("rowNavLock must exist in Theme.kt and wrap every card row and chip rail (Right locked, Left only via NavEscape)");
  }
}

/* ---------- 33. Continue Watching is tab-scoped ---------- */
{
  const hs3 = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!hs3) bad("HomeScreen.kt missing - Continue Watching scoping cannot be verified");
  else if (/Section\.MOVIES -> all\.filter \{ it\.type == "movie" \}/.test(hs3) &&
           /Section\.TV -> all\.filter \{ it\.type == "tv" && it\.lang != "ko" \}/.test(hs3) &&
           /Section\.KDRAMA -> all\.filter \{ it\.type == "tv" && it\.lang == "ko" \}/.test(hs3) &&
           /Section\.ANIME -> all\.filter \{ it\.type == "anime" \}/.test(hs3)) {
    ok("Continue Watching is tab-scoped (KDrama split from TV; Home + My List keep everything)");
  } else {
    bad("Continue Watching must filter by the tab's type - anime progress never shows in the movie/TV tabs");
  }
}

/* ---------- 34. search state is hoisted ---------- */
{
  const appH = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const hsH = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!appH || !hsH) bad("App.kt/HomeScreen.kt missing - search hoisting cannot be verified");
  else if (/var searchMode by remember \{ mutableStateOf\(false\) \}/.test(appH) &&
           /var searchSubmitted by remember \{ mutableStateOf\(""\) \}/.test(appH) &&
           /fun clearSearch\(\)/.test(appH) &&
           !/var searchMode by remember/.test(hsH) &&
           /searchSubmitted: String,/.test(hsH)) {
    ok("search state is hoisted (Back from detail lands in the search; tabs and player exit clear it)");
  } else {
    bad("search state must live in StreamBoxApp and pass into BrowseScreen - local remember lost the search on detail-back");
  }
}

/* ---------- 35. VidBolt quality plumbing is provider-reported only ---------- */
{
  const psQ = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psQ) bad("PlayerScreen.kt missing - VidBolt quality rules cannot be verified");
  else if (/vbqual: function\(opt, val\)/.test(psQ) &&
           /o\.group === "Quality"/.test(psQ) &&
           /qualsByLang/.test(psQ) &&
           /"sourceQuality"/.test(psQ) &&
           /option:\s*String\(opt\),\s*value:\s*String\(val\)/.test(psQ)) {
    ok("VidBolt quality rows are provider-reported and echoed verbatim (sourceQuality / quality)");
  } else {
    bad("VidBolt quality plumbing missing (vbqual command + Quality panel rows + per-language resolve ladder)");
  }
}

/* ---------- 36. stable preview signing (updates install in place) ---------- */
{
  const ks = path.join(ROOT, "android-native/preview.keystore");
  const gradle = fs.existsSync(path.join(ROOT, "android-native/app/build.gradle.kts"))
    ? qaRead(path.join(ROOT, "android-native/app/build.gradle.kts"), "utf8") : "";
  if (!fs.existsSync(ks)) {
    bad("android-native/preview.keystore missing - without a stable key every APK re-signs and 'update' fails to install");
  } else if (/signingConfigs/.test(gradle) &&
             /storeFile = rootProject\.file\("preview\.keystore"\)/.test(gradle) &&
             /signingConfig = signingConfigs\.getByName\("preview"\)/.test(gradle)) {
    ok("every build signs with the committed preview keystore (update installs in place)");
  } else {
    bad("build.gradle.kts must wire BOTH build types to the preview keystore signingConfig");
  }
}

/* ---------- 37. versionCode frozen for cross-build installs ---------- */
{
  const gradleV = fs.existsSync(path.join(ROOT, "android-native/app/build.gradle.kts"))
    ? qaRead(path.join(ROOT, "android-native/app/build.gradle.kts"), "utf8") : "";
  if (!gradleV) bad("build.gradle.kts missing - versionCode freeze cannot be verified");
  else if (/versionCode = 50/.test(gradleV)) {
    ok("versionCode frozen at 50 (any build installs over any other; versionName carries the feature)");
  } else {
    bad("native versionCode must STAY 50 - bumping it blocks rolling back to older APKs (0.50 request)");
  }
}

/* ---------- 38. cinematic splash + one-time What's New ---------- */
{
  const appB = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const storeB = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"), "utf8") : "";
  if (!appB || !storeB) bad("App.kt/Store.kt missing - splash and What's New cannot be verified");
  else if (/private fun SplashScreen\(\)/.test(appB) &&
           /private fun WhatsNewDialog\(/.test(appB) &&
           /Store\.lastSeenVersion\(appCtx\)/.test(appB) &&
           /BuildConfig\.VERSION_NAME/.test(appB) &&
           /fun lastSeenVersion\(ctx: Context\)/.test(storeB)) {
    ok("boot splash + What's New card fire once per fresh update");
  } else {
    bad("App.kt must boot through SplashScreen and gate WhatsNewDialog on Store.lastSeenVersion vs BuildConfig.VERSION_NAME");
  }
}

/* ---------- 39. browse speed: parallel rows + disk feed cache ---------- */
{
  const hsB = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const stB = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"), "utf8") : "";
  if (!hsB || !stB) bad("HomeScreen.kt/Store.kt missing - browse speed rules cannot be verified");
  else if (/plan\.map \{ \(label, f\) ->\s*async \{/.test(hsB) &&
           /Store\.loadFeedCache\(ctx, key\)/.test(hsB) &&
           /Store\.saveFeedCache\(ctx, key, rows\)/.test(hsB) &&
           /fun loadFeedCache\(ctx: Context, key: String\)/.test(stB)) {
    ok("rows load in parallel and the disk feed cache paints cold starts instantly");
  } else {
    bad("browse must keep parallel row loads AND the Store feed cache (paint-then-revalidate)");
  }
}

/* ---------- 40. player speed: doc cache + preconnects + one resolve ---------- */
{
  const psF = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psF) bad("PlayerScreen.kt missing - player speed rules cannot be verified");
  else if (/VB_DOC_CACHE/.test(psF) &&
           /rel=\\"preconnect\\"/.test(psF) &&
           /VB_PENDING\[q\] = 1;/.test(psF) &&
           /VB_RESOLVE\[q\] = j;/.test(psF) &&
           /applyStream\(j\);/.test(psF)) {
    ok("player fast paths: cached injected page, preconnects, resolve fetched once per episode");
  } else {
    bad("player must keep VB_DOC_CACHE + preconnect links + the VB_RESOLVE single fetch (VB_PENDING dedupe)");
  }
}

/* ---------- 41. catalog response cache ---------- */
{
  const apiC = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"), "utf8") : "";
  if (!apiC) bad("Api.kt missing - response cache cannot be verified");
  else if (/private val respCache/.test(apiC) && /RESP_TTL_MS/.test(apiC) &&
           /respCache\[path\]\?\.let \{ if \(now - it\.at < RESP_TTL_MS\) return it\.json \}/.test(apiC)) {
    ok("catalog calls ride the 120s response cache (no double network)");
  } else {
    bad("Api.get must serve repeat calls from respCache within the TTL");
  }
}

/* ---------- 42. motion clarity + judder reduction ---------- */
{
  const psM = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psM) bad("PlayerScreen.kt missing - motion rules cannot be verified");
  else if (/requestVideoFrameCallback/.test(psM) &&
           /'video:fps'/.test(psM) &&
           /preferredDisplayModeId = pick\.getModeId\(\)/.test(psM) &&
           /refresh is an INTEGER multiple/.test(psM) &&
           /preferredDisplayModeId = 0/.test(psM)) {
    ok("motion clarity: content fps measured and matched to an integer-multiple display mode, restored on exit");
  } else {
    bad("motion clarity requires the rVFC fps sampler + preferredDisplayModeId matching + restore-on-exit");
  }
}

/* ---------- 56. K-drama VidBolt defaults ---------- */
{
  const psK = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psK) bad("PlayerScreen.kt missing - K-drama defaults cannot be verified");
  else if (/var contentLang by (?:remember \{ )?mutableStateOf\(title\.lang\)/.test(psK) &&
           /var koManual by (?:remember \{ )?mutableStateOf\(false\)/.test(psK) &&
           /contains\("tagalog", true\)/.test(psK) &&
           /contains\("korean", true\)/.test(psK) &&
           /l\.contains\("english"\) \|\| l == "eng"/.test(psK) &&
           /if \(contentLang == "ko"\) return@LaunchedEffect/.test(psK) &&
           /K-Dramas use the provider's own English subtitles/.test(psK) &&
           /window\.__sb && window\.__sb\.vbaudio\(/.test(psK) &&
           /window\.__sb && window\.__sb\.vbsub\(-1\)/.test(psK)) {
    ok("K-drama defaults: Tagalog audio (Korean fallback) + VidBolt's own English subs, our chain stays off");
  } else {
    bad("K-dramas must default to the Tagalog dub (Korean fallback) and ride VidBolt's English subs - our chain never auto-loads over them (rules read contentLang, never raw title.lang)");
  }
}

/* ---------- 57. Left escapes to the TV nav rail ---------- */
{
  const thL = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"), "utf8") : "";
  const hsL = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!thL || !hsL) bad("Theme/HomeScreen missing - Left-escape rules cannot be verified");
  else if (/object NavEscape \{/.test(thL) &&
           /FocusDirection\.Left -> NavEscape\.left \?: FocusRequester\.Cancel/.test(thL) &&
           /FocusDirection\.Right -> FocusRequester\.Cancel/.test(thL) &&
           /NavEscape\.left = railFR/.test(hsL) &&
           /onDispose \{ NavEscape\.left = null \}/.test(hsL) &&
           /fr = if \(s == section\) activeFR else null/.test(hsL)) {
    ok("Left escapes rows/grids to the registered TV rail (never a yank to the chips); Right stays locked");
  } else {
    bad("rowNavLock Left must land on the TV nav rail via NavEscape (registered in BrowseScreen, targeting the active tab); Right stays Cancel");
  }
}

/* ---------- 58. Browse one scroll surface + readable captions + dazzo ---------- */
{
  const hsG = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!hsG) bad("HomeScreen.kt missing - Browse layout rules cannot be verified");
  else if (/GridItemSpan\(maxLineSpan\)/.test(hsG) &&
           /"b-kinds"/.test(hsG) &&
           /"b-years"/.test(hsG) &&
           /"b-more"/.test(hsG) &&
           /LaunchedEffect\(Unit\) \{ loadMore\(\) \}/.test(hsG) &&
           /inset = false/.test(hsG) &&
           /t\.name,\s*\n\s*fontSize = if \(AppEnv\.isTv\) 15\.sp else 13\.sp,\s*\n\s*color = Color\.White,/.test(hsG) &&
           /"dazzo",/.test(hsG) &&
           !/remember\([^)]*\) \{\s*ImageRequest\.Builder\(LocalContext\.current/.test(hsG)) {
    ok("Browse is one scroll surface (spanning chip headers), poster names render white/bold, and dazzo sits bottom right");
  } else {
    bad("Browse must ride ONE LazyVerticalGrid (spanning headers, no clipped grid window), with white/bold poster names, the dazzo credit in TvLegend, and no LocalContext.current inside remember {} (K2 composable-context error)");
  }
}

/* ---------- 59. provider episode events never steer the load ---------- */
{
  const psE = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psE) bad("PlayerScreen.kt missing - episode-sync rules cannot be verified");
  else {
    const lateRead = /playerUrl\(title, epSeason, epNumber, routeMal, routeAni, mirror, startAt, anipmLang, cinesrcQ, epBase\)/.test(psE);
    const rawStomp = /if \(s > 0\) epSeason = s/.test(psE) || /epNumber = o\.optInt\("episode", epNumber\) \+ 1/.test(psE);
    if (!lateRead && !rawStomp &&
        /playerUrl\(title, s, ep, rMal, rAni, mirror, startAt, lang, q, base, contentLang == "ko"\)/.test(psE) &&
        /fun adoptProviderEpisode\(sIn: Int, eIn: Int\)/.test(psE) &&
        /EP_SYNC_GRACE_MS/.test(psE) &&
        /if \(count > 0 && e > count\) return false/.test(psE) &&
        /adoptProviderEpisode\(o\.optInt\("season", -1\), o\.optInt\("episode", -1\)\)/.test(psE) &&
        /adoptProviderEpisode\(s, \(e - epBase\)\.coerceAtLeast\(1\)\)/.test(psE) &&
        /"mirror:nextup" -> \{[\s\S]{0,400}?advanceEpisode\(\)/.test(psE) &&
        /lastLoadAtMs = System\.currentTimeMillis\(\)/.test(psE)) {
      ok("episode sync: loads capture their target and provider switches enter state only through adoptProviderEpisode");
    } else {
      bad("episode sync must (a) capture season/episode at load() call time, (b) route every provider episode event through adoptProviderEpisode (grace + range guards), and (c) run nextup through advanceEpisode() - raw provider numbers steered loads to different videos");
    }
  }
}

/* ---------- 60. player UI lives outside PlayerScreen's method ---------- */
{
  const psM = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psM) bad("PlayerScreen.kt missing - JVM method-limit rules cannot be verified");
  else {
    const layers = ["topHudLayer", "captionLayer", "pausedLayer", "barLayer", "epsLayer", "subsLayer"];
    const missing = layers.filter((l) => !new RegExp("val " + l + ": @Composable BoxScope\\.\\(\\) -> Unit = \\{").test(psM));
    if (!missing.length && /JVM-64KB split/.test(psM)) {
      ok("player layers compile outside PlayerScreen's method (named lambda vals - JVM 64KB limit)");
    } else {
      bad("every big player layer must live in a named composable lambda VAL (missing: " + missing.join(", ") + ") - trailing Box/Column/Row content lambdas compile INTO PlayerScreen's method and hit the JVM 64KB limit (Method too large)");
    }
  }
}

/* ---------- 61. auto-next is ours (canonical route, no double-step) ---------- */
{
  const psA = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psA) bad("PlayerScreen.kt missing - auto-next rules cannot be verified");
  else if (/if \(adoptProviderEpisode\(o\.optInt\("season", -1\), o\.optInt\("episode", -1\)\)\) \{\s*\n\s*load\(\)/.test(psA) &&
           /"ended" -> \{[\s\S]{0,2200}?\!endedHandled && pastSyncGrace\(\)/.test(psA) &&
           /endedHandled = true/.test(psA) &&
           /"ended" -> \{[\s\S]{0,2200}?val more = nextEpisodeAvailable\(\)/.test(psA) &&
           /if \(pastSyncGrace\(\)\) goNext\(\)/.test(psA) &&
           /fun pastSyncGrace\(\): Boolean/.test(psA) &&
           /endedHandled = false/.test(psA)) {
    ok("auto-next is ours: provider switches re-open through load(), ended is one-per-load (endedHandled + pastSyncGrace) and walks the shared next guards, nextup rides goNext()");
  } else {
    bad("auto-next must never ride the embed's own Up Next - adopted switches reload through load(), ended runs once per load (endedHandled + pastSyncGrace) through advanceEpisode/finish, and nextup is grace-guarded through goNext()");
  }
}

/* ---------- 62. auto-next runs on every series ---------- */
{
  const psN = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const stN = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"), "utf8") : "";
  if (!psN || !stN) bad("PlayerScreen.kt/Store.kt missing - auto-next coverage cannot be verified");
  else {
    const endedBlock = (psN.match(/"ended" -> \{[\s\S]{0,3200}?\n\s{20,}\}(?=\n)/) || [""])[0];
    const anipmExcluded = /mirror != "anipm"/.test(endedBlock);
    const adoptResetsClock = /fun adoptProviderEpisode[\s\S]{0,1600}?The switch IS a transition/.test(psN) &&
      /fun adoptProviderEpisode[\s\S]{0,1600}?lastLoadAtMs = System\.currentTimeMillis\(\)/.test(psN);
    if (!anipmExcluded &&
        /if \(!endedHandled && pastSyncGrace\(\)\)/.test(endedBlock) &&
        /val more = nextEpisodeAvailable\(\)/.test(endedBlock) &&
        /if \(more && autoSkip && mirror != "hls"\)/.test(endedBlock) &&
        /save\(if \(scrub >= 0\) scrub else cur, dur, done = true\)/.test(endedBlock) &&
        /getBoolean\(K_AUTO_NEXT, true\)/.test(stN) &&
        /private const val K_AUTO_NEXT = "auto_next_v2"/.test(stN) &&
        adoptResetsClock) {
      ok("auto-next runs for every series: the ended takeover is total (no mirror exclusions), nothing-left goes DONE + Thank You, Store.autoSkip defaults ON, adopt restarts the sync clock");
    } else {
      bad("auto-next must run for every series mirror (no anipm exclusion), the ended takeover must advance when more exists and finish DONE when not, Store.autoSkip must default ON on a fresh key, and adoptProviderEpisode must restart the sync clock - episodes sat stuck at the end");
    }
  }
}

/* ---------- 63. the stored language heals before routing ---------- */
{
  const psL = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const apiL = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"), "utf8") : "";
  if (!psL || !apiL) bad("PlayerScreen.kt/Api.kt missing - language heal cannot be verified");
  else if (/originalLanguage/.test(apiL) &&
           /optString\("original_language", ""\)/.test(apiL) &&
           /if \(contentLang\.isEmpty\(\) && det != null && det\.originalLanguage\.isNotBlank\(\)\)/.test(psL) &&
           /contentLang = det\.originalLanguage/.test(psL) &&
           /!booted && contentLang == "ko" && mirror != "vidbolt"/.test(psL) &&
           /\(title\.type == "movie" \|\| title\.lang\.isNotEmpty\(\)\)/.test(psL) &&
           /routeMal \?: mal, routeAni \?: ani/.test(psL) &&
           /contentLang, done/.test(psL) &&
           /!koManual && contentLang == "ko" && mirror == "vidbolt"/.test(psL) &&
           !/var koAuto/.test(psL)) {
    ok("stored language heals before routing: contentLang from TMDB original_language, ko realigns to VidBolt before the first load, records self-heal on save");
  } else {
    bad("a blank stored lang must heal from TMDB original_language BEFORE the first load picks a mirror (K-Dramas routed to CineSrc and lost the Tagalog dub) - contentLang is authoritative, lang-less TV boots wait, save() writes the healed value");
  }
}

/* ---------- 64. VidBolt track discovery always finds the real tracks ---------- */
{
  const psT = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psT) bad("PlayerScreen.kt missing - track discovery rules cannot be verified");
  else if (psT.includes("function vbRoute()") &&
           psT.includes("var r = vbRoute();") &&
           psT.includes("if (have || vbTries > 40) clearInterval(vbTimer);") &&
           psT.includes("var anime = r.type === \"anime\";") &&
           !psT.includes("setTimeout(vbTracks, 5000)") &&
           !/var bt = fw && fw\.__VB__ && fw\.__VB__\.title;\s*\n\s*if \(!bt\) return;/.test(psT)) {
    ok("VidBolt track discovery: route reads the embed URL first and retries until the real audio tracks land (once-per-episode resolve kept)");
  } else {
    bad("track discovery must resolve its route from the EMBED URL (vbRoute - fw.__VB__ surfaces too late on slow TVs) and retry until the real tracks land - the audio list came up empty and the Tagalog dub vanished");
  }
}

/* ---------- 65. resolve hardening + weak-TV speed ---------- */
{
  const psT = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const appT = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt")) : "";
  const hsT = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt")) : "";
  if (!psT || !appT || !hsT) bad("PlayerScreen/App/HomeScreen missing - resolve hardening cannot be verified");
  else if (psT.includes("var VB_PENDING = {};") &&
           psT.includes("else if (fw && fw.__VB__ && !VB_PENDING[q]) {") &&
           /if \(st && st\.sources && st\.sources\.length > 0\) VB_RESOLVE\[q\] = j;/.test(psT) &&
           psT.includes("if (sig === VB_PUBSIG) return;") &&
           psT.includes("for (var vk in VB_RESOLVE)") &&
           /delay\(700\)/.test(appT) &&
           /crossfade\(if \(AppEnv\.isTv\) 0 else 180\)/.test(hsT) &&
           !psT.includes("setTimeout(vbTracks, 5000)") &&
           !/VB_RESOLVE\[q\] = j; applyStream/.test(psT)) {
    ok("resolve hardening: bootstrap-gated single fetch, empties never cached, publish-on-change; splash 700ms and no TV crossfade");
  } else {
    bad("the resolve fetch must wait for fw.__VB__ (bootstrap session), run one in flight per episode (VB_PENDING), cache ONLY source-bearing answers, and publish only on change - early empty caches erased the Tagalog dub and storms slowed slow-wifi starts");
  }
}

/* ---------- 66. Tagalog-first for K-Dramas ---------- */
{
  const psG = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psG) bad("PlayerScreen.kt missing - Tagalog-first rules cannot be verified");
  else if (/tagalog: Boolean = false/.test(psG) &&
           /if \(tagalog\) "&audio=Tagalog" else ""/.test(psG) &&
           psG.includes("function vbSweepTagalog(q)") &&
           psG.includes("&only=") &&
           /option:'server', value:id/.test(psG) &&
           psG.includes("vbSweepTagalog(q);") &&
           psG.includes("No Tagalog dub available") &&
           /audio=Tagalog/.test(psG)) {
    ok("Tagalog-first: boot preference + full server sweep with auto-switch + honest fallback note (Korean)");
  } else {
    bad("K-Dramas must ask for Tagalog at boot (audio=Tagalog), sweep EVERY VidBolt server (only=) and switch to the first carrying a Tagalog/Filipino dub, and say so when none does - their catalog rotates and the dub must snap back on its own");
  }
}

/* ---------- 67. playback stutter + nav-bar lag ---------- */
{
  const psP = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const subP = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Subs.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Subs.kt")) : "";
  const hsP2 = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt")) : "";
  const appP2 = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt")) : "";
  if (!psP || !subP || !hsP2 || !appP2) bad("Player/Subs/Home/App missing - stutter and nav rules cannot be verified");
  else if (/private fun BarTimeline\(/.test(psP) &&
           /cueAt\(f\.cues, t\)/.test(psP) &&
           /cues\.sortedBy \{ it\.s \}/.test(psP) &&
           /fun cueAt\(cues: List<Cue>, t: Float\)/.test(subP) &&
           /suspend fun warmFeedCaches\(/.test(hsP2) &&
           /while \(paused\(\)\) delay\(3000\)/.test(hsP2) &&
           /warmFeedCaches\(appCtx\) \{ screen is Screen\.Player \}/.test(appP2)) {
    ok("stutter + nav: timeline scoped, captions binary-searched, tab feeds warm in the background (paused during playback)");
  } else {
    bad("playback must keep the timeline in its own recomposition scope, captions must binary-search the sorted cues, and tab feeds must warm in the background (paused while the player owns the screen)");
  }
}

/* ---------- 68. skip timestamps: real MAL entry + calibrated merge ---------- */
{
  const psM = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const inM = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/IntroDb.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/IntroDb.kt")) : "";
  if (!psM || !inM) bad("PlayerScreen/IntroDb missing - skip timestamp rules cannot be verified");
  else if (/val malEntry = routeMal \?: mal/.test(psM) &&
           /val entryEp = \(epBase \+ epNumber\)\.coerceAtLeast\(1\)/.test(psM) &&
           /SkipTimes\.aniSkip\(malEntry, entryEp, d\)/.test(psM) &&
           /LaunchedEffect\(title\.id, title\.type, epSeason, epNumber, routeMal, epBase, if \(dur > 30\) dur\.toInt\(\) else 0\)/.test(psM) &&
           /IntroDb\.merge\(db, ani, sk, d, d > 30f\)/.test(psM) &&
           /fun merge\(/.test(inM) &&
           /fun overlapFrac\(/.test(inM) &&
           /val score: Int/.test(inM)) {
    ok("skip timestamps: AniSkip rides the season's own MAL entry (entry-relative episode) and the sources merge into one calibrated, deduped set");
  } else {
    bad("skip timestamps must query AniSkip with the season-routed MAL entry + entry-relative episode (routeMal, epBase) and merge all sources with duration calibration + overlap dedupe - the old call sent the wrong entry and a stale first-listed range shadowed the real one");
  }
}

/* ---------- 69. auto-skip seeks past, never auto-advances ---------- */
{
  const psS69 = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psS69) bad("PlayerScreen.kt missing - auto-skip rules cannot be verified");
  else {
    const autoBlock = (psS69.match(/LaunchedEffect\(cur, hit, autoSkip\) \{[\s\S]{0,700}?\n    \}/) || [""])[0];
    if (/h\.end != null && h\.kind != "credits"/.test(autoBlock) &&
        /autoDone = key\s*\n\s*seekTo\(h\.end\)/.test(autoBlock) &&
        !/advanceEpisode|goNext/.test(autoBlock)) {
      ok("auto-skip seeks past recap/intro/outro/preview and never auto-advances - the outro keeps post-credits scenes and chaining waits for ended");
    } else {
      bad("auto-skip must SEEK past ranged skips (recap/intro/outro/preview) and never auto-advance out of the episode - the chaining belongs to auto-next at ended (0.73)");
    }
  }
}

/* ---------- 70. the timeline highlights the skip marks ---------- */
{
  const psT70 = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psT70) bad("PlayerScreen.kt missing - timeline mark rules cannot be verified");
  else if (/private fun BarTimeline\(/.test(psT70) &&
           /marks: IntroDb\.Skips/.test(psT70) &&
           /val legend = remember\(marks\)/.test(psT70) &&
           /Canvas\(Modifier\.matchParentSize\(\)\)/.test(psT70) &&
           /for \(r in marks\.credits\) seg\(r, Cyan\)/.test(psT70) &&
           /marks = skips,/.test(psT70)) {
    ok("timeline highlights the skip marks: recap/intro/outro/preview paint as segments on the track with a legend line");
  } else {
    bad("the timeline must HIGHLIGHT the known skip ranges (colored segments on the track + legend) so the intro/outro/recap timestamps are visible while watching (0.73)");
  }
}

/* ---------- 71. seek bursts coalesce (one provider seek per window) ---------- */
{
  const psS71 = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psS71) bad("PlayerScreen.kt missing - seek reliability cannot be verified");
  else if (/fun sendSeekNow\(target: Float\)/.test(psS71) &&
           /if \(now - lastSeekCmdAt >= 450L\)/.test(psS71) &&
           /seekJob = seekScope\.launch/.test(psS71) &&
           /fun seekTo\(t: Float\)/.test(psS71)) {
    ok("seek bursts coalesce: the UI moves instantly but the provider gets one seek per 450ms window (trailing target wins)");
  } else {
    bad("seeking must coalesce bursts into one provider seek per 450ms window - rapid back-seeks buried the provider in in-place seek commands and froze the player (0.73)");
  }
}

/* ---------- 72. the Another World (isekai) virtual genre ---------- */
{
  const apiK = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt")) : "";
  const hsK = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt")) : "";
  if (!apiK || !hsK) bad("Api/HomeScreen missing - the Another World genre cannot be verified");
  else if (/const val GENRE_ANOTHER_WORLD = 237451/.test(apiK) &&
           /const val ANOTHER_WORLD_NAME = "Another World"/.test(apiK) &&
           /237451\|291482\|5484\|213756\|227624\|33465/.test(apiK) &&
           /with_keywords=/.test(apiK) &&
           /listOf\(Api\.GENRE_ANOTHER_WORLD to Api\.ANOTHER_WORLD_NAME\) \+ trimmed/.test(hsK)) {
    ok("Another World genre: the virtual chip rides real TMDB keywords (isekai/reincarnation/another world) on every Browse kind");
  } else {
    bad("the Another World chip must ride REAL TMDB keyword ids through discover with_keywords (237451|291482|5484|213756|227624|33465) and lead the Browse genre strip - never invented ids or fake routes (0.73)");
  }
}

/* ---------- 73. season finale + next season on detail ---------- */
{
  const dtF = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt")) : "";
  if (!dtF) bad("DetailScreen.kt missing - finale/next-season rules cannot be verified");
  else if (/"SEASON FINALE"/.test(dtF) &&
           /"Next: Season "/.test(dtF) &&
           /finale = ep\.n == \(episodes\.maxOfOrNull \{ it\.n \} \?: -1\)/.test(dtF) &&
           /val nextS = det\.seasons\.firstOrNull \{ it\.n == season \+ 1 && it\.n >= 1 \}/.test(dtF)) {
    ok("detail shows the season finale row and the next season (ready or Coming Soon) above the episodes");
  } else {
    bad("the detail screen must mark the season's last episode (SEASON FINALE) and name the NEXT season above the episodes (count or Coming Soon) - and invent nothing when none exists (0.73)");
  }
}

/* ---------- 74. thank-you screen + Continue Watching lifecycle ---------- */
{
  const psD = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const stD = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt")) : "";
  const apiD = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt")) : "";
  const hsD = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt")) : "";
  const apD = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt")) : "";
  if (!psD || !stD || !apiD || !hsD || !apD) bad("Player/Store/Api/Home/App missing - the thank-you + CW lifecycle cannot be verified");
  else if (/private fun ThanksOverlay\(/.test(psD) &&
           /"Thank you for watching"/.test(psD) &&
           /"MORE LIKE THIS"/.test(psD) &&
           /fun finishSeason\(\)/.test(psD) &&
           /showThanks = true/.test(psD) &&
           /val done: Boolean = false/.test(stD) &&
           /\.put\("done", x\.done\)/.test(stD) &&
           /optBoolean\("done", false\)/.test(stD) &&
           /fun nextAiredEpisode\(/.test(apiD) &&
           /if \(!p\.done\)/.test(hsD) &&
           /Api\.nextAiredEpisode\(p\.id, p\.season, p\.episode, p\.mal, p\.ani\)/.test(hsD) &&
           /done = false,/.test(hsD) &&
           /Thank You screen \(0\.73\)/.test(apD)) {
    ok("thank-you screen + CW lifecycle: finished episodes go DONE and out of the row, a newer aired episode revives it rolled forward");
  } else {
    bad("nothing-left must open the Thank You screen with More Like This and mark the record DONE (out of Continue Watching) - and a newer aired episode must revive the row rolled to the NEW episode itself (0.73)");
  }
}

/* ---------- 75. stall watchdog: the freeze self-heals ---------- */
{
  const psW = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psW) bad("PlayerScreen.kt missing - stall rules cannot be verified");
  else if (/Stall watchdog \(0\.74\)/.test(psW) &&
           /lastRepPos/.test(psW) &&
           /if \(now - lastMoveAt < 8000\) continue/.test(psW) &&
           /stallStep = 1/.test(psW) &&
           /stallStep = 2/.test(psW) &&
           /webGen \+= 1/.test(psW) &&
           /"video:stall" -> \{/.test(psW) &&
           /stallKick \+= 1/.test(psW) &&
           /if \(!muted\) cmd\("window\.__sb && window\.__sb\.mute\(false\)"\)/.test(psW)) {
    ok("stall watchdog: a frozen position while playing walks the ladder (nudge, reopen at startAt + volume restore, rebuild) and the frame-gap signal jumps the queue");
  } else {
    bad("the player must self-heal the freeze class (0.74) - while playing, the PROVIDER-REPORTED position must keep moving or the ladder nudges, reopens at startAt, and finally rebuilds; the JS frame-gap detector (video:stall) jumps the queue; an unmuted player is always restored unmuted");
  }
}

/* ---------- 76. render-process death rebuilds the WebView ---------- */
{
  const psR = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psR) bad("PlayerScreen.kt missing - render-process recovery cannot be verified");
  else if (/override fun onRenderProcessGone\(/.test(psR) &&
           /key\(webGen\) \{/.test(psR) &&
           /onRelease = \{ old ->/.test(psR) &&
           /old\.destroy\(\)/.test(psR) &&
           /webGen == 0 && title\.type != "anime"/.test(psR) &&
           /LaunchedEffect\(webGen\)/.test(psR) &&
           /load\(startAt = cur\)/.test(psR)) {
    ok("render-process death rebuilds the WebView (onRenderProcessGone -> key(webGen), old view destroyed in onRelease) and reopens at the live position");
  } else {
    bad("a killed render process leaves a zombie WebView that NO seek or reload can revive (0.74) - onRenderProcessGone must be handled, the view rebuilt via key(webGen) with destroy on release, the factory auto-boot gated to the first generation, and the episode reopened at the live position");
  }
}

/* ---------- 77. weak-box pressure: no runaway timers + thinned bridge + memory valve ---------- */
{
  const psP77 = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const appT77 = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/MainActivity.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/MainActivity.kt")) : "";
  if (!psP77 || !appT77) bad("PlayerScreen/MainActivity missing - pressure rules cannot be verified");
  else if (!/setInterval\(hook, 1000\)/.test(psP77) &&
           /if \(!window\.__sbHasVideo \|\| hookTicks % 5 === 0\) hook\(\);/.test(psP77) &&
           /window\.__sbFrameAt = Date\.now\(\);/.test(psP77) &&
           /clearInterval\(window\.__sbFpsInt\)/.test(psP77) &&
           /window\.__sbLastTu/.test(psP77) &&
           /tuGate\.get\(\) < 250/.test(psP77) &&
           /fun vbTrimMemory\(\)/.test(psP77) &&
           /override fun onTrimMemory/.test(appT77) &&
           /vbTrimMemory\(\)/.test(appT77)) {
    ok("weak-box pressure is down: hooks slow after the first video, the fps scan retires after attach, timeupdates thin to ~4Hz on both sides of the bridge, and memory pressure drops the shim cache");
  } else {
    bad("the shell must not carry runaway timers (0.74) - the 1s hook slows once a video is live, the fps scan retires after attach, timeupdates thin to ~4Hz on BOTH sides of the bridge, and onTrimMemory drops the shim page cache so the render process keeps headroom");
  }
}

/* ---------- 78. slow-WiFi autopilot: session-only, reported ladders only ---------- */
{
  const psN78 = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psN78) bad("PlayerScreen.kt missing - slow-WiFi rules cannot be verified");
  else if (/fun enterSlow\(\)/.test(psN78) &&
           /fun slowStep\(\)/.test(psN78) &&
           /slowSession = true/.test(psN78) &&
           /netNote = "Slow network/.test(psN78) &&
           /vbqual\('sourceQuality',/.test(psN78) &&
           /window\.__sb && window\.__sb\.q\(/.test(psN78) &&
           /cinesrcQ = next/.test(psN78) &&
           /val mp4 = \(if \(langKey != null\) vbMp4\[langKey\] else null\)/.test(psN78) &&
           /enterSlow\(\)/.test(psN78) &&
           !/Store\.[a-zA-Z]*Slow/.test(psN78)) {
    ok("slow-WiFi autopilot: stalls and slow starts lighten the stream one rung through provider-reported ladders only (AniPM heights, VidBolt quality labels + real MP4 kinds, CineSrc cycle), session-only with an honest note");
  } else {
    bad("the slow-network autopilot (0.75) must step quality down through PROVIDER-REPORTED ladders only (never invented values), prefer a real reported MP4 file at or below the rung over same-quality HLS, stay session-only (never persisted), and say so with one honest note");
  }
}

/* ---------- 79. Crisp picture pass: style injection only, never JS bodies ---------- */
{
  const psC79 = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psC79) bad("PlayerScreen.kt missing - the Crisp picture pass cannot be verified");
  else if (/function crispTo\(doc, on\)/.test(psC79) &&
           /feConvolveMatrix/.test(psC79) &&
           /sbCrispStyle/.test(psC79) &&
           /filter:url\(#sbCrispF\)/.test(psC79) &&
           /crispAll\(\);/.test(psC79) &&
           /slow: function\(on\)\{ window\.__sbSlow = !!on; crispAll\(\); \}/.test(psC79)) {
    ok("Crisp picture pass: always-on SVG sharpen + vividness injected as style into the page and its same-origin frames, standing down during slow-network recovery");
  } else {
    bad("the Crisp picture pass (0.75) must be STYLE-ONLY (SVG convolve filter + CSS, never a provider JS body), reach the page and its same-origin frames, and stand down while the slow-network autopilot owns the pipeline");
  }
}

/* ---------- 80. next-episode warm start ---------- */
{
  const psP80 = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  if (!psP80) bad("PlayerScreen.kt missing - the warm start cannot be verified");
  else if (/Next-episode warm start \(0\.75\)/.test(psP80) &&
           /fun nextTargetPair\(\): Pair<Int, Int>\?/.test(psP80) &&
           /if \(mirror != "vidbolt" \|\| slowSession \|\| showThanks \|\| !playing\) continue/.test(psP80) &&
           /if \(!prefetched\.add\(pKey\)\) continue/.test(psP80) &&
           /runCatching \{ vbShimmedDoc\(url\) \}/.test(psP80)) {
    ok("next-episode warm start: the VidBolt embed page prefetches once per episode after 45s of stable playback, never on slow sessions");
  } else {
    bad("the next-episode warm start (0.75) must prefetch the VidBolt embed page once per episode after stable playback, never on a slow session (the line belongs to the video) and never twice");
  }
}

/* ---------- 81. player states live in ONE holder (register budget) ---------- */
{
  const psH = fs.existsSync(psPath) ? qaRead(psPath, "utf8") : "";
  if (!psH) bad("PlayerScreen.kt missing - the state-holder rule cannot be verified");
  else {
    const fnS = psH.indexOf("fun PlayerScreen(");
    const fnE = psH.indexOf("/** Player timeline", fnS);
    const body = fnS >= 0 && fnE > fnS ? psH.slice(fnS, fnE) : "";
    // Only the function's OWN state declarations bloat its method - nested
    // composable lambdas compile into their own classes and may remember.
    const bareRemembers = (body.match(/^ {4}var \w+ by remember/gm) || []).length;
    if (/private class PlayerMem\(/.test(psH) &&
        /val pm = remember\(title\.id, title\.type, season, episode\) \{/.test(psH) &&
        /PlayerMem\(title, season, episode, mal, ani, lang, resumeAt, bootMirror, ctx\)/.test(body) &&
        bareRemembers === 0) {
      ok("player states live in one PlayerMem holder (one remember, no bare var-by-remember) - the method's register file stays healthy");
    } else {
      bad("PlayerScreen's states must live on ONE remembered PlayerMem (val pm) - a field of bare `var x by remember` inflates the method until ART rejects the class (copy-cat VerifyError) and the TV dies at boot (0.76)");
    }
  }
}

/* ---------- 82. anime availability = the AniPM drop ---------- */
{
  const apiG = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? qaRead(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt")) : "";
  const psG = fs.existsSync(psPath) ? qaPlayer(psPath) : "";
  const dtG = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"))
    ? qaPlayer(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt")) : "";
  if (!apiG || !psG || !dtG) bad("Api/PlayerScreen/DetailScreen missing - the drop-gate cannot be verified");
  else if (/fun aniPmHas\(mal: Int\?, aep: Int\): Boolean\?/.test(apiG) &&
           /"https:\/\/ani\.pm\/embed\/mal\/" \+ mal \+ "\/" \+ aep \+ "\/sub"/.test(apiG) &&
           /requestMethod = "HEAD"/.test(apiG) &&
           /in 200\.\.299 -> true/.test(apiG) &&
           /404 -> false/.test(apiG) &&
           /fun gateAnimeEpisodes\(/.test(apiG) &&
           /fun animeEntry\(/.test(apiG) &&
           /ep\.air\.take\(10\) < today/.test(apiG) &&
           /Api\.gateAnimeEpisodes\(title, season, raw\)/.test(dtG) &&
           /Api\.gateAnimeEpisodes\(title, epSeason, raw\)/.test(psG) &&
           /Waiting for the AniPM drop/.test(dtG)) {
    ok("anime availability = the AniPM drop: boundary episodes stay locked until the embed answers 200, early drops unlock early, and the fallback flips the day AFTER the date");
  } else {
    bad("anime episodes must unlock on the AniPM DROP (0.77) - probe the documented embed route (HEAD 200 = out, 404 = not yet), start boundary episodes LOCKED with an honest Waiting for the AniPM drop line, fall back to the day AFTER the date, and never invent stream URLs");
  }
}

console.log("\n==== StreamBox QA: " + fails + " failures, " + warns + " warnings ====");
process.exit(fails ? 1 : 0);
