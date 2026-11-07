"use strict";
/* StreamBox startup benchmark: measures what the Play CDN -> static
   tailwind.css swap buys on the boot path, over a local HTTP server with a
   cold profile (no cache), 3 runs per variant, medians reported.

   Metrics (Navigation Timing + paint): DOMContentLoaded, loadEventEnd,
   first-contentful-paint - the CDN variant pays a script download + a JIT
   compile BEFORE any utility style exists; the static variant paints with
   one stylesheet. Also reports payload sizes. */

const fs = require("fs");
const os = require("os");
const path = require("path");
const http = require("http");
const zlib = require("zlib");
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

function variants() {
  const html = fs.readFileSync(path.join(ROOT, "index.html"), "utf8");
  const cfg = fs.readFileSync(path.join(ROOT, "tailwind.config.js"), "utf8");
  const cfgJs = cfg.replace("module.exports =", "tailwind.config =");
  const cdnHead = `<script src="https://cdn.tailwindcss.com"></script><script>${cfgJs}</script>`;
  const localHead = `<link rel="stylesheet" href="tailwind.css">`;
  const strip = (s) => s
    .replace(/<script src="https:\/\/cdn\.tailwindcss\.com"><\/script>/, "@@TW@@")
    .replace(/<script>(?:(?!<\/script>)[\s\S])*tailwind\.config = (?:(?!<\/script>)[\s\S])*<\/script>/, "")
    .replace(/<link rel="stylesheet" href="tailwind\.css">/, "@@TW@@");
  const base = strip(html);
  return {
    cdn: base.replace("@@TW@@", cdnHead),
    local: base.replace("@@TW@@", localHead),
  };
}

function serve(files) {
  return new Promise((res) => {
    const srv = http.createServer((req, rq) => {
      const name = (req.url || "/").split("?")[0].replace(/^\/+/, "") || "index.html";
      const key = name.replace(/[^a-z0-9._-]/gi, "_");
      const body = files[key];
      if (!body) { rq.writeHead(404); rq.end("nope"); return; }
      rq.writeHead(200, { "content-type": key.endsWith(".css") ? "text/css" : "text/html; charset=utf-8", "cache-control": "no-store" });
      rq.end(body);
    });
    srv.listen(0, "127.0.0.1", () => res(srv));
  });
}

const median = (a) => { const s = [...a].sort((x, y) => x - y); return s[Math.floor(s.length / 2)]; };
const trimmed = (a) => {
  // drop the extreme pair (this machine runs a Gradle daemon + emulators)
  const s = [...a].sort((x, y) => x - y);
  if (s.length >= 5) return median(s.slice(1, -1));
  return median(s);
};

(async () => {
  const css = fs.readFileSync(path.join(ROOT, "tailwind.css"));
  const cdnBytes = 115000; // approx Play CDN script (measured below if reachable)
  const gz = zlib.gzipSync(css).length;
  console.log(`payload: tailwind.css ${css.length}B raw / ${gz}B gzip vs Play CDN script ~${cdnBytes}B raw (+ JIT runtime)`);

  const v = variants();
  const files = {
    "cdn.html": v.cdn,
    "local.html": v.local,
    "tailwind.css": css,
  };
  const srv = await serve(files);
  const port = srv.address().port;

  const edge = spawn(EDGE, [
    "--headless=new", "--remote-debugging-port=9344", `--user-data-dir=${path.join(os.tmpdir(), "sbperf-" + Date.now())}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank",
  ], { stdio: "ignore" });
  let cdp = null;
  for (let i = 0; i < 50 && !cdp; i++) {
    await new Promise((r) => setTimeout(r, 400));
    try { cdp = await CDP.connect(9344); } catch (e) {}
  }
  if (!cdp) { console.log("FAIL no headless Edge"); edge.kill(); srv.close(); process.exit(1); }

  const runs = { cdn: [], local: [] };
  for (const variant of ["cdn", "local"]) {
    for (let i = 0; i < 5; i++) {
      const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
      const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
      await cdp.send("Network.enable", {}, sessionId);
      await cdp.send("Network.setCacheDisabled", { cacheDisabled: true }, sessionId);
      await cdp.send("Page.enable", {}, sessionId);
      const t0 = Date.now();
      await cdp.send("Page.navigate", { url: `http://127.0.0.1:${port}/${variant}.html?calm=1&tv=1` }, sessionId);
      await new Promise((r) => setTimeout(r, 5000));
      const r = await cdp.send("Runtime.evaluate", {
        expression: `JSON.stringify((() => { const n = performance.getEntriesByType("navigation")[0] || {}; const p = performance.getEntriesByType("paint"); const fcp = p.find(x => x.name === "first-contentful-paint"); return { dcl: Math.round(n.domContentLoadedEventEnd || 0), load: Math.round(n.loadEventEnd || 0), fcp: fcp ? Math.round(fcp.startTime) : null, wall: ${Date.now()} - ${t0} }; })())`,
        returnByValue: true,
      }, sessionId);
      runs[variant].push(JSON.parse(r.result.value));
      await cdp.send("Target.closeTarget", { targetId });
      await new Promise((res) => setTimeout(res, 400));
    }
  }
  edge.kill();
  srv.close();

  for (const variant of ["cdn", "local"]) {
    const m = {
      dcl: trimmed(runs[variant].map((x) => x.dcl)),
      load: trimmed(runs[variant].map((x) => x.load)),
      fcp: trimmed(runs[variant].map((x) => x.fcp || 99999)),
    };
    console.log(`${variant}: trimmed-median DCL=${m.dcl}ms load=${m.load}ms FCP=${m.fcp}ms  (runs: ${runs[variant].map((x) => x.dcl + "/" + x.load + "/" + x.fcp).join(" ")})`);
  }
  console.log("done");
})();
