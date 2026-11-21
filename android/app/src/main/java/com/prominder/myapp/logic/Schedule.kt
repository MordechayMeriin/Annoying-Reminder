package com.prominder.myapp.logic

import com.prominder.myapp.data.TaskRow
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Times and days: quiet hours, daily jobs, the next check-in after a nag, recurrences, the chat day, "Later". Pure: no I/O. */
object Schedule {
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

    /** Next occurrence of a wall-clock time, strictly after now. */
    fun nextTimeAt(now: ZonedDateTime, t: LocalTime): ZonedDateTime {
        val candidate = now.at(t)
        return if (candidate <= now) candidate.plusDays(1) else candidate
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

    /** 'Later': +2 hours before [LATER_CUTOFF], otherwise the next morning plan time. */
    val LATER_CUTOFF: LocalTime = LocalTime.of(18, 0)
    const val LATER_HOURS = 2L

    fun laterTime(now: ZonedDateTime, morning: LocalTime): ZonedDateTime =
        if (now.toLocalTime() < LATER_CUTOFF) now.plusHours(LATER_HOURS) else nextTimeAt(now, morning)

    /** The day Today's 3 belongs to: the chat day. */
    fun focusDay(now: ZonedDateTime, reset: LocalTime): LocalDate = dayStart(now, reset).toLocalDate()
}
