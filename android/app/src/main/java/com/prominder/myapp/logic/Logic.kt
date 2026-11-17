package com.prominder.myapp.logic

import com.prominder.myapp.data.SessionRow
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil

/*
 * Pure scheduling logic (no I/O), ported one for one from backend/app/logic.py. The Python tests are ported
 * too (test/…/logic/): a rule that passed in Python passes here.
 *
 * Python works on dicts with aware datetimes; here a task is a [TaskRow] (times as stored text, read through
 * the extension properties below), "now in local time" is a ZonedDateTime, and moments are Instants.
 */

// ---------------------------------------------------------------- reading a row's times

val TaskRow.due: Instant? get() = Times.parseOrNull(dueAt)
val TaskRow.remind: Instant? get() = Times.parseOrNull(remindAt)
val TaskRow.blocked: Instant? get() = Times.parseOrNull(blockedUntil)
val TaskRow.parked: Instant? get() = Times.parseOrNull(parkedUntil)
val TaskRow.avoidAsked: Instant? get() = Times.parseOrNull(avoidAskedAt)
val TaskRow.avoidReset: Instant? get() = Times.parseOrNull(avoidResetAt)
val TaskRow.lastNotified: Instant? get() = Times.parseOrNull(lastNotifiedAt)
val TaskRow.created: Instant? get() = Times.parseOrNull(createdAt)
val TaskRow.staleOffered: Instant? get() = Times.parseOrNull(staleOfferedAt)
val TaskRow.focusDay: LocalDate? get() = focusDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

val SessionRow.started: Instant get() = Times.parse(startedAt)
val SessionRow.ended: Instant? get() = Times.parseOrNull(endedAt)
val SessionRow.endSent: Instant? get() = Times.parseOrNull(endSentAt)
val SessionRow.noteClosed: Instant? get() = Times.parseOrNull(noteClosedAt)

private fun ZonedDateTime.at(t: LocalTime): ZonedDateTime = with(t).truncatedTo(ChronoUnit.MINUTES)

object Logic {
    // ================================================================ basics

    /** Quiet window may cross midnight (e.g. 23:00 → 07:00). */
    fun inQuietHours(now: ZonedDateTime, start: LocalTime, end: LocalTime): Boolean {
        val t = now.toLocalTime()
        return if (start <= end) t >= start && t < end else t >= start || t < end
    }

    /** Next occurrence of the quiet-hours end time, in local time. */
    fun quietEndAfter(now: ZonedDateTime, end: LocalTime): ZonedDateTime {
        val candidate = now.at(end)
        return if (candidate <= now) candidate.plusDays(1) else candidate
    }

    /** A daily job fires at/after its time, but not if we're far past it (e.g. the phone was off). */
    fun jobIsDue(now: ZonedDateTime, scheduled: LocalTime, windowHours: Double = 3.0): Boolean {
        val start = now.at(scheduled)
        return start <= now && now < start.plusSeconds((windowHours * 3600).toLong())
    }

    /** When to check in next after a reminder was just sent (nag_count already incremented). Null = stop. */
    fun nextReminderAfterNag(now: Instant, nagCount: Int, priority: Int, intervalMin: Int, maxNags: Int): Instant? {
        if (nagCount >= maxNags) return null
        val interval = when {
            priority == 2 -> maxOf(10, intervalMin * 2 / 3)
            priority >= 3 -> maxOf(5, intervalMin / 2)
            else -> intervalMin
        }
        return now.plus(Duration.ofMinutes(interval.toLong()))
    }

    // ================================================================ how loud

    const val NORMAL = 0
    const val URGENT = 1
    const val ALARM = 2
    const val NEAR_DEADLINE_H = 48L

    private val ALARM_MIN_PRIORITY = mapOf("critical" to 3, "important" to 2, "all" to 1)

    data class AlertPolicy(
        val urgentAfter: Int = 1,
        /** critical | important | all | off (by task priority) */
        val alarmFor: String = "important",
        val alarmAfter: Int = 2,
        val maxNags: Int = 4,
    )

    /** May a contact about this task be louder than normal, and come more than once a day? */
    fun escalates(task: TaskRow, now: Instant): Boolean {
        if (task.kind == "meeting" || task.kind == "fence" || task.remindRequested) return true
        val due = task.due ?: return false
        val window = Duration.ofHours(NEAR_DEADLINE_H)
        return now.minus(window) < due && due < now.plus(window)
    }

    /** How loud a contact about one task should be. ignored: check-ins about it in a row with no reply. */
    fun alertLevel(task: TaskRow, ignored: Int, p: AlertPolicy, now: Instant): Int {
        if (task.kind == "meeting" || task.kind == "fence") return URGENT
        if (!escalates(task, now)) return NORMAL
        val minPrio = ALARM_MIN_PRIORITY[p.alarmFor]
        if (minPrio != null && task.priority >= minPrio && ignored >= minOf(p.alarmAfter, maxOf(p.maxNags - 1, 0))) return ALARM
        if (task.remindRequested || task.priority >= 2 || ignored >= p.urgentAfter) return URGENT
        return NORMAL
    }

    /** Next occurrence of a wall-clock time, strictly after now. */
    fun nextTimeAt(now: ZonedDateTime, t: LocalTime): ZonedDateTime {
        val candidate = now.at(t)
        return if (candidate <= now) candidate.plusDays(1) else candidate
    }

    /** Seconds an alarm may keep ringing: up to maxS, but never into quiet hours or Shabbat. */
    fun alarmWindowS(now: ZonedDateTime, quietStart: LocalTime, nextRestStart: Instant?, maxS: Long = 3600): Long {
        var end = now.toInstant().plusSeconds(maxS)
        end = minOf(end, nextTimeAt(now, quietStart).toInstant())
        if (nextRestStart != null && nextRestStart > now.toInstant()) end = minOf(end, nextRestStart)
        return maxOf(0, Duration.between(now.toInstant(), end).seconds)
    }

    /** Next due time for a recurring task, keeping the same local wall-clock time. */
    fun nextOccurrence(current: Instant, recurrence: String, zone: ZoneId): Instant {
        val local = current.atZone(zone)
        val d = when (recurrence) {
            "daily" -> local.toLocalDate().plusDays(1)
            "weekdays" -> { // Israeli work week: Sunday–Thursday
                var n = local.toLocalDate().plusDays(1)
                while (n.dayOfWeek == DayOfWeek.FRIDAY || n.dayOfWeek == DayOfWeek.SATURDAY) n = n.plusDays(1)
                n
            }
            "weekly" -> local.toLocalDate().plusDays(7)
            "monthly" -> local.toLocalDate().plusMonths(1)
            else -> throw IllegalArgumentException("Unknown recurrence: $recurrence")
        }
        return ZonedDateTime.of(d, local.toLocalTime(), zone).toInstant()
    }

    /** (start, meeting) for every meeting starting in [start, end), soonest first; recurring ones expanded. */
    fun meetingOccurrences(meetings: Iterable<TaskRow>, start: Instant, end: Instant, zone: ZoneId): List<Pair<Instant, TaskRow>> {
        val out = mutableListOf<Pair<Instant, TaskRow>>()
        for (m in meetings) {
            var whenAt = m.due ?: continue
            val recurrence = m.recurrence
            if (recurrence.isNullOrEmpty()) {
                if (whenAt >= start && whenAt < end) out += whenAt to m
                continue
            }
            for (i in 0 until 400) { // hard stop; a daily meeting over a week needs ~7
                if (whenAt >= end) break
                if (whenAt >= start) out += whenAt to m
                whenAt = nextOccurrence(whenAt, recurrence, zone)
            }
        }
        return out.sortedWith(compareBy({ it.first }, { it.second.id }))
    }

    /** An ISO string from the LLM / UI. Naive values are local time. */
    fun parseLocalDateTime(value: String?, zone: ZoneId): Instant? {
        val v = value?.trim()?.replace("Z", "+00:00") ?: return null
        if (v.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(v).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(v).atZone(zone).toInstant() }.getOrNull()
            ?: LocalDate.parse(v).atStartOfDay(zone).toInstant()
    }

    // ================================================================ conversation day

    /** Start of the current chat "day" (it begins at the day-reset time). */
    fun dayStart(now: ZonedDateTime, reset: LocalTime): ZonedDateTime {
        val start = now.at(reset)
        return if (start > now) start.minusDays(1) else start
    }

    // ================================================================ proactive-contact guardrails

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
        val fixed = task.kind == "meeting" || task.kind == "fence"
        if (!fixed && task.kind != "session") state.focusHoldUntil?.let { if (it > nowI) later += Hold("focus session", it) }
        if (!fixed) {
            if (state.lastProactiveAt != null && task.kind != "session") {
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
        fun fixed(t: TaskRow) = t.kind == "meeting" || t.kind == "fence"
        send.sortWith(compareBy<TaskRow>({ !fixed(it) }, { if (fixed(it)) it.due ?: far else far }, { -it.priority }, { it.remind ?: far }))
        return ContactPlan(send, defer)
    }

    /** Keep an LLM-chosen follow-up within [now + min, now + max days]. */
    fun clampFollowup(now: Instant, requested: Instant, g: Guardrails): Instant {
        val lo = now.plus(Duration.ofMinutes(g.followupMinMin.toLong()))
        val hi = now.plus(Duration.ofDays(g.followupMaxDays.toLong()))
        return minOf(maxOf(requested, lo), hi)
    }

    // ================================================================ replies → tasks

    private val WORD = Regex("""[\p{L}\p{N}_]{3,}""")

    private fun words(text: String?): Set<String> = WORD.findAll(text ?: "").map { it.value.lowercase() }.toSet()

    /** Up to [limit] task ids a reply is probably about, best first (only a hint for the LLM). */
    fun selectCandidateTasks(
        reply: String, now: Instant, lastProactiveAt: Instant?, lastProactiveTaskIds: List<Long>, recentTaskIds: List<Long>,
        open: List<TaskRow>, linkHours: Double, limit: Int = 3,
    ): List<Long> {
        val out = mutableListOf<Long>()
        fun add(ids: Iterable<Long>) = ids.forEach { if (it !in out) out += it }
        if (lastProactiveAt != null && Duration.between(lastProactiveAt, now).seconds <= linkHours * 3600) add(lastProactiveTaskIds)
        add(recentTaskIds)
        val w = words(reply)
        if (w.isNotEmpty()) add(open.filter { (w intersect words(it.title)).isNotEmpty() }.map { it.id })
        return out.take(limit)
    }

    /** Which tasks a user turn is linked to: what the agent acted on, else an unambiguous check-in's task. */
    fun linkTaskIds(actedOn: List<Long>, checkinCandidates: List<Long>): List<Long> = when {
        actedOn.isNotEmpty() -> actedOn.distinct()
        checkinCandidates.size == 1 -> checkinCandidates
        else -> emptyList()
    }

    // ================================================================ text

    /** At most [limit] characters, cut at the last space or line break when that keeps 2/3, ending with "…". */
    fun clip(text: String, limit: Int): String {
        if (text.length <= limit) return text
        var cut = text.substring(0, limit - 1)
        if (!text[limit - 1].isWhitespace()) {
            val space = maxOf(cut.lastIndexOf(' '), cut.lastIndexOf('\n'))
            if (space >= limit * 2 / 3) cut = cut.substring(0, space)
        }
        return cut.trimEnd() + "…"
    }

    // ================================================================ ADHD assistant (Phase 1)

    const val FOCUS_MAX = 3
    val LATER_CUTOFF: LocalTime = LocalTime.of(18, 0)
    const val LATER_HOURS = 2L
    const val AVOIDED_MIN_EVENTS = 3
    const val ACTIVE_SKIPS_CHECKIN_MIN = 60L
    const val START_MIN_NOTIFICATION = 5
    const val START_MIN_APP = 25
    val OVERWHELMED_FROM: LocalTime = LocalTime.of(15, 0)

    data class Chip(val id: String, val action: String? = null, val minutes: Int? = null)

    val CHIP_LABELS: Map<String, Pair<String, String>> = linkedMapOf(
        "start5" to ("▶ Start 5 min" to "▶ להתחיל 5 דק׳"),
        "done" to ("✓ Done" to "✓ בוצע"),
        "later" to ("Later" to "אחר כך"),
        "stuck" to ("I'm stuck" to "נתקעתי"),
        "start_first" to ("▶ Start the first one" to "▶ להתחיל בראשונה"),
        "change" to ("Change it" to "לשנות"),
        "another" to ("▶ Another round" to "▶ עוד סבב"),
        "stop" to ("Stop" to "לעצור"),
        "too_big" to ("Too big" to "גדול מדי"),
        "unclear" to ("Unclear" to "לא ברור"),
        "boring" to ("Boring" to "משעמם"),
        "not_now" to ("Not now" to "לא עכשיו"),
        "pick_one" to ("Pick one for me" to "תבחר לי משהו"),
        "move_overdue" to ("Move overdue to next week" to "להעביר את מה שבאיחור לשבוע הבא"),
        "drop" to ("Drop them" to "לוותר עליהן"),
        "keep" to ("Keep" to "להשאיר"),
        "start_next" to ("▶ Start next" to "▶ להתחיל את הבאה"),
        "what_now" to ("What now?" to "מה עכשיו?"),
        "start" to ("▶ Start" to "▶ להתחיל"),
        "low_energy" to ("Low energy" to "אין לי כוח"),
        "something_else" to ("Something else" to "משהו אחר"),
    )

    private val START5 = Chip("start5", "start", START_MIN_NOTIFICATION)
    private val DONE = Chip("done", "done")
    private val WHAT_NOW = Chip("what_now", "what_now")

    val CHIPS_BY_KIND: Map<String, List<Chip>> = mapOf(
        "checkin" to listOf(START5, DONE, Chip("later", "later"), Chip("stuck")),
        "morning" to listOf(Chip("start_first", "start", START_MIN_NOTIFICATION), Chip("change")),
        "session_end" to listOf(DONE, Chip("another", "another"), Chip("stop", "stop")),
        "avoided" to listOf(Chip("too_big"), Chip("unclear"), Chip("boring"), Chip("not_now", "not_now")),
        "reentry" to listOf(Chip("pick_one"), Chip("move_overdue", "move_overdue")),
        "sunday" to listOf(Chip("drop", "drop"), Chip("keep", "keep")),
        "meeting" to emptyList(),
        "midday" to listOf(Chip("start_next", "start", START_MIN_NOTIFICATION), WHAT_NOW),
        "what_now" to listOf(Chip("start", "start", START_MIN_NOTIFICATION), Chip("low_energy", "what_now"),
            Chip("something_else", "what_now")),
        "chat_task" to listOf(START5, DONE),
        "evening" to emptyList(),
    )

    private val NEEDS_TASK = setOf("start", "done", "later", "another", "stop", "not_now")
    private val NEEDS_TASKS = setOf("drop", "keep")
    private val HEBREW = Regex("[֐-׿]")

    /** 'he' when the user's latest chat message (or, failing that, e.g. the task title) has Hebrew letters. */
    fun chipLang(latestUserText: String?, fallbackText: String? = null): String {
        val text = if (!latestUserText.isNullOrBlank()) latestUserText else fallbackText
        return if (text != null && HEBREW.containsMatchIn(text)) "he" else "en"
    }

    fun chipLabel(chipId: String, lang: String): String = CHIP_LABELS.getValue(chipId).let { if (lang == "he") it.second else it.first }

    /** The reply buttons for a message, from its kind and its task's stage. A done task gets none. */
    fun chipsFor(kind: String, stage: String?): List<Chip> =
        if (stage == "done" || stage == "cancelled") emptyList() else CHIPS_BY_KIND[kind].orEmpty()

    /** One chip as stored on a message. */
    data class ChipJson(
        val id: String, val label: String, val action: String? = null, val minutes: Int? = null,
        val taskId: Long? = null, val taskIds: List<Long>? = null,
    )

    /** Chips as stored: action chips bound to their task (left out without one); Drop them / Keep to [taskIds]. */
    fun chipsJson(chips: Iterable<Chip>, lang: String, taskId: Long?, taskIds: List<Long> = emptyList()): List<ChipJson> =
        chips.filter { (taskId != null || it.action !in NEEDS_TASK) && (taskIds.isNotEmpty() || it.action !in NEEDS_TASKS) }
            .map { c ->
                if (c.action == null) ChipJson(c.id, chipLabel(c.id, lang))
                else ChipJson(c.id, chipLabel(c.id, lang), c.action, c.minutes?.takeIf { it > 0 },
                    taskId.takeIf { c.action in NEEDS_TASK }, taskIds.takeIf { c.action in NEEDS_TASKS })
            }

    /** Which text chip a user message is (a tap sends its label), in either language. */
    fun chipIdForText(text: String?): String? {
        val t = (text ?: "").trim()
        return CHIP_LABELS.entries.firstOrNull { (_, l) -> t == l.first || t == l.second }?.key
    }

    /** 'Later': +2 hours before 18:00, otherwise the next morning plan time. */
    fun laterTime(now: ZonedDateTime, morning: LocalTime): ZonedDateTime =
        if (now.toLocalTime() < LATER_CUTOFF) now.plusHours(LATER_HOURS) else nextTimeAt(now, morning)

    // ---------------------------------------------------------------- stages

    /** The day Today's 3 belongs to: the chat day. */
    fun focusDay(now: ZonedDateTime, reset: LocalTime): LocalDate = dayStart(now, reset).toLocalDate()

    /** captured → clarified → planned → done; avoided after 3+ postponed / ignored events in 7 days. */
    fun taskStage(task: TaskRow, counts7d: Map<String, Int>?, today: LocalDate): String {
        if (task.status == "done" || task.status == "cancelled") return task.status
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
        if (sinceLastReply.any { (kind, content) -> kind == "event" && content.startsWith("[Marked done") }) out += "completed"
        return out
    }

    // ---------------------------------------------------------------- Today's 3 and who gets check-ins

    private fun isOpenTopTask(t: TaskRow) = t.status == "open" && t.kind == "task" && t.parentId == null

    /** The code's pick for Today's 3: overdue, then due today, then by priority, then oldest. */
    fun pickFocus(tasks: Iterable<TaskRow>, now: ZonedDateTime, limit: Int = FOCUS_MAX): List<Long> {
        val nowI = now.toInstant()
        val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()
        val far = Instant.MAX
        fun bucket(t: TaskRow): Int {
            val due = t.due
            return when {
                due != null && due < nowI -> 0
                due != null && due < endOfDay -> 1
                else -> 2
            }
        }
        return tasks.filter { isOpenTopTask(it) && !(it.blocked?.let { b -> b > nowI } ?: false) && !isParked(it, nowI) }
            .sortedWith(compareBy<TaskRow>({ bucket(it) }, { -it.priority }, { it.due ?: far }, { it.created ?: far }, { it.id }))
            .take(limit).map { it.id }
    }

    /** Not now: parked tasks get no check-ins and aren't offered until the park ends. */
    fun isParked(task: TaskRow, now: Instant): Boolean = task.parked?.let { it > now } ?: false

    /** Check-ins during the day only for meetings, fences, asked-for reminders, Today's 3, near deadlines, avoided asks. */
    fun mayCheckIn(task: TaskRow, now: Instant, today: LocalDate, lapsed: Boolean = false): Boolean {
        if (task.kind == "meeting" || task.kind == "fence") return true
        if (isParked(task, now)) return false
        if (task.remindRequested) return true
        if (lapsed) return false
        if (task.focusDay == today || task.avoidAsks > 0) return true
        val due = task.due ?: return false
        return due < now.plus(Duration.ofHours(NEAR_DEADLINE_H))
    }

    fun userActiveRecently(lastActivity: Instant?, now: Instant, minutes: Long = ACTIVE_SKIPS_CHECKIN_MIN): Boolean =
        lastActivity != null && Duration.between(lastActivity, now) < Duration.ofMinutes(minutes)

    // ================================================================ Phase 2: start and come back

    val SESSION_CHOICES = listOf(5, 15, 25, 45)
    const val SESSION_MAX_MIN = 120
    const val SESSION_SILENCE_MIN = 15L
    const val FOCUS_HOLD_MIN = 10L
    const val BREADCRUMB_HOURS = 3L
    const val DEFAULT_MULTIPLIER = 1.5
    const val DEFAULT_ESTIMATE_MIN = 15
    const val LOW_ENERGY_MAX_MIN = 15
    val WHAT_NOW_CHIPS = listOf("what_now", "low_energy", "something_else")

    /** A session length: a multiple of 5, 5 to SESSION_MAX_MIN. */
    fun sessionLength(minutes: Int?, default: Int = START_MIN_NOTIFICATION): Int {
        val m = if (minutes == null || minutes == 0) default else minutes
        val rounded = Math.floorDiv(-m, 5).let { -it } * 5
        return minOf(maxOf(rounded, 5), SESSION_MAX_MIN)
    }

    fun sessionEndAt(s: SessionRow): Instant = s.started.plus(Duration.ofMinutes(s.plannedMin.toLong()))

    /** running → ended (time's up) → closed (an outcome is recorded). */
    fun sessionState(s: SessionRow?, now: Instant): String? = when {
        s == null -> null
        s.endedAt != null -> "closed"
        now < sessionEndAt(s) -> "running"
        else -> "ended"
    }

    /** Nobody answered SESSION_SILENCE_MIN after its end check-in (or its end, if none went out). */
    fun sessionSilent(s: SessionRow, now: Instant): Boolean {
        if (s.endedAt != null) return false
        val since = s.endSent ?: sessionEndAt(s)
        return now >= since.plus(Duration.ofMinutes(SESSION_SILENCE_MIN))
    }

    fun sessionEndDue(s: SessionRow, now: Instant): Boolean =
        sessionState(s, now) == "ended" && s.endSentAt == null && !sessionSilent(s, now)

    /** (outcome, ended at) for an open session being replaced. */
    fun sessionCloseOutcome(s: SessionRow, now: Instant): Pair<String, Instant> {
        val end = sessionEndAt(s)
        return if (now < end) "stopped" to now else "paused" to end
    }

    fun actualMinutes(s: SessionRow, now: Instant): Int {
        val end = s.ended ?: now
        return maxOf(0, Math.round(Duration.between(s.started, end).seconds / 60.0).toInt())
    }

    /** While a session runs, other check-ins wait until it ends + FOCUS_HOLD_MIN. latest: the most recent session. */
    fun focusHoldUntil(latest: SessionRow?): Instant? {
        latest ?: return null
        return (latest.ended ?: sessionEndAt(latest)).plus(Duration.ofMinutes(FOCUS_HOLD_MIN))
    }

    fun sessionLastActive(latest: SessionRow?, now: Instant): Instant? {
        latest ?: return null
        return latest.ended ?: minOf(now, sessionEndAt(latest))
    }

    fun wantsBreadcrumb(latest: SessionRow?, now: Instant): Boolean {
        latest ?: return false
        val ended = latest.ended ?: return false
        return latest.outcome in setOf("stopped", "paused") && latest.noteClosedAt == null &&
            Duration.between(ended, now) < Duration.ofHours(BREADCRUMB_HOURS)
    }

    // ---------------------------------------------------------------- What now?

    /** reason: fits | short_start | small | bonus | all_done (task null). */
    data class Pick(val task: TaskRow?, val minutes: Int, val reason: String, val estimate: Int? = null)

    private fun est(t: TaskRow, multiplier: Double): Int = ceil((t.estimateMin ?: DEFAULT_ESTIMATE_MIN) * multiplier).toInt()

    private fun sessionFor(est: Int, available: Int?): Int {
        var m = SESSION_CHOICES.firstOrNull { it >= est } ?: SESSION_CHOICES.last()
        if (available != null) {
            val fitting = SESSION_CHOICES.filter { it <= available }
            m = if (fitting.isNotEmpty()) minOf(m, fitting.last()) else SESSION_CHOICES.first()
        }
        return m
    }

    /** What now? decided by code in one step. Null = nothing is open at all. */
    fun pickNext(
        tasks: Iterable<TaskRow>, now: Instant, nextFixedEvent: Instant?, multiplier: Double = DEFAULT_MULTIPLIER,
        lowEnergy: Boolean = false, today: LocalDate, doneIds: Set<Long> = emptySet(), exclude: Collection<Long> = emptyList(),
    ): Pick? {
        val far = Instant.MAX
        val rows = tasks.filter {
            isOpenTopTask(it) && it.id !in doneIds && !(it.blocked?.let { b -> b > now } ?: false) && !isParked(it, now)
        }
        val focus = rows.filter { it.focusDay == today }.sortedWith(compareBy({ it.focusRank ?: 99 }, { it.id }))
        val others = rows.filter { it.focusDay != today }
            .sortedWith(compareBy<TaskRow>({ it.due ?: far }, { -it.priority }, { it.created ?: far }, { it.id }))
        val order = focus + others
        if (order.isEmpty()) return if (doneIds.isNotEmpty()) Pick(null, 0, "all_done") else null
        val skip = exclude.toSet()
        val cands = order.filter { it.id !in skip }.ifEmpty { order }
        val focusIds = focus.map { it.id }.toSet()
        val bonus = focus.isEmpty() && doneIds.isNotEmpty()
        val available = nextFixedEvent?.let { maxOf(0, (Duration.between(now, it).seconds / 60).toInt()) }

        fun fits(t: TaskRow) = available == null || est(t, multiplier) <= available
        fun chosen(t: TaskRow, reason: String): Pick {
            val e = est(t, multiplier)
            return Pick(t, sessionFor(e, available), reason, e)
        }

        val first = cands.first()
        if (lowEnergy) {
            fun raw(t: TaskRow) = t.estimateMin ?: DEFAULT_ESTIMATE_MIN
            val small = cands.filter { raw(it) <= LOW_ENERGY_MAX_MIN && fits(it) }
            if (small.isNotEmpty()) return chosen(small.minWith(compareBy({ raw(it) }, { it.estimateMin == null })), "small")
            return Pick(first, SESSION_CHOICES.first(), "short_start", est(first, multiplier))
        }
        if (fits(first)) return chosen(first, if (bonus) "bonus" else "fits")
        val group = cands.filter { (it.id in focusIds) == (first.id in focusIds) && fits(it) }
        if (group.isNotEmpty()) return chosen(group.minBy { est(it, multiplier) }, if (bonus) "bonus" else "fits")
        return Pick(first, SESSION_CHOICES.first(), "short_start", est(first, multiplier))
    }

    // ---------------------------------------------------------------- template texts (no LLM)

    private val TEXTS: Map<String, Pair<String, String>> = mapOf(
        "fits" to ("Do this: {step} ({min} min)." to "עכשיו: {step} ({min} דק׳)."),
        "small" to ("Something small: {step} ({min} min)." to "משהו קטן: {step} ({min} דק׳)."),
        "short_start_before" to ("Before {at}: just 5 minutes of this: {step}." to "עד {at}: רק 5 דקות של זה: {step}."),
        "short_start" to ("Just 5 minutes of this: {step}." to "רק 5 דקות של זה: {step}."),
        "bonus" to ("All of Today's 3 are done. Bonus, only if you feel like it: {step} ({min} min)." to
            "כל שלוש המשימות של היום בוצעו. בונוס, רק אם בא לך: {step} ({min} דק׳)."),
        "all_done" to ("All of Today's 3 are done. Nothing else is open." to "כל שלוש המשימות של היום בוצעו. אין עוד משהו פתוח."),
        "nothing" to ("Nothing open right now. Tell me what's on your mind and I'll save it." to
            "אין כרגע משהו פתוח. ספר לי מה על הראש ואשמור את זה."),
        "session_end" to ("⏱ Time's up: {step}. Done, another round, or stop?" to "⏱ הזמן נגמר: {step}. בוצע, עוד סבב, או לעצור?"),
        "avoid_ask" to ("{title} keeps slipping, and that's fine. What's in the way?" to "{title} נדחית שוב ושוב, וזה בסדר. מה מפריע?"),
        "reentry" to ("Good to see you. Want me to pick one thing for today?" to "טוב לראות אותך. שאבחר לך דבר אחד להיום?"),
        "fence" to ("⛔ {at} — {title}. Time to stop." to "⛔ {at} — {title}. הגיע הזמן לעצור."),
        "wins_week" to ("✅ Last week you finished {n} things." to "✅ בשבוע שעבר סיימת {n} דברים."),
        "wins_week_1" to ("✅ Last week you finished one thing." to "✅ בשבוע שעבר סיימת דבר אחד."),
        "stale" to ("🧹 Open for 3+ weeks with no progress: {titles}. Drop them or keep?" to
            "🧹 פתוחות יותר מ-3 שבועות בלי התקדמות: {titles}. לוותר עליהן או להשאיר?"),
    )

    /** A fixed text in the user's language, with {name} values filled in. */
    fun text(key: String, lang: String, vararg values: Pair<String, Any>): String {
        var out = TEXTS.getValue(key).let { if (lang == "he") it.second else it.first }
        for ((k, v) in values) out = out.replace("{$k}", v.toString())
        return out
    }

    /** The task as one thing to do: its next action, with the title when they differ. */
    fun stepOf(task: TaskRow): String {
        val step = task.nextAction?.takeIf { it.isNotEmpty() } ?: task.title
        return if (step == task.title) step else "$step — ${task.title}"
    }

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** What now?'s reply. nextFixedLocal: the next meeting's local start (names the deadline of a short start). */
    fun whatNowText(pick: Pick?, lang: String, nextFixedLocal: ZonedDateTime? = null): String {
        pick ?: return text("nothing", lang)
        val task = pick.task ?: return text(pick.reason, lang)
        val step = "step" to stepOf(task)
        val min = "min" to pick.minutes
        if (pick.reason == "short_start" && nextFixedLocal != null) return text("short_start_before", lang, "at" to nextFixedLocal.format(HHMM), step, min)
        return text(pick.reason, lang, step, min)
    }

    fun sessionText(kind: String, task: TaskRow, minutes: Int, lang: String): String =
        text(kind, lang, "step" to (task.nextAction?.takeIf { it.isNotEmpty() } ?: task.title), "min" to minutes)

    // ================================================================ Phase 3: gentler pressure

    const val AVOID_RETRY_DAYS = 3L
    const val AVOID_MAX_ASKS = 2
    const val PARK_DAYS = 7L
    const val LAPSE_HOURS = 48.0
    const val REENTRY_REPEAT_DAYS = 7L
    const val STALE_DAYS = 21L
    const val STALE_COOLDOWN_DAYS = 28L
    const val STALE_MAX = 3
    const val WEEK_WINS_DAYS = 7L
    const val SUNDAY_MERGE_MIN = 60
    const val FENCE_STALE_MIN = 30L

    /** Instead of an ordinary check-in: null (not avoided) | ask | wait | park. */
    fun avoidMove(task: TaskRow, counts7d: Map<String, Int>?, now: Instant): String? {
        if (task.kind != "task") return null
        val counts = counts7d.orEmpty()
        if ((counts["postponed"] ?: 0) + (counts["ignored"] ?: 0) < AVOIDED_MIN_EVENTS) return null
        val due = task.due
        if (due != null && now < due && due <= now.plus(Duration.ofHours(NEAR_DEADLINE_H))) return null
        val asks = task.avoidAsks
        val asked = task.avoidAsked
        if (asks == 0 || asked == null) return "ask"
        if (now < asked.plus(Duration.ofDays(AVOID_RETRY_DAYS))) return "wait"
        return if (asks < AVOID_MAX_ASKS) "ask" else "park"
    }

    /** No chat message and no tap for 48 hours, not counting Shabbat and holidays (restPeriods). */
    fun lapsed(lastActivity: Instant?, now: Instant, restPeriods: Iterable<Pair<Instant, Instant>> = emptyList(), hours: Double = LAPSE_HOURS): Boolean {
        lastActivity ?: return false
        var away = Duration.between(lastActivity, now)
        for ((start, end) in restPeriods) {
            val overlap = Duration.between(maxOf(start, lastActivity), minOf(end, now))
            if (!overlap.isNegative && !overlap.isZero) away = away.minus(overlap)
        }
        return away >= Duration.ofSeconds((hours * 3600).toLong())
    }

    /** During a lapse: send the re-entry message? Once per lapse, then once a week. */
    fun reentryDue(lastActivity: Instant?, lastReentry: Instant?, now: Instant): Boolean {
        if (lastReentry == null || (lastActivity != null && lastReentry < lastActivity)) return true
        return Duration.between(lastReentry, now) >= Duration.ofDays(REENTRY_REPEAT_DAYS)
    }

    fun weekMeetingsMerged(morning: LocalTime, weekMeetings: LocalTime?): Boolean {
        weekMeetings ?: return false
        return abs((weekMeetings.hour * 60 + weekMeetings.minute) - (morning.hour * 60 + morning.minute)) <= SUNDAY_MERGE_MIN
    }

    fun fenceIsStale(task: TaskRow, now: Instant): Boolean =
        task.due?.let { Duration.between(it, now) > Duration.ofMinutes(FENCE_STALE_MIN) } ?: false

    /** Move overdue to next week: the same weekday and time, the first one still ahead. */
    fun movedToNextWeek(due: ZonedDateTime, now: Instant): ZonedDateTime {
        var new = due.plusDays(7)
        while (new.toInstant() <= now) new = new.plusDays(7)
        return new
    }
}
