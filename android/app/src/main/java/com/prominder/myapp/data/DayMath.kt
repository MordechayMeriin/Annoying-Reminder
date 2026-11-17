package com.prominder.myapp.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Date rules ported from backend/app/logic.py, one for one (the tests are ported too: DayMathTest).
 * Phase C ports the rest of logic.py.
 */
object DayMath {
    /** logic.LATER_CUTOFF / LATER_HOURS */
    val LATER_CUTOFF: LocalTime = LocalTime.of(18, 0)
    const val LATER_HOURS = 2L

    /** logic.next_occurrence: the next due time of a recurring task, at the same local wall-clock time. */
    fun nextOccurrence(current: Instant, recurrence: String, zone: ZoneId): Instant {
        val local = current.atZone(zone)
        var d = local.toLocalDate()
        d = when (recurrence) {
            "daily" -> d.plusDays(1)
            // Israeli work week: Sunday–Thursday
            "weekdays" -> {
                var next = d.plusDays(1)
                while (next.dayOfWeek == DayOfWeek.FRIDAY || next.dayOfWeek == DayOfWeek.SATURDAY) next = next.plusDays(1)
                next
            }
            "weekly" -> d.plusDays(7)
            "monthly" -> d.plusMonths(1) // like logic._add_month: Jan 31 → Feb 28
            else -> throw IllegalArgumentException("Unknown recurrence: $recurrence")
        }
        return ZonedDateTime.of(d, local.toLocalTime(), zone).toInstant()
    }

    /** tasks._next_time: the next occurrence that's still in the future (catching up if it was overdue). */
    fun nextFutureOccurrence(due: Instant, recurrence: String, zone: ZoneId, now: Instant): Instant {
        var next = nextOccurrence(due, recurrence, zone)
        while (!next.isAfter(now)) next = nextOccurrence(next, recurrence, zone)
        return next
    }

    /** logic.next_time_at: the next moment the clock shows [at] (today if still ahead, else tomorrow). */
    fun nextTimeAt(now: ZonedDateTime, at: LocalTime): ZonedDateTime {
        val today = now.with(at).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** logic.later_time: "Later" is decided by code: +2 hours before 18:00, otherwise the next morning plan. */
    fun laterTime(now: ZonedDateTime, morning: LocalTime): ZonedDateTime =
        if (now.toLocalTime() < LATER_CUTOFF) now.plusHours(LATER_HOURS) else nextTimeAt(now, morning)

    /** logic.day_start: the start of the current chat day, which begins at the day-reset time. */
    fun dayStart(now: ZonedDateTime, reset: LocalTime): ZonedDateTime {
        val start = now.with(reset).withSecond(0).withNano(0)
        return if (start.isAfter(now)) start.minusDays(1) else start
    }

    /** logic.focus_day: the day Today's 3 belongs to (the chat day, not the calendar day). */
    fun focusDay(now: ZonedDateTime, reset: LocalTime): LocalDate = dayStart(now, reset).toLocalDate()

    /** tasks.day_bounds: the start and end of a local calendar day. */
    fun dayBounds(now: ZonedDateTime, daysAhead: Long = 0): Pair<Instant, Instant> {
        val start = now.toLocalDate().plusDays(daysAhead).atStartOfDay(now.zone)
        return start.toInstant() to start.plusDays(1).toInstant()
    }

    /** actions._fmt_minutes */
    fun formatMinutes(minutes: Long): String = when {
        minutes % (24 * 60) == 0L -> (minutes / (24 * 60)).let { "$it day" + if (it != 1L) "s" else "" }
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "$minutes min"
    }

    fun minutesBetween(a: Instant, b: Instant): Long = Duration.between(a, b).toMinutes()
}
