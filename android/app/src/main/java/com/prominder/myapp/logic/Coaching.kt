package com.prominder.myapp.logic

import com.prominder.myapp.data.CANCELLED
import com.prominder.myapp.data.DONE
import com.prominder.myapp.data.MessageKind
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.logic.ChipRules.chipIdForText
import com.prominder.myapp.logic.Focus.FOCUS_MAX
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** What the coach knows about a task and a chat turn: its stage, the playbooks that apply, which tasks a reply is about. Pure: no I/O. */
object Coaching {
    const val AVOIDED_MIN_EVENTS = 3
    val OVERWHELMED_FROM: LocalTime = LocalTime.of(15, 0)

    private val WORD = Regex("""[\p{L}\p{N}_]{3,}""")

    private fun words(text: String?): Set<String> = WORD.findAll(text ?: "").map { it.value.lowercase() }.toSet()

    /** Open tasks whose title shares a word with the message: the user named them. */
    fun namedTasks(text: String, open: List<TaskRow>): List<Long> {
        val w = words(text)
        return if (w.isEmpty()) emptyList() else open.filter { (w intersect words(it.title)).isNotEmpty() }.map { it.id }
    }

    /**
     * Up to [limit] task ids a reply is probably about, best first (only a hint for the LLM). The tasks the message names
     * come first: a task nagged all morning fills the check-in and recent slots, and must not crowd out the one the user
     * is talking about. Then the last check-in's tasks (while it's recent), then the tasks of today's latest messages.
     */
    fun selectCandidateTasks(
        reply: String, now: Instant, lastProactiveAt: Instant?, lastProactiveTaskIds: List<Long>, recentTaskIds: List<Long>,
        open: List<TaskRow>, linkHours: Double, limit: Int = 3,
    ): List<Long> {
        val out = mutableListOf<Long>()
        fun add(ids: Iterable<Long>) = ids.forEach { if (it !in out) out += it }
        add(namedTasks(reply, open))
        if (lastProactiveAt != null && Duration.between(lastProactiveAt, now).seconds <= linkHours * 3600) add(lastProactiveTaskIds)
        add(recentTaskIds)
        return out.take(limit)
    }

    /**
     * The check-in a message answers, for linking a turn where the agent changed nothing: the last check-in's tasks, but
     * only while the message is a reply to it. Not once the assistant has answered something since (the conversation
     * moved on), and not when the message names other tasks and not the check-in's.
     */
    fun checkinReplyIds(checkinIds: List<Long>, answeredSince: Boolean, named: List<Long>): List<Long> = when {
        answeredSince -> emptyList()
        named.isNotEmpty() && checkinIds.none { it in named } -> emptyList()
        else -> checkinIds
    }

    /** Which tasks a user turn is linked to: what the agent acted on, else an unambiguous check-in's task. */
    fun linkTaskIds(actedOn: List<Long>, checkinCandidates: List<Long>): List<Long> = when {
        actedOn.isNotEmpty() -> actedOn.distinct()
        checkinCandidates.size == 1 -> checkinCandidates
        else -> emptyList()
    }

    /** captured → clarified → planned → done; avoided after 3+ postponed / ignored events in 7 days. */
    fun taskStage(task: TaskRow, counts7d: Map<String, Int>?, today: LocalDate): String {
        if (task.status == DONE || task.status == CANCELLED) return task.status
        val counts = counts7d.orEmpty()
        if ((counts["postponed"] ?: 0) + (counts["ignored"] ?: 0) >= AVOIDED_MIN_EVENTS) return "avoided"
        if (task.focusDay == today) return "planned"
        if (!task.nextAction.isNullOrEmpty()) return "clarified"
        return "captured"
    }

    // ---------------------------------------------------------------- playbooks

    val PLAYBOOKS = listOf("planning", "stuck", "avoiding", "overwhelmed", "reentry", "celebrate", "sunday")

    /** At most two situational playbooks for one LLM call. */
    fun selectPlaybooks(job: String? = null, stages: Iterable<String> = emptyList(), signals: Iterable<String> = emptyList()): List<String> {
        val sig = signals.toSet()
        val order = mutableListOf<String>()
        if (job == "reentry") order += "reentry"
        else if (job == "morning") {
            order += "planning"
            if ("sunday" in sig) order += "sunday"
        }
        if ("reentry" in sig && "reentry" !in order) order += "reentry"
        if ("avoid_reason" in sig || "avoided" in stages.toSet()) order += "avoiding"
        if ("stuck" in sig) order += "stuck"
        if ("overwhelmed" in sig) order += "overwhelmed"
        if ("completed" in sig) order += "celebrate"
        return order.distinct().take(2)
    }

    val AVOID_CHIPS = listOf("too_big", "unclear", "boring")
    val AVOID_REASONS = AVOID_CHIPS + listOf("scary", "people", "no_energy")

    /** Playbook signals for a chat turn. sinceLastReply: (kind, content) of messages after the assistant's last one. */
    fun chatSignals(text: String, sinceLastReply: List<Pair<String, String>>, stuckToday: Boolean, now: ZonedDateTime, focusOpen: Int): Set<String> {
        val out = mutableSetOf<String>()
        val chip = chipIdForText(text)
        if (stuckToday || chip == "stuck") out += "stuck"
        if (chip in AVOID_CHIPS) out += "avoid_reason"
        if (chip == "pick_one") out += "reentry"
        if (now.toLocalTime() >= OVERWHELMED_FROM && focusOpen >= FOCUS_MAX) out += "overwhelmed"
        // A ✓ Done taken back with Undo ("[Undone: …]") doesn't count.
        val events = sinceLastReply.filter { (kind, _) -> kind == MessageKind.EVENT }.map { it.second }
        if (events.count { it.startsWith("[Marked done") } > events.count { it.startsWith("[Undone") }) out += "completed"
        return out
    }
}
