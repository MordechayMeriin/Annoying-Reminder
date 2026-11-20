package com.prominder.myapp.logic

import com.prominder.myapp.logic.Contacts.ContactState
import com.prominder.myapp.logic.Contacts.Guardrails
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Avoidance, de-escalation, the lapse, fences, Sunday: the cases of test_logic_phase3.py (the Python server's tests, removed in b09e82e). */
class GentlerPressureTest {
    private fun l(d: Int, h: Int, mi: Int = 0) = local(2026, 11, d, h, mi) // Sun 8 Nov 2026 … Sat 14 Nov 2026
    private val NOW = l(9, 10)
    private val G = Guardrails(quietStart = hm(23), quietEnd = hm(7))
    private val avoided = mapOf("postponed" to 2, "ignored" to 1)

    // ------------------------------------------------------------ avoidance (F7)

    @Test fun avoidMoveAskWaitAskPark() {
        assertNull(Pressure.avoidMove(task(), mapOf("postponed" to 1, "ignored" to 1), NOW.i))
        assertEquals("ask", Pressure.avoidMove(task(), avoided, NOW.i))
        val asked = task(avoidAsks = 1, avoidAsked = NOW)
        assertEquals("wait", Pressure.avoidMove(asked, avoided, NOW.plusDays(2).i))
        assertEquals("ask", Pressure.avoidMove(asked, avoided, NOW.plusDays(3).i))
        val twice = task(avoidAsks = 2, avoidAsked = NOW)
        assertEquals("wait", Pressure.avoidMove(twice, avoided, NOW.plusDays(1).i))
        assertEquals("park", Pressure.avoidMove(twice, avoided, NOW.plusDays(3).i))
    }

    @Test fun avoidMoveLeavesMeetingsFencesAndUpcomingDeadlinesAlone() {
        val five = mapOf("ignored" to 5)
        assertNull(Pressure.avoidMove(task(kind = "meeting"), five, NOW.i))
        assertNull(Pressure.avoidMove(task(kind = "fence"), five, NOW.i))
        assertNull(Pressure.avoidMove(task(due = NOW.plusHours(20)), five, NOW.i))
        assertEquals("ask", Pressure.avoidMove(task(due = NOW.minusDays(3)), five, NOW.i))
        assertEquals("ask", Pressure.avoidMove(task(due = NOW.plusDays(5)), five, NOW.i))
    }

    @Test fun avoidedChipsAndTextsInBothLanguages() {
        val chips = ChipRules.chipsJson(ChipRules.chipsFor("avoided", "avoided"), "en", 7)
        assertEquals(listOf("too_big" to null, "unclear" to null, "boring" to null, "not_now" to "not_now"), chips.map { it.id to it.action })
        assertEquals(7L, chips.last().taskId)
        assertEquals("גדול מדי", ChipRules.chipsJson(ChipRules.chipsFor("avoided", null), "he", 7)[0].label)
        assertEquals("Taxes keeps slipping, and that's fine. What's in the way?", Texts.text("avoid_ask", "en", "title" to "Taxes"))
        assertTrue("מה מפריע" in Texts.text("avoid_ask", ChipRules.chipLang("מיסים"), "title" to "מיסים"))
    }

    // ------------------------------------------------------------ de-escalation

    @Test fun escalatesOnlyFixedEventsAskedForRemindersAndNearDeadlines() {
        assertFalse(Loudness.escalates(task(priority = 3), NOW.i))
        assertTrue(Loudness.escalates(task(remindRequested = true), NOW.i))
        assertTrue(Loudness.escalates(task(kind = "meeting"), NOW.i) && Loudness.escalates(task(kind = "fence"), NOW.i))
        assertTrue(Loudness.escalates(task(due = NOW.plusHours(47)), NOW.i))
        assertTrue(Loudness.escalates(task(due = NOW.minusHours(47)), NOW.i))
        assertFalse(Loudness.escalates(task(due = NOW.plusHours(49)), NOW.i))
        assertFalse(Loudness.escalates(task(due = NOW.minusDays(5)), NOW.i))
    }

    @Test fun oneCheckInADayForTasksThatDontEscalate() {
        val once = ContactState(sentToday = 1, sentTodayByTask = mapOf(1L to 1))
        assertEquals(l(10, 7).i, Contacts.contactDecision(task(), once, NOW, G))
        assertNull(Contacts.contactDecision(task(due = NOW.plusHours(5)), once, NOW, G))
        assertNull(Contacts.contactDecision(task(remindRequested = true), once, NOW, G))
        assertNull(Contacts.contactDecision(task(), ContactState(), NOW, G))
    }

    @Test fun alertLevels() {
        val p = Loudness.AlertPolicy()
        assertEquals(Loudness.URGENT, Loudness.alertLevel(task(kind = "meeting"), 9, p, NOW.i))
        assertEquals(Loudness.NORMAL, Loudness.alertLevel(task(priority = 3), 9, p, NOW.i))           // doesn't escalate
        assertEquals(Loudness.URGENT, Loudness.alertLevel(task(remindRequested = true), 0, p, NOW.i))
        assertEquals(Loudness.ALARM, Loudness.alertLevel(task(remindRequested = true, priority = 2), 2, p, NOW.i))
        assertEquals(Loudness.URGENT, Loudness.alertLevel(task(remindRequested = true, priority = 1), 2, p, NOW.i)) // not alarm-worthy
        assertEquals(Loudness.NORMAL, Loudness.alertLevel(task(due = NOW.plusHours(5)), 0, p, NOW.i))
        assertEquals(Loudness.URGENT, Loudness.alertLevel(task(due = NOW.plusHours(5)), 1, p, NOW.i))
        assertEquals(Loudness.URGENT, Loudness.alertLevel(task(remindRequested = true, priority = 3), 2, p.copy(alarmFor = "off"), NOW.i))
    }

    @Test fun alarmWindowStopsBeforeQuietHoursAndShabbat() {
        assertEquals(3600L, Loudness.alarmWindowS(NOW, hm(23), null))
        assertEquals(1800L, Loudness.alarmWindowS(l(9, 22, 30), hm(23), null))
        assertEquals(600L, Loudness.alarmWindowS(NOW, hm(23), NOW.plusMinutes(10).i))
    }

    // ------------------------------------------------------------ fences (F9)

    @Test fun fencesSkipTheGapTheCapsAndTheFocusHoldButNotQuietHours() {
        val busy = ContactState(lastProactiveAt = NOW.minusMinutes(2).i, sentToday = 20, focusHoldUntil = NOW.plusMinutes(30).i)
        val fence = task(kind = "fence", due = NOW)
        assertNull(Contacts.contactDecision(fence, busy, NOW, G))
        assertNotNull(Contacts.contactDecision(task(), busy, NOW, G))
        assertEquals(l(10, 7).i, Contacts.contactDecision(fence, ContactState(), l(9, 23, 30), G))
        val rest = Guardrails(quietStart = hm(23), quietEnd = hm(7), restUntil = { l(14, 19).i })
        assertEquals(l(14, 19).i, Contacts.contactDecision(fence, ContactState(), NOW, rest))
    }

    @Test fun fencesLeadTheMessageWithMeetingsAndGoStale() {
        val f = task(id = 2, kind = "fence", due = NOW)
        val plan = Contacts.planContacts(listOf(task(id = 1, remind = NOW), f), ContactState(), NOW, G)
        assertEquals(listOf(2L, 1L), plan.send.map { it.id })
        assertFalse(Pressure.fenceIsStale(f, NOW.plusMinutes(30).i))
        assertTrue(Pressure.fenceIsStale(f, NOW.plusMinutes(31).i))
    }

    @Test fun whatNowNeverPicksPastAFence() {
        val pick = Focus.pickNext(listOf(task(id = 1, estimate = 40, nextAction = "Write")), NOW.i, NOW.plusMinutes(20).i, today = NOW.toLocalDate())!!
        assertEquals("short_start" to 5, pick.reason to pick.minutes)
    }

    // ------------------------------------------------------------ parking

    @Test fun parkedTasksGetNoCheckInsAndArentOffered() {
        val parked = task(parked = NOW.plusDays(3), focus = NOW.toLocalDate(), due = NOW)
        assertTrue(Focus.isParked(parked, NOW.i))
        assertFalse(Focus.isParked(parked, NOW.plusDays(4).i))
        assertFalse(Focus.mayCheckIn(parked, NOW.i, NOW.toLocalDate()))
        assertFalse(Focus.mayCheckIn(parked.copy(remindRequested = true), NOW.i, NOW.toLocalDate()))
        assertEquals(emptyList<Long>(), Focus.pickFocus(listOf(parked), NOW))
        assertNull(Focus.pickNext(listOf(parked), NOW.i, null, today = NOW.toLocalDate()))
        assertTrue(Focus.mayCheckIn(parked.copy(kind = "fence"), NOW.i, NOW.toLocalDate()))
    }

    @Test fun anAskedAvoidedTaskStaysDueForItsLastTry() {
        assertTrue(Focus.mayCheckIn(task(avoidAsks = 1), NOW.i, NOW.toLocalDate()))
        assertFalse(Focus.mayCheckIn(task(), NOW.i, NOW.toLocalDate()))
    }

    // ------------------------------------------------------------ the lapse and re-entry

    @Test fun lapseIs48HoursWithoutActivity() {
        assertFalse(Pressure.lapsed(null, NOW.i))
        assertFalse(Pressure.lapsed(NOW.minusHours(47).i, NOW.i))
        assertTrue(Pressure.lapsed(NOW.minusHours(48).i, NOW.i))
    }

    @Test fun shabbatAndHolidaysDontCountTowardTheLapse() {
        val shabbat = listOf<Pair<Instant, Instant>>(l(13, 15).i to l(14, 19).i)
        val last = l(13, 9).i
        assertFalse(Pressure.lapsed(last, l(15, 9).i, shabbat))
        assertFalse(Pressure.lapsed(last, l(16, 12).i, shabbat))
        assertTrue(Pressure.lapsed(last, l(16, 13).i, shabbat))
        assertTrue(Pressure.lapsed(last, l(15, 9).i, listOf(l(6, 15).i to l(7, 19).i)))
    }

    @Test fun lapseStopsTaskCheckInsOnly() {
        val today = NOW.toLocalDate()
        assertFalse(Focus.mayCheckIn(task(focus = today), NOW.i, today, lapsed = true))
        assertFalse(Focus.mayCheckIn(task(due = NOW), NOW.i, today, lapsed = true))
        assertTrue(Focus.mayCheckIn(task(remindRequested = true), NOW.i, today, lapsed = true))
        assertTrue(Focus.mayCheckIn(task(kind = "meeting"), NOW.i, today, lapsed = true))
        assertTrue(Focus.mayCheckIn(task(kind = "fence"), NOW.i, today, lapsed = true))
    }

    @Test fun oneReentryMessagePerLapseThenWeekly() {
        val active = NOW.minusDays(3).i
        assertTrue(Pressure.reentryDue(active, null, NOW.i))
        assertTrue(Pressure.reentryDue(active, NOW.minusDays(33).i, NOW.i))
        assertFalse(Pressure.reentryDue(active, NOW.minusDays(1).i, NOW.i))
        assertTrue(Pressure.reentryDue(NOW.minusDays(12).i, NOW.minusDays(7).i, NOW.i))
    }

    @Test fun reentryChipsAndPlaybooks() {
        val chips = ChipRules.chipsJson(ChipRules.chipsFor("reentry", null), "en", null)
        assertEquals(listOf("pick_one" to null, "move_overdue" to "move_overdue"), chips.map { it.id to it.action })
        assertTrue("reentry" in Coaching.chatSignals("Pick one for me", emptyList(), false, NOW, 0))
        assertEquals(listOf("reentry"), Coaching.selectPlaybooks(null, emptyList(), setOf("reentry")))
        assertEquals("Good to see you. Want me to pick one thing for today?", Texts.text("reentry", "en"))
    }

    @Test fun moveOverdueKeepsTheWeekdayAndTime() {
        assertEquals(l(15, 9), Pressure.movedToNextWeek(l(8, 9), NOW.i))
        assertEquals(l(15, 9), Pressure.movedToNextWeek(l(8, 9).minusDays(21), NOW.i))
    }

    // ------------------------------------------------------------ Sunday (F8)

    @Test fun weekMeetingsJoinTheMorningPlanWithinAnHour() {
        assertTrue(Pressure.weekMeetingsMerged(hm(7, 30), hm(8)))
        assertTrue(Pressure.weekMeetingsMerged(hm(8), hm(7)))
        assertFalse(Pressure.weekMeetingsMerged(hm(7, 30), hm(8, 31)))
        assertFalse(Pressure.weekMeetingsMerged(hm(7, 30), null))
    }

    @Test fun sundayChipsActOnExactlyTheListedTasks() {
        val chips = ChipRules.chipsJson(ChipRules.chipsFor("sunday", null), "en", null, listOf(4, 9))
        assertEquals(listOf(Triple("drop", "drop", listOf(4L, 9L)), Triple("keep", "keep", listOf(4L, 9L))),
            chips.map { Triple(it.id, it.action, it.taskIds) })
        assertTrue(ChipRules.chipsJson(ChipRules.chipsFor("sunday", null), "en", null).isEmpty())
        assertTrue(Texts.text("stale", "en", "titles" to "A · B").startsWith("🧹 Open for 3+ weeks with no progress: A · B."))
        assertEquals("✅ Last week you finished 9 things.", Texts.text("wins_week", "en", "n" to 9))
    }

    // ------------------------------------------------------------ meetings

    @Test fun meetingOccurrencesExpandRecurringOnesInTheWindow() {
        val once = task(1, kind = "meeting", due = l(10, 9))
        val daily = task(2, kind = "meeting", due = l(5, 8), recurrence = "daily")
        val got = Schedule.meetingOccurrences(listOf(once, daily), l(9, 0).i, l(11, 0).i, TZ)
        assertEquals(listOf(l(9, 8).i to 2L, l(10, 8).i to 2L, l(10, 9).i to 1L), got.map { it.first to it.second.id })
    }
}
