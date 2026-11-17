package com.prominder.myapp.logic

import com.prominder.myapp.logic.Logic.ContactState
import com.prominder.myapp.logic.Logic.Guardrails
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * backend/tests/test_shabbat.py (Rosh HaAyin, candles 20 min, havdalah at nightfall, ±60 min), plus a table of
 * the server's own answers (hdate), so KosherJava gives the same minute for every period in a year.
 */
class RestCalendarTest {
    private val CFG = RestConfig(TZ, 32.0956, 34.9566, 60.0)
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = local(y, mo, d, h, mi)
    private fun current(z: java.time.ZonedDateTime) = RestCalendar.currentPeriod(z.i, CFG)

    @Test fun regularShabbatWithMargins() {
        val p = current(at(2026, 11, 14, 12))!!
        // candle lighting Fri 16:23 - 60 min, havdalah Sat 17:20 + 60 min
        assertEquals(Triple(at(2026, 11, 13, 15, 23).i, at(2026, 11, 14, 18, 20).i, "שבת"), Triple(p.start, p.end, p.name))
    }

    @Test fun boundaries() {
        for ((whenAt, quiet) in listOf(
            at(2026, 11, 13, 15, 22) to false, at(2026, 11, 13, 15, 23) to true, at(2026, 11, 14, 18, 19) to true,
            at(2026, 11, 14, 18, 20) to false, at(2026, 11, 11, 12) to false,
        )) assertEquals("$whenAt", quiet, current(whenAt) != null)
    }

    @Test fun roshHashanaAfterShabbatIsOnePeriod() {
        val p = current(at(2026, 9, 13, 12))!!
        assertEquals(LocalDate.of(2026, 9, 11), p.start.atZone(TZ).toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 13), p.end.atZone(TZ).toLocalDate())
        assertEquals(p, current(at(2026, 9, 12, 21)))
        assertTrue("ראש השנה" in p.name && p.name.startsWith("שבת"))
    }

    @Test fun yomKippurOnAWeekday() {
        val p = current(at(2026, 9, 21, 12))!!
        assertEquals(LocalDate.of(2026, 9, 20), p.start.atZone(TZ).toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 21), p.end.atZone(TZ).toLocalDate())
        assertEquals("יום הכפורים", p.name)
    }

    @Test fun oneDayHolidaysInIsraelTwoOutside() {
        val israel = RestCalendar.periodsBetween(LocalDate.of(2027, 4, 20), LocalDate.of(2027, 4, 22), CFG)
        assertEquals(listOf(LocalDate.of(2027, 4, 22)), israel.map { it.end.atZone(TZ).toLocalDate() })
        val abroad = RestCalendar.periodsBetween(LocalDate.of(2027, 4, 20), LocalDate.of(2027, 4, 22), CFG.copy(diaspora = true))
        assertEquals(listOf(LocalDate.of(2027, 4, 24)), abroad.map { it.end.atZone(TZ).toLocalDate() })
    }

    @Test fun cholHamoedIsNotQuiet() = assertNull(current(at(2027, 4, 25, 12)))

    @Test fun nextPeriodFromAWeekday() = assertEquals(at(2026, 11, 13, 15, 23).i, RestCalendar.nextPeriod(at(2026, 11, 9, 12).i, CFG)!!.start)

    @Test fun modeOff() = assertNull(RestCalendar.restUntil(at(2026, 11, 14, 12).i, null))

    @Test fun guardrailHoldsEvenMeetingsUntilAfterShabbat() {
        val g = Guardrails(quietStart = hm(23), quietEnd = hm(7), restUntil = { RestCalendar.restUntil(it.toInstant(), CFG) })
        val meeting = task(1, kind = "meeting", priority = 3)
        assertEquals(at(2026, 11, 14, 18, 20).i, Logic.contactDecision(meeting, ContactState(), at(2026, 11, 14, 10), g))
        assertNull(Logic.contactDecision(meeting, ContactState(), at(2026, 11, 15, 10), g))
    }

    @Test fun summerShabbatEndsLate() {
        // test_daily_job_runs_right_after_shabbat_if_still_in_its_window: Sat 3 Jul 2027 ends 20:32 + 60 → 21:32
        assertEquals(at(2027, 7, 3, 21, 32).i, RestCalendar.restUntil(at(2027, 7, 3, 21, 30).i, CFG))
        assertNull(RestCalendar.restUntil(at(2027, 7, 3, 21, 35).i, CFG))
    }

    // ------------------------------------------------------------ the same minutes as the server (hdate)

    private data class Row(val diaspora: Boolean, val start: String, val end: String, val name: String)

    @Test fun everyPeriodMatchesTheServersCalendar() {
        val misses = mutableListOf<String>()
        for (row in HDATE) {
            val day = LocalDateTime.parse(row.start, FMT).toLocalDate()
            val cfg = CFG.copy(diaspora = row.diaspora)
            val p = RestCalendar.periodsBetween(day, day.plusDays(2), cfg).firstOrNull()
            assertNotNull("no period near ${row.start}", p)
            val got = Row(row.diaspora, FMT.format(p!!.start.atZone(TZ)), FMT.format(p.end.atZone(TZ)), p.name)
            if (got != row) misses += "expected $row, got $got"
        }
        assertTrue(misses.joinToString("\n"), misses.isEmpty())
    }

    /** backend: periods_between(...) for these ranges, printed (see docs/android-phase-c.md). */
    private val HDATE = listOf(
        Row(false, "2026-09-04 17:43", "2026-09-05 20:39", "שבת"),
        Row(false, "2026-09-11 17:34", "2026-09-13 20:28", "שבת · א' ראש השנה · ב' ראש השנה"),
        Row(false, "2026-09-18 17:25", "2026-09-19 20:20", "שבת"),
        Row(false, "2026-09-20 17:22", "2026-09-21 20:17", "יום הכפורים"),
        Row(false, "2026-09-25 17:15", "2026-09-26 20:10", "שבת · סוכות"),
        Row(false, "2026-10-02 17:06", "2026-10-03 20:01", "שבת · שמיני עצרת · שמחת תורה"),
        Row(false, "2026-10-09 16:57", "2026-10-10 19:52", "שבת"),
        Row(false, "2026-10-16 16:48", "2026-10-17 19:44", "שבת"),
        Row(false, "2026-10-23 16:41", "2026-10-24 19:36", "שבת"),
        Row(false, "2026-10-30 15:34", "2026-10-31 18:30", "שבת"),
        Row(false, "2026-11-06 15:28", "2026-11-07 18:24", "שבת"),
        Row(false, "2026-11-13 15:23", "2026-11-14 18:20", "שבת"),
        Row(false, "2026-11-20 15:19", "2026-11-21 18:17", "שבת"),
        Row(false, "2026-11-27 15:16", "2026-11-28 18:15", "שבת"),
        Row(false, "2026-12-04 15:15", "2026-12-05 18:15", "שבת"),
        Row(false, "2026-12-11 15:16", "2026-12-12 18:16", "שבת"),
        Row(false, "2026-12-18 15:17", "2026-12-19 18:18", "שבת"),
        Row(false, "2026-12-25 15:20", "2026-12-26 18:21", "שבת"),
        Row(false, "2027-01-01 15:24", "2027-01-02 18:25", "שבת"),
        Row(false, "2027-01-08 15:29", "2027-01-09 18:30", "שבת"),
        Row(false, "2027-01-15 15:35", "2027-01-16 18:35", "שבת"),
        Row(false, "2027-01-22 15:42", "2027-01-23 18:41", "שבת"),
        Row(false, "2027-01-29 15:48", "2027-01-30 18:47", "שבת"),
        Row(false, "2027-03-05 16:19", "2027-03-06 19:16", "שבת"),
        Row(false, "2027-03-12 16:24", "2027-03-13 19:21", "שבת"),
        Row(false, "2027-03-19 16:29", "2027-03-20 19:26", "שבת"),
        Row(false, "2027-03-26 17:34", "2027-03-27 20:31", "שבת"),
        Row(false, "2027-04-02 17:39", "2027-04-03 20:36", "שבת"),
        Row(false, "2027-04-09 17:43", "2027-04-10 20:41", "שבת"),
        Row(false, "2027-04-16 17:48", "2027-04-17 20:46", "שבת"),
        Row(false, "2027-04-21 17:51", "2027-04-22 20:50", "פסח"),
        Row(false, "2027-04-23 17:53", "2027-04-24 20:51", "שבת"),
        Row(false, "2027-04-27 17:56", "2027-04-28 20:55", "שביעי פסח"),
        Row(false, "2027-04-30 17:58", "2027-05-01 20:57", "שבת"),
        Row(false, "2027-05-07 18:03", "2027-05-08 21:03", "שבת"),
        Row(false, "2027-05-14 18:08", "2027-05-15 21:08", "שבת"),
        Row(false, "2027-05-21 18:12", "2027-05-22 21:14", "שבת"),
        Row(false, "2027-05-28 18:17", "2027-05-29 21:19", "שבת"),
        Row(false, "2027-06-04 18:21", "2027-06-05 21:24", "שבת"),
        Row(false, "2027-06-10 18:24", "2027-06-12 21:27", "שבת · שבועות"),
        Row(false, "2027-06-18 18:27", "2027-06-19 21:30", "שבת"),
        Row(false, "2027-06-25 18:29", "2027-06-26 21:32", "שבת"),
        Row(false, "2027-07-02 18:30", "2027-07-03 21:32", "שבת"),
        Row(false, "2027-07-09 18:29", "2027-07-10 21:31", "שבת"),
        Row(true, "2027-04-16 17:48", "2027-04-17 20:46", "שבת"),
        Row(true, "2027-04-21 17:51", "2027-04-24 20:51", "שבת · פסח · שני של פסח"),
        Row(true, "2027-04-27 17:56", "2027-04-29 20:55", "שביעי פסח · אחרון של פסח"),
        Row(true, "2027-04-30 17:58", "2027-05-01 20:57", "שבת"),
        Row(true, "2027-05-07 18:03", "2027-05-08 21:03", "שבת"),
        Row(true, "2027-05-14 18:08", "2027-05-15 21:08", "שבת"),
        Row(true, "2027-05-21 18:12", "2027-05-22 21:14", "שבת"),
        Row(true, "2027-05-28 18:17", "2027-05-29 21:19", "שבת"),
        Row(true, "2027-06-04 18:21", "2027-06-05 21:24", "שבת"),
        Row(true, "2027-06-10 18:24", "2027-06-12 21:27", "שבת · שבועות · שני של שבועות"),
        Row(true, "2027-09-24 17:17", "2027-09-25 20:11", "שבת"),
        Row(true, "2027-10-01 17:07", "2027-10-03 20:01", "שבת · א' ראש השנה · ב' ראש השנה"),
        Row(true, "2027-10-08 16:58", "2027-10-09 19:53", "שבת"),
        Row(true, "2027-10-10 16:56", "2027-10-11 19:51", "יום הכפורים"),
        Row(true, "2027-10-15 16:50", "2027-10-17 19:44", "שבת · סוכות · שני של סוכות"),
        Row(true, "2027-10-22 16:42", "2027-10-24 19:36", "שבת · שמיני עצרת · שמחת תורה"),
        Row(true, "2027-10-29 16:35", "2027-10-30 19:31", "שבת"),
    )
}
