package com.streambox.nativeapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.streambox.nativeapp.AppEnv
import com.streambox.nativeapp.party.Party
import com.streambox.nativeapp.party.PartyChatMsg
import com.streambox.nativeapp.party.PartyPeer

/* Watch Party room screen. Same Obsidian Glow language as every other
   screen (Glass cards, Pill buttons, focusRing); TV grows targets and the
   room code for 10-foot reading. Chat is fully usable from the D-pad via
   the quick-message pills (typing is never mandatory on TV). */

private val QUICK = listOf("Hello!", "Ready!", "One sec", "LOL", "Brb", "Bye!")

/** House card panel (the Card color token - same pattern as UpdateDialog). */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.background(Card, RoundedCornerShape(24.dp)), content = content)
}

@Composable
fun PartyScreen(initialCode: String?, onBack: () -> Unit) {
    val big = AppEnv.isTv
    val room by Party.room
    val status by Party.status
    val err by Party.lastError
    val clip = LocalClipboardManager.current

    var name by remember { mutableStateOf(Party.displayName().let { if (it == "Guest") "" else it }) }
    var roomName by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf(initialCode ?: "") }
    var joinPw by remember { mutableStateOf("") }
    var allowCtl by remember { mutableStateOf(false) }
    var chatOn by remember { mutableStateOf(true) }
    var chatInput by remember { mutableStateOf("") }

    LaunchedEffect(initialCode) {
        if (!initialCode.isNullOrBlank() && Party.room.value == null) {
            joinCode = initialCode.uppercase()
        }
    }
    LaunchedEffect(status, err) { /* recomposition hooks keep errors visible */ }

    BackHandler { onBack() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .padding(horizontal = if (big) 40.dp else 16.dp, vertical = if (big) 28.dp else 12.dp)
            .imePadding(),
    ) {
        // ---- header: same rhythm as every screen's top bar ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("WATCH PARTY", color = Accent, fontWeight = FontWeight.Black,
                fontSize = if (big) 22.sp else 13.sp, letterSpacing = 2.sp)
            Spacer(Modifier.width(12.dp))
            val cap = Party.syncLabel()
            if (cap.isNotEmpty()) {
                Text(cap, color = Soft, fontSize = if (big) 15.sp else 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.background(Glass, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            PillButton("Close", big, accent = false, onClick = onBack)
        }
        if (err.isNotBlank()) {
            Text(err, color = Amber, fontSize = if (big) 16.sp else 12.sp,
                modifier = Modifier.padding(top = 8.dp))
        }

        if (room == null) {
            Lobby(
                big = big,
                name = name, onName = { name = it },
                roomName = roomName, onRoomName = { roomName = it },
                pw = pw, onPw = { pw = it },
                allowCtl = allowCtl, onAllowCtl = { allowCtl = it },
                chatOn = chatOn, onChatOn = { chatOn = it },
                joinCode = joinCode, onJoinCode = { joinCode = it.uppercase().take(6) },
                joinPw = joinPw, onJoinPw = { joinPw = it },
                onCreate = {
                    Party.setName(name)
                    Party.create(roomName, pw, allowCtl, chatOn)
                },
                onJoin = {
                    Party.setName(name)
                    Party.join(joinCode, joinPw)
                },
            )
        } else {
            Room(
                big = big,
                chatInput = chatInput, onChatInput = { chatInput = it },
                onSend = { val t = chatInput; chatInput = ""; Party.sendChat(t) },
                onQuick = { Party.sendChat(it) },
                onCopyInvite = { clip.setText(AnnotatedString(Party.inviteUrl())) },
                onCopyCode = { clip.setText(AnnotatedString(room!!.code)) },
                onResync = { Party.resync() },
                onLeave = { Party.leave(); onBack() },
                onEnd = { Party.endRoom(); onBack() },
                onKick = { Party.kick(it) },
                onCtl = { Party.setControls(it) },
                onChatToggle = { Party.setChatEnabled(it) },
            )
        }
    }
}

@Composable
private fun Lobby(
    big: Boolean,
    name: String, onName: (String) -> Unit,
    roomName: String, onRoomName: (String) -> Unit,
    pw: String, onPw: (String) -> Unit,
    allowCtl: Boolean, onAllowCtl: (Boolean) -> Unit,
    chatOn: Boolean, onChatOn: (Boolean) -> Unit,
    joinCode: String, onJoinCode: (String) -> Unit,
    joinPw: String, onJoinPw: (String) -> Unit,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(if (big) 18.dp else 12.dp),
        modifier = Modifier.fillMaxSize().padding(top = if (big) 18.dp else 10.dp),
    ) {
        item {
            Field(big, "YOUR NAME", name, onName, "Who's watching?")
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(if (big) 22.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Start a party", color = Soft, fontWeight = FontWeight.Bold, fontSize = if (big) 19.sp else 15.sp)
                    Field(big, "ROOM NAME (OPTIONAL)", roomName, onRoomName, "Movie night")
                    Field(big, "PASSWORD (OPTIONAL)", pw, onPw, "Leave empty for open", password = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Check(big, "Guests can control playback", allowCtl, onAllowCtl)
                        Spacer(Modifier.width(16.dp))
                        Check(big, "Chat", chatOn, onChatOn)
                    }
                    PillButton("Create Room", big, accent = true, onClick = onCreate)
                }
            }
        }
        item {
            Panel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(if (big) 22.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Join with a code", color = Soft, fontWeight = FontWeight.Bold, fontSize = if (big) 19.sp else 15.sp)
                    Field(big, "ROOM CODE", joinCode, onJoinCode, "ABC234")
                    if (joinCode.length >= 4) Field(big, "PASSWORD (IF SET)", joinPw, onJoinPw, "", password = true)
                    PillButton("Join", big, accent = true, onClick = onJoin)
                }
            }
        }
    }
}

@Composable
private fun Room(
    big: Boolean,
    chatInput: String, onChatInput: (String) -> Unit,
    onSend: () -> Unit, onQuick: (String) -> Unit,
    onCopyInvite: () -> Unit, onCopyCode: () -> Unit,
    onResync: () -> Unit, onLeave: () -> Unit, onEnd: () -> Unit,
    onKick: (String) -> Unit, onCtl: (Boolean) -> Unit, onChatToggle: (Boolean) -> Unit,
) {
    val room = Party.room.value ?: return
    val listState = rememberLazyListState()
    LaunchedEffect(room.chat.size) {
        if (room.chat.isNotEmpty()) listState.animateScrollToItem(room.chat.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        // ---- identity block: name + the code readable from the couch ----
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(if (big) 22.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(room.name, color = Soft, fontWeight = FontWeight.Black,
                        fontSize = if (big) 21.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (room.content?.let { if (it.type == "movie") "Movie" else "S${it.season} E${it.episode}" } ?: "Nothing yet") +
                            " \u00B7 " + room.status + (if (room.isHost) " \u00B7 you host" else ""),
                        color = Dim, fontSize = if (big) 15.sp else 11.sp, maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(room.code, color = Accent, fontWeight = FontWeight.Black,
                        fontSize = if (big) 34.sp else 24.sp, letterSpacing = if (big) 10.sp else 6.sp)
                    Text("ROOM CODE", color = Dim, fontSize = 10.sp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            PillButton("Copy Invite", big, accent = false, onClick = onCopyInvite)
            PillButton("Copy Code", big, accent = false, onClick = onCopyCode)
            PillButton("Resync", big, accent = false, onClick = onResync)
            PillButton("Leave", big, accent = false, onClick = onLeave)
            if (room.isHost) PillButton("End Party", big, accent = false, onClick = onEnd)
        }

        // ---- participants ----
        Text("WATCHING (" + room.participants.size + ")", color = Dim, fontWeight = FontWeight.Bold,
            fontSize = if (big) 15.sp else 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f).heightIn(max = if (big) 180.dp else 120.dp)) {
                items(room.participants, key = { it.id }) { p ->
                    PeerRow(big, p, canKick = room.isHost && p.id != room.selfId, onKick = onKick)
                }
            }
        }

        if (room.isHost) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Check(big, "Guests control playback", room.allowControl, onCtl)
                Spacer(Modifier.width(16.dp))
                Check(big, "Chat", room.chatEnabled, onChatToggle)
            }
        }

        // ---- chat ----
        if (room.chatEnabled) {
            Text("CHAT", color = Dim, fontWeight = FontWeight.Bold,
                fontSize = if (big) 15.sp else 11.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            Panel(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(room.chat, key = { it.id }) { m -> ChatRow(big, m, room.selfId) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                QUICK.forEach { q ->
                    PillButton(q, big, accent = false, onClick = { onQuick(q) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(
                    value = chatInput, onValueChange = { if (it.length <= 300) onChatInput(it) },
                    singleLine = true, placeholder = { Text("Say something...", color = Dim) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    colors = partyFieldColors(),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PillButton("Send", big, accent = true, onClick = onSend)
            }
        }
    }
}

@Composable
private fun PeerRow(big: Boolean, p: PartyPeer, canKick: Boolean, onKick: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Spacer(
            Modifier.size(8.dp).background(if (p.connected) Accent else Dim, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(p.name + (if (p.host) " \u2605" else "") + (if (!p.connected) " (away)" else "") + (if (p.muted) " \uD83D\uDD07" else ""),
            color = if (p.host) Accent else Soft, fontSize = if (big) 17.sp else 13.sp,
            fontWeight = if (p.host) FontWeight.Bold else FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (canKick) PillButton("Remove", big, accent = false, onClick = { onKick(p.id) })
    }
}

@Composable
private fun ChatRow(big: Boolean, m: PartyChatMsg, selfId: String) {
    val mine = m.from == selfId
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Text(m.name + " \u00B7 " + ts(m.ts), color = if (mine) Dim else Accent,
            fontSize = if (big) 12.sp else 10.sp, fontWeight = FontWeight.Bold)
        Text(m.text, color = Soft, fontSize = if (big) 17.sp else 13.sp,
            modifier = Modifier
                .background(if (mine) Glass else Pill, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

private fun ts(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format("%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
}

@Composable
private fun Field(big: Boolean, label: String, value: String, onValue: (String) -> Unit, hint: String, password: Boolean = false) {
    Column {
        Text(label, color = Dim, fontWeight = FontWeight.Bold, fontSize = if (big) 13.sp else 10.sp, letterSpacing = 1.sp)
        OutlinedTextField(
            value = value, onValueChange = onValue, singleLine = true,
            placeholder = { Text(hint, color = Dim) },
            visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            colors = partyFieldColors(),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun Check(big: Boolean, label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = on, onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Dim))
        Text(label, color = Soft, fontSize = if (big) 16.sp else 12.sp)
    }
}

@Composable
private fun partyFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Accent, unfocusedBorderColor = Stroke,
    focusedTextColor = Soft, unfocusedTextColor = Soft,
    focusedContainerColor = Pill, unfocusedContainerColor = Pill,
    cursorColor = Accent,
)
