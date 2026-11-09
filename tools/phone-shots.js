"use strict";
/* StreamBox phone visual check: screenshots the real site at phone widths so
   the design can be SEEN, not just measured. Usage:
     node tools/phone-shots.js [outdir]
   Captures: home + player (bar up) + player fullscreen at 390x844 and 320x568. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const { spawn } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const OUT = process.argv[2] || path.join(os.tmpdir(), "sb-shots");

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
      rq.writeHead(200, { "content-type": ext === ".css" ? "text/css" : ext === ".js" ? "text/javascript" : ext === ".png" ? "image/png" : "text/html; charset=utf-8", "cache-control": "no-store" });
      rq.end(fs.readFileSync(file));
    });
    srv.on("error", rej);
    srv.listen(8788, "127.0.0.1", () => res(srv));
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const srv = await serve();
  const edge = spawn(EDGE, [
    "--headless=new", "--remote-debugging-port=9388", `--user-data-dir=${path.join(os.tmpdir(), "sbshots-" + Date.now())}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank",
  ], { stdio: "ignore" });
  let cdp = null;
  for (let i = 0; i < 50 && !cdp; i++) { await sleep(400); try { cdp = await CDP.connect(9388); } catch (e) {} }
  if (!cdp) { console.log("FAIL no headless Edge"); edge.kill(); srv.close(); process.exit(1); }

  const shot = async (page, name) => {
    const r = await page.s("Page.captureScreenshot", { format: "png" });
    fs.writeFileSync(path.join(OUT, name + ".png"), Buffer.from(r.data, "base64"));
    console.log("shot ->", name + ".png");
  };

  for (const [label, w, h] of [["ip14", 390, 844], ["small", 320, 568]]) {
    const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
    const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
    const page = { s: (m, p) => cdp.send(m, p, sessionId) };
    const ev = async (x) => { const r = await page.s("Runtime.evaluate", { expression: x, returnByValue: true, awaitPromise: true }); return r.result ? r.result.value : null; };
    await page.s("Emulation.setDeviceMetricsOverride", { width: w, height: h, deviceScaleFactor: 2, mobile: true });
    await page.s("Page.enable");
    await page.s("Page.navigate", { url: `http://127.0.0.1:8788/index.html?calm=1` });
    for (let i = 0; i < 30; i++) { await sleep(400); if (await ev(`!!document.querySelector("#hero button, .card")`)) break; }
    await sleep(800);
    await shot(page, label + "-home");
    // open the player and keep the bar up for the shot
    await ev(`openWatch("movie", 550)`);
    await sleep(3500);
    await ev(`try { pokeHud(); } catch(e){}`);
    await sleep(400);
    await shot(page, label + "-player");
    // fullscreen (cinema / native)
    await ev(`try { ctlFullscreen(); } catch(e){}`);
    await sleep(900);
    await ev(`try { pokeHud(); } catch(e){}`);
    await sleep(300);
    await shot(page, label + "-fullscreen");
    await cdp.send("Target.closeTarget", { targetId });
    await sleep(200);
  }

  edge.kill();
  srv.close();
  console.log("done ->", OUT);
})();
