"use strict";
/* StreamBox visual regression harness: proves the production Tailwind build
   (tailwind.css) renders EXACTLY what the runtime Play CDN renders, so the
   CDN -> static-CSS swap can never drift the design.

   How it works (deterministic, no image codecs, no third-party deps):
   1. Extract every class-shaped token the app uses (markup + JS strings).
   2. Build two probe pages: one wearing the Play CDN + the house config
      (serialized from tailwind.config.js), one wearing the generated
      tailwind.css - identical token DOM on both.
   3. Drive headless Edge over CDP (Node's built-in WebSocket) and diff
      getComputedStyle for every token element across both pages.
   4. Whole-page pass: load the real app twice (CDN vs local, network
      stubbed so both fail identically, calm=1 to freeze the hero) and diff
      every element's computed style.

   Exit code 0 = no drift, 1 = drift found (details on stdout). */

const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const ROOT = path.resolve(__dirname, "..");
const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const PROPS = [
  "display", "position", "width", "height", "margin-top", "margin-right", "margin-bottom", "margin-left",
  "padding-top", "padding-right", "padding-bottom", "padding-left", "color", "background-color",
  "border-radius", "border-width", "border-color", "font-size", "font-weight", "font-family",
  "line-height", "letter-spacing", "text-align", "text-transform", "opacity", "visibility",
  "flex-direction", "justify-content", "align-items", "gap", "overflow", "z-index",
];

function read(f) { return fs.readFileSync(path.join(ROOT, f), "utf8"); }

/* ---- 1. class tokens from every quoted string (markup + JS literals) ---- */
function extractTokens(src) {
  const strings = src.match(/"[^"\n]{1,200}"|'[^'\n]{1,200}'|`[^`\n]{1,200}`/g) || [];
  const set = new Set();
  for (const s of strings) {
    for (const t of s.slice(1, -1).split(/[\s(){}[\]<>=;,*]+/)) {
      if (!t || t.length > 50) continue;
      if (/[${}\\]/.test(t)) continue;                       // template junk
      if (/^(https?|sb|tv|movie|android|content|text|application)/i.test(t)) continue;
      if (!/^[!a-zA-Z]/.test(t)) continue;                   // classes start with a letter (or !)
      if (/^[a-zA-Z]+$/.test(t) && !/^[a-z]+$/.test(t)) continue; // CamelCase = code identifiers
      set.add(t);
    }
  }
  return [...set].sort();
}

function probeHtml(tokens, headExtra, id) {
  const rows = tokens.map((t, i) => `<div id="t${i}" class="${t.replace(/"/g, "&quot;")}">x</div>`).join("\n");
  // freeze animations on BOTH probes: animate-pulse would otherwise be
  // sampled at a different frame on each page (fake drift)
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><title>${id}</title><style>*{animation:none!important;transition:none!important}</style>${headExtra}</head><body>${rows}</body></html>`;
}

/* ---- CDP driver (global WebSocket when present, else the `ws` package
   already vendored by party-server - zero new dependencies) ---- */
let WSImpl = globalThis.WebSocket;
if (!WSImpl) {
  try { WSImpl = require(path.join(ROOT, "party-server", "node_modules", "ws")); }
  catch (e) { WSImpl = require("ws"); }
}
function wireWs(ws, onMsg, onOpen, onErr) {
  if (typeof ws.addEventListener === "function") {
    ws.addEventListener("open", onOpen);
    ws.addEventListener("error", onErr);
    ws.addEventListener("message", (e) => onMsg(e.data));
  } else {
    ws.on("open", onOpen);
    ws.on("error", onErr);
    ws.on("message", (d) => onMsg(d.toString()));
  }
}
class CDP {
  constructor(ws) { this.ws = ws; this.id = 0; this.wait = new Map(); }
  static async connect(port) {
    const ver = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
    const ws = new WSImpl(ver.webSocketDebuggerUrl);
    await new Promise((res, rej) => wireWs(ws, () => {}, res, rej));
    const c = new CDP(ws);
    wireWs(ws, (data) => {
      const m = JSON.parse(data);
      if (m.id && c.wait.has(m.id)) {
        const { res, rej } = c.wait.get(m.id);
        c.wait.delete(m.id);
        if (m.error) rej(new Error(m.error.message)); else res(m.result);
      }
    }, () => {}, () => {});
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

async function newPage(cdp) {
  const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
  return { s: (m, p) => cdp.send(m, p, sessionId) };
}

const EVAL_PROPS = PROPS.map((p) => `o[${JSON.stringify(p)}] = cs.getPropertyValue(${JSON.stringify(p)});`).join("\n");

async function collectTokenStyles(page, count) {
  const expr = `(() => {
    const out = [];
    for (let i = 0; i < ${count}; i++) {
      const el = document.getElementById("t" + i);
      if (!el) { out.push(null); continue; }
      const cs = getComputedStyle(el);
      const o = {};
      ${EVAL_PROPS}
      out.push(o);
    }
    return JSON.stringify(out);
  })()`;
  const r = await page.s("Runtime.evaluate", { expression: expr, returnByValue: true });
  return JSON.parse(r.result.value);
}

async function pageElementStyles(page) {
  const expr = `(() => {
    const out = [];
    const SKIP = new Set(["STYLE", "SCRIPT", "LINK", "META", "TITLE", "NOSCRIPT", "HEAD"]);
    const walk = (el, pathStr) => {
      if (out.length > 2500) return;
      if (!SKIP.has(el.tagName)) {
        const cs = getComputedStyle(el);
        const cn = (el.className && el.className.baseVal !== undefined) ? el.className.baseVal : String(el.className || "");
        const o = { path: pathStr, tag: el.tagName, cls: cn.slice(0, 60) };
        ${EVAL_PROPS}
        out.push(o);
      }
      let i = 0;
      for (const k of el.children) walk(k, pathStr + ">" + (SKIP.has(el.tagName) ? "" : (i++)));
    };
    // BODY subtree only: the Play CDN injects its own <style> into <head> so
    // head-level index alignment is meaningless - rendered content lives in body
    walk(document.body, "body");
    return JSON.stringify(out);
  })()`;
  const r = await page.s("Runtime.evaluate", { expression: expr, returnByValue: true });
  return JSON.parse(r.result.value);
}

function propEq(p, x, y) {
  if (x === y) return true;
  // color channels: the CDN and the CLI can mix opacity colors with 1/255
  // rounding differences - visually identical, not drift
  if (/color/.test(p)) {
    const nx = String(x).match(/\d+(\.\d+)?/g) || [];
    const ny = String(y).match(/\d+(\.\d+)?/g) || [];
    if (nx.length && nx.length === ny.length) {
      return nx.every((v, i) => Math.abs(parseFloat(v) - parseFloat(ny[i])) <= 1);
    }
  }
  return false;
}

function diff(label, a, b, nameOf, limit) {
  let bad = 0;
  const n = Math.min(a.length, b.length);
  for (let i = 0; i < n; i++) {
    const x = a[i], y = b[i];
    if (!x || !y) continue;
    for (const p of PROPS) {
      if (!propEq(p, x[p], y[p])) {
        bad++;
        if (bad <= (limit || 25)) console.log(`DRIFT ${label} [${nameOf(i, x)}] ${p}: cdn=${JSON.stringify(x[p])} local=${JSON.stringify(y[p])}`);
      }
    }
  }
  return bad;
}

(async () => {
  const html = read("index.html");
  const css = read("tailwind.css");
  const cfg = read("tailwind.config.js");
  if (!css || css.length < 5000) { console.log("FAIL tailwind.css missing or tiny - run the tailwind build first"); process.exit(1); }

  const tokens = extractTokens(html);
  console.log(`tokens under test: ${tokens.length}`);

  const cfgJs = cfg.replace("module.exports =", "tailwind.config =");
  const cdnHead = `<script src="https://cdn.tailwindcss.com"></script><script>${cfgJs}</script>`;
  const localHead = `<link rel="stylesheet" href="tailwind.css">`;
  // normalize whatever tailwind source the html currently wears to a marker
  const strip = (s) => s
    .replace(/<script src="https:\/\/cdn\.tailwindcss\.com"><\/script>/, "@@TW@@")
    .replace(/<script>(?:(?!<\/script>)[\s\S])*tailwind\.config = (?:(?!<\/script>)[\s\S])*<\/script>/, "")
    .replace(/<link rel="stylesheet" href="tailwind\.css">/, "@@TW@@")
    .replace("@@TW@@", "<!--TW-->");

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "sbvd-"));
  fs.writeFileSync(path.join(tmp, "tailwind.css"), css);
  fs.writeFileSync(path.join(tmp, "probe-cdn.html"), probeHtml(tokens, cdnHead, "cdn"));
  fs.writeFileSync(path.join(tmp, "probe-local.html"), probeHtml(tokens, localHead, "local"));

  // whole-page variants live in the REPO root so relative assets resolve
  const stub = `<script>try{window.fetch=function(){return Promise.reject(new Error("vd-stub"))};}catch(e){}</script>`;
  const base = strip(html).replace("<head>", "<head>" + stub);
  const pageCdn = path.join(ROOT, "_vd_page_cdn.html");
  const pageLocal = path.join(ROOT, "_vd_page_local.html");
  fs.writeFileSync(pageCdn, base.replace("<!--TW-->", cdnHead));
  fs.writeFileSync(pageLocal, base.replace("<!--TW-->", localHead));

  const port = 9333;
  const edge = spawn(EDGE, [
    "--headless=new", `--remote-debugging-port=${port}`, `--user-data-dir=${path.join(tmp, "profile")}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank",
  ], { stdio: "ignore" });
  let cdp = null;
  for (let i = 0; i < 50 && !cdp; i++) {
    await new Promise((r) => setTimeout(r, 400));
    try { cdp = await CDP.connect(port); } catch (e) {}
  }
  if (!cdp) { console.log("FAIL could not connect to headless Edge"); edge.kill(); process.exit(1); }

  const furl = (p, q) => "file:///" + p.replace(/\\/g, "/") + (q || "");

  // ---- phase 1: token probe ----
  const p1 = await newPage(cdp);
  await p1.s("Page.enable");
  await p1.s("Page.navigate", { url: furl(tmp + "\\probe-cdn.html") });
  await new Promise((r) => setTimeout(r, 4000)); // let the CDN JIT compile
  const cdnStyles = await collectTokenStyles(p1, tokens.length);
  const p2 = await newPage(cdp);
  await p2.s("Page.enable");
  await p2.s("Page.navigate", { url: furl(tmp + "\\probe-local.html") });
  await new Promise((r) => setTimeout(r, 1500));
  const localStyles = await collectTokenStyles(p2, tokens.length);
  const tokenDrift = diff("token", cdnStyles, localStyles, (i) => tokens[i], 30);

  // ---- phase 2: whole page (network stubbed both sides, hero frozen) ----
  const p3 = await newPage(cdp);
  await p3.s("Page.enable");
  await p3.s("Page.navigate", { url: furl(pageCdn, "?calm=1&tv=1") });
  await new Promise((r) => setTimeout(r, 6000));
  const cdnPage = await pageElementStyles(p3);
  const p4 = await newPage(cdp);
  await p4.s("Page.enable");
  await p4.s("Page.navigate", { url: furl(pageLocal, "?calm=1&tv=1") });
  await new Promise((r) => setTimeout(r, 6000));
  const localPage = await pageElementStyles(p4);
  const pageDrift = diff("page", cdnPage, localPage, (i, x) => x.path + "/" + x.tag + "." + x.cls, 30);

  edge.kill();
  try { fs.unlinkSync(pageCdn); fs.unlinkSync(pageLocal); } catch (e) {}

  console.log(`token drift: ${tokenDrift}, page drift: ${pageDrift}`);
  if (tokenDrift || pageDrift) { console.log("RESULT: DRIFT"); process.exit(1); }
  console.log("RESULT: CLEAN");
})();
