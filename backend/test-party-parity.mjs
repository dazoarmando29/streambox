/* Parity smoke test for src/party.js (the PartyHub Durable Object) against
   the behavior locked down by cinesrc-website/party-server/test/sync-reliability
   .test.js (the Node reference). This is a REAL behavioral harness, not a regex
   check: it runs with plain `node test-party-parity.mjs` (no framework, no
   Cloudflare runtime) by driving the hub at DISPATCH level with fake
   WebSockets + a fake DO state object, with Date.now() pinned to a fake clock
   (the reference suite injects a clock the same way).

   Scenarios mirror the reference suite:
     - coordinated episode transitions (readiness-gated two-phase gate)
     - stale sessions + idempotent/late "ended" completions
     - wait-for-everyone buffering holds (create/release/timeout/override)
     - stale-sv readiness guard + sync_report riding the pstate channel
     - settings clamps + hold release on switching back to "move"
     - alarm-driven deadline enforcement (the DO's tick)
     - heartbeat-only liveness, hashed passwords, rate limiters
     - the `end` broadcast reaching the host too

   Plus the protocol v2.1 social layer (the party-features.test.js contract):
     - authoritative media version (propagation, guest denial, persistence)
     - host/co-host roles + granular guest permissions (server-enforced)
     - playback requests (server-side approval execution, approver routing)
     - chat replies/spoiler/scene positions + reactions and their budget
     - shared queue (suggest/vote/approve -> canonical episode, server-side)
     - locked rooms (fresh joins rejected, resume tokens still reattach)
     - group intermission (bounded countdown, auto-resume on the alarm) */

import assert from "node:assert/strict";
import { PartyHub } from "./src/party.js";

/* ---------- fake clock ---------- */
const T0 = 1_700_000_000_000;
const clock = { t: T0 };
Date.now = () => clock.t;

/* LIMITS values the hub keeps private - asserted through behavior where
   possible, pinned here where the wire does not echo them back. */
const TRANSITION_MIN_MS = 3000;
const START_COUNTDOWN_MS = 2500;
const MEDIA_FIELDS = 48;
const CHAT_RATE_MAX = 6; // reactions share the chat budget

/* ---------- fake DO state + fake WebSockets ---------- */

function mkSocket() {
  return {
    out: [],
    meta: null,
    serializeAttachment(m) { this.meta = m; },
    deserializeAttachment() { return this.meta; },
    send(s) { this.out.push(JSON.parse(s)); },
  };
}

function mkEnv() {
  const sockets = [];
  const alarms = [];
  const state = {
    storage: { setAlarm: (t) => { alarms.push(t); } },
    acceptWebSocket: (ws) => { sockets.push(ws); },
    getWebSockets: () => sockets.slice(),
  };
  return { hub: new PartyHub(state, {}), sockets, alarms };
}

let seq = 0;
function connect(env, name) {
  const ws = mkSocket();
  seq += 1;
  // mirrors the meta minted in PartyHub.fetch (id/token/name/room/cid)
  ws.meta = {
    id: "p" + seq, token: "tok" + seq, name: name || "Guest",
    roomId: "", ip: "10.0.0." + (seq % 200), cid: "c" + seq,
  };
  env.sockets.push(ws);
  return ws;
}

function take(ws) { const out = ws.out; ws.out = []; return out; }
const of = (events, t) => (events || []).filter((e) => e.t === t);
const send = (hub, ws, obj) => hub.dispatch(ws, JSON.stringify(obj));

/* Advance the fake clock and run the deadline tick (production runs it on the
   DO alarm; the reference server runs tick() every 2s). */
function advance(ctx, ms, step = 1000) {
  const batches = [];
  let left = ms;
  while (left > 0) {
    const dt = Math.min(step, left);
    clock.t += dt;
    batches.push(...ctx.hub.tick());
    left -= dt;
  }
  return batches;
}

/* A room with `members` participants: host + (members-1) guests. */
async function makeParty(env, members = 2, opts = {}) {
  const host = connect(env, "Host");
  await send(env.hub, host, {
    t: "create", name: "Host", roomName: "Test party",
    maxParticipants: 16, allowControl: opts.allowControl === true,
  });
  const roomMsg = take(host).find((x) => x.t === "room");
  assert.ok(roomMsg, "host gets a room reply");
  const rid = roomMsg.room.roomId;
  const code = roomMsg.room.code;
  const people = { host };
  for (let i = 1; i < members; i++) {
    const g = connect(env, "G" + i);
    await send(env.hub, g, { t: "join", name: "G" + i, code });
    assert.ok(take(g).find((x) => x.t === "room"), "guest G" + i + " joins");
    people["g" + i] = g;
  }
  for (const ws of env.sockets) take(ws); // drop join noise
  const ids = {};
  for (const [k, ws] of Object.entries(people)) ids[k] = ws.meta.id;
  return {
    env, hub: env.hub, rid, code, people, ids, snap0: roomMsg.room,
    room: () => env.hub.rooms.get(rid),
    snap: () => env.hub.snapshot(env.hub.rooms.get(rid), ids.host),
  };
}

/* ---------- tiny runner (runs after all tests are registered below) ---------- */

const tests = [];
function test(name, fn) { tests.push({ name, fn }); }

async function runAll() {
  let passed = 0, failed = 0;
  for (const { name, fn } of tests) {
    clock.t = T0;
    try {
      await fn();
      passed++;
      console.log("  ok   " + name);
    } catch (e) {
      failed++;
      console.log("  FAIL " + name);
      console.log("       " + ((e && e.message) || e));
      // multi-line assert messages flood stack previews: show the frames only
      if (e && e.stack) console.log(String(e.stack).split("\n").filter((l) => l.trim().startsWith("at ")).slice(0, 3).join("\n"));
    }
  }
  console.log("\n" + passed + " passed, " + failed + " failed");
  process.exit(failed ? 1 : 0);
}

/* ============================ scenarios ============================ */

test("welcome carries proto: 2 and minted identity (fetch path)", async () => {
  const env = mkEnv();
  const pair = [mkSocket(), mkSocket()];
  // Workers-only globals: stubbed just enough for fetch() to run in Node
  globalThis.WebSocketPair = class { constructor() { this[0] = pair[0]; this[1] = pair[1]; } };
  globalThis.Response = class { constructor(body, init) { this.body = body; this.init = init || {}; } };
  const req = {
    url: "https://hub.test/party",
    headers: { get: (k) => ({ upgrade: "websocket" })[String(k).toLowerCase()] ?? null },
  };
  await env.hub.fetch(req);
  const welcome = take(pair[1]).find((x) => x.t === "welcome");
  assert.ok(welcome, "welcome sent on connect");
  assert.strictEqual(welcome.proto, 2);
  assert.ok(welcome.participantId && welcome.token && typeof welcome.serverTs === "number");
  assert.ok(pair[1].meta && pair[1].meta.cid, "identity rides the socket attachment");
});

test("room snapshot carries wait policy + gate/hold fields and participant pstate", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const snap = ctx.snap0;
  assert.strictEqual(snap.waitMode, "move");
  assert.strictEqual(snap.readyTimeoutMs, 8000);
  assert.strictEqual(snap.holdTimeoutMs, 20000);
  assert.strictEqual(snap.gate, null);
  assert.strictEqual(snap.hold, null);
  assert.ok(snap.participants.every((p) => p.pstate === "ready"), "per-participant player state in the snapshot");
  assert.strictEqual(snap.playback.status, "waiting");
});

test("coordinated episode transition: readiness-gated two-phase gate", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1, ids } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "playback", action: "play", sessionVersion: 1 });
  take(host); take(g1);
  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 2 });
  const tC = clock.t;
  const evs = take(host);
  const contentEv = of(evs, "content")[0];
  const schedEv = of(evs, "schedule")[0];
  assert.ok(contentEv && schedEv, "content + schedule broadcast together");
  assert.strictEqual(contentEv.sessionVersion, 2, "content session version bumped");
  assert.strictEqual(contentEv.state.position, 0);
  assert.ok(contentEv.state.updatedAt > tC, "shared gate is in the future (scheduled start)");
  assert.strictEqual(schedEv.phase, "loading");
  assert.strictEqual(schedEv.reason, "content");
  assert.strictEqual(schedEv.startAt, null);
  assert.strictEqual(schedEv.ready, 0);
  assert.strictEqual(schedEv.total, 2);
  // gate holds the room at 00:00 and is visible in the snapshot
  const snap = ctx.snap();
  assert.strictEqual(snap.playback.position, 0);
  assert.strictEqual(snap.gate.phase, "loading");
  assert.strictEqual(snap.gate.deadline, tC + 8000);
  // connected participants with stale stamps implicitly reload (-> loading)
  assert.strictEqual(ctx.room().participants.get(ids.host).pstate, "loading");
  // everyone reports loading then ready
  await send(hub, host, { t: "pstate", st: "loading", sv: 2 });
  await send(hub, g1, { t: "pstate", st: "loading", sv: 2 });
  await send(hub, host, { t: "pstate", st: "ready", sv: 2 });
  await send(hub, g1, { t: "pstate", st: "ready", sv: 2 });
  take(host); take(g1);
  // earliest passes -> readiness schedules the start (alarm = the DO's tick)
  clock.t = tC + TRANSITION_MIN_MS + 100;
  await hub.alarm();
  const evs2 = take(host);
  const finalContent = of(evs2, "content")[0];
  const sched2 = of(evs2, "schedule")[0];
  assert.ok(finalContent, "the finalized gate is re-broadcast as a content event");
  assert.strictEqual(finalContent.sessionVersion, 2, "same session - no reload, just a re-armed start");
  assert.strictEqual(finalContent.actor, "Party");
  assert.strictEqual(finalContent.state.updatedAt, tC + TRANSITION_MIN_MS + 100 + START_COUNTDOWN_MS);
  assert.strictEqual(sched2.phase, "scheduled");
  assert.strictEqual(sched2.reason, "ready");
  assert.strictEqual(sched2.ready, 2);
  assert.strictEqual(sched2.startAt, tC + TRANSITION_MIN_MS + 100 + START_COUNTDOWN_MS);
  // the gate sweeps to live once startAt is behind us
  clock.t = tC + 6000;
  await hub.alarm();
  assert.strictEqual(ctx.room().gate.phase, "live");
  // a legit completion is accepted now (the sweep exists so THIS is not stale)
  await send(hub, host, { t: "playback", action: "ended", sessionVersion: 2 });
  assert.strictEqual(of(take(host), "playback")[0].state.status, "ended");
  // ...and a duplicate completion is swallowed
  await send(hub, host, { t: "playback", action: "ended", sessionVersion: 2 });
  assert.strictEqual(of(take(host), "playback").length, 0, "a duplicated completion is swallowed");
});

test("stale sessions bounce and only the latest session is actionable", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 2 });
  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 3 });
  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 4 });
  const svs = take(host).filter((e) => e.t === "content").map((e) => e.sessionVersion);
  assert.deepStrictEqual(svs, [2, 3, 4], "each change bumps the session exactly once");
  take(g1);
  // events stamped for older sessions bounce as stale
  await send(hub, host, { t: "playback", action: "play", sessionVersion: 2 });
  assert.deepStrictEqual(take(host), [{ t: "notice", notice: "stale_playback" }]);
  await send(hub, host, { t: "playback", action: "ended", sessionVersion: 3 });
  assert.deepStrictEqual(take(host), [{ t: "notice", notice: "stale_playback" }]);
  // and a completion racing a pending transition is refused even unstamped
  await send(hub, host, { t: "playback", action: "ended" });
  assert.deepStrictEqual(take(host), [{ t: "notice", notice: "stale_playback" }]);
  // current-session commands still work after the storm
  await send(hub, host, { t: "playback", action: "play", sessionVersion: 4 });
  const evs = take(host);
  assert.strictEqual(of(evs, "playback")[0].action, "play", "current-session command accepted");
  assert.strictEqual(of(evs, "schedule")[0].reason, "host_override");
});

test("wait-for-everyone: a buffering participant holds the room, recovery releases it", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "settings", waitMode: "everyone" });
  const sev = of(take(host), "settings")[0];
  assert.strictEqual(sev.waitMode, "everyone");
  assert.strictEqual(sev.readyTimeoutMs, 8000);
  assert.strictEqual(sev.holdTimeoutMs, 20000);
  await send(hub, host, { t: "playback", action: "play", position: 100, sessionVersion: 1 });
  take(host); take(g1);
  advance(ctx, 20_000);
  await send(hub, g1, { t: "pstate", st: "buffering", sv: 1 });
  const evs = take(g1);
  assert.ok(of(evs, "peer_state").some((e) => e.st === "buffering"), "the room SEES the buffering participant");
  const pauseEv = of(evs, "playback")[0];
  assert.ok(pauseEv && pauseEv.auto === true && pauseEv.action === "pause" && pauseEv.actor === "Party");
  assert.strictEqual(pauseEv.state.position, 120, "paused at the AUTHORITATIVE position, not the stalled player's timestamp");
  assert.strictEqual(pauseEv.state.status, "paused");
  assert.strictEqual(pauseEv.hold.waiting, 1);
  assert.strictEqual(pauseEv.hold.until, clock.t + 20000);
  advance(ctx, 10_000);
  assert.strictEqual(ctx.snap().playback.status, "paused", "room is still holding");
  assert.strictEqual(ctx.snap().playback.position, 120, "held room never advances");
  await send(hub, g1, { t: "pstate", st: "recovered", sv: 1 });
  const playEv = of(take(g1), "playback")[0];
  assert.ok(playEv && playEv.auto === true && playEv.action === "play" && playEv.hold === null);
  assert.strictEqual(playEv.state.updatedAt, clock.t + 500, "tiny shared resume gate - everyone unfreezes together");
  assert.strictEqual(ctx.snap().playback.status, "playing");
});

test("hold timeout releases on the alarm with a hold_released notice", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "settings", waitMode: "everyone", holdTimeoutMs: 10000 });
  assert.strictEqual(of(take(host), "settings")[0].holdTimeoutMs, 10000);
  await send(hub, host, { t: "playback", action: "play", position: 100, sessionVersion: 1 });
  take(host); take(g1);
  advance(ctx, 5000);
  await send(hub, g1, { t: "pstate", st: "buffering", sv: 1 });
  const holdUntil = ctx.room().hold.until;
  take(g1); take(host);
  assert.strictEqual(env.alarms.at(-1), holdUntil, "armAlarm targets the next hold expiry");
  // nobody sends anything: the ALARM resolves the hold (tick parity)
  clock.t = holdUntil + 2000;
  await hub.alarm();
  const evs = take(g1);
  const playEv = of(evs, "playback")[0];
  assert.ok(playEv && playEv.auto === true && playEv.action === "play");
  const notice = of(evs, "notice").find((e) => e.notice === "hold_released");
  assert.ok(notice, "the room is told the hold released without the stuck viewer");
  assert.strictEqual(notice.waiting, 1);
  assert.strictEqual(ctx.snap().playback.status, "playing", "the party is not trapped by one dead source");
});

test("playback buffer action tracks the participant and holds in wait-for-everyone mode", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2, { allowControl: true });
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "playback", action: "play", position: 100, sessionVersion: 1 });
  take(host); take(g1);
  await send(hub, g1, { t: "playback", action: "buffer" });
  let evs = take(g1);
  assert.strictEqual(of(evs, "peer_state").length, 1);
  assert.strictEqual(of(evs, "playback").length, 0, "move mode never disturbs the timeline");
  assert.strictEqual(ctx.snap().playback.status, "playing");
  await send(hub, host, { t: "settings", waitMode: "everyone" });
  take(host);
  await send(hub, g1, { t: "playback", action: "buffer" });
  evs = take(g1);
  assert.strictEqual(of(evs, "playback")[0].action, "pause", "the buffer action can create a hold");
  assert.strictEqual(ctx.snap().playback.status, "paused");
});

test("host override: play or seek during a pending transition starts the room now", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 2 });
  take(host);
  await send(hub, host, { t: "playback", action: "seek", position: 5 });
  let evs = take(host);
  assert.strictEqual(of(evs, "content").length, 1, "seek override finalizes the gate");
  assert.strictEqual(of(evs, "schedule")[0].reason, "host_override");
  assert.strictEqual(of(evs, "playback")[0].state.position, 5);
  advance(ctx, 1000);
  assert.strictEqual(ctx.room().gate.phase, "live");

  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 3 });
  take(host);
  await send(hub, host, { t: "playback", action: "play" });
  evs = take(host);
  assert.strictEqual(of(evs, "content").length, 1, "play override finalizes the gate too");
  assert.strictEqual(of(evs, "schedule")[0].reason, "host_override");
  assert.strictEqual(of(evs, "playback")[0].state.status, "playing");
  advance(ctx, 1000);
  assert.strictEqual(ctx.room().gate.phase, "live");
});

test("stale-sv readiness is ignored; sync_report rides the pstate channel", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1, ids } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 2 });
  take(host); take(g1);
  await send(hub, g1, { t: "pstate", st: "playing", sv: 99 });
  assert.ok(of(take(host), "peer_state").length === 1, "the state change is still visible");
  assert.strictEqual(ctx.snap().gate.ready, 0, "stale-sv readiness is ignored");
  await send(hub, g1, { t: "pstate", st: "ready", sv: 2 });
  assert.strictEqual(ctx.snap().gate.ready, 1);
  // a sync_report can carry player state + session stamp (additive fields)
  await send(hub, g1, { t: "sync_report", position: 55, paused: true, dur: 1400, source: "VidBolt", st: "recovered", sv: 2 });
  const p = ctx.room().participants.get(ids.g1);
  assert.strictEqual(p.dur, 1400);
  assert.strictEqual(p.source, "VidBolt");
  assert.strictEqual(p.position, 55);
  assert.ok(of(take(host), "peer_state").some((e) => e.st === "recovered"));
  assert.strictEqual(ctx.snap().gate.ready, 1, "recovered re-marks the same seat");
  // a report stamped for the PREVIOUS session never marks anyone ready
  await send(hub, host, { t: "sync_report", position: 0, paused: true, st: "ready", sv: 1 });
  assert.strictEqual(ctx.snap().gate.ready, 1, "stale-sv readiness is ignored");
  // invalid player states are protocol errors
  await send(hub, g1, { t: "pstate", st: "bogus" });
  const err = take(g1).find((x) => x.t === "error");
  assert.ok(err && err.error === "bad_message", "invalid player state rejected");
});

test("settings clamp the timeouts and switching to move releases the hold", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "settings", waitMode: "everyone", readyTimeoutMs: 500, holdTimeoutMs: 10000 });
  const sev = of(take(host), "settings")[0];
  assert.strictEqual(sev.readyTimeoutMs, 4000, "readyTimeoutMs clamped to the floor");
  assert.strictEqual(sev.holdTimeoutMs, 10000);
  await send(hub, host, { t: "playback", action: "play", position: 100, sessionVersion: 1 });
  take(host); take(g1);
  advance(ctx, 5000);
  await send(hub, g1, { t: "pstate", st: "buffering", sv: 1 });
  assert.ok(ctx.snap().hold, "hold is active");
  take(host); take(g1);
  await send(hub, host, { t: "settings", waitMode: "move" });
  const evs = take(host);
  assert.strictEqual(of(evs, "settings")[0].waitMode, "move");
  const rel = of(evs, "playback")[0];
  assert.ok(rel && rel.auto === true && rel.action === "play");
  assert.strictEqual(of(evs, "notice").length, 0, "switching policy releases quietly");
  assert.strictEqual(ctx.snap().hold, null);
  await send(hub, host, { t: "settings", holdTimeoutMs: 999999 });
  assert.strictEqual(of(take(host), "settings")[0].holdTimeoutMs, 120000, "holdTimeoutMs clamped to the ceiling");
});

test("end: the host who ends the room also receives room_ended", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, g1, { t: "end" });
  assert.strictEqual(take(g1)[0].error, "unauthorized", "ending is host-only");
  await send(hub, host, { t: "end" });
  const h = take(host);
  assert.ok(of(h, "room_ended").some((e) => e.reason === "host_ended"), "the host hears its own room_ended");
  assert.strictEqual(of(take(g1), "room_ended").length, 1);
  assert.strictEqual(hub.rooms.size, 0, "room dropped after the broadcast");
});

test("heartbeat-only traffic keeps the seat alive; silence still counts as gone", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, ids } = { ...ctx.people, ...ctx };

  advance(ctx, 90_000);
  await send(hub, host, { t: "heartbeat", clientTs: 123 });
  const hb = take(host).find((x) => x.t === "heartbeat");
  assert.ok(hb && hb.serverTs === clock.t, "heartbeat echoed with the server clock");
  advance(ctx, 95_000); // 185s since join, 95s since the heartbeat
  await hub.alarm();
  const room = ctx.room();
  assert.strictEqual(room.participants.get(ids.host).connected, true, "a heartbeat-only client is NOT evicted as stale");
  assert.ok(!room.participants.has(ids.g1) || !room.participants.get(ids.g1).connected, "silence still counts as gone");
});

test("passwords are hashed per-room, never stored or compared in plaintext", async () => {
  const env = mkEnv();
  const host = connect(env, "Host");
  await send(env.hub, host, { t: "create", name: "Host", roomName: "Locked", password: "pw", maxParticipants: 16 });
  const snap = take(host).find((x) => x.t === "room").room;
  assert.strictEqual(snap.hasPassword, true);
  const g = connect(env, "G");
  await send(env.hub, g, { t: "join", name: "G", code: snap.code, password: "wrong" });
  const err = take(g)[0];
  assert.ok(err && err.t === "error" && err.error === "wrong_password" && err.for === "join");
  await send(env.hub, g, { t: "join", name: "G", code: snap.code, password: "pw" });
  assert.ok(take(g).some((x) => x.t === "room"), "the right password joins");
  const stored = env.hub.rooms.get(snap.roomId);
  assert.strictEqual(stored.password, undefined, "no plaintext password field");
  assert.ok(/^[0-9a-f]{64}$/.test(stored.passwordHash), "sha256 hex digest");
  assert.ok(/^[0-9a-f]{16}$/.test(stored.passwordSalt), "per-room salt");
});

test("pstate has its own limiter: over-limit reports drop silently", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, g1, ids } = { ...ctx.people, ...ctx };

  const l = hub.limFor(ids.g1);
  assert.strictEqual(l.pstate.w, 5000);
  assert.strictEqual(l.pstate.max, 12);
  assert.strictEqual(l.sync.w, 12000);
  assert.strictEqual(l.sync.max, 3);
  take(ctx.people.host); take(g1);
  for (let i = 0; i < 14; i++) {
    await send(hub, g1, { t: "pstate", st: i % 2 ? "ready" : "playing", sv: 1 });
  }
  const evs = take(g1);
  assert.strictEqual(of(evs, "peer_state").length, 12, "12 reports pass the 5s window");
  assert.strictEqual(of(evs, "error").length, 0, "over-limit reports are dropped SILENTLY");
});

test("sync_report has its own limiter and clamps invalid positions leniently", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, g1, ids } = { ...ctx.people, ...ctx };
  const pos = () => ctx.room().participants.get(ids.g1).position;

  take(ctx.people.host); take(g1);
  await send(hub, g1, { t: "sync_report", position: 111, paused: false });
  await send(hub, g1, { t: "sync_report", position: -5, paused: true });
  assert.strictEqual(pos(), 0, "invalid position is CLAMPED (lenient DO behavior), not rejected");
  await send(hub, g1, { t: "sync_report", position: 77, paused: false });
  assert.strictEqual(pos(), 77);
  await send(hub, g1, { t: "sync_report", position: 88, paused: false });
  assert.strictEqual(pos(), 77, "4th report in the window is dropped silently");
  assert.strictEqual(take(g1).filter((x) => x.t === "error").length, 0);
});

/* ==================== protocol v2.1: media + social layer ==================== */

test("media: intended version reaches joiners, guests denied, survives content changes", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  // host watches Japanese audio + English subs on AniPM (provider identifiers)
  await send(hub, host, { t: "media", media: { mirror: "anipm", variant: "mal:5114/ep:5", audio: "sub", subs: "en", subsOn: true } });
  const ev = of(take(host), "media")[0];
  assert.ok(ev, "media change broadcasts");
  assert.strictEqual(ev.media.audio, "sub");
  assert.strictEqual(ev.media.subsOn, true);
  assert.strictEqual(ev.media.by, "Host");
  assert.strictEqual(ev.actor, "Host");
  // a late joiner's snapshot carries the intended version
  const late = connect(env, "Late");
  await send(hub, late, { t: "join", name: "Late", code: ctx.code });
  const snap = take(late).find((x) => x.t === "room").room;
  assert.strictEqual(snap.media.mirror, "anipm");
  assert.strictEqual(snap.media.audio, "sub", "intended audio reaches the joiner");
  assert.strictEqual(snap.media.subs, "en");
  assert.strictEqual(snap.media.subsOn, true);
  // a guest can never set the party's media version
  take(g1);
  await send(hub, g1, { t: "media", media: { mirror: "anipm", audio: "dub" } });
  const err = take(g1).find((x) => x.t === "error");
  assert.ok(err && err.error === "unauthorized" && err.for === "media", "guest media is refused");
  assert.strictEqual(ctx.snap().media.audio, "sub", "the party version is untouched");
  // episode changes preserve the intended version (it survives session bumps)
  await send(hub, host, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 5 });
  take(host);
  assert.strictEqual(ctx.snap().media.audio, "sub", "content changes never reset the media version");
  // and the reverse: host switches to English dub - everyone follows
  await send(hub, host, { t: "media", media: { mirror: "anipm", variant: "mal:5114/ep:5", audio: "dub", subs: "off", subsOn: false } });
  assert.strictEqual(of(take(host), "media")[0].media.audio, "dub");
  assert.strictEqual(ctx.snap().media.subsOn, false);
});

test("media values are opaque provider identifiers; garbage collapses to a rejection", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host } = { ...ctx.people, ...ctx };

  // VidBolt panel identifiers (label + value) are preserved exactly
  await send(hub, host, { t: "media", media: { mirror: "vidbolt", variant: "audio:3|subs:1", audio: "Tagalog", subs: "English" } });
  const m = of(take(host), "media")[0].media;
  assert.strictEqual(m.audio, "Tagalog");
  assert.strictEqual(m.variant, "audio:3|subs:1");
  // garbage collapses to bad_message instead of inventing state
  await send(hub, host, { t: "media", media: {} });
  const err = take(host).find((x) => x.t === "error");
  assert.ok(err && err.error === "bad_message" && err.for === "media");
  assert.strictEqual(ctx.snap().media.variant, "audio:3|subs:1", "the previous version survives garbage");
  // long junk is capped at the field limit
  await send(hub, host, { t: "media", media: { audio: "x".repeat(200) } });
  assert.strictEqual(of(take(host), "media")[0].media.audio, "x".repeat(MEDIA_FIELDS), "field capped");
});

test("co-host gates: promotion grants control, demotion removes it, co-hosts cannot kick co-hosts", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 3);
  const { hub, host, g1, g2 } = { ...ctx.people, ...ctx };

  // a plain guest cannot control (allowControl off)
  await send(hub, g1, { t: "playback", action: "play" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized");
  // host promotes g1 to co-host
  await send(hub, host, { t: "role", targetId: ctx.ids.g1, role: "cohost" });
  const evs = take(host);
  assert.strictEqual(of(evs, "role")[0].role, "cohost");
  assert.strictEqual(of(evs, "role")[0].participantId, ctx.ids.g1);
  assert.strictEqual(of(evs, "role")[0].actor, "G1", "the role event names the TARGET (rooms.js parity)");
  const upd = of(evs, "peer_joined").find((x) => x.updated);
  assert.strictEqual(upd.participant.role, "cohost", "the role rides the peer update");
  assert.ok(ctx.snap().cohosts.includes(ctx.ids.g1));
  // co-host can drive playback, change episodes and set the media version
  await send(hub, g1, { t: "playback", action: "play", position: 10, sessionVersion: 1 });
  assert.strictEqual(of(take(g1), "playback").length, 1, "co-host playback works");
  await send(hub, g1, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 2 });
  assert.strictEqual(of(take(g1), "content").length, 1, "co-host episode change works");
  await send(hub, g1, { t: "media", media: { mirror: "anipm", audio: "sub" } });
  assert.strictEqual(of(take(g1), "media").length, 1, "co-host media works");
  // co-host can moderate - but never the host or another co-host
  await send(hub, host, { t: "role", targetId: ctx.ids.g2, role: "cohost" });
  take(host); take(g1); take(g2);
  await send(hub, g1, { t: "kick", targetId: ctx.ids.g2 });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized", "co-host cannot kick a co-host");
  await send(hub, g1, { t: "mute", targetId: ctx.ids.g2, muted: true });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized", "co-host cannot mute a co-host");
  // the host can - and the kick also drops the co-host role
  await send(hub, host, { t: "kick", targetId: ctx.ids.g2 });
  assert.strictEqual(of(take(g2), "kicked")[0].targetId, ctx.ids.g2, "kicked reaches ONLY the target");
  assert.strictEqual(of(take(host), "peer_left")[0].reason, "kicked");
  assert.ok(!ctx.snap().cohosts.includes(ctx.ids.g2), "a kick drops the role too");
  // demote: control is gone
  await send(hub, host, { t: "role", targetId: ctx.ids.g1, role: "participant" });
  take(host); take(g1);
  await send(hub, g1, { t: "playback", action: "play" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized", "demotion removes control");
  assert.ok(!ctx.snap().cohosts.includes(ctx.ids.g1));
  // only the host manages roles
  await send(hub, g1, { t: "role", targetId: ctx.ids.host, role: "participant" });
  const err = take(g1).find((x) => x.t === "error");
  assert.ok(err && err.error === "unauthorized" && err.for === "role");
});

test("granular guest permissions gate each action class (seek denied / episode granted)", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2, { allowControl: true });
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  // defaults: play class allowed for guests, episode changes are not
  await send(hub, g1, { t: "playback", action: "play", position: 5, sessionVersion: 1 });
  assert.strictEqual(of(take(g1), "playback").length, 1, "play class allowed by default");
  await send(hub, g1, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 3 });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized", "episode class denied by default");
  // host grants episode rights + disables seeking (host-only matrix)
  await send(hub, host, { t: "settings", perms: { episode: true, seek: false } });
  const sev = of(take(host), "settings")[0];
  assert.strictEqual(sev.perms.episode, true);
  assert.strictEqual(sev.perms.seek, false);
  take(g1);
  await send(hub, g1, { t: "content", type: "tv", tmdbId: 42, season: 1, episode: 3 });
  assert.strictEqual(of(take(g1), "content").length, 1, "granted episode authority works");
  await send(hub, g1, { t: "playback", action: "seek", position: 90 });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized", "seek class denied");
  await send(hub, g1, { t: "playback", action: "pause" });
  assert.strictEqual(of(take(g1), "playback").length, 1, "play/pause class still allowed");
  // guests can never edit the permission matrix
  await send(hub, g1, { t: "settings", perms: { episode: false } });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized");
  assert.strictEqual(ctx.snap().perms.episode, true, "the matrix is untouched");
});

test("requests: approval pauses the room server-side; episode moves are broadcast-only", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "playback", action: "play", position: 100, sessionVersion: 1 });
  take(host); take(g1);
  advance(ctx, 30_000);
  // guest asks for a pause (the requester can NEVER pause directly)
  await send(hub, g1, { t: "request", action: "pause", note: "phone call" });
  const q = of(take(host), "request")[0].request;
  assert.strictEqual(q.id, "q1");
  assert.strictEqual(q.from, "G1");
  assert.strictEqual(q.fromId, ctx.ids.g1);
  assert.strictEqual(q.action, "pause");
  assert.strictEqual(q.note, "phone call");
  await send(hub, g1, { t: "playback", action: "pause" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized", "the requester cannot pause directly");
  // host approval pauses the room SERVER-side
  await send(hub, host, { t: "request_reply", requestId: q.id, approved: true });
  const evs = take(g1);
  const rep = of(evs, "request_reply")[0];
  assert.strictEqual(rep.approved, true);
  assert.strictEqual(rep.requesterId, ctx.ids.g1);
  assert.strictEqual(rep.approverId, ctx.ids.host);
  assert.strictEqual(rep.request.action, "pause");
  const pause = of(evs, "playback")[0];
  assert.ok(pause && pause.action === "pause" && pause.actor === "G1 (approved)");
  assert.strictEqual(ctx.snap().playback.status, "paused", "approval executed by the SERVER");
  // decline path: no state change
  await send(hub, g1, { t: "request", action: "rewind", position: 120 });
  const q2 = of(take(host), "request")[0].request;
  const before = ctx.snap().playback.position;
  await send(hub, host, { t: "request_reply", requestId: q2.id, approved: false });
  assert.strictEqual(of(take(g1), "request_reply")[0].approved, false);
  assert.strictEqual(ctx.snap().playback.position, before, "a declined request never moves the timeline");
  // next-episode approvals are broadcast with the APPROVER id (their client
  // executes the content change - the server cannot know episode data)
  await send(hub, g1, { t: "request", action: "next_episode" });
  const q3 = of(take(host), "request")[0].request;
  await send(hub, host, { t: "request_reply", requestId: q3.id, approved: true });
  const evs2 = take(g1);
  const rep2 = of(evs2, "request_reply")[0];
  assert.strictEqual(rep2.approverId, ctx.ids.host);
  assert.strictEqual(rep2.request.action, "next_episode");
  assert.strictEqual(of(evs2, "playback").length, 0, "no fake timeline action for an episode move");
  // only host/co-host may approve
  await send(hub, g1, { t: "request", action: "pause" });
  const q4 = of(take(host), "request")[0].request;
  await send(hub, g1, { t: "request_reply", requestId: q4.id, approved: true });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized");
  // pending requests are capped (q4 is still pending: a refused reply keeps it)
  for (let i = 0; i < 7; i++) await send(hub, g1, { t: "request", action: "rewind" });
  await send(hub, g1, { t: "request", action: "rewind" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "rate_limited", "pending requests are capped");
});

test("queue: suggest + vote + host approval becomes the canonical episode (server-side)", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 3);
  const { hub, host, g1, g2 } = { ...ctx.people, ...ctx };

  await send(hub, g1, { t: "queue", op: "add", item: { type: "tv", tmdbId: 42, season: 1, episode: 8, title: "Next up" } });
  const item = of(take(host), "queue")[0].item;
  assert.strictEqual(item.votes, 1, "suggester auto-votes");
  assert.strictEqual(item.title, "Next up");
  await send(hub, g2, { t: "queue", op: "vote", itemId: item.id });
  assert.strictEqual(of(take(host), "queue")[0].item.votes, 2);
  // guest approval is refused (canonical playback needs episode authority)
  await send(hub, g1, { t: "queue", op: "approve", itemId: item.id });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized");
  // host approval executes the content change SERVER-side
  await send(hub, host, { t: "queue", op: "approve", itemId: item.id });
  const evs = take(g1);
  assert.strictEqual(of(evs, "queue")[0].op, "approve");
  const contentEv = of(evs, "content")[0];
  assert.ok(contentEv, "approval produced the content transition");
  assert.strictEqual(contentEv.content.episode, 8);
  assert.strictEqual(ctx.snap().queue.length, 0, "approved item leaves the queue");
  take(host); take(g1); take(g2); // drop the approval broadcast copies
  // removal rules: creator can remove own; others need perms.queue
  await send(hub, g1, { t: "queue", op: "add", item: { type: "movie", tmdbId: 7, title: "Film" } });
  const item2 = of(take(host), "queue")[0].item;
  take(g1); take(g2);
  await send(hub, g2, { t: "queue", op: "remove", itemId: item2.id });
  assert.strictEqual(take(g2).find((x) => x.t === "error").error, "unauthorized");
  await send(hub, g1, { t: "queue", op: "remove", itemId: item2.id });
  assert.strictEqual(of(take(g1), "queue")[0].op, "remove");
  // clear needs host/co-host/queue-perm
  await send(hub, g1, { t: "queue", op: "add", item: { type: "movie", tmdbId: 9, title: "B" } });
  take(host); take(g1);
  await send(hub, g1, { t: "queue", op: "clear" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized", "clear needs queue authority");
  await send(hub, host, { t: "queue", op: "clear" });
  assert.strictEqual(of(take(host), "queue")[0].op, "clear");
  assert.strictEqual(ctx.snap().queue.length, 0);
});

test("locked rooms reject fresh joins but a resume token still reattaches", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "media", media: { mirror: "anipm", audio: "sub" } });
  await send(hub, host, { t: "settings", locked: true });
  const sev = of(take(host), "settings")[0];
  assert.strictEqual(sev.locked, true);
  assert.ok(sev.perms && typeof sev.perms === "object", "settings carry the permission matrix");
  assert.strictEqual(ctx.snap().locked, true);
  // a stranger cannot enter
  const stranger = connect(env, "Stranger");
  await send(hub, stranger, { t: "join", name: "Stranger", code: ctx.code });
  const err = take(stranger)[0];
  assert.ok(err && err.error === "closed" && err.for === "join", "door is locked");
  // but a returning participant keeps their seat via the resume token
  await hub.webSocketClose(g1);
  const back = connect(env, "Back");
  await send(hub, back, { t: "join", name: "G1", code: ctx.code, token: g1.meta.token });
  const roomMsg = take(back).find((x) => x.t === "room");
  assert.ok(roomMsg, "the token reattaches through the locked door");
  assert.strictEqual(roomMsg.reattached, true);
  assert.strictEqual(roomMsg.room.selfId, ctx.ids.g1, "same seat identity");
  assert.strictEqual(roomMsg.room.media.audio, "sub", "recovery restores the intended media version");
  // unlocking re-opens the door
  await send(hub, host, { t: "settings", locked: false });
  assert.strictEqual(of(take(host), "settings")[0].locked, false);
  const stranger2 = connect(env, "Stranger2");
  await send(hub, stranger2, { t: "join", name: "S2", code: ctx.code });
  assert.ok(take(stranger2).find((x) => x.t === "room"), "unlocked room accepts fresh joins");
});

test("intermission: pauses with a countdown, guests blocked, auto-resumes on the alarm", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, host, { t: "playback", action: "play", position: 100, sessionVersion: 1 });
  take(host); take(g1);
  advance(ctx, 60_000);
  await send(hub, host, { t: "intermission", op: "start", seconds: 120 });
  const evs = take(g1);
  const start = of(evs, "intermission")[0];
  assert.strictEqual(start.op, "start");
  assert.strictEqual(start.seconds, 120);
  assert.strictEqual(start.by, "Host");
  const pause = of(evs, "playback")[0];
  assert.ok(pause && pause.action === "pause" && pause.actor === "Host (break)");
  assert.strictEqual(ctx.snap().playback.status, "paused", "the break pauses the room");
  const until = ctx.room().intermission.until;
  assert.ok(until > clock.t, "late joiners see the break countdown");
  assert.strictEqual(ctx.snap().intermission.until, until);
  // a participant cannot start/end breaks (policy permission)
  await send(hub, g1, { t: "intermission", op: "end" });
  const err = take(g1).find((x) => x.t === "error");
  assert.ok(err && err.error === "unauthorized" && err.for === "intermission");
  // the alarm is armed at the countdown deadline once it is close
  clock.t = until - 5000;
  await send(hub, host, { t: "heartbeat", clientTs: 0 });
  await send(hub, g1, { t: "heartbeat", clientTs: 0 });
  await hub.alarm();
  assert.strictEqual(env.alarms.at(-1), until, "armAlarm targets the intermission expiry");
  // ...and the break ends on time even if nobody presses anything
  take(host); take(g1);
  clock.t = until + 1000;
  await send(hub, host, { t: "heartbeat", clientTs: 0 });
  await send(hub, g1, { t: "heartbeat", clientTs: 0 });
  await hub.alarm();
  const evs2 = take(g1);
  const playEv = of(evs2, "playback")[0];
  assert.ok(playEv && playEv.action === "play" && playEv.actor === "Party");
  assert.strictEqual(playEv.state.updatedAt, clock.t + 500, "resume carries the tiny shared gate");
  assert.strictEqual(of(evs2, "intermission")[0].by, "timeout", "auto-resume is honest about the reason");
  assert.strictEqual(ctx.snap().playback.status, "playing", "a forgotten break can never freeze the room");
  assert.strictEqual(ctx.snap().intermission, null);
  take(host); // drain host's copy of the auto-resume broadcast
  // seconds clamp to the 30..900 bounds
  await send(hub, host, { t: "intermission", op: "start", seconds: 5 });
  assert.strictEqual(of(take(host), "intermission")[0].seconds, 30, "seconds clamped to the floor");
  await send(hub, host, { t: "intermission", op: "start", seconds: 5000 });
  assert.strictEqual(of(take(host), "intermission")[0].seconds, 900, "seconds clamped to the ceiling");
  // host can end early
  await send(hub, host, { t: "intermission", op: "end" });
  const evs3 = take(host);
  const early = of(evs3, "intermission")[0];
  assert.ok(early && early.op === "end" && early.by === "Host");
  const resume = of(evs3, "playback")[0];
  assert.ok(resume && resume.action === "play" && resume.actor === "Host (break over)");
  assert.strictEqual(resume.state.updatedAt, clock.t + 500);
});

test("chat carries replyTo/spoiler/pos (validated, additive)", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const { hub, host, g1 } = { ...ctx.people, ...ctx };

  await send(hub, g1, { t: "chat", text: "that twist!", pos: 1250.9, spoiler: true });
  const msg = of(take(host), "chat")[0].msg;
  assert.strictEqual(msg.text, "that twist!");
  assert.strictEqual(msg.pos, 1250, "scene position recorded (floored)");
  assert.strictEqual(msg.spoiler, true);
  take(g1); // drain g1's copy of its own broadcast
  await send(hub, host, { t: "chat", text: "ikr", replyTo: msg.id });
  const reply = of(take(g1), "chat")[0].msg;
  assert.strictEqual(reply.replyTo, msg.id);
  take(host); // drain host's copy of its own broadcast
  // out-of-range/unknown values are dropped, never invented
  await send(hub, g1, { t: "chat", text: "ok", replyTo: "x".repeat(30), spoiler: "yes", pos: -5 });
  const plain = of(take(host), "chat")[0].msg;
  assert.strictEqual(plain.replyTo, undefined, "an over-long replyTo is dropped");
  assert.strictEqual(plain.spoiler, undefined, "spoiler must be exactly true");
  assert.strictEqual(plain.pos, undefined, "an invalid position is dropped");
  // the social fields persist in the room history
  const hist = ctx.snap().chat;
  assert.strictEqual(hist[0].spoiler, true);
  assert.strictEqual(hist[1].replyTo, msg.id);
  // muted users still cannot chat (unchanged gate)
  await send(hub, host, { t: "mute", targetId: ctx.ids.g1, muted: true });
  take(g1);
  await send(hub, g1, { t: "chat", text: "muted" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "unauthorized");
});

test("reactions broadcast with message identity; muted blocked; over-limit dropped silently", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 3);
  const { hub, host, g1, g2 } = { ...ctx.people, ...ctx };

  await send(hub, g1, { t: "react", emoji: "laugh", msgId: "m1" });
  const ev = of(take(host), "react")[0];
  assert.strictEqual(ev.emoji, "laugh");
  assert.strictEqual(ev.name, "G1");
  assert.strictEqual(ev.fromId, ctx.ids.g1);
  assert.strictEqual(ev.msgId, "m1");
  assert.ok(typeof ev.ts === "number");
  // burst reactions carry no msgId (null on the wire)
  await send(hub, g1, { t: "react", emoji: "love" });
  assert.strictEqual(of(take(host), "react")[0].msgId, null);
  // unknown emoji rejected; a msgId variant requires REACTIONS membership
  await send(hub, g1, { t: "react", emoji: "bogus" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "bad_message");
  await send(hub, g1, { t: "react", emoji: "bogus", msgId: "m2" });
  assert.strictEqual(take(g1).find((x) => x.t === "error").error, "bad_message", "a msgId variant still requires a known emoji");
  // muted users cannot react
  await send(hub, host, { t: "mute", targetId: ctx.ids.g2, muted: true });
  take(g2);
  await send(hub, g2, { t: "react", emoji: "love" });
  const err = take(g2).find((x) => x.t === "error");
  assert.ok(err && err.error === "unauthorized" && err.for === "react");
  // reactions share the chat limiter: over budget is dropped SILENTLY
  take(host); take(g1); take(g2);
  for (let i = 0; i < CHAT_RATE_MAX + 2; i++) await send(hub, host, { t: "react", emoji: "applause" });
  const evs = take(host);
  assert.strictEqual(of(evs, "react").length, CHAT_RATE_MAX, "the burst budget passes");
  assert.strictEqual(of(evs, "error").length, 0, "over-limit reactions drop silently");
});

test("snapshot carries the v2.1 layer (media/cohosts/perms/locked/queue/requests/intermission + roles)", async () => {
  const env = mkEnv();
  const ctx = await makeParty(env, 2);
  const snap = ctx.snap();
  assert.strictEqual(snap.media, null);
  assert.deepStrictEqual(snap.cohosts, []);
  assert.deepStrictEqual(snap.perms, { play: true, seek: true, episode: false, queue: false, moderate: false, policy: false });
  assert.strictEqual(snap.locked, false);
  assert.deepStrictEqual(snap.queue, []);
  assert.deepStrictEqual(snap.requests, []);
  assert.strictEqual(snap.intermission, null);
  assert.strictEqual(snap.participants.find((p) => p.id === ctx.ids.host).role, "host");
  assert.strictEqual(snap.participants.find((p) => p.id === ctx.ids.g1).role, "participant");
  // the additive fields ride the SAME snapshot shape as the v2.0 layer
  assert.strictEqual(snap.waitMode, "move");
  assert.strictEqual(snap.gate, null);
  assert.strictEqual(snap.hold, null);
  assert.strictEqual(snap.playback.status, "waiting");
});

await runAll();
