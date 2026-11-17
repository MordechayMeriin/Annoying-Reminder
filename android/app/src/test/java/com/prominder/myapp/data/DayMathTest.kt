package com.prominder.myapp.data

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from backend/tests/test_logic.py and test_logic_adhd.py: the same cases must give the same answers. */
class DayMathTest {
    private val tz = ZoneId.of("Asia/Jerusalem")
    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, tz)
    private fun at(h: Int, mi: Int = 0, d: Int = 9) = local(2026, 11, d, h, mi)

    @Test
    fun recurrence() {
        val thu = local(2026, 11, 12, 9).toInstant()
        assertEquals(local(2026, 11, 15, 9).toInstant(), DayMath.nextOccurrence(thu, "weekdays", tz)) // → Sunday
        assertEquals(local(2026, 11, 13, 9).toInstant(), DayMath.nextOccurrence(thu, "daily", tz))
        assertEquals(local(2026, 11, 19, 9).toInstant(), DayMath.nextOccurrence(thu, "weekly", tz))
        assertEquals(local(2026, 2, 28, 8).toInstant(), DayMath.nextOccurrence(local(2026, 1, 31, 8).toInstant(), "monthly", tz))
    }

    @Test
    fun `recurrence keeps the wall clock across DST`() {
        // Israel's DST ends on the last Sunday of October (2026-10-25)
        val after = DayMath.nextOccurrence(local(2026, 10, 24, 9).toInstant(), "daily", tz).atZone(tz)
        assertEquals(9, after.hour)
        assertEquals(2 * 3600, after.offset.totalSeconds)
    }

    @Test
    fun `an overdue recurring task catches up to the future`() {
        val due = local(2026, 11, 2, 9).toInstant()
        val now = local(2026, 11, 9, 10).toInstant()
        assertEquals(local(2026, 11, 10, 9).toInstant(), DayMath.nextFutureOccurrence(due, "daily", tz, now))
    }

    @Test
    fun `later is two hours before six pm, else the next morning`() {
        val morning = LocalTime.of(7, 30)
        assertEquals(at(12), DayMath.laterTime(at(10), morning))
        assertEquals(at(19, 59), DayMath.laterTime(at(17, 59), morning))
        assertEquals(at(7, 30, d = 10), DayMath.laterTime(at(18), morning))
        assertEquals(at(7, 30, d = 10), DayMath.laterTime(at(23, 30), morning))
    }

    @Test
    fun `focus day follows the day reset`() {
        assertEquals(LocalDate.of(2026, 11, 8), DayMath.focusDay(at(3, 59), LocalTime.of(4, 0)))
        assertEquals(LocalDate.of(2026, 11, 9), DayMath.focusDay(at(4), LocalTime.of(4, 0)))
    }

    @Test
    fun `minutes read like the server's`() {
        assertEquals("1 day", DayMath.formatMinutes(1440))
        assertEquals("2 days", DayMath.formatMinutes(2880))
        assertEquals("3h", DayMath.formatMinutes(180))
        assertEquals("45 min", DayMath.formatMinutes(45))
    }

    @Test
    fun `times are stored like the server stores them`() {
        val t = local(2026, 11, 16, 10, 30).toInstant()
        assertEquals("2026-11-16T08:30:00.000000Z", Times.format(t))
        assertEquals(t, Times.parse("2026-11-16T08:30:00.000000Z"))
        assertEquals("2026-11-17T17:14:48.606595Z", Times.format(Times.parse("2026-11-17T17:14:48.606595Z")))
    }
}
