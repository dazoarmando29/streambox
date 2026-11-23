"use strict";
/* StreamBox mobile/tablet visual audit: screenshots the REAL site at phone and
   tablet viewports across every key screen so design work is grounded in what
   actually renders (not what the CSS "should" do).

   Usage: node tools/mobile-shots.js [outdir]   (default: os.tmpdir()/sb-mobile-shots)

   Screens: home, details modal, search, player (portrait + landscape),
   player settings sheet, party join, active party room, my list.
   Viewports: 320x568, 390x844, 430x932 (phones) | 768x1024, 834x1112,
   1024x768 (tablets). Same CDP driver as tools/phone-shots.js - no new deps. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { spawn } = require("child_process");

const ROOT = process.env.SB_ROOT || path.resolve(__dirname, "..");
const REPO = path.resolve(__dirname, ".."); // ws lives in the repo's party-server
const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const OUT = process.argv[2] || path.join(os.tmpdir(), "sb-mobile-shots");

let WSImpl = globalThis.WebSocket;
if (!WSImpl) {
  try { WSImpl = require(path.join(REPO, "party-server", "node_modules", "ws")); }
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

function serve() {
  const MIME = {
    ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript",
    ".png": "image/png", ".webmanifest": "application/manifest+json", ".svg": "image/svg+xml",
  };
  const srv = http.createServer((req, res) => {
    const u = new URL(req.url, "http://x");
    let file = u.pathname === "/" ? "/index.html" : u.pathname;
    const fp = path.join(ROOT, file.replace(/\//g, path.sep));
    if (!fp.startsWith(ROOT) || !fs.existsSync(fp) || fs.statSync(fp).isDirectory()) {
      res.writeHead(404); res.end("nope"); return;
    }
    // proper MIME or Chrome refuses stylesheets ('octet-stream' = unstyled page)
    const type = MIME[path.extname(fp).toLowerCase()] || "application/octet-stream";
    res.writeHead(200, { "content-type": type, "cache-control": "no-store" });
    res.end(fs.readFileSync(fp));
  });
  // MUST serve on 127.0.0.1:8788 - the Worker's CORS allowlist accepts that
  // origin only (random ports get no catalog feed, like the other tools)
  return new Promise((r) => srv.listen(8788, "127.0.0.1", () => r(srv)));
}

const VIEWPORTS = [
  ["phone-320", 320, 568],
  ["phone-390", 390, 844],
  ["phone-430", 430, 932],
  ["tablet-768", 768, 1024],
  ["tablet-834", 834, 1112],
  ["tablet-1024-land", 1024, 768],
];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const srv = await serve();
  const port = srv.address().port;
  const edge = spawn(EDGE, [
    "--headless=new", "--remote-debugging-port=9358", `--user-data-dir=${path.join(os.tmpdir(), "sbms-" + Date.now())}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "--autoplay-policy=no-user-gesture-required",
    "--disable-background-timer-throttling", "--hide-scrollbars",
  ], { stdio: "ignore" });
  let cdp = null;
  for (let i = 0; i < 50 && !cdp; i++) {
    await sleep(400);
    try { cdp = await CDP.connect(9358); } catch (e) {}
  }
  if (!cdp) { console.log("FAIL: no headless Edge"); edge.kill(); process.exit(1); }

  const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
  const ev = async (expr) => {
    const r = await cdp.send("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true }, sessionId);
    if (r.exceptionDetails) console.log("  eval error: " + ((r.exceptionDetails.exception && r.exceptionDetails.exception.description) || r.exceptionDetails.text || "").slice(0, 160));
    return r.result ? r.result.value : null;
  };
  /* interactions must WAIT for the app (rows load over the network) */
  const waitEval = async (expr, tries = 20) => {
    for (let i = 0; i < tries; i++) {
      const okv = await ev(expr);
      if (okv) return true;
      await sleep(500);
    }
    return false;
  };
  const shot = async (name) => {
    const r = await cdp.send("Page.captureScreenshot", { format: "jpeg", quality: 82 }, sessionId);
    const fp = path.join(OUT, name + ".jpg");
    fs.writeFileSync(fp, Buffer.from(r.data, "base64"));
    console.log("shot " + name);
  };
  const nav = async (url) => {
    await cdp.send("Page.navigate", { url }, sessionId);
    await waitEval(`document.readyState === 'complete' && !!document.getElementById('rows')`, 30);
    await sleep(1500); // first paint + row skeletons resolve
  };

  for (const [label, w, h] of VIEWPORTS) {
    if (process.env.SB_VPS && !process.env.SB_VPS.split(",").includes(label)) continue;
    await cdp.send("Emulation.setDeviceMetricsOverride", {
      width: w, height: h, deviceScaleFactor: 2, mobile: w <= 430,
      screenWidth: w, screenHeight: h,
    }, sessionId);
    await cdp.send("Emulation.setTouchEmulationEnabled", { enabled: w <= 1024, maxTouchPoints: 5 }, sessionId);

    /* 1. home */
    await nav(`http://127.0.0.1:${port}/index.html`);
    await shot(label + "-1-home");

    /* 2. details modal (first catalog card) */
    const clicked = await waitEval(`(function(){ var c = document.querySelector('#rows .card'); if (!c) return false; c.click(); return true; })()`);
    if (clicked) await waitEval(`(function(){ var m = document.getElementById('modal'); return m && !m.classList.contains('hidden'); })()`, 16);
    await sleep(1200);
    await shot(label + "-2-details");
    await ev(`closeModal ? closeModal() : 0`);
    await sleep(600);

    /* 3. search overlay */
    await ev(`openSearch(new Event('click'))`);
    await waitEval(`(function(){ var m = document.getElementById('searchPanel'); return m && !m.classList.contains('hidden'); })()`, 10);
    await sleep(800);
    await shot(label + "-3-search");
    await ev(`closeSearch ? closeSearch() : 0`);
    await sleep(500);

    /* 4. player portrait */
    await nav(`http://127.0.0.1:${port}/index.html?type=tv&id=1396&s=1&e=1`);
    await waitEval(`(function(){ var w = document.getElementById('watchView'); return w && !w.classList.contains('hidden'); })()`, 30);
    await sleep(2500);
    await ev(`pokeHud ? pokeHud() : 0`);
    await sleep(600);
    await shot(label + "-4-player");

    /* 5. player settings sheet */
    await ev(`openSettings()`);
    await waitEval(`(function(){ var m = document.getElementById('setPanel'); return m && !m.classList.contains('hidden'); })()`, 10);
    await sleep(800);
    await shot(label + "-5-settings");
    await ev(`closePanels ? closePanels() : 0`);
    await sleep(500);

    /* 6. party join panel */
    await ev(`partyOpen('join')`);
    await waitEval(`(function(){ var m = document.getElementById('partyPanel'); return m && !m.classList.contains('hidden'); })()`, 10);
    await sleep(800);
    await shot(label + "-6-party-join");
    await ev(`partyClose()`);
    await sleep(400);

    /* 7. my list (empty state) */
    await nav(`http://127.0.0.1:${port}/index.html`);
    await ev(`showList(new Event('click'))`);
    await sleep(1500);
    await shot(label + "-7-mylist");
  }

  /* player landscape (phones) */
  for (const [w, h] of [[844, 390], [932, 430]]) {
    await cdp.send("Emulation.setDeviceMetricsOverride", {
      width: w, height: h, deviceScaleFactor: 2, mobile: true, screenWidth: w, screenHeight: h,
    }, sessionId);
    await nav(`http://127.0.0.1:${port}/index.html?type=tv&id=1396&s=1&e=1`);
    await sleep(3500);
    await ev(`pokeHud ? pokeHud() : 0`);
    await sleep(500);
    await shot("phone-land-" + w + "-player");
    await ev(`openSettings()`);
    await sleep(900);
    await shot("phone-land-" + w + "-settings");
  }

  console.log("\nScreenshots in: " + OUT);
  try { edge.kill(); } catch (e) {}
  srv.close();
  process.exit(0);
})();
