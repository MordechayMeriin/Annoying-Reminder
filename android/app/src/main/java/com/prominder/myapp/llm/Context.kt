package com.prominder.myapp.llm

import com.prominder.myapp.data.CANCELLED
import com.prominder.myapp.data.DONE
import com.prominder.myapp.data.EventKind
import com.prominder.myapp.data.MessageKind
import com.prominder.myapp.data.OPEN
import com.prominder.myapp.data.Role
import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.data.AppDao
import com.prominder.myapp.data.EventRow
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.logic.avoidReset
import com.prominder.myapp.logic.blocked
import com.prominder.myapp.logic.due
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Subtasks, recent events and 7-day event counts per task. */
data class Facts(
    val subtasks: Map<Long, List<TaskRow>> = emptyMap(),
    val events: Map<Long, List<EventRow>> = emptyMap(),
    val counts: Map<Long, Map<String, Int>> = emptyMap(),
) {
    /** A stuck event on one of these tasks since [start] (the stuck playbook). */
    fun stuckSince(ids: List<Long>, start: Instant): Boolean {
        val from = Times.format(start)
        return ids.any { id -> events[id].orEmpty().any { it.kind == EventKind.STUCK && it.createdAt >= from } }
    }
}

/**
 * Compact, plain-text context for the LLM (easier for a mid-size model than
 * JSON dumps). The format functions are pure; the suspend ones read what they need from the database.
 */
object LlmContext {
    private val PRIORITY = mapOf(2 to "important", 3 to "CRITICAL")
    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH)

    /** "today 14:00", "tomorrow 09:00", "yesterday 18:30", "Thu 12 Nov 10:00". */
    fun fmtWhen(at: Instant?, nowLocal: ZonedDateTime): String {
        at ?: return ""
        val d = at.atZone(nowLocal.zone)
        val day = when (ChronoUnit.DAYS.between(nowLocal.toLocalDate(), d.toLocalDate())) {
            0L -> "today"
            1L -> "tomorrow"
            -1L -> "yesterday"
            else -> d.format(DAY)
        }
        return "$day ${d.format(HHMM)}"
    }

    fun fmtWhen(text: String?, nowLocal: ZonedDateTime): String = fmtWhen(Times.parseOrNull(text), nowLocal)

    fun formatTask(
        task: TaskRow, nowLocal: ZonedDateTime, subtasks: List<TaskRow> = emptyList(), events: List<EventRow> = emptyList(),
        counts: Map<String, Int> = emptyMap(), contactsToday: Int = 0, today: LocalDate? = null,
    ): String {
        val head = mutableListOf("#${task.id} \"${task.title}\"")
        if (task.kind == TaskKind.MEETING) head += "meeting"
        if (today != null && task.focusDate == today.toString()) head += "in Today's 3 (#${task.focusRank ?: "?"})"
        PRIORITY[task.priority]?.let { head += it }
        task.due?.let { due ->
            val overdue = due < nowLocal.toInstant() && task.kind != TaskKind.MEETING
            head += (if (overdue) "OVERDUE since " else "due ") + fmtWhen(due, nowLocal)
        }
        task.recurrence?.let { head += "repeats $it" }
        if (task.status != OPEN) head += task.status
        if (contactsToday > 0) head += "checked in ${contactsToday}x today"
        val lines = mutableListOf(head.joinToString(" · "))
        if (!task.nextAction.isNullOrEmpty()) {
            val est = task.estimateMin?.let { " (whole task ~$it min)" }.orEmpty()
            lines += "  Next action: ${task.nextAction}$est"
        } else if (task.kind == TaskKind.TASK) {
            lines += "  Next action: (none yet: infer one and save it with update_task)"
        }
        if (!task.resumeNote.isNullOrEmpty()) lines += "  Where they stopped last time: ${task.resumeNote}"
        if (!task.description.isNullOrEmpty()) lines += "  Description: ${task.description}"
        if (!task.blockedReason.isNullOrEmpty()) lines += "  Blocked: ${task.blockedReason} (check again ${fmtWhen(task.blocked, nowLocal)})"
        if (subtasks.isNotEmpty()) {
            lines += "  Steps: " + subtasks.joinToString("; ") { "[${if (it.status == DONE) "x" else " "}] #${it.id} ${it.title}" }
        }
        if (events.isNotEmpty()) {
            lines += "  History (newest first): " + events.joinToString("; ") { e ->
                "${fmtWhen(e.createdAt, nowLocal)} ${e.kind}" + if (!e.note.isNullOrEmpty() && e.kind != EventKind.DESCRIPTION_CHANGED) " (${e.note})" else ""
            }
        }
        val shown = counts.filterKeys { it in COUNTED }
        if (shown.isNotEmpty()) lines += "  Last 7 days: " + shown.toSortedMap().entries.joinToString(", ") { "${it.key} ${it.value}x" }
        return lines.joinToString("\n")
    }

    private val COUNTED = setOf("postponed", "ignored", "committed", "progress", "stuck")

    fun formatMessages(messages: List<MessageRow>, nowLocal: ZonedDateTime, maxChars: Int = 300): String =
        messages.joinToString("\n") { m ->
            val who = if (m.kind == MessageKind.EVENT) "Event" else if (m.role == Role.USER) "User" else "You"
            var text = m.content.replace("\n", " ")
            if (text.length > maxChars) text = text.take(maxChars - 1) + "…"
            "[${fmtWhen(m.createdAt, nowLocal)}] $who: $text"
        }

    fun formatOpenItems(items: List<TaskRow>, nowLocal: ZonedDateTime, exclude: Set<Long>): String {
        val rows = items.filter { it.id !in exclude }.take(10)
        if (rows.isEmpty()) return "(nothing else)"
        return rows.joinToString("\n") { t ->
            "- #${t.id} ${t.title}" + (t.due?.let { " (${fmtWhen(it, nowLocal)})" } ?: "") +
                (t.nextAction?.takeIf { it.isNotEmpty() }?.let { " → $it" } ?: "")
        }
    }

    fun taskContexts(items: List<TaskRow>, nowLocal: ZonedDateTime, facts: Facts, contactsToday: Map<Long, Int> = emptyMap(), today: LocalDate? = null): List<String> =
        items.map { t ->
            formatTask(t, nowLocal, facts.subtasks[t.id].orEmpty(), facts.events[t.id].orEmpty(), facts.counts[t.id].orEmpty(),
                contactsToday[t.id] ?: 0, today)
        }

    // ------------------------------------------------------------ reading

    suspend fun loadFacts(dao: AppDao, items: List<TaskRow>, now: Instant): Facts {
        if (items.isEmpty()) return Facts()
        val ids = items.map { it.id }
        return Facts(
            subtasks = ids.associateWith { id -> dao.children(id).filter { it.status != CANCELLED }.sortedWith(compareBy({ it.createdAt }, { it.id })) },
            events = ids.associateWith { id -> dao.eventsSince(id, "").take(8) },
            counts = eventCounts(dao, items, now, 7),
        )
    }

    /** Events per kind in the last [days], counted from the task's last fresh start. */
    suspend fun eventCounts(dao: AppDao, items: List<TaskRow>, now: Instant, days: Long): Map<Long, Map<String, Int>> {
        val cutoff = now.minus(Duration.ofDays(days))
        return items.associate { t ->
            val reset = t.avoidReset
            val start = Times.format(if (reset != null && reset > cutoff) reset else cutoff)
            t.id to dao.eventsSince(t.id, start).filter { it.createdAt > start }.groupingBy { it.kind }.eachCount()
        }
    }

    /** The latest [limit] messages about any of these tasks (any day), oldest first. */
    suspend fun forTasks(dao: AppDao, ids: List<Long>, limit: Int): List<MessageRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = LinkedHashMap<Long, MessageRow>()
        for (id in ids.distinct()) dao.messagesForTask(id, limit).forEach { byId[it.id] = it }
        return byId.values.sortedBy { it.id }.takeLast(limit)
    }

    suspend fun linkedHistory(dao: AppDao, ids: List<Long>, nowLocal: ZonedDateTime, limit: Int = 8): String {
        val msgs = forTasks(dao, ids, limit)
        return if (msgs.isEmpty()) "(no earlier messages about this)" else formatMessages(msgs, nowLocal)
    }

    /** The current chat day, oldest first, capped for very busy days. */
    suspend fun since(dao: AppDao, start: Instant, limit: Int): List<MessageRow> =
        dao.messagesSince(Times.format(start)).take(limit).reversed()
}
