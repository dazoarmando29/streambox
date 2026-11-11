package com.streambox.nativeapp.party

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.streambox.nativeapp.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/* Watch Party engine (native app). Same protocol as the website + party-server:
   the server is authoritative for room + playback STATE, every device plays its
   own stream, and sync rides the player's real command bridge (window.__sb).
   Sync honesty: only mirrors with a real play/pause/seek API are driven; the
   player UI labels the honest mode, exactly like the website. */

data class PartyContent(val type: String, val tmdbId: Int, val season: Int, val episode: Int) {
    val key: String get() = "$type/$tmdbId/$season/$episode"
}

data class PartyPeer(
    val id: String,
    val name: String,
    val connected: Boolean,
    val muted: Boolean,
    val buffering: Boolean,
    val host: Boolean,
)

data class PartyChatMsg(val id: String, val from: String, val name: String, val text: String, val ts: Long)

data class PartyRoom(
    val roomId: String,
    val code: String,
    val name: String,
    val hostId: String,
    val selfId: String,
    val hasPassword: Boolean,
    val allowControl: Boolean,
    val chatEnabled: Boolean,
    val content: PartyContent?,
    val source: String = "", // room-wide mirror ("server") - follows the last controller switch
    val status: String,
    val position: Float,
    val rate: Float,
    val updatedAt: Long,
    val sessionVersion: Int,
    val seq: Int,
    val participants: List<PartyPeer>,
    val chat: List<PartyChatMsg>,
) {
    val isHost: Boolean get() = hostId == selfId
    fun canControl(): Boolean = isHost || allowControl
}

/** The live player exposes its bridge here while on screen (registered by
    PlayerScreen); Party never touches the WebView directly. */
interface PartyOps {
    fun play()
    fun pause()
    fun seek(seconds: Float)
    fun position(): Float
    fun duration(): Float
    fun isPaused(): Boolean
    fun session(): PartyContent?
    /** Honest sync class for the ACTIVE mirror: "full" | "partial" | "coordinated". */
    fun syncCap(): String = "full"
    /** Subtle drift nudges where the bridge supports playback rate. */
    fun rate(r: Float) {}
    /** Ms since THIS player loaded its episode - drift correction holds off
        right after a load (stale-timeline guard). */
    fun sinceLoadMs(): Long = Long.MAX_VALUE
}

object PartyPlayer {
    @Volatile var ops: PartyOps? = null
    @Volatile var open: ((PartyContent) -> Unit)? = null
    /** Room-wide source ("server") switch: registered by PlayerScreen. */
    @Volatile var onSource: ((String) -> Unit)? = null
}

object Party {
    // ---- tunables (mirror the website CFG) ----
    private const val SYNC_NUDGE = 0.25f
    private const val SYNC_SEEK = 2.0f
    private const val SYNC_INTERVAL = 2000L
    private const val SYNC_MIN_GAP = 4000L
    private const val HEARTBEAT = 20000L
    private const val CHAT_MAX = 300

    // ---- observable state (Compose reads these) ----
    val room = androidx.compose.runtime.mutableStateOf<PartyRoom?>(null)
    val status = androidx.compose.runtime.mutableStateOf("idle") // idle|connecting|in-room|reconnecting
    val unread = androidx.compose.runtime.mutableStateOf(0)
    val lastError = androidx.compose.runtime.mutableStateOf("")

    // ---- session identity ----
    private var ctx: Context? = null
    private var selfId = ""
    private var token = ""
    private var name = ""
    private var wantRoom: String? = null // code we belong to (for rejoin)
    private var joinPw = ""
    private var lastSeq = 0
    private var lastContentKey = ""
    private var offsetMs = 0L
    private var suppressUntil = 0L
    private var lastCorrect = 0L
    private var reconnects = 0

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ws: WebSocket? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived socket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var hbRunnable: Runnable? = null
    private var tickRunnable: Runnable? = null
    private var retryRunnable: Runnable? = null
    private var relayRunnable: Runnable? = null
    private var pendingMsg: org.json.JSONObject? = null // create/join awaiting its room reply
    private var rejoinTries = 0
    private var lastRelayedPaused: Boolean? = null
    private var lastLoadAtMs = 0L

    fun init(context: Context) {
        if (ctx != null) return
        ctx = context.applicationContext
        // drift + content-diff tick (only runs while in a room)
        val tick = object : Runnable {
            override fun run() {
                try { tick() } catch (e: Exception) {}
                main.postDelayed(this, SYNC_INTERVAL)
            }
        }
        tickRunnable = tick
        main.postDelayed(tick, SYNC_INTERVAL)
    }

    fun wsUrl(): String = BuildConfig.PARTY_WS

    private fun send(o: JSONObject): Boolean {
        return try { ws?.send(o.toString()) == true } catch (e: Exception) { false }
    }

    // ---------- connection (single socket, backoff + jitter) ----------
    fun connect() {
        val url = wsUrl()
        if (url.isBlank()) {
            status.value = "idle"
            lastError.value = "Watch Party server not configured"
            return
        }
        if (ws != null) return // single-connection guard
        status.value = if (room.value != null) "reconnecting" else "connecting"
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnects = 0
                send(JSONObject().put("t", "hello").put("name", displayName()))
                val code = wantRoom
                if (code != null && room.value != null) {
                    // ONE join per connection: a second join on the same socket
                    // is how ghost seats used to multiply on older hubs
                    send(JSONObject().put("t", "join").put("name", displayName())
                        .put("code", code).put("token", token).put("password", joinPw))
                } else if (room.value == null && pendingMsg != null) {
                    // the create/join died with the last socket before its
                    // reply: re-send it instead of stranding "connecting"
                    send(pendingMsg!!)
                }
                webSocket.send(JSONObject().put("t", "heartbeat").put("clientTs", System.currentTimeMillis()).toString())
                scheduleHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(JSONObject(text)) } catch (e: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ws === webSocket) ws = null
                if (room.value != null || pendingMsg != null) {
                    status.value = if (room.value != null) "reconnecting" else "connecting"
                    retry()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws === webSocket) ws = null
                if (room.value != null || pendingMsg != null) {
                    status.value = if (room.value != null) "reconnecting" else "connecting"
                    retry()
                }
            }
        })
    }

    private fun retry() {
        if (room.value == null && pendingMsg == null) return
        reconnects += 1
        val base = min(15000L, (500.0 * Math.pow(1.8, min(reconnects, 8).toDouble())).toLong())
        val wait = (base * (0.7 + Random.nextDouble() * 0.6)).toLong()
        retryRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { if (ws == null && (room.value != null || pendingMsg != null)) connect() }
        retryRunnable = r
        main.postDelayed(r, wait)
    }

    private fun scheduleHeartbeat() {
        hbRunnable?.let { main.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                send(JSONObject().put("t", "heartbeat").put("clientTs", System.currentTimeMillis()))
                main.postDelayed(this, HEARTBEAT)
            }
        }
        hbRunnable = r
        main.postDelayed(r, HEARTBEAT)
    }

    fun disconnect() {
        wantRoom = null
        pendingMsg = null
        rejoinTries = 0
        lastRelayedPaused = null
        retryRunnable?.let { main.removeCallbacks(it) }
        hbRunnable?.let { main.removeCallbacks(it) }
        relayRunnable?.let { main.removeCallbacks(it) }
        try { ws?.close(1000, "bye") } catch (e: Exception) {}
        ws = null
        room.value = null
        status.value = "idle"
        unread.value = 0
        lastContentKey = ""
    }

    // ---------- room actions ----------
    fun displayName(): String = name.ifBlank { "Guest" }

    fun setName(n: String) { name = n.trim().take(24) }

    fun create(roomName: String, password: String, allowControl: Boolean, chatOn: Boolean) {
        wantRoom = null
        val msg = JSONObject().put("t", "create").put("name", displayName())
            .put("roomName", roomName.trim().take(40)).put("password", password)
            .put("maxParticipants", 8).put("allowControl", allowControl).put("chatEnabled", chatOn)
        pendingMsg = msg
        connect()
        main.post { send(msg) }
    }

    fun join(code: String, password: String, resumeToken: String = token) {
        wantRoom = code.trim().uppercase()
        joinPw = password
        val msg = JSONObject().put("t", "join").put("name", displayName())
            .put("code", wantRoom ?: "").put("password", joinPw).put("token", resumeToken)
        pendingMsg = msg
        connect()
        main.post { send(msg) }
    }

    fun leave() {
        send(JSONObject().put("t", "leave"))
        clearSession()
        room.value = null
        status.value = "idle"
        wantRoom = null
    }

    fun endRoom() {
        if (room.value?.isHost != true) return
        send(JSONObject().put("t", "end"))
        clearSession()
        room.value = null
        status.value = "idle"
        wantRoom = null
    }

    fun sendChat(text: String) {
        val t = text.trim().take(CHAT_MAX)
        if (t.isEmpty() || room.value == null) return
        send(JSONObject().put("t", "chat").put("text", t))
    }

    fun resync() {
        if (room.value == null) return
        send(JSONObject().put("t", "resync"))
    }

    fun setControls(allow: Boolean) { if (room.value?.isHost == true) send(JSONObject().put("t", "settings").put("allowControl", allow)) }
    fun setChatEnabled(on: Boolean) { if (room.value?.isHost == true) send(JSONObject().put("t", "settings").put("chatEnabled", on)) }
    fun kick(id: String) { if (room.value?.isHost == true) send(JSONObject().put("t", "kick").put("targetId", id)) }
    fun mute(id: String, muted: Boolean) { if (room.value?.isHost == true) send(JSONObject().put("t", "mute").put("targetId", id).put("muted", muted)) }

    /** Invite link carries the room code only - never a password/token. */
    fun inviteUrl(): String = BuildConfig.PARTY_INVITE_BASE + "?party=" + (room.value?.code ?: "")

    /** Honest sync label for the ACTIVE mirror ("" while no player is up). */
    fun syncLabel(): String {
        val ops = PartyPlayer.ops ?: return ""
        return when (ops.syncCap()) {
            "partial" -> "PARTIAL SYNC"
            "coordinated" -> "COORDINATED START"
            else -> "FULL SYNC"
        }
    }

    // ---------- local player -> room ----------
    private fun suppressLocal(): Boolean = System.currentTimeMillis() < suppressUntil
    private var localSentAt = 0L

    fun onLocalPlayPause(paused: Boolean) {
        val r = room.value ?: return
        // a DELIBERATE press always reaches the room - the echo window guards
        // provider events, never the user's own hands (a quick pause->play was
        // swallowed by the echo of the first press and force-reverted after)
        if (!r.canControl()) return
        localSentAt = System.currentTimeMillis()
        lastRelayedPaused = paused
        send(JSONObject().put("t", "playback")
            .put("action", if (paused) "pause" else "play")
            .put("position", PartyPlayer.ops?.position() ?: 0f)
            .put("sessionVersion", r.sessionVersion))
    }

    /** The user paused/played INSIDE the provider's own player (its own
        button, not our overlay) - the room must hear it. Echoes of OUR OWN
        sends and remote-driven changes are gated away (suppressLocal + the
        1.5s local-send window). The relay is CONFIRMED after 1.2s so a
        provider machine pause/resume flap (buffer stall) cancels itself
        instead of pausing the whole room. */
    fun providerPlayState(paused: Boolean) {
        if (System.currentTimeMillis() < localSentAt + 1500) return
        if (suppressLocal()) return
        relayRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable {
            try {
                val now = System.currentTimeMillis()
                if (now < localSentAt + 1500 || suppressLocal()) return@Runnable
                val rr = room.value ?: return@Runnable
                if (!rr.canControl()) return@Runnable
                val still = PartyPlayer.ops?.isPaused() ?: return@Runnable
                if (still == lastRelayedPaused) return@Runnable // the room already knows
                onLocalPlayPause(still)
            } catch (e: Exception) {}
        }
        relayRunnable = r
        main.postDelayed(r, 1200)
    }

    fun onLocalSeek(seconds: Float) {
        val r = room.value ?: return
        // deliberate seeks always reach the room
        if (!r.canControl()) return
        localSentAt = System.currentTimeMillis()
        send(JSONObject().put("t", "playback").put("action", "seek")
            .put("position", max(0f, seconds)).put("sessionVersion", r.sessionVersion))
    }

    /** Room-wide source ("server") switch: controllers move the whole room.
        Old hubs answer bad_message - a capability gap, never an error. */
    fun onLocalSource(mirror: String) {
        val r = room.value ?: return
        if (!r.canControl()) return
        val m = mirror.trim().take(24)
        if (m.isEmpty() || m == "trailer") return
        send(JSONObject().put("t", "source").put("mirror", m))
    }

    private fun detectContentChange() {
        val r = room.value ?: return
        if (!r.isHost || suppressLocal()) return
        val s = PartyPlayer.ops?.session() ?: return
        if (s.key != lastContentKey) {
            // stamp ONLY on a successful send: a dropped message on a dying
            // socket used to eat the switch silently (the room never heard it)
            if (send(JSONObject().put("t", "content").put("type", s.type)
                .put("tmdbId", s.tmdbId).put("season", s.season).put("episode", s.episode))) {
                lastContentKey = s.key
            }
        }
    }

    /** Non-hosts never auto-advance locally - the host's content event drives. */
    fun suppressAutoAdvance(): Boolean = room.value != null && status.value == "in-room" && room.value?.isHost != true

    // ---------- sync math (mirrors party-server protocol.js) ----------
    /** Room clock: local time corrected by the heartbeat offset estimate. */
    fun serverNow(): Long = System.currentTimeMillis() + offsetMs

    fun expectedPos(nowMs: Long = System.currentTimeMillis()): Float {
        val r = room.value ?: return 0f
        val p = r.position
        if (r.status != "playing") return p
        val dt = (nowMs + offsetMs - r.updatedAt) / 1000f
        return max(0f, p + dt * r.rate)
    }

    private fun tick() {
        if (room.value == null) return
        detectContentChange()
        val ops = PartyPlayer.ops ?: return
        val r = room.value ?: return
        if (r.status != "playing") return
        val nowS = serverNow()
        if (r.updatedAt > nowS) return // scheduled start: hold the gate
        // fresh load / episode switch: syncToRoom lands it - correcting
        // against the PREVIOUS episode's room timeline used to seek the new
        // player to the old episode's end (clamped -> ended -> the room
        // lurched a whole step)
        if (min(ops.sinceLoadMs(), System.currentTimeMillis() - lastLoadAtMs) < 8000) return
        val drift = ops.position() - expectedPos()
        val mag = abs(drift)
        if (mag < SYNC_NUDGE) return
        val now = System.currentTimeMillis()
        if (now - lastCorrect < SYNC_MIN_GAP) return // never a correction loop
        lastCorrect = now
        suppressUntil = now + 1500
        try {
            if (mag >= SYNC_SEEK) {
                ops.seek(expectedPos() + 0.25f) // big drift: one clean seek
            } else {
                // subtle 4% nudge in the right direction - the old code
                // hard-SEEKED both branches and the player stuttered on
                // every drift over 0.35s (the reported constant seeking)
                ops.rate(if (drift > 0) 0.96f else 1.04f)
                main.postDelayed({ try { PartyPlayer.ops?.rate(1f) } catch (e: Exception) {} },
                    min(8000L, (mag / 0.04f * 1000).toLong()))
            }
        } catch (e: Exception) {}
    }

    // ---------- server events ----------
    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "welcome" -> {
                // per-CONNECTION identity - never overwrite a stored resume
                // identity with it (that rotation broke every second reconnect)
                if (selfId.isEmpty() && m.has("participantId")) selfId = m.optString("participantId")
                if (token.isEmpty() && m.has("token")) token = m.optString("token")
            }
            "heartbeat" -> {
                val t1 = System.currentTimeMillis()
                val t0 = m.optLong("clientTs", 0L)
                // synthetic echoes (clientTs 0/absent) are not clock samples:
                // accepting one pinned rtt=0 and froze the offset forever
                if (t0 > 0L) {
                    val off = m.optLong("serverTs", t1) - (t0 + t1) / 2
                    offsetMs = if (offsetMs == 0L) off else (offsetMs * 0.7 + off * 0.3).toLong()
                }
            }
            "room" -> {
                val rm = parseRoom(m.optJSONObject("room"))
                if (rm != null) {
                    room.value = rm
                    // the SNAPSHOT names our identity: after a token reattach
                    // it is the seat id, not the connection id from welcome
                    if (rm.selfId.isNotEmpty()) selfId = rm.selfId
                    status.value = "in-room"
                    lastSeq = rm.seq
                    lastContentKey = rm.content?.key ?: ""
                    lastRelayedPaused = rm.status != "playing"
                    pendingMsg = null // the handshake answered - stop re-sending
                    rejoinTries = 0
                    unread.value = 0
                    // identity rule that survives BOTH hub generations: on a
                    // reattach our stored resume token IS the seat identity -
                    // never let a response rotate it (older hubs echo the
                    // per-connection token there, stranding the next
                    // reconnect). A fresh join adopts the new seat's token.
                    if (!m.optBoolean("reattached") && m.has("token")) token = m.optString("token")
                    saveSession()
                    val c = rm.content
                    val live = PartyPlayer.ops?.session()
                    if (c != null && (live == null || live.key != c.key)) {
                        openContent(c) // a reconnect NEVER rebuilds the player mid-episode
                    } else if (c == null && rm.isHost) {
                        // a room created around the player carries its content
                        detectContentChange()
                    }
                }
            }
            "room_state" -> {
                val rm = parseRoom(m.optJSONObject("room")) ?: return
                room.value = rm
                // the snapshot is authoritative: adopt its seq or a replayed
                // older event passes the stale gate and rolls state back
                if (rm.seq > lastSeq) lastSeq = rm.seq
            }
            "playback" -> {
                val r = room.value ?: return
                val seq = m.optInt("seq")
                if (seq != 0 && seq <= lastSeq) return // stale
                if (seq != 0) lastSeq = seq
                val st = m.optJSONObject("state")
                room.value = r.copy(
                    status = st?.optString("status", r.status) ?: r.status,
                    position = st?.optDouble("position", r.position.toDouble())?.toFloat() ?: r.position,
                    rate = st?.optDouble("rate", r.rate.toDouble())?.toFloat() ?: r.rate,
                    updatedAt = st?.optLong("updatedAt", r.updatedAt) ?: r.updatedAt,
                    sessionVersion = st?.optInt("sessionVersion", r.sessionVersion) ?: r.sessionVersion,
                    seq = seq.takeIf { it != 0 } ?: r.seq,
                )
                applyPlayback(m.optString("action"))
            }
            "content" -> {
                val r = room.value ?: return
                val seq = m.optInt("seq")
                if (seq != 0 && seq <= lastSeq) return
                if (seq != 0) lastSeq = seq
                val c = m.optJSONObject("content")
                val content = if (c != null) PartyContent(
                    c.optString("type", "movie"), c.optInt("tmdbId"),
                    max(1, c.optInt("season", 1)), max(1, c.optInt("episode", 1)),
                ) else null
                room.value = r.copy(content = content, sessionVersion = r.sessionVersion + 1, position = 0f, status = "loading")
                if (content != null) openContent(content)
            }
            "source" -> {
                // room-wide mirror ("server") switch: follow it
                val r = room.value ?: return
                val src = m.optString("mirror", "")
                if (src.isEmpty()) return
                room.value = r.copy(source = src)
                try { main.post { PartyPlayer.onSource?.invoke(src) } } catch (e: Exception) {}
            }
            "chat" -> {
                val r = room.value ?: return
                val o = m.optJSONObject("msg") ?: return
                val msg = PartyChatMsg(o.optString("id"), o.optString("from"), o.optString("name"),
                    o.optString("text"), o.optLong("ts"))
                if (r.chat.any { it.id == msg.id }) return // dedupe
                val chat = (r.chat + msg).takeLast(50)
                room.value = r.copy(chat = chat)
                unread.value = unread.value + 1
            }
            "peer_joined" -> {
                val r = room.value ?: return
                val o = m.optJSONObject("participant") ?: return
                val p = PartyPeer(o.optString("id"), o.optString("name"), o.optBoolean("connected"),
                    o.optBoolean("muted"), o.optBoolean("buffering"), o.optBoolean("host"))
                val list = r.participants.filter { it.id != p.id } + p
                room.value = r.copy(participants = list)
            }
            "peer_left" -> {
                val r = room.value ?: return
                val id = m.optString("participantId")
                room.value = r.copy(participants = r.participants.filter { it.id != id })
            }
            "host_changed" -> {
                val r = room.value ?: return
                room.value = r.copy(hostId = m.optString("hostId"),
                    participants = r.participants.map { it.copy(host = it.id == m.optString("hostId")) })
            }
            "settings" -> {
                val r = room.value ?: return
                room.value = r.copy(
                    allowControl = m.optBoolean("allowControl", r.allowControl),
                    chatEnabled = m.optBoolean("chatEnabled", r.chatEnabled),
                )
            }
            "kicked" -> {
                // `kicked` is personal: only the named target tears down
                // (older hubs broadcast it to the whole room - one kick evicted
                // everyone from their own UI)
                val target = m.optString("targetId")
                if (target.isNotEmpty() && target != selfId) return
                disconnect()
                lastError.value = "You were removed from the party"
            }
            "room_ended" -> {
                disconnect()
                lastError.value = "The Watch Party ended"
            }
            "notice" -> {
                if (m.optString("notice") == "room_gone") {
                    disconnect()
                    lastError.value = "The Watch Party is no longer available"
                }
            }
            "error" -> {
                val e = m.optString("error")
                val forJoin = m.optString("for") == "join"
                if (forJoin && room.value == null && pendingMsg != null) {
                    // the handshake failed: retry what's retryable, stop on
                    // the rest with an honest message (never stuck "connecting")
                    if (e == "full" || e == "rate_limited") {
                        rejoinTries += 1
                        if (rejoinTries <= 5) {
                            val msg = pendingMsg
                            main.postDelayed({ if (room.value == null && msg != null) { pendingMsg = msg; send(msg) } },
                                min(15000L, 1500L * rejoinTries))
                            return
                        }
                    }
                    pendingMsg = null
                    status.value = "idle"
                    lastError.value = when (e) {
                        "not_found" -> "Room not found (wrong code or it expired)"
                        "wrong_password" -> "Wrong room password"
                        "full" -> "That room is full"
                        "conflict" -> "Already in a room - leave it first"
                        else -> "Couldn't join the party: " + e
                    }
                    return
                }
                if (e == "conflict") { if (room.value != null) status.value = "in-room"; return }
                lastError.value = when (e) {
                    "not_found" -> "Room not found (wrong code or it expired)"
                    "wrong_password" -> "Wrong room password"
                    "full" -> "That room is full"
                    "rate_limited" -> "Too many attempts - try again later"
                    "unauthorized" -> "Only the host can do that"
                    else -> "Party: " + e
                }
            }
        }
    }

    private fun parseRoom(o: JSONObject?): PartyRoom? {
        if (o == null) return null
        val c = o.optJSONObject("content")
        val pb = o.optJSONObject("playback")
        val peers = ArrayList<PartyPeer>()
        val pa = o.optJSONArray("participants")
        if (pa != null) for (i in 0 until pa.length()) {
            val p = pa.optJSONObject(i) ?: continue
            peers.add(PartyPeer(p.optString("id"), p.optString("name"), p.optBoolean("connected"),
                p.optBoolean("muted"), p.optBoolean("buffering"), p.optBoolean("host")))
        }
        val chat = ArrayList<PartyChatMsg>()
        val ca = o.optJSONArray("chat")
        if (ca != null) for (i in 0 until ca.length()) {
            val x = ca.optJSONObject(i) ?: continue
            chat.add(PartyChatMsg(x.optString("id"), x.optString("from"), x.optString("name"),
                x.optString("text"), x.optLong("ts")))
        }
        return PartyRoom(
            roomId = o.optString("roomId"), code = o.optString("code"), name = o.optString("name"),
            hostId = o.optString("hostId"), selfId = o.optString("selfId", selfId),
            hasPassword = o.optBoolean("hasPassword"), allowControl = o.optBoolean("allowControl"),
            chatEnabled = o.optBoolean("chatEnabled", true),
            content = if (c != null) PartyContent(c.optString("type", "movie"), c.optInt("tmdbId"),
                max(1, c.optInt("season", 1)), max(1, c.optInt("episode", 1))) else null,
            source = o.optString("source", ""),
            status = pb?.optString("status", "waiting") ?: "waiting",
            position = pb?.optDouble("position", 0.0)?.toFloat() ?: 0f,
            rate = pb?.optDouble("rate", 1.0)?.toFloat() ?: 1f,
            updatedAt = pb?.optLong("updatedAt", System.currentTimeMillis()) ?: System.currentTimeMillis(),
            sessionVersion = pb?.optInt("sessionVersion", 1) ?: 1,
            seq = pb?.optInt("seq", 0) ?: 0,
            participants = peers, chat = chat,
        )
    }

    private var gateRunnable: Runnable? = null
    private fun scheduleGate() {
        val pb = room.value ?: return
        val wait = max(0L, pb.updatedAt - serverNow()) + 250L
        gateRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { try { syncToRoom(0) } catch (e: Exception) {} }
        gateRunnable = r
        main.postDelayed(r, wait)
    }

    /** Bring THIS player onto the room timeline (join, episode change, gate).
        Waits for real player readiness; never starts late-joiners at 00:00. */
    private fun syncToRoom(tries: Int) {
        if (room.value == null) return
        val ops = PartyPlayer.ops ?: run {
            if (tries < 24) main.postDelayed({ syncToRoom(tries + 1) }, 500) // player still opening
            return
        }
        val r = room.value ?: return
        if (r.status == "playing" && r.updatedAt > serverNow()) {
            try { if (!ops.isPaused()) ops.pause() } catch (e: Exception) {}
            scheduleGate()
            return
        }
        suppressUntil = System.currentTimeMillis() + 1500
        val target = expectedPos()
        try {
            if (r.status == "playing") {
                if (ops.isPaused()) ops.play()
                ops.seek(target + 0.25f)
            } else {
                if (!ops.isPaused()) ops.pause()
                if (abs(ops.position() - target) > 0.6f) ops.seek(target)
            }
        } catch (e: Exception) {}
    }

    private fun applyPlayback(action: String) {
        val ops = PartyPlayer.ops ?: return
        val pb = room.value ?: return
        if (pb.status == "playing" && pb.updatedAt > serverNow() + 300) {
            scheduleGate() // scheduled start - hold everyone at the gate
            return
        }
        // own echo (the hub broadcasts back to the sender) must never
        // suppress the user's NEXT press - only remote-driven changes may
        if (System.currentTimeMillis() >= localSentAt + 1200) suppressUntil = System.currentTimeMillis() + 1500
        if (action == "play" || action == "pause") lastRelayedPaused = (action == "pause")
        try {
            val target = expectedPos() + 0.25f
            when (action) {
                "play" -> {
                    if (ops.isPaused()) ops.play()
                    if (abs(ops.position() - expectedPos()) > 0.6f) ops.seek(target)
                }
                "pause" -> {
                    if (!ops.isPaused()) ops.pause()
                    if (abs(ops.position() - expectedPos()) > 0.6f) ops.seek(expectedPos())
                }
                "seek" -> ops.seek(target)
            }
        } catch (e: Exception) {}
    }

    private fun openContent(c: PartyContent) {
        lastContentKey = c.key
        lastLoadAtMs = System.currentTimeMillis() // drift grace starts here
        suppressUntil = System.currentTimeMillis() + 4000
        try { main.post { PartyPlayer.open?.invoke(c) } } catch (e: Exception) {}
        // joiners used to open the video and sit at 00:00 - sync to the room
        // timeline the moment THIS player reports ready
        main.postDelayed({ try { syncToRoom(0) } catch (e: Exception) {} }, 800)
    }

    // ---------- session persistence (rejoin after app restart) ----------
    private fun saveSession() {
        val c = ctx ?: return
        val r = room.value ?: return
        try {
            com.streambox.nativeapp.data.Store.saveParty(c, r.code, token, name, joinPw)
        } catch (e: Exception) {}
    }

    private fun clearSession() {
        val c = ctx ?: return
        try { com.streambox.nativeapp.data.Store.saveParty(c, "", "", "", "") } catch (e: Exception) {}
    }

    /** Restore after process death / relaunch: rejoin by resume token. */
    fun restoreSession() {
        val c = ctx ?: return
        val s = try { com.streambox.nativeapp.data.Store.party(c) } catch (e: Exception) { null } ?: return
        if (s.code.isBlank()) return
        token = s.token
        name = s.name.ifBlank { name }
        joinPw = s.password
        join(s.code, s.password, s.token)
    }
}
