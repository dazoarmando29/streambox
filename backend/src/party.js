/* Watch Party hub - Cloudflare Durable Object (same protocol as the
   standalone party-server; that Node service remains the self-host path).
   ONE hub instance holds every room (parties are small and ephemeral);
   the server stays authoritative for room + playback STATE and no media
   bytes ever cross it. Rooms live in memory: a DO eviction behaves like a
   server restart - clients get `room_gone` and clean up (tested flow).

   Hibernation-safe: participant meta rides the WebSocket attachment, so a
   sleeping isolate wakes up knowing who is who. Alarms drive the TTL sweep
   (no timers survive hibernation). */

const LIMITS = {
  MAX_MSG_BYTES: 8 * 1024,
  NAME_MAX: 24,
  ROOM_NAME_MAX: 40,
  CHAT_MAX: 300,
  MAX_PARTICIPANTS: 16,
  DEFAULT_PARTICIPANTS: 8,
  MSG_WINDOW_MS: 10000,
  MSG_MAX: 60,
  CHAT_WINDOW_MS: 10000,
  CHAT_RATE_MAX: 6,
  CODE_ATTEMPT_WINDOW_MS: 5 * 60 * 1000,
  CODE_ATTEMPT_MAX: 12,
  HOST_GRACE_MS: 60 * 1000,
  PARTICIPANT_TTL_MS: 45 * 1000,
  // half-open sockets (no close fires) must not pin a seat "connected" forever
  PARTICIPANT_STALE_MS: 100 * 1000,
  EMPTY_ROOM_TTL_MS: 120 * 1000,
  MAX_ROOM_AGE_MS: 24 * 60 * 60 * 1000,
  RECENT_SEAT_MS: 5 * 60 * 1000,
  CHAT_HISTORY: 50,
  MAX_ROOMS: 200,
  CREATE_WINDOW_MS: 60 * 60 * 1000,
  CREATE_MAX: 3,
};

const START_DELAY_MS = 8000; // episode changes start together on the server clock
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_RE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/;

function cleanText(s, max) {
  if (typeof s !== "string") return "";
  return s.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}
function cleanName(s) { return cleanText(s, LIMITS.NAME_MAX) || "Guest"; }

function expectedPosition(pb, now) {
  if (!pb) return 0;
  const p = Number(pb.position) || 0;
  if (pb.status !== "playing") return p;
  return Math.max(0, p + ((now - (pb.updatedAt || now)) / 1000) * (Number(pb.rate) || 1));
}

function uid(prefix) {
  const b = crypto.getRandomValues(new Uint8Array(12));
  let s = "";
  for (const x of b) s += String.fromCharCode(48 + (x % 74));
  return prefix + btoa(s).replace(/[+/=]/g, "") + Date.now().toString(36);
}

class Lim {
  constructor(windowMs, max) { this.w = windowMs; this.max = max; this.hits = new Map(); }
  allow(key, now = Date.now()) {
    let h = this.hits.get(key);
    if (!h || now - h.start >= this.w) { h = { start: now, n: 0 }; this.hits.set(key, h); }
    h.n += 1;
    return h.n <= this.max;
  }
  sweep(now = Date.now()) {
    for (const [k, h] of this.hits) if (now - h.start >= this.w) this.hits.delete(k);
  }
}

export class PartyHub {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.rooms = new Map();   // roomId -> room
    this.byCode = new Map();  // code -> roomId
    this.codeTries = new Lim(LIMITS.CODE_ATTEMPT_WINDOW_MS, LIMITS.CODE_ATTEMPT_MAX);
    this.createLim = new Lim(LIMITS.CREATE_WINDOW_MS, LIMITS.CREATE_MAX);
    this.msgLim = new Map();  // participantId -> { msg, chat }
  }

  /* ---------- transport ---------- */

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname !== "/party") return new Response("not found", { status: 404 });
    const allowed = (this.env.PARTY_ALLOWED_ORIGINS || "https://dazoarmando29.github.io,http://localhost:8787,http://127.0.0.1:8787,http://localhost:8000,http://127.0.0.1:8000")
      .split(",").map((s) => s.trim()).filter(Boolean);
    const origin = request.headers.get("origin") || "";
    if (origin && !allowed.includes(origin)) return new Response("forbidden", { status: 403 });
    if (request.headers.get("Upgrade") !== "websocket") return new Response("upgrade required", { status: 426 });

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const ip = (request.headers.get("cf-connecting-ip") || "").split(",")[0].trim() || "unknown";
    // cid = per-CONNECTION generation: the seat keeps the cid of the socket
    // that owns it, so a replaced ghost's traffic/close can never evict the
    // live connection (the duplicate-"you" bug)
    const meta = { id: uid("p"), token: uid("tok"), name: "Guest", roomId: "", ip, cid: uid("c") };
    server.serializeAttachment(meta);
    this.state.acceptWebSocket(server);
    this.send(server, { t: "welcome", participantId: meta.id, token: meta.token, serverTs: Date.now() });
    return new Response(null, { status: 101, webSocket: client });
  }

  send(ws, obj) {
    try { ws.send(JSON.stringify(obj)); } catch (e) {}
  }

  meta(ws) {
    try { return ws.deserializeAttachment(); } catch (e) { return null; }
  }

  broadcast(roomId, events, exceptId) {
    const room = this.rooms.get(roomId);
    if (!room) return;
    for (const ws of this.state.getWebSockets()) {
      const m = this.meta(ws);
      if (!m || m.roomId !== roomId || m.id === exceptId) continue;
      for (const ev of events) this.send(ws, ev);
    }
  }

  /* ---------- inbound ---------- */

  async webSocketMessage(ws, raw) {
    try {
      await this.dispatch(ws, raw);
    } catch (e) {
      console.error("party-dispatch-error", (e && e.stack) || String(e));
      try { this.send(ws, { t: "error", error: "bad_message" }); } catch (e2) {}
    }
  }

  async dispatch(ws, raw) {
    const m0 = this.meta(ws);
    if (!m0) { console.error("party: message from a socket without meta"); return; }
    // BYTES, not UTF-16 code units: raw.length let ~4x the budget through
    // (8k emoji ≈ 32KiB of UTF-8 before the gate fired)
    if (typeof raw !== "string" || new TextEncoder().encode(raw).length > LIMITS.MAX_MSG_BYTES) {
      this.send(ws, { t: "error", error: "bad_message" });
      return;
    }
    const lim = this.limFor(m0.id);
    if (!lim.msg.allow("m", Date.now())) { this.send(ws, { t: "error", error: "rate_limited" }); return; }

    let m;
    try { m = JSON.parse(raw); } catch (e) { return this.send(ws, { t: "error", error: "bad_message" }); }
    if (!m || typeof m.t !== "string") return this.send(ws, { t: "error", error: "bad_message" });

    const inRoom = !!m0.roomId;
    const allowIn = ["hello", "create", "join", "heartbeat"];
    if (!inRoom && !allowIn.includes(m.t)) return this.send(ws, { t: "error", error: "not_in_room" });

    // a REPLACED ghost socket (this seat already moved to a newer connection)
    // must never drive the room - drop its leftover traffic entirely
    if (m0.roomId && m.t !== "join" && m.t !== "heartbeat") {
      const rN = this.rooms.get(m0.roomId);
      const pN = rN && rN.participants.get(m0.id);
      if (pN && pN.cid && m0.cid && pN.cid !== m0.cid) return;
    }

    switch (m.t) {
      case "hello":
        m0.name = cleanName(m.name); ws.serializeAttachment(m0); return;
      case "heartbeat": {
        this.send(ws, { t: "heartbeat", clientTs: Number(m.clientTs) || 0, serverTs: Date.now(), participantId: m0.id });
        const room = m0.roomId ? this.rooms.get(m0.roomId) : null;
        if (m0.roomId && !room) {
          this.send(ws, { t: "notice", notice: "room_gone" });
          m0.roomId = ""; ws.serializeAttachment(m0);
        }
        return;
      }
      case "create": {
        if (m0.roomId) return this.send(ws, { t: "error", error: "conflict" });
        // per-IP create budget + a global room cap: unbounded creates let one
        // attacker fill the hub's memory and evict EVERY legit party
        if (!this.createLim.allow(m0.ip || m0.id)) return this.send(ws, { t: "error", error: "rate_limited" });
        if (this.rooms.size >= LIMITS.MAX_ROOMS) return this.send(ws, { t: "error", error: "full" });
        m0.name = cleanName(m.name);
        const room = this.createRoom(m0, m);
        m0.roomId = room.id;
        ws.serializeAttachment(m0);
        this.send(ws, { t: "room", room: this.snapshot(room, m0.id), token: m0.token });
        this.armAlarm();
        return;
      }
      case "join": {
        const already = m0.roomId && this.rooms.get(m0.roomId) && this.rooms.get(m0.roomId).participants.has(m0.id);
        if (already) return this.send(ws, { t: "error", error: "conflict" });
        m0.name = cleanName(m.name);
        const code = typeof m.code === "string" ? m.code.trim().toUpperCase() : "";
        const r = this.joinRoom(m0, code, typeof m.password === "string" ? m.password : "",
          typeof m.token === "string" ? m.token : "");
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "join" });
        m0.roomId = r.room.id;
        if (r.reattached) {
          // the limiter follows the seat: p.id churn used to orphan its entry
          // AND hand every reconnect a fresh rate budget
          const l = this.msgLim.get(m0.id);
          if (l) this.msgLim.delete(m0.id);
          m0.id = r.reattached;
          if (l) this.msgLim.set(m0.id, l);
        }
        ws.serializeAttachment(m0);
        // always hand back the SEAT token (r.token): the connection token
        // rotates on every reconnect and must never become the resume identity
        this.send(ws, { t: "room", room: this.snapshot(this.rooms.get(m0.roomId), m0.id), token: r.token || m0.token, reattached: !!r.reattached, replaced: !!r.replaced });
        if (r.events && r.events.length) this.broadcast(m0.roomId, r.events, m0.id);
        if (!r.reattached) {
          const pub = this.snapshot(this.rooms.get(m0.roomId), m0.id).participants.find((x) => x.id === m0.id);
          this.broadcast(m0.roomId, [{ t: "peer_joined", room: m0.roomId, participant: pub }], m0.id);
        }
        this.armAlarm();
        return;
      }
      case "leave": {
        const rid = m0.roomId;
        if (!rid) return;
        m0.roomId = ""; ws.serializeAttachment(m0);
        this.broadcast(rid, this.removeParticipant(rid, m0.id, "left"));
        return;
      }
      case "end": {
        const room = this.rooms.get(m0.roomId);
        if (!room) return this.send(ws, { t: "error", error: "not_found" });
        if (room.hostId !== m0.id) return this.send(ws, { t: "error", error: "unauthorized" });
        room.ended = true;
        const rid = m0.roomId;
        m0.roomId = ""; ws.serializeAttachment(m0);
        this.broadcast(rid, [{ t: "room_ended", roomId: rid, reason: "host_ended" }]);
        this.dropRoom(room);
        return;
      }
      case "playback": {
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found", for: "playback" });
        const part = room.participants.get(m0.id);
        if (!part) return this.send(ws, { t: "error", error: "not_in_room", for: "playback" });
        if (room.hostId !== m0.id && !room.allowControl) return this.send(ws, { t: "error", error: "unauthorized", for: "playback" });
        const sv = m.sessionVersion != null ? parseInt(m.sessionVersion, 10) : null;
        if (sv != null && sv !== room.playback.sessionVersion) return this.send(ws, { t: "notice", notice: "stale_playback" });
        const action = String(m.action || "");
        const now = Date.now();
        const pb = room.playback;
        const cur = expectedPosition(pb, now);
        const pos = m.position != null ? Number(m.position) : null;
        if (pos != null && (!Number.isFinite(pos) || pos < 0 || pos > 24 * 3600)) return this.send(ws, { t: "error", error: "bad_message" });
        if (action === "play") { pb.status = "playing"; pb.position = pos != null ? pos : cur; }
        else if (action === "pause") { pb.status = "paused"; pb.position = pos != null ? pos : cur; }
        else if (action === "seek") {
          if (pos == null) return this.send(ws, { t: "error", error: "bad_message" });
          pb.position = pos;
        } else if (action === "rate") {
          const r = Number(m.rate);
          if (!Number.isFinite(r) || r < 0.25 || r > 3) return this.send(ws, { t: "error", error: "bad_message" });
          pb.position = cur; pb.rate = r;
        } else if (action === "ended") { pb.status = "ended"; pb.position = cur; }
        else if (action === "buffer") {
          // participant-side buffering notice: state unchanged, no broadcast
          // (protocol parity with party-server: never answered bad_message)
          part.buffering = true;
          return;
        }
        else return this.send(ws, { t: "error", error: "bad_message" });
        pb.updatedAt = now;
        pb.seq += 1;
        part.buffering = false;
        this.broadcast(m0.roomId, [{
          t: "playback", seq: pb.seq, sessionVersion: pb.sessionVersion,
          actor: part.name, action, state: this.pbState(room, now),
        }]);
        return;
      }
      case "content": {
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found", for: "content" });
        if (room.hostId !== m0.id) return this.send(ws, { t: "error", error: "unauthorized", for: "content" });
        const type = m.type === "movie" ? "movie" : "tv";
        const id = parseInt(m.tmdbId, 10);
        if (!Number.isFinite(id) || id <= 0) return this.send(ws, { t: "error", error: "bad_message" });
        const now = Date.now();
        room.content = { type, tmdbId: id, season: Math.max(1, parseInt(m.season, 10) || 1), episode: Math.max(1, parseInt(m.episode, 10) || 1) };
        // Scheduled start: everyone begins at startAt on the SERVER clock
        // (updatedAt lands in the future; expectedPosition clamps to 0 until
        // then) - no more different start times per device.
        room.playback = { status: "playing", position: 0, rate: 1, updatedAt: now + START_DELAY_MS, sessionVersion: room.playback.sessionVersion + 1, seq: room.playback.seq + 1 };
        this.broadcast(m0.roomId, [{
          t: "content", seq: room.playback.seq, sessionVersion: room.playback.sessionVersion,
          actor: (room.participants.get(m0.id) || {}).name || "", content: room.content, state: this.pbState(room, now),
        }]);
        return;
      }
      case "chat": {
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found", for: "chat" });
        const part = room.participants.get(m0.id);
        if (!part || part.muted || !room.chatEnabled) return this.send(ws, { t: "error", error: "unauthorized", for: "chat" });
        if (!lim.chat.allow("c", Date.now())) return this.send(ws, { t: "error", error: "rate_limited", for: "chat" });
        const text = cleanText(m.text, LIMITS.CHAT_MAX);
        if (!text) return this.send(ws, { t: "error", error: "bad_message" });
        room.chatSeq += 1;
        const msg = { id: "m" + room.chatSeq, from: m0.id, name: part.name, text, ts: Date.now() };
        room.chat.push(msg);
        if (room.chat.length > LIMITS.CHAT_HISTORY) room.chat.shift();
        this.broadcast(m0.roomId, [{ t: "chat", msg }]);
        return;
      }
      case "kick": {
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found" });
        if (room.hostId !== m0.id) return this.send(ws, { t: "error", error: "unauthorized" });
        const targetId = String(m.targetId || "");
        if (!targetId || targetId === room.hostId || !room.participants.has(targetId)) return this.send(ws, { t: "error", error: "bad_message" });
        // `kicked` goes ONLY to the target - the room just hears the leave.
        // (Broadcasting `kicked` to everyone used to tear down every client:
        // each one treated any `kicked` event as its own removal.)
        const targetEvents = [{ t: "kicked", targetId }];
        const roomEvents = this.removeParticipant(m0.roomId, targetId, "kicked");
        for (const ws2 of this.state.getWebSockets()) {
          const m2 = this.meta(ws2);
          if (m2 && m2.id === targetId) {
            for (const ev of targetEvents.concat(roomEvents)) this.send(ws2, ev);
            // detach BEFORE the broadcast or the kicked socket hears it twice
            m2.roomId = "";
            ws2.serializeAttachment(m2);
          }
        }
        this.broadcast(m0.roomId, roomEvents);
        return;
      }
      case "mute": {
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found" });
        if (room.hostId !== m0.id) return this.send(ws, { t: "error", error: "unauthorized" });
        const target = room.participants.get(String(m.targetId || ""));
        if (!target || m.targetId === room.hostId) return this.send(ws, { t: "error", error: "bad_message" });
        target.muted = m.muted === true;
        this.broadcast(m0.roomId, [{ t: "peer_joined", room: room.id, participant: { ...this.pub(target, room), host: target.id === room.hostId }, updated: true }]);
        return;
      }
      case "settings": {
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found" });
        if (room.hostId !== m0.id) return this.send(ws, { t: "error", error: "unauthorized" });
        if (m.allowControl != null) room.allowControl = m.allowControl === true;
        if (m.chatEnabled != null) room.chatEnabled = m.chatEnabled === true;
        if (m.roomName) room.name = cleanText(m.roomName, LIMITS.ROOM_NAME_MAX);
        this.broadcast(m0.roomId, [{ t: "settings", room: room.id, allowControl: room.allowControl, chatEnabled: room.chatEnabled, roomName: room.name }]);
        return;
      }
      case "resync": {
        const room = this.rooms.get(m0.roomId);
        if (!room) return this.send(ws, { t: "error", error: "not_found" });
        this.send(ws, { t: "room_state", room: this.snapshot(room, m0.id) });
        return;
      }
      case "sync_report": {
        const room = this.rooms.get(m0.roomId);
        const part = room && room.participants.get(m0.id);
        if (part) {
          // clamp like party-server/protocol.js (negatives/Infinity never enter room state)
          const pos = Number(m.position);
          part.position = Number.isFinite(pos) && pos >= 0 && pos <= 24 * 3600 ? pos : 0;
          part.paused = m.paused === true;
          part.buffering = false;
          // additive: duration + source name let members see real peer state
          // and detect different cuts (sub vs dub / two mirrors)
          const dur = Number(m.dur);
          if (Number.isFinite(dur) && dur > 0 && dur <= 24 * 3600) part.dur = dur;
          const src = cleanText(m.source != null ? String(m.source) : "", 24);
          if (src) part.source = src;
          part.lastSeen = Date.now();
          room.lastActivityAt = part.lastSeen;
        }
        return;
      }
      case "source": {
        // room-wide mirror ("server") switch: controllers drive it, the room's
        // timeline is NEVER reset here (switching source mid-episode keeps the
        // room where it is). Protocol parity with party-server.
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found", for: "source" });
        const part = room.participants.get(m0.id);
        if (!part || !part.connected) return this.send(ws, { t: "error", error: "not_in_room", for: "source" });
        if (room.hostId !== m0.id && !room.allowControl) return this.send(ws, { t: "error", error: "unauthorized", for: "source" });
        const mirror = cleanText(m.mirror != null ? String(m.mirror) : "", 24);
        if (!mirror) return this.send(ws, { t: "error", error: "bad_message" });
        room.source = mirror;
        room.lastActivityAt = Date.now();
        this.broadcast(m0.roomId, [{ t: "source", room: room.id, mirror: room.source, actor: part.name }]);
        return;
      }
      default:
        return this.send(ws, { t: "error", error: "bad_message" });
    }
  }

  async webSocketClose(ws) {
    const m = this.meta(ws);
    if (!m) return;
    if (!m.roomId) {
      // a socket that never joined still owns a rate-limit entry - drop it
      // here since no room drop will ever sweep it
      this.msgLim.delete(m.id);
      return;
    }
    const room = this.rooms.get(m.roomId);
    const part = room && room.participants.get(m.id);
    if (!part) return;
    // stale close: the seat already moved to a NEWER connection (reattach
    // replaced the ghost) - the old socket dying must never evict the live one
    if (part.cid && m.cid && part.cid !== m.cid) return;
    part.connected = false;
    part.lastSeen = Date.now();
    if (room.hostId === m.id) room.hostDisconnectedAt = part.lastSeen;
    this.broadcast(m.roomId, [{ t: "peer_joined", room: room.id, participant: { ...this.pub(part, room), host: m.id === room.hostId }, updated: true }], m.id);
    this.armAlarm();
  }

  async webSocketError(ws) { await this.webSocketClose(ws); }

  /* ---------- rooms ---------- */

  limFor(id) {
    let l = this.msgLim.get(id);
    if (!l) { l = { msg: new Lim(LIMITS.MSG_WINDOW_MS, LIMITS.MSG_MAX), chat: new Lim(LIMITS.CHAT_WINDOW_MS, LIMITS.CHAT_RATE_MAX) }; this.msgLim.set(id, l); }
    return l;
  }

  makeCode() {
    for (let i = 0; i < 50; i++) {
      const b = crypto.getRandomValues(new Uint8Array(6));
      let code = "";
      for (const x of b) code += CODE_ALPHABET[x % CODE_ALPHABET.length];
      if (!this.byCode.has(code)) return code;
    }
    throw new Error("code space exhausted");
  }

  createRoom(host, m) {
    const now = Date.now();
    const code = this.makeCode();
    const room = {
      id: uid("r"), code,
      name: cleanText(m.roomName, LIMITS.ROOM_NAME_MAX) || `${host.name}'s party`,
      password: typeof m.password === "string" && m.password ? String(m.password).slice(0, 64) : "",
      maxParticipants: Math.min(LIMITS.MAX_PARTICIPANTS, Math.max(2, parseInt(m.maxParticipants, 10) || LIMITS.DEFAULT_PARTICIPANTS)),
      allowControl: m.allowControl === true,
      chatEnabled: m.chatEnabled !== false,
      hostId: host.id,
      content: null,
      source: "", // room-wide mirror ("server") - follows the last controller switch
      playback: { status: "waiting", position: 0, rate: 1, updatedAt: now, sessionVersion: 1, seq: 0 },
      participants: new Map(),
      recentSeats: new Map(),
      chat: [], chatSeq: 0,
      createdAt: now, lastActivityAt: now, hostDisconnectedAt: 0, ended: false,
    };
    room.participants.set(host.id, this.mkPart(host, now));
    this.rooms.set(room.id, room);
    this.byCode.set(code, room.id);
    return room;
  }

  joinRoom(p, code, password, token) {
    const now = Date.now();
    if (code && !this.codeTries.allow(p.ip || p.id, now)) return { error: "rate_limited" };
    if (!CODE_RE.test(code)) return { error: "not_found" };
    const room = this.rooms.get(this.byCode.get(code) || "");
    if (!room || room.ended) return { error: "not_found" };
    if (token) {
      for (const [id, part] of room.participants) {
        if (part.token === token) {
          // same token = same seat: REATTACH and replace the connection. A
          // half-open ghost socket must never force the same person onto a
          // fresh seat (duplicate "you"s, host loss, full-room bounces).
          const replaced = !!(part.cid && p.cid && part.cid !== p.cid);
          part.connected = true; part.lastSeen = now;
          part.cid = p.cid;
          p.id = id;
          if (id === room.hostId) room.hostDisconnectedAt = 0;
          // the SEAT's token is the client's identity across reconnects -
          // never the per-connection token (that rotated every reconnect and
          // stranded the next one on a fresh seat: ghost duplicates, host
          // loss, password lockout)
          return { room, reattached: id, token: part.token, replaced };
        }
      }
      const saved = room.recentSeats.get(token);
      if (saved && !saved.kicked) {
        room.recentSeats.delete(token);
        const part = this.mkPart(p, now);
        part.id = saved.id;
        part.token = token;
        part.name = saved.name || part.name;
        room.participants.set(saved.id, part);
        p.id = saved.id;
        const events = [];
        if (saved.wasHost) {
          // reclaiming the host seat is a host change when someone else was
          // promoted meanwhile - every client must hear it or their UI keeps
          // a stale host (and the returning host's controls get rejected)
          if (room.hostId !== saved.id) {
            events.push({ t: "host_changed", room: room.id, hostId: saved.id, hostName: part.name });
          }
          room.hostId = saved.id;
          room.hostDisconnectedAt = 0;
        }
        return { room, reattached: saved.id, token, events };
      }
    }
    if (room.password && password !== room.password) return { error: "wrong_password" };
    const connected = [...room.participants.values()].filter((x) => x.connected).length;
    if (connected >= room.maxParticipants) return { error: "full" };
    room.participants.set(p.id, this.mkPart(p, now));
    return { room, token: p.token };
  }

  mkPart(p, now) {
    return { id: p.id, name: cleanName(p.name), token: p.token, cid: p.cid || "", joinedAt: now, lastSeen: now, connected: true, muted: false, buffering: false, position: 0, paused: true, dur: 0, source: "" };
  }

  removeParticipant(roomId, participantId, reason) {
    const room = this.rooms.get(roomId);
    if (!room) return [];
    const part = room.participants.get(participantId);
    if (!part) return [];
    room.participants.delete(participantId);
    room.recentSeats.set(part.token, {
      id: part.id, name: part.name, wasHost: room.hostId === participantId,
      removedAt: Date.now(), kicked: reason === "kicked", reason,
    });
    room.lastActivityAt = Date.now();
    if (room.hostId === participantId && !room.ended && !room.hostDisconnectedAt) room.hostDisconnectedAt = Date.now();
    return [{ t: "peer_left", room: room.id, participantId, name: part.name, reason }];
  }

  dropRoom(room) {
    this.rooms.delete(room.id);
    this.byCode.delete(room.code);
    for (const [k, l] of this.msgLim) if (k.startsWith("p") && !this.findPart(k)) this.msgLim.delete(k);
  }

  findPart(id) {
    for (const room of this.rooms.values()) if (room.participants.has(id)) return true;
    return false;
  }

  pub(part, room) {
    return { id: part.id, name: part.name, connected: part.connected, muted: part.muted, buffering: part.buffering, position: part.position, paused: part.paused, dur: part.dur || 0, source: part.source || "" };
  }

  pbState(room, now) {
    return {
      status: room.playback.status,
      position: expectedPosition(room.playback, now),
      rate: room.playback.rate,
      // future stamps are the scheduled-start gate and must survive
      updatedAt: room.playback.updatedAt > now ? room.playback.updatedAt : now,
      sessionVersion: room.playback.sessionVersion,
    };
  }

  snapshot(room, selfId) {
    const now = Date.now();
    return {
      roomId: room.id, code: room.code, name: room.name,
      hostId: room.hostId, selfId,
      hasPassword: !!room.password,
      maxParticipants: room.maxParticipants,
      allowControl: room.allowControl, chatEnabled: room.chatEnabled,
      content: room.content,
      source: room.source || "",
      playback: { ...this.pbState(room, now), seq: room.playback.seq },
      participants: [...room.participants.values()].map((p) => ({ ...this.pub(p, room), host: p.id === room.hostId })),
      chat: room.chat.slice(-LIMITS.CHAT_HISTORY),
      serverTs: now,
    };
  }

  /* ---------- TTL sweep (alarm-driven) ---------- */

  armAlarm() {
    try { this.state.storage.setAlarm(Date.now() + 10000); } catch (e) {}
  }

  async alarm() {
    const now = Date.now();
    for (const room of [...this.rooms.values()]) {
      if (room.ended) { this.dropRoom(room); continue; }
      if (now - room.createdAt > LIMITS.MAX_ROOM_AGE_MS) {
        room.ended = true;
        this.broadcast(room.id, [{ t: "room_ended", roomId: room.id, reason: "expired" }]);
        this.dropRoom(room);
        continue;
      }
      for (const [id, part] of [...room.participants]) {
        // half-open sockets never fire close: silence counts as gone so a
        // ghost can neither hold a seat "connected" nor fill the room
        if (part.connected && now - part.lastSeen > LIMITS.PARTICIPANT_STALE_MS) {
          part.connected = false;
          if (room.hostId === id && !room.hostDisconnectedAt) room.hostDisconnectedAt = now;
        }
        if (!part.connected && now - part.lastSeen > LIMITS.PARTICIPANT_TTL_MS) {
          const evs = this.removeParticipant(room.id, id, "timeout");
          if (evs.length) this.broadcast(room.id, evs);
        }
      }
      for (const [tok, s] of [...room.recentSeats]) {
        if (now - s.removedAt > LIMITS.RECENT_SEAT_MS) room.recentSeats.delete(tok);
      }
      // a dropped member's restorable seat keeps the ROOM alive for the return
      // window: both phones losing signal for a minute must never end the party
      const restorable = [...room.recentSeats.values()].some(
        (s) => !s.kicked && s.reason !== "left" && now - s.removedAt <= LIMITS.RECENT_SEAT_MS);
      const host = room.participants.get(room.hostId);
      if (!host || !host.connected) {
        if (!room.hostDisconnectedAt) room.hostDisconnectedAt = now;
        else if (now - room.hostDisconnectedAt > LIMITS.HOST_GRACE_MS) {
          const next = [...room.participants.values()].filter((p) => p.connected).sort((a, b) => a.joinedAt - b.joinedAt)[0];
          if (next) {
            room.hostId = next.id;
            room.hostDisconnectedAt = 0;
            this.broadcast(room.id, [{ t: "host_changed", room: room.id, hostId: next.id, hostName: next.name }]);
          } else if (room.participants.size === 0 && !restorable) {
            room.ended = true;
            this.broadcast(room.id, [{ t: "room_ended", roomId: room.id, reason: "abandoned" }]);
            this.dropRoom(room);
          }
        }
      } else {
        room.hostDisconnectedAt = 0;
      }
      if (room.participants.size === 0 && !restorable && now - room.lastActivityAt > LIMITS.EMPTY_ROOM_TTL_MS) {
        room.ended = true;
        this.dropRoom(room);
      }
    }
    this.codeTries.sweep(now);
    this.createLim.sweep(now);
    // msgLim entries for ids that are no longer anywhere (never joined a room,
    // or their room was dropped) used to accumulate forever under reconnect
    // storms - the sweep in dropRoom only ran when a room happened to die.
    for (const id of [...this.msgLim.keys()]) {
      let alive = false;
      for (const room of this.rooms.values()) {
        if (room.participants.has(id)) { alive = true; break; }
      }
      if (!alive) this.msgLim.delete(id);
    }
    if (this.rooms.size > 0) this.armAlarm();
  }
}
