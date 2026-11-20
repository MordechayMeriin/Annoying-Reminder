package com.prominder.myapp.data

import androidx.room.withTransaction
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonPrimitive

/** What the task form edits. Null means "none" (no date, no repeat…). */
data class TaskForm(
    val title: String,
    val kind: String = "task",
    val priority: Int = 1,
    val dueAt: Instant? = null,
    val recurrence: String? = null,
    val nextAction: String? = null,
    val description: String? = null,
    val estimateMin: Int? = null,
)

/**
 * Every change to your data goes through here. The rules are ported from backend/app/tasks.py and
 * actions.py (create, update, done, later, cancel, Today's 3), so the phone does what the server did.
 *
 * Each change is one transaction that stamps the rows it writes with a new change number ([TaskRow.seq] etc.),
 * then calls [onChange] (which re-plans the next tick; the backup picks the change up by its seq).
 */
class Store(
    private val db: AppDatabase,
    private val clock: () -> Instant = Instant::now,
    private val onChange: () -> Unit = {},
    /** You typed in the chat or tapped something (a chat event): a repeating alarm stops, you've seen it. */
    private val onUserActivity: () -> Unit = {},
) {
    val dao: AppDao = db.dao()

    // ------------------------------------------------------------ reading

    suspend fun settings(): AppSettings = AppSettings(dao.settings().associate { it.key to it.value })

    fun watchSettings(): Flow<AppSettings> = dao.watchSettings().map { rows -> AppSettings(rows.associate { it.key to it.value }) }

    /** Whether the phone holds your data yet (before the handover it doesn't). */
    suspend fun hasData(): Boolean = dao.meta(META_RESTORED_AT) != null || dao.taskCount() > 0 || dao.messageCount() > 0

    fun now(): Instant = clock()

    /** The day Today's 3 belongs to (it starts at the day-reset time). */
    fun focusDay(settings: AppSettings): LocalDate = DayMath.focusDay(clock().atZone(settings.zone), settings.dayResetTime)

    // ------------------------------------------------------------ tasks

    suspend fun create(form: TaskForm, parentId: Long? = null): TaskRow = write {
        val s = settings()
        check(form)
        val due = form.dueAt?.let(Times::format)
        val remind = form.dueAt?.let { if (form.kind == "meeting") it.minusSeconds(60L * s.meetingLeadMin) else it }?.let(Times::format)
        val row = TaskRow(
            id = nextId(META_TASK_COUNTER, dao.maxTaskId()),
            parentId = parentId,
            title = form.title.trim().take(TITLE_MAX),
            kind = form.kind,
            priority = form.priority,
            description = clip(form.description, DESCRIPTION_MAX),
            dueAt = due,
            remindAt = remind,
            remindRequested = remind != null,
            recurrence = form.recurrence.takeIf { due != null },
            nextAction = clip(form.nextAction, NEXT_ACTION_MAX),
            estimateMin = form.estimateMin,
            createdAt = nowText(),
        )
        putTask(row)
        row
    }

    /** tasks.update for the fields the form edits. A new time resets the nagging and counts as a requested reminder. */
    suspend fun edit(id: Long, form: TaskForm): TaskRow? = write {
        val t = dao.task(id) ?: return@write null
        check(form)
        val s = settings()
        var new = t.copy(
            title = form.title.trim().take(TITLE_MAX),
            kind = form.kind,
            priority = form.priority,
            nextAction = clip(form.nextAction, NEXT_ACTION_MAX),
            description = clip(form.description, DESCRIPTION_MAX),
            estimateMin = form.estimateMin,
        )
        val due = form.dueAt?.let(Times::format)
        if (due != t.dueAt || form.kind != t.kind) {
            val remind = form.dueAt?.let { if (form.kind == "meeting") it.minusSeconds(60L * s.meetingLeadMin) else it }
            new = new.copy(dueAt = due, remindAt = remind?.let(Times::format), remindRequested = remind != null,
                nagCount = 0, escalated = false, parkedUntil = null)
        }
        new = new.copy(recurrence = form.recurrence.takeIf { new.dueAt != null })
        descriptionChanged(t, new.description)
        putTask(new)
        new
    }

    suspend fun setDescription(id: Long, text: String): TaskRow? = write {
        val t = dao.task(id) ?: return@write null
        val new = t.copy(description = clip(text, DESCRIPTION_MAX))
        descriptionChanged(t, new.description)
        putTask(new)
        new
    }

    /**
     * ✓ Done (actions.apply "done" + tasks.complete). A recurring task rolls on to its next time instead of
     * closing; an open task's open steps are done with it. A hard stop can't be "done", only cancelled.
     * [chatEvent]: write "[Marked done: …]" in the chat (a tap); the assistant's own tool call doesn't.
     */
    suspend fun complete(id: Long, chatEvent: Boolean = true): TaskRow? = write {
        val t = dao.task(id) ?: return@write null
        if (t.status != OPEN || t.kind == "fence") return@write t
        closeSessions(id, "done")
        addEvent(id, "done")
        val now = clock()
        val due = Times.parseOrNull(t.dueAt)
        val recurring = t.recurrence != null && due != null
        val new = if (recurring) {
            val next = DayMath.nextFutureOccurrence(due!!, t.recurrence!!, settings().zone, now)
            val lead = Times.parseOrNull(t.remindAt)?.let { java.time.Duration.between(it, due) } ?: java.time.Duration.ZERO
            t.copy(dueAt = Times.format(next), remindAt = Times.format(next.minus(lead)), remindRequested = true, nagCount = 0,
                escalated = false, lastNotifiedAt = null, blockedReason = null, blockedUntil = null, resumeNote = null)
        } else {
            for (child in dao.children(id)) {
                if (child.status == OPEN) putTask(child.copy(status = DONE, completedAt = Times.format(now), remindAt = null))
            }
            t.copy(status = DONE, completedAt = Times.format(now), remindAt = null, resumeNote = null)
        }
        putTask(new)
        if (chatEvent) addChatEvent("[Marked done: ${t.title}" + if (recurring) " — next one scheduled]" else "]", listOf(id))
        new
    }

    /** ⏰ Later: the time is decided by code (logic.later_time), so you never have to pick one. Returns it. */
    suspend fun later(id: Long): Instant? = write {
        val t = dao.task(id) ?: return@write null
        if (t.status != OPEN || t.kind == "fence") return@write null
        val s = settings()
        val whenLocal = DayMath.laterTime(clock().atZone(s.zone), s.morningTime)
        putTask(t.copy(remindAt = Times.format(whenLocal.toInstant()), remindRequested = true, nagCount = 0, escalated = false,
            parkedUntil = null))
        addEvent(id, "postponed", "later (button)")
        addChatEvent("[Later: ${t.title} — next check-in ${whenLocal.format(DAY_TIME)}]", listOf(id))
        whenLocal.toInstant()
    }

    /** Cancel the task (and its open steps). [chatEvent]: as for [complete]. */
    suspend fun cancel(id: Long, chatEvent: Boolean = true): TaskRow? = write {
        val t = dao.task(id) ?: return@write null
        if (t.status == CANCELLED) return@write t
        closeSessions(id, "stopped")
        for (child in dao.children(id)) {
            if (child.status == OPEN) putTask(child.copy(status = CANCELLED, remindAt = null))
        }
        val new = t.copy(status = CANCELLED, remindAt = null)
        putTask(new)
        if (t.status == OPEN && chatEvent) addChatEvent("[Cancelled: ${t.title}]", listOf(id))
        new
    }

    // ------------------------------------------------------------ Today's 3

    /** tasks.set_focus: these (at most 3, open, not meetings) become Today's 3, in this order. */
    suspend fun setFocus(ids: List<Long>, day: LocalDate): List<TaskRow> = write { setFocusIn(ids, day) }

    /** ☆ / ★ on a task: add it to Today's 3 (if there's room) or take it out. False when it's full. */
    suspend fun toggleFocus(id: Long): Boolean = write {
        val day = focusDay(settings())
        val current = focusIds(day)
        val ids = if (id in current) current - id else current + id
        if (ids.size > FOCUS_MAX) return@write false
        setFocusIn(ids, day)
        true
    }

    suspend fun focusIds(day: LocalDate): List<Long> {
        val date = day.toString()
        return dao.focus(date)?.ids.orEmpty().filter { i -> dao.task(i)?.focusDate == date }
    }

    // ------------------------------------------------------------ settings and preferences

    /** Save a setting (null: back to the default). Throws [SettingException] when the value isn't valid. */
    suspend fun saveSetting(key: String, raw: String?) {
        val spec = SettingSpecs.BY_KEY[key] ?: throw SettingException("unknown setting")
        val value = raw?.let { SettingSpecs.canonical(spec, it) }
        write {
            if (value == null || value == SettingSpecs.canonical(spec, spec.default)) {
                dao.deleteSetting(key)
                tombstone("settings", JsonPrimitive(key))
            } else {
                dirty = true
                dao.upsertSettings(listOf(SettingRow(key, value, nowText(), seq)))
            }
        }
    }

    suspend fun deletePreference(id: Long) = write {
        if (dao.preference(id) != null) {
            dao.deletePreference(id)
            tombstone("preferences", JsonPrimitive(id))
        }
    }

    // ------------------------------------------------------------ the write machinery

    /**
     * The change number every row written in the current transaction gets. Safe as a field: Room runs one
     * write transaction at a time, so no other change can start while this one uses it.
     */
    private var seq: Long = 0

    /** Whether the current transaction wrote a row (a tick that changed nothing doesn't schedule a backup). */
    private var dirty = false

    /** Whether the current transaction saved a message of yours (typed, or a tap's chat event). */
    private var userActed = false

    /** One change: a transaction whose rows all get the same new change number. The tick (remind/) uses it too. */
    internal suspend fun <T> write(block: suspend () -> T): T {
        val (result, flags) = db.withTransaction {
            seq = (dao.meta(META_SEQ)?.toLongOrNull() ?: 0) + 1
            dirty = false
            userActed = false
            dao.setMeta(MetaRow(META_SEQ, seq.toString()))
            block() to (dirty to userActed)
        }
        if (flags.second) onUserActivity()
        if (flags.first) onChange()
        return result
    }

    internal suspend fun putTask(row: TaskRow) {
        dirty = true
        dao.upsertTasks(listOf(row.copy(seq = seq)))
    }

    internal suspend fun putSession(row: SessionRow) {
        dirty = true
        dao.upsertSessions(listOf(row.copy(seq = seq)))
    }

    internal suspend fun addEvent(taskId: Long, kind: String, note: String? = null, avoidReason: String? = null) {
        dirty = true
        dao.upsertEvents(listOf(EventRow(taskId, kind, note?.take(500)?.ifEmpty { null }, avoidReason, nowText(), rid(), seq)))
    }

    internal suspend fun putPreference(row: PreferenceRow) {
        dirty = true
        dao.upsertPreferences(listOf(row.copy(seq = seq)))
    }

    internal suspend fun putMessage(row: MessageRow) {
        dirty = true
        dao.upsertMessages(listOf(row.copy(seq = seq)))
    }

    /** thread.add: a message in the chat. Chips as JSON text (Chips.encode). Public for the assistant (llm/). */
    suspend fun addMessageNow(role: String, content: String, kind: String, taskIds: List<Long> = emptyList(),
                              chips: String = "[]", model: String? = null): MessageRow = write { addMessage(role, content, kind, taskIds, chips, model) }

    internal suspend fun addMessage(role: String, content: String, kind: String, taskIds: List<Long> = emptyList(),
                                    chips: String = "[]", model: String? = null): MessageRow {
        dirty = true
        if (role == "user") userActed = true
        val ids = taskIds.distinct()
        val row = MessageRow(nextId(META_MESSAGE_COUNTER, dao.maxMessageId()), role, content, kind, ids.firstOrNull(), ids,
            chips, model, nowText(), seq)
        dao.upsertMessages(listOf(row))
        return row
    }

    /** thread.add_event: something you did outside the chat, written as your own message so the assistant reads it. */
    internal suspend fun addChatEvent(text: String, taskIds: List<Long>) {
        addMessage("user", text, "event", taskIds)
    }

    private suspend fun descriptionChanged(old: TaskRow, newText: String?) {
        if (!old.description.isNullOrEmpty() && old.description != newText) addEvent(old.id, "description_changed", old.description)
    }

    /** actions.close_task_sessions: the task is done, cancelled or parked, so its open session ends with it. */
    internal suspend fun closeSessions(taskId: Long, outcome: String) {
        dao.openSessions(taskId).forEach { closeSession(it, outcome) }
    }

    /**
     * actions.close_session: records an outcome (done | another | stopped | paused) with a session_ended event (paused:
     * a paused event), "stopped, 7 of 25 min". Null if it was already closed (a second tap).
     */
    internal suspend fun closeSession(s: SessionRow, outcome: String, endedAt: Instant = clock()): SessionRow? {
        val cur = dao.session(s.id)?.takeIf { it.endedAt == null } ?: return null
        val closed = cur.copy(endedAt = Times.format(endedAt), outcome = outcome)
        putSession(closed)
        addEvent(closed.taskId, if (outcome == "paused") "paused" else "session_ended",
            "$outcome, ${com.prominder.myapp.logic.Logic.actualMinutes(closed, clock())} of ${closed.plannedMin} min")
        return closed
    }

    internal suspend fun setFocusIn(ids: List<Long>, day: LocalDate): List<TaskRow> {
        val date = day.toString()
        val wanted = ids.distinct().take(FOCUS_MAX)
        for (t in dao.focusTasks(date)) {
            if (t.id !in wanted) putTask(t.copy(focusDate = null, focusRank = null))
        }
        val out = mutableListOf<TaskRow>()
        for ((index, id) in wanted.withIndex()) {
            val t = dao.task(id) ?: continue
            if (t.status != OPEN || t.kind != "task") continue
            val new = t.copy(focusDate = date, focusRank = index + 1)
            putTask(new)
            out += new
        }
        dirty = true
        dao.upsertFocus(listOf(FocusRow(date, out.map { it.id }, seq)))
        return out
    }

    private suspend fun tombstone(table: String, key: kotlinx.serialization.json.JsonElement) {
        dirty = true
        dao.insertTombstone(Tombstone(table, key.toString(), seq))
    }

    /** The next id: after both the backup's counter and the highest id here (as db.next_id did on the server). */
    internal suspend fun nextId(counter: String, maxId: Long): Long {
        val id = maxOf(dao.meta(counter)?.toLongOrNull() ?: 0, maxId) + 1
        dao.setMeta(MetaRow(counter, id.toString()))
        return id
    }

    internal fun nowText() = Times.format(clock())

    private fun check(form: TaskForm) {
        require(form.title.isNotBlank()) { "The title is empty." }
        require(form.kind in KINDS) { "Unknown kind: ${form.kind}" }
        require(form.priority in 1..3) { "priority must be 1-3" }
        require(form.recurrence == null || form.recurrence in RECURRENCES) { "Unknown recurrence: ${form.recurrence}" }
        require(form.estimateMin == null || form.estimateMin in 1..10000) { "estimate_min must be 1-10000" }
    }

    companion object {
        const val FOCUS_MAX = 3
        const val TITLE_MAX = 200
        const val DESCRIPTION_MAX = 800
        const val NEXT_ACTION_MAX = 200
        val KINDS = listOf("task", "meeting", "fence")
        val RECURRENCES = listOf("daily", "weekdays", "weekly", "monthly")

        const val META_SEQ = "seq"
        const val META_TASK_COUNTER = "counter.task"
        const val META_MESSAGE_COUNTER = "counter.message"
        const val META_SESSION_COUNTER = "counter.session"
        const val META_PREFERENCE_COUNTER = "counter.preference"
        const val META_RESTORED_AT = "sync.restored_at"
        const val META_HANDED_OVER_AT = "sync.handed_over_at"
        const val META_UPLOADED_SEQ = "sync.uploaded_seq"
        const val META_LAST_UPLOAD_AT = "sync.last_upload_at"
        const val META_LAST_SYNC_ERROR = "sync.last_error"

        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
        private val random = SecureRandom()

        /** db.rand(): 8 hex characters. */
        fun rid(): String = "%08x".format(random.nextInt())

        /** tasks._clip: trimmed, cut with "…" when too long, null when empty. */
        fun clip(text: String?, limit: Int): String? {
            val t = text?.trim() ?: return null
            if (t.isEmpty()) return null
            return if (t.length <= limit) t else t.take(limit - 1) + "…"
        }
    }
}

/** The start of a calendar day, for "done today". */
fun ZonedDateTime.startOfDay(): Instant = toLocalDate().atStartOfDay(zone).toInstant()
