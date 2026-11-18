package com.prominder.myapp.data

import com.prominder.myapp.logic.Logic
import com.prominder.myapp.logic.due
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The changes the assistant makes (its tools, Phase D) and the reply buttons that act on several tasks, ported from
 * backend/app/tasks.py and actions.py. Each is one Store transaction, like the screens' own changes.
 */

/** The fields tasks.update changes; only the keys given are touched. */
val UPDATABLE = setOf(
    "title", "description", "kind", "priority", "due_at", "remind_at", "recurrence", "next_action", "estimate_min", "resume_note",
)

const val RESUME_NOTE_MAX = 300
const val PREFERENCES_MAX = 20
const val PREFERENCE_CHARS = 200

/** tasks.create, with every field the assistant can set. The reminder defaults to the due time (a meeting's: its lead). */
suspend fun Store.createTask(
    title: String, kind: String = "task", priority: Int = 1, description: String? = null, dueAt: Instant? = null,
    remindAt: Instant? = null, recurrence: String? = null, parentId: Long? = null, nextAction: String? = null, estimateMin: Int? = null,
): TaskRow = write {
    checkFields(kind = kind, priority = priority, recurrence = recurrence, estimateMin = estimateMin)
    require(title.isNotBlank()) { "The title is empty." }
    val s = settings()
    val remind = remindAt ?: dueAt?.let { if (kind == "meeting") it.minusSeconds(60L * s.meetingLeadMin) else it }
    val row = TaskRow(
        id = nextId(Store.META_TASK_COUNTER, dao.maxTaskId()),
        parentId = parentId,
        title = title.trim().take(Store.TITLE_MAX),
        kind = kind,
        priority = priority,
        description = Store.clip(description, Store.DESCRIPTION_MAX),
        dueAt = dueAt?.let(Times::format),
        remindAt = remind?.let(Times::format),
        remindRequested = remind != null,
        recurrence = recurrence,
        nextAction = Store.clip(nextAction, Store.NEXT_ACTION_MAX),
        estimateMin = estimateMin,
        createdAt = nowText(),
    )
    putTask(row)
    row
}

/**
 * tasks.update. [fields] maps a name from [UPDATABLE] to its new value (times as Instants, null clears). A new time
 * resets the nagging, counts as a requested reminder and ends a park; a changed description keeps the old text as a
 * description_changed event.
 */
suspend fun Store.updateTask(id: Long, fields: Map<String, Any?>): TaskRow? = write {
    val t = dao.task(id) ?: return@write null
    val f = fields.filterKeys { it in UPDATABLE }
    if (f.isEmpty()) return@write t
    checkFields(
        kind = f["kind"] as String? ?: "task", priority = f["priority"] as Int?, recurrence = f["recurrence"] as String?,
        estimateMin = f["estimate_min"] as Int?,
    )
    var new = t
    if ("title" in f) new = new.copy(title = (f["title"] as String).trim().take(Store.TITLE_MAX))
    if ("description" in f) new = new.copy(description = Store.clip(f["description"] as String?, Store.DESCRIPTION_MAX))
    if ("kind" in f) new = new.copy(kind = f["kind"] as String)
    if ("priority" in f) new = new.copy(priority = f["priority"] as Int)
    if ("recurrence" in f) new = new.copy(recurrence = f["recurrence"] as String?)
    if ("next_action" in f) new = new.copy(nextAction = Store.clip(f["next_action"] as String?, Store.NEXT_ACTION_MAX))
    if ("estimate_min" in f) new = new.copy(estimateMin = f["estimate_min"] as Int?)
    if ("resume_note" in f) new = new.copy(resumeNote = Store.clip(f["resume_note"] as String?, RESUME_NOTE_MAX))
    if ("due_at" in f || "remind_at" in f) {
        val due = f["due_at"] as Instant?
        if ("due_at" in f) new = new.copy(dueAt = due?.let(Times::format))
        var remindSet = "remind_at" in f
        var remind = f["remind_at"] as Instant?
        if (!remindSet && due != null) {
            // The lead of the task's kind before this change, as on the server.
            remind = if (t.kind == "meeting") due.minusSeconds(60L * settings().meetingLeadMin) else due
            remindSet = true
        }
        if (remindSet) new = new.copy(remindAt = remind?.let(Times::format))
        new = new.copy(nagCount = 0, escalated = false, remindRequested = remindSet && remind != null, parkedUntil = null)
    }
    putTask(new)
    if ("description" in f && !t.description.isNullOrEmpty() && t.description != new.description) {
        addEvent(id, "description_changed", t.description)
    }
    new
}

/** tasks.set_followup: the next check-in. [requested]: you asked for that time (it doesn't use up the per-task cap). */
suspend fun Store.setFollowup(id: Long, at: Instant, requested: Boolean): TaskRow? = write {
    val t = dao.task(id)?.takeIf { it.status == OPEN } ?: return@write null
    val new = t.copy(remindAt = Times.format(at), remindRequested = requested, nagCount = 0, escalated = false, parkedUntil = null)
    putTask(new)
    new
}

/** tasks.block: waiting on someone or something; looked at again at [until]. */
suspend fun Store.block(id: Long, reason: String, until: Instant): TaskRow? = write {
    val t = dao.task(id)?.takeIf { it.status == OPEN } ?: return@write null
    val new = t.copy(blockedReason = reason, blockedUntil = Times.format(until), remindAt = Times.format(until), remindRequested = false, nagCount = 0)
    putTask(new)
    addEvent(id, "blocked", reason)
    new
}

suspend fun Store.unblock(id: Long): TaskRow? = write {
    val t = dao.task(id) ?: return@write null
    val new = t.copy(blockedReason = null, blockedUntil = null)
    putTask(new)
    addEvent(id, "unblocked")
    new
}

/** tasks.reset_avoidance: a fresh start (a reason given, progress made): earlier postponements stop counting, a park ends. */
suspend fun Store.resetAvoidance(id: Long) = write {
    val t = dao.task(id) ?: return@write
    putTask(t.copy(avoidResetAt = nowText(), avoidAsks = 0, avoidAskedAt = null, parkedUntil = null))
}

/** tasks.add_event, for the tools (postponed, committed, progress, stuck, avoided…). */
suspend fun Store.taskEvent(id: Long, kind: String, note: String? = null, avoidReason: String? = null) = write {
    require(kind in EVENT_KINDS) { "Unknown event kind: $kind" }
    require(avoidReason == null || avoidReason in Logic.AVOID_REASONS) { "Unknown avoid reason: $avoidReason" }
    addEvent(id, kind, note, avoidReason)
}

/** sessions.close_notes_for_task: where you stopped was saved in the chat, so the app needn't ask again. */
suspend fun Store.closeNotesForTask(id: Long) = write {
    for (s in dao.sessionsOf(id)) if (s.noteClosedAt == null) putSession(s.copy(noteClosedAt = nowText()))
}

/** preferences.add: one short line. IllegalArgumentException when it's empty or the list is full. */
suspend fun Store.addPreference(text: String): PreferenceRow = write {
    val line = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(PREFERENCE_CHARS)
    require(line.isNotEmpty()) { "The preference is empty." }
    val all = dao.preferences()
    all.firstOrNull { it.text.equals(line, ignoreCase = true) }?.let { return@write it }
    require(all.size < PREFERENCES_MAX) { "There are already $PREFERENCES_MAX saved preferences. The user can delete some under ⚙ Settings." }
    val row = PreferenceRow(nextId(Store.META_PREFERENCE_COUNTER, dao.maxPreferenceId()), line, nowText())
    putPreference(row)
    row
}

/** thread.link: which tasks a message is about. */
suspend fun Store.linkMessage(id: Long, taskIds: List<Long>) = write {
    val m = dao.message(id) ?: return@write
    val ids = taskIds.distinct()
    putMessage(m.copy(taskIds = ids, taskId = ids.firstOrNull()))
}

/** tasks.list_tasks: the lists the assistant can ask for. */
suspend fun Store.listTasks(scope: String): List<TaskRow> {
    val s = settings()
    val now = now()
    val (start, end) = DayMath.dayBounds(now.atZone(s.zone))
    if (scope == "done_today") return dao.doneSince(Times.format(start))
    val open = dao.openTasks()
    return when (scope) {
        "today" -> TaskLists.today(open, end)
        "overdue" -> open.filter { t -> t.due?.let { it < now } == true }.sortedWith(compareBy({ it.dueAt }, { it.id }))
        "upcoming" -> TaskLists.upcoming(open, end)
        "blocked" -> open.filter { it.blockedReason != null }.sortedWith(compareBy({ it.blockedUntil ?: "9999" }, { it.id }))
        else -> TaskLists.all(open)
    }
}

// ------------------------------------------------------------ reply buttons on several tasks (actions.py)

private val DAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.ENGLISH)

private fun titles(rows: List<TaskRow>, limit: Int = 5): String {
    val shown = rows.take(limit).joinToString(" · ") { it.title }
    return shown + if (rows.size > limit) " (+${rows.size - limit})" else ""
}

/** Not now (actions.park): no check-ins for a week, out of Today's 3. Returns when the park ends. */
suspend fun Store.notNow(id: Long, days: Long = Logic.PARK_DAYS): Instant? = write {
    val t = dao.task(id)?.takeIf { it.status == OPEN } ?: return@write null
    closeSessions(id, "stopped")
    val until = now().plus(Duration.ofDays(days))
    putTask(t.copy(parkedUntil = Times.format(until), remindAt = Times.format(until), remindRequested = false, nagCount = 0,
        escalated = false, focusDate = null, focusRank = null, avoidResetAt = nowText(), avoidAsks = 0, avoidAskedAt = null))
    addEvent(id, "parked", "until ${until.atZone(settings().zone).toLocalDate()}")
    addChatEvent("[Not now: ${t.title} — parked until ${until.atZone(settings().zone).format(DAY_DATE)}]", listOf(id))
    until
}

/** Move overdue to next week (the re-entry message): an amnesty, so no postponed events. Returns the moved tasks. */
suspend fun Store.moveOverdue(): List<TaskRow> = write {
    val s = settings()
    val now = now()
    val moved = mutableListOf<TaskRow>()
    val overdue = dao.openTasks().filter { t -> t.kind == "task" && t.due?.let { it < now } == true }.sortedWith(compareBy({ it.dueAt }, { it.id }))
    for (t in overdue) {
        val due = t.due!!
        val new = if (t.recurrence != null) DayMath.nextFutureOccurrence(due, t.recurrence, s.zone, now)
        else Logic.movedToNextWeek(due.atZone(s.zone), now).toInstant()
        val remind = if (t.kind == "meeting") new.minusSeconds(60L * s.meetingLeadMin) else new
        val row = t.copy(dueAt = Times.format(new), remindAt = Times.format(remind), remindRequested = true, nagCount = 0, escalated = false,
            parkedUntil = null)
        putTask(row)
        moved += row
    }
    val n = moved.size
    addChatEvent(if (n > 0) "[Moved $n overdue task${if (n != 1) "s" else ""} to next week: ${titles(moved)}]" else "[Nothing overdue to move]",
        moved.map { it.id })
    moved
}

/** Drop them (the Sunday cleanup): cancels exactly these, if still open. */
suspend fun Store.drop(ids: List<Long>): List<TaskRow> = write {
    val dropped = dao.tasks(ids.distinct()).filter { it.status == OPEN }.sortedBy { it.id }
    for (t in dropped) {
        closeSessions(t.id, "stopped")
        for (child in dao.children(t.id)) if (child.status == OPEN) putTask(child.copy(status = CANCELLED, remindAt = null))
        putTask(t.copy(status = CANCELLED, remindAt = null))
    }
    addChatEvent(if (dropped.isNotEmpty()) "[Dropped: ${titles(dropped)}]" else "[Nothing to drop]", dropped.map { it.id })
    dropped
}

/** Keep (the Sunday cleanup): nothing changes; the tap is noted so the assistant knows. */
suspend fun Store.keep(ids: List<Long>): List<TaskRow> = write {
    val kept = dao.tasks(ids.distinct()).filter { it.status == OPEN }.sortedBy { it.id }
    addChatEvent(if (kept.isNotEmpty()) "[Kept: ${titles(kept)}]" else "[Kept]", kept.map { it.id })
    kept
}

/**
 * What now? (actions.what_now): one task to do now, picked by code (Logic.pickNext), no LLM. Writes the tap as an
 * event and the answer as the assistant's message, with ▶ Start · Low energy · Something else.
 */
suspend fun Store.whatNow(lowEnergy: Boolean = false, exclude: List<Long> = emptyList(), chip: String = "what_now"): MessageRow = write {
    val s = settings()
    val now = now()
    val local = now.atZone(s.zone)
    val today = Logic.focusDay(local, s.dayResetTime)
    val open = TaskLists.all(dao.openTasks())
    val focus = TaskLists.focus(dao.focusTasks(today.toString()), today.toString())
    val since = Times.format(today.atStartOfDay(s.zone).toInstant())
    val done = TaskLists.doneToday(focus, focus.flatMap { t -> dao.eventsSince(t.id, since).filter { it.kind == "done" } })
    val end = now.plus(Duration.ofHours(12))
    val fixed = open.filter { t -> (t.kind == "meeting" || t.kind == "fence") && t.due?.let { it < end && (it >= now || t.recurrence != null) } == true }
    val next = Logic.meetingOccurrences(fixed, now, end, s.zone).firstOrNull { it.first > now }
    val pick = Logic.pickNext(open, now, next?.first, Logic.DEFAULT_MULTIPLIER, lowEnergy, today = today, doneIds = done, exclude = exclude)
    val lang = Logic.chipLang(dao.latestOfKindsBy(listOf("chat"), "user")?.content, open.take(5).joinToString(" ") { it.title })
    addChatEvent("[${Logic.chipLabel(chip, lang)}]", emptyList())
    val text = Logic.whatNowText(pick, lang, next?.first?.atZone(s.zone))
    val task = pick?.task
    val chips = if (task == null) emptyList() else {
        val offered = (exclude + task.id).distinct()
        Logic.chipsJson(Logic.chipsFor("what_now", null), lang, task.id).mapNotNull { c ->
            when (c.id) {
                "start" -> c.copy(minutes = pick.minutes)
                "low_energy" -> if (lowEnergy || pick.reason == "bonus") null else c.copy(lowEnergy = true)
                "something_else" -> c.copy(exclude = offered, lowEnergy = lowEnergy)
                else -> c
            }
        }
    }
    addMessage("assistant", text, "chat", listOfNotNull(task?.id), Chips.encode(chips))
}

/** tasks.EVENT_KINDS */
val EVENT_KINDS = setOf(
    "postponed", "committed", "progress", "stuck", "blocked", "unblocked", "ignored", "done", "description_changed",
    "session_started", "session_ended", "paused", "parked", "avoided",
)

private fun checkFields(kind: String, priority: Int?, recurrence: String?, estimateMin: Int?) {
    require(kind in Store.KINDS) { "Unknown kind: $kind" }
    require(priority == null || priority in 1..3) { "priority must be 1-3" }
    require(recurrence == null || recurrence in Store.RECURRENCES) { "Unknown recurrence: $recurrence" }
    require(estimateMin == null || estimateMin in 1..10000) { "estimate_min must be 1-10000" }
}
