package com.prominder.myapp.logic

import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.logic.Coaching.AVOIDED_MIN_EVENTS
import com.prominder.myapp.logic.Loudness.NEAR_DEADLINE_H
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.abs

/** Gentler pressure (Phase 3): avoided tasks, the lapse and re-entry, the Sunday merge, stale hard stops. Pure: no I/O. */
object Pressure {
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
        if (task.kind != TaskKind.TASK) return null
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
