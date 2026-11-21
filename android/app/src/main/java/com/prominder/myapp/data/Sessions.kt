package com.prominder.myapp.data

import com.prominder.myapp.logic.FocusSessions
import java.time.Duration
import java.time.Instant

/*
 * Focus sessions (▶ Start): a timer on the task's next action,
 * one check-in at its end (sent by the tick, remind/Ticker.kt), and "Where did you stop?" after a stopped or
 * unanswered one. At most one session is open: starting another closes it first. Each action is one Store
 * transaction and writes a short event in the chat ("[▶ 25 min: Find the PDF (Taxes)]"), which counts as you
 * answering, like a typed reply.
 */

/** sessions.OUTCOMES */
val SESSION_OUTCOMES = setOf("done", "another", "stopped", "paused")

/** The open session (normally at most one) and its task, for the timer. */
data class OpenSession(val session: SessionRow, val task: TaskRow?)

/**
 * ▶ Start (another = ▶ Another round, as long as the task's last session). Any other open
 * session is closed first. [minutes] falls back to the last session's length (another), then to [default]. The task's
 * own check-in that would fall inside the session (+ the focus hold) is dropped: the end check-in replaces it.
 * Null if the task isn't open.
 */
suspend fun Store.startSession(taskId: Long, minutes: Int? = null, another: Boolean = false,
                               default: Int = FocusSessions.START_MIN_NOTIFICATION): SessionRow? = write {
    val task = dao.task(taskId)?.takeIf { it.status == OPEN } ?: return@write null
    val now = now()
    val last = if (another) dao.sessionsOf(taskId).maxByOrNull { it.id } else null
    for (open in dao.allOpenSessions().sortedBy { it.id }) {
        if (another && last != null && open.id == last.id) {
            closeSession(open, "another")
        } else {
            val (outcome, endedAt) = FocusSessions.sessionCloseOutcome(open, now)
            closeSession(open, outcome, endedAt)
        }
    }
    val length = FocusSessions.sessionLength(minutes ?: last?.plannedMin, default)
    val session = SessionRow(nextId(Store.META_SESSION_COUNTER, dao.maxSessionId()), taskId, nowText(), length)
    putSession(session)
    addEvent(taskId, "session_started", "$length min" + if (another) " (another round)" else "")
    // Started: it isn't avoided (or parked) any more.
    var t = task.copy(avoidResetAt = nowText(), avoidAsks = 0, avoidAskedAt = null, parkedUntil = null)
    val remind = Times.parseOrNull(t.remindAt)
    if (remind != null && remind <= FocusSessions.sessionEndAt(session).plus(Duration.ofMinutes(FocusSessions.FOCUS_HOLD_MIN))) t = t.copy(remindAt = null)
    putTask(t)
    val head = if (another) "▶ Another round, " else "▶ "
    val step = task.nextAction?.takeIf { it.isNotEmpty() } ?: task.title
    addChatEvent("[$head$length min: $step" + if (step != task.title) " (${task.title})]" else "]", listOf(taskId))
    session
}

/** The task's open session ends as stopped (then "Where did you stop?" is offered). */
suspend fun Store.stopSession(taskId: Long): TaskRow? = write {
    val task = dao.task(taskId) ?: return@write null
    val open = dao.openSessions(taskId).maxByOrNull { it.id }
    val closed = open?.let { closeSession(it, "stopped") }
    addChatEvent(if (closed != null) "[Stopped after ${FocusSessions.actualMinutes(closed, now())} min: ${task.title}]" else "[Stopped: ${task.title}]",
        listOf(taskId))
    task
}

/**
 * The end of a session, from the timer or a notification: done | another | stopped. Returns the session that's open
 * afterwards (another: the new round), else the closed one; null if there's no such session.
 */
suspend fun Store.endSession(sessionId: Long, outcome: String): SessionRow? {
    val s = dao.session(sessionId) ?: return null
    return when (outcome) {
        "done" -> { complete(s.taskId); dao.session(sessionId) }
        "another" -> startSession(s.taskId, s.plannedMin, another = true)
        "stopped" -> { stopSession(s.taskId); dao.session(sessionId) }
        else -> throw IllegalArgumentException("Unknown outcome: $outcome")
    }
}

/**
 * A session's length: 5 / 15 / 25 / 45, or +5. Only an open session; a sent end check-in comes again at the
 * new end. Null when it had already ended.
 */
suspend fun Store.setSessionLength(sessionId: Long, minutes: Int): SessionRow? = write {
    val s = dao.session(sessionId)?.takeIf { it.endedAt == null } ?: return@write null
    val new = s.copy(plannedMin = FocusSessions.sessionLength(minutes), endSentAt = null)
    putSession(new)
    new
}

/**
 * "Where did you stop?" (optional). The line becomes the task's resume note, shown on the next
 * timer and used by the next check-in. Empty or null just dismisses the field. Either way it isn't offered again.
 */
suspend fun Store.saveBreadcrumb(sessionId: Long, text: String?): TaskRow? = write {
    val s = dao.session(sessionId) ?: return@write null
    if (s.noteClosedAt == null) putSession(s.copy(noteClosedAt = nowText()))
    val line = text?.trim().orEmpty()
    val task = dao.task(s.taskId) ?: return@write null
    if (line.isEmpty()) return@write task
    val new = task.copy(resumeNote = Store.clip(line, RESUME_NOTE_MAX))
    putTask(new)
    addChatEvent("[Where I stopped: $line]", listOf(task.id))
    new
}

/** The open session (the latest, if there were ever two) and its task. */
suspend fun Store.currentSession(): OpenSession? {
    val s = dao.allOpenSessions().maxWithOrNull(compareBy({ it.startedAt }, { it.id })) ?: return null
    return OpenSession(s, dao.task(s.taskId))
}

/** The timer's numbers: when it ends, how much is left (never negative). */
fun SessionRow.endsAt(): Instant = FocusSessions.sessionEndAt(this)
