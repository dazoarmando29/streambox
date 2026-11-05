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
  // join-by-code guessing: per-remote-address
  CODE_ATTEMPT_WINDOW_MS: 5 * 60 * 1000,
  CODE_ATTEMPT_MAX: 12,
  // room lifecycle
  HOST_GRACE_MS: 60 * 1000,
  PARTICIPANT_TTL_MS: 45 * 1000,
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
]);

// server -> client message types
const SERVER_MSGS = new Set([
  "welcome", "room", "room_state", "playback", "content", "chat",
  "peer_joined", "peer_left", "host_changed", "settings", "kicked",
  "room_ended", "error", "heartbeat", "notice",
]);

const ERROR_CODES = new Set([
  "bad_message", "unauthorized", "not_found", "wrong_password", "full",
  "closed", "rate_limited", "room_limit", "not_in_room", "conflict",
]);

const PLAYBACK_ACTIONS = new Set(["play", "pause", "seek", "rate", "ended", "buffer"]);
const ROOM_STATUSES = new Set(["waiting", "loading", "ready", "playing", "paused", "ended", "error"]);

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
      return { ok: true, msg };
    }
    case "heartbeat":
      return { ok: true, msg: { t, clientTs: Number(m.clientTs) || 0 } };
    case "sync_report": {
      const p = Number(m.position);
      const paused = m.paused === true;
      if (!Number.isFinite(p) || p < 0 || p > 24 * 3600) return { ok: false, error: "bad_message" };
      return { ok: true, msg: { t, position: p, paused } };
    }
    default:
      return { ok: false, error: "bad_message" };
  }
}

module.exports = { LIMITS, CODE_ALPHABET, CODE_LEN, CODE_RE, CLIENT_MSGS, SERVER_MSGS, ERROR_CODES, PLAYBACK_ACTIONS, ROOM_STATUSES, cleanText, cleanName, expectedPosition, validateClientMsg };
