package com.prominder.myapp.data

import com.prominder.myapp.logic.FocusSessions
import androidx.room.withTransaction
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
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
 * Every change to your data goes through here: create, edit, done, later, cancel, Today's 3, settings.
 *
 * Each change is one transaction ([write]) that stamps the rows it writes with a new change number ([TaskRow.seq]
 * etc.), then calls [onChange] (which re-plans the next tick; the backup picks the change up by its seq).
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
        val remind = form.dueAt?.let { if (form.kind == TaskKind.MEETING) it.minusSeconds(60L * s.meetingLeadMin) else it }?.let(Times::format)
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

    /** The fields the form edits. A new time resets the nagging and counts as a requested reminder. */
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
            val remind = form.dueAt?.let { if (form.kind == TaskKind.MEETING) it.minusSeconds(60L * s.meetingLeadMin) else it }
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
     * ✓ Done. A recurring task rolls on to its next time instead of
     * closing; an open task's open steps are done with it. A hard stop can't be "done", only cancelled.
     * [chatEvent]: write "[Marked done: …]" in the chat (a tap); the assistant's own tool call doesn't.
     */
    suspend fun complete(id: Long, chatEvent: Boolean = true): TaskRow? = write {
        val t = dao.task(id) ?: return@write null
        if (t.status != OPEN || t.kind == TaskKind.FENCE) return@write t
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

    /** ⏰ Later: the time is decided by code, so you never have to pick one. Returns it. */
    suspend fun later(id: Long): Instant? = write {
        val t = dao.task(id) ?: return@write null
        if (t.status != OPEN || t.kind == TaskKind.FENCE) return@write null
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

    /** These (at most 3, open, not meetings) become Today's 3, in this order. */
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
                // A row and a tombstone for the same key must never travel together: the backup would refuse both.
                dao.deleteTombstone("settings", JsonPrimitive(key).toString())
                val tx = tx()
                tx.dirty = true
                dao.upsertSettings(listOf(SettingRow(key, value, nowText(), tx.seq)))
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
     * The change in progress: the change number its rows get, and what it did. It lives in the coroutine context of
     * the transaction, not on the Store, so two changes can never mix their state, and a [write] inside a [write]
     * simply joins the outer change.
     */
    private class Tx(val seq: Long) : AbstractCoroutineContextElement(Tx) {
        /** It wrote a row (a change that wrote nothing gets no change number and doesn't re-plan or back up). */
        var dirty = false

        /** It saved a message of yours (typed, or a tap's chat event). */
        var userActed = false

        companion object Key : CoroutineContext.Key<Tx>
    }

    private suspend fun tx(): Tx = coroutineContext[Tx] ?: error("Store: a row was written outside write { }")

    /** One change: a transaction whose rows all get the same new change number. The tick (remind/) uses it too. */
    internal suspend fun <T> write(block: suspend () -> T): T {
        if (coroutineContext[Tx] != null) return block()   // already inside a change: part of it
        val (result, tx) = db.withTransaction {
            val tx = Tx((dao.meta(META_SEQ)?.toLongOrNull() ?: 0) + 1)
            val result = kotlinx.coroutines.withContext(tx) { block() }
            // Only a change that wrote something uses up its number, so "nothing new since the last backup" stays true.
            if (tx.dirty) dao.setMeta(MetaRow(META_SEQ, tx.seq.toString()))
            result to tx
        }
        if (tx.userActed) onUserActivity()
        if (tx.dirty) onChange()
        return result
    }

    internal suspend fun putTask(row: TaskRow) {
        val tx = tx()
        tx.dirty = true
        dao.upsertTasks(listOf(row.copy(seq = tx.seq)))
    }

    internal suspend fun putSession(row: SessionRow) {
        val tx = tx()
        tx.dirty = true
        dao.upsertSessions(listOf(row.copy(seq = tx.seq)))
    }

    internal suspend fun addEvent(taskId: Long, kind: String, note: String? = null, avoidReason: String? = null) {
        val tx = tx()
        tx.dirty = true
        dao.upsertEvents(listOf(EventRow(taskId, kind, note?.take(500)?.ifEmpty { null }, avoidReason, nowText(), rid(), tx.seq)))
    }

    internal suspend fun putPreference(row: PreferenceRow) {
        val tx = tx()
        tx.dirty = true
        dao.upsertPreferences(listOf(row.copy(seq = tx.seq)))
    }

    internal suspend fun putMessage(row: MessageRow) {
        val tx = tx()
        tx.dirty = true
        dao.upsertMessages(listOf(row.copy(seq = tx.seq)))
        dao.linkMessage(row)
    }

    /** A message in the chat. Chips as JSON text (Chips.encode). Public for the assistant (llm/). */
    suspend fun addMessageNow(role: String, content: String, kind: String, taskIds: List<Long> = emptyList(),
                              chips: String = "[]", model: String? = null): MessageRow = write { addMessage(role, content, kind, taskIds, chips, model) }

    internal suspend fun addMessage(role: String, content: String, kind: String, taskIds: List<Long> = emptyList(),
                                    chips: String = "[]", model: String? = null): MessageRow {
        val tx = tx()
        if (role == Role.USER) tx.userActed = true
        val ids = taskIds.distinct()
        val row = MessageRow(nextId(META_MESSAGE_COUNTER, dao.maxMessageId()), role, content, kind, ids.firstOrNull(), ids,
            chips, model, nowText())
        putMessage(row)
        return row
    }

    /** Something you did outside the chat, written as your own message so the assistant reads it. */
    internal suspend fun addChatEvent(text: String, taskIds: List<Long>) {
        addMessage("user", text, "event", taskIds)
    }

    private suspend fun descriptionChanged(old: TaskRow, newText: String?) {
        if (!old.description.isNullOrEmpty() && old.description != newText) addEvent(old.id, "description_changed", old.description)
    }

    /** The task is done, cancelled or parked, so its open session ends with it. */
    internal suspend fun closeSessions(taskId: Long, outcome: String) {
        dao.openSessions(taskId).forEach { closeSession(it, outcome) }
    }

    /**
     * Records an outcome (done | another | stopped | paused) with a session_ended event (paused:
     * a paused event), "stopped, 7 of 25 min". Null if it was already closed (a second tap).
     */
    internal suspend fun closeSession(s: SessionRow, outcome: String, endedAt: Instant = clock()): SessionRow? {
        val cur = dao.session(s.id)?.takeIf { it.endedAt == null } ?: return null
        val closed = cur.copy(endedAt = Times.format(endedAt), outcome = outcome)
        putSession(closed)
        addEvent(closed.taskId, if (outcome == "paused") "paused" else "session_ended",
            "$outcome, ${FocusSessions.actualMinutes(closed, clock())} of ${closed.plannedMin} min")
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
            if (t.status != OPEN || t.kind != TaskKind.TASK) continue
            val new = t.copy(focusDate = date, focusRank = index + 1)
            putTask(new)
            out += new
        }
        val tx = tx()
        tx.dirty = true
        dao.upsertFocus(listOf(FocusRow(date, out.map { it.id }, tx.seq)))
        return out
    }

    private suspend fun tombstone(table: String, key: kotlinx.serialization.json.JsonElement) {
        val tx = tx()
        tx.dirty = true
        dao.insertTombstone(Tombstone(table, key.toString(), tx.seq))
    }

    /** The next id: after both the backup's counter and the highest id here, so a restored phone never reuses one. */
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
        val KINDS = TaskKind.ALL
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

        /** An event's random suffix: 8 hex characters. */
        fun rid(): String = "%08x".format(random.nextInt())

        /** Trimmed, cut with "…" when too long, null when empty. */
        fun clip(text: String?, limit: Int): String? {
            val t = text?.trim() ?: return null
            if (t.isEmpty()) return null
            return if (t.length <= limit) t else t.take(limit - 1) + "…"
        }
    }
}

/** The start of a calendar day, for "done today". */
fun ZonedDateTime.startOfDay(): Instant = toLocalDate().atStartOfDay(zone).toInstant()
