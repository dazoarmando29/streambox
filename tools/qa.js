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
       "Next Episode" with no countdown suffix, and credits/preview ranges
       carry a TAIL guard - one open-ended start=0 entry matched every
       timestamp and the Next Episode pill sat over the S1E1 intro
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
       double-step */
const fs = require("fs");
const os = require("os");
const path = require("path");
const cp = require("child_process");
const ROOT = path.resolve(__dirname, "..");
const read = (f) => fs.readFileSync(path.join(ROOT, f), "utf8");

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
  const src = fs.readFileSync(path.join(wfDir, wf), "utf8");
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
      const src = fs.readFileSync(p, "utf8");
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
  const ps = fs.readFileSync(psPath, "utf8");
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
  const hlsSrc = fs.readFileSync(hlsPath, "utf8");
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
      const src = fs.readFileSync(p, "utf8");
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
  const ps2 = fs.readFileSync(psPath, "utf8");
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
      const src = fs.readFileSync(p, "utf8");
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
  const psq = fs.readFileSync(psPath, "utf8");
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
        const src = fs.readFileSync(p, "utf8");
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
  const psResume = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
        const src = fs.readFileSync(p, "utf8");
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
  const psW = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const psVb = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const psG = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const psH = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
  if (!psH) bad("PlayerScreen.kt missing - shell hook embed cannot be verified");
  else if (/"<script>" \+ SB_HOOK \+ "<\/script>"/.test(psH)) {
    ok("SB_HOOK rides the wrapped shell markup (attached before the iframe speaks)");
  } else {
    bad("SB_HOOK must be embedded in loadMirror's shell HTML - onPageFinished injection can land after the player is already talking");
  }
}

/* ---------- 28. silent-start kicks before the watchdog hops ---------- */
{
  const psK = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const apiS = fs.existsSync(apiP) ? fs.readFileSync(apiP, "utf8") : "";
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
  const appS = fs.existsSync(appP) ? fs.readFileSync(appP, "utf8") : "";
  const detPath = path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt");
  const det = fs.existsSync(detPath) ? fs.readFileSync(detPath, "utf8") : "";
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
  const hs = fs.existsSync(hsP) ? fs.readFileSync(hsP, "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  if (!hs2) bad("HomeScreen.kt missing - poster caption rules cannot be verified");
  else {
    const th2 = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"))
      ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const dsN = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
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
  const psA = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const psN = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const psSub = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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

/* ---------- 45. skip pills: plain names + tail-guarded credits ---------- */
{
  const psSkip = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
  if (!psSkip) bad("PlayerScreen.kt missing - skip pill rules cannot be verified");
  else if (/fun hitAt\(cur: Float, dur: Float/.test(psSkip) &&
           /fun inTail\(r: IntroDb\.TRange\)/.test(psSkip) &&
           /inRange\(it\) && inTail\(it\)/.test(psSkip) &&
           /SkipHit\("credits", it\.end, "Next Episode"\)/.test(psSkip) &&
           !/hit\.label \+ "  " \+ fmt/.test(psSkip)) {
    ok("skip pills: plain Skip Intro / Next Episode names and credits only count in the episode tail");
  } else {
    bad("skip pills must use plain names (no countdown) and tail-guard credits/preview ranges (bogus start=0 ranges sat over the intro)");
  }
}

/* ---------- 46. server auto-switch leads with VidBolt ---------- */
{
  const psHop = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const hsBr = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const apiBr = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const stT = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"), "utf8") : "";
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
  const psR = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const hsK = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"), "utf8") : "";
  const hsZ = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const dsZ = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/DetailScreen.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const hsH = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
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
  const psQ = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/build.gradle.kts"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/build.gradle.kts"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/App.kt"), "utf8") : "";
  const storeB = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
  const stB = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/data/Store.kt"), "utf8") : "";
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
  const psF = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
  if (!psF) bad("PlayerScreen.kt missing - player speed rules cannot be verified");
  else if (/VB_DOC_CACHE/.test(psF) &&
           /rel=\\"preconnect\\"/.test(psF) &&
           /VB_RESOLVE\[q\] = j; applyStream\(j\)/.test(psF)) {
    ok("player fast paths: cached injected page, preconnects, resolve fetched once per episode");
  } else {
    bad("player must keep VB_DOC_CACHE + preconnect links + the VB_RESOLVE single fetch");
  }
}

/* ---------- 41. catalog response cache ---------- */
{
  const apiC = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/api/Api.kt"), "utf8") : "";
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
  const psM = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const psK = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
  if (!psK) bad("PlayerScreen.kt missing - K-drama defaults cannot be verified");
  else if (/var koAuto by remember \{ mutableStateOf\(title\.lang == "ko"\) \}/.test(psK) &&
           /contains\("tagalog", true\)/.test(psK) &&
           /contains\("korean", true\)/.test(psK) &&
           /l\.contains\("english"\) \|\| l == "eng"/.test(psK) &&
           /if \(title\.lang == "ko"\) return@LaunchedEffect/.test(psK) &&
           /K-Dramas use the provider's own English subtitles/.test(psK) &&
           /window\.__sb && window\.__sb\.vbaudio\(/.test(psK) &&
           /window\.__sb && window\.__sb\.vbsub\(-1\)/.test(psK)) {
    ok("K-drama defaults: Tagalog audio (Korean fallback) + VidBolt's own English subs, our chain stays off");
  } else {
    bad("K-dramas must default to the Tagalog dub (Korean fallback) and ride VidBolt's English subs - our chain never auto-loads over them");
  }
}

/* ---------- 57. Left escapes to the TV nav rail ---------- */
{
  const thL = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/Theme.kt"), "utf8") : "";
  const hsL = fs.existsSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"))
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
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
    ? fs.readFileSync(path.join(ROOT, "android-native/app/src/main/java/com/streambox/nativeapp/ui/HomeScreen.kt"), "utf8") : "";
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
  const psE = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
  if (!psE) bad("PlayerScreen.kt missing - episode-sync rules cannot be verified");
  else {
    const lateRead = /playerUrl\(title, epSeason, epNumber, routeMal, routeAni, mirror, startAt, anipmLang, cinesrcQ, epBase\)/.test(psE);
    const rawStomp = /if \(s > 0\) epSeason = s/.test(psE) || /epNumber = o\.optInt\("episode", epNumber\) \+ 1/.test(psE);
    if (!lateRead && !rawStomp &&
        /playerUrl\(title, s, ep, rMal, rAni, mirror, startAt, lang, q, base\)/.test(psE) &&
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
  const psM = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
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
  const psA = fs.existsSync(psPath) ? fs.readFileSync(psPath, "utf8") : "";
  if (!psA) bad("PlayerScreen.kt missing - auto-next rules cannot be verified");
  else if (/if \(adoptProviderEpisode\(o\.optInt\("season", -1\), o\.optInt\("episode", -1\)\)\) \{\s*\n\s*load\(\)/.test(psA) &&
           /"ended" -> \{[\s\S]{0,1600}?\!endedHandled && pastSyncGrace\(\)/.test(psA) &&
           /endedHandled = true\s*\n\s*advanceEpisode\(\)/.test(psA) &&
           /if \(pastSyncGrace\(\)\) advanceEpisode\(\)/.test(psA) &&
           /fun pastSyncGrace\(\): Boolean/.test(psA) &&
           /endedHandled = false/.test(psA)) {
    ok("auto-next is ours: provider switches re-open through load(), ended advances once per load through advanceEpisode(), everything grace-guarded");
  } else {
    bad("auto-next must never ride the embed's own Up Next - adopted switches reload through load(), ended runs advanceEpisode() once per load (endedHandled + pastSyncGrace), and nextup is grace-guarded");
  }
}

console.log("\n==== StreamBox QA: " + fails + " failures, " + warns + " warnings ====");
process.exit(fails ? 1 : 0);
