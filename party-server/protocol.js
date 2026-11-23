"use strict";
/* StreamBox Watch Party protocol: message shapes, validation, shared math.
   One protocol for website + Android apps. The server is authoritative for
   room + playback state; clients render and drive their own (per-user)
   playback sessions. No media bytes ever cross this protocol. */

const LIMITS = {
  MAX_MSG_BYTES: 8 * 1024,
  NAME_MAX: 24,
  ROOM_NAME_MAX: 40,
  CHAT_MAX: 300,
  MAX_PARTICIPANTS: 16,
  DEFAULT_PARTICIPANTS: 8,
  // per-connection windows
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
  // join-by-code guessing: per-remote-address
  CODE_ATTEMPT_WINDOW_MS: 5 * 60 * 1000,
  CODE_ATTEMPT_MAX: 12,
  // room lifecycle
  HOST_GRACE_MS: 60 * 1000,
  PARTICIPANT_TTL_MS: 45 * 1000,
  // half-open sockets (no close ever fires) must not pin a seat "connected"
  // forever: this long a silence counts as gone. > 5 missed client heartbeats
  // (20s cadence) so background-tab timer throttling never evicts a viewer.
  PARTICIPANT_STALE_MS: 100 * 1000,
  EMPTY_ROOM_TTL_MS: 120 * 1000,
  MAX_ROOM_AGE_MS: 24 * 60 * 60 * 1000,
  CHAT_HISTORY: 50,
};

// unambiguous alphabet (no 0/O/1/I/L) - room codes readable on a TV
const CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
const CODE_LEN = 6;
const CODE_RE = /^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/;

// client -> server message types
const CLIENT_MSGS = new Set([
  "hello", "create", "join", "leave", "end",
  "playback", "content", "chat", "resync",
  "kick", "mute", "settings", "heartbeat", "sync_report",
  "source", "pstate",
]);

// server -> client message types
const SERVER_MSGS = new Set([
  "welcome", "room", "room_state", "playback", "content", "chat",
  "peer_joined", "peer_left", "host_changed", "settings", "kicked",
  "room_ended", "error", "heartbeat", "notice", "source",
  "peer_state", "schedule",
]);

const ERROR_CODES = new Set([
  "bad_message", "unauthorized", "not_found", "wrong_password", "full",
  "closed", "rate_limited", "room_limit", "not_in_room", "conflict",
]);

const PLAYBACK_ACTIONS = new Set(["play", "pause", "seek", "rate", "ended", "buffer"]);
const ROOM_STATUSES = new Set(["waiting", "loading", "ready", "playing", "paused", "ended", "error"]);
/* Per-participant player states (requirement: track each controllable player,
   never collapse the room into one status). "recovered" = was buffering and is
   playable again; "error"/"disconnected" never block the room. */
const PSTATE_VALUES = new Set(["loading", "ready", "playing", "paused", "buffering", "recovered", "error", "disconnected"]);
/* Player states that must NOT hold back a readiness-scheduled start or a
   wait-for-everyone hold release (a dead source must never trap the room). */
const PSTATE_BLOCKING = new Set(["loading", "buffering"]);
const WAIT_MODES = new Set(["move", "everyone"]);

function isStr(v, min, max) {
  return typeof v === "string" && v.length >= min && v.length <= max;
}

/* Human-safe text: strip control chars, collapse whitespace runs. Chat is
   stored as PLAIN TEXT; clients must escape on render (server never trusts
   clients to sanitize for display). */
function cleanText(s, max) {
  if (typeof s !== "string") return "";
  return s
    .replace(/[\u0000-\u001f\u007f]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, max);
}

function cleanName(s) {
  return cleanText(s, LIMITS.NAME_MAX) || "Guest";
}

/* Authoritative position at server time `now`: playback.position was the
   position at playback.updatedAt (server clock), advancing at `rate` while
   playing. This is THE shared sync formula (clients copy it verbatim). */
function expectedPosition(playback, now) {
  if (!playback) return 0;
  const p = Number(playback.position) || 0;
  if (playback.status !== "playing") return p;
  const dt = (now - (playback.updatedAt || now)) / 1000;
  return Math.max(0, p + dt * (Number(playback.rate) || 1));
}

/* Validate a client message. Returns { ok: true, msg } with a normalized
   message, or { ok: false, error } with a protocol error code. Never throws. */
function validateClientMsg(raw, inRoom) {
  let m;
  try {
    m = typeof raw === "string" ? JSON.parse(raw) : raw;
  } catch (e) {
    return { ok: false, error: "bad_message" };
  }
  if (!m || typeof m !== "object" || typeof m.t !== "string" || !CLIENT_MSGS.has(m.t)) {
    return { ok: false, error: "bad_message" };
  }
  const t = m.t;
  if (inRoom === false && t !== "hello" && t !== "create" && t !== "join" && t !== "heartbeat") {
    return { ok: false, error: "not_in_room" };
  }
  switch (t) {
    case "hello":
      return { ok: true, msg: { t, name: cleanName(m.name) } };
    case "create": {
      const max = Math.min(LIMITS.MAX_PARTICIPANTS, Math.max(2, parseInt(m.maxParticipants, 10) || LIMITS.DEFAULT_PARTICIPANTS));
      return {
        ok: true,
        msg: {
          t,
          name: cleanName(m.name),
          roomName: cleanText(m.roomName, LIMITS.ROOM_NAME_MAX),
          password: typeof m.password === "string" ? m.password.slice(0, 64) : "",
          maxParticipants: max,
          allowControl: m.allowControl === true,
          chatEnabled: m.chatEnabled !== false,
        },
      };
    }
    case "join":
      return {
        ok: true,
        msg: {
          t,
          name: cleanName(m.name),
          code: isStr(m.code, 1, 64) ? String(m.code).trim().toUpperCase() : "",
          roomId: isStr(m.roomId, 1, 64) ? String(m.roomId).trim() : "",
          password: typeof m.password === "string" ? m.password.slice(0, 64) : "",
          token: isStr(m.token, 8, 64) ? String(m.token).trim() : "",
        },
      };
    case "leave":
    case "end":
    case "resync":
      return { ok: true, msg: { t } };
    case "playback": {
      if (!PLAYBACK_ACTIONS.has(m.action)) return { ok: false, error: "bad_message" };
      const msg = { t, action: m.action };
      if (m.position != null) {
        const p = Number(m.position);
        if (!Number.isFinite(p) || p < 0 || p > 24 * 3600) return { ok: false, error: "bad_message" };
        msg.position = p;
      }
      if (m.rate != null) {
        const r = Number(m.rate);
        if (!Number.isFinite(r) || r < 0.25 || r > 3) return { ok: false, error: "bad_message" };
        msg.rate = r;
      }
      if (m.sessionVersion != null) {
        const sv = parseInt(m.sessionVersion, 10);
        if (!Number.isFinite(sv) || sv < 1) return { ok: false, error: "bad_message" };
        msg.sessionVersion = sv;
      }
      return { ok: true, msg };
    }
    case "content": {
      const type = m.type === "movie" ? "movie" : "tv";
      const id = parseInt(m.tmdbId, 10);
      if (!Number.isFinite(id) || id <= 0) return { ok: false, error: "bad_message" };
      return {
        ok: true,
        msg: {
          t,
          type,
          tmdbId: id,
          season: Math.max(1, parseInt(m.season, 10) || 1),
          episode: Math.max(1, parseInt(m.episode, 10) || 1),
        },
      };
    }
    case "chat": {
      const text = cleanText(m.text, LIMITS.CHAT_MAX);
      if (!text) return { ok: false, error: "bad_message" };
      return { ok: true, msg: { t, text } };
    }
    case "kick":
    case "mute": {
      if (!isStr(m.targetId, 1, 64)) return { ok: false, error: "bad_message" };
      return { ok: true, msg: { t, targetId: m.targetId, muted: m.muted === true } };
    }
    case "settings": {
      const msg = { t };
      if (m.allowControl != null) msg.allowControl = m.allowControl === true;
      if (m.chatEnabled != null) msg.chatEnabled = m.chatEnabled === true;
      if (m.roomName != null) msg.roomName = cleanText(m.roomName, LIMITS.ROOM_NAME_MAX);
      // buffering policy: "move" (default - party keeps moving) | "everyone"
      // (host-enabled hold-until-ready, bounded by a timeout + host override)
      if (m.waitMode != null) {
        const wm = String(m.waitMode) === "everyone" ? "everyone" : "move";
        msg.waitMode = wm;
      }
      if (m.readyTimeoutMs != null) {
        const rt = parseInt(m.readyTimeoutMs, 10);
        if (Number.isFinite(rt)) {
          msg.readyTimeoutMs = Math.min(LIMITS.READY_TIMEOUT_MAX_MS, Math.max(LIMITS.READY_TIMEOUT_MIN_MS, rt));
        }
      }
      if (m.holdTimeoutMs != null) {
        const ht = parseInt(m.holdTimeoutMs, 10);
        if (Number.isFinite(ht)) {
          msg.holdTimeoutMs = Math.min(LIMITS.HOLD_TIMEOUT_MAX_MS, Math.max(LIMITS.HOLD_TIMEOUT_MIN_MS, ht));
        }
      }
      return { ok: true, msg };
    }
    case "heartbeat":
      return { ok: true, msg: { t, clientTs: Number(m.clientTs) || 0 } };
    case "sync_report": {
      const p = Number(m.position);
      const paused = m.paused === true;
      if (!Number.isFinite(p) || p < 0 || p > 24 * 3600) return { ok: false, error: "bad_message" };
      const msg = { t, position: p, paused };
      // additive observability fields: duration + source name let members see
      // real peer state and detect different cuts (sub vs dub / different
      // mirrors). Old hubs/clients ignore what they don't know.
      if (m.dur != null) {
        const d = Number(m.dur);
        if (Number.isFinite(d) && d > 0 && d <= 24 * 3600) msg.dur = d;
      }
      if (m.source != null) {
        const s = cleanText(m.source, 24);
        if (s) msg.source = s;
      }
      // session stamp + player state ride along when a newer client reports:
      // a report stamped for a previous episode must not mark anyone "ready"
      // for the current one (stale-readiness guard).
      if (m.sv != null) {
        const sv = parseInt(m.sv, 10);
        if (Number.isFinite(sv) && sv >= 1) msg.sv = sv;
      }
      if (m.st != null && PSTATE_VALUES.has(m.st)) msg.st = m.st;
      return { ok: true, msg };
    }
    case "pstate": {
      // explicit player-state report (loading/ready/buffering/...). This is the
      // readiness + buffering channel: fast, rate-limited separately.
      if (!PSTATE_VALUES.has(m.st)) return { ok: false, error: "bad_message" };
      const msg = { t, st: m.st };
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
      return { ok: true, msg };
    }
    case "source": {
      // room-wide mirror ("server") switch: last manual pick wins. The server
      // never resets playback here - switching source mid-episode keeps the
      // room's timeline intact.
      const mirror = cleanText(m.mirror, 24);
      if (!mirror) return { ok: false, error: "bad_message" };
      return { ok: true, msg: { t, mirror } };
    }
    default:
      return { ok: false, error: "bad_message" };
  }
}

/* ---- shared drift-correction planner (clients copy this verbatim) ---- */

/* Pure decision function for drift correction. The website's driftTick() calls
   this exact logic (kept byte-identical; QA lock verifies the copy). Policy:
   - buffering / seeking / unready players are NEVER fought
   - a player held before a scheduled start is never corrected
   - tiny drift: ignore; moderate: gentle rate nudge when genuinely supported;
     large: one controlled seek; seek-only bridges: rare large-gap seeks
   - a cooldown between corrections makes correction loops impossible
   - never seek into the credits/end zone or across known different cuts
   Returns { action: "none"|"nudge"|"seek", rate?, restoreMs?, reason }. */
function planCorrection(o) {
  const cfg = Object.assign({
    nudgeAt: 0.2,        // seconds of drift before a gentle rate nudge
    seekAt: 1.5,         // seconds of drift before a controlled hard seek
    seekOnlyAt: 1.2,     // seek-only bridges: seek when past this
    minGapMs: 2500,      // min ms between corrections (no correction loops)
    rateStep: 0.10,      // 10% rate nudge
    restoreCapMs: 8000,  // max ms before restoring rate to 1
    endZoneSec: 20,      // never hard-seek within the last N seconds
    cutBridgeAt: 15,     // different cuts: only bridge gaps at/above this
  }, o.cfg || {});
  const drift = Number(o.drift) || 0;
  const mag = Math.abs(drift);
  if (!o.canSeek) return { action: "none", reason: "unsupported" };
  if (o.buffering) return { action: "none", reason: "buffering" };
  if (o.seeking) return { action: "none", reason: "seeking" };
  if (o.ready === false) return { action: "none", reason: "not_ready" };
  if (o.gateAt && o.serverNowMs != null && o.gateAt > o.serverNowMs) {
    return { action: "none", reason: "gate" };
  }
  if (mag < cfg.nudgeAt) return { action: "none", reason: "in_band" };
  if (o.cutMismatch && mag < cfg.cutBridgeAt) return { action: "none", reason: "cut_mismatch" };
  const nowMs = o.nowMs || 0;
  if (o.lastCorrectMs && nowMs - o.lastCorrectMs < cfg.minGapMs) {
    return { action: "none", reason: "cooldown" };
  }
  if (mag >= cfg.seekAt) {
    // controlled seek - but never into the end zone (credits would jump)
    if (o.dur && o.pos != null && o.pos > o.dur - cfg.endZoneSec) {
      return { action: "none", reason: "end_zone" };
    }
    return { action: "seek", reason: "large_drift" };
  }
  if (o.canRate) {
    const rate = drift > 0 ? 1 - cfg.rateStep : 1 + cfg.rateStep;
    return {
      action: "nudge",
      rate,
      restoreMs: Math.min(cfg.restoreCapMs, Math.round(mag / cfg.rateStep * 1000)),
      reason: "moderate_drift",
    };
  }
  if (mag > cfg.seekOnlyAt) return { action: "seek", reason: "seek_only_bridge" };
  return { action: "none", reason: "below_seek_only_floor" };
}

module.exports = {
  LIMITS, CODE_ALPHABET, CODE_LEN, CODE_RE, CLIENT_MSGS, SERVER_MSGS, ERROR_CODES,
  PLAYBACK_ACTIONS, ROOM_STATUSES, PSTATE_VALUES, PSTATE_BLOCKING, WAIT_MODES,
  cleanText, cleanName, expectedPosition, planCorrection, validateClientMsg,
};
