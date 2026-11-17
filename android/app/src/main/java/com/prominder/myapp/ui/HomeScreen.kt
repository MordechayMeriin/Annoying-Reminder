package com.prominder.myapp.ui

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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.ui.theme.Palette

data class FocusItem(val id: Long, val title: String, val meta: String, val done: Boolean)

/** A chat line; [day] is set on the first message of each day (the separator above it). */
data class ChatItem(val id: Long, val role: String, val kind: String, val content: String, val time: String, val day: String? = null)

data class HomeState(
    val focus: List<FocusItem> = emptyList(),
    val messages: List<ChatItem> = emptyList(),
    val openCount: Int = 0,
    val setupDone: Int = 0,
    val setupTotal: Int = 0,
    /** A short answer to the last tap ("Later: next check-in Wed 14:00"). */
    val notice: String? = null,
)

/**
 * The main screen, like the web app's: Today's 3 on top, the chat below. Until the assistant moves to the phone
 * (Phase D) the chat is the history, read-only; tasks are added and changed by hand.
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
            FocusCard(state.focus, onOpenTask, onDone, onPickFocus)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.messages.isEmpty()) {
                Muted("No messages yet.", Modifier.align(Alignment.Center))
            } else {
                // Newest at the bottom, opening at the bottom.
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), reverseLayout = true) {
                    items(state.messages.asReversed(), key = { it.id }) { m ->
                        Column {
                            m.day?.let {
                                Text(it, color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp))
                            }
                            ChatLine(m)
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Palette.Card).padding(horizontal = 12.dp, vertical = 8.dp)) {
            state.notice?.let {
                Text(it, color = Palette.Accent, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Muted("Chat and reminders come back in the next updates.", Modifier.weight(1f))
                Button(onClick = onNewTask) { Text("＋ New task") }
            }
        }
    }
}

@Composable
private fun FocusCard(items: List<FocusItem>, onOpen: (Long) -> Unit, onDone: (Long) -> Unit, onPick: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY'S 3", color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) Text("${items.count { it.done }}/${items.size} done", color = Palette.Muted, fontSize = 12.sp)
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
private fun ChatLine(m: ChatItem) {
    if (m.kind == "event") {
        Text("${m.content}  ${m.time}", color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        return
    }
    val mine = m.role == "user"
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(14.dp)).background(if (mine) Palette.Card2 else Palette.Card)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(m.content, style = MaterialTheme.typography.bodyMedium)
            Text(m.time, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.End))
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
