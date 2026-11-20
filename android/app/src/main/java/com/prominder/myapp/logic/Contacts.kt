package com.prominder.myapp.logic

import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.logic.Loudness.escalates
import com.prominder.myapp.logic.Schedule.dayStart
import com.prominder.myapp.logic.Schedule.inQuietHours
import com.prominder.myapp.logic.Schedule.quietEndAfter
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** The guardrails on proactive messages: quiet hours, Shabbat, the gap, the daily and per-task caps, the focus hold. Pure: no I/O. */
object Contacts {
    /** The kind a session's end goes through the guardrails as: you asked for it, so the gap and the focus hold don't apply. */
    const val SESSION_END = "session"

    data class Guardrails(
        val quietStart: LocalTime,
        val quietEnd: LocalTime,
        val dayReset: LocalTime = LocalTime.of(4, 0),
        val minGapMin: Int = 20,
        val maxPerDay: Int = 20,
        val maxPerTaskPerDay: Int = 4,
        val followupMinMin: Int = 10,
        val followupMaxDays: Int = 7,
        /** Shabbat / holidays: when the current rest period ends, or null (not one now). */
        val restUntil: ((ZonedDateTime) -> Instant?)? = null,
    )

    /** What was already sent since the last day reset (derived from messages, never stored). */
    data class ContactState(
        val lastProactiveAt: Instant? = null,
        val sentToday: Int = 0,
        /** Only unrequested check-ins (kind 'nudge') per task. */
        val sentTodayByTask: Map<Long, Int> = emptyMap(),
        /** A focus session runs (or just ended): other check-ins wait until then. */
        val focusHoldUntil: Instant? = null,
    )

    data class ContactPlan(val send: List<TaskRow>, val defer: Map<Long, Instant>)

    /**
     * May a due task be contacted about now? Null = now, else the moment to postpone its remind_at to.
     * kind 'session' = the check-in at the end of a focus session. The latest postponement wins.
     */
    fun contactDecision(task: TaskRow, state: ContactState, now: ZonedDateTime, g: Guardrails): Instant? =
        contactReasons(task, state, now, g).maxOfOrNull { it.until }

    /** One rule that holds a contact back, and until when (for the Diagnostics tick log). */
    data class Hold(val reason: String, val until: Instant)

    /** Every rule that holds this contact back now (none = send). [contactDecision] takes the latest. */
    fun contactReasons(task: TaskRow, state: ContactState, now: ZonedDateTime, g: Guardrails): List<Hold> {
        val nowI = now.toInstant()
        val later = mutableListOf<Hold>()
        g.restUntil?.invoke(now)?.let { later += Hold("Shabbat / holiday", it) }
        if (inQuietHours(now, g.quietStart, g.quietEnd)) later += Hold("quiet hours", quietEndAfter(now, g.quietEnd).toInstant())
        task.blocked?.let { if (it > nowI) later += Hold("blocked", it) }
        val fixed = task.kind == TaskKind.MEETING || task.kind == TaskKind.FENCE
        if (!fixed && task.kind != SESSION_END) state.focusHoldUntil?.let { if (it > nowI) later += Hold("focus session", it) }
        if (!fixed) {
            if (state.lastProactiveAt != null && task.kind != SESSION_END) {
                val gapEnd = state.lastProactiveAt.plus(Duration.ofMinutes(g.minGapMin.toLong()))
                if (gapEnd > nowI) {
                    val waited = Duration.between(state.lastProactiveAt, nowI).toMinutes()
                    later += Hold("min gap, $waited of ${g.minGapMin} min", gapEnd)
                }
            }
            if (state.sentToday >= g.maxPerDay) later += Hold("daily cap, ${state.sentToday} of ${g.maxPerDay}", nextMorning(now, g).toInstant())
            val perTask = if (escalates(task, nowI)) g.maxPerTaskPerDay else minOf(1, g.maxPerTaskPerDay)
            val sent = state.sentTodayByTask[task.id] ?: 0
            if (!task.remindRequested && sent >= perTask) {
                later += Hold(if (perTask == 1) "one check-in a day" else "per-task cap, $sent of $perTask", nextMorning(now, g).toInstant())
            }
        }
        return later
    }

    /** First moment the daily counters have reset AND quiet hours are over. */
    fun nextMorning(now: ZonedDateTime, g: Guardrails): ZonedDateTime {
        val t = dayStart(now, g.dayReset).plusDays(1)
        return if (inQuietHours(t, g.quietStart, g.quietEnd)) quietEndAfter(t, g.quietEnd) else t
    }

    /** Split due tasks into one combined message (send) and postponements (defer). */
    fun planContacts(due: Iterable<TaskRow>, state: ContactState, now: ZonedDateTime, g: Guardrails): ContactPlan {
        val send = mutableListOf<TaskRow>()
        val defer = linkedMapOf<Long, Instant>()
        for (task in due) {
            val later = contactDecision(task, state, now, g)
            if (later == null) send += task else defer[task.id] = later
        }
        // Meetings and fences first (soonest start), then most important, then longest waiting.
        val far = Instant.MAX
        fun fixed(t: TaskRow) = t.kind == TaskKind.MEETING || t.kind == TaskKind.FENCE
        send.sortWith(compareBy<TaskRow>({ !fixed(it) }, { if (fixed(it)) it.due ?: far else far }, { -it.priority }, { it.remind ?: far }))
        return ContactPlan(send, defer)
    }

    /** Keep an LLM-chosen follow-up within [now + min, now + max days]. */
    fun clampFollowup(now: Instant, requested: Instant, g: Guardrails): Instant {
        val lo = now.plus(Duration.ofMinutes(g.followupMinMin.toLong()))
        val hi = now.plus(Duration.ofDays(g.followupMaxDays.toLong()))
        return minOf(maxOf(requested, lo), hi)
    }
}
