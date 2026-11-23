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
     - the `end` broadcast reaching the host too */

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
      if (e && e.stack) console.log(String(e.stack).split("\n").slice(1, 4).join("\n"));
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

await runAll();
