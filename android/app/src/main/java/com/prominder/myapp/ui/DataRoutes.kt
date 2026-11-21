package com.prominder.myapp.ui

import com.prominder.myapp.data.ChipAction
import com.prominder.myapp.data.MessageKind
import com.prominder.myapp.data.Role
import com.prominder.myapp.data.TaskKind
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.prominder.myapp.data.AppSettings
import com.prominder.myapp.data.Chips
import com.prominder.myapp.data.DayMath
import com.prominder.myapp.data.EventRow
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.OPEN
import com.prominder.myapp.data.SettingException
import com.prominder.myapp.data.SettingSpecs
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskForm
import com.prominder.myapp.data.TaskLists
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.data.drop
import com.prominder.myapp.data.keep
import com.prominder.myapp.data.moveOverdue
import com.prominder.myapp.data.notNow
import com.prominder.myapp.data.SessionRow
import com.prominder.myapp.data.endSession
import com.prominder.myapp.data.saveBreadcrumb
import com.prominder.myapp.data.setSessionLength
import com.prominder.myapp.data.startSession
import com.prominder.myapp.data.stopSession
import com.prominder.myapp.data.startOfDay
import com.prominder.myapp.data.whatNow
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.llm.Chat
import com.prominder.myapp.logic.ChipRules
import com.prominder.myapp.logic.FocusSessions
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * The screens wired to the phone's database: each route watches the rows it shows (so a change anywhere shows at
 * once) and turns taps into Store calls. The screens themselves only get plain values (and are screenshot-tested).
 *
 * Every watch is collectAsStateWithLifecycle: while the app is in the background nothing is re-read, however often
 * the tick writes.
 */

@Composable
private fun settingsOf(store: Store): AppSettings =
    remember(store) { store.watchSettings() }.collectAsStateWithLifecycle(AppSettings.DEFAULTS).value

/**
 * The time, for what a screen shows relative to now ("Today", "in 2 h", which day Today's 3 is for). It moves on every
 * minute while the screen is visible, and at once when you come back to the app, so it rolls over past midnight and
 * the day-reset time by itself.
 */
@Composable
private fun rememberNow(store: Store): Instant {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(store.now(), store, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = store.now()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }
    return now
}

@Composable
fun HomeRoute(
    store: Store,
    chat: Chat,
    setupDone: Int,
    setupTotal: Int,
    notice: String?,
    onNotice: (String?) -> Unit,
    go: (Route) -> Unit,
    onOpenChecklist: () -> Unit,
    onLogout: () -> Unit,
    focusInput: Int = 0,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = settingsOf(store)
    val turn by chat.turn.collectAsStateWithLifecycle()
    // Read only by the message field (and in callbacks), so typing redraws the field, not the whole screen.
    val draft = rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var dictatingNote by rememberSaveable { mutableStateOf(false) }
    val canDictate = remember { dictationAvailable(context) }
    // Dictation: Android's own speech recognizer (Hebrew included), its words added to the message field (or to
    // "Where did you stop?").
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val words = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !words.isNullOrBlank()) {
            fun add(to: String) = listOf(to.trim(), words.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            if (dictatingNote) note = add(note) else draft.value = add(draft.value)
        }
    }
    val zone = settings.zone
    val now = rememberNow(store)
    val day = DayMath.focusDay(now.atZone(zone), settings.dayResetTime).toString()
    val open by remember { store.dao.watchOpenTasks() }.collectAsStateWithLifecycle(emptyList())
    val focusRows by remember(day) { store.dao.watchFocusTasks(day) }.collectAsStateWithLifecycle(emptyList())
    val todayStart = Times.format(now.atZone(zone).startOfDay())
    val doneEvents by remember(todayStart) { store.dao.watchDoneSince(todayStart) }.collectAsStateWithLifecycle(emptyList())
    val messages by remember { store.dao.watchLatestMessages(MESSAGES_SHOWN) }.collectAsStateWithLifecycle(emptyList())
    val sessionsToday by remember(todayStart) { store.dao.watchSessionCountSince(todayStart) }.collectAsStateWithLifecycle(0)
    val latest by remember { store.dao.watchLatestSession() }.collectAsStateWithLifecycle(null)
    val sessionTask by remember(latest?.taskId) { store.dao.watchTask(latest?.taskId ?: -1) }.collectAsStateWithLifecycle(null)
    var picking by rememberSaveable { mutableStateOf(false) }
    val lang = ChipRules.chipLang(messages.lastOrNull { it.role == Role.USER && it.kind == MessageKind.CHAT }?.content, sessionTask?.title)
    val running = latest?.takeIf { it.endedAt == null }
    // "Where did you stop?": after a stopped or unanswered session, for a few hours, unless answered (wants_breadcrumb).
    val offer = latest?.takeIf { running == null && FocusSessions.wantsBreadcrumb(it, now) }
    fun openTimer() = go(Route.Session)

    val focus = TaskLists.focus(focusRows, day)
    val done = TaskLists.doneToday(focus, doneEvents)
    // Parsing, formatting and decoding the buttons of 300 messages: only when they, or the day, change.
    val showModel = settings.showModel
    val chatLines = remember(messages, zone, showModel, now.atZone(zone).toLocalDate()) { chatItems(messages, zone, now, showModel) }
    HomeScreen(
        state = HomeState(
            focus = focus.map { FocusItem(it.id, it.title, Format.taskMeta(it, zone, now), it.id in done) },
            messages = chatLines,
            openCount = open.count { it.parentId == null },
            setupDone = setupDone,
            setupTotal = setupTotal,
            notice = notice,
            chat = ChatUi(turn.sending, turn.sentId, turn.steps, turn.typing, turn.error, canRetry = turn.failedId != null),
            canDictate = canDictate,
            session = running?.let { SessionBarUi(sessionTask?.title.orEmpty(), FocusSessions.sessionEndAt(it).toEpochMilli(), lang) },
            breadcrumb = offer?.let { o -> sessionTask?.let { BreadcrumbUi(o.id, it.title, lang) } },
            breadcrumbDraft = note,
            sessionsToday = sessionsToday,
        ),
        onOpenTasks = { go(Route.Tasks) },
        onOpenTask = { go(Route.Task(it)) },
        onDone = { id -> scope.launch { store.complete(id); onNotice("Done ✓") } },
        onPickFocus = { picking = true },
        onNewTask = { go(Route.NewTask()) },
        onOpenSettings = { go(Route.Settings) },
        onOpenChecklist = onOpenChecklist,
        onOpenDiagnostics = { go(Route.Diagnostics) },
        onLogout = onLogout,
        draft = { draft.value },
        onDraft = { draft.value = it },
        onSend = { if (chat.send(draft.value)) draft.value = "" },
        onChip = { c -> scope.launch { onNotice(chipAction(store, chat, c, zone, onSession = ::openTimer)) } },
        onMic = {
            dictatingNote = false
            val lang = ChipRules.chipLang(draft.value.ifBlank { null } ?: messages.lastOrNull { it.role == Role.USER && it.kind == MessageKind.CHAT }?.content)
            try {
                dictate.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "he") "he-IL" else "en-US"))
            } catch (e: ActivityNotFoundException) {
                onNotice("Dictation isn't available on this phone.")
            }
        },
        onRetry = { chat.retry() },
        focusInput = focusInput,
        onStart = { id ->
            scope.launch {
                if (store.startSession(id, FocusSessions.START_MIN_APP) != null) openTimer() else onNotice("That task isn't open any more.")
            }
        },
        onOpenSession = ::openTimer,
        onBreadcrumbDraft = { note = it },
        onBreadcrumb = { text ->
            val id = offer?.id
            note = ""
            if (id != null) scope.launch {
                store.saveBreadcrumb(id, text)
                if (text != null) onNotice(if (lang == "he") "נשמר לפעם הבאה" else "Saved for next time")
            }
        },
        onBreadcrumbMic = {
            dictatingNote = true
            try {
                dictate.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "he") "he-IL" else "en-US"))
            } catch (e: ActivityNotFoundException) {
                onNotice("Dictation isn't available on this phone.")
            }
        },
    )
    if (picking) {
        val candidates = TaskLists.all(open.filter { it.kind == TaskKind.TASK }).map { it.id to it.title }
        FocusPickerDialog(
            candidates = candidates,
            selected = focus.filter { it.status == OPEN }.map { it.id },
            onSave = { ids -> picking = false; scope.launch { store.setFocus(ids, store.focusDay(store.settings())) } },
            onDismiss = { picking = false },
        )
    }
}

/** The chat, with a day separator on each day's first message. */
internal fun chatItems(rows: List<MessageRow>, zone: ZoneId, now: Instant, showModel: Boolean = false): List<ChatItem> {
    var lastDay: java.time.LocalDate? = null
    return rows.map { m ->
        val at = Times.parseOrNull(m.createdAt) ?: now
        val d = at.atZone(zone).toLocalDate()
        val sep = if (d != lastDay) Format.day(at, zone, now) else null
        lastDay = d
        ChatItem(m.id, m.role, m.kind, m.content, Format.clock(at, zone), sep,
            chips = Chips.decode(m.chips), model = m.model.takeIf { showModel })
    }
}

/**
 * A reply button: an action on the phone's data (no LLM), or a reply in your words. Returns a short answer to show.
 * ▶ Start and ▶ Another round open the timer ([onSession]).
 */
internal suspend fun chipAction(store: Store, chat: Chat, c: ChipRules.ChipJson, zone: ZoneId, onSession: () -> Unit = {}): String? = when (c.action) {
    null -> if (chat.send(c.label)) null else "One message at a time: wait for the reply."
    "start", "another" -> c.taskId?.let { id ->
        val started = if (c.action == ChipAction.ANOTHER) store.startSession(id, another = true) else store.startSession(id, c.minutes)
        if (started != null) null.also { onSession() } else "That task isn't open any more."
    }
    "stop" -> c.taskId?.let { store.stopSession(it); "Stopped" }
    "done" -> c.taskId?.let { store.complete(it); "Done ✓" }
    "later" -> c.taskId?.let { id -> store.later(id)?.let { "Later: next check-in ${Format.time(it, zone, store.now())}" } }
    "not_now" -> c.taskId?.let { store.notNow(it); "Parked for a week" }
    "move_overdue" -> store.moveOverdue().let { if (it.isEmpty()) "Nothing overdue" else "Moved to next week" }
    "drop" -> store.drop(c.taskIds.orEmpty()).let { "Dropped" }
    "keep" -> store.keep(c.taskIds.orEmpty()).let { "Kept" }
    "what_now" -> store.whatNow(c.lowEnergy == true, c.exclude.orEmpty(), c.id).let { null }
    else -> null
}

/** The latest session, once loaded (the timer closes itself when there's no open one). */
private data class Loaded(val session: SessionRow?)

/**
 * The focus timer, wired to the database. It shows the open session and closes by itself when the session ends
 * (✓ Done, Stop, or from a notification). ▶ Another round keeps it open on the new round.
 */
@Composable
fun SessionRoute(store: Store, onNotice: (String?) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val loaded by remember { store.dao.watchLatestSession().map { Loaded(it) } }.collectAsStateWithLifecycle(null)
    val latest = loaded?.session
    val task by remember(latest?.taskId) { store.dao.watchTask(latest?.taskId ?: -1) }.collectAsStateWithLifecycle(null)
    val lastChat by remember { store.dao.watchLatestMessages(20) }.collectAsStateWithLifecycle(emptyList())
    var busy by remember { mutableStateOf(false) }
    val open = latest?.takeIf { it.endedAt == null }
    LaunchedEffect(loaded) { if (loaded != null && open == null) onBack() }
    val s = open ?: run {
        Muted("…")
        return
    }
    val title = task?.title.orEmpty()
    val lang = ChipRules.chipLang(lastChat.lastOrNull { it.role == Role.USER && it.kind == MessageKind.CHAT }?.content, title)
    val now = rememberTicker()
    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } finally { busy = false } }
    }
    SessionScreen(
        ui = SessionUi(title, task?.nextAction?.takeIf { it.isNotEmpty() } ?: title, task?.resumeNote, s.plannedMin,
            Times.parse(s.startedAt).toEpochMilli(), FocusSessions.sessionEndAt(s).toEpochMilli(), lang),
        now = now,
        busy = busy,
        onDone = { act { store.endSession(s.id, "done"); onNotice(if (lang == "he") "בוצע ✓" else "Done ✓") } },
        onStop = { act { store.endSession(s.id, "stopped") } },
        onAnother = { act { store.endSession(s.id, "another") } },
        onLength = { m -> act { if (store.setSessionLength(s.id, m) == null) onNotice("That session has already ended.") } },
        onClose = onBack,
    )
}

private fun dictationAvailable(context: android.content.Context): Boolean =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).resolveActivity(context.packageManager) != null

@Composable
fun TasksRoute(store: Store, go: (Route) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = settingsOf(store)
    val zone = settings.zone
    val now = rememberNow(store)
    val open by remember { store.dao.watchOpenTasks() }.collectAsStateWithLifecycle(emptyList())
    var tab by rememberSaveable { mutableStateOf(0) }
    val day = DayMath.focusDay(now.atZone(zone), settings.dayResetTime).toString()
    val titles = open.associate { it.id to it.title }
    val endOfToday = DayMath.dayBounds(now.atZone(zone)).second
    fun item(t: TaskRow) = taskItem(t, zone, now, day, titles)
    TasksScreen(
        state = TasksState(
            tab = tab,
            today = TaskLists.today(open, endOfToday).map(::item),
            upcoming = TaskLists.upcoming(open, endOfToday).map(::item),
            all = TaskLists.all(open).map(::item),
        ),
        onTab = { tab = it },
        onOpen = { go(Route.Task(it)) },
        onDone = { id -> scope.launch { store.complete(id) } },
        onNew = { go(Route.NewTask()) },
        onBack = onBack,
    )
}

internal fun taskItem(t: TaskRow, zone: ZoneId, now: Instant, focusDay: String, titles: Map<Long, String>) = TaskItem(
    id = t.id,
    title = t.title,
    meta = Format.taskMeta(t, zone, now),
    overdue = TaskLists.overdue(t, now),
    inFocus = t.focusDate == focusDay,
    parent = t.parentId?.let { p -> titles[p]?.let { "Step of: $it" } ?: "A step" },
    canBeDone = t.kind != TaskKind.FENCE,
)

@Composable
fun TaskDetailRoute(store: Store, id: Long, onNotice: (String?) -> Unit, go: (Route) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = settingsOf(store)
    val zone = settings.zone
    val now = rememberNow(store)
    val task by remember(id) { store.dao.watchTask(id) }.collectAsStateWithLifecycle(null)
    val steps by remember(id) { store.dao.watchSubtasks(id) }.collectAsStateWithLifecycle(emptyList())
    val events by remember(id) { store.dao.watchEvents(id, 30) }.collectAsStateWithLifecycle(emptyList())
    val t = task ?: run {
        Muted("Loading…")
        return
    }
    val parent by remember(t.parentId) { store.dao.watchTask(t.parentId ?: -1) }.collectAsStateWithLifecycle(null)
    val parentTitle = parent?.title
    val day = DayMath.focusDay(now.atZone(zone), settings.dayResetTime).toString()
    TaskDetailScreen(
        state = TaskDetailState(
            id = t.id,
            title = t.title,
            meta = Format.taskMeta(t, zone, now),
            status = t.status,
            kind = t.kind,
            parent = parentTitle?.let { "Step of: $it" },
            nextAction = t.nextAction,
            blocked = t.blockedReason,
            resumeNote = t.resumeNote,
            description = t.description.orEmpty(),
            inFocus = t.focusDate == day,
            steps = steps.map { taskItem(it, zone, now, day, emptyMap()).copy(parent = null) },
            history = events.map { historyItem(it, zone, now) },
        ),
        onSaveDescription = { text -> scope.launch { store.setDescription(id, text) } },
        onDone = { scope.launch { store.complete(id); onNotice("Done ✓: ${t.title}"); onBack() } },
        onLater = {
            scope.launch {
                val at = store.later(id)
                onNotice(at?.let { "Later: next check-in ${Format.time(it, zone, store.now())}" })
                onBack()
            }
        },
        onToggleFocus = {
            scope.launch {
                if (!store.toggleFocus(id)) onNotice("Today's 3 is full. Take one out first.")
            }
        },
        onStart = {
            scope.launch {
                if (store.startSession(id, FocusSessions.START_MIN_APP) != null) go(Route.Session) else onNotice("That task isn't open any more.")
            }
        },
        onEdit = { go(Route.EditTask(id)) },
        onCancelTask = { scope.launch { store.cancel(id); onNotice("Cancelled: ${t.title}"); onBack() } },
        onAddStep = { title -> scope.launch { store.create(TaskForm(title.trim()), parentId = id) } },
        onOpenStep = { go(Route.Task(it)) },
        onDoneStep = { sid -> scope.launch { store.complete(sid) } },
        onBack = onBack,
    )
}

private fun historyItem(e: EventRow, zone: ZoneId, now: Instant) = HistoryItem(
    label = Format.EVENT_LABELS[e.kind] ?: e.kind,
    time = Format.time(e.createdAt, zone, now).orEmpty(),
    note = listOfNotNull(e.note, e.avoidReason).joinToString(" · ").ifEmpty { null },
)

@Composable
fun TaskFormRoute(store: Store, editId: Long?, parentId: Long?, onSaved: (Long) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = settingsOf(store)
    // Loaded once (not watched): the form edits its own copy.
    val loaded by produceState<TaskRow?>(null, editId) { value = editId?.let { store.dao.task(it) } }
    val existing = loaded
    if (editId != null && existing == null) {
        Muted("Loading…")
        return
    }
    val initial = existing?.let {
        TaskForm(it.title, it.kind, it.priority, Times.parseOrNull(it.dueAt), it.recurrence, it.nextAction, it.description, it.estimateMin)
    } ?: TaskForm("")
    TaskFormScreen(
        heading = when {
            existing != null -> "Edit task"
            parentId != null -> "New step"
            else -> "New task"
        },
        initial = initial,
        zone = settings.zone,
        onSave = { form ->
            // The form checked it already; the store checks again and throws only on a bug.
            scope.launch {
                try {
                    val row = if (existing != null) store.edit(existing.id, form) else store.create(form, parentId)
                    Diag.log("data", if (existing != null) "Task ${existing.id} edited" else "Task ${row?.id} added")
                    row?.let { onSaved(it.id) }
                } catch (e: IllegalArgumentException) {
                    Diag.log("data", "Task not saved: ${e.message}")
                }
            }
            null
        },
        onBack = onBack,
    )
}

@Composable
fun SettingsRoute(store: Store, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings = settingsOf(store)
    val prefs by remember { store.dao.watchPreferences() }.collectAsStateWithLifecycle(emptyList())
    val items = SettingSpecs.ALL.map { spec ->
        val default = SettingSpecs.canonical(spec, spec.default)
        SettingItem(spec.key, spec.label, spec.help, spec.group, spec.kind, settings.raw(spec.key), spec.key !in settings.overrides, default, spec.options)
    }
    SettingsScreen(
        items = items,
        preferences = prefs.map { it.id to it.text },
        onSave = { key, raw ->
            // Checked here first, so a wrong value is reported in the dialog; saved in the background.
            val spec = SettingSpecs.BY_KEY.getValue(key)
            try {
                raw?.let { SettingSpecs.canonical(spec, it) }
                scope.launch { store.saveSetting(key, raw) }
                null
            } catch (e: SettingException) {
                e.message
            }
        },
        onDeletePreference = { id -> scope.launch { store.deletePreference(id) } },
        onBack = onBack,
    )
}

private const val MESSAGES_SHOWN = 300
