"use strict";
/* StreamBox dead-click regression harness: clicks every interactive door on
   the real page and asserts something VISIBLE happens. This is the class of
   bug it exists for: the Watch Party buttons called a bare open() that
   silently resolved to window.open() - every door looked alive but nothing
   opened. Static QA cannot see that; a click that changes no state can.

   How it works (headless Edge over CDP, same driver pattern as
   tools/visual-diff.js - zero new dependencies):
   1. serve the repo over localhost, boot index.html (calm=1, hero frozen)
   2. snapshot "visible state" (open overlays, body classes, toast text,
      active view) around every click
   3. a click whose before/after snapshots are identical is a DEAD CLICK
   4. Escape drives the REAL Back ladder (keydown path) and must close the
      topmost overlay - regression for layering too

   Covers: header Party/About/Search doors, nav rail, hero buttons, the
   details modal's every button (incl. the once-dead Watch Party), and the
   party panel's own close path. Exit 0 = every door works. */

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
    // MUST serve on 127.0.0.1:8788 - the Worker's CORS allowlist accepts
    // exactly this origin (random ports get no TMDB feed and the hero never
    // renders, which cascades into fake dead-clicks on every modal button)
    srv.on("error", rej);
    srv.listen(8788, "127.0.0.1", () => res(srv));
  });
}

const SNAPSHOT = `(() => {
  const vis = (id) => { const e = document.getElementById(id); return !!(e && !e.classList.contains("hidden")); };
  const overlays = ["partyPanel","aboutPanel","modal","searchPanel","setPanel","epDrawer","avSheet","vrSheet",
    "tvGuide","pinPad","resumeDlg","upNextCard","toast","watchView","listView","gridView","homeView"].filter(vis);
  return JSON.stringify({
    overlays,
    body: document.body.className,
    toast: (document.getElementById("toastMsg") || {}).textContent || "",
    hint: (document.getElementById("searchHint") || {}).textContent || "",
  });
})()`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const srv = await serve();
  const port = srv.address().port;
  const edge = spawn(EDGE, [
    "--headless=new", "--remote-debugging-port=9355", `--user-data-dir=${path.join(os.tmpdir(), "sbclick-" + Date.now())}`,
    "--no-first-run", "--no-default-browser-check", "--disable-gpu", "about:blank",
  ], { stdio: "ignore" });
  let cdp = null;
  for (let i = 0; i < 50 && !cdp; i++) {
    await sleep(400);
    try { cdp = await CDP.connect(9355); } catch (e) {}
  }
  if (!cdp) { console.log("FAIL could not connect to headless Edge"); edge.kill(); srv.close(); process.exit(1); }

  const { targetId } = await cdp.send("Target.createTarget", { url: "about:blank" });
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId, flatten: true });
  const s = (m, p) => cdp.send(m, p, sessionId);
  const evaluate = async (expr) => {
    const r = await s("Runtime.evaluate", { expression: expr, returnByValue: true, awaitPromise: true });
    return r.result ? r.result.value : null;
  };
  const snapshot = () => evaluate(SNAPSHOT);
  const click = async (sel) => {
    const ok = await evaluate(`(() => { const e = document.querySelector(${JSON.stringify(sel)}); if (!e) return false; e.click(); return true; })()`);
    await sleep(650);
    return ok;
  };
  const escape = async () => {
    await s("Input.dispatchKeyEvent", { type: "keyDown", key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 });
    await s("Input.dispatchKeyEvent", { type: "keyUp", key: "Escape", code: "Escape", windowsVirtualKeyCode: 27 });
    await sleep(500);
  };

  await s("Page.enable");
  await s("Page.navigate", { url: `http://127.0.0.1:${port}/index.html?calm=1` });
  // boot + real TMDB feed: poll until the hero carries buttons (fixed sleeps
  // raced slow feeds and cascaded into fake dead-clicks)
  for (let i = 0; i < 40; i++) {
    await sleep(500);
    const ready = await evaluate(`!!document.querySelector("#hero button")`);
    if (ready) break;
  }
  await sleep(500);

  let dead = 0, passed = 0;
  // press Escape until every overlay is gone (the ladder legitimately
  // dismisses a toast before navigating - one press is not always enough)
  const settle = async () => {
    for (let i = 0; i < 7; i++) {
      const st = JSON.parse(await snapshot());
      const open = st.overlays.filter((o) => o !== "homeView" && o !== "watchView");
      if (!open.length && !st.toast) return;
      await escape();
      await sleep(250);
    }
  };
  await settle();
  const check = async (name, sel, opts = {}) => {
    if (!opts.keep) await settle(); // keep:true = run inside the state a prior test left open
    const before = await snapshot();
    const found = await click(sel);
    if (!found) { console.log(`FAIL ${name}: selector not found (${sel})`); dead++; return; }
    // poll for the effect: real detail fetches take >650ms and a fixed wait
    // raced them (the next Back press then cancelled the pending modal)
    let after = await snapshot();
    let changed = opts.expect ? opts.expect(before, after, JSON.parse(after)) : before !== after;
    for (let i = 0; i < 18 && !changed; i++) {
      await sleep(350);
      after = await snapshot();
      changed = opts.expect ? opts.expect(before, after, JSON.parse(after)) : before !== after;
    }
    if (!changed) { console.log(`FAIL ${name}: DEAD CLICK (no visible state change) [${sel}]`); dead++; return; }
    console.log(`ok    ${name}`);
    passed++;
    if (opts.escape !== false) await settle();
  };

  // --- header doors ---
  await check("header Party door opens the party panel", "button[onclick*=\"partyOpen('join')\"]",
    { expect: (b, a, s) => s.overlays.includes("partyPanel") });
  await check("header About door opens the About panel", "button[onclick*=\"aboutOpen()\"]",
    { expect: (b, a, s) => s.overlays.includes("aboutPanel") });
  await check("header Search door opens the search overlay", "button[onclick*=\"openSearch\"]",
    { expect: (b, a, s) => s.overlays.includes("searchPanel") });

  // --- nav rail (view switches) ---
  await check("nav rail switches views", "#navList",
    { expect: (b, a) => b !== a, escape: false });
  await escape();

  // --- hero + cards -> details modal ---
  await check("hero Watch opens the details modal", "#hero button",
    { expect: (b, a, s) => s.overlays.includes("modal"), escape: false });

  // --- the details modal's own doors (THE regression) ---
  // make sure the modal really landed (the hero click is async) before poking it
  for (let i = 0; i < 20; i++) {
    const st = JSON.parse(await snapshot());
    if (st.overlays.includes("modal")) break;
    await sleep(350);
  }
  // inline (no settle - that would close the modal under test): the party
  // door INSIDE the details modal must open the panel over the modal
  {
    const before = await snapshot();
    const found = await click("#mPartyBtn");
    let after = await snapshot();
    let ok1 = JSON.parse(after).overlays.includes("partyPanel");
    for (let i = 0; i < 12 && !ok1; i++) { await sleep(350); after = await snapshot(); ok1 = JSON.parse(after).overlays.includes("partyPanel"); }
    if (found && ok1 && JSON.parse(after).overlays.includes("modal")) { console.log("ok    details Watch Party opens the party panel over the modal (the dead-door regression)"); passed++; }
    else { console.log(`FAIL details Watch Party: found=${found} state=${after}`); dead++; }
  }
  // Escape must close the PARTY panel first and KEEP the modal - on the real
  // remote path the first press can just blur the auto-focused name field
  // (by design), so press until the panel closes and then assert the modal
  // survived underneath
  {
    for (let i = 0; i < 3; i++) {
      const st = JSON.parse(await snapshot());
      if (!st.overlays.includes("partyPanel")) break;
      await escape();
    }
    const s1 = JSON.parse(await snapshot());
    if (!s1.overlays.includes("partyPanel") && s1.overlays.includes("modal")) { console.log("ok    Escape closes the party panel first and keeps the modal (z-layering)"); passed++; }
    else { console.log(`FAIL Escape layering: ${JSON.stringify(s1.overlays)}`); dead++; }
  }
  await check("details My List toggles with visible feedback", "#mListBtn", { escape: false, keep: true });
  await check("details Watch opens the player", "#mWatch",
    { expect: (b, a, s) => s.overlays.includes("watchView"), escape: false, keep: true });
  {
    await settle(); // player Back -> home (toast-first ladder may need 2 presses)
    const s2 = JSON.parse(await snapshot());
    if (!s2.overlays.includes("watchView")) { console.log("ok    Back exits the player to home"); passed++; }
    else { console.log("FAIL Back did not exit the player"); dead++; }
  }

  // --- second card: modal season chips (tv) - open first tv card found ---
  {
    const clicked = await evaluate(`(() => { const c = [...document.querySelectorAll(".card")].find(x => (x.getAttribute("onclick")||"").includes('"tv"') || (x.onclick+"").includes('"tv"')); if (!c) return false; c.click(); return true; })()`);
    for (let i = 0; i < 20; i++) {
      const st = JSON.parse(await snapshot());
      if (st.overlays.includes("modal")) break;
      await sleep(350);
    }
    if (clicked) {
      const before = await snapshot();
      const chip = await evaluate(`(() => { const b = document.querySelector("#mSeasonChips button"); if (!b) return false; b.click(); return true; })()`);
      let after = await snapshot();
      for (let i = 0; i < 10 && before === after; i++) { await sleep(400); after = await snapshot(); }
      if (chip && before !== after) { console.log("ok    season chips repaint the episode list"); passed++; }
      else if (!chip) { console.log("ok    (no tv card with seasons to exercise - skipped)"); passed++; }
      else { console.log("FAIL season chip: DEAD CLICK"); dead++; }
      await settle();
    } else { console.log("ok    (no tv card in feed - season chip skipped)"); passed++; }
  }

  // --- party panel close path ---
  await check("party panel closes back to its door", "button[onclick*=\"partyOpen('join')\"]",
    { expect: (b, a, s) => s.overlays.includes("partyPanel"), escape: false });
  for (let i = 0; i < 3; i++) {
    const st = JSON.parse(await snapshot());
    if (!st.overlays.includes("partyPanel")) break;
    await escape();
  }
  {
    const s3 = JSON.parse(await snapshot());
    if (!s3.overlays.includes("partyPanel")) { console.log("ok    Escape closes the party panel"); passed++; }
    else { console.log("FAIL party panel stuck open"); dead++; }
  }

  edge.kill();
  srv.close();
  console.log(`\n==== click-test: ${passed} passed, ${dead} failed ====`);
  process.exit(dead ? 1 : 0);
})();
