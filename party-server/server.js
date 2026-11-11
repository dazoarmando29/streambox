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
  const { RateLimiter } = require("./rooms");
  // per-remote-address create limiter: room creation is cheap for an attacker
  // and expensive for the server (rooms live up to 24h) - bound it like code
  // guessing is bounded
  const createLimiters = new RateLimiter(60 * 1000, opts.maxCreatesPerMin || 5);

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

  // limiters live ON the connection object so they die with it: the old Map
  // keyed by participantId leaked one entry per reattach (p.id changes) and
  // gave every reconnect a fresh rate budget
  function limitersFor(p) {
    if (!p._lim) {
      p._lim = {
        msg: new RateLimiter(LIMITS.MSG_WINDOW_MS, LIMITS.MSG_MAX),
        chat: new RateLimiter(LIMITS.CHAT_WINDOW_MS, LIMITS.CHAT_RATE_MAX),
        sync: new RateLimiter(LIMITS.SYNC_REPORT_MIN_MS * 3, 3),
      };
    }
    return p._lim;
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
      const lim = limitersFor(p);
      const now = Date.now();
      if (!lim.msg.allow("m", now)) { send(ws, { t: "error", error: "rate_limited" }); return; }

      const inRoom = !!p.roomId;
      const v = validateClientMsg(raw, inRoom);
      if (!v.ok) { send(ws, { t: "error", error: v.error }); return; }
      const m = v.msg;

      // refresh liveness for any traffic (conn-guarded: a replaced ghost
      // socket never refreshes the live seat)
      if (p.roomId) rooms.markSeen(p.roomId, p.id, ws);
      // a REPLACED ghost socket (this seat already moved to a newer
      // connection) must never drive the room - drop its leftover traffic
      {
        const rNow = p.roomId ? rooms.rooms.get(p.roomId) : null;
        const partNow = rNow && rNow.participants.get(p.id);
        if (partNow && partNow.conn && partNow.conn !== ws) return;
      }

      switch (m.t) {
        case "hello": {
          p.name = m.name;
          // no synthetic heartbeat echo here: a `clientTs: 0` reply used to
          // poison the client's clock sync (rtt=0 pinned the anchor window
          // shut for every real WAN sample). The client's own heartbeat on
          // open gets a real echo - that is the clock sample.
          break;
        }
        case "create": {
          if (p.roomId) { send(ws, { t: "error", error: "conflict" }); return; }
          if (!createLimiters.allow(remoteIp || "unknown", Date.now())) {
            send(ws, { t: "error", error: "rate_limited", for: "create" });
            return;
          }
          p.name = m.name;
          const r = rooms.create(p, m);
          p.roomId = r.room.roomId;
          send(ws, { t: "room", room: r.room, token: p.token });
          break;
        }
        case "join": {
          // a connection that already HAS a seat cannot double-join; one whose
          // seat expired (long background) may rejoin via its resume token
          const already = p.roomId && rooms.rooms.get(p.roomId) && rooms.rooms.get(p.roomId).participants.has(p.id);
          if (already) { send(ws, { t: "error", error: "conflict" }); return; }
          p.name = m.name;
          const r = rooms.join(p, { ...m, remoteIp });
          if (r.error) { send(ws, { t: "error", error: r.error, for: "join" }); return; }
          p.roomId = r.room.roomId;
          if (r.reattached) p.id = r.reattached;
          // a replaced ghost socket loses its seat: close it deliberately so
          // it can linger half-open no longer (conn guards make its traffic
          // and its late close harmless either way)
          if (r.dropConn && r.dropConn !== ws) { try { r.dropConn.close(); } catch (e) {} }
          // always hand back the SEAT token (r.token): the connection token
          // rotates on every reconnect and must never become the client's
          // resume identity
          send(ws, { t: "room", room: r.room, token: r.token || p.token, reattached: !!r.reattached, replaced: !!r.replaced });
          if (r.events && r.events.length) broadcast(r.room.roomId, r.events, p.id);
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
          if (!lim.chat.allow("c", Date.now())) { send(ws, { t: "error", error: "rate_limited", for: "chat" }); return; }
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
          // `kicked` reaches ONLY the target - the room hears just the leave.
          // (Broadcasting `kicked` to everyone used to tear down every client:
          // each one treated any `kicked` event as its own removal.)
          if (target && target.conn) for (const ev of r.targetEvents || []) send(target.conn, ev);
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
          // room gone underneath us (server restart / room expired): tell the
          // client to clean up instead of silently going deaf. Seat reattach
          // is handled by the client's reconnect-join (resume token).
          const room = p.roomId ? rooms.rooms.get(p.roomId) : null;
          if (p.roomId && !room) send(ws, { t: "notice", notice: "room_gone" });
          break;
        }
        case "sync_report": {
          if (!lim.sync.allow("s", Date.now())) return; // drop silently
          rooms.syncReport(p.roomId, p.id, m.position, m.paused, m.dur, m.source);
          break;
        }
        case "source": {
          // room-wide mirror ("server") switch - timelines untouched
          const r = rooms.source(p.roomId, p.id, m.mirror);
          if (r.error) { send(ws, { t: "error", error: r.error, for: "source" }); return; }
          broadcast(p.roomId, r.events || []);
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
      if (!rid) return;
      // conn-guarded: a replaced ghost socket closing must never evict the
      // live seat it was replaced by
      const r = rooms.disconnect(rid, p.id, ws);
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
