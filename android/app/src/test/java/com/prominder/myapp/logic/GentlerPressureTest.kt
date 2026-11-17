package com.prominder.myapp.logic

import com.prominder.myapp.logic.Logic.ContactState
import com.prominder.myapp.logic.Logic.Guardrails
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** backend/tests/test_logic_phase3.py, one for one: avoidance, de-escalation, the lapse, fences, Sunday. */
class GentlerPressureTest {
    private fun l(d: Int, h: Int, mi: Int = 0) = local(2026, 11, d, h, mi) // Sun 8 Nov 2026 … Sat 14 Nov 2026
    private val NOW = l(9, 10)
    private val G = Guardrails(quietStart = hm(23), quietEnd = hm(7))
    private val avoided = mapOf("postponed" to 2, "ignored" to 1)

    // ------------------------------------------------------------ avoidance (F7)

    @Test fun avoidMoveAskWaitAskPark() {
        assertNull(Logic.avoidMove(task(), mapOf("postponed" to 1, "ignored" to 1), NOW.i))
        assertEquals("ask", Logic.avoidMove(task(), avoided, NOW.i))
        val asked = task(avoidAsks = 1, avoidAsked = NOW)
        assertEquals("wait", Logic.avoidMove(asked, avoided, NOW.plusDays(2).i))
        assertEquals("ask", Logic.avoidMove(asked, avoided, NOW.plusDays(3).i))
        val twice = task(avoidAsks = 2, avoidAsked = NOW)
        assertEquals("wait", Logic.avoidMove(twice, avoided, NOW.plusDays(1).i))
        assertEquals("park", Logic.avoidMove(twice, avoided, NOW.plusDays(3).i))
    }

    @Test fun avoidMoveLeavesMeetingsFencesAndUpcomingDeadlinesAlone() {
        val five = mapOf("ignored" to 5)
        assertNull(Logic.avoidMove(task(kind = "meeting"), five, NOW.i))
        assertNull(Logic.avoidMove(task(kind = "fence"), five, NOW.i))
        assertNull(Logic.avoidMove(task(due = NOW.plusHours(20)), five, NOW.i))
        assertEquals("ask", Logic.avoidMove(task(due = NOW.minusDays(3)), five, NOW.i))
        assertEquals("ask", Logic.avoidMove(task(due = NOW.plusDays(5)), five, NOW.i))
    }

    @Test fun avoidedChipsAndTextsInBothLanguages() {
        val chips = Logic.chipsJson(Logic.chipsFor("avoided", "avoided"), "en", 7)
        assertEquals(listOf("too_big" to null, "unclear" to null, "boring" to null, "not_now" to "not_now"), chips.map { it.id to it.action })
        assertEquals(7L, chips.last().taskId)
        assertEquals("גדול מדי", Logic.chipsJson(Logic.chipsFor("avoided", null), "he", 7)[0].label)
        assertEquals("Taxes keeps slipping, and that's fine. What's in the way?", Logic.text("avoid_ask", "en", "title" to "Taxes"))
        assertTrue("מה מפריע" in Logic.text("avoid_ask", Logic.chipLang("מיסים"), "title" to "מיסים"))
    }

    // ------------------------------------------------------------ de-escalation

    @Test fun escalatesOnlyFixedEventsAskedForRemindersAndNearDeadlines() {
        assertFalse(Logic.escalates(task(priority = 3), NOW.i))
        assertTrue(Logic.escalates(task(remindRequested = true), NOW.i))
        assertTrue(Logic.escalates(task(kind = "meeting"), NOW.i) && Logic.escalates(task(kind = "fence"), NOW.i))
        assertTrue(Logic.escalates(task(due = NOW.plusHours(47)), NOW.i))
        assertTrue(Logic.escalates(task(due = NOW.minusHours(47)), NOW.i))
        assertFalse(Logic.escalates(task(due = NOW.plusHours(49)), NOW.i))
        assertFalse(Logic.escalates(task(due = NOW.minusDays(5)), NOW.i))
    }

    @Test fun oneCheckInADayForTasksThatDontEscalate() {
        val once = ContactState(sentToday = 1, sentTodayByTask = mapOf(1L to 1))
        assertEquals(l(10, 7).i, Logic.contactDecision(task(), once, NOW, G))
        assertNull(Logic.contactDecision(task(due = NOW.plusHours(5)), once, NOW, G))
        assertNull(Logic.contactDecision(task(remindRequested = true), once, NOW, G))
        assertNull(Logic.contactDecision(task(), ContactState(), NOW, G))
    }

    @Test fun alertLevels() {
        val p = Logic.AlertPolicy()
        assertEquals(Logic.URGENT, Logic.alertLevel(task(kind = "meeting"), 9, p, NOW.i))
        assertEquals(Logic.NORMAL, Logic.alertLevel(task(priority = 3), 9, p, NOW.i))           // doesn't escalate
        assertEquals(Logic.URGENT, Logic.alertLevel(task(remindRequested = true), 0, p, NOW.i))
        assertEquals(Logic.ALARM, Logic.alertLevel(task(remindRequested = true, priority = 2), 2, p, NOW.i))
        assertEquals(Logic.URGENT, Logic.alertLevel(task(remindRequested = true, priority = 1), 2, p, NOW.i)) // not alarm-worthy
        assertEquals(Logic.NORMAL, Logic.alertLevel(task(due = NOW.plusHours(5)), 0, p, NOW.i))
        assertEquals(Logic.URGENT, Logic.alertLevel(task(due = NOW.plusHours(5)), 1, p, NOW.i))
        assertEquals(Logic.URGENT, Logic.alertLevel(task(remindRequested = true, priority = 3), 2, p.copy(alarmFor = "off"), NOW.i))
    }

    @Test fun alarmWindowStopsBeforeQuietHoursAndShabbat() {
        assertEquals(3600L, Logic.alarmWindowS(NOW, hm(23), null))
        assertEquals(1800L, Logic.alarmWindowS(l(9, 22, 30), hm(23), null))
        assertEquals(600L, Logic.alarmWindowS(NOW, hm(23), NOW.plusMinutes(10).i))
    }

    // ------------------------------------------------------------ fences (F9)

    @Test fun fencesSkipTheGapTheCapsAndTheFocusHoldButNotQuietHours() {
        val busy = ContactState(lastProactiveAt = NOW.minusMinutes(2).i, sentToday = 20, focusHoldUntil = NOW.plusMinutes(30).i)
        val fence = task(kind = "fence", due = NOW)
        assertNull(Logic.contactDecision(fence, busy, NOW, G))
        assertNotNull(Logic.contactDecision(task(), busy, NOW, G))
        assertEquals(l(10, 7).i, Logic.contactDecision(fence, ContactState(), l(9, 23, 30), G))
        val rest = Guardrails(quietStart = hm(23), quietEnd = hm(7), restUntil = { l(14, 19).i })
        assertEquals(l(14, 19).i, Logic.contactDecision(fence, ContactState(), NOW, rest))
    }

    @Test fun fencesLeadTheMessageWithMeetingsAndGoStale() {
        val f = task(id = 2, kind = "fence", due = NOW)
        val plan = Logic.planContacts(listOf(task(id = 1, remind = NOW), f), ContactState(), NOW, G)
        assertEquals(listOf(2L, 1L), plan.send.map { it.id })
        assertFalse(Logic.fenceIsStale(f, NOW.plusMinutes(30).i))
        assertTrue(Logic.fenceIsStale(f, NOW.plusMinutes(31).i))
    }

    @Test fun whatNowNeverPicksPastAFence() {
        val pick = Logic.pickNext(listOf(task(id = 1, estimate = 40, nextAction = "Write")), NOW.i, NOW.plusMinutes(20).i, today = NOW.toLocalDate())!!
        assertEquals("short_start" to 5, pick.reason to pick.minutes)
    }

    // ------------------------------------------------------------ parking

    @Test fun parkedTasksGetNoCheckInsAndArentOffered() {
        val parked = task(parked = NOW.plusDays(3), focus = NOW.toLocalDate(), due = NOW)
        assertTrue(Logic.isParked(parked, NOW.i))
        assertFalse(Logic.isParked(parked, NOW.plusDays(4).i))
        assertFalse(Logic.mayCheckIn(parked, NOW.i, NOW.toLocalDate()))
        assertFalse(Logic.mayCheckIn(parked.copy(remindRequested = true), NOW.i, NOW.toLocalDate()))
        assertEquals(emptyList<Long>(), Logic.pickFocus(listOf(parked), NOW))
        assertNull(Logic.pickNext(listOf(parked), NOW.i, null, today = NOW.toLocalDate()))
        assertTrue(Logic.mayCheckIn(parked.copy(kind = "fence"), NOW.i, NOW.toLocalDate()))
    }

    @Test fun anAskedAvoidedTaskStaysDueForItsLastTry() {
        assertTrue(Logic.mayCheckIn(task(avoidAsks = 1), NOW.i, NOW.toLocalDate()))
        assertFalse(Logic.mayCheckIn(task(), NOW.i, NOW.toLocalDate()))
    }

    // ------------------------------------------------------------ the lapse and re-entry

    @Test fun lapseIs48HoursWithoutActivity() {
        assertFalse(Logic.lapsed(null, NOW.i))
        assertFalse(Logic.lapsed(NOW.minusHours(47).i, NOW.i))
        assertTrue(Logic.lapsed(NOW.minusHours(48).i, NOW.i))
    }

    @Test fun shabbatAndHolidaysDontCountTowardTheLapse() {
        val shabbat = listOf<Pair<Instant, Instant>>(l(13, 15).i to l(14, 19).i)
        val last = l(13, 9).i
        assertFalse(Logic.lapsed(last, l(15, 9).i, shabbat))
        assertFalse(Logic.lapsed(last, l(16, 12).i, shabbat))
        assertTrue(Logic.lapsed(last, l(16, 13).i, shabbat))
        assertTrue(Logic.lapsed(last, l(15, 9).i, listOf(l(6, 15).i to l(7, 19).i)))
    }

    @Test fun lapseStopsTaskCheckInsOnly() {
        val today = NOW.toLocalDate()
        assertFalse(Logic.mayCheckIn(task(focus = today), NOW.i, today, lapsed = true))
        assertFalse(Logic.mayCheckIn(task(due = NOW), NOW.i, today, lapsed = true))
        assertTrue(Logic.mayCheckIn(task(remindRequested = true), NOW.i, today, lapsed = true))
        assertTrue(Logic.mayCheckIn(task(kind = "meeting"), NOW.i, today, lapsed = true))
        assertTrue(Logic.mayCheckIn(task(kind = "fence"), NOW.i, today, lapsed = true))
    }

    @Test fun oneReentryMessagePerLapseThenWeekly() {
        val active = NOW.minusDays(3).i
        assertTrue(Logic.reentryDue(active, null, NOW.i))
        assertTrue(Logic.reentryDue(active, NOW.minusDays(33).i, NOW.i))
        assertFalse(Logic.reentryDue(active, NOW.minusDays(1).i, NOW.i))
        assertTrue(Logic.reentryDue(NOW.minusDays(12).i, NOW.minusDays(7).i, NOW.i))
    }

    @Test fun reentryChipsAndPlaybooks() {
        val chips = Logic.chipsJson(Logic.chipsFor("reentry", null), "en", null)
        assertEquals(listOf("pick_one" to null, "move_overdue" to "move_overdue"), chips.map { it.id to it.action })
        assertTrue("reentry" in Logic.chatSignals("Pick one for me", emptyList(), false, NOW, 0))
        assertEquals(listOf("reentry"), Logic.selectPlaybooks(null, emptyList(), setOf("reentry")))
        assertEquals("Good to see you. Want me to pick one thing for today?", Logic.text("reentry", "en"))
    }

    @Test fun moveOverdueKeepsTheWeekdayAndTime() {
        assertEquals(l(15, 9), Logic.movedToNextWeek(l(8, 9), NOW.i))
        assertEquals(l(15, 9), Logic.movedToNextWeek(l(8, 9).minusDays(21), NOW.i))
    }

    // ------------------------------------------------------------ Sunday (F8)

    @Test fun weekMeetingsJoinTheMorningPlanWithinAnHour() {
        assertTrue(Logic.weekMeetingsMerged(hm(7, 30), hm(8)))
        assertTrue(Logic.weekMeetingsMerged(hm(8), hm(7)))
        assertFalse(Logic.weekMeetingsMerged(hm(7, 30), hm(8, 31)))
        assertFalse(Logic.weekMeetingsMerged(hm(7, 30), null))
    }

    @Test fun sundayChipsActOnExactlyTheListedTasks() {
        val chips = Logic.chipsJson(Logic.chipsFor("sunday", null), "en", null, listOf(4, 9))
        assertEquals(listOf(Triple("drop", "drop", listOf(4L, 9L)), Triple("keep", "keep", listOf(4L, 9L))),
            chips.map { Triple(it.id, it.action, it.taskIds) })
        assertTrue(Logic.chipsJson(Logic.chipsFor("sunday", null), "en", null).isEmpty())
        assertTrue(Logic.text("stale", "en", "titles" to "A · B").startsWith("🧹 Open for 3+ weeks with no progress: A · B."))
        assertEquals("✅ Last week you finished 9 things.", Logic.text("wins_week", "en", "n" to 9))
    }

    // ------------------------------------------------------------ meetings

    @Test fun meetingOccurrencesExpandRecurringOnesInTheWindow() {
        val once = task(1, kind = "meeting", due = l(10, 9))
        val daily = task(2, kind = "meeting", due = l(5, 8), recurrence = "daily")
        val got = Logic.meetingOccurrences(listOf(once, daily), l(9, 0).i, l(11, 0).i, TZ)
        assertEquals(listOf(l(9, 8).i to 2L, l(10, 8).i to 2L, l(10, 9).i to 1L), got.map { it.first to it.second.id })
    }
}
