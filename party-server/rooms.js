"use strict";
/* StreamBox Watch Party room state machine. The server is authoritative:
   every command is validated and permission-checked HERE (clients hide
   controls, the server enforces). No media bytes, no credentials — only
   playback *state* (ids, positions, statuses) ever lives in a room. */

const crypto = require("crypto");
const {
  LIMITS, CODE_ALPHABET, CODE_LEN, CODE_RE,
  cleanText, cleanName, expectedPosition,
} = require("./protocol");

const START_DELAY_MS = 8000; // episode changes start together on the server clock
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
    const now = Date.now();
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
    const now = Date.now();
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
    part.lastSeen = Date.now();
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
    if (room.hostId !== participantId && !room.allowControl) return { error: "unauthorized" };
    // stale guard: a playback action stamped for a previous episode/session
    // must never affect the current one
    if (msg.sessionVersion != null && msg.sessionVersion !== room.playback.sessionVersion) {
      return { stale: true };
    }
    const now = Date.now();
    const pb = room.playback;
    const cur = expectedPosition(pb, now);
    switch (msg.action) {
      case "play":
        pb.status = "playing";
        pb.position = msg.position != null ? msg.position : cur;
        break;
      case "pause":
        pb.status = "paused";
        pb.position = msg.position != null ? msg.position : cur;
        break;
      case "seek":
        if (msg.position == null) return { error: "bad_message" };
        pb.position = msg.position;
        break;
      case "rate":
        if (msg.rate == null) return { error: "bad_message" };
        pb.position = cur;
        pb.rate = msg.rate;
        break;
      case "ended":
        pb.status = "ended";
        pb.position = cur;
        break;
      case "buffer":
        // participant-side buffering notice: state unchanged, no broadcast
        part.buffering = true;
        return { buffered: true };
      default:
        return { error: "bad_message" };
    }
    pb.updatedAt = now;
    pb.seq += 1;
    room.lastActivityAt = now;
    part.buffering = false;
    return {
      events: [{
        t: "playback",
        seq: pb.seq,
        sessionVersion: pb.sessionVersion,
        actor: part.name,
        action: msg.action,
        state: this._pbState(room, now),
      }],
    };
  }

  content(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (room.hostId !== participantId) return { error: "unauthorized" }; // episode change is host-only
    const now = Date.now();
    room.content = { type: msg.type, tmdbId: msg.tmdbId, season: msg.season, episode: msg.episode };
    // new playback session: previous events are stale from here on.
    // Scheduled start: everyone begins this episode at startAt on the SERVER
    // clock (updatedAt lands in the future; expectedPosition clamps to 0
    // until then) - no more different start times per device.
    room.playback = {
      status: "playing", position: 0, rate: 1, updatedAt: now + START_DELAY_MS,
      sessionVersion: room.playback.sessionVersion + 1,
      seq: room.playback.seq + 1,
    };
    room.lastActivityAt = now;
    return {
      events: [{
        t: "content",
        seq: room.playback.seq,
        sessionVersion: room.playback.sessionVersion,
        actor: part.name,
        content: room.content,
        state: this._pbState(room, now),
      }],
    };
  }

  chat(roomId, participantId, text) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (!room.chatEnabled) return { error: "unauthorized" };
    if (part.muted) return { error: "unauthorized" };
    const clean = cleanText(text, LIMITS.CHAT_MAX);
    if (!clean) return { error: "bad_message" };
    room.chatSeq += 1;
    const msg = { id: "m" + room.chatSeq, from: participantId, name: part.name, text: clean, ts: Date.now() };
    room.chat.push(msg);
    if (room.chat.length > LIMITS.CHAT_HISTORY) room.chat.shift();
    room.lastActivityAt = Date.now();
    return { events: [{ t: "chat", msg }] };
  }

  kick(roomId, participantId, targetId) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    if (room.hostId !== participantId) return { error: "unauthorized" };
    if (targetId === room.hostId || !room.participants.has(targetId)) return { error: "bad_message" };
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
    if (room.hostId !== participantId) return { error: "unauthorized" };
    const target = room.participants.get(targetId);
    if (!target || targetId === room.hostId) return { error: "bad_message" };
    target.muted = muted === true;
    return {
      events: [{ t: "peer_joined", participant: this._pub(target), room: room.id, updated: true }],
    };
  }

  settings(roomId, participantId, patch) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    if (room.hostId !== participantId) return { error: "unauthorized" };
    if (patch.allowControl != null) room.allowControl = patch.allowControl === true;
    if (patch.chatEnabled != null) room.chatEnabled = patch.chatEnabled === true;
    if (patch.roomName) room.name = patch.roomName;
    return { events: [{ t: "settings", room: room.id, allowControl: room.allowControl, chatEnabled: room.chatEnabled, roomName: room.name }] };
  }

  heartbeat(participantId, clientTs) {
    return { t: "heartbeat", clientTs: clientTs || 0, serverTs: Date.now(), participantId };
  }

  syncReport(roomId, participantId, position, paused, dur, source) {
    const room = this.rooms.get(roomId);
    if (!room) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part) return { error: "not_in_room" };
    part.position = position;
    part.paused = paused === true;
    part.buffering = false;
    if (dur != null && Number.isFinite(dur) && dur > 0) part.dur = dur;
    if (source) part.source = String(source).slice(0, 24);
    part.lastSeen = Date.now();
    room.lastActivityAt = part.lastSeen;
    return { events: [] }; // reports are observability only - never rebroadcast
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
    room.lastActivityAt = Date.now();
    return {
      events: [{ t: "source", room: room.id, mirror: room.source, actor: part.name }],
    };
  }

  resync(roomId, participantId) {
    const room = this.rooms.get(roomId);
    if (!room) return { error: "not_found" };
    return { events: [{ t: "room_state", room: this.snapshot(room, participantId) }] };
  }

  markSeen(roomId, participantId, conn) {
    const room = this.rooms.get(roomId);
    const part = room && room.participants.get(participantId);
    if (!part) return;
    // a REPLACED ghost socket's leftover traffic never refreshes the live seat
    if (conn && part.conn && part.conn !== conn) return;
    part.lastSeen = Date.now();
    part.connected = true;
  }

  /* ---- expiry sweep (called on a timer by the server) ---- */

  sweep(now = Date.now()) {
    const out = [];
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

  _mkParticipant(p, now, isHost) {
    return {
      id: p.id, name: cleanName(p.name), token: p.token || makeId("tok"),
      conn: p.conn || null, joinedAt: now, lastSeen: now, connected: true,
      muted: false, buffering: false, position: 0, paused: true,
      dur: 0, source: "",
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
      removedAt: Date.now(), kicked: reason === "kicked", reason,
    });
    room.lastActivityAt = Date.now();
    const events = [{ t: "peer_left", room: room.id, participantId, name: part.name, reason }];
    if (room.hostId === participantId && !room.ended) {
      // grace period runs from the DISCONNECT, never reset by seat expiry
      if (!room.hostDisconnectedAt) room.hostDisconnectedAt = Date.now();
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
    };
  }

  snapshot(room, selfId) {
    const now = Date.now();
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
      playback: {
        ...this._pbState(room, now),
        seq: room.playback.seq,
      },
      participants: [...room.participants.values()].map((p) => ({ ...this._pub(p), host: p.id === room.hostId })),
      chat: room.chat.slice(-LIMITS.CHAT_HISTORY),
      serverTs: now,
    };
  }

  roomCount() { return this.rooms.size; }
}

module.exports = { RoomManager, RateLimiter, makeId, hashPassword };
