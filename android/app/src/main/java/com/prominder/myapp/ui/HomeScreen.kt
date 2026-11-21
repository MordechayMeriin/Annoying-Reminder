package com.prominder.myapp.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prominder.myapp.R
import com.prominder.myapp.data.MessageKind
import com.prominder.myapp.data.Role
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
    /** Notifications are off: nothing can reach you, so it's a banner and not only a dot on ⋮. */
    val setupUrgent: Boolean = false,
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
    /** Today's 3 opens as the full card (it starts as the one-line strip, so the chat gets the room). */
    val focusExpanded: Boolean = false,
    /** Screenshot tests: a fixed clock for the session bar (epoch ms); null = the real one. */
    val fixedNow: Long? = null,
)

/**
 * The main screen: the chat with the assistant, Today's 3 as a one-line strip above it (tap it for the full card), and
 * the message field. The reply buttons sit under the latest message; while the assistant works, its steps and the
 * typing dots show under the chat.
 */
@OptIn(ExperimentalLayoutApi::class)
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
    val listState = rememberLazyListState()
    var focusOpen by rememberSaveable { mutableStateOf(state.focusExpanded) }
    // The full card folds back to the strip while you type, and when you scroll the chat: the chat comes first.
    val typing = WindowInsets.isImeVisible
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) focusOpen = false }
    }

    Column(Modifier.fillMaxSize()) {
        var menu by remember { mutableStateOf(false) }
        val setupLeft = state.setupTotal > 0 && state.setupDone < state.setupTotal
        TopBar(stringResource(R.string.app_title)) {
            val tasks = if (state.openCount > 0) stringResource(R.string.home_tasks_count, state.openCount) else stringResource(R.string.home_tasks)
            // Compact, so the title keeps its room (Hebrew words are wider, and the phone's font may be larger).
            LinkButton(tasks, onOpenTasks)
            AppIconButton(Icons.Filled.Settings, stringResource(R.string.cd_settings), onOpenSettings)
            Box {
                AppIconButton(Icons.Filled.MoreVert, stringResource(R.string.cd_more), { menu = true }, badge = setupLeft)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.menu_setup, state.setupDone, state.setupTotal)) },
                        onClick = { menu = false; onOpenChecklist() },
                        trailingIcon = if (setupLeft) { { Box(Modifier.size(9.dp).clip(CircleShape).background(Palette.Accent)) } } else null,
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_diagnostics)) }, onClick = { menu = false; onOpenDiagnostics() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.log_out)) }, onClick = { menu = false; onLogout() })
                }
            }
        }

        Column(Modifier.padding(horizontal = 12.dp)) {
            if (state.setupUrgent) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Card2).clickable(onClick = onOpenChecklist)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_banner_urgent), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted)
                }
                Spacer(Modifier.height(8.dp))
            }
            state.session?.let { bar ->
                SessionBar(bar, rememberTicker(state.fixedNow), onOpenSession)
                Spacer(Modifier.height(8.dp))
            }
            FocusCard(state.focus, state.sessionsToday, expanded = focusOpen && !typing, onToggle = { focusOpen = !focusOpen },
                onOpenTask, onDone, onPickFocus, onStart)
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val chat = state.chat
            val last = state.messages.lastOrNull()
            // The reply buttons: under the latest message while it's the assistant's and nothing is being sent.
            val chipsFor = last?.takeIf { it.role == Role.ASSISTANT && it.chips.isNotEmpty() && chat.sending == null }?.id
            val modelFor = state.messages.lastOrNull { it.role == Role.ASSISTANT }?.id
            if (state.messages.isEmpty() && chat.sending == null) {
                Muted(stringResource(R.string.chat_empty), Modifier.align(Alignment.Center).padding(24.dp))
            } else {
                // Newest at the bottom, opening at the bottom.
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, reverseLayout = true) {
                    item(key = "status") { ChatStatus(chat, chat.sending != null && chat.sentId == null, onRetry) }
                    items(state.messages.asReversed(), key = { it.id }) { m ->
                        Column {
                            m.day?.let {
                                Text(it, color = Palette.Muted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp))
                            }
                            ChatLine(m, delivery = if (m.id == chat.sentId) stringResource(R.string.chat_sent) else null, model = m.model.takeIf { m.id == modelFor })
                            if (m.id == chipsFor) ChipRow(m.chips, onChip)
                        }
                    }
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Palette.Card).padding(horizontal = 4.dp, vertical = 6.dp)) {
            state.breadcrumb?.let { Breadcrumb(it, state.breadcrumbDraft, state.canDictate, onBreadcrumbDraft, onBreadcrumb, onBreadcrumbMic) }
            Composer(state.canDictate, state.chat.sending == null, draft, onDraft, onSend, onMic, onNewTask, focusInput)
        }
    }
}

/**
 * The message field with the task form (the list-with-＋ icon), and one round button: 🎤 dictation while the field is
 * empty, Send once there's text (so the field gets the width the two used to take).
 */
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
        AppIconButton(AppIcons.AddTask, stringResource(R.string.cd_new_task_form), onNewTask, tint = Palette.Muted)
        val text = draft()
        OutlinedTextField(
            value = text,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).focusRequester(focus),
            placeholder = { Text(stringResource(R.string.chat_placeholder), color = Palette.Muted) },
            shape = RoundedCornerShape(24.dp),
            maxLines = 5,
        )
        Spacer(Modifier.width(6.dp))
        if (text.isBlank() && canDictate) {
            RoundAction(AppIcons.Mic, stringResource(R.string.cd_dictate), onMic)
        } else {
            RoundAction(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send), onSend, enabled = text.isNotBlank() && idle)
        }
        Spacer(Modifier.width(4.dp))
    }
}

/** The composer's round amber button (Send or 🎤). */
@Composable
private fun RoundAction(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    FilledIconButton(
        onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Palette.Accent, contentColor = Palette.OnAccent,
            disabledContainerColor = Palette.Card2, disabledContentColor = Palette.Muted,
        ),
    ) { Icon(icon, contentDescription = label) }
}

/** Under the chat: the message being sent, this turn's tool steps, the typing dots, or why there's no reply. */
@Composable
private fun ChatStatus(chat: ChatUi, showSending: Boolean, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (showSending && chat.sending != null) {
            ChatLine(ChatItem(-1, "user", "chat", chat.sending, ""), delivery = stringResource(R.string.chat_sending), model = null)
        }
        chat.steps.forEachIndexed { i, step ->
            val running = i == chat.steps.lastIndex && !chat.typing
            Text(step, color = if (running) Palette.Text else Palette.Muted, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(vertical = 2.dp))
        }
        if (chat.typing) TypingDots()
        // A passing problem (no network, the model busy), not a failure of yours: a calm card with ⟳ Try again.
        chat.error?.let {
            Row(
                Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Card)
                    .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(it, color = Palette.Text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                if (chat.canRetry) {
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = Palette.Text, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_retry), color = Palette.Text, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** The assistant is writing: three dots that pulse in turn. */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val label = stringResource(R.string.cd_typing)
    Row(
        Modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card).padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { contentDescription = label },
    ) {
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
                Button(onClick = { onChip(c) }, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) { Text(c.label) }
            } else {
                OutlinedButton(onClick = { onChip(c) }, modifier = Modifier.padding(end = 6.dp, bottom = 4.dp)) { Text(c.label, color = Palette.Text) }
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.breadcrumb_question, b.title), color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = onDraft, modifier = Modifier.weight(1f), singleLine = true,
                placeholder = { Text(stringResource(R.string.breadcrumb_hint), color = Palette.Muted) })
            if (canDictate) AppIconButton(AppIcons.Mic, stringResource(R.string.cd_dictate), onMic, tint = Palette.Muted)
            LinkButton(stringResource(R.string.save), { onDone(draft.trim().ifEmpty { null }) })
            LinkButton(stringResource(R.string.skip), { onDone(null) }, color = Palette.Muted)
        }
    }
}

/**
 * Today's 3. Folded (the usual), one line: how many are done, the next one and ▶ to start it. Unfolded (tap the line),
 * the full card: each task with its ✓, its time and ▶, and Change.
 */
@Composable
private fun FocusCard(
    items: List<FocusItem>, sessions: Int, expanded: Boolean, onToggle: () -> Unit,
    onOpen: (Long) -> Unit, onDone: (Long) -> Unit, onPick: () -> Unit, onStart: (Long) -> Unit,
) {
    val allDone = items.isNotEmpty() && items.all { it.done }
    val progress = when {
        items.isEmpty() -> null
        allDone -> stringResource(R.string.focus_all_done)
        else -> stringResource(R.string.focus_done_count, items.count { it.done }, items.size)
    }
    AnimatedContent(expanded, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(100)) }, label = "focus") { open ->
        if (!open) {
            FocusStrip(items, progress, onToggle, onPick, onStart)
        } else {
            AppCard(padding = PaddingValues(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.todays_3).uppercase(), color = Palette.Muted, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp).weight(1f))
                    // The day's wins: no streaks, no zeros.
                    val wins = listOfNotNull(progress, if (sessions > 0) pluralStringResource(R.plurals.focus_sessions, sessions, sessions) else null)
                    if (wins.isNotEmpty()) Text(wins.joinToString(" · "), color = if (allDone) Palette.Ok else Palette.Muted, style = MaterialTheme.typography.bodySmall)
                    LinkButton(stringResource(if (items.isEmpty()) R.string.focus_pick else R.string.focus_change), onPick)
                    AppIconButton(Icons.Filled.KeyboardArrowUp, stringResource(R.string.cd_collapse), onToggle, tint = Palette.Muted)
                }
                if (items.isEmpty()) {
                    Muted(stringResource(R.string.focus_empty), Modifier.padding(start = 4.dp, end = 12.dp, bottom = 8.dp))
                }
                items.forEachIndexed { i, item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(item.id) }, verticalAlignment = Alignment.CenterVertically) {
                        DoneCircle(item.done) { onDone(item.id) }
                        Column(Modifier.weight(1f).padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) {
                            Text("${i + 1}. ${item.title}", maxLines = 2, overflow = TextOverflow.Ellipsis,
                                textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                color = if (item.done) Palette.Muted else Palette.Text)
                            if (item.meta.isNotEmpty()) Text(item.meta, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!item.done) {
                            AppIconButton(Icons.Filled.PlayArrow, stringResource(R.string.cd_start_task, item.title), { onStart(item.id) }, tint = Palette.Accent)
                        } else {
                            Spacer(Modifier.width(48.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Today's 3 folded: "TODAY'S 3 · 1/3 · Write the report ▶ ⌄". */
@Composable
private fun FocusStrip(items: List<FocusItem>, progress: String?, onExpand: () -> Unit, onPick: () -> Unit, onStart: (Long) -> Unit) {
    val expandLabel = stringResource(R.string.cd_expand)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card)
            .clickable(onClickLabel = expandLabel, onClick = onExpand).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.todays_3).uppercase(), color = Palette.Muted, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        val next = items.firstOrNull { !it.done }
        when {
            items.isEmpty() -> {
                Text(stringResource(R.string.focus_empty_short), color = Palette.Muted, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                LinkButton(stringResource(R.string.focus_pick), onPick)
            }
            next == null -> Text(progress.orEmpty(), color = Palette.Ok, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            else -> {
                Text(progress.orEmpty(), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(10.dp))
                Text(next.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                AppIconButton(Icons.Filled.PlayArrow, stringResource(R.string.cd_start_task, next.title), { onStart(next.id) }, tint = Palette.Accent)
            }
        }
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = Palette.Muted, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

/**
 * The round ✓ in lists: an empty ring while the task is open (a ✓ inside would read as "already done"), filled green
 * with a ✓ once done, with a little bounce and a tap you can feel. 48dp to touch, though it looks smaller.
 */
@Composable
fun DoneCircle(done: Boolean, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scale = remember { Animatable(1f) }
    var wasDone by remember { mutableStateOf(done) }
    LaunchedEffect(done) {
        if (done && !wasDone) {
            scale.animateTo(1.35f, tween(110))
            scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMediumLow))
        }
        wasDone = done
    }
    val label = stringResource(if (done) R.string.cd_done else R.string.cd_mark_done)
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .clickable(enabled = !done, onClickLabel = label) {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(26.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                .then(if (done) Modifier.background(Palette.Ok, CircleShape) else Modifier.border(2.dp, Palette.Muted, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Bg, modifier = Modifier.size(18.dp))
        }
    }
}

/** What a proactive message is, shown small at its top ("Check-in"), so a ping reads differently from an answer. */
@Composable
private fun proactiveLabel(kind: String): String? = when (kind) {
    MessageKind.MORNING -> stringResource(R.string.msg_kind_morning)
    MessageKind.CHECKIN, MessageKind.NUDGE -> stringResource(R.string.msg_kind_checkin)
    MessageKind.EVENING -> stringResource(R.string.msg_kind_evening)
    MessageKind.REMINDER -> stringResource(R.string.msg_kind_reminder)
    MessageKind.MEETING -> stringResource(R.string.msg_kind_meeting)
    MessageKind.SESSION -> stringResource(R.string.msg_kind_session)
    MessageKind.REENTRY -> stringResource(R.string.msg_kind_reentry)
    else -> null
}

/** A tap's line in the chat, as stored ("[Marked done: Taxes]"; the assistant reads it so), without the brackets. */
internal fun eventText(content: String): String = content.trim().removePrefix("[").removeSuffix("]")

/**
 * One chat line. Yours: blue, on the end side, its tail at the top end. The assistant's: the card colour with a thin
 * border, on the start side. A tap's event: a small pill in the middle.
 */
@Composable
private fun ChatLine(m: ChatItem, delivery: String? = null, model: String? = null) {
    if (m.kind == MessageKind.EVENT) {
        EventPill(m)
        return
    }
    val mine = m.role == Role.USER
    val shape = if (mine) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp) else RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
    val meta = if (mine) Palette.OnUserBubbleMuted else Palette.Muted
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape)
                .then(if (mine) Modifier.background(Palette.UserBubble) else Modifier.background(Palette.Card).border(1.dp, Palette.Line, shape))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            val top = if (mine) null else listOfNotNull(proactiveLabel(m.kind), model?.substringAfterLast('/')).joinToString(" · ").ifEmpty { null }
            top?.let { Text(it, color = meta, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
            Text(m.content, style = MaterialTheme.typography.bodyMedium, color = if (mine) Palette.OnUserBubble else Palette.Text)
            Text(listOfNotNull(m.time.ifEmpty { null }, delivery).joinToString(" · "), color = meta, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.End))
        }
    }
}

@Composable
private fun EventPill(m: ChatItem) {
    val done = m.content.trimStart().startsWith("[Marked done")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Card).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (done) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Palette.Ok, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(eventText(m.content), color = Palette.Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (m.time.isNotEmpty()) Text("  ${m.time}", color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Choose up to three open tasks for Today's 3, in the order you tick them. */
@Composable
fun FocusPickerDialog(candidates: List<Pair<Long, String>>, selected: List<Long>, onSave: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val picked = remember { mutableStateListOf<Long>().apply { addAll(selected) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.todays_3)) },
        text = {
            if (candidates.isEmpty()) {
                Muted(stringResource(R.string.focus_picker_empty))
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
        confirmButton = { TextButton(onClick = { onSave(picked.toList()) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
