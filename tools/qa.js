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
   Numbers 15-62 belong to the native track's qa (shared numbering space so
   cross-track references never collide) -- website-side invariants start at 63:
   63 K-drama chain suppression (our Wyzie/TSDB/BetaSeries chain never
      auto-loads or restores over K-dramas; VidBolt own English subs note)
   64 K-drama VidBolt defaults (Tagalog dub first, Korean fallback, English
      provider subtitles picked from the provider's own option values)
   65 manual subtitle pick stands the K-drama auto down and clears the
      provider caption layer (two layers never stack)
   66 provider episode events enter state ONLY through adoptProviderEpisode
      (no raw watchState.e writes from message callbacks)
   67 adopt guards: echo no-op, 8s settling window, season-range drop, and
      the sync clock restarts on every load AND adopt
   68 advanceEpisode owns the next-episode decision (season-end roll to next
      season E1, unaired guard) and provider nextup events run through it
   69 Auto-next runs on EVERY series on ended (one advance per load latch +
      grace guard + movie exclusion), toggle-gated and ON by default
   70 provider nextup events are grace-gated and never write raw episode
      state (no countdown auto-advance path survives)
   71 skip pill names stay PLAIN (Skip Recap / Skip Intro / Next Episode /
      Skip Credits) -- no countdown or time suffixes
   72 credits ranges keep the TAIL guard (start > 10s AND start >= dur/2)
   73 poster names are white/bold with a tracked year-dot-type meta line
   74 rating chips render only with real votes (vote_count > 0)
   75 year rail over the full paginated catalog (All default, years descending)
   76 browse grid is ONE scroll surface: chip chrome rides ABOVE the grid as
      spanning headers (never inside it) and TV never clips cards mid-poster
      (content-visibility intrinsic under-sizes = the 540dp slice)
   77 Left out of any row/grid reaches the nav rail active tab deterministically
      (strip-scope L/R, chevrons never intercept, Right stays locked in-row)
   78 per-card reveal ban: no bringIntoView anywhere, and row/grid focus
      scrolls only with block:"nearest" (the oversized reveal rect was the
      zigzag)
   79 Coming Soon flips at 00:00 PH (epUnaired anchors to phMidnightMs,
      never the device timezone) and PH labels/countdowns are the display
   80 "NEW" only within 7 days of the PH air date (epIsNew 7d window)
   81 episode status lives in ONE pinned right column (runtime / NEW /
      X left one line; COMING SOON stacks "Sun, Oct 25 (PH) - in 3d 17h")
   82 the dazzo credit signs the bottom right (TV legend treatment)
   83 caption style ladder: 12-26sp clamp, white/yellow/cyan, bg
      None/Semi/Solid, persisted (streambox_cap_style)
   84 live caption preview restyles with the ladders (both surfaces)
   85 the A/V sheet carries SERVERS | AUDIO | SUBTITLES | QUALITY |
      STYLING & SYNC; VidBolt audio by LABEL, subtitles/quality echo the
      provider's own values verbatim (numeric H index)
   86 season-aware anime routing: the season's OWN entry by AIR YEAR via
      /api/anime-map (year-keyed cache), Hajime no Ippo pinned S1=263 /
      S2=5258 / S3=19647
   87 numbering mode: same entry keeps absolute numbering (animeAbsEp), a
      new entry numbers per-season, and combined-entry provider events
      convert back from entry numbering (animeFromAbs)
   88 anime boot deferred until the season route resolves (mapping awaited
      before the embed src is set)
   89 the Anime section rides AniList genres through the Worker
      (/api/anime/genres chips, /api/anime/browse feed by genre name)
   90 Worker anime items map to the card/detail route as tv (TMDB ids, real
      votes) and the All default keeps the ja+16 discover
   91 Obsidian Glow palette (native Theme.kt parity): accent #00F0B5, cyan
      #00D2FF, ink #0B111E, amber #F5A623, soft/dim text tokens -- stale
      pre-glow hexes never come back
   92 display type is Space Grotesk, body is Plus Jakarta Sans (loaded)
   93 feed caches: TMDB dual-layer cache with a 30-min discover TTL, Worker
      anime feed cached with the same TTL + cap
   94 preconnects cover every player host + the Worker + TMDB + fonts
   95 the site is connected to our Cloudflare: TMDB rides the Worker
      /api/tmdb/3 proxy and NO API keys live in the client (no api_key=, no
      key shards, no api.themoviedb.org calls)
   96 Wyzie search rides /api/wyz/search and BetaSeries listings ride
      /api/bs-subs (server-side chains); the allorigins relay is for FILE
      downloads only and api.betaseries.com never appears
   97 auto-fallbacks NEVER land on VidRift (its bridge has no play/pause —
      the remote can't pause there): no stuckFallback("vidrift"), no auto
      setMirror("vidrift"), and stuckFallback rejects it as last-good too;
      manual Source picks may still choose it
   98 Tagalog-first K-Dramas: the Tagalog dub is ASKED at boot via the
      documented audio option (no-op when the release carries none, Korean
      fallback stays)
   99 the server sweep lands on the first Tagalog/Filipino-carrying server
      (one pass per episode, aborts on any manual pick) and the panel states
      "No Tagalog dub available" with the Filipino subtitle pointer
   100 the 7-tab house rail: Home / Movies / TV Shows / Anime / KDrama /
      My List / Browse (desktop + phone bars)
   101 the KDrama tab browses original_language=ko TV (never anime), cards
      label K-DRAMA, and Continue entries carry the ko flag
   102 tab-scoped Continue Watching: Home = everything, Movies = movies,
      TV = non-ko non-anime TV, Anime = anime, KDrama = ko; the grid surface
      shows the       scoped row; the Browse tab leads with the kind pick
   103 home hero wears the design pack: trending badge pill with the pulse
      dot, meta chips (star pill + year + type + genres), and the CTA trio
      (Watch pill + glass Details + My List bookmark via heroListToggle)
   104 Top 10 row with the giant rank numerals + the pack modal treatment
      (28px card, glow shadow, teal Watch Now)
   105 no cross-view leaks: each Continue surface shows only on its own
      screen (home row on the HOME rows, scoped row on the GRID view), and
      the Explore header never regresses to a bare section name (studio
      picks append as a suffix via browseTitle)
   106 no floating scroll-to-top button (it stacked over the remote-cursor
      control and covered the dazzo legend) and the footer keeps right-edge
      clearance so fixed bottom-right controls never cover the credit
   107 player page design: breadcrumb + server pill + Switch Server, the
      details sidebar (kind/year/votes chips, cast chips, tech specs, My
      List + Share), the engine panel quality pills, and episode chips with
      honest status words (PLAYING / Up Next / Seen / COMING SOON)
   108 details modal design: season chips + episode cards row (Resume badge,
      play disc) and the meta pill row (amber star pill, year, type, genres)
   109 the engine panel stays de-duplicated: one card per switch, ONE Motion
       Judder control, no Apply button (switches apply instantly), no second
       quality stepper beside the QLEVELS pills, engine-only inputs hidden
   110 My List correctness: the player-page button reflects the saved state
       (In My List vs + My List), and list cards open REAL details (the card
       object must carry id — the missing id opened the empty Untitled modal)
   111 the rolling release publish never deletes first (the delete-then-create
       flake lost the whole release once) — create-or-update with --clobber,
       tag survives every publish
   112 quality has no hidden floor: defaults never pin 720/480 or persist a
       default, and the stall auto-drop is session-only (soft-picture fix)
   113 Ad Shield covers EVERY player: sandbox pop-up blocking on the
       sandbox-tolerant mirrors (CineSrc included — the website-side shield
       that kills resume-click ad popups), NO_SANDBOX = only the pages that
       BREAK in sandbox, no "unavailable" dead-end, and the APK filter blocks
       ad hosts BEFORE media (pre-roll dies; stream media from real hosts
       always rides)
   114 K-Dramas boot on VidBolt (CineSrc is their fallback, never the default)
   115 Crisp Video is ALWAYS on (no toggle): the perceptual sharpening filter
       paints on the player frame for every server — honest copy, no 4K claim
   116 dormant HLS shell: OUR OWN <video> only (the one legal upscaler attach
       point), dormant until window._hlsSource is wired, dispatched from
       loadPlayer, and the upscaler never touches an iframe
   117 WebSR upscaler wiring: lazy WebGPU load (esm.sh websr + weights), the
       navigator.gpu gate, plain-playback fallback on every failure, and an
       honest "AI Upscale 2x" label (never 4K)
   118 the caption background hugs the TEXT (a .cap-pill inside the overlay)
       — never a full-bleed band across the player
   119 captions float up while the control bar is up and settle back to their
       own position when the bar minimizes again
   120 the player page survives a tab switch/reload: the watch session is
       saved on play, restored on boot, and cleared when leaving the player */
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

/* ---------- 13. workflow ASCII ---------- */
const wf = read(".github/workflows/android-apk.yml");
const nonAscii = [...new Set([...wf].filter((c) => c.charCodeAt(0) > 127))];
if (!nonAscii.length) ok("workflow YAML is pure ASCII");
else bad("workflow YAML has non-ASCII chars: " + nonAscii.map((c) => "U+" + c.charCodeAt(0).toString(16).toUpperCase()).join(" "));

/* ---------- 14. manifest parses ---------- */
try { JSON.parse(read("manifest.webmanifest")); ok("manifest.webmanifest parses"); }
catch (e) { bad("manifest.webmanifest invalid JSON: " + e.message); }

/* ---------- 63. K-drama chain suppression ---------- */
{
  const fnBody = (name) => {
    const m = html.match(new RegExp("function " + name + "\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*?\\n\\}"));
    return m ? m[0] : null;
  };
  if (fnBody("isKDrama")) ok("isKDrama() present");
  else bad("isKDrama() missing");
  const wyzAuto = fnBody("wyzAutoSubs");
  if (wyzAuto && /isKDrama\(\)/.test(wyzAuto)) ok("wyzAutoSubs never auto-loads over K-dramas");
  else bad("wyzAutoSubs missing the isKDrama() guard -- chain must never auto-load over K-dramas");
  const bsAutoB = fnBody("bsAuto");
  if (bsAutoB && /isKDrama\(\)/.test(bsAutoB)) ok("bsAuto never auto-loads over K-dramas");
  else bad("bsAuto missing the isKDrama() guard -- backups must never auto-load over K-dramas");
  const loadStored = fnBody("loadStoredSubs");
  if (loadStored && /isKDrama\(\)\s*&&\s*chain/.test(loadStored)) ok("loadStoredSubs skips chain restores over K-dramas");
  else bad("loadStoredSubs missing the K-drama chain-restore ban (manual files may still restore)");
  if (/K-Dramas use the provider's own English subtitles/.test(html)) ok("K-drama Subs note string present");
  else bad("K-drama Subs note string missing (provider tracks + note in the Subs column)");
}

/* ---------- 64. K-drama VidBolt defaults ---------- */
{
  const m = html.match(/function koAutoPick\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("koAutoPick() missing -- K-dramas need the Tagalog/English track defaults");
  else {
    const t = m[0];
    if (/tagalog\|filipino/i.test(t) && /korean/i.test(t)) ok("koAutoPick defaults Tagalog (Korean when none)");
    else bad("koAutoPick missing Tagalog-first / Korean-fallback audio pick");
    if (/option === "subtitles"/.test(t) && /english/i.test(t)) ok("koAutoPick forces VidBolt own English subtitles");
    else bad("koAutoPick missing the English provider-subtitle pick");
    if (/boltMobileOption\(\s*(au|su)\.option,\s*(au|su)\.value\s*\)/.test(t)) ok("koAutoPick echoes provider option values verbatim");
    else bad("koAutoPick must send the provider option's own value (audio label / subtitle H index) -- never a label as a command value");
  }
}

/* ---------- 65. manual pick stands the auto down, clears provider captions ---------- */
{
  const fnBody = (name) => {
    const m = html.match(new RegExp("function " + name + "\\s*\\([^)]*\\)\\s*\\{[\\s\\S]*?\\n\\}"));
    return m ? m[0] : null;
  };
  const stand = fnBody("koStandDown");
  if (stand && /_koAuto\s*=\s*false/.test(stand) && /clearProviderCaptions\(\)/.test(stand)) ok("koStandDown stands the K-drama auto down and clears provider captions");
  else bad("koStandDown incomplete (must set _koAuto=false AND call clearProviderCaptions)");
  const clear = fnBody("clearProviderCaptions");
  if (clear && /"subtitles"\s*,\s*"-1"/.test(clear)) ok("clearProviderCaptions sends VidBolt subtitles -1");
  else bad("clearProviderCaptions missing the VidBolt {subtitles:-1} command -- two caption layers must never stack");
  const manualSites = ["loadSubFile", "savePastedSubs", "wyzDownload", "bsDownload"];
  const missingStand = manualSites.filter((fn) => {
    const b = fnBody(fn);
    return !b || !/koStandDown\(\)/.test(b);
  });
  if (!missingStand.length) ok("manual subtitle picks call koStandDown (" + manualSites.length + " sites)");
  else bad("manual subtitle pick without koStandDown: " + missingStand.join(", "));
}

/* ---------- 66. provider episode events only via adoptProviderEpisode ---------- */
{
  if (/function adoptProviderEpisode\s*\(/.test(html)) ok("adoptProviderEpisode present");
  else bad("adoptProviderEpisode missing -- provider episode events need ONE validated entry path");
  const rawWrites = [...html.matchAll(/watchState\.e\s*=\s*(?:d|data)\.episode\b/g)];
  if (!rawWrites.length) ok("no raw watchState.e writes from provider messages");
  else bad("provider message writes watchState.e directly (x" + rawWrites.length + ") -- route through adoptProviderEpisode");
  const adoptCalls = [...html.matchAll(/adoptProviderEpisode\(/g)].length - 1; // minus the definition
  if (adoptCalls >= 3) ok("adoptProviderEpisode wired into " + adoptCalls + " provider episode handlers");
  else bad("adoptProviderEpisode should gate every provider episode handler (vidrift:episode / anipm episodechange / cinesrc:nextepisode) -- found " + adoptCalls + " call sites");
}

/* ---------- 67. adopt guards + sync clock ---------- */
{
  const m = html.match(/function adoptProviderEpisode\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("adoptProviderEpisode body not found");
  else {
    const t = m[0];
    if (/s === watchState\.s && e === watchState\.e/.test(t)) ok("adopt echoes of the tracked pair are no-ops");
    else bad("adoptProviderEpisode missing the echo no-op guard");
    if (/pastSyncGrace\(\)/.test(t)) ok("adopt drops settling noise via pastSyncGrace");
    else bad("adoptProviderEpisode missing the settling-window guard");
    if (/e > count/.test(t)) ok("adopt drops numbers outside the real season range");
    else bad("adoptProviderEpisode missing the season-range drop");
    if (/window\._lastLoadAt\s*=\s*Date\.now\(\)/.test(t)) ok("adopt restarts the sync clock");
    else bad("adoptProviderEpisode must restart the sync clock (window._lastLoadAt)");
  }
  const grace = html.match(/function pastSyncGrace\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (grace && /8000/.test(grace[0])) ok("pastSyncGrace uses the 8s settling window");
  else bad("pastSyncGrace must use the 8s (8000ms) settling window");
  const lp = html.match(/function loadPlayer\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (lp && /window\._lastLoadAt\s*=\s*Date\.now\(\)/.test(lp[0])) ok("loadPlayer restarts the sync clock at CALL time");
  else bad("loadPlayer must stamp window._lastLoadAt at entry");
}

/* ---------- 68. advanceEpisode owns the next-episode decision ---------- */
{
  const adv = html.match(/function advanceEpisode\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (!adv) bad("advanceEpisode missing -- Next Episode / skip credits / auto-next need ONE advance path");
  else {
    const t = adv[0];
    if (/season_number === s0 \+ 1/.test(t) && /watchState\.e = 1/.test(t)) ok("advanceEpisode rolls a season finale to the next season E1");
    else bad("advanceEpisode missing the season-end roll to next season E1");
    if (/epUnaired\(/.test(t)) ok("advanceEpisode never steps into an unaired episode");
    else bad("advanceEpisode missing the unaired guard");
    if (/window\._watchEps/.test(t)) ok("advanceEpisode consults the loaded episode list (call-time data)");
    else bad("advanceEpisode must read the loaded episode list for its target");
  }
  const ne = html.match(/function nextEpisode\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (ne && /advanceEpisode\(\)/.test(ne[0])) ok("nextEpisode(1) delegates to advanceEpisode");
  else bad("nextEpisode must delegate forward steps to advanceEpisode");
  if (/vidrift:nextup-play[\s\S]{0,400}advanceEpisode\(\)/.test(html)) ok("provider nextup events run through our own advanceEpisode");
  else bad("vidrift:nextup-play must run through advanceEpisode -- never trust raw provider numbers");
}

/* ---------- 69. Auto-next on every series (latch + grace + movie ban) ---------- */
{
  const m = html.match(/function maybeUpNext\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("maybeUpNext() missing");
  else {
    const t = m[0];
    if (/watchState\.type !== "tv"/.test(t)) ok("Auto-next never runs on movies (series only)");
    else bad("maybeUpNext must exclude movies (watchState.type !== \"tv\")");
    if (/_endedHandled/.test(t) && /pastSyncGrace\(\)/.test(t)) ok("Auto-next is one-advance-per-load (latch) + grace-guarded");
    else bad("maybeUpNext missing the endedHandled latch or the pastSyncGrace guard -- racing events double-step");
    if (/advanceEpisode\(\)/.test(t)) ok("Auto-next advances through our own advanceEpisode");
    else bad("maybeUpNext must advance through advanceEpisode (canonical route)");
    if (/autoNextOn\(\)|optAutonext/.test(t)) ok("Auto-next honors the optAutonext toggle");
    else bad("maybeUpNext must read the optAutonext toggle (directly or via autoNextOn())");
  }
  const an = html.match(/function autoNextOn\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (an && /optAutonext/.test(an[0]) && /checked/.test(an[0])) ok("autoNextOn() reads optAutonext.checked");
  else bad("autoNextOn() must read the optAutonext checkbox");
  const lp = html.match(/function loadPlayer\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (lp && /_endedHandled\s*=\s*false/.test(lp[0])) ok("endedHandled latch resets per load");
  else bad("loadPlayer must reset the _endedHandled latch (one advance per load)");
  if (/id="optAutonext"\s+checked/.test(html)) ok("Auto-next toggle defaults ON");
  else bad("optAutonext must be checked by default (auto-next is on out of the box)");
}

/* ---------- 70. nextup events grace-gated, no raw writes, no countdown path ---------- */
{
  const seg = html.match(/vidrift:nextup-play[\s\S]{0,600}/);
  if (!seg) bad("vidrift:nextup-play handler missing");
  else {
    const t = seg[0];
    if (/pastSyncGrace\(\)/.test(t) && /advanceEpisode\(\)/.test(t)) ok("nextup events are grace-gated into our advanceEpisode");
    else bad("vidrift:nextup-play must gate advanceEpisode behind pastSyncGrace");
    if (!/watchState\.(e|s)\s*=/.test(t)) ok("nextup events never write raw episode state");
    else bad("vidrift:nextup-play writes watchState directly -- route through advanceEpisode only");
  }
  const m = html.match(/function maybeUpNext\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (m && !/Date\.now\(\)\s*\+\s*10000/.test(m[0])) ok("no countdown auto-advance path survives in maybeUpNext");
  else bad("maybeUpNext must not keep a countdown auto-advance path -- Auto-next advances on ended, the card is manual");
}

/* ---------- 71. skip pill names stay plain ---------- */
{
  const m = html.match(/function paintSkipPill\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("paintSkipPill missing");
  else {
    const t = m[0];
    if (/fmtT\(/.test(t)) bad("paintSkipPill formats a time/countdown suffix -- names must stay plain");
    else ok("skip pill has no time/countdown suffix");
    if (/Skip Recap/.test(t) && /Skip Intro/.test(t) && /Next Episode/.test(t) && /Skip Credits/.test(t)) ok("skip pill names are Skip Recap / Skip Intro / Next Episode / Skip Credits");
    else bad("skip pill must use the plain names (Skip Recap / Skip Intro / Next Episode / Skip Credits)");
    if (/·/.test(t)) bad("paintSkipPill still appends a dot-suffix -- plain names only");
    else ok("skip pill labels carry no dot suffixes");
  }
}

/* ---------- 72. credits TAIL guard ---------- */
{
  const m = html.match(/function inTail\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("inTail() missing -- credits/preview ranges need the TAIL guard");
  else {
    const t = m[0];
    if (/start > 10/.test(t) && /0\.5/.test(t)) ok("inTail: start > 10s AND start >= dur/2");
    else bad("inTail must require start > 10s AND start >= dur * 0.5");
  }
  const oh = html.match(/function outroHit\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (oh && /inTail\(/.test(oh[0])) ok("outroHit enforces the TAIL guard (credits/preview)");
  else bad("outroHit must gate on inTail -- an early credits range never shows Next Episode");
}

/* ---------- 73. poster names white/bold + bullet meta line (design pack) ---------- */
{
  const m = html.match(/function cardEl\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("cardEl missing");
  else {
    const t = m[0];
    if (/card-title[^<]*font-bold/.test(t)) ok("poster names are bold");
    else bad("cardEl title must be font-bold (white bold names)");
    if (/card-meta/.test(t) && /\\u\{00B7\}/.test(t)) ok("meta line carries the year-dot-genre/type separator");
    else bad("cardEl needs the meta line with the \\u{00B7} separator (year - genres/type)");
    if (/MOVIE/.test(t) && /ANIME/.test(t) && /TV/.test(t)) ok("meta type labels normalized (MOVIE / TV / ANIME)");
    else bad("cardEl meta type labels must normalize to MOVIE / TV / ANIME");
    if (/card-overlay/.test(t) && /cap-scrim/.test(t)) ok("catalog cards use the overlay design (name/meta on the poster scrim)");
    else bad("cardEl must render the design-pack overlay variant for grids (cap-scrim)");
  }
  const css = html.match(/\.card \.card-title\s*\{[^}]*\}/);
  if (css && /#fff|white/.test(css[0])) ok("card-title color is white in CSS");
  else bad(".card .card-title must be white");
}

/* ---------- 74. rating chips = real votes only ---------- */
{
  const m = html.match(/function cardEl\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("cardEl missing");
  else {
    const t = m[0];
    if (/vote_count/.test(t) && /realVote/.test(t)) ok("rating chip gated on real votes (vote_count)");
    else bad("cardEl must gate the rating chip on vote_count > 0 (never a fake 0.0)");
    if (/realVote\s*\?\s*`<span class="rate-badge">/.test(t)) ok("rate-badge only rendered on realVote");
    else bad("rate-badge must be inside the realVote ternary");
  }
}

/* ---------- 75. year rail over the full catalog ---------- */
{
  if (/id="yearRow"/.test(html)) ok("yearRow rail present in markup");
  else bad("yearRow rail missing from the Browse markup");
  const by = html.match(/function buildYears\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (by && /All Years/.test(by[0]) && /mk\("All Years", "0"\)/.test(by[0])) ok("year rail has the All default chip");
  else bad("buildYears must lead with the All Years default chip");
  if (by && /y >= 2020/.test(by[0]) && /2010s/.test(by[0]) && /classics/.test(by[0])) ok("year rail: years descend to 2020 + decade chips (2010s/2000s/90s/Classics)");
  else bad("buildYears must walk years descending to 2020 then the decade chips");
  const bm = html.match(/async function browseMore\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bm && /primary_release_year/.test(bm[0]) && /first_air_date_year/.test(bm[0])) ok("browseMore filters by year over the full paginated catalog");
  else bad("browseMore must send primary_release_year / first_air_date_year");
  if (bm && /browse\.year/.test(bm[0]) && /\.gte=/.test(bm[0]) && /\.lte=/.test(bm[0])) ok("decade chips filter by date range (gte/lte)");
  else bad("decade/classics chips must map to date ranges in browseMore");
}

/* ---------- 76. one scroll surface, no sliced posters ---------- */
{
  // chip rails must be SIBLINGS of #grid (spanning headers above it), never children
  const gridOpen = html.match(/<div id="grid"[^>]*>/);
  const chipAfterGrid = /<div id="grid"[\s\S]{0,400}id="(genreRow|yearRow|studioRow)"/.test(html);
  if (gridOpen && !chipAfterGrid) ok("chip chrome rides above the grid (never squeezes its columns)");
  else bad("chip rows must NOT live inside #grid -- they squeeze the poster columns into a clipped window");
  if (/body\.tv-mode #grid > .card[\s\S]{0,200}content-visibility:\s*visible/.test(html)) ok("TV grids drop content-visibility (no mid-poster clipping)");
  else bad("TV browse grids must set content-visibility: visible on cards (containment slices posters on weak TVs)");
  const gRule = html.match(/#grid > \.card, #listGrid > \.card \{[^}]*\}/);
  const intr = gRule && gRule[0].match(/contain-intrinsic-size:\s*auto (\d+)px/);
  if (intr && Number(intr[1]) >= 460) ok("grid contain-intrinsic-size fits the largest card layout (" + intr[1] + "px)");
  else bad("grid contain-intrinsic-size under-sizes large cards -- raise it to >= 460px (rows rule does not count)");
}

/* ---------- 77. Left-escape to the nav rail ---------- */
{
  const nf = html.match(/function navEscapeFocus\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (nf && /nav-active/.test(nf[0]) && /navHome/.test(nf[0])) ok("navEscapeFocus lands on the nav rail active tab");
  else bad("navEscapeFocus missing -- Left out of any row/grid must reach the nav menu deterministically");
  const escapes = [...html.matchAll(/navEscapeFocus\(\)/g)].length - 1;
  if (escapes >= 2) ok("Left-escape wired at the row/grid edge (" + escapes + " call sites)");
  else bad("the Left-escape must fire when no left target exists in the strip (found " + escapes + " call sites)");
  if (/dir === "left" && inContent/.test(html)) ok("Left-escape is scoped to rows/grids (inContent)");
  else bad("the Left-escape must key off inContent -- rows/grids only");
  // strip scope must be the scrolling strip/grid, never .sb-row (chevrons intercept)
  const scope = html.match(/const wrap = ae0\.closest\([^;]+\)/);
  if (scope && /\.row-scroll/.test(scope[0]) && /#grid/.test(scope[0]) && !/\.sb-row/.test(scope[0])) ok("L/R strip scope is cards/chips only (chevrons never intercept)");
  else bad("L/R scope must be the strip/grid only (.row-scroll / #grid / #listGrid) -- .sb-row lets chevrons park Left");
}

/* ---------- 78. per-card reveal ban (no zigzag) ---------- */
{
  if (/bringIntoView/.test(html)) bad("bringIntoView present -- the oversized reveal rect was the zigzag");
  else ok("no bringIntoView anywhere (per-card reveal ban)");
  const badReveals = [];
  for (const m of html.matchAll(/\.scrollIntoView\(\{([^}]*)\}/g)) {
    const args = m[1];
    if (/block:\s*["']nearest["']/.test(args)) continue;
    const fnIdx = html.lastIndexOf("function ", m.index);
    const fnName = fnIdx >= 0 ? (html.slice(fnIdx, fnIdx + 80).match(/function\s+([A-Za-z_$][\w$]*)/) || [])[1] : "";
    const ctx = html.slice(Math.max(0, m.index - 320), m.index + m[0].length);
    if (/playerBox/.test(ctx) || fnName === "focusPlayer" || fnName === "openWatch") continue; // player landings only
    badReveals.push(ctx.trim().slice(-90));
  }
  if (!badReveals.length) ok("row/grid focus scrolls use block:\"nearest\" only (player landings excepted)");
  else badReveals.forEach((b) => bad("oversized reveal scroll (use block:\"nearest\"): " + b));
}

/* ---------- 79. Coming Soon flips at 00:00 PH ---------- */
{
  const pm = html.match(/function phMidnightMs\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (pm && /8 \* 3600 \* 1000/.test(pm[0])) ok("phMidnightMs anchors to Asia/Manila (UTC+8 midnight)");
  else bad("phMidnightMs missing/incorrect -- 00:00 PH must gate availability");
  const eu = html.match(/function epUnaired\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (eu && /phMidnightMs\(/.test(eu[0]) && !/getFullYear/.test(eu[0])) ok("epUnaired flips at 00:00 PH (never device-local dates)");
  else bad("epUnaired must compare against phMidnightMs -- device timezones must not gate playability");
  const al = html.match(/function phAirLabel\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (al && /Asia\/Manila/.test(al[0])) ok("phAirLabel formats in Asia/Manila");
  else bad("phAirLabel must format dates in Asia/Manila");
  const cd = html.match(/function phCountdown\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (cd && /in \$\{d\}d \$\{h\}h/.test(cd[0]) && /available now/.test(cd[0])) ok("phCountdown renders the in Xd Yh / available now forms");
  else bad("phCountdown missing the 'in 3d 17h' countdown forms");
}

/* ---------- 80. NEW only within 7 days ---------- */
{
  const m = html.match(/function epIsNew\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("epIsNew missing -- NEW badges need the 7-day rule");
  else {
    const t = m[0];
    if (/7 \* 86400000/.test(t) && /phMidnightMs\(/.test(t)) ok("epIsNew uses the 7-day window from the PH air date");
    else bad("epIsNew must be a 7-day (7 * 86400000) window anchored to phMidnightMs");
  }
  const dr = html.match(/async function renderEpDrawer\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (dr && /epIsNew\(/.test(dr[0])) ok("drawer status column uses epIsNew");
  else bad("renderEpDrawer must show NEW only via epIsNew");
}

/* ---------- 81. episode status in ONE pinned right column ---------- */
{
  const dr = html.match(/async function renderEpDrawer\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!dr) bad("renderEpDrawer missing");
  else {
    const t = dr[0];
    if (/flex items-start gap-3/.test(t) && /flex-1 min-w-0/.test(t) && /shrink-0 text-right/.test(t)) ok("episode row: flexible title + pinned right status column");
    else bad("renderEpDrawer rows must pin the status column right (title flex-1, status shrink-0 text-right)");
    if (/COMING SOON/.test(t)) ok("Coming Soon label present in the status column");
    else bad("the status column must label unaired episodes COMING SOON");
    if (/\(PH\)/.test(t) && /phCountdown\(/.test(t) && /phAirLabel\(/.test(t)) ok("COMING SOON stacks the PH air date + countdown");
    else bad("COMING SOON must stack 'Sun, Oct 25 (PH) - in 3d 17h' (phAirLabel + phCountdown)");
    if (/left/.test(t) && /epWatchInfo/.test(t)) ok("in-progress episodes show the X left status from our saved progress");
    else bad("the status column must show X left from epWatchInfo (saved progress)");
  }
}

/* ---------- 82. dazzo credit bottom right ---------- */
{
  const foot = html.match(/<footer[\s\S]*?<\/footer>/);
  if (foot && /dazzo/.test(foot[0]) && /10-FOOT UI/.test(foot[0])) ok("footer carries the dazzo TV-legend credit (bottom right)");
  else bad("footer must credit dazzo bottom-right with the STREAMBOX 10-FOOT UI legend");
  if (foot && /flex-1/.test(foot[0])) ok("legend is right-aligned in the footer");
  else bad("the dazzo legend must push right (flex-1 spacer)");
}

/* ---------- 83. caption style ladder ---------- */
{
  const cs = html.match(/function capSizeStep\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (cs && /Math\.min\(26,\s*Math\.max\(12,/.test(cs[0])) ok("caption size clamps to the 12-26sp ladder");
  else bad("capSizeStep must clamp size to 12-26sp");
  if (/const CAP_COLORS = \["white", "yellow", "cyan"\]/.test(html)) ok("caption colors are white/yellow/cyan");
  else bad("CAP_COLORS must be exactly white/yellow/cyan");
  if (/const CAP_BGS = \["none", "semi", "solid"\]/.test(html)) ok("caption bg is None/Semi/Solid");
  else bad("CAP_BGS must be exactly none/semi/solid");
  if (/streambox_cap_style/.test(html)) ok("caption style persists (streambox_cap_style)");
  else bad("caption style must persist via streambox_cap_style");
}

/* ---------- 84. live caption preview ---------- */
{
  if (/id="avCapPreview"/.test(html) && /id="subCapPreview"/.test(html)) ok("caption preview present on both surfaces");
  else bad("live caption preview missing (avCapPreview / subCapPreview)");
  const p = html.match(/function paintCapStyleUI\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (p && /applyCapStyle\(/.test(p[0]) && /avCapPreview/.test(p[0])) ok("preview restyles through applyCapStyle");
  else bad("paintCapStyleUI must restyle the live previews via applyCapStyle");
  const pc = html.match(/function paintCaps\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (pc && /applyCapStyle\(\s*pill\s*\)/.test(pc[0])) ok("the caption overlay paints the styled pill (12-26sp / colors / plate)");
  else bad("paintCaps must apply the caption style to the .cap-pill it renders");
}

/* ---------- 85. A/V sheet columns + provider values verbatim ---------- */
{
  const sheet = html.match(/<div id="avSheet"[\s\S]*?<\/div>\s*\n\s*<div id="vrSheet"/);
  const body = sheet ? sheet[0] : html.match(/<div id="avSheet"[\s\S]{0,2500}/)[0];
  ["SERVERS", "AUDIO", "SUBTITLES", "QUALITY", "STYLING"].forEach((h) => {
    if (body.includes(h)) ok("A/V sheet has the " + h + " column");
    else bad("A/V sheet missing the " + h + " column");
  });
  const av = html.match(/function paintBoltAV\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (av && /o\.label/.test(av[0]) && /boltMobileOption\(o\.option, o\.value\)/.test(av[0])) ok("VidBolt rows show provider labels and echo o.value verbatim");
  else bad("paintBoltAV must render labels verbatim and send the provider's own value (audio label / subtitle H index)");
  const lbl = html.match(/const lbl = \(o\.label[^;]+;/);
  if (lbl) ok("audio/track rows prefer the provider LABEL");
  else bad("A/V rows must prefer the provider label for display");
}

/* ---------- 86. season-aware anime routing ---------- */
{
  const pin = html.match(/const ANIME_SEASON_MAP\s*=\s*\[[\s\S]*?\];/);
  if (pin && /hajime no ippo\|fighting spirit/i.test(pin[0])
      && /1:\s*\{\s*mal:\s*263/.test(pin[0]) && /2:\s*\{\s*mal:\s*5258/.test(pin[0]) && /3:\s*\{\s*mal:\s*19647/.test(pin[0]))
    ok("Hajime no Ippo season route pinned (S1=263 / S2=5258 / S3=19647)");
  else bad("ANIME_SEASON_MAP must pin Hajime no Ippo S1=263 / S2=5258 / S3=19647");
  const y = html.match(/function animeSeasonAirYear\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (y && /air_date/.test(y[0])) ok("season air year comes from the season's own air_date");
  else bad("animeSeasonAirYear must read the season air_date (not just the show year)");
  if (/streambox-api\.dazoarmando\.workers\.dev\/api\/anime-map/.test(html)) ok("season entries resolve through the Worker /api/anime-map");
  else bad("anime mapping must go through the Worker /api/anime-map (year-aware)");
  const mf = html.match(/function animeMapFor\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (mf && /year/.test(mf[0])) ok("animeMapFor passes the year to the mapper");
  else bad("animeMapFor must send the air year to /api/anime-map");
  if (/sb:ani3:.*:/.test(html)) ok("anime mapping cache is year-keyed (split franchises cannot poison later seasons)");
  else bad("the sb:ani3 cache key must include the year");
}

/* ---------- 87. numbering mode (absolute vs per-season) ---------- */
{
  const rr = html.match(/function animeRouteResolve\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (!rr) bad("animeRouteResolve missing");
  else {
    const t = rr[0];
    if (/combined/.test(t) && /abs/.test(t)) ok("animeRouteResolve computes the numbering mode (abs vs per-season)");
    else bad("animeRouteResolve must decide absolute vs per-season numbering from entry identity");
    if (/window\._animeRoute\s*=/.test(t)) ok("the route (ids + mode) is published on window._animeRoute");
    else bad("animeRouteResolve must publish window._animeRoute for the event path");
  }
  const fa = html.match(/function animeFromAbs\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (fa && /season_number/.test(fa[0]) && /episode_count/.test(fa[0])) ok("animeFromAbs converts entry numbering back to TMDB s/e");
  else bad("animeFromAbs missing -- combined-entry provider events must convert back from entry numbering");
  const ec = html.match(/case "episodechange":[\s\S]{0,800}adoptProviderEpisode\(/);
  if (ec && /animeFromAbs\(/.test(ec[0])) ok("anipm episodechange converts entry numbering then adopts");
  else bad("the anipm episodechange handler must convert via animeFromAbs before adoptProviderEpisode");
  const la = html.match(/function loadAnipmPlayer\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (la && /route\.abs[\s\S]{0,120}animeAbsEp|route\.abs \?/.test(la[0])) ok("AniPM episode numbers follow the numbering mode");
  else bad("loadAnipmPlayer must pick absolute vs per-season episode numbers via route.abs");
}

/* ---------- 88. anime boot deferred ---------- */
{
  const la = html.match(/function loadAnipmPlayer\s*\(\)\s*\{[\s\S]*?\n\}/);
  const lv = html.match(/function loadVidboltAnime\s*\(\)\s*\{[\s\S]*?\n\}/);
  const inOrder = (fn, label) => {
    if (!fn) { bad(label + " missing"); return; }
    const t = fn[0];
    const routeAt = t.search(/animeRouteResolve\(/);
    const srcAt = t.search(/\.src\s*=/);
    if (routeAt >= 0 && srcAt > routeAt) ok(label + " defers boot until the route resolves");
    else bad(label + " must await animeRouteResolve BEFORE setting the embed src (deferred boot)");
  };
  inOrder(la, "loadAnipmPlayer");
  inOrder(lv, "loadVidboltAnime");
}

/* ---------- 89. AniList anime genres via the Worker ---------- */
{
  if (/api\/anime\/genres/.test(html) && /api\/anime\/browse/.test(html)) ok("Anime section calls the Worker /api/anime/genres + /api/anime/browse");
  else bad("the Anime section must use the Worker AniList genre endpoints");
  const gg = html.match(/async function getGenres\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (gg && /ANIME_GENRE_API/.test(gg[0]) && /anilist/.test(gg[0])) ok("getGenres serves AniList genres for anime (fallback TMDB)");
  else bad("getGenres must prefer the Worker AniList genre list for anime");
  const bm = html.match(/async function browseMore\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bm && /ANIME_BROWSE_API/.test(bm[0]) && /typeof browse\.genre === "string"/.test(bm[0])) ok("AniList genre names browse the Worker feed");
  else bad("browseMore must route AniList genre names through /api/anime/browse");
  const sg = html.match(/async function setGenre\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (sg && /typeof id === "string"/.test(sg[0])) ok("setGenre keeps AniList names (not coerced to numbers)");
  else bad("setGenre must keep AniList genre names as strings");
}

/* ---------- 90. Worker anime items -> cards ---------- */
{
  const fc = html.match(/function animeFeedCard\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!fc) bad("animeFeedCard missing -- Worker feed items need the card/detail adapter");
  else {
    const t = fc[0];
    if (/media_type: "tv"/.test(t) && /id: x\.id/.test(t)) ok("anime feed items route as tv with TMDB ids");
    else bad("animeFeedCard must map id + media_type \"tv\" (TMDB ids) for openDetail/openWatch");
    if (/vote_count/.test(t) && /vote_average/.test(t)) ok("anime feed items carry real-vote rating fields");
    else bad("animeFeedCard must map vote/vote_count for the real-votes rating chip");
  }
  const pt = html.match(/function posterTag\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (pt && /https?:\/\//.test(pt[0])) ok("posterTag renders absolute (Kitsu) poster URLs as-is");
  else bad("posterTag must accept absolute poster URLs from the anime feed");
  const bm = html.match(/async function browseMore\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bm && /with_original_language=ja/.test(bm[0])) ok("All-default anime browse keeps the ja+16 discover");
  else bad("the Anime All default must keep the TMDB ja + genre-16 discover");
}

/* ---------- 91. Obsidian Glow palette ---------- */
{
  if (/tailwind\.config/.test(html) && /"#00F0B5"/.test(html) && /"#00D2FF"/.test(html)) ok("tailwind palette maps to the Obsidian Glow tokens");
  else bad("tailwind.config must map teal/cyan to #00F0B5 / #00D2FF");
  if (/#0B111E/.test(html) && /#F5A623/.test(html)) ok("ink #0B111E + amber #F5A623 present");
  else bad("the ink (#0B111E) and amber (#F5A623) tokens must be in play");
  if (/"#8E9CAE"/.test(html) && /"#C7D5E8"/.test(html)) ok("dim/soft text tokens mapped (#8E9CAE / #C7D5E8)");
  else bad("gray text must wear the Dim #8E9CAE / Soft #C7D5E8 tokens");
  const stale = [...html.matchAll(/#14b8a6|#2dd4bf|#7dd3fc|#0b1426|#16294d/gi)];
  if (!stale.length) ok("no stale pre-glow palette hexes");
  else bad("stale pre-glow hex still present (x" + stale.length + ") -- the palette is Obsidian Glow only");
  if (/radial-gradient\([^)]*rgba\(0,240,181/.test(html)) ok("underlay glow washes use the accent rgba(0,240,181)");
  else bad("the body glow wash must use rgba(0,240,181,..) (native underlay glows)");
}

/* ---------- 92. Obsidian Glow type ---------- */
{
  if (/fonts\.googleapis\.com[^\n]*Space\+Grotesk/.test(html) && /Plus\+Jakarta\+Sans/.test(html)) ok("Space Grotesk + Plus Jakarta Sans loaded");
  else bad("the Google Fonts stylesheet must load Space Grotesk and Plus Jakarta Sans");
  if (/font-family:\s*"Plus Jakarta Sans"/.test(html)) ok("body type is Plus Jakarta Sans");
  else bad("body must wear Plus Jakarta Sans");
  if (/font-family:\s*"Space Grotesk"/.test(html)) ok("display type is Space Grotesk");
  else bad("titles/labels must wear Space Grotesk (display)");
}

/* ---------- 93. feed caches ---------- */
{
  const t = html.match(/function tmdb\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (t && /sessionStorage/.test(t[0]) && /_mem\.get/.test(t[0])) ok("tmdb() keeps the dual-layer cache (memory + sessionStorage)");
  else bad("tmdb() must keep the memory + sessionStorage cache layers");
  if (t && /discover\/[\s\S]{0,60}30 \* 60 \* 1000/.test(t[0])) ok("discover feeds cache 30 min (native BrowseCache TTL)");
  else bad("discover feeds must use the 30-min TTL (tab hops re-read the same pages)");
  const f = html.match(/function feedFetchJson\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (f && /FEED_TTL_MS/.test(f[0]) && /_feedMem/.test(f[0])) ok("Worker anime feed caches with the 30-min TTL + cap");
  else bad("feedFetchJson missing -- the AniList genre feed must cache (30-min TTL, capped)");
  const bm = html.match(/async function browseMore\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bm && /feedFetchJson\(/.test(bm[0])) ok("anime genre pages ride the feed cache");
  else bad("browseMore must fetch Worker pages through feedFetchJson");
}

/* ---------- 94. preconnects ---------- */
{
  const hosts = ["image.tmdb.org", "cinesrc.st", "ani.pm",
    "embed.vidrift.net", "vidzen.fun", "vidbolt.pro", "streambox-api.dazoarmando.workers.dev",
    "fonts.googleapis.com", "fonts.gstatic.com"];
  const miss = hosts.filter(hh => !html.includes(hh) || !(new RegExp("(preconnect|dns-prefetch)[^>]*" + hh.replace(/\./g, "\\."), "i").test(html) || new RegExp(hh.replace(/\./g, "\\.") + "[^>]*(preconnect|dns-prefetch)", "i").test(html)));
  // static head links may list the href before the rel: accept either order,
  // fall back to a combined window check
  const miss2 = miss.filter(hh => {
    const idx = html.indexOf(hh);
    return !(idx >= 0 && /preconnect|dns-prefetch/i.test(html.slice(Math.max(0, idx - 120), idx + 120)));
  });
  if (!miss2.length) ok("preconnect/dns-prefetch covers all " + hosts.length + " hosts (players + Worker + TMDB + fonts)");
  else bad("missing preconnect for: " + miss2.join(", "));
  if (/https:\/\/streambox-api\.dazoarmando\.workers\.dev/.test(html)) ok("Worker host is in play");
  else bad("the Worker host must appear (preconnect + endpoints)");
}

/* ---------- 95. site connected to our Cloudflare (TMDB via Worker, no keys) ---------- */
{
  const tb = html.match(/const TMDB_BASE\s*=\s*"([^"]+)"/);
  if (tb && /streambox-api\.dazoarmando\.workers\.dev\/api\/tmdb\/3/.test(tb[1])) ok("TMDB_BASE points at the Worker /api/tmdb/3 proxy");
  else bad("TMDB_BASE must point at https://streambox-api.dazoarmando.workers.dev/api/tmdb/3");
  if (/api_key=/.test(html)) bad("an api_key= parameter survives in the client -- the Worker holds keys");
  else ok("no api_key= anywhere in the client");
  if (/TMDB_API_KEY|WYZ_BUILTIN|BS_KEY|_sbk|_wzk|_bsk/.test(html)) bad("a key or key-shard blob survives in the client");
  else ok("no API keys or key shards in page source");
  if (/api\.themoviedb\.org/.test(html)) bad("a direct api.themoviedb.org call/preconnect survives -- TMDB goes through the Worker");
  else ok("no direct TMDB hosts in the client");
}

/* ---------- 96. subtitle listings ride the Worker ---------- */
{
  const wa = html.match(/async function wyzSearchApi\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (wa && /WYZ_WORKER/.test(wa[0]) && /wyzSearchApi/.test(wa[0])) ok("Wyzie search rides the Worker (/api/wyz/search) with an own-key escape hatch");
  else bad("wyzSearchApi must default to the Worker and only go direct for a user-supplied key");
  const sites = [...html.matchAll(/wyzSearchApi\(/g)].length - 1;
  if (sites >= 2) ok("both Wyzie search paths (manual + auto) ride wyzSearchApi (" + sites + " sites)");
  else bad("wyzSearch and wyzAutoSubs must both call wyzSearchApi");
  const bf = html.match(/async function bsFindSubs\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bf && /BS_WORKER/.test(bf[0]) && /api\/bs-subs/.test(html)) ok("BetaSeries listings ride the Worker (/api/bs-subs)");
  else bad("bsFindSubs must call the Worker /api/bs-subs (chain + key server-side)");
  if (/api\.betaseries\.com/.test(html)) bad("a direct api.betaseries.com call survives");
  else ok("no direct BetaSeries API calls in the client");
  const proxyUses = [...html.matchAll(/BS_PROXY\s*\+/g)];
  if (proxyUses.length && /BS_PROXY = "https:\/\/api\.allorigins\.win\/raw\?url="; \/\/ FILE downloads only/.test(html))
    ok("the allorigins relay is scoped to FILE downloads only (" + proxyUses.length + " download sites)");
  else bad("BS_PROXY must be the file-download relay only (comment-scoped), never a listing path");
}

/* ---------- 97. VidRift is never an auto-fallback ---------- */
{
  if (/stuckFallback\(\s*"vidrift"\s*\)/.test(html)) bad('stuckFallback("vidrift") survives -- VidRift has no play/pause command');
  else ok("no stuckFallback targets vidrift");
  if (/setMirror\(\s*"vidrift"\s*,\s*true\s*\)/.test(html)) bad('an auto setMirror("vidrift", true) survives -- never auto-switch to VidRift');
  else ok("no auto setMirror(\"vidrift\") hop survives");
  const sf = html.match(/function stuckFallback\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (sf && /g !== "vidrift"/.test(sf[0])) ok("stuckFallback rejects vidrift as last-known-good too");
  else bad("stuckFallback must skip vidrift even when it was the last-good mirror");
  if (/moved you to VidBolt/.test(html) && /moved you to YapGrid/.test(html)) ok("fallback messages name VidBolt/YapGrid");
  else bad("the match-failure hops must land on VidBolt then YapGrid (TMDB-direct), never VidRift");
}

/* ---------- 98. Tagalog asked at boot (K-dramas) ---------- */
{
  const ba = html.match(/function koBootAsk\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (ba && /boltMobileOption\(\s*"audio"\s*,\s*"Tagalog"\s*\)/.test(ba[0]) && /_koAuto === false/.test(ba[0]))
    ok("koBootAsk sends the documented audio option (Tagalog) at VidBolt ready");
  else bad("koBootAsk must ask for the Tagalog dub at boot via boltMobileOption(audio, Tagalog)");
  const rd = html.match(/if \(d\.type === "ready"\)\s*\{[\s\S]{0,400}/);
  if (rd && /koBootAsk\(\)/.test(rd[0])) ok("the boot ask runs on the VidBolt ready event");
  else bad("the VidBolt ready handler must call koBootAsk");
  const ap = html.match(/function koAutoPick\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (ap && /tagalog\|filipino/i.test(ap[0]) && /korean/i.test(ap[0])) ok("Korean remains the house fallback when no Tagalog exists");
  else bad("koAutoPick must keep the Tagalog-first / Korean-fallback ladder");
}

/* ---------- 99. server sweep + honest no-dub panel ---------- */
{
  const sw = html.match(/function koTagalogSweep\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (!sw) bad("koTagalogSweep missing -- VidBolt catalogs rotate, sweep their servers for the dub");
  else {
    const t = sw[0];
    if (/option === "server"/.test(t) && /boltMobileOption\(next\.option, next\.value\)/.test(t)) ok("the sweep hops provider server options (values echoed verbatim)");
    else bad("koTagalogSweep must hop the provider's own server options via boltMobileOption");
    if (/_koAuto === false/.test(t)) ok("the sweep aborts on any manual pick");
    else bad("koTagalogSweep must abort when _koAuto stands down");
    if (/window\._koSweepKey/.test(t) && /tried/.test(t)) ok("the sweep is one pass per episode (tried list + latch)");
    else bad("koTagalogSweep must be one bounded pass per episode (tried list, _koSweepKey latch)");
    if (/window\._koAutoDone = ""/.test(t)) ok("each server hop re-runs the track pick");
    else bad("a server hop must clear _koAutoDone so koAutoPick re-runs on the new server");
  }
  const nn = html.match(/function koNoTagalogNote\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (nn && /No Tagalog dub available/.test(nn[0]) && /Filipino/.test(nn[0])) ok("the panel states No Tagalog dub available with the Filipino subtitle pointer");
  else bad("koNoTagalogNote must render the honest no-dub message + Filipino subtitle pointer");
  const pav = html.match(/function paintBoltAV\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (pav && /_koAudioNote/.test(pav[0])) ok("the A/V AUDIO column shows the no-dub note");
  else bad("paintBoltAV must surface window._koAudioNote in the AUDIO column");
}

/* ---------- 100. the 7-tab house rail ---------- */
{
  const tabs = ["navHome", "navMovies", "navTv", "navAnime", "navKdrama", "navList", "navBrowse"];
  const miss = tabs.filter((t) => !html.includes('id="' + t + '"'));
  if (!miss.length) ok("all 7 desktop tabs present (Home/Movies/TV/Anime/KDrama/My List/Browse)");
  else bad("missing desktop tabs: " + miss.join(", "));
  const mtabs = ["mnavHome", "mnavMovies", "mnavTv", "mnavAnime", "mnavKdrama", "mnavList", "mnavBrowse"];
  const mmiss = mtabs.filter((t) => !html.includes('id="' + t + '"'));
  if (!mmiss.length) ok("all 7 phone tabs present");
  else bad("missing phone tabs: " + mmiss.join(", "));
  const pn = html.match(/function paintNav\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (pn && /navKdrama/.test(pn[0]) && /navBrowse/.test(pn[0])) ok("paintNav marks all 7 tabs");
  else bad("paintNav must know the KDrama + Browse tabs");
}

/* ---------- 101. KDrama tab (original_language=ko, never anime) ---------- */
{
  const bm = html.match(/async function browseMore\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bm && /with_original_language=ko/.test(bm[0])) ok("the KDrama tab browses original_language=ko TV");
  else bad("browseMore must serve the ko discover (with_original_language=ko)");
  if (bm && /_label: browse\.type === "ko" \? "K-DRAMA"/.test(bm[0])) ok("KDrama cards label K-DRAMA");
  else bad("ko browse cards must carry the K-DRAMA label");
  const gg = html.match(/async function getGenres\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (gg && /type === "ko"/.test(gg[0])) ok("getGenres maps ko to the TV genre list");
  else bad("getGenres must resolve genres for the ko tab");
  const sc = html.match(/function saveContinue\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (sc && /ko: !!watchState\.ko/.test(sc[0])) ok("Continue entries carry the ko flag");
  else bad("saveContinue must store the ko flag for tab scoping");
}

/* ---------- 102. tab-scoped Continue Watching + Browse kind pick ---------- */
{
  const cs = html.match(/function continueScopeItems\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!cs) bad("continueScopeItems missing -- Continue Watching must be tab-scoped");
  else {
    const t = cs[0];
    if (/scope === "movie"/.test(t) && /scope === "tv"/.test(t) && /scope === "anime"/.test(t) && /scope === "ko"/.test(t))
      ok("continueScopeItems scopes movie/tv/anime/ko (home = everything)");
    else bad("continueScopeItems must scope movie, non-ko tv, anime and ko");
    if (/!x\.anime && !x\.ko/.test(t)) ok("the TV scope excludes anime and K-dramas");
    else bad("the TV Continue scope must be non-ko non-anime TV");
  }
  const rc = html.match(/function renderContinue\s*\(\)\s*\{[\s\S]*?\n\}/);
  const pc = html.match(/function paintContinueRow\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (rc && /gridContinue/.test(rc[0]) && /paintContinueRow\(/.test(rc[0]) && pc && /continueScopeItems\(/.test(pc[0]))
    ok("the grid surface shows the scoped Continue row");
  else bad("renderContinue must paint the scoped row into the grid surface too (paintContinueRow + continueScopeItems)");
  if (html.includes('id="kindRow"') && /function buildKindRow/.test(html) && /openBrowseTab/.test(html))
    ok("the Browse tab leads with the kind pick (kindRow)");
  else bad("the Browse tab needs the kind pick first (kindRow + openBrowseTab + setKind)");
}

/* ---------- 103. home hero design pack ---------- */
{
  const sh = html.match(/function setHero\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!sh) bad("setHero missing");
  else {
    const t = sh[0];
    if (/Trending/.test(t) && /animate-pulse/.test(t) && /rounded-full/.test(t)) ok("hero badge pill with the pulse dot");
    else bad("the hero needs the trending badge pill (pulse dot + #N Trending label)");
    if (/heroListToggle/.test(t) && /glass-pill/.test(t) && /Watch/.test(t) && /Details/.test(t)) ok("hero CTA trio: Watch + glass Details + My List bookmark");
    else bad("the hero CTA trio must be Watch + Details + heroListToggle bookmark");
    if (/genreNames\(/.test(t) && /\\u\{2605\}/.test(t)) ok("hero meta chips: star pill + year + type + genres");
    else bad("the hero meta chips need the star pill and genre names");
  }
  const hl = html.match(/function heroListToggle\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (hl && /saveList\(/.test(hl[0]) && /inList\(/.test(hl[0])) ok("heroListToggle saves/removes My List entries");
  else bad("heroListToggle must toggle the My List store");
}

/* ---------- 104. Top 10 + modal design pack ---------- */
{
  const t10 = html.match(/function makeTop10\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (t10 && /rank-number/.test(t10[0]) && /Top 10/.test(t10[0])) ok("Top 10 row renders the giant rank numerals");
  else bad("makeTop10 must render rank-number numerals behind the posters");
  if (/\.rank-number\s*\{/.test(html) && /-webkit-text-stroke/.test(html)) ok("rank numerals use the outline treatment");
  else bad(".rank-number CSS (giant outline digits) missing");
  const init = html.match(/async function init\s*\(\)\s*\{[\s\S]*?\n  try \{[\s\S]*?\n  \}/);
  if (html.includes("makeTop10(trend.results)") && html.includes("Trending Now across StreamBox"))
    ok("home rows: Trending Now across StreamBox + Top 10 wired into init");
  else bad("init must append makeRow(Trending Now across StreamBox) + makeTop10");
  const modal = html.match(/<div id="modal"[\s\S]{0,1800}/);
  if (modal && /rounded-\[28px\]/.test(modal[0]) && /Watch Now/.test(modal[0])) ok("details modal wears the pack treatment (28px card + teal Watch Now)");
  else bad("the details modal must use the design-pack card treatment");
}

/* ---------- 105. no cross-view Continue leaks + stable Explore titles ---------- */
{
  const rc = html.match(/function renderContinue\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (rc && /rows"\)\.classList\.contains\("hidden"\)/.test(rc[0]) && /gridView"\)\.classList\.contains\("hidden"\)/.test(rc[0]))
    ok("renderContinue gates each surface on its own active view");
  else bad("renderContinue must gate the home row on #rows and the scoped row on #gridView (the home-card leak)");
  const pc = html.match(/function paintContinueRow\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (pc && /!active/.test(pc[0]) && /classList\.add\("hidden"\)/.test(pc[0])) ok("paintContinueRow hides inactive surfaces");
  else bad("paintContinueRow must hide the row when its surface is not active");
  const bt = html.match(/function browseTitle\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bt && /Explore Movies/.test(bt[0]) && /Explore K-Dramas/.test(bt[0])) ok("browseTitle owns the Explore header names");
  else bad("browseTitle missing -- Explore titles must come from one helper");
  const psc = html.match(/function paintStudioChips\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (psc && /browseTitle\(browse\.type\)/.test(psc[0]) && !/textContent = st \? `\$\{base\}/.test(psc[0]))
    ok("studio picks append as a suffix -- the Explore header never regresses");
  else bad("paintStudioChips must build the title from browseTitle (never a bare Movies/TV Shows)");
}

/* ---------- 106. no floating scroll-top + dazzo legend clearance ---------- */
{
  if (/aria-label="Scroll to top"/.test(html) || /window\.scrollTo\(\{top:0/.test(html))
    bad("a floating scroll-to-top button survives -- it stacked over the remote-cursor control and covered the dazzo legend");
  else ok("no floating scroll-to-top button");
  const foot = html.match(/<footer[\s\S]*?<\/footer>/);
  if (foot && /padding-right:\s*\d\dpx/.test(foot[0]) && /dazzo/.test(foot[0])) ok("footer keeps inline right-edge clearance for the dazzo legend");
  else bad("the footer must carry inline padding-right clearance (class padding loses to md:px-*) so fixed controls never cover dazzo");
  if (/#cursorBtn \{ display: none !important; \}/.test(html) && /body\.tv-mode #cursorBtn[\s\S]{0,120}inline-flex/.test(html))
    ok("remote-cursor button shows on TV only (a mouse is the pointer elsewhere)");
  else bad("cursorBtn must hide outside TV -- on desktop it sat over the dazzo legend");
  if (/body\.watch-on #cursorBtn/.test(html)) ok("the watch-on cursor hide survives");
  else bad("the body.watch-on #cursorBtn hide must stay (no cursor button over playback)");
}

/* ---------- 107. player page design pack ---------- */
{
  if (html.includes('id="watchCrumb"') && html.includes('id="serverPillName"') && /Switch Server/.test(html))
    ok("player breadcrumb + server pill + Switch Server present");
  else bad("the player page needs the breadcrumb bar (watchCrumb + serverPillName + Switch Server)");
  ["wKind", "wYear", "wRating", "wAudio", "wQual", "wEngine", "wCastLabel"].forEach((id) => {
    if (!html.includes('id="' + id + '"')) bad("sidebar element missing: #" + id);
  });
  if (/id="wKind"/.test(html) && /wAudio/.test(html) && /Source Engine/.test(html)) ok("details sidebar carries the chips + tech-specs box");
  else bad("the watch sidebar needs kind/year/votes chips and the tech-specs box");
  const wl = html.match(/function watchListToggle\s*\(\)\s*\{[\s\S]*?\n\}/);
  const sh = html.match(/function shareTitle\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (wl && sh && /watchListToggle\(\)/.test(html) && /shareTitle\(\)/.test(html)) ok("sidebar actions: My List toggle + Share");
  else bad("watchListToggle / shareTitle must exist and be wired");
  const qp = html.match(/function paintQPills\s*\(\)\s*\{[\s\S]*?\n\}/);
  const qpk = html.match(/function qualityPick\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (qp && qpk && /QLEVELS/.test(qp[0])) ok("engine panel quality pills ride QLEVELS (no invented 4K)");
  else bad("paintQPills/qualityPick missing -- the engine panel needs the QLEVELS pills");
  const le = html.match(/async function loadEpisodes\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (le && /PLAYING/.test(le[0]) && /Up Next/.test(le[0]) && /COMING SOON/.test(le[0]) && /Seen/.test(le[0]))
    ok("episode chips show the honest status words");
  else bad("loadEpisodes chips must show PLAYING / Up Next / Seen / COMING SOON");
  if (le && !/4[Kk]|HD\b/.test(le[0].replace(/watchState/g, ""))) ok("episode chips carry no invented quality labels");
  else bad("episode chips must not invent HD/4K labels");
}

/* ---------- 108. details modal design pack ---------- */
{
  if (html.includes('id="mSeasonChips"') && html.includes('id="mEpCards"')) ok("modal season chips + episode cards row present");
  else bad("the details modal needs mSeasonChips + mEpCards");
  const us = html.match(/function updateModalSeasonLabel\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (us && /modalSeasonPick/.test(us[0]) && /Season /.test(us[0])) ok("season chips are built per season (year-tagged)");
  else bad("updateModalSeasonLabel must build the season chips");
  const ue = html.match(/function updateModalEpLabel\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (ue && /Resume/.test(ue[0]) && /mEpCards/.test(ue[0]) && /COMING SOON/.test(ue[0]))
    ok("episode cards carry Resume badges + Coming Soon lines");
  else bad("updateModalEpLabel must build the episode cards (Resume + status)");
  const mm = html.match(/getElementById\("mMeta"\)\.innerHTML = `[\s\S]{0,1200}/);
  if (mm && /amber-400\/15/.test(mm[0]) && /genres/.test(mm[0])) ok("modal meta wears the pill row (amber star pill + year + type + genres)");
  else bad("mMeta must render the design pill row");
}

/* ---------- 109. engine panel de-duplicated ---------- */
{
  if (/applyPlayerOpts\(\)/.test(html.replace(/function applyPlayerOpts[^}]*\}/, "")))
    bad("an Apply button survives -- switches apply instantly and Apply was a disguised Reload");
  else ok("no Apply button in the engine panel");
  const stepper = html.match(/onclick="qualityStep\(-1\)"/);
  if (stepper) bad("the old quality stepper survives beside the QLEVELS pills -- pick one (the pills)");
  else ok("no duplicate quality stepper (QLEVELS pills own it)");
  const cards = [...html.matchAll(/id="opt(Shield|TvSafe|Autonext|Autoskip|Autoswitch)"/g)];
  if (cards.length === 5) ok("the 5 switches live in one card each");
  else bad("switches must render as individual cards (found " + cards.length + " of 5)");
  const j = html.match(/id="optJudder"/g);
  if (j && j.length === 1 && /Motion Judder/.test(html) && /id="judderLabel"/.test(html))
    ok("ONE Motion Judder control with its readout (no split labels)");
  else bad("Motion Judder must be a single control with a readout label");
  if (/id="optAutoplay" checked class="hidden"/.test(html) && /id="optColor"[^>]*class="hidden"/.test(html) && /id="optMotion" checked class="hidden"/.test(html))
    ok("engine-only inputs kept hidden (JS still reads them)");
  else bad("optAutoplay/optColor/optMotion must stay as hidden inputs -- engine code reads them");
  if (/id="engineChip"/.test(html)) ok("the engine chip carries the real WebView version");
  else bad("engineChip missing (Chrome nnn Modern)");
}

/* ---------- 110. My List correctness ---------- */
{
  const pw = html.match(/function paintWatchListBtn\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (pw && /inList\(/.test(pw[0]) && /In My List/.test(pw[0])) ok("paintWatchListBtn reflects the saved state");
  else bad("paintWatchListBtn missing -- the player-page button must show In My List vs + My List");
  const wt = html.match(/function watchListToggle\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (wt && /paintWatchListBtn\(\)/.test(wt[0])) ok("watchListToggle repaints the button after toggling");
  else bad("watchListToggle must repaint the My List button");
  const ow = html.match(/async function openWatch\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (ow && /paintWatchListBtn\(\)/.test(ow[0])) ok("openWatch paints the My List button state");
  else bad("openWatch must paint the My List button state on load");
  const rl = html.match(/function renderList\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (rl && /id: x\.id/.test(rl[0]) && /vote_count: x\.votes/.test(rl[0]) && /first_air_date/.test(rl[0]))
    ok("list cards carry id + votes + year (real details, not the Untitled shell)");
  else bad("renderList must pass id/votes/year into cardEl -- without id openDetail opened an empty modal");
  const clicks = [...html.matchAll(/cardEl\(\{[^}]*\},/g)].map((m) => m[0]);
  const noId = clicks.filter((c) => !/id:/.test(c) && !/\.\.\.x/.test(c));
  if (!noId.length) ok("every synthetic cardEl object carries an id");
  else bad("cardEl called with a synthetic object missing id: " + noId[0].slice(0, 80));
}

/* ---------- 111. rolling release publish never deletes first ---------- */
{
  const wfy = read(".github/workflows/android-apk.yml");
  if (/gh release delete/.test(wfy)) bad("the workflow deletes the rolling release before recreating it -- the delete-create flake lost the release once");
  else ok("no gh release delete in the publish step");
  if (/gh release view latest[\s\S]{0,200}gh release edit latest/.test(wfy) && /--clobber/.test(wfy))
    ok("publish is create-or-update (view -> edit + upload --clobber)");
  else bad("the publish step must create-or-update: gh release view + edit + upload --clobber (or create when missing)");
  if (/gh release create latest/.test(wfy) && /gh release upload latest/.test(wfy)) ok("both paths publish both APKs");
  else bad("the publish step must cover create (missing) and upload (existing) paths");
}

/* ---------- 112. quality has no hidden floor ---------- */
{
  const qd = html.match(/function qDropAuto\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (qd && !/localStorage\.setItem\(\s*"streambox_quality"/.test(qd[0])) ok("the stall auto-drop is session-only (never persisted)");
  else bad("qDropAuto must never persist the drop -- viewers got stuck below HD forever");
  const qdef = html.match(/const QLEVELS[\s\S]{0,900}/);
  if (qdef && !/localStorage\.setItem\(\s*"streambox_quality"/.test(qdef[0])) ok("quality defaults never persist a floor");
  else bad("the quality default block must not persist 720/480 floors");
  if (qdef && !/"720"\); \}\s*\}?\s*else if \(\(typeof IS_TV_DEVICE/.test(qdef[0]) && !/findIndex\(x => x\.v === "480"\)/.test(qdef[0]))
    ok("no TV 720p / calm 480p default pins (Auto serves best)");
  else bad("the old TV 720p / calm 480p default pins must stay gone -- they made 1080p sources look soft");
  const manual = [...html.matchAll(/localStorage\.setItem\(\s*"streambox_quality"/g)].length;
  if (manual <= 3) ok("streambox_quality is written only by manual picks (" + manual + " sites)");
  else bad("streambox_quality written from " + manual + " sites -- only manual quality picks may persist");
}

/* ---------- 113. Ad Shield everywhere (website sandbox + APK filter) ---------- */
{
  const ns = html.match(/const NO_SANDBOX = new Set\(\[([^\]]*)\]\)/);
  if (ns && /"vidbolt"/.test(ns[1]) && /"vidrift"/.test(ns[1]) && /"anipm"/.test(ns[1])
      && !/"cinesrc"/.test(ns[1]) && !/"vidzen"/.test(ns[1]))
    ok("NO_SANDBOX is only the sandbox-breaking set (CineSrc/VidZen keep the pop-up block)");
  else bad("NO_SANDBOX must be the BREAK set only -- CineSrc/VidZen need sandbox popup-blocking on the website");
  if (/Ad Shield unavailable/.test(html)) bad("the Ad Shield unavailable path survives -- the shield covers every player");
  else ok("no Ad Shield unavailable dead-end");
  const am = html.match(/function applyMirrorSandbox\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (am && /sh\.disabled = false/.test(am[0]) && !/disabled = true/.test(am[0]) && /setAttribute\("sandbox"/.test(am[0]))
    ok("sandboxed mirrors get the pop-up block with the shield toggle live");
  else bad("applyMirrorSandbox must apply sandbox on tolerant mirrors and never disable the toggle");
  const ts = html.match(/function toggleShield\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (ts && /setAttribute\("sandbox"/.test(ts[0])) ok("toggleShield drives the sandbox pop-up block");
  else bad("toggleShield must drive the sandbox pop-up block (the website-side ad shield)");
  let ma = "";
  try { ma = read("android/app/src/main/java/com/streambox/app/MainActivity.java"); } catch (e) {}
  const blockFirst = ma && /ad hosts die FIRST[\s\S]{0,300}isAdHost/.test(ma) && /media second/.test(ma);
  if (ma && blockFirst) ok("the APK filter blocks ad hosts BEFORE media (pre-roll dies, streams ride)");
  else bad("MainActivity must block ad hosts first and only then honor media extensions");
}

/* ---------- 114. K-Dramas boot on VidBolt ---------- */
{
  const ow = html.match(/async function openWatch\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (ow && /watchState\.ko \? "vidbolt" : "cinesrc"/.test(ow[0])) ok("K-Dramas open on VidBolt (CineSrc is their fallback)");
  else bad("openWatch must route K-Dramas to vidbolt with cinesrc as fallback");
  const fbSites = [...html.matchAll(/stuckFallback\(/g)].map((m) => html.slice(m.index, m.index + 180));
  const wrong = fbSites.filter((f) => /anime \? "anipm"/.test(f) && !/isKDrama\(\) \? "cinesrc"/.test(f));
  if (!wrong.length) ok("every K-drama-capable fallback lands on CineSrc (" + fbSites.length + " sites)");
  else bad("fallback must name cinesrc for K-dramas: " + wrong[0].slice(0, 90));
}

/* ---------- 115. Crisp Video always on (no toggle) ---------- */
{
  if (/id="optCrisp"|function crispToggle|function applyCrisp/.test(html))
    bad("a Crisp Video toggle survives -- it is always on, no toggle");
  else ok("Crisp sharpening has no toggle (always on)");
  if (/#playerBox #player \{[^}]*filter:\s*contrast\(/.test(html)) ok("the sharpening filter paints on the player frame for every server");
  else bad("#playerBox #player must carry the always-on sharpening filter");
  const crispCss = html.match(/#playerBox #player \{[^}]*\}/);
  if (crispCss && !/4[Kk]/.test(crispCss[0])) ok("crisp styling makes no 4K claims");
  else bad("the crisp filter must not claim 4K -- it is perceptual sharpening only");
}

/* ---------- 116. dormant HLS shell (own video only) ---------- */
{
  if (html.includes('id="hlsVideo"') && html.includes('id="hlsShell"') && html.includes('id="hlsCanvas"'))
    ok("the HLS shell carries its own video + upscale canvas");
  else bad("the HLS shell slot needs hlsShell/hlsVideo/hlsCanvas");
  const lp = html.match(/function loadPlayer\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (lp && /mirror === "hls"/.test(lp[0]) && /loadHlsPlayer\(\)/.test(lp[0]))
    ok("loadPlayer dispatches the hls mirror to the shell");
  else bad("loadPlayer must dispatch mirror === \"hls\" to loadHlsPlayer");
  const lh = html.match(/function loadHlsPlayer\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (lh && /window\._hlsSource/.test(lh[0]) && /dormant/.test(lh[0])) ok("the shell stays dormant until _hlsSource is wired");
  else bad("loadHlsPlayer must gate on window._hlsSource and say so while dormant");
  const au = html.match(/async function attachHlsUpscaler\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (au && /hlsVideo/.test(au[0]) && !/contentWindow|getElementById\("player"\)/.test(au[0]))
    ok("the upscaler attaches ONLY to our own video element (never an iframe)");
  else bad("attachHlsUpscaler must target #hlsVideo only -- embed iframes can never be upscaled");
  const tv = html.match(/const TV_REMOTE_OK = new Set\(\[([^\]]*)\]\)/);
  if (tv && /"hls"/.test(tv[1])) ok("the shell is TV-remote drivable (first-party player)");
  else bad("TV_REMOTE_OK must include the hls shell (it is our own bar-driven player)");
}

/* ---------- 117. WebSR wiring (lazy, graceful, honest) ---------- */
{
  const au = html.match(/async function attachHlsUpscaler\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (!au) bad("attachHlsUpscaler missing");
  else {
    const t = au[0];
    if (/import\("https:\/\/esm\.sh\/@websr\/websr/.test(t) && /fetch\("https:\/\/katana\.video\/files\/cnn-2x/.test(t))
      ok("WebSR + Anime4K weights load lazily (only when the shell plays)");
    else bad("attachHlsUpscaler must lazy-load WebSR and its weights on first use");
    if (/navigator\.gpu/.test(t)) ok("the WebGPU gate runs before init");
    else bad("attachHlsUpscaler must gate on navigator.gpu");
    if (/catch/.test(t) && /catch\(\(\) =>/.test(t)) ok("every failure path falls back to plain playback");
    else bad("the upscaler needs plain-playback fallbacks (init catch + render catch)");
    if (/AI Upscale 2x/.test(t) && !/\b4[Kk]\b/.test(t)) ok("the label is honest AI Upscale 2x (never 4K)");
    else bad("the upscaler status must say AI Upscale 2x -- never a 4K claim");
  }
}

/* ---------- 118. caption background hugs the text ---------- */
{
  const pc = html.match(/function paintCaps\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (pc && /cap-pill/.test(pc[0]) && /applyCapStyle\(pill\)/.test(pc[0])) ok("the caption styles the inner pill, not the full-width overlay");
  else bad("paintCaps must render a .cap-pill and style THAT -- the overlay background painted a full-bleed band");
  if (/applyCapStyle\(ov\)/.test(html)) bad("applyCapStyle is still applied to the overlay itself (full-bleed background)");
  else ok("the overlay itself never gets a background");
  const ac = html.match(/function applyCapStyle\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (ac && !/8%/.test(ac[0]) && /padding/.test(ac[0])) ok("caption padding hugs the text (no percentage bleed)");
  else bad("applyCapStyle must use tight pixel padding -- percentage padding bleeds across the player");
}

/* ---------- 119. captions float with the bar ---------- */
{
  if (/#capOverlay\.bar-lift\.cap-bottom/.test(html) && /#capOverlay\.bar-lift\.cap-center/.test(html))
    ok("caption lift positions exist for bottom/center (the float)");
  else bad("#capOverlay.bar-lift positions missing -- captions must float while the bar is up");
  const cf = html.match(/function capFloat\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (cf && /bar-lift/.test(cf[0])) ok("capFloat toggles the lift class");
  else bad("capFloat must toggle the bar-lift class");
  const hh = html.match(/function hideHud\s*\(\)\s*\{[\s\S]*?\n\}/);
  const ph = html.match(/function pokeHud\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (hh && /capFloat\(false\)/.test(hh[0])) ok("bar minimized: the caption settles back down");
  else bad("hideHud must call capFloat(false) -- captions settle when the bar hides");
  if (ph && /capFloat\(true\)/.test(ph[0])) ok("bar up: the caption floats above it");
  else bad("pokeHud must call capFloat(true) -- captions float while the bar is up");
}

/* ---------- 120. watch session survives tab switches ---------- */
{
  const sw = html.match(/function saveWatchSession\s*\(\)\s*\{[\s\S]*?\n\}/);
  const cl = html.match(/function clearWatchSession\s*\(\)\s*\{[\s\S]*?\n\}/);
  const rs = html.match(/function restoreWatchSession\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (sw && cl && rs && /streambox_watch/.test(sw[0] + rs[0])) ok("the watch session is saved and restored (streambox_watch)");
  else bad("saveWatchSession / clearWatchSession / restoreWatchSession must exist around streambox_watch");
  const ow = html.match(/async function openWatch\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (ow && /saveWatchSession\(\)/.test(ow[0])) ok("opening a title records the session");
  else bad("openWatch must record the watch session");
  const gh = html.match(/function goHome\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (gh && /clearWatchSession\(\)/.test(gh[0])) ok("leaving the player clears the session");
  else bad("goHome must clear the watch session");
  const ini = html.match(/async function init\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (ini && !/restoreWatchSession\(\)/.test(ini[0])) ok("boot does NOT restore the watch session (a reload lands on Home, tab-switch keeps the page as-is)");
  else bad("init must NOT call restoreWatchSession() -- a reload must land on Home, not jump back into the last video");
  if (rs && /search/.test(rs[0]) && /openWatch\(/.test(rs[0])) ok("restore reopens the title; deep links win");
  else bad("restoreWatchSession must reopen the title and let deep links win");
}

/* ---------- 121. every host the site touches is pre-warmed ---------- */
{
  const warm = ["api.theintrodb.org", "via.placeholder.com",
    "api.jikan.moe", "api.consumet.org", "graphql.anilist.co"];
  const miss = warm.filter(hh => {
    const re = new RegExp("(preconnect|dns-prefetch)[^>]*" + hh.replace(/\./g, "\\."), "i");
    const re2 = new RegExp(hh.replace(/\./g, "\\.") + "[^>]*(preconnect|dns-prefetch)", "i");
    if (re.test(html) || re2.test(html)) return false;
    const idx = html.indexOf(hh);
    return !(idx >= 0 && /preconnect|dns-prefetch/i.test(html.slice(Math.max(0, idx - 120), idx + 120)));
  });
  if (!miss.length) ok("preconnect/dns-prefetch warms every host the player touches (intro ranges, fallback posters, anime mapping)");
  else bad("missing preconnect/dns-prefetch for: " + miss.join(", "));
}

/* ---------- 122. paint-as-you-land ---------- */
{
  const hero = /fetchpriority="high"[^>]*decoding="async"|decoding="async"[^>]*fetchpriority="high"/.test(html);
  if (hero) ok("hero backdrop loads eager + async (fetchpriority=high, decoding=async)");
  else bad("hero backdrop must use fetchpriority=high + decoding=async (first paint, no layout shift)");
  const initFn = html.match(/async function init\s*\(\)\s*\{[\s\S]*?\n\}/);
  if (initFn) {
    const sk = initFn[0].indexOf("skeletonRow");
    const tm = initFn[0].indexOf("withTimeout(tmdb");
    if (sk >= 0 && tm >= 0 && sk < tm) ok("skeleton rows paint before the first catalog fetch");
    else bad("init() must paint skeleton rows before awaiting the first catalog page");
  } else bad("init() missing");
}

/* ---------- 123. D-pad never strands on an untagged control ---------- */
{
  const mo = html.match(/new MutationObserver[\s\S]{0,3000}/);
  if (mo && /\.tabIndex\s*=\s*0/.test(mo[0])
      && /setAttribute\("role",\s*"button"\)/.test(mo[0])
      && /data-focusable/.test(mo[0]))
    ok("MutationObserver tags late-added cards/buttons (tabindex=0, role=button, data-focusable=1)");
  else bad("the MutationObserver must tag every dynamically added focusable so TV D-pad focus never strands");
}

/* ---------- 124. motion stays scoped (TV SoC safe) ---------- */
{
  if (/:root\s*\{\s*--mot/.test(html) && !/\*\s*\{[^}]*transition/.test(html))
    ok("UI transitions are scoped via --mot, never a universal * rule that re-resolves on TV SoCs");
  else bad("transitions must be scoped to a named list driven by --mot, not a universal * rule");
  if (/body\.calm \*, body\.calm \*::before, body\.calm \*::after/.test(html)
      && /animation:\s*none !important/.test(html)
      && /transition:\s*none !important/.test(html))
    ok("calm mode kills animation and transitions everywhere (max smoothness, no melt)");
  else bad("body.calm must kill animation and transitions everywhere");
}

console.log("\n==== StreamBox QA: " + fails + " failures, " + warns + " warnings ====");
process.exit(fails ? 1 : 0);
