"use strict";
/* Watch Party protocol + room tests. Two+ simulated clients talk to the real
   server over real WebSockets on an ephemeral port. No third-party players
   involved: the protocol is tested separately from embed availability. */

const test = require("node:test");
const assert = require("node:assert");
const WebSocket = require("ws");
const { createPartyServer } = require("../server");
const { expectedPosition, validateClientMsg, LIMITS } = require("../protocol");

function startServer(t, opts = {}) {
  return new Promise((resolve) => {
    const app = createPartyServer(opts);
    app.listen(0, () => resolve({ app, url: `ws://127.0.0.1:${app.server.address().port}/party` }));
    t.after(() => new Promise((r) => app.close(r)));
  });
}

function connect(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const inbox = [];
    const waiters = [];
    ws.on("message", (d) => {
      let msg;
      try { msg = JSON.parse(d.toString()); } catch (e) { return; }
      for (let i = 0; i < waiters.length; i++) {
        if (waiters[i].pred(msg)) { waiters.splice(i, 1)[0].resolve(msg); return; }
      }
      inbox.push(msg);
    });
    ws.on("open", () => resolve(client));
    ws.on("error", reject);
    const client = {
      ws,
      send(obj) { ws.send(JSON.stringify(obj)); },
      next(pred, timeout = 3000) {
        for (let i = 0; i < inbox.length; i++) {
          if (pred(inbox[i])) return Promise.resolve(inbox.splice(i, 1)[0]);
        }
        return new Promise((res, rej) => {
          const w = { pred, resolve: res };
          waiters.push(w);
          setTimeout(() => {
            const i = waiters.indexOf(w);
            if (i >= 0) { waiters.splice(i, 1); rej(new Error("timeout waiting for message")); }
          }, timeout);
        });
      },
      waitType(type, timeout) { return this.next((m) => m.t === type, timeout); },
      drain() { inbox.length = 0; },
      close() { ws.close(); },
    };
  });
}

/* ---------- shared sync math ---------- */

test("expectedPosition: playing advances at rate, paused freezes", () => {
  const t0 = 1000000;
  const pb = { status: "playing", position: 10, rate: 1, updatedAt: t0 };
  assert.strictEqual(expectedPosition(pb, t0), 10);
  assert.strictEqual(expectedPosition(pb, t0 + 5000), 15);
  pb.rate = 2;
  assert.strictEqual(expectedPosition(pb, t0 + 5000), 20);
  pb.status = "paused";
  assert.strictEqual(expectedPosition(pb, t0 + 60000), 10); // frozen
  pb.status = "playing";
  assert.ok(expectedPosition(pb, t0 + 10000) >= 30 - 0.001); // resumes from freeze point
});

test("validateClientMsg: rejects garbage, accepts and normalizes valid", () => {
  assert.strictEqual(validateClientMsg("not json", false).ok, false);
  assert.strictEqual(validateClientMsg(JSON.stringify({ t: "nope" }), false).ok, false);
  assert.strictEqual(validateClientMsg(JSON.stringify({ t: "chat", text: "" }), true).ok, false);
  assert.strictEqual(validateClientMsg(JSON.stringify({ t: "playback", action: "seek", position: -5 }), true).ok, false);
  assert.strictEqual(validateClientMsg(JSON.stringify({ t: "playback", action: "seek" }), false).ok, false, "out-of-room");
  const chat = validateClientMsg(JSON.stringify({ t: "chat", text: " hi\u0000\u0001  there " }), true);
  assert.ok(chat.ok);
  assert.strictEqual(chat.msg.text, "hi there");
  const long = validateClientMsg(JSON.stringify({ t: "chat", text: "x".repeat(9999) }), true);
  assert.strictEqual(long.msg.text.length, LIMITS.CHAT_MAX);
  const content = validateClientMsg(JSON.stringify({ t: "content", type: "tv", tmdbId: "1396", season: 2, episode: 3 }), true);
  assert.deepStrictEqual({ type: content.msg.type, tmdbId: content.msg.tmdbId, season: content.msg.season, episode: content.msg.episode }, { type: "tv", tmdbId: 1396, season: 2, episode: 3 });
});

/* ---------- room lifecycle ---------- */

test("create + join by code + leave + end", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana", roomName: "Movie night" });
  const room1 = await a.waitType("room");
  assert.strictEqual(room1.room.name, "Movie night");
  assert.match(room1.room.code, /^[A-Z2-9]{6}$/);
  assert.strictEqual(room1.room.participants.length, 1);

  b.send({ t: "join", name: "Bo", code: room1.room.code });
  const room2 = await b.waitType("room");
  assert.strictEqual(room2.room.participants.length, 2);
  const peer = await a.waitType("peer_joined");
  assert.strictEqual(peer.participant.name, "Bo");

  b.send({ t: "leave" });
  const left = await a.waitType("peer_left");
  assert.strictEqual(left.name, "Bo");

  a.send({ t: "end" });
  const ended = await a.waitType("room_ended");
  assert.strictEqual(ended.reason, "host_ended");
});

test("wrong password rejected; correct password joins", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  const c = await connect(url);
  a.send({ t: "create", name: "Ana", password: "hunter2" });
  const room = (await a.waitType("room")).room;

  b.send({ t: "join", name: "Bo", code: room.code, password: "nope" });
  const err = await b.waitType("error");
  assert.strictEqual(err.error, "wrong_password");

  c.send({ t: "join", name: "Cy", code: room.code, password: "hunter2" });
  const ok = await c.waitType("room");
  assert.strictEqual(ok.room.participants.length, 2);
});

test("full room rejects the extra participant", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  const c = await connect(url);
  a.send({ t: "create", name: "Ana", maxParticipants: 2 });
  const room = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room.code });
  await b.waitType("room");
  c.send({ t: "join", name: "Cy", code: room.code });
  const err = await c.waitType("error");
  assert.strictEqual(err.error, "full");
});

test("expired room is reclaimed by the sweep", async (t) => {
  const { app, url } = await startServer(t, { ttl: { maxRoomAge: 50, emptyRoomTtl: 50, participantTtl: 50, hostGrace: 50 } });
  const a = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room = (await a.waitType("room")).room;
  await new Promise((r) => setTimeout(r, 80));
  const batches = app.rooms.sweep();
  assert.ok(batches.some((b) => b.events.some((e) => e.t === "room_ended" && e.reason === "expired")));
  assert.strictEqual(app.rooms.roomCount(), 0);
  void room;
});

test("join-code guessing is rate limited", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  a.send({ t: "create", name: "Ana" });
  await a.waitType("room");
  const bot = await connect(url); // fresh identity, not in a room
  for (let i = 0; i < LIMITS.CODE_ATTEMPT_MAX + 1; i++) {
    bot.send({ t: "join", name: "Bot", code: "ZZZZZZ" });
  }
  const err = await bot.next((m) => m.t === "error" && m.error === "rate_limited");
  assert.strictEqual(err.error, "rate_limited");
});

/* ---------- synchronization ---------- */

test("play/pause/seek broadcast authoritative state to everyone", async (t) => {
  const { app, url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room.code });
  await b.waitType("room");

  a.send({ t: "playback", action: "play", position: 30, sessionVersion: room.playback.sessionVersion });
  const ev1 = await b.waitType("playback");
  assert.strictEqual(ev1.action, "play");
  assert.strictEqual(ev1.state.status, "playing");
  assert.strictEqual(ev1.state.position, 30);
  await a.waitType("playback"); // actor gets the echo

  a.drain(); b.drain();
  a.send({ t: "playback", action: "seek", position: 120, sessionVersion: room.playback.sessionVersion });
  const ev2 = await b.waitType("playback");
  assert.strictEqual(ev2.state.position, 120);

  a.drain(); b.drain();
  a.send({ t: "playback", action: "pause", position: 125, sessionVersion: room.playback.sessionVersion });
  const ev3 = await b.waitType("playback");
  assert.strictEqual(ev3.state.status, "paused");
  assert.strictEqual(ev3.state.position, 125);
  // authoritative state is queryable for late joins
  const r2 = app.rooms.rooms.get(room.roomId);
  assert.strictEqual(r2.playback.status, "paused");
  assert.strictEqual(r2.playback.position, 125);
});

test("late joiner receives current content + position and cannot reset it", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room = (await a.waitType("room")).room;
  a.send({ t: "content", type: "movie", tmdbId: 550, season: 1, episode: 1 });
  await a.waitType("content");
  a.send({ t: "playback", action: "play", position: 42, sessionVersion: 2 });
  await a.waitType("playback");

  b.send({ t: "join", name: "Bo", code: room.code });
  const r = (await b.waitType("room")).room;
  assert.strictEqual(r.content.tmdbId, 550);
  assert.strictEqual(r.playback.sessionVersion, 2);
  assert.ok(Math.abs(r.playback.position - 42) < 5, "late joiner sees the party position, not zero");
  // the late joiner's mere presence sent no playback event: state untouched
  a.drain();
  b.send({ t: "resync" });
  await b.waitType("room_state");
  const st = await a.next((m) => m.t === "room_state" || m.t === "playback", 300).catch(() => null);
  assert.strictEqual(st, null, "join/resync never emits playback");
});

test("episode change bumps sessionVersion and stale playback is rejected", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room.code });
  await b.waitType("room");

  a.send({ t: "content", type: "tv", tmdbId: 1396, season: 1, episode: 1 });
  const ev = await b.waitType("content");
  assert.strictEqual(ev.sessionVersion, 2);

  // stale: action stamped for the PREVIOUS session
  a.drain(); b.drain();
  a.send({ t: "playback", action: "seek", position: 500, sessionVersion: 1 });
  const notice = await a.waitType("notice");
  assert.strictEqual(notice.notice, "stale_playback");
  const st = await b.next((m) => m.t === "playback", 300).catch(() => null);
  assert.strictEqual(st, null, "stale seek never reaches participants");

  // current session works
  a.drain();
  a.send({ t: "playback", action: "seek", position: 5, sessionVersion: 2 });
  const ok = await b.waitType("playback");
  assert.strictEqual(ok.state.position, 5);
});

test("duplicate playback commands are idempotent (no double-advance)", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room = (await a.waitType("room")).room;
  const sv = room.playback.sessionVersion;
  a.drain();
  a.send({ t: "playback", action: "seek", position: 60, sessionVersion: sv });
  a.send({ t: "playback", action: "seek", position: 60, sessionVersion: sv });
  await a.next((m) => m.t === "playback");
  await a.next((m) => m.t === "playback");
  const room2 = (await (async () => { a.send({ t: "resync" }); return a.waitType("room_state"); })()).room;
  assert.strictEqual(room2.playback.position, 60);
});

test("non-host playback rejected without allowControl, allowed with it", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana", allowControl: false });
  const room = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room.code });
  await b.waitType("room");

  b.send({ t: "playback", action: "pause", position: 10, sessionVersion: room.playback.sessionVersion });
  const err = await b.waitType("error");
  assert.strictEqual(err.error, "unauthorized");

  a.send({ t: "settings", allowControl: true });
  await b.waitType("settings");
  b.drain();
  b.send({ t: "playback", action: "pause", position: 10, sessionVersion: room.playback.sessionVersion });
  const ev = await b.waitType("playback");
  assert.strictEqual(ev.state.status, "paused");
});

test("episode change is host-only", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana", allowControl: true });
  const room = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room.code });
  await b.waitType("room");
  b.send({ t: "content", type: "movie", tmdbId: 1, season: 1, episode: 1 });
  const err = await b.waitType("error");
  assert.strictEqual(err.error, "unauthorized");
});

/* ---------- host disconnect / reconnect ---------- */

test("host disconnect: grace, reattach restores host", async (t) => {
  const { app, url } = await startServer(t, { ttl: { hostGrace: 120, participantTtl: 120, emptyRoomTtl: 500, maxRoomAge: 60000 } });
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const m1 = await a.waitType("room");
  const room1 = m1.room;
  const tokenA = m1.token;
  b.send({ t: "join", name: "Bo", code: room1.code });
  const room2 = (await b.waitType("room")).room;

  // host drops (network blip)
  a.close();
  await b.waitType("peer_joined"); // connection-state update
  // within grace: seat + host id preserved
  let snap = app.rooms.rooms.get(room1.roomId);
  assert.strictEqual(snap.hostId, room1.hostId, "host seat preserved during the grace window");

  // host returns with its resume token -> reattached as same identity
  const a2 = await connect(url);
  a2.send({ t: "join", name: "Ana", code: room1.code, token: tokenA });
  const r2 = await a2.next((m) => m.t === "room" || m.t === "error");
  assert.strictEqual(r2.t, "room", "reattach by resume token");
  assert.strictEqual(r2.room.hostId, room1.hostId, "host seat restored on reattach");
  void room2;
});

test("host expiry transfers hosting to the longest-standing participant", async (t) => {
  const { app, url } = await startServer(t, { ttl: { hostGrace: 60, participantTtl: 60, emptyRoomTtl: 500, maxRoomAge: 60000 } });
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room1 = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room1.code });
  const room2 = (await b.waitType("room")).room;

  a.close();
  await b.waitType("peer_joined");
  await new Promise((r) => setTimeout(r, 90));
  const batches = app.rooms.sweep();
  const ev = batches.flatMap((x) => x.events).find((e) => e.t === "host_changed");
  assert.ok(ev, "host_changed emitted after grace");
  assert.strictEqual(ev.hostId, room2.selfId);
});

test("seat expiry while away: token restores identity AND the host seat", async (t) => {
  const { app, url } = await startServer(t, { ttl: { participantTtl: 60, hostGrace: 5000, emptyRoomTtl: 5000, maxRoomAge: 60000 } });
  const a = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const m1 = await a.waitType("room");
  const b = await connect(url);
  b.send({ t: "join", name: "Bo", code: m1.room.code });
  await b.waitType("room");
  // host's phone sleeps: socket drops, the seat expires past the TTL
  a.close();
  await b.waitType("peer_joined");
  await new Promise((r) => setTimeout(r, 90));
  app.rooms.sweep(); // seat removed, remembered for 5 minutes
  // the phone wakes and rejoins by resume token
  const a2 = await connect(url);
  a2.send({ t: "join", name: "Ana", code: m1.room.code, token: m1.token });
  const r = await a2.next((m) => m.t === "room" || m.t === "error");
  assert.strictEqual(r.t, "room");
  assert.strictEqual(r.reattached, true);
  assert.strictEqual(r.room.hostId, m1.room.hostId, "the returning host gets the host seat back");
});

test("a kicked participant can never come back on the old token", async (t) => {
  const { app, url } = await startServer(t, { ttl: { participantTtl: 60, hostGrace: 60000, emptyRoomTtl: 5000, maxRoomAge: 60000 } });
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const m1 = await a.waitType("room");
  b.send({ t: "join", name: "Bo", code: m1.room.code });
  const m2 = await b.waitType("room");
  a.send({ t: "kick", targetId: m2.room.selfId });
  await b.waitType("kicked");
  const b2 = await connect(url);
  b2.send({ t: "join", name: "Bo", code: m1.room.code, token: m2.token || "stale-token-1234" });
  const r = await b2.next((m) => m.t === "room" || m.t === "error");
  // a fresh seat is allowed (kicking is not a ban) but the OLD identity/host
  // state must never be restored - token reattach must be refused
  assert.strictEqual(r.t, "room");
  assert.ok(!r.reattached, "kicked token must not reattach");
  void app;
});

test("heartbeat after the room expires reports room_gone", async (t) => {
  const { app, url } = await startServer(t, { ttl: { maxRoomAge: 50, participantTtl: 5000, emptyRoomTtl: 5000, hostGrace: 5000 } });
  const a = await connect(url);
  a.send({ t: "create", name: "Ana" });
  await a.waitType("room");
  await new Promise((r) => setTimeout(r, 80));
  app.rooms.sweep(); // room ages out
  a.drain();
  a.send({ t: "heartbeat", clientTs: Date.now() });
  await a.waitType("heartbeat");
  const notice = await a.waitType("notice");
  assert.strictEqual(notice.notice, "room_gone");
});

/* ---------- chat ---------- */

test("chat broadcasts with id + name; rate limited; muted users blocked", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room.code });
  const roomB = (await b.waitType("room")).room;

  b.send({ t: "chat", text: "hello \u{1F389}" });
  const ev = await a.waitType("chat");
  assert.strictEqual(ev.msg.name, "Bo");
  assert.strictEqual(ev.msg.text, "hello \u{1F389}"); // emoji ride as plain text
  assert.ok(ev.msg.id);

  // host mutes Bo -> Bo can no longer chat (checked before the rate limiter's
  // window would matter: this is only Bo's second message)
  a.send({ t: "mute", targetId: roomB.selfId, muted: true });
  await b.next((m) => m.t === "peer_joined" && m.updated);
  b.send({ t: "chat", text: "can I speak?" });
  const err2 = await b.next((m) => m.t === "error" && m.for === "chat");
  assert.strictEqual(err2.error, "unauthorized");

  // rate limit: a burst beyond CHAT_RATE_MAX is refused
  let limited = false;
  for (let i = 0; i < LIMITS.CHAT_RATE_MAX + 3; i++) b.send({ t: "chat", text: "spam " + i });
  const err = await b.next((m) => m.t === "error" && m.for === "chat" && m.error === "rate_limited").catch(() => null);
  limited = !!err;
  assert.ok(limited, "chat rate limit enforced");
});

test("kick removes the participant and notifies the room", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  const b = await connect(url);
  a.send({ t: "create", name: "Ana" });
  const room = (await a.waitType("room")).room;
  b.send({ t: "join", name: "Bo", code: room.code });
  const r2 = (await b.waitType("room")).room;
  const boId = r2.selfId;

  a.send({ t: "kick", targetId: boId });
  const kicked = await b.waitType("kicked");
  assert.strictEqual(kicked.targetId, boId);
  const left = await a.waitType("peer_left");
  assert.strictEqual(left.reason, "kicked");
});

/* ---------- transport hardening ---------- */

test("oversized + invalid messages are refused, server stays up", async (t) => {
  const { url } = await startServer(t);
  const a = await connect(url);
  a.send({ t: "create", name: "Ana" });
  await a.waitType("room");
  a.ws.send("x".repeat(LIMITS.MAX_MSG_BYTES + 100)); // > maxPayload -> ws closes the socket
  const b = await connect(url);
  b.send({ t: "create", name: "Bo" });
  const room = await b.waitType("room");
  assert.ok(room.room.code, "server still serves after abusive input");
});

test("origin allowlist rejects foreign origins at upgrade", async (t) => {
  const { url } = await startServer(t, { allowedOrigins: ["https://dazoarmando29.github.io"] });
  await new Promise((resolve) => {
    const ws = new WebSocket(url, { origin: "https://evil.example" });
    ws.on("error", () => resolve()); // 403 at upgrade
    ws.on("open", () => { assert.fail("foreign origin must not connect"); resolve(); });
  });
  const ok = await connect(url);
  ok.send({ t: "create", name: "Ana" });
  const room = await ok.waitType("room");
  assert.ok(room.room.code, "allowed/origin-less clients still connect");
});

test("simulated multi-device party: 3 clients converge on one state", async (t) => {
  const { url } = await startServer(t);
  const host = await connect(url);
  const p2 = await connect(url);
  const p3 = await connect(url);
  host.send({ t: "create", name: "Host" });
  const room = (await host.waitType("room")).room;
  for (const c of [p2, p3]) {
    c.send({ t: "join", name: "Guest", code: room.code });
    await c.waitType("room");
    await host.waitType("peer_joined");
  }
  const sv = room.playback.sessionVersion;
  host.send({ t: "playback", action: "play", position: 10, sessionVersion: sv });
  const states = await Promise.all([p2, p3].map((c) => c.waitType("playback")));
  assert.strictEqual(states[0].state.position, states[1].state.position);
  assert.strictEqual(states[0].seq, states[1].seq, "everyone sees the same event order");
  host.send({ t: "content", type: "tv", tmdbId: 1399, season: 3, episode: 7 });
  const c2 = await p2.waitType("content");
  const c3 = await p3.waitType("content");
  assert.strictEqual(c2.sessionVersion, c3.sessionVersion);
  assert.deepStrictEqual(c2.content, c3.content);
});
