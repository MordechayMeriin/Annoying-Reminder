package com.prominder.myapp.ui

import com.prominder.myapp.data.MessageKind
import com.prominder.myapp.data.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.prominder.myapp.logic.ChipRules.ChipJson
import com.prominder.myapp.ui.theme.Palette

/*
 * The screen's models are @Immutable: their lists are never changed after they're made, so Compose may compare them
 * by value and skip redrawing what didn't change (plain List fields would otherwise make them "unstable").
 */

data class FocusItem(val id: Long, val title: String, val meta: String, val done: Boolean)

/**
 * A chat line; [day] is set on the first message of each day (the separator above it). [chips]: its reply buttons
 * (shown under the latest message only); [model]: the LLM that wrote it, shown when ⚙ Show which AI model replied is on.
 */
@Immutable
data class ChatItem(
    val id: Long, val role: String, val kind: String, val content: String, val time: String, val day: String? = null,
    val chips: List<ChipJson> = emptyList(), val model: String? = null,
)

/** The turn in progress, for the chat (from llm/Chat.kt's ChatTurn). */
@Immutable
data class ChatUi(
    val sending: String? = null,
    val sentId: Long? = null,
    val steps: List<String> = emptyList(),
    val typing: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = false,
)

@Immutable
data class HomeState(
    val focus: List<FocusItem> = emptyList(),
    val messages: List<ChatItem> = emptyList(),
    val openCount: Int = 0,
    val setupDone: Int = 0,
    val setupTotal: Int = 0,
    /** A short answer to the last tap ("Later: next check-in Wed 14:00"). */
    val notice: String? = null,
    val chat: ChatUi = ChatUi(),
    /** Dictation is possible on this phone (the 🎤 button). */
    val canDictate: Boolean = false,
    /** A focus session runs (the bar above the chat), or null. */
    val session: SessionBarUi? = null,
    /** "Where did you stop?" is offered, or null. */
    val breadcrumb: BreadcrumbUi? = null,
    /** The text in its field. */
    val breadcrumbDraft: String = "",
    /** Focus sessions started today (the wins: "⏱ 2"). */
    val sessionsToday: Int = 0,
    /** Screenshot tests: a fixed clock for the session bar (epoch ms); null = the real one. */
    val fixedNow: Long? = null,
)

/**
 * The main screen: Today's 3 on top, the chat with the assistant below (Phase D). The reply
 * buttons sit under the latest message; while the assistant works, its steps and the typing dots show under the chat.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onOpenTasks: () -> Unit,
    onOpenTask: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onPickFocus: () -> Unit,
    onNewTask: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChecklist: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onLogout: () -> Unit,
    /** The text in the message field, read by the field alone: typing doesn't redraw the rest of the screen. */
    draft: () -> String = { "" },
    onDraft: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onChip: (ChipJson) -> Unit = {},
    onMic: () -> Unit = {},
    onRetry: () -> Unit = {},
    /** Changes each time the field should take the focus (the "New task" shortcut). */
    focusInput: Int = 0,
    /** ▶ on a Today's 3 row: a focus session (25 min). */
    onStart: (Long) -> Unit = {},
    onOpenSession: () -> Unit = {},
    onBreadcrumbDraft: (String) -> Unit = {},
    /** Save (the text) or Skip (null). */
    onBreadcrumb: (String?) -> Unit = {},
    onBreadcrumbMic: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        var menu by remember { mutableStateOf(false) }
        TopBar("🔔 ProMinder") {
            TextButton(onClick = onOpenTasks) { Text("☑ Tasks" + if (state.openCount > 0) " ${state.openCount}" else "", color = Palette.Text) }
            TextButton(onClick = onOpenSettings) { Text("⚙", color = Palette.Text, fontSize = 18.sp) }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮", color = Palette.Text, fontSize = 18.sp) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Phone setup (${state.setupDone}/${state.setupTotal})") }, onClick = { menu = false; onOpenChecklist() })
                    DropdownMenuItem(text = { Text("Diagnostics") }, onClick = { menu = false; onOpenDiagnostics() })
                    DropdownMenuItem(text = { Text("Log out") }, onClick = { menu = false; onLogout() })
                }
            }
        }

        Column(Modifier.padding(horizontal = 12.dp)) {
            if (state.setupTotal > 0 && state.setupDone < state.setupTotal) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Card2).clickable(onClick = onOpenChecklist)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("! ", color = Palette.Accent, fontWeight = FontWeight.Bold)
                    Text("Phone setup: ${state.setupDone} of ${state.setupTotal} done", modifier = Modifier.weight(1f), fontSize = 14.sp)
                    Text("›", color = Palette.Muted)
                }
                Spacer(Modifier.height(8.dp))
            }
            state.session?.let { bar ->
                SessionBar(bar, rememberTicker(state.fixedNow), onOpenSession)
                Spacer(Modifier.height(8.dp))
            }
            FocusCard(state.focus, state.sessionsToday, onOpenTask, onDone, onPickFocus, onStart)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val chat = state.chat
            val last = state.messages.lastOrNull()
            // The reply buttons: under the latest message while it's the assistant's and nothing is being sent.
            val chipsFor = last?.takeIf { it.role == Role.ASSISTANT && it.chips.isNotEmpty() && chat.sending == null }?.id
            val modelFor = state.messages.lastOrNull { it.role == Role.ASSISTANT }?.id
            if (state.messages.isEmpty() && chat.sending == null) {
                Muted("No messages yet. Write anything: a task, a question, how it's going.", Modifier.align(Alignment.Center).padding(24.dp))
            } else {
                // Newest at the bottom, opening at the bottom.
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), reverseLayout = true) {
                    item(key = "status") { ChatStatus(chat, chat.sending != null && chat.sentId == null, onRetry) }
                    items(state.messages.asReversed(), key = { it.id }) { m ->
                        Column {
                            m.day?.let {
                                Text(it, color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp))
                            }
                            ChatLine(m, delivery = if (m.id == chat.sentId) "✓ Sent" else null, model = m.model.takeIf { m.id == modelFor })
                            if (m.id == chipsFor) ChipRow(m.chips, onChip)
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Palette.Card).padding(horizontal = 8.dp, vertical = 6.dp)) {
            state.notice?.let {
                Text(it, color = Palette.Accent, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp))
                Spacer(Modifier.height(4.dp))
            }
            state.breadcrumb?.let { Breadcrumb(it, state.breadcrumbDraft, state.canDictate, onBreadcrumbDraft, onBreadcrumb, onBreadcrumbMic) }
            Composer(state.canDictate, state.chat.sending == null, draft, onDraft, onSend, onMic, onNewTask, focusInput)
        }
    }
}

/** The message field with ＋ (a task by hand), 🎤 (dictation) and Send. */
@Composable
private fun Composer(
    canDictate: Boolean, idle: Boolean, draft: () -> String, onDraft: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit,
    onNewTask: () -> Unit, focusInput: Int,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusInput) {
        if (focusInput > 0) {
            runCatching { focus.requestFocus() }
            keyboard?.show()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onNewTask, modifier = Modifier.size(44.dp)) { Text("＋", fontSize = 20.sp, color = Palette.Muted) }
        val text = draft()
        OutlinedTextField(
            value = text,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text("Message", color = Palette.Muted) },
            maxLines = 5,
        )
        if (canDictate) {
            TextButton(onClick = onMic, modifier = Modifier.size(44.dp)) { Text("🎤", fontSize = 18.sp) }
        }
        val canSend = text.isNotBlank() && idle
        Button(onClick = onSend, enabled = canSend, modifier = Modifier.padding(start = 4.dp)) { Text("Send") }
    }
}

/** Under the chat: the message being sent, this turn's tool steps, the typing dots, or why there's no reply. */
@Composable
private fun ChatStatus(chat: ChatUi, showSending: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (showSending && chat.sending != null) {
            ChatLine(ChatItem(-1, "user", "chat", chat.sending, ""), delivery = "Sending…", model = null)
        }
        chat.steps.forEachIndexed { i, step ->
            val running = i == chat.steps.lastIndex && !chat.typing
            Text(step, color = if (running) Palette.Text else Palette.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        if (chat.typing) TypingDots()
        chat.error?.let {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(it, color = Palette.Danger, fontSize = 13.sp, modifier = Modifier.weight(1f))
                if (chat.canRetry) TextButton(onClick = onRetry) { Text("Try again") }
            }
        }
    }
}

/** The assistant is writing: three dots that pulse in turn. */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card).padding(horizontal = 12.dp, vertical = 10.dp)) {
        repeat(3) { i ->
            val a by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse), label = "dot$i")
            Box(Modifier.padding(horizontal = 3.dp).size(7.dp).alpha(a).clip(CircleShape).background(Palette.Muted))
        }
    }
}

/** The reply buttons: an action (✓ Done, Later…) or a reply in your words (I'm stuck). The first action stands out. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(chips: List<ChipJson>, onChip: (ChipJson) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
        chips.forEachIndexed { i, c ->
            val primary = i == 0 && c.action != null
            if (primary) {
                Button(onClick = { onChip(c) }, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) { Text(c.label, fontSize = 14.sp) }
            } else {
                OutlinedButton(onClick = { onChip(c) }, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) { Text(c.label, fontSize = 14.sp) }
            }
        }
    }
}

/**
 * "Where did you stop on “Taxes”? (optional)": after you tap Stop, or don't answer a session's end. Save keeps the line
 * for the next session and check-in; Skip (or doing nothing for a few hours) changes nothing.
 */
@Composable
private fun Breadcrumb(b: BreadcrumbUi, draft: String, canDictate: Boolean, onDraft: (String) -> Unit, onDone: (String?) -> Unit, onMic: () -> Unit) {
    val he = b.lang == "he"
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(if (he) "איפה עצרת ב„${b.title}”? (לא חובה)" else "Where did you stop on “${b.title}”? (optional)", color = Palette.Muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text(if (he) "למשל: עמוד 3" else "e.g. page 3", color = Palette.Muted) })
            if (canDictate) TextButton(onClick = onMic, modifier = Modifier.size(44.dp)) { Text("🎤", fontSize = 18.sp) }
            TextButton(onClick = { onDone(draft.trim().ifEmpty { null }) }) { Text(if (he) "שמירה" else "Save") }
            TextButton(onClick = { onDone(null) }) { Text(if (he) "דילוג" else "Skip", color = Palette.Muted) }
        }
    }
}

@Composable
private fun FocusCard(items: List<FocusItem>, sessions: Int, onOpen: (Long) -> Unit, onDone: (Long) -> Unit, onPick: () -> Unit, onStart: (Long) -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY'S 3", color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            // The day's wins: no streaks, no zeros.
            val wins = listOfNotNull(if (items.isNotEmpty()) "${items.count { it.done }}/${items.size} done" else null,
                if (sessions > 0) "⏱ $sessions session${if (sessions == 1) "" else "s"}" else null)
            if (wins.isNotEmpty()) Text(wins.joinToString(" · "), color = Palette.Muted, fontSize = 12.sp)
            TextButton(onClick = onPick) { Text(if (items.isEmpty()) "Pick" else "Change") }
        }
        if (items.isEmpty()) {
            Muted("Nothing picked for today. Pick up to three, or tap ☆ on a task.")
        }
        items.forEachIndexed { i, item ->
            Row(Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                DoneCircle(item.done) { if (!item.done) onDone(item.id) }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text("${i + 1}. ${item.title}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        color = if (item.done) Palette.Muted else Palette.Text)
                    if (item.meta.isNotEmpty()) Text(item.meta, color = Palette.Muted, fontSize = 12.sp)
                }
                if (!item.done) {
                    TextButton(onClick = { onStart(item.id) }, modifier = Modifier.size(44.dp)) { Text("▶", color = Palette.Accent, fontSize = 18.sp) }
                }
            }
        }
    }
}

/** The round ✓ button in lists. */
@Composable
fun DoneCircle(done: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (done) Palette.Ok else Palette.Card2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", color = if (done) Palette.Bg else Palette.Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
private fun ChatLine(m: ChatItem, delivery: String? = null, model: String? = null) {
    if (m.kind == MessageKind.EVENT) {
        Text("${m.content}  ${m.time}", color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        return
    }
    val mine = m.role == Role.USER
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) Palette.Card2 else Palette.Card)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            model?.let { Text(it.substringAfterLast('/'), color = Palette.Muted, fontSize = 11.sp) }
            Text(m.content, style = MaterialTheme.typography.bodyMedium)
            Text(listOfNotNull(m.time.ifEmpty { null }, delivery).joinToString(" · "), color = Palette.Muted, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.End))
        }
    }
}

/** Choose up to three open tasks for Today's 3, in the order you tick them. */
@Composable
fun FocusPickerDialog(candidates: List<Pair<Long, String>>, selected: List<Long>, onSave: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val picked = remember { mutableStateListOf<Long>().apply { addAll(selected) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Today's 3") },
        text = {
            if (candidates.isEmpty()) {
                Muted("No open tasks to pick from. Add one with ＋ New task.")
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.first }) { (id, title) ->
                        val rank = picked.indexOf(id)
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (rank >= 0) picked.remove(id) else if (picked.size < 3) picked.add(id)
                            }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = rank >= 0, onCheckedChange = null, enabled = rank >= 0 || picked.size < 3)
                            Text((if (rank >= 0) "${rank + 1}. " else "") + title, modifier = Modifier.padding(start = 8.dp), maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(picked.toList()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
