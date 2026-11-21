package com.prominder.myapp.logic

import com.prominder.myapp.data.OPEN
import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.logic.FocusSessions.SESSION_CHOICES
import com.prominder.myapp.logic.Loudness.NEAR_DEADLINE_H
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.math.ceil

/** Today's 3, who gets check-ins during the day, and What now? (the code's pick of one thing to do). Pure: no I/O. */
object Focus {
    const val FOCUS_MAX = 3
    const val ACTIVE_SKIPS_CHECKIN_MIN = 60L

    private fun isOpenTopTask(t: TaskRow) = t.status == OPEN && t.kind == TaskKind.TASK && t.parentId == null

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
        if (task.kind == TaskKind.MEETING || task.kind == TaskKind.FENCE) return true
        if (isParked(task, now)) return false
        if (task.remindRequested) return true
        if (lapsed) return false
        if (task.focusDay == today || task.avoidAsks > 0) return true
        val due = task.due ?: return false
        return due < now.plus(Duration.ofHours(NEAR_DEADLINE_H))
    }

    fun userActiveRecently(lastActivity: Instant?, now: Instant, minutes: Long = ACTIVE_SKIPS_CHECKIN_MIN): Boolean =
        lastActivity != null && Duration.between(lastActivity, now) < Duration.ofMinutes(minutes)

    const val DEFAULT_MULTIPLIER = 1.5
    const val DEFAULT_ESTIMATE_MIN = 15
    const val LOW_ENERGY_MAX_MIN = 15
    val WHAT_NOW_CHIPS = listOf("what_now", "low_energy", "something_else")

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
}
