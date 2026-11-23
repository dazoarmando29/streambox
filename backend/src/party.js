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
  SYNC_REPORT_MIN_MS: 4000,
  // player-state reports (loading/ready/buffering/...) move fast but stay bounded
  PSTATE_WINDOW_MS: 5000,
  PSTATE_MAX: 12,
  // coordinated episode transitions (readiness-gated shared start)
  START_DELAY_MS: 8000,        // fallback gate: room starts this long after a content change AT THE LATEST
  TRANSITION_MIN_MS: 3000,     // loading reports may arrive this long before a start can be scheduled
  START_COUNTDOWN_MS: 2500,    // countdown baked into a readiness-scheduled start
  READY_TIMEOUT_DEFAULT_MS: 8000,
  READY_TIMEOUT_MIN_MS: 4000,
  READY_TIMEOUT_MAX_MS: 120000,
  // wait-for-everyone buffering holds
  HOLD_TIMEOUT_DEFAULT_MS: 20000,
  HOLD_TIMEOUT_MIN_MS: 5000,
  HOLD_TIMEOUT_MAX_MS: 120000,
  // party social layer bounds
  MEDIA_FIELDS: 48,        // per-field cap for media version identifiers
  QUEUE_MAX: 12,
  PENDING_REQUESTS_MAX: 8,
  REACT_WINDOW_MS: 10000,
  REACT_MAX: 20,
  INTERMISSION_MAX_MS: 15 * 60 * 1000,
  INVITE_TTL_MAX_MS: 7 * 24 * 60 * 60 * 1000,
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
  // per-IP room creation: aligned with party-server/server.js (5/min) - the
  // old 3/hour blocked households behind one NAT from starting parties (and
  // diverged from the Node reference). Mass-creation stays bounded by
  // MAX_ROOMS + empty-room TTL + the code-guessing lockout.
  CREATE_WINDOW_MS: 60 * 1000,
  CREATE_MAX: 5,
};

const START_DELAY_MS = LIMITS.START_DELAY_MS; // fallback gate: episode changes start together on the server clock
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_RE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/;

/* Per-participant player states (never one status for the whole room).
   "recovered" = was buffering and is playable again; "error"/"disconnected"
   never block the room. */
const PSTATE_VALUES = new Set(["loading", "ready", "playing", "paused", "buffering", "recovered", "error", "disconnected"]);
/* Player states that must NOT hold back a readiness-scheduled start or a
   wait-for-everyone hold release (a dead source must never trap the room). */
const PSTATE_BLOCKING = new Set(["loading", "buffering"]);
// buffering policy: "move" (default - party keeps moving) | "everyone"
// (host-enabled hold-until-ready, bounded by a timeout + host override)
const WAIT_MODES = new Set(["move", "everyone"]);
/* Roles: host owns the room; co-host shares playback/episode/moderation;
   everyone else is a participant (gated by allowControl + per-guest perms). */
const ROLES = new Set(["host", "cohost", "participant"]);
/* Granular guest permissions (server-enforced; co-hosts bypass, host only
   does host-only ops: end/roles/lock/settings). */
const PERM_KEYS = ["play", "seek", "episode", "queue", "moderate", "policy"];
const REACTIONS = new Set(["laugh", "surprise", "love", "shock", "applause"]);
const REQUEST_ACTIONS = new Set(["pause", "rewind", "skip_intro", "next_episode", "change_episode"]);
const QUEUE_OPS = new Set(["add", "vote", "approve", "remove", "clear"]);

function isStr(v, min, max) {
  return typeof v === "string" && v.length >= min && v.length <= max;
}

function cleanText(s, max) {
  if (typeof s !== "string") return "";
  return s.replace(/[\u0000-\u001f\u007f]/g, " ").replace(/\s+/g, " ").trim().slice(0, max);
}
function cleanName(s) { return cleanText(s, LIMITS.NAME_MAX) || "Guest"; }

/* Authoritative media-version config. Fields are OPAQUE PROVIDER identifiers
   (the labels/values the provider itself reports - AniPM lang segment, VidBolt
   panel option values) - never guessed languages, never signed media URLs.
   "compat" is advisory: what the sender knows about availability. */
function cleanMedia(m) {
  if (!m || typeof m !== "object") return null;
  const out = {};
  const F = LIMITS.MEDIA_FIELDS;
  for (const k of ["mirror", "variant", "audio", "subs", "subsLang", "release"]) {
    if (m[k] != null) {
      const v = cleanText(String(m[k]), F);
      if (v) out[k] = v;
    }
  }
  if (m.subsOn != null) out.subsOn = m.subsOn === true;
  return Object.keys(out).length ? out : null;
}

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

function randHex(n) {
  const b = crypto.getRandomValues(new Uint8Array(n));
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}

/* password check mirrors party-server rooms.js hashPassword: sha256 of
   salt + "\0" + password, per-room salt (async here - Workers only ship the
   WebCrypto async digests, the digest INPUT is identical) */
async function hashPassword(password, salt) {
  const data = new TextEncoder().encode(salt + "\u0000" + password);
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
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
    this.msgLim = new Map();  // participantId -> { msg, chat, sync, pstate }
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
    // proto: 2 marks the readiness-gated protocol (pstate/schedule/gate fields)
    this.send(server, { t: "welcome", participantId: meta.id, token: meta.token, serverTs: Date.now(), proto: 2 });
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

    // refresh liveness for any traffic (party-server markSeen) - a
    // heartbeat-only client must never be evicted as stale. cid-guarded: a
    // replaced ghost socket never refreshes the live seat
    if (m0.roomId) this.markSeen(m0.roomId, m0.id, m0);
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
        if (!this.createLim.allow(m0.ip || m0.id)) return this.send(ws, { t: "error", error: "rate_limited", for: "create" });
        if (this.rooms.size >= LIMITS.MAX_ROOMS) return this.send(ws, { t: "error", error: "full" });
        m0.name = cleanName(m.name);
        const room = await this.createRoom(m0, m);
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
        const r = await this.joinRoom(m0, code, typeof m.password === "string" ? m.password : "",
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
        const r = this.end(m0.roomId, m0.id);
        if (r.error) return this.send(ws, { t: "error", error: r.error });
        const rid = m0.roomId;
        // broadcast BEFORE detaching: the host who ends the room must ALSO
        // hear room_ended (party-server broadcasts to everyone incl. sender)
        this.broadcast(rid, r.events);
        m0.roomId = ""; ws.serializeAttachment(m0);
        if (room) this.dropRoom(room);
        return;
      }
      case "playback": {
        const r = this.playback(m0.roomId, m0.id, m);
        if (r.error) {
          // wire shape preserved: validation failures stay bare, room-state
          // failures carry for:"playback"
          return this.send(ws, r.error === "bad_message"
            ? { t: "error", error: r.error }
            : { t: "error", error: r.error, for: "playback" });
        }
        if (r.stale) return this.send(ws, { t: "notice", notice: "stale_playback" });
        if (r.events && r.events.length) this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "content": {
        const type = m.type === "movie" ? "movie" : "tv";
        const id = parseInt(m.tmdbId, 10);
        if (!Number.isFinite(id) || id <= 0) return this.send(ws, { t: "error", error: "bad_message" });
        const r = this.content(m0.roomId, m0.id, {
          type, tmdbId: id,
          season: Math.max(1, parseInt(m.season, 10) || 1),
          episode: Math.max(1, parseInt(m.episode, 10) || 1),
        });
        if (r.error) {
          return this.send(ws, r.error === "bad_message"
            ? { t: "error", error: r.error }
            : { t: "error", error: r.error, for: "content" });
        }
        this.broadcast(m0.roomId, r.events);
        this.armAlarm();
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
        // additive social fields: replies, spoiler flag, scene position
        if (isStr(m.replyTo, 1, 24)) msg.replyTo = m.replyTo;
        if (m.spoiler === true) msg.spoiler = true;
        const pos = Number(m.pos);
        if (Number.isFinite(pos) && pos >= 0 && pos <= 24 * 3600) msg.pos = Math.floor(pos);
        room.chat.push(msg);
        if (room.chat.length > LIMITS.CHAT_HISTORY) room.chat.shift();
        this.broadcast(m0.roomId, [{ t: "chat", msg }]);
        return;
      }
      case "media": {
        // AUTHORITATIVE media version: the host's intended source variant
        // (dub/sub, audio track, subtitle track, provider variant id). This is
        // what every participant loads FIRST - local defaults never win over
        // it. Values are opaque provider identifiers (labels/values the
        // provider itself reports) - never signed media URLs.
        const r = this.media(m0.roomId, m0.id, cleanMedia(m.media));
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "media" });
        this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "role": {
        // host promotes/demotes co-hosts (validated like protocol.js `role`)
        if (!isStr(m.targetId, 1, 64)) return this.send(ws, { t: "error", error: "bad_message" });
        const role = m.role === "cohost" ? "cohost" : "participant";
        const r = this.role(m0.roomId, m0.id, m.targetId, role);
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "role" });
        this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "request": {
        // guest playback request (validated like protocol.js `request`)
        if (!REQUEST_ACTIONS.has(m.action)) return this.send(ws, { t: "error", error: "bad_message" });
        const req = { t: "request", action: m.action };
        const rp = Number(m.position);
        if (Number.isFinite(rp) && rp >= 0 && rp <= 24 * 3600) req.position = Math.floor(rp);
        if (isStr(m.note, 1, 80)) req.note = cleanText(m.note, 80);
        const r = this.request(m0.roomId, m0.id, req);
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "request" });
        this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "request_reply": {
        // host/co-host approves or declines (validated like protocol.js)
        if (!isStr(m.requestId, 1, 24)) return this.send(ws, { t: "error", error: "bad_message" });
        const r = this.requestReply(m0.roomId, m0.id, { requestId: m.requestId, approved: m.approved === true });
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "request_reply" });
        this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "react": {
        // lightweight live reactions (burst) or message emoji reactions
        // (validated like protocol.js `react`): a msgId variant requires
        // REACTIONS membership
        let rr;
        if (isStr(m.msgId, 1, 24) && REACTIONS.has(m.emoji)) {
          rr = { msgId: m.msgId, emoji: m.emoji };
        } else {
          if (!REACTIONS.has(m.emoji)) return this.send(ws, { t: "error", error: "bad_message" });
          rr = { emoji: m.emoji };
        }
        // reactions share the chat limiter, dropped silently (party-server)
        if (!lim.chat.allow("r", Date.now())) return;
        const r = this.react(m0.roomId, m0.id, rr);
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "react" });
        this.broadcast(m0.roomId, r.events);
        return;
      }
      case "queue": {
        // shared watch queue (validated like protocol.js `queue`)
        if (!QUEUE_OPS.has(m.op)) return this.send(ws, { t: "error", error: "bad_message" });
        const qm = { t: "queue", op: m.op };
        if (m.op === "add") {
          const type = m.item && m.item.type === "movie" ? "movie" : "tv";
          const id = m.item && parseInt(m.item.tmdbId, 10);
          if (!Number.isFinite(id) || id <= 0) return this.send(ws, { t: "error", error: "bad_message" });
          qm.item = {
            type,
            tmdbId: id,
            season: Math.max(1, parseInt(m.item.season, 10) || 1),
            episode: Math.max(1, parseInt(m.item.episode, 10) || 1),
            title: cleanText(m.item.title || "", 60),
          };
        } else if (isStr(m.itemId, 1, 24)) {
          qm.itemId = m.itemId;
        }
        const r = this.queue(m0.roomId, m0.id, qm);
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "queue" });
        this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "intermission": {
        // group intermission (validated like protocol.js `intermission`)
        const op = m.op === "end" ? "end" : "start";
        const im = { t: "intermission", op };
        if (op === "start") {
          const sec = parseInt(m.seconds, 10);
          im.seconds = Math.min(900, Math.max(30, Number.isFinite(sec) ? sec : 300));
        }
        const r = this.intermission(m0.roomId, m0.id, im);
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "intermission" });
        this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "kick": {
        const room = this.rooms.get(m0.roomId);
        if (!room || room.ended) return this.send(ws, { t: "error", error: "not_found" });
        const role = this._roleOf(room, m0.id);
        if (role === "participant") return this.send(ws, { t: "error", error: "unauthorized" });
        const targetId = String(m.targetId || "");
        if (!targetId || targetId === room.hostId || !room.participants.has(targetId)) return this.send(ws, { t: "error", error: "bad_message" });
        // a co-host can never remove another co-host (only the host manages roles)
        if (role === "cohost" && room.cohosts.has(targetId)) return this.send(ws, { t: "error", error: "unauthorized" });
        room.cohosts.delete(targetId);
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
        const role = this._roleOf(room, m0.id);
        if (role === "participant") return this.send(ws, { t: "error", error: "unauthorized" });
        const targetId = String(m.targetId || "");
        const target = room.participants.get(targetId);
        if (!target || targetId === room.hostId) return this.send(ws, { t: "error", error: "bad_message" });
        // a co-host can never mute another co-host (only the host manages roles)
        if (role === "cohost" && room.cohosts.has(targetId)) return this.send(ws, { t: "error", error: "unauthorized" });
        target.muted = m.muted === true;
        this.broadcast(m0.roomId, [{ t: "peer_joined", room: room.id, participant: { ...this.pub(target, room), host: target.id === room.hostId }, updated: true }]);
        return;
      }
      case "settings": {
        // normalized like party-server protocol.js settings (additive fields)
        const patch = {};
        if (m.allowControl != null) patch.allowControl = m.allowControl === true;
        if (m.chatEnabled != null) patch.chatEnabled = m.chatEnabled === true;
        if (m.roomName != null) patch.roomName = cleanText(m.roomName, LIMITS.ROOM_NAME_MAX);
        // buffering policy: "move" (default - party keeps moving) | "everyone"
        // (host-enabled hold-until-ready, bounded by a timeout + host override)
        if (m.waitMode != null) patch.waitMode = String(m.waitMode) === "everyone" ? "everyone" : "move";
        if (m.readyTimeoutMs != null) {
          const rt = parseInt(m.readyTimeoutMs, 10);
          if (Number.isFinite(rt)) patch.readyTimeoutMs = Math.min(LIMITS.READY_TIMEOUT_MAX_MS, Math.max(LIMITS.READY_TIMEOUT_MIN_MS, rt));
        }
        if (m.holdTimeoutMs != null) {
          const ht = parseInt(m.holdTimeoutMs, 10);
          if (Number.isFinite(ht)) patch.holdTimeoutMs = Math.min(LIMITS.HOLD_TIMEOUT_MAX_MS, Math.max(LIMITS.HOLD_TIMEOUT_MIN_MS, ht));
        }
        // door state + guest permission matrix (host-only fields, applied in settings())
        if (m.locked != null) patch.locked = m.locked === true;
        if (m.perms != null && typeof m.perms === "object") {
          const perms = {};
          for (const k of PERM_KEYS) if (m.perms[k] != null) perms[k] = m.perms[k] === true;
          if (Object.keys(perms).length) patch.perms = perms;
        }
        const r = this.settings(m0.roomId, m0.id, patch);
        if (r.error) return this.send(ws, { t: "error", error: r.error });
        if (r.events && r.events.length) this.broadcast(m0.roomId, r.events);
        this.armAlarm();
        return;
      }
      case "resync": {
        const room = this.rooms.get(m0.roomId);
        if (!room) return this.send(ws, { t: "error", error: "not_found" });
        this.send(ws, { t: "room_state", room: this.snapshot(room, m0.id) });
        return;
      }
      case "sync_report": {
        // own limiter (party-server lim.sync): reports are observability, a
        // flood is dropped silently
        if (!lim.sync.allow("s", Date.now())) return; // drop silently
        const r = this.syncReport(m0.roomId, m0.id, m);
        if (r && r.events && r.events.length) {
          this.broadcast(m0.roomId, r.events);
          this.armAlarm();
        }
        return;
      }
      case "pstate": {
        // readiness + buffering channel (party-server lim.pstate): fast, and
        // it DRIVES room behavior (transition scheduling, holds)
        if (!PSTATE_VALUES.has(m.st)) return this.send(ws, { t: "error", error: "bad_message" });
        if (!lim.pstate.allow("p", Date.now())) return; // drop silently
        // normalized exactly like party-server protocol.js `pstate`
        const msg = { t: "pstate", st: m.st };
        if (m.position != null) {
          const p = Number(m.position);
          if (Number.isFinite(p) && p >= 0 && p <= 24 * 3600) msg.position = p;
        }
        if (m.dur != null) {
          const d = Number(m.dur);
          if (Number.isFinite(d) && d > 0 && d <= 24 * 3600) msg.dur = d;
        }
        if (m.source != null) {
          const s = cleanText(m.source, 24);
          if (s) msg.source = s;
        }
        if (m.sv != null) {
          const sv = parseInt(m.sv, 10);
          if (Number.isFinite(sv) && sv >= 1) msg.sv = sv;
        }
        const r = this.pstate(m0.roomId, m0.id, msg);
        if (r.error) return this.send(ws, { t: "error", error: r.error, for: "pstate" });
        if (r.events && r.events.length) this.broadcast(m0.roomId, r.events);
        this.armAlarm();
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
    part.pstate = "disconnected"; // a dead player never blocks the room
    if (room.hostId === m.id) room.hostDisconnectedAt = part.lastSeen;
    this.broadcast(m.roomId, [{ t: "peer_joined", room: room.id, participant: { ...this.pub(part, room), host: m.id === room.hostId }, updated: true }], m.id);
    this.armAlarm();
  }

  async webSocketError(ws) { await this.webSocketClose(ws); }

  /* ---------- rooms ---------- */

  limFor(id) {
    let l = this.msgLim.get(id);
    if (!l) {
      l = {
        msg: new Lim(LIMITS.MSG_WINDOW_MS, LIMITS.MSG_MAX),
        chat: new Lim(LIMITS.CHAT_WINDOW_MS, LIMITS.CHAT_RATE_MAX),
        sync: new Lim(LIMITS.SYNC_REPORT_MIN_MS * 3, 3),
        pstate: new Lim(LIMITS.PSTATE_WINDOW_MS, LIMITS.PSTATE_MAX),
      };
      this.msgLim.set(id, l);
    }
    return l;
  }

  /* any traffic refreshes liveness (party-server markSeen) */
  markSeen(roomId, participantId, meta) {
    const room = this.rooms.get(roomId);
    const part = room && room.participants.get(participantId);
    if (!part) return;
    // a REPLACED ghost socket's leftover traffic never refreshes the live seat
    if (meta && part.cid && meta.cid && part.cid !== meta.cid) return;
    part.lastSeen = Date.now();
    part.connected = true;
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

  async createRoom(host, m) {
    const now = Date.now();
    const code = this.makeCode();
    const room = {
      id: uid("r"), code,
      name: cleanText(m.roomName, LIMITS.ROOM_NAME_MAX) || `${host.name}'s party`,
      passwordHash: "", passwordSalt: "", // per-room salt (party-server rooms.js)
      maxParticipants: Math.min(LIMITS.MAX_PARTICIPANTS, Math.max(2, parseInt(m.maxParticipants, 10) || LIMITS.DEFAULT_PARTICIPANTS)),
      allowControl: m.allowControl === true,
      chatEnabled: m.chatEnabled !== false,
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
      recentSeats: new Map(),
      chat: [], chatSeq: 0,
      createdAt: now, lastActivityAt: now, hostDisconnectedAt: 0, ended: false,
    };
    if (typeof m.password === "string" && m.password) {
      room.passwordSalt = randHex(8);
      room.passwordHash = await hashPassword(String(m.password).slice(0, 64), room.passwordSalt);
    }
    room.participants.set(host.id, this.mkPart(host, now));
    this.rooms.set(room.id, room);
    this.byCode.set(code, room.id);
    return room;
  }

  async joinRoom(p, code, password, token) {
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
    if (room.passwordHash && (await hashPassword(password || "", room.passwordSalt)) !== room.passwordHash) return { error: "wrong_password" };
    // locked rooms accept no NEW members (invites stop working) - returning
    // participants keep reattaching via their tokens above
    if (room.locked) return { error: "closed" };
    const connected = [...room.participants.values()].filter((x) => x.connected).length;
    if (connected >= room.maxParticipants) return { error: "full" };
    room.participants.set(p.id, this.mkPart(p, now));
    return { room, token: p.token };
  }

  mkPart(p, now) {
    return {
      id: p.id, name: cleanName(p.name), token: p.token, cid: p.cid || "",
      joinedAt: now, lastSeen: now, connected: true, muted: false, buffering: false, position: 0, paused: true, dur: 0, source: "",
      // per-participant player state (never one status for the whole room)
      pstate: "ready", pstateSv: 0,
    };
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

  /* ---------- room state machine (1:1 port of party-server rooms.js) ---------- */

  /* host-initiated room close: the room object survives long enough for the
     broadcast to reach EVERYONE - the host who pressed end hears room_ended */
  end(roomId, participantId) {
    const room = this.rooms.get(roomId);
    if (!room) return { error: "not_found" };
    if (room.hostId !== participantId) return { error: "unauthorized" };
    room.ended = true;
    return { events: [{ t: "room_ended", roomId: room.id, reason: "host_ended" }] };
  }

  /* playback command (party-server rooms.js playback()): the server is
     authoritative - every command is validated and permission-checked HERE.
     msg = raw client message ({action, position, rate, sessionVersion}).
     Returns { error } | { stale: true } | { buffered, events } | { events }. */
  playback(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    const action = String(msg.action || "");
    // granular gate (rooms.js _canControl): seek / play+rate are separate
    // permission classes for guests - host + co-host always control
    const kind = action === "seek" ? "seek" : action === "rate" ? "rate" : "play";
    if (!this._canControl(room, part, kind)) return { error: "unauthorized" };
    // stale guard: a playback action stamped for a previous episode/session
    // must never affect the current one
    const sv = msg.sessionVersion != null ? parseInt(msg.sessionVersion, 10) : null;
    if (sv != null && sv !== room.playback.sessionVersion) return { stale: true };
    const pos = msg.position != null ? Number(msg.position) : null;
    if (pos != null && (!Number.isFinite(pos) || pos < 0 || pos > 24 * 3600)) return { error: "bad_message" };
    const now = Date.now();
    this._sweepGate(room, now);
    const pb = room.playback;
    const cur = expectedPosition(pb, now);
    const events = [];
    switch (action) {
      case "play":
        // host/controller override: a play always releases a wait hold and
        // ends a pending transition (nobody waits forever)
        if (room.hold) events.push(...this._releaseHold(room, now, "override"));
        if (room.gate && room.gate.phase === "loading") {
          events.push(...this._finalizeStart(room, now + 500, "host_override"));
        }
        pb.status = "playing";
        pb.position = pos != null ? pos : cur;
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
        pb.position = pos != null ? pos : cur;
        part.pstate = "paused";
        break;
      case "seek":
        if (pos == null) return { error: "bad_message" };
        // a seek during a pending transition is a host override of the gate too
        if (room.gate && room.gate.phase === "loading" && pos > 0) {
          events.push(...this._finalizeStart(room, now, "host_override"));
        }
        pb.position = pos;
        break;
      case "rate": {
        const r = Number(msg.rate);
        if (!Number.isFinite(r) || r < 0.25 || r > 3) return { error: "bad_message" };
        pb.position = cur;
        pb.rate = r;
        break;
      }
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
      action,
      state: this._pbState(room, now),
    });
    events.push(...this._maybeReleaseHold(room, now));
    return { events };
  }

  /* episode change (host-only) - coordinated transition, ported 1:1 from
     party-server rooms.js content(). Lifecycle:
       1. new content session (sessionVersion+1) - every earlier event is stale
       2. clients unload the old episode and start loading the new one,
          reporting player state (loading -> ready) per participant
       3. when the reported blockers are done - or the readiness timeout
          expires - the shared start is scheduled on the SERVER clock
       4. slow/late participants catch up to the room timeline once ready
     The initial gate (updatedAt in the future) doubles as the fallback for
     older clients that know nothing of readiness: they hold at 00:00 until
     the gate exactly as before. The finalized gate is re-broadcast as a
     second `content` event (same identity) which every client generation
     folds into its existing gate logic (no reload - same content key). */
  content(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room || room.ended) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part || !part.connected) return { error: "not_in_room" };
    if (!this._canControl(room, part, "episode")) return { error: "unauthorized" }; // host/cohost/permed
    const now = Date.now();
    this._sweepGate(room, now); // lazy deadline enforcement (the gate is replaced below anyway)
    room.content = { type: msg.type, tmdbId: msg.tmdbId, season: msg.season, episode: msg.episode };
    const timeout = Math.min(LIMITS.READY_TIMEOUT_MAX_MS,
      Math.max(LIMITS.READY_TIMEOUT_MIN_MS, room.readyTimeoutMs || LIMITS.READY_TIMEOUT_DEFAULT_MS));
    // the shared gate lands on the SERVER clock: updatedAt in the future keeps
    // expectedPosition clamped at 0 until the room starts together
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

  /* room settings (host + co-host) - party-server rooms.js settings(): the
     buffering policy + the two timeouts ride the same event; `locked` and the
     guest permission matrix are host-only */
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
        return {
          events: [ev(), ...this._releaseHold(room, Date.now(), "ready")],
        };
      }
    }
    if (patch.readyTimeoutMs != null && Number.isFinite(patch.readyTimeoutMs)) {
      room.readyTimeoutMs = Math.min(LIMITS.READY_TIMEOUT_MAX_MS, Math.max(LIMITS.READY_TIMEOUT_MIN_MS, patch.readyTimeoutMs));
    }
    if (patch.holdTimeoutMs != null && Number.isFinite(patch.holdTimeoutMs)) {
      room.holdTimeoutMs = Math.min(LIMITS.HOLD_TIMEOUT_MAX_MS, Math.max(LIMITS.HOLD_TIMEOUT_MIN_MS, patch.holdTimeoutMs));
    }
    return {
      events: [ev()],
    };
  }

  /* periodic client drift report - observability only, never rebroadcast.
     NOTE divergence from party-server protocol.js: an invalid position is
     CLAMPED to 0 here (deployed old clients send junk and relied on that),
     while party-server rejects the whole message. */
  syncReport(roomId, participantId, msg) {
    const room = this.rooms.get(roomId);
    if (!room) return { error: "not_found" };
    const part = room.participants.get(participantId);
    if (!part) return { error: "not_in_room" };
    const pos = Number(msg.position);
    part.position = Number.isFinite(pos) && pos >= 0 && pos <= 24 * 3600 ? pos : 0;
    part.paused = msg.paused === true;
    part.buffering = false;
    // additive: duration + source name let members see real peer state and
    // detect different cuts (sub vs dub / two mirrors)
    const dur = Number(msg.dur);
    if (Number.isFinite(dur) && dur > 0 && dur <= 24 * 3600) part.dur = dur;
    const src = cleanText(msg.source != null ? String(msg.source) : "", 24);
    if (src) part.source = src;
    // additive: session stamp + player state ride along when a newer client
    // reports - a report stamped for a previous episode must not mark anyone
    // "ready" for the current one (stale-readiness guard)
    const pmsg = { position: part.position, paused: part.paused };
    if (Number.isFinite(dur) && dur > 0 && dur <= 24 * 3600) pmsg.dur = dur;
    if (src) pmsg.source = src;
    if (msg.sv != null) {
      const sv = parseInt(msg.sv, 10);
      if (Number.isFinite(sv) && sv >= 1) pmsg.sv = sv;
    }
    if (msg.st != null && PSTATE_VALUES.has(msg.st)) pmsg.st = msg.st;
    if (pmsg.st) {
      // the readiness + buffering channel rides the report: same handler
      return this.pstate(roomId, participantId, pmsg);
    }
    part.lastSeen = Date.now();
    room.lastActivityAt = part.lastSeen;
    return { events: [] }; // reports are observability only - never rebroadcast
  }

  /* Player-state channel (readiness + buffering) - ported 1:1 from
     party-server rooms.js pstate(). This is what makes episode starts
     coordinated and slow networks recoverable: every controllable player
     reports loading/ready/playing/paused/buffering/recovered/error per
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
    const now = Date.now();
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
      events.push(...this._maybeCreateHold(room, participantId, now));
    }
    events.push(...this._maybeReleaseHold(room, now));
    events.push(...this._maybeScheduleStart(room, now));
    return { events };
  }

  /* ---- protocol v2.1: authoritative media + party social layer
     (1:1 port of party-server rooms.js) ---- */

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
    room.media = { ...clean, by: part.name, at: Date.now() };
    room.lastActivityAt = Date.now();
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
    room.lastActivityAt = Date.now();
    const target = room.participants.get(targetId);
    return {
      events: [
        { t: "role", room: room.id, participantId: targetId, role: this._roleOf(room, targetId), actor: target.name },
        { t: "peer_joined", room: room.id, participant: { ...this.pub(target, room), host: targetId === room.hostId, role: this._roleOf(room, targetId) }, updated: true },
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
      at: Date.now(),
    };
    room.requests.set(req.id, req);
    room.lastActivityAt = Date.now();
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
    const now = Date.now();
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
    room.lastActivityAt = Date.now();
    return {
      events: [{
        t: "react", room: room.id, emoji: msg.emoji,
        fromId: participantId, name: part.name, msgId: msg.msgId || null, ts: Date.now(),
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
    const now = Date.now();
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
    const now = Date.now();
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

  /* ---- coordinated transitions + buffering holds (rooms.js helpers) ---- */

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
  _maybeScheduleStart(room, now = Date.now()) {
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
    const now = Date.now();
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
    return [
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

  /* A scheduled gate becomes "live" the moment its start time is behind us -
     otherwise a legit episode-end later in the session would look stale. */
  _sweepGate(room, now) {
    const gate = room.gate;
    if (gate && gate.phase === "scheduled" && gate.startAt != null && now >= gate.startAt) {
      gate.phase = "live";
    }
  }

  /* deadline tick (party-server rooms.js tick()): transitions + holds resolve
     on the alarm AND lazily on every command - correctness never depends on a
     bare timer alone. Returns { roomId, events }[] batches like the Node
     manager so the caller broadcasts them. */
  tick(now = Date.now()) {
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

  pub(part, room) {
    return {
      id: part.id, name: part.name, connected: part.connected, muted: part.muted, buffering: part.buffering, position: part.position, paused: part.paused, dur: part.dur || 0, source: part.source || "",
      // per-participant player state (loading/ready/playing/buffering/...)
      pstate: part.pstate || "ready",
    };
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

  snapshot(room, selfId) {
    const now = Date.now();
    this._sweepGate(room, now);
    return {
      roomId: room.id, code: room.code, name: room.name,
      hostId: room.hostId, selfId,
      hasPassword: !!room.passwordHash,
      maxParticipants: room.maxParticipants,
      allowControl: room.allowControl, chatEnabled: room.chatEnabled,
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
      playback: { ...this._pbState(room, now), seq: room.playback.seq },
      participants: [...room.participants.values()].map((p) => ({ ...this.pub(p, room), host: p.id === room.hostId, role: this._roleOf(room, p.id) })),
      chat: room.chat.slice(-LIMITS.CHAT_HISTORY),
      serverTs: now,
    };
  }

  /* ---------- TTL sweep (alarm-driven) ---------- */

  armAlarm() {
    // the alarm doubles as the deadline tick: wake at the next gate deadline
    // or hold expiry too, never later than the 10s TTL cadence
    let at = Date.now() + 10000;
    for (const room of this.rooms.values()) {
      if (room.ended) continue;
      const pending = [
        room.gate && room.gate.phase === "loading" ? room.gate.deadline : null,
        room.hold ? room.hold.until : null,
        room.intermission ? room.intermission.until : null,
      ];
      for (const t of pending) {
        if (t != null && t < at) at = t;
      }
    }
    try { this.state.storage.setAlarm(at); } catch (e) {}
  }

  async alarm() {
    // deadline tick first (party-server tick()): coordinated starts and
    // wait-for-everyone holds resolve on time even when nobody sends traffic
    // (they also re-evaluate lazily on every command - correctness never
    // depends on the timer alone)
    for (const batch of this.tick()) this.broadcast(batch.roomId, batch.events);
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
