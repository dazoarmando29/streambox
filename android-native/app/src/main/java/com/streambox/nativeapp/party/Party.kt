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
}

object PartyPlayer {
    @Volatile var ops: PartyOps? = null
    @Volatile var open: ((PartyContent) -> Unit)? = null
}

object Party {
    // ---- tunables (mirror the website CFG) ----
    private const val SYNC_NUDGE = 0.35f
    private const val SYNC_SEEK = 3.0f
    private const val SYNC_INTERVAL = 5000L
    private const val SYNC_MIN_GAP = 3000L
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

    private fun send(o: JSONObject) {
        try { ws?.send(o.toString()) } catch (e: Exception) {}
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
                    send(JSONObject().put("t", "join").put("name", displayName())
                        .put("code", code).put("token", token).put("password", joinPw))
                }
                webSocket.send(JSONObject().put("t", "heartbeat").put("clientTs", System.currentTimeMillis()).toString())
                scheduleHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try { handle(JSONObject(text)) } catch (e: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ws === webSocket) ws = null
                if (room.value != null) {
                    status.value = "reconnecting"
                    retry()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws === webSocket) ws = null
                if (room.value != null) {
                    status.value = "reconnecting"
                    retry()
                }
            }
        })
    }

    private fun retry() {
        if (room.value == null) return
        reconnects += 1
        val base = min(15000L, (500.0 * Math.pow(1.8, min(reconnects, 8).toDouble())).toLong())
        val wait = (base * (0.7 + Random.nextDouble() * 0.6)).toLong()
        retryRunnable?.let { main.removeCallbacks(it) }
        val r = Runnable { if (ws == null && room.value != null) connect() }
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
        retryRunnable?.let { main.removeCallbacks(it) }
        hbRunnable?.let { main.removeCallbacks(it) }
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
        connect()
        val go = Runnable {
            send(JSONObject().put("t", "create").put("name", displayName())
                .put("roomName", roomName.trim().take(40)).put("password", password)
                .put("maxParticipants", 8).put("allowControl", allowControl).put("chatEnabled", chatOn))
        }
        main.post(go)
    }

    fun join(code: String, password: String, resumeToken: String = token) {
        wantRoom = code.trim().uppercase()
        joinPw = password
        connect()
        val go = Runnable {
            send(JSONObject().put("t", "join").put("name", displayName())
                .put("code", wantRoom ?: "").put("password", joinPw).put("token", resumeToken))
        }
        main.post(go)
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

    fun onLocalPlayPause(paused: Boolean) {
        val r = room.value ?: return
        if (!r.canControl() || suppressLocal()) return
        send(JSONObject().put("t", "playback")
            .put("action", if (paused) "pause" else "play")
            .put("position", PartyPlayer.ops?.position() ?: 0f)
            .put("sessionVersion", r.sessionVersion))
    }

    fun onLocalSeek(seconds: Float) {
        val r = room.value ?: return
        if (!r.canControl() || suppressLocal()) return
        send(JSONObject().put("t", "playback").put("action", "seek")
            .put("position", max(0f, seconds)).put("sessionVersion", r.sessionVersion))
    }

    private fun detectContentChange() {
        val r = room.value ?: return
        if (!r.isHost || suppressLocal()) return
        val s = PartyPlayer.ops?.session() ?: return
        if (s.key != lastContentKey) {
            lastContentKey = s.key
            send(JSONObject().put("t", "content").put("type", s.type)
                .put("tmdbId", s.tmdbId).put("season", s.season).put("episode", s.episode))
        }
    }

    /** Non-hosts never auto-advance locally - the host's content event drives. */
    fun suppressAutoAdvance(): Boolean = room.value != null && status.value == "in-room" && room.value?.isHost != true

    // ---------- sync math (mirrors party-server protocol.js) ----------
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
        val drift = ops.position() - expectedPos()
        if (abs(drift) < SYNC_NUDGE) return
        val now = System.currentTimeMillis()
        if (now - lastCorrect < SYNC_MIN_GAP) return // never a correction loop
        lastCorrect = now
        suppressUntil = now + 1500
        try {
            if (abs(drift) >= SYNC_SEEK) ops.seek(expectedPos() + 0.3f)
            else ops.seek(expectedPos() + 0.3f) // gentle rate nudges need a
            // provider rate API only CineSrc-style bridges expose; on the
            // native shell a light seek is the reliable correction
        } catch (e: Exception) {}
    }

    // ---------- server events ----------
    private fun handle(m: JSONObject) {
        when (m.optString("t")) {
            "welcome" -> {
                if (m.has("participantId")) selfId = m.optString("participantId")
                if (m.has("token")) token = m.optString("token")
            }
            "heartbeat" -> {
                val t1 = System.currentTimeMillis()
                val t0 = m.optLong("clientTs", t1)
                val off = m.optLong("serverTs", t1) - (t0 + t1) / 2
                offsetMs = if (offsetMs == 0L) off else (offsetMs * 0.7 + off * 0.3).toLong()
            }
            "room" -> {
                val rm = parseRoom(m.optJSONObject("room"))
                if (rm != null) {
                    room.value = rm
                    status.value = "in-room"
                    lastSeq = rm.seq
                    lastContentKey = rm.content?.key ?: ""
                    unread.value = 0
                    if (m.has("token")) token = m.optString("token")
                    saveSession()
                    val c = rm.content
                    if (c != null) openContent(c)
                }
            }
            "room_state" -> {
                val rm = parseRoom(m.optJSONObject("room")) ?: return
                room.value = rm
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
            status = pb?.optString("status", "waiting") ?: "waiting",
            position = pb?.optDouble("position", 0.0)?.toFloat() ?: 0f,
            rate = pb?.optDouble("rate", 1.0)?.toFloat() ?: 1f,
            updatedAt = pb?.optLong("updatedAt", System.currentTimeMillis()) ?: System.currentTimeMillis(),
            sessionVersion = pb?.optInt("sessionVersion", 1) ?: 1,
            seq = pb?.optInt("seq", 0) ?: 0,
            participants = peers, chat = chat,
        )
    }

    private fun applyPlayback(action: String) {
        val ops = PartyPlayer.ops ?: return
        suppressUntil = System.currentTimeMillis() + 1500
        try {
            val target = expectedPos() + 0.3f
            when (action) {
                "play" -> {
                    if (ops.isPaused()) ops.play()
                    if (abs(ops.position() - expectedPos()) > SYNC_SEEK) ops.seek(target)
                }
                "pause" -> {
                    if (!ops.isPaused()) ops.pause()
                    if (abs(ops.position() - expectedPos()) > 1.5f) ops.seek(expectedPos())
                }
                "seek" -> ops.seek(target)
            }
        } catch (e: Exception) {}
    }

    private fun openContent(c: PartyContent) {
        lastContentKey = c.key
        suppressUntil = System.currentTimeMillis() + 4000
        try { main.post { PartyPlayer.open?.invoke(c) } } catch (e: Exception) {}
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
