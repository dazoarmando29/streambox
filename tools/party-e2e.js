"use strict";
/* StreamBox Watch Party - REAL BROWSER end-to-end synchronization test.

   Boots the actual index.html PARTY engine in TWO (or three) headless Edge
   pages, connected over REAL WebSockets to a REAL local party-server, each
   driving a controllable playback adapter (fake media clock behind the real
   CineSrc adapter surface). This exercises the shipped sync engine end to
   end: join positioning, play/pause/seek propagation, coordinated episode
   gates, slow-player catch-up, reconnection, and the drift distribution.

   What this does NOT prove: real third-party embed behavior (cross-origin
   iframes) or iPhone Safari - those need physical devices (documented in
   WATCH-PARTY-RELIABILITY-REPORT.md). Chrome-family only, like the rest of
   tools/ (same CDP driver - no new deps).

   Usage: node tools/party-e2e.js            (exit 0 = all scenarios pass) */

const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { spawn } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const { createPartyServer } = require(path.join(ROOT, "party-server", "server"));

let WSImpl = globalThis.WebSocket;
if (!WSImpl) {
  try { WSImpl = require(path.join(ROOT, "party-server", "node_modules", "ws")); }
  catch (e) { WSImpl = require("ws"); }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.wait = new Map(); }
  static async connect(port) {
    const ver = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
    const ws = new WSImpl(ver.webSocketDebuggerUrl);
    await new Promise((res, rej) => {
      if (typeof ws.addEventListener === "function") { ws.addEventListener("open", res); ws.addEventListener("error", rej); }
      else { ws.on("open", res); ws.on("error", rej); }
    });
    const c = new CDP(ws);
    const onMsg = (d) => {
      const m = JSON.parse(d.toString ? d.toString() : d);
      if (m.id && c.wait.has(m.id)) {
        const { res, rej } = c.wait.get(m.id);
        c.wait.delete(m.id);
        if (m.error) rej(new Error(m.error.message)); else res(m.result);
      }
    };
    if (typeof ws.addEventListener === "function") ws.addEventListener("message", (e) => onMsg(e.data));
    else ws.on("message", onMsg);
    return c;
  }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    return new Promise((res, rej) => {
      this.wait.set(id, { res, rej });
      this.ws.send(JSON.stringify({ id, method, params, sessionId }));
    });
  }
}

let pass = 0, fail = 0;
function ok(msg) { pass++; console.log("ok    " + msg); }
function bad(msg) { fail++; console.log("FAIL  " + msg); }
function check(cond, msg) { if (cond) ok(msg); else bad(msg); }

/* static server: index.html + the seekable test video fixture */
function serve() {
  const srv = http.createServer((req, res) => {
    const u = new URL(req.url, "http://x");
    let file = u.pathname === "/" ? "/index.html" : u.pathname;
    const fp = path.join(ROOT, file.replace(/\//g, path.sep));
    if (!fp.startsWith(ROOT) || !fs.existsSync(fp) || fs.statSync(fp).isDirectory()) {
      res.writeHead(404); res.end("nope"); return;
    }
    // proper MIME or Chrome refuses stylesheets (unstyled page)
    const MIME = { ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript", ".png": "image/png", ".mp4": "video/mp4" };
    const type = MIME[path.extname(fp).toLowerCase()] || "application/octet-stream";
    res.writeHead(200, { "content-type": type, "cache-control": "no-store" });
    res.end(fs.readFileSync(fp));
  });
  return new Promise((r) => srv.listen(0, "127.0.0.1", () => r(srv)));
}

/* the controllable playback adapter: a WALL-CLOCK fake media clock behind the
   real CineSrc adapter surface (sendCmd/pState) - the PARTY sync engine, drift
   corrector and bridges run unmodified against it. Wall-clock (not timer)
   based: hidden/background tabs throttle timers, real media never stops. */
function fakePlayerInstall(readyDelayMs) {
  return `
    window.__fake = { basePos: 0, baseAt: Date.now(), dur: 120, playing: false, rate: 1, readyAfter: Date.now() + ${readyDelayMs || 0}, log: [] };
    window.__fakePos = function(){
      var f = window.__fake;
      if (!f.playing) return f.basePos;
      return Math.min(f.dur, f.basePos + ((Date.now() - f.baseAt) / 1000) * f.rate);
    };
    mirror = "cinesrc";
    Object.defineProperty(pState, "cur", { get: window.__fakePos,
      set: function(v){ var f = window.__fake; f.basePos = Number(v) || 0; f.baseAt = Date.now(); } });
    Object.defineProperty(pState, "dur", {
      get: function(){ return Date.now() >= window.__fake.readyAfter ? window.__fake.dur : 0; },
      set: function(){} });
    Object.defineProperty(pState, "paused", {
      get: function(){ return !window.__fake.playing; },
      set: function(v){ var f = window.__fake; f.basePos = window.__fakePos(); f.baseAt = Date.now(); f.playing = !v; } });
    window.openWatch = function(type, id, s, e){
      watchState.type = type; watchState.id = id; watchState.s = s || 1; watchState.e = e || 1;
      try { document.getElementById("watchView").classList.remove("hidden"); } catch(err){}
      window._lastLoadAt = Date.now();
      var f = window.__fake;
      f.basePos = 0; f.baseAt = Date.now(); f.playing = false;
    };
    window.sendCmd = function(cmd, args){
      var f = window.__fake;
      f.log.push([Math.round(Date.now() % 100000), cmd, JSON.stringify(args || [])]);
      if (cmd === "play") { f.basePos = window.__fakePos(); f.baseAt = Date.now(); f.playing = true; }
      else if (cmd === "pause") { f.basePos = window.__fakePos(); f.baseAt = Date.now(); f.playing = false; }
      else if (cmd === "seek") { f.basePos = Math.max(0, Math.min(f.dur, (args && args[0]) || 0)); f.baseAt = Date.now(); }
      else if (cmd === "setPlaybackRate") { f.basePos = window.__fakePos(); f.baseAt = Date.now(); f.rate = (args && args[0]) || 1; }
    };
  `;
}

const SAMPLE_DRIFT = `
  (function(){
    try {
      var pb = PARTY.state.room && PARTY.state.room.playback;
      if (!pb) return null;
      var nowS = Date.now() + PARTY.state.offset;
      var p = Number(pb.position) || 0;
      var exp = pb.status === "playing"
        ? Math.max(0, p + ((nowS - (pb.updatedAt || nowS)) / 1000) * (Number(pb.rate) || 1))
        : p;
      var pos = window.__fakePos();
      return { drift: Math.abs(pos - exp), pos: pos, exp: exp, st: PARTY.state.status };
    } catch(e) { return null; }
  })()
`;

(async () => {
  const srv = await serve();
  const webPort = srv.address().port;
  const party = createPartyServer({});
  await new Promise((r) => party.listen(0, r));
  const partyPort = party.server.address().port;
  console.log("web http://127.0.0.1:" + webPort + "  ws ws://127.0.0.1:" + partyPort + "/party");

  const edge = spawn(EDGE, [
    "--headless=new", "--remote-debugging-port=9357", `--user-data-dir=${path.join(os.tmpdir(), "sbparty-" + Date.now())}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--autoplay-policy=no-user-gesture-required",
    "--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding",
    "about:blank",
  ], { stdio: "ignore" });
  let cdp = null;
  for (let i = 0; i < 50 && !cdp; i++) {
    await sleep(400);
    try { cdp = await CDP.connect(9357); } catch (e) {}
  }
  if (!cdp) { console.log("FAIL could not connect to headless Edge"); edge.kill(); process.exit(1); }

  const clients = {};
  async function openClient(name, readyDelayMs) {
    const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
    const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
    const ev = async (expr) => {
      const r = await cdp.send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true }, sessionId);
      if (r.exceptionDetails) {
        const det = r.exceptionDetails.exception && (r.exceptionDetails.exception.description || r.exceptionDetails.exception.value);
        throw new Error(name + ": " + (r.exceptionDetails.text || "eval error") + " :: " + (det || "").slice(0, 400));
      }
      return r.result ? r.result.value : null;
    };
    await cdp.send("Page.addScriptToEvaluateOnNewDocument", {
      source: `window.SB_PARTY_WS = "ws://127.0.0.1:${partyPort}/party";`,
    }, sessionId);
    await cdp.send("Page.enable", {}, sessionId);
    await cdp.send("Page.navigate", { url: `http://127.0.0.1:${webPort}/index.html` }, sessionId);
    await sleep(2500); // let the app parse + boot
    await ev(fakePlayerInstall(readyDelayMs));
    clients[name] = { ev, sessionId, targetId, samples: [] };
    return clients[name];
  }

  try {
    /* ---------- setup: host + guest + (later) a slow third device ---------- */
    await openClient("A", 0);
    await openClient("B", 0);
    const A = clients.A, B = clients.B;

    await A.ev(`document.getElementById("partyName").value = "Host"; 
      document.getElementById("partyRoomName").value = "E2E";
      document.getElementById("partyRoomPw").value = "";
      document.getElementById("partyCtlAll").checked = true;
      document.getElementById("partyChatOn").checked = true;
      PARTY.create(); "created"`);

    let code = null;
    for (let i = 0; i < 20 && !code; i++) {
      await sleep(500);
      code = await A.ev(`PARTY.state.room ? PARTY.state.room.code : null`);
    }
    check(!!code, "host created a room (code " + code + ")");
    if (!code) throw new Error("no room");

    /* ---------- 1. two participants join ---------- */
    await B.ev(`document.getElementById("partyName").value = "Guest";
      document.getElementById("partyCode").value = ${JSON.stringify(code)};
      document.getElementById("partyJoinPw").value = "";
      PARTY.join(); "joined"`);
    let joined = false;
    for (let i = 0; i < 20 && !joined; i++) {
      await sleep(500);
      joined = await B.ev(`PARTY.state.status === "in-room" && !!PARTY.state.room`);
    }
    check(joined, "guest joined the same room over a real WebSocket");

    /* ---------- 2. host opens content: coordinated start ---------- */
    await A.ev(`window.openWatch("tv", 1396, 1, 1); PARTY.onLocalOpenWatch("tv", 1396, 1, 1); "opened"`);
    let sameEp = false;
    for (let i = 0; i < 24 && !sameEp; i++) {
      await sleep(500);
      sameEp = await B.ev(`watchState.id === 1396 && watchState.e === 1`);
    }
    check(sameEp, "guest landed on the same episode identity (coordinated transition)");

    /* ---------- 3. host plays -> guest follows ---------- */
    await A.ev(`window.sendCmd("play"); pState.paused = false; PARTY.onLocalPlayPause(); "play"`);
    await sleep(2500);
    let bPlaying = await B.ev(`window.__fake.playing`);
    check(bPlaying === true, "host play propagated to the guest");
    let d = await B.ev(SAMPLE_DRIFT);
    check(d && d.drift < 1.5, "guest tracks the room after play (drift " + (d ? d.drift.toFixed(2) : "?") + "s)");

    /* ---------- 4. host seeks -> guest lands ---------- */
    await A.ev(`PARTY.onLocalSeek(50); window.sendCmd("seek", [50]); "seek"`);
    await sleep(2000);
    const seekPos = await B.ev(`window.__fakePos()`);
    check(Math.abs(seekPos - (50 + 1.5)) < 3, "guest followed the seek (guest pos " + (seekPos || 0).toFixed(1) + "s vs ~51.5s)");
    d = await B.ev(SAMPLE_DRIFT);
    check(d && d.drift < 1.5, "guest drift after seek under 1.5s (got " + (d ? d.drift.toFixed(2) : "?") + "s)");

    /* ---------- 5. LATE JOIN during playback lands at the timeline ---------- */
    await openClient("C", 0);
    const C = clients.C;
    await C.ev(`document.getElementById("partyName").value = "Late";
      document.getElementById("partyCode").value = ${JSON.stringify(code)};
      document.getElementById("partyJoinPw").value = "";
      PARTY.join(); "joined"`);
    await sleep(4000);
    const cPos = await C.ev(`window.__fakePos()`);
    const aPos = await A.ev(`window.__fakePos()`);
    check(cPos != null && aPos != null && Math.abs(cPos - aPos) < 4,
      "late joiner landed on the LIVE timeline (C " + (cPos || 0).toFixed(1) + "s vs host " + (aPos || 0).toFixed(1) + "s)");

    /* ---------- 6. host pauses -> everyone pauses ---------- */
    await A.ev(`window.sendCmd("pause"); pState.paused = true; PARTY.onLocalPlayPause(); "pause"`);
    await sleep(2500);
    check((await B.ev(`window.__fake.playing`)) === false, "host pause propagated to the guest");
    const frozenB = await B.ev(`window.__fakePos()`);
    await sleep(2000);
    const frozenB2 = await B.ev(`window.__fakePos()`);
    check(frozenB === frozenB2, "paused room never advances on the guest");

    /* ---------- 7. EPISODE CHANGE while playing ---------- */
    await A.ev(`window.sendCmd("play"); pState.paused = false; PARTY.onLocalPlayPause(); "play"`);
    await sleep(1500);
    await A.ev(`window.openWatch("tv", 1396, 1, 2); PARTY.onLocalOpenWatch("tv", 1396, 1, 2); "switch"`);
    let switched = false;
    for (let i = 0; i < 30 && !switched; i++) {
      await sleep(500);
      switched = await B.ev(`watchState.e === 2`);
    }
    check(switched, "episode change reached the guest (now on E2)");
    // both start the new episode together at the gate
    await sleep(7000);
    const e2a = await A.ev(`({p: window.__fakePos(), playing: window.__fake.playing})`);
    const e2b = await B.ev(`({p: window.__fakePos(), playing: window.__fake.playing})`);
    check(e2a && e2b && Math.abs(e2a.p - e2b.p) < 2.5,
      "coordinated start: host and guest on the same new-episode timeline ("
      + (e2a ? e2a.p.toFixed(1) : "?") + "s vs " + (e2b ? e2b.p.toFixed(1) : "?") + "s)");

    /* ---------- 8. SLOW participant (20s load) catches up, never 00:00 ---------- */
    await openClient("S", 20000); // player ready only after 20s
    const S = clients.S;
    await S.ev(`document.getElementById("partyName").value = "Slow";
      document.getElementById("partyCode").value = ${JSON.stringify(code)};
      document.getElementById("partyJoinPw").value = "";
      PARTY.join(); "joined"`);
    await sleep(3000);
    const sEarly = await S.ev(`window.__fakePos()`);
    check(sEarly === 0, "slow player is still loading (holding at 0 while unready)");
    await sleep(22000); // past its readiness
    const sPos = await S.ev(`window.__fakePos()`);
    const aPos2 = await A.ev(`window.__fakePos()`);
    check(sPos != null && sPos > 5 && Math.abs(sPos - aPos2) < 6,
      "slow joiner caught up to the room after its 20s load (S " + (sPos || 0).toFixed(1)
      + "s vs host " + (aPos2 || 0).toFixed(1) + "s - never stranded at 00:00)");

    /* ---------- 9. RECONNECTION: kill the socket, auto-rejoin same seat ---------- */
    const selfBefore = await B.ev(`PARTY.state.selfId`);
    await B.ev(`PARTY.state.ws.close(); "killed"`);
    await sleep(6000);
    const back = await B.ev(`PARTY.state.status === "in-room" && PARTY.state.room != null`);
    const selfAfter = await B.ev(`PARTY.state.selfId`);
    check(back === true, "guest auto-reconnected and rejoined after a dropped socket");
    check(selfBefore === selfAfter, "same seat identity after reconnect (no ghost duplicate)");
    const bPos2 = await B.ev(`window.__fakePos()`);
    const aPos3 = await A.ev(`window.__fakePos()`);
    check(Math.abs(bPos2 - aPos3) < 6, "reconnected guest re-landed on the live timeline");

    /* ---------- 12. MEDIA VERSION: host intent reaches guests (dub/sub bug) ---------- */
    // simulate AniPM surfaces on both pages (the adapter's anipmSend is a
    // silent no-op without a frame; _anipmTracks is the real state object)
    await A.ev(`mirror = "anipm"; window._anipmTracks = { lang: "sub", qualities: [], quality: "Auto", subTracks: null }; PARTY.syncMediaUp(); "ok"`);
    await sleep(800);
    const hostMedia = await A.ev(`PARTY.state.room.media && PARTY.state.room.media.audio`);
    check(hostMedia === "sub", "host's intended version (Japanese/sub) is authoritative in room state");
    // the guest's own player would default to DUB (the reported bug)
    const guestBefore = await B.ev(`mirror = "anipm"; window._anipmTracks = { lang: "dub", qualities: [], quality: "Auto", subTracks: null }; window._anipmTracks.lang`);
    check(guestBefore === "dub", "guest's local default is dub (the mismatch waiting to happen)");
    await B.ev(`PARTY.applyMediaIntent(true); "applied"`);
    const guestAfter = await B.ev(`window._anipmTracks.lang`);
    check(guestAfter === "sub", "guest LOADED THE HOST'S VERSION (dub/sub mismatch fixed: " + guestBefore + " -> " + guestAfter + ")");
    // episode change preserves the intended version (3.5)
    await A.ev(`PARTY.onLocalOpenWatch("tv", 1396, 1, 3); "switch"`);
    await sleep(2500);
    const mediaAfterEp = await B.ev(`PARTY.state.room.media && PARTY.state.room.media.audio`);
    check(mediaAfterEp === "sub", "episode change keeps the intended audio (nobody defaults to dub)");
    // personal deviation stays personal (3.7) and never overwrites the room
    await B.ev(`PARTY.state._mediaApplying = 0; window._anipmTracks.lang = "dub"; PARTY.onLocalMediaChange(); "personal"`);
    await sleep(800);
    const personal = await B.ev(`PARTY.state.personalMedia === true`);
    const roomStill = await A.ev(`PARTY.state.room.media.audio`);
    check(personal === true && roomStill === "sub", "personal language choice stays local - the room version is untouched");
    const matched = await B.ev(`PARTY.matchHost(); window._anipmTracks.lang`);
    check(matched === "sub", "Match Host Version returns the participant to the intended version");
    await A.ev(`mirror = "cinesrc"; "back"`); await B.ev(`mirror = "cinesrc"; "back"`);

    /* ---------- 13. DRIFT DISTRIBUTION (4 clients, 45s of playback) ---------- */
    for (const n of ["A", "B", "C", "S"]) clients[n].samples = [];
    for (let i = 0; i < 90; i++) {
      for (const n of ["A", "B", "C", "S"]) {
        const s = await clients[n].ev(SAMPLE_DRIFT);
        if (s && s.st === "in-room" && window0(s)) clients[n].samples.push(s.drift);
      }
      await sleep(500);
    }
    function window0(s) { return s.pos > 0 || s.exp > 0; }
    const all = ["A", "B", "C", "S"].flatMap((n) => clients[n].samples).sort((x, y) => x - y);
    const pct = (p) => all[Math.min(all.length - 1, Math.floor((p / 100) * all.length))];
    const med = pct(50), p95 = pct(95), worst = all[all.length - 1];
    for (const n of ["A", "B", "C", "S"]) {
      const cs = clients[n].samples.slice().sort((x, y) => x - y);
      const cm = cs.length ? cs[Math.floor(cs.length / 2)] : NaN;
      console.log("  [drift " + n + "] n=" + cs.length + " median=" + cm.toFixed(3) + "s worst=" + (cs[cs.length - 1] || 0).toFixed(3) + "s");
    }
    for (const n of ["A", "B", "C", "S"]) {
      const dbg = await clients[n].ev(`(function(){
        var pb = PARTY.state.room && PARTY.state.room.playback;
        return { ep: watchState.e, pos: window.__fakePos(), playing: window.__fake.playing, rate: window.__fake.rate,
                 roomStatus: pb && pb.status, roomPos: pb && pb.position, roomUpd: pb && pb.updatedAt, seq: pb && pb.seq,
                 lastDrift: PARTY.state.lastDrift, lastSeq: PARTY.state.lastSeq };
      })()`);
      console.log("  [state " + n + "] " + JSON.stringify(dbg));
    }
    console.log("  [browser drift] n=" + all.length + " median=" + med.toFixed(3) + "s p95=" + p95.toFixed(3) + "s worst=" + worst.toFixed(3) + "s");
    check(med < 0.5, "browser median drift under 0.5s (got " + med.toFixed(3) + "s)");
    check(p95 < 1.5, "browser p95 drift under 1.5s (got " + p95.toFixed(3) + "s)");
    check(worst < 5, "browser worst-case drift bounded (got " + worst.toFixed(3) + "s)");

    /* ---------- 11. leaving cleans up ---------- */
    await C.ev(`PARTY.leave(); "left"`);
    await sleep(1500);
    const cGone = await C.ev(`PARTY.state.room === null && PARTY.state.ws === null`);
    check(cGone === true, "leaving tears down the room state and the socket");
  } catch (e) {
    bad("harness error: " + e.message);
  }

  console.log("\n==== Party E2E: " + pass + " passed, " + fail + " failed ====");
  try { edge.kill(); } catch (e) {}
  try { party.close(); } catch (e) {}
  srv.close();
  process.exit(fail ? 1 : 0);
})();
