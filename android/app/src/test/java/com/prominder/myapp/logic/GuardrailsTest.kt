package com.prominder.myapp.logic

import com.prominder.myapp.logic.Logic.ContactState
import com.prominder.myapp.logic.Logic.Guardrails
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** backend/tests/test_logic.py and test_guardrails.py, one for one. */
class GuardrailsTest {
    private val G = Guardrails(quietStart = hm(23), quietEnd = hm(7), dayReset = hm(4), minGapMin = 20, maxPerDay = 20,
        maxPerTaskPerDay = 4, followupMinMin = 10, followupMaxDays = 7)

    private fun l(d: Int, h: Int, mi: Int = 0) = local(2026, 11, d, h, mi)

    // ------------------------------------------------------------ test_logic.py

    @Test fun quietHoursAcrossMidnight() {
        assertTrue(Logic.inQuietHours(l(8, 23, 30), hm(23), hm(7)))
        assertTrue(Logic.inQuietHours(l(9, 6, 59), hm(23), hm(7)))
        assertFalse(Logic.inQuietHours(l(9, 7), hm(23), hm(7)))
        assertFalse(Logic.inQuietHours(l(9, 14), hm(23), hm(7)))
    }

    @Test fun quietEndAfter() {
        assertEquals(l(9, 7), Logic.quietEndAfter(l(8, 23, 30), hm(7)))
        assertEquals(l(9, 7), Logic.quietEndAfter(l(9, 3), hm(7)))
    }

    @Test fun jobWindow() {
        assertTrue(Logic.jobIsDue(l(9, 7, 30), hm(7, 30)))
        assertTrue(Logic.jobIsDue(l(9, 9), hm(7, 30)))
        assertFalse(Logic.jobIsDue(l(9, 7, 25), hm(7, 30)))
        assertFalse(Logic.jobIsDue(l(9, 11), hm(7, 30)))
    }

    @Test fun nagging() {
        val now = ZonedDateTime.of(2026, 11, 9, 10, 0, 0, 0, ZoneOffset.UTC).toInstant()
        assertEquals(now.plus(Duration.ofMinutes(30)), Logic.nextReminderAfterNag(now, 1, 1, 30, 4))
        assertEquals(now.plus(Duration.ofMinutes(15)), Logic.nextReminderAfterNag(now, 1, 3, 30, 4))
        assertNull(Logic.nextReminderAfterNag(now, 4, 1, 30, 4))
        assertNull(Logic.nextReminderAfterNag(now, 4, 3, 30, 4))
    }

    @Test fun recurrence() {
        val thu = l(12, 9)
        assertEquals(l(15, 9).i, Logic.nextOccurrence(thu.i, "weekdays", TZ))
        assertEquals(l(13, 9).i, Logic.nextOccurrence(thu.i, "daily", TZ))
        assertEquals(l(19, 9).i, Logic.nextOccurrence(thu.i, "weekly", TZ))
        assertEquals(local(2026, 2, 28, 8).i, Logic.nextOccurrence(local(2026, 1, 31, 8).i, "monthly", TZ))
    }

    @Test fun recurrenceKeepsWallClockAcrossDst() {
        val after = Logic.nextOccurrence(local(2026, 10, 24, 9).i, "daily", TZ).atZone(TZ)
        assertEquals(9, after.hour)
        assertEquals(ZoneOffset.ofHours(2), after.offset)
    }

    @Test fun parseLocalDateTime() {
        assertEquals(l(9, 14).i, Logic.parseLocalDateTime("2026-11-09T14:00", TZ))
        assertEquals(ZonedDateTime.of(2026, 11, 9, 12, 0, 0, 0, ZoneOffset.UTC).toInstant(), Logic.parseLocalDateTime("2026-11-09T12:00Z", TZ))
        assertNull(Logic.parseLocalDateTime("", TZ))
    }

    // ------------------------------------------------------------ contact_decision

    @Test fun freeToContact() = assertNull(Logic.contactDecision(task(1), ContactState(), l(9, 10), G))

    @Test fun quietHoursPostponeUntilMorning() {
        assertEquals(l(10, 7).i, Logic.contactDecision(task(1), ContactState(), l(9, 23, 30), G))
        assertEquals(l(10, 7).i, Logic.contactDecision(task(1, kind = "meeting", priority = 2), ContactState(), l(10, 6), G))
    }

    @Test fun quietHoursAreNeverOverruled() {
        assertEquals(l(10, 7).i, Logic.contactDecision(task(1, priority = 3), ContactState(), l(9, 23, 30), G))
        assertEquals(l(10, 7).i, Logic.contactDecision(task(2, kind = "meeting", priority = 3), ContactState(), l(10, 6, 50), G))
    }

    @Test fun minGapPostponesTasksButNotMeetings() {
        val st = ContactState(lastProactiveAt = l(9, 10).i, sentToday = 1)
        assertEquals(l(9, 10, 20).i, Logic.contactDecision(task(1), st, l(9, 10, 5), G))
        assertNull(Logic.contactDecision(task(2, kind = "meeting"), st, l(9, 10, 5), G))
        assertNull(Logic.contactDecision(task(1), st, l(9, 10, 20), G))
    }

    @Test fun dailyCapPostponesToNextMorning() {
        val st = ContactState(lastProactiveAt = l(9, 8).i, sentToday = 20)
        assertEquals(l(10, 7).i, Logic.contactDecision(task(1), st, l(9, 15), G))
    }

    @Test fun meetingsSkipDailyCap() {
        val st = ContactState(lastProactiveAt = l(9, 16, 40).i, sentToday = 20)
        assertNull(Logic.contactDecision(task(1, kind = "meeting"), st, l(9, 16, 45), G))
    }

    @Test fun perTaskCapOnlyAffectsThatTask() {
        val st = ContactState(lastProactiveAt = l(9, 8).i, sentToday = 5, sentTodayByTask = mapOf(1L to 4))
        assertEquals(l(10, 7).i, Logic.contactDecision(task(1), st, l(9, 15), G))
        assertNull(Logic.contactDecision(task(2), st, l(9, 15), G))
    }

    @Test fun requestedReminderIgnoresPerTaskCapButNotDailyCap() {
        val st = ContactState(lastProactiveAt = l(9, 8).i, sentToday = 5, sentTodayByTask = mapOf(1L to 4))
        assertNull(Logic.contactDecision(task(1, remindRequested = true), st, l(9, 15), G))
        val full = st.copy(sentToday = 20)
        assertEquals(l(10, 7).i, Logic.contactDecision(task(1, remindRequested = true), full, l(9, 15), G))
    }

    @Test fun blockedWaitsUntilBlockedUntil() {
        val t = task(1, blocked = l(9, 18))
        assertEquals(l(9, 18).i, Logic.contactDecision(t, ContactState(), l(9, 15), G))
        assertNull(Logic.contactDecision(t, ContactState(), l(9, 18, 5), G))
    }

    @Test fun latestPostponementWins() {
        assertEquals(l(10, 7).i, Logic.contactDecision(task(1, priority = 3, blocked = l(10, 6)), ContactState(), l(9, 23, 30), G))
        val st = ContactState(lastProactiveAt = l(9, 10).i, sentToday = 1)
        assertEquals(l(9, 12).i, Logic.contactDecision(task(2, blocked = l(9, 12)), st, l(9, 10, 5), G))
    }

    @Test fun nextMorningWaitsForCounterReset() {
        assertEquals(l(10, 7), Logic.nextMorning(l(9, 15), G))
        assertEquals(l(10, 7), Logic.nextMorning(l(9, 6), G))
        val lateReset = Guardrails(quietStart = hm(23), quietEnd = hm(7), dayReset = hm(9))
        assertEquals(l(10, 9), Logic.nextMorning(l(9, 15), lateReset))
    }

    // ------------------------------------------------------------ grouping

    @Test fun dueTasksGroupedIntoOneMessageMeetingsFirst() {
        val due = listOf(task(1, remind = l(9, 9)), task(2, priority = 3, remind = l(9, 10)), task(3, kind = "meeting", due = l(9, 11)))
        val plan = Logic.planContacts(due, ContactState(), l(9, 10, 50), G)
        assertEquals(listOf(3L, 2L, 1L), plan.send.map { it.id })
        assertTrue(plan.defer.isEmpty())
    }

    @Test fun groupingWithGapKeepsMeetingAndDefersRest() {
        val st = ContactState(lastProactiveAt = l(9, 10, 40).i, sentToday = 3)
        val plan = Logic.planContacts(listOf(task(1), task(3, kind = "meeting", due = l(9, 11))), st, l(9, 10, 45), G)
        assertEquals(listOf(3L), plan.send.map { it.id })
        assertEquals(mapOf(1L to l(9, 11).i), plan.defer)
    }

    // ------------------------------------------------------------ follow-ups and day boundary

    @Test fun clampFollowup() {
        val now = l(9, 10).i
        assertEquals(now.plus(Duration.ofMinutes(10)), Logic.clampFollowup(now, now.plus(Duration.ofMinutes(2)), G))
        assertEquals(now.plus(Duration.ofDays(7)), Logic.clampFollowup(now, now.plus(Duration.ofDays(30)), G))
        assertEquals(now.plus(Duration.ofHours(2)), Logic.clampFollowup(now, now.plus(Duration.ofHours(2)), G))
    }

    @Test fun dayStartResetsAt4amNotMidnight() {
        assertEquals(l(8, 4), Logic.dayStart(l(9, 0, 30), hm(4)))
        assertEquals(l(9, 4), Logic.dayStart(l(9, 4), hm(4)))
        assertEquals(l(9, 4), Logic.dayStart(l(9, 22), hm(4)))
    }

    // ------------------------------------------------------------ reply → task

    private val OPEN = listOf(task(7, title = "Call the plumber"), task(8, title = "לשלם ארנונה"), task(9, title = "Gym"))

    @Test fun candidatesRecentCheckinFirst() =
        assertEquals(listOf(9L, 7L), Logic.selectCandidateTasks("ugh, the plumber again", l(9, 10).i, l(9, 9).i, listOf(9), emptyList(), OPEN, 3.0))

    @Test fun candidatesOldCheckinIgnoredHebrewMatch() =
        assertEquals(listOf(8L), Logic.selectCandidateTasks("שילמתי ארנונה", l(9, 10).i, l(9, 5).i, listOf(9), emptyList(), OPEN, 3.0))

    @Test fun candidatesLimitAndDedupe() =
        assertEquals(listOf(9L, 7L, 8L), Logic.selectCandidateTasks("plumber", l(9, 10).i, l(9, 9).i, listOf(9, 7), listOf(7, 8), OPEN, 3.0, limit = 3))

    @Test fun linkFollowsActionsThenUnambiguousCheckin() {
        assertEquals(listOf(5L, 6L), Logic.linkTaskIds(listOf(5, 5, 6), listOf(9)))
        assertEquals(listOf(9L), Logic.linkTaskIds(emptyList(), listOf(9)))
        assertEquals(emptyList<Long>(), Logic.linkTaskIds(emptyList(), listOf(9, 7)))
        assertEquals(emptyList<Long>(), Logic.linkTaskIds(emptyList(), emptyList()))
    }

    @Test fun clip() {
        assertEquals("short", Logic.clip("short", 10))
        assertEquals("hello wor…", Logic.clip("hello world again", 10)) // the space is too early: cut mid-word
        assertEquals("hello world,…", Logic.clip("hello world, again", 14))
        assertEquals("abcdefghi…", Logic.clip("abcdefghijklmnop", 10))
    }
}
