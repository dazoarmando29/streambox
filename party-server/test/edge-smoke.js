"use strict";
/* Live smoke against the DEPLOYED party hub (Cloudflare Durable Object):
   two clients over real TLS WebSockets - create, join by code, playback
   broadcast, chat. Mirrors the protocol test matrix on the real edge. */
const WebSocket = require("ws");
const URL_WS = "wss://streambox-api.dazoarmando.workers.dev/party";

function mk(name) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(URL_WS, { origin: "https://dazoarmando29.github.io" });
    const c = { ws, name, send: (o) => ws.send(JSON.stringify(o)), inbox: [] };
    ws.on("message", (d) => {
      const m = JSON.parse(d.toString());
      c.inbox.push(m);
      console.log(name, "<-", m.t, m.error || m.notice || (m.room ? "room:" + m.room.code : (m.msg ? JSON.stringify(m.msg.text) : "")));
    });
    ws.on("open", () => resolve(c));
    ws.on("error", reject);
    setTimeout(() => reject(new Error(name + " open timeout")), 10000);
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
  const host = await mk("HOST");
  const guest = await mk("GUEST");
  host.send({ t: "create", name: "Tester", roomName: "Edge smoke" });
  const r1 = await wait(host, (m) => m.t === "room" || m.t === "error");
  if (r1.t !== "room") throw new Error("create failed: " + JSON.stringify(r1));
  console.log("ROOM CODE:", r1.room.code);
  guest.send({ t: "join", name: "Friend", code: r1.room.code });
  const r2 = await wait(guest, (m) => m.t === "room" || m.t === "error");
  if (r2.t !== "room" || r2.room.participants.length !== 2) throw new Error("join failed");
  host.send({ t: "playback", action: "play", position: 42, sessionVersion: 1 });
  const ev = await wait(guest, (m) => m.t === "playback");
  if (ev.state.position !== 42 || ev.state.status !== "playing") throw new Error("playback broadcast wrong");
  guest.send({ t: "chat", text: "hello from the edge \u{1F389}" });
  const ch = await wait(host, (m) => m.t === "chat");
  console.log("CHAT:", ch.msg.name, "->", ch.msg.text);
  console.log("\nEDGE SMOKE: PASS (create + join + playback sync + chat over the deployed hub)");
  host.ws.close(); guest.ws.close();
  process.exit(0);
})().catch((e) => { console.error("EDGE SMOKE: FAIL -", e.message); process.exit(1); });
