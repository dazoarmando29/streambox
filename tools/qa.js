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
      capital C in App.kt - Column->oolumn, Color->oolor) */
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

console.log("\n==== StreamBox QA: " + fails + " failures, " + warns + " warnings ====");
process.exit(fails ? 1 : 0);
