"use strict";
/* StreamBox Watch Party server: WebSocket transport + room authority.
   Stateless HTTP health endpoint + stateful rooms in memory. Rooms are
   ephemeral by design (expiry sweeps reclaim them) - no database.

   Deployment: `node server.js` (PORT env, default 8787). Put TLS in front
   (any reverse proxy / load balancer) so clients can use wss://. The
   WebSocket endpoint is /party. */

const http = require("http");
const { WebSocketServer, WebSocket } = require("ws");
const { RoomManager, makeId } = require("./rooms");
const { LIMITS, validateClientMsg, CLIENT_MSGS } = require("./protocol");

function createPartyServer(opts = {}) {
  const rooms = new RoomManager(opts.ttl || {});
  const path = opts.path || "/party";
  const allowedOrigins = opts.allowedOrigins || []; // empty = same-site policy left to the proxy
  const msgLimiters = new Map(); // participantId -> { msg, chat, sync }

  const server = http.createServer((req, res) => {
    if (req.url === "/healthz" || req.url === "/") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ ok: true, name: "streambox-party-server", rooms: rooms.roomCount() }));
      return;
    }
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: "not_found" }));
  });

  const wss = new WebSocketServer({ noServer: true, maxPayload: LIMITS.MAX_MSG_BYTES });

  server.on("upgrade", (req, socket, head) => {
    let pathname = "/";
    try { pathname = new URL(req.url, "http://localhost").pathname; } catch (e) {}
    if (pathname !== path) { socket.destroy(); return; }
    const origin = req.headers.origin || "";
    if (allowedOrigins.length && origin && !allowedOrigins.includes(origin)) {
      socket.write("HTTP/1.1 403 Forbidden\r\n\r\n");
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws, req));
  });

  function limitersFor(participantId) {
    let l = msgLimiters.get(participantId);
    if (!l) {
      l = {
        msg: new (require("./rooms").RateLimiter)(LIMITS.MSG_WINDOW_MS, LIMITS.MSG_MAX),
        chat: new (require("./rooms").RateLimiter)(LIMITS.CHAT_WINDOW_MS, LIMITS.CHAT_RATE_MAX),
        sync: new (require("./rooms").RateLimiter)(LIMITS.SYNC_REPORT_MIN_MS * 3, 3),
      };
      msgLimiters.set(participantId, l);
    }
    return l;
  }

  function send(ws, obj) {
    try { if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); } catch (e) {}
  }

  function broadcast(roomId, events, exceptId) {
    const room = rooms.rooms.get(roomId);
    if (!room) return;
    for (const part of room.participants.values()) {
      if (part.id === exceptId || !part.conn) continue;
      for (const ev of events) send(part.conn, ev);
    }
  }

  function participantCount(room) {
    return [...room.participants.values()].filter((p) => p.connected).length;
  }

  wss.on("connection", (ws, req) => {
    const remoteIp = (req.headers["x-forwarded-for"] || req.socket.remoteAddress || "unknown").toString().split(",")[0].trim();
    const p = {
      id: makeId("p"),
      token: makeId("tok"),
      name: "Guest",
      conn: ws,
      roomId: "",
    };

    send(ws, { t: "welcome", participantId: p.id, token: p.token, serverTs: Date.now() });

    ws.on("message", (data, isBinary) => {
      if (isBinary) { send(ws, { t: "error", error: "bad_message" }); return; }
      const raw = data.toString();
      if (raw.length > LIMITS.MAX_MSG_BYTES) { send(ws, { t: "error", error: "bad_message" }); return; }
      const lim = limitersFor(p.id);
      const now = Date.now();
      if (!lim.msg.allow(p.id, now)) { send(ws, { t: "error", error: "rate_limited" }); return; }

      const inRoom = !!p.roomId;
      const v = validateClientMsg(raw, inRoom);
      if (!v.ok) { send(ws, { t: "error", error: v.error }); return; }
      const m = v.msg;

      // refresh liveness for any traffic
      if (p.roomId) rooms.markSeen(p.roomId, p.id);

      switch (m.t) {
        case "hello": {
          p.name = m.name;
          send(ws, { t: "heartbeat", clientTs: 0, serverTs: Date.now(), participantId: p.id });
          break;
        }
        case "create": {
          if (p.roomId) { send(ws, { t: "error", error: "conflict" }); return; }
          p.name = m.name;
          const r = rooms.create(p, m);
          p.roomId = r.room.roomId;
          send(ws, { t: "room", room: r.room, token: p.token });
          break;
        }
        case "join": {
          if (p.roomId) { send(ws, { t: "error", error: "conflict" }); return; }
          p.name = m.name;
          const r = rooms.join(p, { ...m, remoteIp });
          if (r.error) { send(ws, { t: "error", error: r.error, for: "join" }); return; }
          p.roomId = r.room.roomId;
          if (r.reattached) p.id = r.reattached;
          send(ws, { t: "room", room: r.room, token: p.token, reattached: !!r.reattached });
          if (!r.reattached) {
            broadcast(r.room.roomId, [{ t: "peer_joined", room: r.room.roomId, participant: r.room.participants.find((x) => x.id === p.id) }], p.id);
          }
          break;
        }
        case "leave": {
          const rid = p.roomId;
          if (!rid) return;
          p.roomId = "";
          const r = rooms.leave(rid, p.id);
          broadcast(rid, r.events || []);
          break;
        }
        case "end": {
          const r = rooms.end(p.roomId, p.id);
          if (r.error) { send(ws, { t: "error", error: r.error }); return; }
          const rid = p.roomId;
          p.roomId = "";
          broadcast(rid, r.events || []); // includes the sender
          break;
        }
        case "playback": {
          const r = rooms.playback(p.roomId, p.id, m);
          if (r.error) { send(ws, { t: "error", error: r.error, for: "playback" }); return; }
          if (r.stale) { send(ws, { t: "notice", notice: "stale_playback" }); return; }
          broadcast(p.roomId, r.events || []);
          break;
        }
        case "content": {
          const r = rooms.content(p.roomId, p.id, m);
          if (r.error) { send(ws, { t: "error", error: r.error, for: "content" }); return; }
          broadcast(p.roomId, r.events || []);
          break;
        }
        case "chat": {
          if (!lim.chat.allow("chat:" + p.id, Date.now())) { send(ws, { t: "error", error: "rate_limited", for: "chat" }); return; }
          const r = rooms.chat(p.roomId, p.id, m.text);
          if (r.error) { send(ws, { t: "error", error: r.error, for: "chat" }); return; }
          broadcast(p.roomId, r.events || []);
          break;
        }
        case "kick": {
          const rid = p.roomId;
          const room = rooms.rooms.get(rid);
          const target = room && room.participants.get(m.targetId);
          const r = rooms.kick(rid, p.id, m.targetId);
          if (r.error) { send(ws, { t: "error", error: r.error }); return; }
          // the kicked participant hears its own removal first - it is already
          // out of the room, so the broadcast below can no longer reach it
          if (target && target.conn) for (const ev of r.events || []) send(target.conn, ev);
          broadcast(rid, r.events || []);
          break;
        }
        case "mute": {
          const r = rooms.mute(p.roomId, p.id, m.targetId, m.muted);
          if (r.error) { send(ws, { t: "error", error: r.error }); return; }
          broadcast(p.roomId, r.events || []);
          break;
        }
        case "settings": {
          const r = rooms.settings(p.roomId, p.id, m);
          if (r.error) { send(ws, { t: "error", error: r.error }); return; }
          broadcast(p.roomId, r.events || []);
          break;
        }
        case "heartbeat": {
          send(ws, rooms.heartbeat(p.id, m.clientTs));
          break;
        }
        case "sync_report": {
          if (!lim.sync.allow("sync:" + p.id, Date.now())) return; // drop silently
          rooms.syncReport(p.roomId, p.id, m.position, m.paused);
          break;
        }
        case "resync": {
          const r = rooms.resync(p.roomId, p.id);
          if (r.error) { send(ws, { t: "error", error: r.error }); return; }
          for (const ev of r.events || []) send(ws, ev);
          break;
        }
        default:
          send(ws, { t: "error", error: "bad_message" });
      }
    });

    ws.on("close", () => {
      const rid = p.roomId;
      msgLimiters.delete(p.id);
      if (!rid) return;
      const r = rooms.disconnect(rid, p.id);
      broadcast(rid, r.events || [], p.id);
    });

    ws.on("error", () => {});
  });

  const sweepTimer = setInterval(() => {
    for (const batch of rooms.sweep()) {
      broadcast(batch.roomId, batch.events);
    }
  }, 10000);
  if (sweepTimer.unref) sweepTimer.unref();

  return {
    server,
    rooms,
    listen(port, cb) { return server.listen(port, cb); },
    close(cb) {
      clearInterval(sweepTimer);
      for (const c of wss.clients) { try { c.terminate(); } catch (e) {} }
      wss.close();
      server.close(cb);
    },
  };
}

if (require.main === module) {
  const port = parseInt(process.env.PORT, 10) || 8787;
  const origins = (process.env.PARTY_ALLOWED_ORIGINS || "").split(",").map((s) => s.trim()).filter(Boolean);
  const app = createPartyServer({ allowedOrigins: origins });
  app.listen(port, () => {
    console.log(`streambox-party-server listening on :${port} (ws path /party)`);
    if (origins.length) console.log("origin allowlist:", origins.join(", "));
    else console.log("origin allowlist: open (set PARTY_ALLOWED_ORIGINS to restrict)");
  });
}

module.exports = { createPartyServer };
