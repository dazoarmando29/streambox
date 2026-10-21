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
   82 the dazzo credit signs the bottom right (TV legend treatment) */
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

/* ---------- 73. poster names white/bold + tracked year-dot-type meta ---------- */
{
  const m = html.match(/function cardEl\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (!m) bad("cardEl missing");
  else {
    const t = m[0];
    if (/card-title[^<]*font-bold/.test(t)) ok("poster names are bold");
    else bad("cardEl title must be font-bold (white bold names)");
    if (/card-meta/.test(t) && /tracking-/.test(t) && /\\u\{00B7\}/.test(t)) ok("tracked year-dot-type meta line present");
    else bad("cardEl needs the tracked meta line (year \\u{00B7} TYPE)");
    if (/MOVIE/.test(t) && /ANIME/.test(t) && /TV/.test(t)) ok("meta type labels normalized (MOVIE / TV / ANIME)");
    else bad("cardEl meta type labels must normalize to MOVIE / TV / ANIME");
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
  if (by && /dataset\.year = "0"/.test(by[0]) && /All/.test(by[0])) ok("year rail has the All default chip");
  else bad("buildYears must lead with the All default chip");
  if (by && /y >= 1950/.test(by[0]) && /y--/.test(by[0])) ok("year chips descend from the current year to 1950");
  else bad("buildYears must walk years descending (current year down)");
  const bm = html.match(/async function browseMore\s*\([^)]*\)\s*\{[\s\S]*?\n\}/);
  if (bm && /primary_release_year/.test(bm[0]) && /first_air_date_year/.test(bm[0])) ok("browseMore filters by year over the full paginated catalog");
  else bad("browseMore must send primary_release_year / first_air_date_year");
  if (bm && /browse\.year/.test(bm[0])) ok("browseMore reads browse.year");
  else bad("browseMore must honor browse.year");
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

console.log("\n==== StreamBox QA: " + fails + " failures, " + warns + " warnings ====");
process.exit(fails ? 1 : 0);
