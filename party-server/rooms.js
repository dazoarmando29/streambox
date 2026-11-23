"use strict";
/* StreamBox Watch Party room state machine. The server is authoritative:
   every command is validated and permission-checked HERE (clients hide
   controls, the server enforces). No media bytes, no credentials — only
   playback *state* (ids, positions, statuses) ever lives in a room. */

const crypto = require("crypto");
const {
  LIMITS, CODE_ALPHABET, CODE_LEN, CODE_RE,
  cleanText, cleanName, cleanMedia, expectedPosition, PSTATE_BLOCKING, WAIT_MODES,
} = require("./protocol");

const START_DELAY_MS = LIMITS.START_DELAY_MS; // fallback gate: episode changes start together on the server clock
let idCounter = 0;
function makeId(prefix) {
  idCounter += 1;
  return prefix + crypto.randomBytes(9).toString("base64url") + idCounter.toString(36);
}
function makeRoomId() { return makeId("r"); }
function makeCode(existing) {
  for (let tries = 0; tries < 50; tries++) {
    let code = "";
    const bytes = crypto.randomBytes(CODE_LEN);
    for (let i = 0; i < CODE_LEN; i++) code += CODE_ALPHABET[bytes[i] % CODE_ALPHABET.length];
    if (!existing.has(code)) return code;
  }
  throw new Error("code_space_exhausted");
}
function hashPassword(password, salt) {
  return crypto.createHash("sha256").update(salt + "\u0000" + password).digest("hex");
}

/* Small fixed-window rate limiter (per key). */
class RateLimiter {
  constructor(windowMs, max) { this.windowMs = windowMs; this.max = max; this.hits = new Map(); }
  allow(key, now = Date.now()) {
    let h = this.hits.get(key);
    if (!h || now - h.start >= this.windowMs) { h = { start: now, n: 0 }; this.hits.set(key, h); }
    if (this.hits.size > 5000) this.sweep(now);
    h.n += 1;
    return h.n <= this.max;
  }
  sweep(now = Date.now()) {
    for (const [k, h] of this.hits) if (now - h.start >= this.windowMs) this.hits.delete(k);
  }
  reset(key) { this.hits.delete(key); }
}

class RoomManager {
  constructor(opts = {}) {
    this.rooms = new Map();       // roomId -> room
    this.byCode = new Map();      // code -> roomId
    this.codeAttempts = new RateLimiter(LIMITS.CODE_ATTEMPT_WINDOW_MS, LIMITS.CODE_ATTEMPT_MAX);
    // injectable clock: deterministic tests drive time instead of sleeping
    this.now = typeof opts.now === "function" ? opts.now : Date.now;
    this.ttl = {
      hostGrace: opts.hostGrace != null ? opts.hostGrace : LIMITS.HOST_GRACE_MS,
      participantTtl: opts.participantTtl != null ? opts.participantTtl : LIMITS.PARTICIPANT_TTL_MS,
      // silence this long = gone, even if the socket never closed (half-open
      // TCP after a network blip is the norm on phones)
      staleMs: opts.staleMs != null ? opts.staleMs : LIMITS.PARTICIPANT_STALE_MS,
      // how long a dropped seat stays restorable by its resume token - and how
      // long the ROOM outlives everyone to give them that return window
      seatMemory: opts.seatMemory != null ? opts.seatMemory : 5 * 60 * 1000,
      emptyRoomTtl: opts.emptyRoomTtl != null ? opts.emptyRoomTtl : LIMITS.EMPTY_ROOM_TTL_MS,
      maxRoomAge: opts.maxRoomAge != null ? opts.maxRoomAge : LIMITS.MAX_ROOM_AGE_MS,
    };
  }

  /* ---- lifecycle ---- */

  create(host, o = {}) {
    const now = this.now();
    const room = {
      id: makeRoomId(),
      code: makeCode(this.byCode),
      name: o.roomName || `${host.name}'s party`,
      passwordHash: o.password ? hashPassword(o.password, crypto.randomBytes(8).toString("hex")) : "",
      passwordSalt: "",
      maxParticipants: o.maxParticipants || LIMITS.DEFAULT_PARTICIPANTS,
      allowControl: o.allowControl === true,
      chatEnabled: o.chatEnabled !== false,
      hostId: host.id,
      content: null,
      source: "", // room-wide mirror ("server") - follows the last controller switch
      playback: { status: "waiting", position: 0, rate: 1, updatedAt: now, sessionVersion: 1, seq: 0 },
      // buffering policy: "move" (default) keeps the party moving when one
      // viewer stalls; "everyone" (host-enabled) holds shared playback until
      // required participants recover - bounded by holdTimeoutMs + host override
      waitMode: "move",
      readyTimeoutMs: LIMITS.READY_TIMEOUT_DEFAULT_MS,
      holdTimeoutMs: LIMITS.HOLD_TIMEOUT_DEFAULT_MS,
      gate: null,  // coordinated episode transition (see content())
      hold: null,  // wait-for-everyone buffering hold (see _maybeCreateHold())
      // AUTHORITATIVE media version (dub/sub/audio/subtitle/variant): what
      // every participant loads FIRST. Local defaults never win over this.
      media: null,
      // roles + granular guest permissions (server-enforced)
      cohosts: new Set(),
      perms: { play: true, seek: true, episode: false, queue: false, moderate: false, policy: false },
      locked: false, // locked rooms accept no NEW joins (invites stop working)
      // social layer
      requests: new Map(), // requestId -> { id, fromId, from, action, position, note, at }
      requestSeq: 0,
      queue: [],           // { id, type, tmdbId, season, episode, title, by, votes: Set }
      queueSeq: 0,
      intermission: null,  // { until, seconds, by }
      participants: new Map(),
      chat: [],
      chatSeq: 0,
      createdAt: now,
      lastActivityAt: now,
      hostDisconnectedAt: 0,
      ended: false,
    };
    if (o.password) {
      room.passwordSalt = crypto.randomBytes(8).toString("hex");
      room.passwordHash = hashPassword(o.password, room.passwordSalt);
    }
    room.participants.set(host.id, this._mkParticipant(host, now, true));
    this.rooms.set(room.id, room);
    this.byCode.set(room.code, room.id);
    return { room: this.snapshot(room, host.id) };
  }

  join(p, { code, roomId, password, token, remoteIp }) {
    const now = this.now();
    // guessing guard BEFORE lookups (only for short-code joins)
    if (code && !this.codeAttempts.allow(remoteIp || p.id, now)) {
      return { error: "rate_limited" };
    }
    let room = null;
    if (code) {
      if (!CODE_RE.test(code)) return { error: "not_found" };
      room = this.rooms.get(this.byCode.get(code) || "");
    } else if (roomId) {
      room = this.rooms.get(roomId);
    }
    if (!room || room.ended) return { error: "not_found" };
    // reconnect identity: a returning participant reattaches to its seat.
    // The token IS the identity - a half-open ghost socket (no close ever
    // fires after a network blip) must NEVER force the same person onto a
    // fresh seat: that minted duplicate "you"s per reconnect, filled the room
    // against maxParticipants (real members bounced with `full`) and left the
    // host star on a ghost (controls answered `unauthorized`). Same token =
    // same seat: REATTACH and replace the connection.
    if (token) {
      for (const [id, part] of room.participants) {
        if (part.token === token) {
          const dropConn = part.conn && part.conn !== p.conn ? part.conn : null;
          part.connected = true;
          part.lastSeen = now;
          part.conn = p.conn;
          p.id = id; // caller adopts the original id
          if (id === room.hostId) room.hostDisconnectedAt = 0;
          room.lastActivityAt = now;
          // the SEAT's token is the client's identity across reconnects -
          // never the per-connection token (that would rotate on every
          // reconnect and strand the next one on a fresh seat)
          return { room: this.snapshot(room, id), reattached: id, token: part.token, replaced: !!dropConn, dropConn };
        }
      }
      // seat already expired (long background): a remembered seat restores
      // identity AND the host role if the host is who returns. Kicked
      // participants are NEVER restorable (a kick is permanent).
      const saved = room.recentSeats && room.recentSeats.get(token);
      if (saved && !saved.kicked) {
        room.recentSeats.delete(token);
        const part = this._mkParticipant(p, now, false);
        part.id = saved.id;
        part.token = token;
        part.name = saved.name || part.name;
        room.participants.set(saved.id, part);
        p.id = saved.id;
        const events = [];
        if (saved.wasHost) {
          // reclaiming the host seat is a host change when someone else was
          // promoted meanwhile - every client must hear it or their UI shows
          // a stale host (and the returning host's controls get rejected)
          if (room.hostId !== saved.id) {
            events.push({ t: "host_changed", room: room.id, hostId: saved.id, hostName: part.name });
          }
          room.hostId = saved.id;
          room.hostDisconnectedAt = 0;
        }
        room.lastActivityAt = now;
        return { room: this.snapshot(room, saved.id), reattached: saved.id, token, events };
      }
    }
    if (room.passwordHash && hashPassword(password || "", room.passwordSalt) !== room.passwordHash) {
      return { error: "wrong_password" };
    }
    // locked rooms accept no NEW members (invites stop working) - returning
    // participants keep reattaching via their tokens above
    if (room.locked) return { error: "closed" };
    const connectedCount = [...room.participants.values()].filter((x) => x.connected).length;
    if (connectedCount >= room.maxParticipants) return { error: "full" };
    room.participants.set(p.id, this._mkParticipant(p, now, false));
    room.lastActivityAt = now;
    return { room: this.snapshot(room, p.id), joined: p.id, token: p.token };
  }

  leave(roomId, participantId) {
    const room = this.rooms.get(roomId);
    if (!room) return { events: [] };
    return this._removeParticipant(room, participantId, "left");
  }

  /* WS dropped (network blip, app backgrounded): keep the seat so the same
     resume token can reattach within the TTL. Voluntary `leave` removes. */
  disconnect(roomId, participantId, conn) {
    const room = this.rooms.get(roomId);
    if (!room) return { events: [] };
    const part = room.participants.get(participantId);
    if (!part) return { events: [] };
    // stale close: the seat already moved to a NEWER connection (reattach
    // replaced the ghost) - the old socket dying must never evict the live one
    if (conn && part.conn && part.conn !== conn) return { events: [] };
    part.connected = false;
    part.conn = null;
    part.lastSeen = this.now();
    part.pstate = "disconnected";
    if (room.hostId === participantId) room.hostDisconnectedAt = part.lastSeen;
    return {
      events: [{ t: "peer_joined", room: room.id, participant: { ...this._pub(part), host: part.id === room.hostId }, updated: true }],
    };
  }

  end(roomId, participantId) {
    const room = this.rooms.get(roomId);
    if (!room) return { error: "not_found" };
    if (room.hostId !== participantId) return { error: "unauthorized" };
    room.ended = true;
    // the room object stays until the next sweep so the broadcast below can
    // still reach every participant - deleting first silenced room_ended
    return { events: [{ t: "room_ended", roomId: room.id, reason: "host_ended" }] };
  }

  /* ---- commands (all permission-checked here) ---- */

  playback(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    const kind = msg.action === "seek" ? "seek" : msg.action === "rate" ? "rate" : "play";
    if (!this._canControl(room, part, kind)) return { error: "unauthorized" };
    // stale guard: a playback action stamped for a previous episode/session
    // must never affect the current one
    if (msg.sessionVersion != null && msg.sessionVersion !== room.playback.sessionVersion) {
      return { stale: true };
    }
    const now = this.now();
    this._sweepGate(room, now);
    const pb = room.playback;
    const cur = expectedPosition(pb, now);
    const events = [];
    switch (msg.action) {
      case "play":
        // host/controller override: a play always releases a wait hold and
        // ends a pending transition (nobody waits forever)
        if (room.hold) events.push(...this._releaseHold(room, now, "override"));
        if (room.gate && room.gate.phase === "loading") {
          events.push(...this._finalizeStart(room, now + 500, "host_override"));
        }
        pb.status = "playing";
        pb.position = msg.position != null ? msg.position : cur;
        part.pstate = "playing";
        break;
      case "pause":
        if (room.hold) events.push(...this._releaseHold(room, now, "override"));
        // pausing during a pending transition cancels the shared start: the
        // room stays paused at the gate position until someone plays
        if (room.gate && room.gate.phase !== "live") {
          room.gate.phase = "live";
          room.gate.startAt = null;
        }
        pb.status = "paused";
        pb.position = msg.position != null ? msg.position : cur;
        part.pstate = "paused";
        break;
      case "seek":
        if (msg.position == null) return { error: "bad_message" };
        // a seek during a pending transition is a host override of the gate too
        if (room.gate && room.gate.phase === "loading" && msg.position > 0) {
          events.push(...this._finalizeStart(room, now, "host_override"));
        }
        pb.position = msg.position;
        break;
      case "rate":
        if (msg.rate == null) return { error: "bad_message" };
        pb.position = cur;
        pb.rate = msg.rate;
        break;
      case "ended":
        // stale completion: an episode that already ended (or one from a
        // previous session) must NEVER advance the room twice
        if (pb.status === "ended") return { events: [] };
        if (room.gate && room.gate.phase !== "live") return { stale: true };
        pb.status = "ended";
        pb.position = cur;
        part.pstate = "paused";
        break;
      case "buffer":
        // participant-side buffering notice: the room timeline is untouched
        // (default policy) - but the state is tracked per participant and a
        // wait-for-everyone room holds here
        part.buffering = true;
        part.pstate = "buffering";
        part.lastSeen = now;
        events.push({ t: "peer_state", room: room.id, participantId, st: "buffering", buffering: true, position: part.position, ts: now });
        if (room.waitMode === "everyone" && pb.status === "playing") {
          events.push(...this._maybeCreateHold(room, participantId, now));
        }
        return { buffered: true, events };
      default:
        return { error: "bad_message" };
    }
    pb.updatedAt = now;
    pb.seq += 1;
    room.lastActivityAt = now;
    part.buffering = false;
    events.push({
      t: "playback",
      seq: pb.seq,
      sessionVersion: pb.sessionVersion,
      actor: part.name,
      action: msg.action,
      state: this._pbState(room, now),
    });
    events.push(...this._maybeReleaseHold(room, now));
    return { events };
  }

  content(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (!this._canControl(room, part, "episode")) return { error: "unauthorized" }; // host/cohost/permed
    const now = this.now();
    room.content = { type: msg.type, tmdbId: msg.tmdbId, season: msg.season, episode: msg.episode };
    // Coordinated episode-transition lifecycle:
    //   1. new content session (sessionVersion+1) - every earlier event is stale
    //   2. clients unload the old episode and start loading the new one,
    //      reporting player state (loading -> ready) per participant
    //   3. when the reported blockers are done - or the readiness timeout
    //      expires - the shared start is scheduled on the SERVER clock
    //   4. slow/late participants catch up to the room timeline once ready
    // The initial gate (updatedAt in the future) doubles as the fallback for
    // older clients that know nothing of readiness: they hold at 00:00 until
    // the gate exactly as before. The finalized gate is re-broadcast as a
    // second `content` event (same identity) which every client generation
    // folds into its existing gate logic (no reload - same content key).
    const timeout = Math.min(LIMITS.READY_TIMEOUT_MAX_MS,
      Math.max(LIMITS.READY_TIMEOUT_MIN_MS, room.readyTimeoutMs || LIMITS.READY_TIMEOUT_DEFAULT_MS));
    room.playback = {
      status: "playing", position: 0, rate: 1, updatedAt: now + timeout,
      sessionVersion: room.playback.sessionVersion + 1,
      seq: room.playback.seq + 1,
    };
    room.gate = {
      phase: "loading",
      sessionVersion: room.playback.sessionVersion,
      earliest: now + LIMITS.TRANSITION_MIN_MS,
      deadline: now + timeout,
      startAt: null,
      ready: new Set(),
    };
    room.hold = null; // an episode change cancels any buffering hold
    room.lastActivityAt = now;
    const events = [{
      t: "content",
      seq: room.playback.seq,
      sessionVersion: room.playback.sessionVersion,
      actor: part.name,
      content: room.content,
      state: this._pbState(room, now),
    }, this._scheduleEvent(room, now, "content")];
    // everyone still around implicitly reloads the new episode: their old
    // "ready"/"playing" was for the previous one. Until they report for THIS
    // sessionVersion they are loading (and only a session-stamped "loading" or
    // "buffering" report can hold back the shared start - a silent legacy
    // client never blocks the room; it catches up after the gate).
    for (const p of room.participants.values()) {
      if (p.connected && p.pstateSv !== room.playback.sessionVersion) p.pstate = "loading";
    }
    return { events };
  }

  chat(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (!room.chatEnabled) return { error: "unauthorized" };
    if (part.muted) return { error: "unauthorized" };
    const clean = cleanText(msg.text, LIMITS.CHAT_MAX);
    if (!clean) return { error: "bad_message" };
    room.chatSeq += 1;
    const out = { id: "m" + room.chatSeq, from: participantId, name: part.name, text: clean, ts: this.now() };
    // additive social fields: replies, spoiler flag, scene position
    if (msg.replyTo) out.replyTo = msg.replyTo;
    if (msg.spoiler === true) out.spoiler = true;
    if (msg.pos != null) out.pos = msg.pos;
    room.chat.push(out);
    if (room.chat.length > LIMITS.CHAT_HISTORY) room.chat.shift();
    room.lastActivityAt = this.now();
    return { events: [{ t: "chat", msg: out }] };
  }

  kick(roomId, participantId, targetId) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const role = this._roleOf(room, participantId);
    if (role === "participant") return { error: "unauthorized" };
    if (targetId === room.hostId || !room.participants.has(targetId)) return { error: "bad_message" };
    // a co-host can never remove another co-host (only the host manages roles)
    if (role === "cohost" && room.cohosts.has(targetId)) return { error: "unauthorized" };
    room.cohosts.delete(targetId);
    // `kicked` goes ONLY to the target - the room just hears the leave.
    // Broadcasting "kicked" to everyone used to tear down every client.
    return {
      events: this._removeParticipant(room, targetId, "kicked").events || [],
      targetEvents: [{ t: "kicked", targetId }],
    };
  }

  mute(roomId, participantId, targetId, muted) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const role = this._roleOf(room, participantId);
    if (role === "participant") return { error: "unauthorized" };
    const target = room.participants.get(targetId);
    if (!target || targetId === room.hostId) return { error: "bad_message" };
    if (role === "cohost" && room.cohosts.has(targetId)) return { error: "unauthorized" };
    target.muted = muted === true;
    return {
      events: [{ t: "peer_joined", participant: this._pub(target), room: room.id, updated: true }],
    };
  }

  settings(roomId, participantId, patch) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const role = this._roleOf(room, participantId);
    if (role === "participant") return { error: "unauthorized" }; // host + co-host manage the room
    if (patch.allowControl != null) room.allowControl = patch.allowControl === true;
    if (patch.chatEnabled != null) room.chatEnabled = patch.chatEnabled === true;
    if (patch.roomName) room.name = patch.roomName;
    // host-only fields: locking the door and the guest permission matrix
    if (role === "host") {
      if (patch.locked != null) room.locked = patch.locked === true;
      if (patch.perms != null && typeof patch.perms === "object") {
        for (const k of Object.keys(room.perms)) {
          if (patch.perms[k] != null) room.perms[k] = patch.perms[k] === true;
        }
      }
    }
    const ev = () => ({ t: "settings", room: room.id, allowControl: room.allowControl, chatEnabled: room.chatEnabled, roomName: room.name, waitMode: room.waitMode, readyTimeoutMs: room.readyTimeoutMs, holdTimeoutMs: room.holdTimeoutMs, locked: room.locked, perms: { ...room.perms } });
    if (patch.waitMode != null && WAIT_MODES.has(patch.waitMode)) {
      room.waitMode = patch.waitMode;
      // leaving wait-for-everyone releases any active hold immediately
      if (room.waitMode === "move" && room.hold) {
        return { events: [ev(), ...this._releaseHold(room, this.now(), "ready")] };
      }
    }
    if (patch.readyTimeoutMs != null && Number.isFinite(patch.readyTimeoutMs)) {
      room.readyTimeoutMs = Math.min(LIMITS.READY_TIMEOUT_MAX_MS, Math.max(LIMITS.READY_TIMEOUT_MIN_MS, patch.readyTimeoutMs));
    }
    if (patch.holdTimeoutMs != null && Number.isFinite(patch.holdTimeoutMs)) {
      room.holdTimeoutMs = Math.min(LIMITS.HOLD_TIMEOUT_MAX_MS, Math.max(LIMITS.HOLD_TIMEOUT_MIN_MS, patch.holdTimeoutMs));
    }
    return { events: [ev()] };
  }

  heartbeat(participantId, clientTs) {
    return { t: "heartbeat", clientTs: clientTs || 0, serverTs: this.now(), participantId };
  }

  syncReport(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part) return { error: "not_in_room" };
    part.position = Number(msg.position) || 0;
    part.paused = msg.paused === true;
    part.buffering = false;
    if (msg.dur != null && Number.isFinite(msg.dur) && msg.dur > 0) part.dur = msg.dur;
    if (msg.source) part.source = String(msg.source).slice(0, 24);
    // additive: newer clients ride the player state + readiness signal on the
    // report (sv/session-stamped so a previous episode's report can never mark
    // anyone ready for the current one)
    if (msg.st && typeof msg.st === "string") {
      return this.pstate(roomId, participantId, msg);
    }
    part.lastSeen = this.now();
    room.lastActivityAt = part.lastSeen;
    return { events: [] }; // reports are observability only - never rebroadcast
  }

  /* Player-state channel (readiness + buffering). This is what makes episode
     starts coordinated and slow networks recoverable: every controllable
     player reports loading/ready/playing/paused/buffering/recovered/error per
     PARTICIPANT (never one status for the room), and the room reacts:
     - a transition blocks on real "loading"/"buffering" only, bounded by the
       readiness timeout so a dead client can never trap the party
     - wait-for-everyone rooms hold the shared timeline for buffering viewers,
       released when they recover, when the hold times out, or by host override */
  pstate(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    const now = this.now();
    this._sweepGate(room, now);
    const st = msg.st;
    part.lastSeen = now;
    room.lastActivityAt = now;
    if (msg.position != null && Number.isFinite(msg.position)) part.position = msg.position;
    if (msg.dur != null && Number.isFinite(msg.dur) && msg.dur > 0) part.dur = msg.dur;
    if (msg.source) part.source = String(msg.source).slice(0, 24);
    const changed = part.pstate !== st;
    part.pstate = st;
    part.pstateSv = msg.sv != null ? msg.sv : part.pstateSv;
    part.buffering = st === "buffering";
    if (st === "playing" || st === "paused") part.paused = st === "paused";
    // readiness signal for the CURRENT transition
    if (room.gate && room.gate.phase !== "live" && part.pstateSv === room.gate.sessionVersion) {
      if (st === "ready" || st === "playing" || st === "paused" || st === "recovered") {
        room.gate.ready.add(participantId);
      }
    }
    const events = [];
    if (changed) events.push({ t: "peer_state", room: room.id, participantId, st, buffering: part.buffering, position: part.position, ts: now });
    // wait-for-everyone: a real stall holds the shared timeline
    if (st === "buffering" && room.waitMode === "everyone" && room.playback.status === "playing") {
      const evs = this._maybeCreateHold(room, participantId, now);
      events.push(...evs);
    }
    const rel = this._maybeReleaseHold(room, now);
    events.push(...rel);
    const sched = this._maybeScheduleStart(room, now);
    events.push(...sched);
    return { events };
  }

  /* Room-wide mirror ("server") switch: controllers drive it, the timeline is
     untouched (no playback reset - switching source mid-episode must keep the
     room where it is). */
  source(roomId, participantId, mirror) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (room.hostId !== participantId && !room.allowControl) return { error: "unauthorized" };
    room.source = String(mirror).slice(0, 24);
    room.lastActivityAt = this.now();
    return {
      events: [{ t: "source", room: room.id, mirror: room.source, actor: part.name }],
    };
  }

  resync(roomId, participantId) {
    const room = this.rooms.get(roomId);
    if (!room) return { error: "not_found" };
    return { events: [{ t: "room_state", room: this.snapshot(room, participantId) }] };
  }

  /* ---- authoritative media version (dub/sub/audio/subtitle/variant) ----
     The HOST's intended version lives in room state; every participant loads
     it FIRST. Local defaults (per-client heuristics, stored prefs) must never
     silently replace an explicit party selection. Values are the provider's
     own identifiers (AniPM lang segment, VidBolt panel option values) - never
     guessed language names, never expiring media URLs. */
  media(roomId, participantId, media) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    const role = this._roleOf(room, participantId);
    if (role === "participant") return { error: "unauthorized" };
    // sanitize HERE too (defense in depth - the transport already validates)
    const clean = cleanMedia(media);
    if (!clean) return { error: "bad_message" };
    room.media = { ...clean, by: part.name, at: this.now() };
    room.lastActivityAt = this.now();
    return { events: [{ t: "media", room: room.id, media: room.media, actor: part.name }] };
  }

  /* ---- roles: host promotes/demotes co-hosts (host-only) ---- */
  role(roomId, participantId, targetId, role) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    if (room.hostId !== participantId) return { error: "unauthorized" };
    if (targetId === room.hostId || !room.participants.has(targetId)) return { error: "bad_message" };
    if (role === "cohost") room.cohosts.add(targetId);
    else room.cohosts.delete(targetId);
    room.lastActivityAt = this.now();
    const target = room.participants.get(targetId);
    return {
      events: [
        { t: "role", room: room.id, participantId: targetId, role: this._roleOf(room, targetId), actor: target.name },
        { t: "peer_joined", room: room.id, participant: { ...this._pub(target), host: targetId === room.hostId, role: this._roleOf(room, targetId) }, updated: true },
      ],
    };
  }

  /* ---- playback requests (guest asks, host/co-host approves) ---- */
  request(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (room.requests.size >= LIMITS.PENDING_REQUESTS_MAX) return { error: "rate_limited" };
    room.requestSeq += 1;
    const req = {
      id: "q" + room.requestSeq,
      fromId: participantId, from: part.name,
      action: msg.action, position: msg.position, note: msg.note || "",
      at: this.now(),
    };
    room.requests.set(req.id, req);
    room.lastActivityAt = this.now();
    return { events: [{ t: "request", room: room.id, request: req }] };
  }

  requestReply(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    const role = this._roleOf(room, participantId);
    if (role === "participant") return { error: "unauthorized" };
    const req = room.requests.get(msg.requestId);
    if (!req) return { error: "not_found" };
    room.requests.delete(msg.requestId);
    const now = this.now();
    const events = [{
      t: "request_reply", room: room.id, requestId: req.id,
      approved: msg.approved === true, request: req,
      requesterId: req.fromId, approverId: participantId, approver: part.name,
    }];
    if (msg.approved === true) {
      // server-side execution where the server CAN execute (pause/rewind/
      // skip-intro are pure timeline actions). Episode moves need content
      // data only the clients have - the APPROVER's client executes those
      // (the reply event carries approverId; only that client acts).
      const pb = room.playback;
      this._sweepGate(room, now);
      if (req.action === "pause") {
        events.push(...this._applyAuthoritative(room, "pause", expectedPosition(pb, now), req.from + " (approved)", now));
      } else if (req.action === "rewind") {
        const base = req.position != null ? req.position : expectedPosition(pb, now);
        events.push(...this._applyAuthoritative(room, "seek", Math.max(0, base - 15), req.from + " (approved)", now));
      } else if (req.action === "skip_intro") {
        if (req.position != null && req.position > expectedPosition(pb, now)) {
          events.push(...this._applyAuthoritative(room, "seek", req.position, req.from + " (approved)", now));
        }
      }
    }
    room.lastActivityAt = now;
    return { events };
  }

  /* timeline actions the server performs on approval/intermission - never
     gated by the requester's own permissions (the approval IS the authority) */
  _applyAuthoritative(room, action, position, actor, now) {
    const pb = room.playback;
    if (pb.status === "ended") return [];
    if (action === "pause") {
      pb.status = "paused";
      pb.position = position != null ? position : expectedPosition(pb, now);
    } else {
      pb.position = position;
    }
    pb.updatedAt = now;
    pb.seq += 1;
    return [{
      t: "playback", seq: pb.seq, sessionVersion: pb.sessionVersion, actor,
      action: action === "pause" ? "pause" : "seek",
      state: this._pbState(room, now),
    }];
  }

  /* ---- live reactions (lightweight, rate-limited at the transport) ---- */
  react(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (part.muted) return { error: "unauthorized" };
    room.lastActivityAt = this.now();
    return {
      events: [{
        t: "react", room: room.id, emoji: msg.emoji,
        fromId: participantId, name: part.name, msgId: msg.msgId || null, ts: this.now(),
      }],
    };
  }

  /* ---- shared watch queue (suggest / vote / host-approved playback) ---- */
  queue(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    const role = this._roleOf(room, participantId);
    const now = this.now();
    // approving a queue item CHANGES canonical playback - that takes episode
    // authority (host/co-host/permed); plain queue management takes perms.queue
    const canApprove = role !== "participant" || room.perms.episode === true;
    const canManage = role !== "participant" || room.perms.queue === true;
    const events = [];
    switch (msg.op) {
      case "add": {
        if (room.queue.length >= LIMITS.QUEUE_MAX) return { error: "rate_limited" };
        room.queueSeq += 1;
        const item = { ...msg.item, id: "qi" + room.queueSeq, by: part.name, byId: participantId, votes: new Set([participantId]) };
        room.queue.push(item);
        events.push({ t: "queue", room: room.id, op: "add", item: this._queuePub(item) });
        break;
      }
      case "vote": {
        const item = room.queue.find((x) => x.id === msg.itemId);
        if (!item) return { error: "not_found" };
        item.votes.add(participantId);
        events.push({ t: "queue", room: room.id, op: "vote", item: this._queuePub(item) });
        break;
      }
      case "approve": {
        if (!canApprove) return { error: "unauthorized" };
        const item = room.queue.find((x) => x.id === msg.itemId);
        if (!item) return { error: "not_found" };
        room.queue = room.queue.filter((x) => x.id !== msg.itemId);
        events.push({ t: "queue", room: room.id, op: "approve", item: this._queuePub(item) });
        // approval = an authorized content change - executed SERVER-side so
        // canonical playback never depends on which client happens to act
        const r = this.content(roomId, participantId, { type: item.type, tmdbId: item.tmdbId, season: item.season, episode: item.episode });
        if (r.error) return r;
        events.push(...r.events);
        return { events };
      }
      case "remove": {
        const item = room.queue.find((x) => x.id === msg.itemId);
        if (!item) return { error: "not_found" };
        if (role === "participant" && item.byId !== participantId && !canManage) return { error: "unauthorized" };
        room.queue = room.queue.filter((x) => x.id !== msg.itemId);
        events.push({ t: "queue", room: room.id, op: "remove", item: this._queuePub(item) });
        break;
      }
      case "clear": {
        if (role === "participant" && !canManage) return { error: "unauthorized" };
        room.queue = [];
        events.push({ t: "queue", room: room.id, op: "clear" });
        break;
      }
      default:
        return { error: "bad_message" };
    }
    room.lastActivityAt = now;
    return { events };
  }

  _queuePub(item) {
    return {
      id: item.id, type: item.type, tmdbId: item.tmdbId,
      season: item.season, episode: item.episode, title: item.title || "",
      by: item.by, votes: item.votes ? item.votes.size : 0,
    };
  }

  /* ---- group intermission (shared break with a bounded countdown) ---- */
  intermission(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    const role = this._roleOf(room, participantId);
    if (role === "participant" && room.perms.policy !== true) return { error: "unauthorized" };
    const now = this.now();
    const events = [];
    if (msg.op === "start") {
      const seconds = Math.min(900, Math.max(30, msg.seconds || 300));
      room.intermission = { until: now + seconds * 1000, seconds, by: part.name };
      if (room.playback.status === "playing") {
        events.push(...this._applyAuthoritative(room, "pause", expectedPosition(room.playback, now), part.name + " (break)", now));
      }
      events.push({ t: "intermission", room: room.id, op: "start", seconds, until: room.intermission.until, by: part.name });
    } else {
      room.intermission = null;
      if (room.playback.status === "paused") {
        const pb = room.playback;
        pb.status = "playing";
        pb.updatedAt = now + 500;
        pb.seq += 1;
        events.push({ t: "playback", seq: pb.seq, sessionVersion: pb.sessionVersion, actor: part.name + " (break over)", action: "play", state: this._pbState(room, now) });
      }
      events.push({ t: "intermission", room: room.id, op: "end", by: part.name });
    }
    room.lastActivityAt = now;
    return { events };
  }

  markSeen(roomId, participantId, conn) {
    const room = this.rooms.get(roomId);
    const part = room && room.participants.get(participantId);
    if (!part) return;
    // a REPLACED ghost socket's leftover traffic never refreshes the live seat
    if (conn && part.conn && part.conn !== conn) return;
    part.lastSeen = this.now();
    part.connected = true;
  }

  /* ---- expiry sweep (called on a timer by the server) ---- */

  sweep(now = this.now()) {
    const out = [];
    // transitions + holds resolve on every sweep too (deadline enforcement
    // never depends on traffic arriving)
    out.push(...this.tick(now));
    for (const room of [...this.rooms.values()]) {
      if (room.ended) { this._dropRoom(room); continue; }
      if (now - room.createdAt > this.ttl.maxRoomAge) {
        room.ended = true;
        this._dropRoom(room);
        out.push({ roomId: room.id, events: [{ t: "room_ended", roomId: room.id, reason: "expired" }] });
        continue;
      }
      // disconnected participants expire after a TTL (reconnect window)
      for (const [id, part] of [...room.participants]) {
        // half-open sockets (network blip: no close ever fires) must not pin
        // a seat "connected" - silence past the stale window counts as gone,
        // so ghosts can neither fill the room nor hold the host star
        if (part.connected && now - part.lastSeen > this.ttl.staleMs) {
          part.connected = false;
          part.conn = null;
          part.pstate = "disconnected";
          if (room.hostId === id && !room.hostDisconnectedAt) room.hostDisconnectedAt = now;
        }
        if (!part.connected && now - part.lastSeen > this.ttl.participantTtl) {
          const r = this._removeParticipant(room, id, "timeout");
          if (r.events && r.events.length) out.push({ roomId: room.id, events: r.events });
        }
      }
      // remembered seats expire too (identity is restorable for a while)
      if (room.recentSeats) {
        for (const [tok, s] of [...room.recentSeats]) {
          if (now - s.removedAt > this.ttl.seatMemory) room.recentSeats.delete(tok);
        }
      }
      // a dropped member's restorable seat keeps the ROOM alive for the return
      // window: both phones losing signal for a minute must never end the
      // party (that forced "leave and create a new room again and again")
      const restorable = !!room.recentSeats && [...room.recentSeats.values()].some(
        (s) => !s.kicked && s.reason !== "left" && now - s.removedAt <= this.ttl.seatMemory);
      // host grace: state preserved, host seat restored if the host returns
      const host = room.participants.get(room.hostId);
      if (!host || !host.connected) {
        if (!room.hostDisconnectedAt) room.hostDisconnectedAt = now;
        else if (now - room.hostDisconnectedAt > this.ttl.hostGrace) {
          const next = [...room.participants.values()].filter((p) => p.connected).sort((a, b) => a.joinedAt - b.joinedAt)[0];
          if (next) {
            room.hostId = next.id;
            room.hostDisconnectedAt = 0;
            out.push({ roomId: room.id, events: [{ t: "host_changed", room: room.id, hostId: next.id, hostName: next.name }] });
          } else if (room.participants.size === 0 && !restorable) {
            room.ended = true;
            this._dropRoom(room);
            out.push({ roomId: room.id, events: [{ t: "room_ended", roomId: room.id, reason: "abandoned" }] });
          }
        }
      } else {
        room.hostDisconnectedAt = 0;
      }
      // empty rooms are reclaimed (unless a restorable seat still promises a return)
      if (room.participants.size === 0 && !restorable && now - room.lastActivityAt > this.ttl.emptyRoomTtl) {
        room.ended = true;
        this._dropRoom(room);
      }
    }
    return out;
  }

  /* ---- helpers ---- */

  /* Wait-for-everyone: hold the shared timeline for a participant whose player
     stalled. The room pauses at the authoritative position (never the stalled
     player's outdated timestamp), bounded by holdTimeoutMs and host override. */
  _maybeCreateHold(room, partId, now) {
    if (room.hold) {
      room.hold.for.add(partId);
      return [];
    }
    const pb = room.playback;
    if (pb.status !== "playing") return [];
    const holdMs = Math.min(LIMITS.HOLD_TIMEOUT_MAX_MS,
      Math.max(LIMITS.HOLD_TIMEOUT_MIN_MS, room.holdTimeoutMs || LIMITS.HOLD_TIMEOUT_DEFAULT_MS));
    room.hold = { for: new Set([partId]), until: now + holdMs, auto: true };
    pb.position = expectedPosition(pb, now);
    pb.status = "paused";
    pb.updatedAt = now;
    pb.seq += 1;
    return [{
      t: "playback",
      seq: pb.seq,
      sessionVersion: pb.sessionVersion,
      actor: "Party",
      action: "pause",
      auto: true,
      hold: this._holdState(room, now),
      state: this._pbState(room, now),
    }];
  }

  _releaseHold(room, now, reason) {
    const hold = room.hold;
    room.hold = null;
    const pb = room.playback;
    if (pb.status !== "paused") return [];
    pb.status = "playing";
    pb.updatedAt = now + 500; // tiny shared resume gate - everyone unfreezes together
    pb.seq += 1;
    const events = [{
      t: "playback",
      seq: pb.seq,
      sessionVersion: pb.sessionVersion,
      actor: "Party",
      action: "play",
      auto: true,
      hold: null,
      state: this._pbState(room, now),
    }];
    if (reason === "timeout" && hold) {
      // honest heads-up: the party started without whoever is still stuck
      events.push({ t: "notice", notice: "hold_released", waiting: hold.for.size });
    }
    return events;
  }

  /* Release the hold when everyone it waits for recovered, or the timeout hit
     (whichever first). Dead/disconnected sources never trap the room. */
  _maybeReleaseHold(room, now) {
    if (!room.hold) return [];
    const blockers = [...room.hold.for].filter((id) => {
      const p = room.participants.get(id);
      return p && p.connected && PSTATE_BLOCKING.has(p.pstate);
    });
    if (blockers.length === 0) return this._releaseHold(room, now, "ready");
    if (now >= room.hold.until) return this._releaseHold(room, now, "timeout");
    return [];
  }

  /* Readiness-gated shared start. Finalizes when the session-stamped blockers
     are done (at least one positive ready signal required - so a room of
     legacy clients that never report keeps the classic deadline behavior),
     or when the readiness timeout expires. Never runs twice. */
  _maybeScheduleStart(room, now = this.now()) {
    const gate = room.gate;
    if (!gate || gate.phase !== "loading") return [];
    const blockers = [];
    for (const [id, p] of room.participants) {
      if (!p.connected) continue;
      if (p.pstateSv === gate.sessionVersion && PSTATE_BLOCKING.has(p.pstate)) blockers.push(id);
    }
    if (now >= gate.deadline) {
      return this._finalizeStart(room, now, "timeout");
    }
    if (now >= gate.earliest && blockers.length === 0 && gate.ready.size > 0) {
      return this._finalizeStart(room, now + LIMITS.START_COUNTDOWN_MS, "ready");
    }
    return [];
  }

  _finalizeStart(room, startAt, reason) {
    const gate = room.gate;
    if (!gate || gate.phase === "live") return [];
    const now = this.now();
    gate.phase = "scheduled";
    gate.startAt = startAt;
    gate.reason = reason;
    const pb = room.playback;
    // shared gate on the SERVER clock: updatedAt lands in the future,
    // expectedPosition clamps to 0 until then (unchanged shared math)
    pb.updatedAt = startAt;
    pb.status = "playing";
    pb.position = 0;
    pb.seq += 1;
    const events = [
      // re-broadcast the SAME content with the finalized gate: every client
      // generation folds this into its gate logic (same content key = no
      // reload, just a re-armed start timer)
      {
        t: "content",
        seq: pb.seq,
        sessionVersion: pb.sessionVersion,
        actor: "Party",
        content: room.content,
        state: this._pbState(room, startAt > now ? startAt : now),
      },
      this._scheduleEvent(room, startAt > now ? startAt : now, reason),
    ];
    // once the gate passes, the session is live (evaluated lazily on traffic
    // and on every tick so no timer is required)
    return events;
  }

  _scheduleEvent(room, now, reason) {
    const gate = room.gate;
    return {
      t: "schedule",
      room: room.id,
      sessionVersion: room.playback.sessionVersion,
      phase: gate ? gate.phase : "live",
      startAt: gate ? gate.startAt : null,
      deadline: gate ? gate.deadline : null,
      ready: gate ? gate.ready.size : 0,
      total: [...room.participants.values()].filter((p) => p.connected).length,
      reason: reason || null,
    };
  }

  _holdState(room, now) {
    if (!room.hold) return null;
    return { until: room.hold.until, waiting: room.hold.for.size };
  }

  /* Lazy deadline enforcement: transitions and holds resolve on traffic and on
     the periodic tick - correctness never depends on a bare timer alone. */
  tick(now = this.now()) {
    const out = [];
    for (const room of this.rooms.values()) {
      if (room.ended) continue;
      this._sweepGate(room, now);
      const evs = [
        ...this._maybeScheduleStart(room, now),
        ...this._maybeReleaseHold(room, now),
      ];
      // an intermission countdown that ran out resumes the party by itself
      // (bounded - a forgotten break can never freeze the room)
      if (room.intermission && now >= room.intermission.until) {
        room.intermission = null;
        const pb = room.playback;
        if (pb.status === "paused") {
          pb.status = "playing";
          pb.updatedAt = now + 500;
          pb.seq += 1;
          evs.push({ t: "playback", seq: pb.seq, sessionVersion: pb.sessionVersion, actor: "Party", action: "play", state: this._pbState(room, now) });
        }
        evs.push({ t: "intermission", room: room.id, op: "end", by: "timeout" });
      }
      if (evs.length) out.push({ roomId: room.id, events: evs });
    }
    return out;
  }

  /* A scheduled gate becomes "live" the moment its start time is behind us -
     otherwise a legit episode-end later in the session would look stale. */
  _sweepGate(room, now) {
    const gate = room.gate;
    if (gate && gate.phase === "scheduled" && gate.startAt != null && now >= gate.startAt) {
      gate.phase = "live";
    }
  }

  /* ---- roles + permissions (server-enforced) ---- */

  _roleOf(room, participantId) {
    if (room.hostId === participantId) return "host";
    if (room.cohosts && room.cohosts.has(participantId)) return "cohost";
    return "participant";
  }

  /* Hosts + co-hosts control playback/episodes; guests need allowControl AND
     the granular permission for that class of action. Host-only ops (end,
     roles, lock) are checked separately. */
  _canControl(room, part, kind) {
    const role = this._roleOf(room, part.id);
    if (role !== "participant") return true;
    if (!room.allowControl) return false;
    if (kind === "play" || kind === "rate") return room.perms.play !== false;
    if (kind === "seek") return room.perms.seek !== false;
    if (kind === "episode") return room.perms.episode === true;
    return false;
  }

  _mkParticipant(p, now, isHost) {
    return {
      id: p.id, name: cleanName(p.name), token: p.token || makeId("tok"),
      conn: p.conn || null, joinedAt: now, lastSeen: now, connected: true,
      muted: false, buffering: false, position: 0, paused: true,
      dur: 0, source: "",
      // per-participant player state (never one status for the whole room)
      pstate: "ready", pstateSv: 0,
    };
  }

  _removeParticipant(room, participantId, reason) {
    const part = room.participants.get(participantId);
    if (!part) return { events: [] };
    room.participants.delete(participantId);
    // remember the seat briefly so a resume token can restore identity/host
    if (!room.recentSeats) room.recentSeats = new Map();
    room.recentSeats.set(part.token, {
      id: part.id, name: part.name, wasHost: room.hostId === participantId,
      removedAt: this.now(), kicked: reason === "kicked", reason,
    });
    room.lastActivityAt = this.now();
    const events = [{ t: "peer_left", room: room.id, participantId, name: part.name, reason }];
    if (room.hostId === participantId && !room.ended) {
      // grace period runs from the DISCONNECT, never reset by seat expiry
      if (!room.hostDisconnectedAt) room.hostDisconnectedAt = this.now();
    }
    return { events };
  }

  _dropRoom(room) {
    this.rooms.delete(room.id);
    this.byCode.delete(room.code);
  }

  _pbState(room, now) {
    return {
      status: room.playback.status,
      position: expectedPosition(room.playback, now),
      rate: room.playback.rate,
      // future stamps are the scheduled-start gate and must survive
      updatedAt: room.playback.updatedAt > now ? room.playback.updatedAt : now,
      sessionVersion: room.playback.sessionVersion,
    };
  }

  _pub(part) {
    return {
      id: part.id, name: part.name, connected: part.connected, muted: part.muted,
      buffering: part.buffering, position: part.position, paused: part.paused,
      dur: part.dur || 0, source: part.source || "",
      // per-participant player state (loading/ready/playing/buffering/...)
      pstate: part.pstate || "ready",
    };
  }

  snapshot(room, selfId) {
    const now = this.now();
    this._sweepGate(room, now);
    return {
      roomId: room.id,
      code: room.code,
      name: room.name,
      hostId: room.hostId,
      selfId,
      hasPassword: !!room.passwordHash,
      maxParticipants: room.maxParticipants,
      allowControl: room.allowControl,
      chatEnabled: room.chatEnabled,
      content: room.content,
      source: room.source || "",
      // AUTHORITATIVE media version (dub/sub/audio/subtitle/variant) - what
      // every participant should load first (additive)
      media: room.media || null,
      // roles + permissions + door state (additive)
      cohosts: room.cohosts ? [...room.cohosts] : [],
      perms: { ...room.perms },
      locked: !!room.locked,
      // social layer (additive)
      queue: (room.queue || []).map((x) => this._queuePub(x)),
      requests: [...(room.requests || new Map()).values()],
      intermission: room.intermission || null,
      // buffering policy + coordinated-transition state (additive)
      waitMode: room.waitMode || "move",
      readyTimeoutMs: room.readyTimeoutMs || LIMITS.READY_TIMEOUT_DEFAULT_MS,
      holdTimeoutMs: room.holdTimeoutMs || LIMITS.HOLD_TIMEOUT_DEFAULT_MS,
      gate: room.gate ? {
        phase: room.gate.phase,
        startAt: room.gate.startAt,
        deadline: room.gate.deadline,
        ready: room.gate.ready.size,
      } : null,
      hold: this._holdState(room, now),
      playback: {
        ...this._pbState(room, now),
        seq: room.playback.seq,
      },
      participants: [...room.participants.values()].map((p) => ({ ...this._pub(p), host: p.id === room.hostId, role: this._roleOf(room, p.id) })),
      chat: room.chat.slice(-LIMITS.CHAT_HISTORY),
      serverTs: now,
    };
  }

  roomCount() { return this.rooms.size; }
}

module.exports = { RoomManager, RateLimiter, makeId, hashPassword };
