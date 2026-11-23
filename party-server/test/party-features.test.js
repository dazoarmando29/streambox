"use strict";
/* StreamBox Watch Party - media version + party features suite.
   Reproduces the reported DUB/SUB mismatch bug at the protocol level and
   locks the authoritative media-configuration contract, then covers the
   social layer: roles/permissions, playback requests, chat/reactions,
   shared queue, secure invites, room recovery, group intermission.

   The provider-side variant resolution (AniPM lang segment, VidBolt panel
   options) is client behavior - the browser E2E (tools/party-e2e.js) covers
   the propagation pipeline; this suite proves the SERVER contract that makes
   "everyone loads the host's intended version" enforceable. */

const test = require("node:test");
const assert = require("node:assert");
const { RoomManager } = require("../rooms");
const { LIMITS, expectedPosition } = require("../protocol");

const T0 = 1_700_000_000_000;

function mkManager(ttl = {}) {
  const clock = { t: T0 };
  const rm = new RoomManager({ now: () => clock.t, ...ttl });
  return { rm, clock };
}
function person(name) {
  return { id: "p-" + name, name, token: "tok-" + name, conn: { tag: name } };
}
function makeParty({ members = 2, allowControl = false, ttl } = {}) {
  const { rm, clock } = mkManager(ttl);
  const host = person("host");
  const created = rm.create(host, { roomName: "Version party", allowControl, maxParticipants: 16 });
  const rid = created.room.roomId;
  const code = created.room.code;
  const people = { host };
  for (let i = 1; i < members; i++) {
    const p = person("g" + i);
    const r = rm.join(p, { code });
    assert.ok(r.room, "guest g" + i + " must join");
    people["g" + i] = p;
  }
  const ids = {};
  for (const [k, p] of Object.entries(people)) ids[k] = p.id;
  return { rm, clock, rid, code, people, ids, host };
}
const of = (events, t) => (events || []).filter((e) => e.t === t);

/* ---------- 3.2/3.3: the authoritative media version ---------- */

test("host's intended media version lands in room state and reaches every joiner (17/18/19/20)", () => {
  const ctx = makeParty({ members: 1 });
  // host watches Japanese audio + English subs on AniPM (provider identifiers)
  const r = ctx.rm.media(ctx.rid, ctx.ids.host, {
    mirror: "anipm", variant: "mal:5114/ep:5", audio: "sub", subs: "en", subsOn: true,
  });
  assert.ok(of(r.events, "media")[0], "media change broadcasts");
  // the GUEST joins afterwards and must see the intended version in its snapshot
  const g = person("guest");
  const j = ctx.rm.join(g, { code: ctx.code });
  assert.strictEqual(j.room.media.mirror, "anipm");
  assert.strictEqual(j.room.media.audio, "sub", "intended audio reaches the joiner");
  assert.strictEqual(j.room.media.subs, "en");
  assert.strictEqual(j.room.media.subsOn, true);
  // and the reverse: host switches to English dub - everyone follows
  const r2 = ctx.rm.media(ctx.rid, ctx.ids.host, { mirror: "anipm", variant: "mal:5114/ep:5", audio: "dub", subs: "off", subsOn: false });
  assert.strictEqual(of(r2.events, "media")[0].media.audio, "dub");
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), g.id).media.audio, "dub");
});

test("a guest can never set the party's media version; personal choices are not broadcast (28)", () => {
  const ctx = makeParty({ members: 2, allowControl: true });
  const r = ctx.rm.media(ctx.rid, ctx.ids.g1, { mirror: "anipm", audio: "dub" });
  assert.strictEqual(r.error, "unauthorized", "only host/co-host define the party version");
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).media, null);
});

test("episode changes preserve the intended version (22): it survives session bumps", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.media(ctx.rid, ctx.ids.host, { mirror: "anipm", audio: "sub", subs: "en", subsOn: true });
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 5 });
  let snap = ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1);
  assert.strictEqual(snap.media.audio, "sub", "E5 config intact");
  ctx.rm.content(ctx.rid, ctx.ids.host, { type: "tv", tmdbId: 42, season: 1, episode: 6 });
  snap = ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1);
  assert.strictEqual(snap.content.episode, 6);
  assert.strictEqual(snap.media.audio, "sub", "E6 keeps the intended audio - nobody defaults to dub");
});

test("source ('server') switching never rewrites the media version (23)", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.media(ctx.rid, ctx.ids.host, { mirror: "vidbolt", audio: "Japanese", subs: "English" });
  const r = ctx.rm.source(ctx.rid, ctx.ids.host, "vidbolt");
  assert.ok(!r.error);
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).media.audio, "Japanese");
  // a host media update is explicit; nothing else touches it
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play" });
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).media.audio, "Japanese");
});

test("provider variant identifiers pass through verbatim; no guessing, no media URLs (26)", () => {
  const ctx = makeParty({ members: 2 });
  // VidBolt panel identifiers (label + value) are preserved exactly
  const r = ctx.rm.media(ctx.rid, ctx.ids.host, {
    mirror: "vidbolt", variant: "audio:3|subs:1", audio: "Tagalog", subs: "English",
  });
  const m = of(r.events, "media")[0].media;
  assert.strictEqual(m.audio, "Tagalog");
  assert.strictEqual(m.variant, "audio:3|subs:1");
  // garbage collapses to null instead of inventing state
  assert.strictEqual(ctx.rm.media(ctx.rid, ctx.ids.host, {}).error, "bad_message");
  // long junk is capped
  const r3 = ctx.rm.media(ctx.rid, ctx.ids.host, { audio: "x".repeat(200) });
  assert.ok(of(r3.events, "media")[0].media.audio.length <= LIMITS.MEDIA_FIELDS);
});

/* ---------- Feature 4: host + co-host roles, server-enforced (39/40) ---------- */

test("co-host promotion grants control; demotion removes it - enforced server-side", () => {
  const ctx = makeParty({ members: 3 });
  // a plain guest cannot control (allowControl off)
  assert.strictEqual(ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "play" }).error, "unauthorized");
  // host promotes g1 to co-host
  const r = ctx.rm.role(ctx.rid, ctx.ids.host, ctx.ids.g1, "cohost");
  assert.ok(of(r.events, "role")[0].role === "cohost");
  // co-host can drive playback, change episodes and set media version
  assert.ok(!ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "play" }).error);
  assert.ok(!ctx.rm.content(ctx.rid, ctx.ids.g1, { type: "tv", tmdbId: 42, season: 1, episode: 2 }).error);
  assert.ok(!ctx.rm.media(ctx.rid, ctx.ids.g1, { mirror: "anipm", audio: "sub" }).error);
  // co-host can moderate (kick/mute) but never the host or another co-host
  ctx.rm.role(ctx.rid, ctx.ids.host, ctx.ids.g2, "cohost");
  assert.strictEqual(ctx.rm.kick(ctx.rid, ctx.ids.g1, ctx.ids.g2).error, "unauthorized");
  assert.ok(!ctx.rm.kick(ctx.rid, ctx.ids.host, ctx.ids.g2).error);
  // demote: control is gone
  ctx.rm.role(ctx.rid, ctx.ids.host, ctx.ids.g1, "participant");
  assert.strictEqual(ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "play" }).error, "unauthorized");
  // only the host manages roles
  assert.strictEqual(ctx.rm.role(ctx.rid, ctx.ids.g1, ctx.ids.g2, "cohost").error, "unauthorized");
});

test("granular guest permissions are enforced per action class", () => {
  const ctx = makeParty({ members: 2, allowControl: true });
  // defaults: play/seek allowed for guests, episode changes are not
  assert.ok(!ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "play" }).error);
  assert.strictEqual(ctx.rm.content(ctx.rid, ctx.ids.g1, { type: "tv", tmdbId: 42, season: 1, episode: 3 }).error, "unauthorized");
  // host grants episode rights + disables seeking
  ctx.rm.settings(ctx.rid, ctx.ids.host, { perms: { episode: true, seek: false } });
  assert.ok(!ctx.rm.content(ctx.rid, ctx.ids.g1, { type: "tv", tmdbId: 42, season: 1, episode: 3 }).error);
  assert.strictEqual(ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "seek", position: 90 }).error, "unauthorized");
  assert.ok(!ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "pause" }).error, "play/pause class still allowed");
  // guests can never edit the permission matrix
  assert.strictEqual((ctx.rm.settings(ctx.rid, ctx.ids.g1, { perms: { episode: false } })).error, "unauthorized");
});

/* ---------- Feature 5: playback requests (41) ---------- */

test("guest requests pause; host approval pauses the room server-side", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play" });
  ctx.clock.t += 30_000;
  const req = ctx.rm.request(ctx.rid, ctx.ids.g1, { action: "pause", note: "phone call" });
  const q = of(req.events, "request")[0].request;
  assert.strictEqual(q.from, "g1");
  // the requester can NEVER pause directly (allowControl off)
  assert.strictEqual(ctx.rm.playback(ctx.rid, ctx.ids.g1, { action: "pause" }).error, "unauthorized");
  const rep = ctx.rm.requestReply(ctx.rid, ctx.ids.host, { requestId: q.id, approved: true });
  const ev = of(rep.events, "request_reply")[0];
  assert.strictEqual(ev.approved, true);
  assert.strictEqual(ev.approverId, ctx.ids.host);
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).playback.status, "paused", "approval executed by the SERVER");
  // decline path: no state change
  const req2 = ctx.rm.request(ctx.rid, ctx.ids.g1, { action: "rewind", position: 120 });
  const q2 = of(req2.events, "request")[0].request;
  const before = ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).playback.position;
  ctx.rm.requestReply(ctx.rid, ctx.ids.host, { requestId: q2.id, approved: false });
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).playback.position, before);
});

test("skip-intro request executes as an authorized seek; episode requests route to the approver's client", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", position: 60 });
  const req = ctx.rm.request(ctx.rid, ctx.ids.g1, { action: "skip_intro", position: 95 });
  const q = of(req.events, "request")[0].request;
  const rep = ctx.rm.requestReply(ctx.rid, ctx.ids.host, { requestId: q.id, approved: true });
  assert.ok(of(rep.events, "playback")[0], "room seeked past the intro");
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).playback.position, 95);
  // next-episode approvals are broadcast with the APPROVER id (their client
  // executes the content change - the server cannot know episode data)
  const req2 = ctx.rm.request(ctx.rid, ctx.ids.g1, { action: "next_episode" });
  const q2 = of(req2.events, "request")[0].request;
  const rep2 = ctx.rm.requestReply(ctx.rid, ctx.ids.host, { requestId: q2.id, approved: true });
  const ev2 = of(rep2.events, "request_reply")[0];
  assert.strictEqual(ev2.approverId, ctx.ids.host);
  assert.strictEqual(ev2.request.action, "next_episode");
  assert.strictEqual(of(rep2.events, "playback").length, 0, "no fake timeline action for an episode move");
  // only host/co-host may approve
  const req3 = ctx.rm.request(ctx.rid, ctx.ids.g1, { action: "pause" });
  const q3 = of(req3.events, "request")[0].request;
  assert.strictEqual(ctx.rm.requestReply(ctx.rid, ctx.ids.g1, { requestId: q3.id, approved: true }).error, "unauthorized");
});

/* ---------- Feature 7: chat upgrades (42) ---------- */

test("chat carries replies, spoiler flags and scene positions (spoiler-safe joining input)", () => {
  const ctx = makeParty({ members: 2 });
  const first = ctx.rm.chat(ctx.rid, ctx.ids.g1, { text: "that twist!", pos: 1250, spoiler: true });
  const msg = of(first.events, "chat")[0].msg;
  assert.strictEqual(msg.pos, 1250, "scene position recorded");
  assert.strictEqual(msg.spoiler, true);
  const reply = ctx.rm.chat(ctx.rid, ctx.ids.host, { text: "ikr", replyTo: msg.id });
  assert.strictEqual(of(reply.events, "chat")[0].msg.replyTo, msg.id);
  // muted users still cannot chat
  ctx.rm.mute(ctx.rid, ctx.ids.host, ctx.ids.g1, true);
  assert.strictEqual(ctx.rm.chat(ctx.rid, ctx.ids.g1, { text: "muted" }).error, "unauthorized");
});

/* ---------- Feature 6: reactions (43) ---------- */

test("reactions broadcast to the room and muted users cannot react", () => {
  const ctx = makeParty({ members: 3 });
  const r = ctx.rm.react(ctx.rid, ctx.ids.g1, { emoji: "laugh", msgId: "m1" });
  const ev = of(r.events, "react")[0];
  assert.strictEqual(ev.emoji, "laugh");
  assert.strictEqual(ev.name, "g1");
  assert.strictEqual(ev.msgId, "m1");
  // unknown emoji rejected at validation; muted users blocked here
  ctx.rm.mute(ctx.rid, ctx.ids.host, ctx.ids.g2, true);
  assert.strictEqual(ctx.rm.react(ctx.rid, ctx.ids.g2, { emoji: "love" }).error, "unauthorized");
});

/* ---------- Feature 9: shared queue + voting (44/45) ---------- */

test("queue: suggest + vote + host approval becomes the canonical episode (server-side)", () => {
  const ctx = makeParty({ members: 3 });
  const add = ctx.rm.queue(ctx.rid, ctx.ids.g1, {
    op: "add", item: { type: "tv", tmdbId: 42, season: 1, episode: 8, title: "Next up" },
  });
  const item = of(add.events, "queue")[0].item;
  assert.strictEqual(item.votes, 1, "suggester auto-votes");
  const vote = ctx.rm.queue(ctx.rid, ctx.ids.g2, { op: "vote", itemId: item.id });
  assert.strictEqual(of(vote.events, "queue")[0].item.votes, 2);
  // guest approval is refused (canonical playback needs episode authority)
  assert.strictEqual(ctx.rm.queue(ctx.rid, ctx.ids.g1, { op: "approve", itemId: item.id }).error, "unauthorized");
  // host approval executes the content change SERVER-side
  const app = ctx.rm.queue(ctx.rid, ctx.ids.host, { op: "approve", itemId: item.id });
  const contentEv = of(app.events, "content")[0];
  assert.ok(contentEv, "approval produced the content transition");
  assert.strictEqual(contentEv.content.episode, 8);
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).queue.length, 0, "approved item leaves the queue");
  // removal rules: creator can remove own; others need perms.queue
  const add2 = ctx.rm.queue(ctx.rid, ctx.ids.g1, { op: "add", item: { type: "movie", tmdbId: 7, title: "Film" } });
  const item2 = of(add2.events, "queue")[0].item;
  assert.strictEqual(ctx.rm.queue(ctx.rid, ctx.ids.g2, { op: "remove", itemId: item2.id }).error, "unauthorized");
  assert.ok(!ctx.rm.queue(ctx.rid, ctx.ids.g1, { op: "remove", itemId: item2.id }).error);
});

/* ---------- Feature 15: secure invites (47) ---------- */

test("locked rooms reject new joins (invites expire) but returning members reattach", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.media(ctx.rid, ctx.ids.host, { mirror: "anipm", audio: "sub" });
  const s = ctx.rm.settings(ctx.rid, ctx.ids.host, { locked: true });
  assert.strictEqual(of(s.events, "settings")[0].locked, true);
  const stranger = person("stranger");
  assert.strictEqual(ctx.rm.join(stranger, { code: ctx.code }).error, "closed", "door is locked");
  // but a returning participant keeps their seat via the resume token
  ctx.rm.disconnect(ctx.rid, ctx.ids.g1, ctx.people.g1.conn);
  const back = person("g1-back");
  const r = ctx.rm.join(back, { code: ctx.code, token: "tok-g1" });
  assert.strictEqual(r.reattached, ctx.ids.g1);
  assert.strictEqual(r.room.media.audio, "sub", "recovery restores the intended media version");
  // unlocking re-opens the door
  ctx.rm.settings(ctx.rid, ctx.ids.host, { locked: false });
  assert.ok(ctx.rm.join(person("stranger2"), { code: ctx.code }).room);
});

/* ---------- Feature 14: room recovery (46) ---------- */

test("room recovery restores identity, role and media version after a long background (46)", () => {
  const ctx = makeParty({ members: 3, ttl: { staleMs: 100_000, participantTtl: 45_000, seatMemory: 300_000 } });
  ctx.rm.role(ctx.rid, ctx.ids.host, ctx.ids.g2, "cohost");
  ctx.rm.media(ctx.rid, ctx.ids.host, { mirror: "vidbolt", audio: "Japanese", subs: "English", subsOn: true });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play" });
  // g2 backgrounds long enough for the seat to expire
  for (let i = 0; i < 16; i++) {
    ctx.clock.t += 10_000;
    ctx.rm.markSeen(ctx.rid, ctx.ids.host, ctx.people.host.conn);
    ctx.rm.sweep();
  }
  const back = person("cohost-back");
  const r = ctx.rm.join(back, { code: ctx.code, token: "tok-g2" });
  assert.strictEqual(r.reattached, ctx.ids.g2, "same identity");
  assert.strictEqual(r.room.media.audio, "Japanese", "intended media version restored");
  // co-host role still enforced for the restored identity
  assert.ok(!ctx.rm.playback(ctx.rid, ctx.ids.g2, { action: "pause" }).error, "co-host control survives the recovery");
});

/* ---------- Feature 12: group intermission ---------- */

test("intermission pauses the party with a countdown and auto-resumes (never stuck)", () => {
  const ctx = makeParty({ members: 3 });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play" });
  ctx.clock.t += 60_000;
  const r = ctx.rm.intermission(ctx.rid, ctx.ids.host, { op: "start", seconds: 120 });
  const ev = of(r.events, "intermission")[0];
  assert.strictEqual(ev.op, "start");
  assert.strictEqual(ev.seconds, 120);
  const snap = ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1);
  assert.strictEqual(snap.playback.status, "paused", "the break pauses the room");
  assert.ok(snap.intermission && snap.intermission.until > ctx.clock.t, "late joiners see the break countdown");
  // a participant cannot start/end breaks (policy permission)
  assert.strictEqual(ctx.rm.intermission(ctx.rid, ctx.ids.g1, { op: "end" }).error, "unauthorized");
  // the break ends on time even if nobody presses anything
  ctx.clock.t += 121_000;
  const batches = ctx.rm.tick();
  assert.strictEqual(ctx.rm.snapshot(ctx.rm.rooms.get(ctx.rid), ctx.ids.g1).playback.status, "playing", "auto-resume");
  const endEv = batches.flatMap((b) => b.events).find((e) => e.t === "intermission");
  assert.ok(endEv && endEv.op === "end");
  // host can also end early
  ctx.rm.intermission(ctx.rid, ctx.ids.host, { op: "start", seconds: 300 });
  const early = ctx.rm.intermission(ctx.rid, ctx.ids.host, { op: "end" });
  assert.ok(of(early.events, "intermission")[0].op === "end");
});

/* ---------- Feature 11: party presets ride settings (verified shape) ---------- */

test("party presets map onto real server settings (casual/strict/anime/movie)", () => {
  const ctx = makeParty({ members: 2 });
  // STRICT SYNC preset = wait-for-everyone + visible readiness
  const strict = ctx.rm.settings(ctx.rid, ctx.ids.host, { waitMode: "everyone", holdTimeoutMs: 30000 });
  assert.strictEqual(of(strict.events, "settings")[0].waitMode, "everyone");
  assert.strictEqual(of(strict.events, "settings")[0].holdTimeoutMs, 30000);
  // CASUAL preset = keep party moving + guest playback control
  const casual = ctx.rm.settings(ctx.rid, ctx.ids.host, { waitMode: "move", allowControl: true });
  assert.strictEqual(of(casual.events, "settings")[0].waitMode, "move");
  assert.strictEqual(of(casual.events, "settings")[0].allowControl, true);
});

/* ---------- 2.2 shared math still authoritative after the social layer ---------- */

test("social actions never disturb the timeline; expectedPosition stays the single formula", () => {
  const ctx = makeParty({ members: 2 });
  ctx.rm.playback(ctx.rid, ctx.ids.host, { action: "play", position: 100 });
  ctx.rm.media(ctx.rid, ctx.ids.host, { mirror: "anipm", audio: "sub" });
  ctx.rm.react(ctx.rid, ctx.ids.g1, { emoji: "love" });
  ctx.rm.chat(ctx.rid, ctx.ids.g1, { text: "hi", pos: 100 });
  ctx.rm.queue(ctx.rid, ctx.ids.g1, { op: "add", item: { type: "movie", tmdbId: 1, title: "x" } });
  ctx.clock.t += 30_000;
  const pb = ctx.rm.rooms.get(ctx.rid).playback;
  assert.strictEqual(expectedPosition(pb, ctx.clock.t), 130, "timeline untouched by social traffic");
});
