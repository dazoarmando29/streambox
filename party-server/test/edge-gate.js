"use strict";
/* Live gate probe: the deployed hub must schedule episode starts in the
   future and pin everyone at 00:00 until the shared gate opens. */
const WebSocket = require("ws");
const URL_WS = "wss://streambox-api.dazoarmando.workers.dev/party";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
function mk(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL_WS, { origin: "https://dazoarmando29.github.io" });
    const c = { ws, name, inbox: [] };
    ws.on("message", (d) => { try { c.inbox.push(JSON.parse(d.toString())); } catch (e) {} });
    ws.on("open", () => resolve(c));
    ws.on("error", reject);
    setTimeout(() => reject(new Error("open timeout")), 10000);
  });
}
function wait(c, pred, timeout = 8000) {
  return new Promise((resolve, reject) => {
    const t0 = Date.now();
    const iv = setInterval(() => {
      const i = c.inbox.findIndex(pred);
      if (i >= 0) { clearInterval(iv); resolve(c.inbox.splice(i, 1)[0]); }
      else if (Date.now() - t0 > timeout) { clearInterval(iv); reject(new Error("timeout")); }
    }, 50);
  });
}
(async () => {
  const a = await mk("HOST");
  const b = await mk("JOINER");
  a.ws.send(JSON.stringify({ t: "create", name: "Host" }));
  const r1 = await wait(a, (m) => m.t === "room");
  b.ws.send(JSON.stringify({ t: "join", name: "Guest", code: r1.room.code }));
  await wait(b, (m) => m.t === "room");
  a.ws.send(JSON.stringify({ t: "content", type: "tv", tmdbId: 1399, season: 4, episode: 1 }));
  const ev = await wait(b, (m) => m.t === "content");
  const gateIn = ev.state.updatedAt - Date.now();
  console.log("gate opens in:", gateIn + "ms | position pinned at:", ev.state.position);
  if (gateIn < 3000 || gateIn > 12000) throw new Error("gate not scheduled (" + gateIn + "ms)");
  if (ev.state.position !== 0) throw new Error("someone can run ahead before the gate");
  console.log("\nEDGE GATE PROBE: PASS (shared scheduled start verified on the deployed hub)");
  a.ws.close(); b.ws.close();
  process.exit(0);
})().catch((e) => { console.error("EDGE GATE PROBE: FAIL -", e.message); process.exit(1); });
