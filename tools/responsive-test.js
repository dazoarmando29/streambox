"use strict";
/* StreamBox responsive-geometry regression harness: every supported screen
   size must render without layout breakage. Boots the real site in headless
   Edge at each viewport (phones, tablets, laptops, desktops, 4K) and checks:

   1. NO horizontal page overflow (the #1 "looks broken" signal)
   2. no visible element sticking out of the viewport (outside scroll rails)
   3. key overlays (details modal, party panel, player bar) FIT the viewport
   4. touch targets: primary buttons stay >= 40px tall on phone widths
      (reported as warnings - the house chip pills are deliberately compact)

   Screens exercised per viewport: home, details modal, watch party panel,
   search overlay, the player page. Exit 0 = every viewport clean.

   Same CDP driver as tools/visual-diff.js / click-test.js - no new deps. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { spawn } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";

let WSImpl = globalThis.WebSocket;
if (!WSImpl) {
  try { WSImpl = require(path.join(ROOT, "party-server", "node_modules", "ws")); }
  catch (e) { WSImpl = require("ws"); }
}

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
  return new Promise((res, rej) => {
    const srv = http.createServer((req, rq) => {
      const name = (req.url || "/").split("?")[0].replace(/^\/+/, "") || "index.html";
      const file = path.join(ROOT, name.replace(/[^a-z0-9._-]/gi, "_"));
      if (!file.startsWith(ROOT) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { rq.writeHead(404); rq.end(); return; }
      const ext = path.extname(file);
      rq.writeHead(200, { "content-type": ext === ".css" ? "text/css" : ext === ".js" ? "text/javascript" : "text/html; charset=utf-8", "cache-control": "no-store" });
      rq.end(fs.readFileSync(file));
    });
    srv.on("error", rej);
    // 8788 = the Worker's CORS allowlist origin (random ports get no feed)
    srv.listen(8788, "127.0.0.1", () => res(srv));
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const AUDIT = `(() => {
  const vw = window.innerWidth, vh = window.innerHeight;
  const out = { vw, vh, overflow: [], outside: [], fit: [], tiny: 0, tinySample: [] };
  if (document.documentElement.scrollWidth > vw + 1)
    out.overflow.push("documentElement scrollWidth " + document.documentElement.scrollWidth + " > viewport " + vw);
  if (document.body.scrollWidth > vw + 1)
    out.overflow.push("body scrollWidth " + document.body.scrollWidth + " > viewport " + vw);
  const inScroller = (el) => {
    let p = el.parentElement;
    while (p && p !== document.body) {
      const o = getComputedStyle(p).overflowX;
      if (o === "auto" || o === "scroll") return true;
      p = p.parentElement;
    }
    return false;
  };
  document.querySelectorAll("body *").forEach((el) => {
    const cs = getComputedStyle(el);
    if (cs.display === "none" || cs.visibility === "hidden" || parseFloat(cs.opacity) === 0) return;
    const r = el.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) return;
    const id = el.tagName + (el.id ? "#" + el.id : "") + "." + String(el.className || "").slice(0, 36);
    if ((r.right > vw + 2 || r.left < -2) && !inScroller(el)) out.outside.push(id + " [" + Math.round(r.left) + ".." + Math.round(r.right) + "]");
  });
  // key overlays must FIT the viewport (fixed/absolute centered cards)
  ["modal", "partyPanel", "aboutPanel", "searchPanel", "setPanel", "ctlBar"].forEach((id) => {
    const el = document.getElementById(id);
    if (!el || el.classList.contains("hidden")) return;
    const r = el.getBoundingClientRect();
    if (r.right > vw + 2 || r.left < -2 || r.bottom > vh + 2)
      out.fit.push(id + " [" + Math.round(r.left) + "," + Math.round(r.top) + ".." + Math.round(r.right) + "," + Math.round(r.bottom) + "]");
  });
  // touch targets on phone widths (info only - chip pills are compact by design)
  if (vw <= 480) {
    document.querySelectorAll("button:not([disabled])").forEach((b) => {
      const r = b.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) return;
      if (r.height < 40 || r.width < 40) {
        out.tiny++;
        if (out.tinySample.length < 6) out.tinySample.push(b.textContent.trim().slice(0, 18) + " " + Math.round(r.width) + "x" + Math.round(r.height));
      }
    });
  }
  return JSON.stringify(out);
})()`;

(async () => {
  const srv = await serve();
  const edge = spawn(EDGE, [
    "--headless=new", "--remote-debugging-port=9366", `--user-data-dir=${path.join(os.tmpdir(), "sbresp-" + Date.now())}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank",
  ], { stdio: "ignore" });
  let cdp = null;
  for (let i = 0; i < 50 && !cdp; i++) {
    await sleep(400);
    try { cdp = await CDP.connect(9366); } catch (e) {}
  }
  if (!cdp) { console.log("FAIL could not connect to headless Edge"); edge.kill(); srv.close(); process.exit(1); }

  const VIEWPORTS = [
    ["small phone", 320, 568], ["android phone", 360, 740], ["iphone", 390, 844], ["iphone max", 430, 932],
    ["ipad portrait", 768, 1024], ["ipad air", 834, 1112], ["ipad landscape", 1024, 768],
    ["laptop sm", 1280, 800], ["laptop", 1366, 768], ["desktop", 1440, 900],
    ["full hd", 1920, 1080], ["qhd", 2560, 1440], ["4k tv", 3840, 2160],
  ];

  let fails = 0, warns = 0;
  for (const [label, w, h] of VIEWPORTS) {
    const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
    const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
    const s = (m, p) => cdp.send(m, p, sessionId);
    const evaluate = async (expr) => {
      const r = await s("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
      return r.result ? r.result.value : null;
    };
    await s("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 1, mobile: w <= 480 });
    await s("Page.enable");
    await s("Page.navigate", { url: `http://127.0.0.1:8788/index.html?calm=1` });
    // wait for feed
    for (let i = 0; i < 30; i++) {
      await sleep(400);
      if (await evaluate(`!!document.querySelector("#hero button, .card")`)) break;
    }
    await sleep(400);

    const screens = [
      ["home", null],
      ["details modal", `(() => { const c = document.querySelector(".card, #hero button"); if (c) c.click(); return !!c; })()`],
      ["party panel", `(() => { try { partyClose(); } catch(e){} try { closeModal(); } catch(e){} partyOpen('join'); return true; })()`],
      ["search overlay", `(() => { try { partyClose(); } catch(e){} openSearch(); return true; })()`],
      ["player page", `(() => { try { closeSearch(); } catch(e){} openWatch("movie", 550); return true; })()`],
    ];
    for (const [screen, script] of screens) {
      if (script) { await evaluate(script); await sleep(1600); }
      const raw = await evaluate(AUDIT);
      const a = JSON.parse(raw || "{}");
      const probs = [...(a.overflow || []), ...(a.outside || []).slice(0, 8), ...(a.fit || [])];
      if (probs.length) {
        fails++;
        console.log(`FAIL ${label} ${w}x${h} / ${screen}:`);
        for (const p of probs.slice(0, 10)) console.log(`      ${p}`);
      } else {
        console.log(`ok    ${label} ${w}x${h} / ${screen}`);
      }
      if (a.tiny) { warns++; console.log(`      info: ${a.tiny} compact targets (e.g. ${a.tinySample.join(", ")})`); }
    }
    await cdp.send("Target.closeTarget", { targetId });
    await sleep(200);
  }

  edge.kill();
  srv.close();
  console.log(`\n==== responsive-test: ${fails} failing screens, ${warns} compact-target infos ====`);
  process.exit(fails ? 1 : 0);
})();
