package com.prominder.myapp.logic

import com.prominder.myapp.logic.Contacts.ContactState
import com.prominder.myapp.logic.Contacts.Guardrails
import java.time.Duration
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ADHD rules, with the cases of test_logic_adhd.py and test_logic_phase2.py (the Python server's tests, removed in b09e82e). */
class AdhdLogicTest {
    private val TODAY: LocalDate = LocalDate.of(2026, 11, 9)
    private val G = Guardrails(quietStart = hm(23), quietEnd = hm(7))
    private fun at(h: Int, mi: Int = 0, d: Int = 9) = local(2026, 11, d, h, mi)

    // ------------------------------------------------------------ chips

    @Test fun chipsPerKind() {
        val expected = mapOf(
            "checkin" to listOf("start5", "done", "later", "stuck"), "morning" to listOf("start_first", "change"),
            "session_end" to listOf("done", "another", "stop"), "avoided" to listOf("too_big", "unclear", "boring", "not_now"),
            "reentry" to listOf("pick_one", "move_overdue"), "sunday" to listOf("drop", "keep"), "meeting" to emptyList(),
            "midday" to listOf("start_next", "what_now"), "what_now" to listOf("start", "low_energy", "something_else"),
            "chat_task" to listOf("start5", "done"), "evening" to emptyList(),
        )
        for ((kind, ids) in expected) assertEquals(kind, ids, ChipRules.chipsFor(kind, "clarified").map { it.id })
    }

    @Test fun everyKindWithChipsHasTwoToFour() {
        for ((kind, chips) in ChipRules.CHIPS_BY_KIND) assertTrue(kind, chips.isEmpty() || chips.size in 2..4)
    }

    @Test fun doneTaskGetsNoChips() {
        assertTrue(ChipRules.chipsFor("checkin", "done").isEmpty())
        assertTrue(ChipRules.chipsFor("chat_task", "cancelled").isEmpty())
    }

    @Test fun chipLabelsFollowTheLanguage() {
        val en = ChipRules.chipsJson(ChipRules.chipsFor("checkin", null), "en", 7)
        val he = ChipRules.chipsJson(ChipRules.chipsFor("checkin", null), "he", 7)
        assertEquals(listOf("▶ Start 5 min", "✓ Done", "Later", "I'm stuck"), en.map { it.label })
        assertEquals("▶ להתחיל 5 דק׳", he[0].label)
        assertEquals("נתקעתי", he[3].label)
        assertEquals(ChipRules.ChipJson("start5", "▶ Start 5 min", "start", 5, 7), en[0])
        assertEquals(ChipRules.ChipJson("stuck", "I'm stuck"), en[3])
        assertTrue(ChipRules.CHIP_LABELS.values.all { it.first.isNotEmpty() && it.second.isNotEmpty() })
    }

    @Test fun actionChipsWithoutATaskAreDropped() =
        assertEquals(listOf("what_now"), ChipRules.chipsJson(ChipRules.chipsFor("midday", null), "en", null).map { it.id })

    @Test fun chipLanguageRule() {
        assertEquals("he", ChipRules.chipLang("שלום מה קורה"))
        assertEquals("en", ChipRules.chipLang("hi there", "לשלם ארנונה"))
        assertEquals("he", ChipRules.chipLang(null, "לשלם ארנונה"))
        assertEquals("en", ChipRules.chipLang("", "Taxes"))
    }

    @Test fun chipTextIsRecognisedInBothLanguages() {
        assertEquals("stuck", ChipRules.chipIdForText("I'm stuck"))
        assertEquals("stuck", ChipRules.chipIdForText(" נתקעתי "))
        assertNull(ChipRules.chipIdForText("I'm stuck on the form"))
    }

    // ------------------------------------------------------------ Later, stages, activity

    @Test fun laterIsTwoHoursBeforeSixPmElseNextMorning() {
        assertEquals(at(12), Schedule.laterTime(at(10), hm(7, 30)))
        assertEquals(at(19, 59), Schedule.laterTime(at(17, 59), hm(7, 30)))
        assertEquals(at(7, 30, d = 10), Schedule.laterTime(at(18), hm(7, 30)))
        assertEquals(at(7, 30, d = 10), Schedule.laterTime(at(23, 30), hm(7, 30)))
    }

    @Test fun focusDayFollowsTheDayReset() {
        assertEquals(LocalDate.of(2026, 11, 8), Schedule.focusDay(at(3, 59), hm(4)))
        assertEquals(TODAY, Schedule.focusDay(at(4), hm(4)))
    }

    @Test fun taskStages() {
        assertEquals("captured", Coaching.taskStage(task(1), emptyMap(), TODAY))
        assertEquals("clarified", Coaching.taskStage(task(1, nextAction = "Open the form"), emptyMap(), TODAY))
        assertEquals("planned", Coaching.taskStage(task(1, nextAction = "x", focus = TODAY), emptyMap(), TODAY))
        assertEquals("captured", Coaching.taskStage(task(1, focus = LocalDate.of(2026, 11, 8)), emptyMap(), TODAY))
        assertEquals("avoided", Coaching.taskStage(task(1, focus = TODAY), mapOf("postponed" to 2, "ignored" to 1), TODAY))
        assertEquals("done", Coaching.taskStage(task(1, status = "done"), mapOf("postponed" to 5), TODAY))
    }

    @Test fun userActiveRecently() {
        assertTrue(Focus.userActiveRecently(at(9, 30).i, at(10).i))
        assertFalse(Focus.userActiveRecently(at(9).i, at(10).i))
        assertFalse(Focus.userActiveRecently(null, at(10).i))
    }

    // ------------------------------------------------------------ playbooks

    @Test fun selectPlaybooks() {
        assertEquals(listOf("planning"), Coaching.selectPlaybooks("morning"))
        assertEquals(listOf("planning", "sunday"), Coaching.selectPlaybooks("morning", signals = setOf("sunday")))
        assertEquals(listOf("reentry"), Coaching.selectPlaybooks("reentry"))
        assertEquals(listOf("avoiding"), Coaching.selectPlaybooks(null, listOf("planned", "avoided")))
        assertEquals(listOf("stuck"), Coaching.selectPlaybooks(null, emptyList(), setOf("stuck")))
        assertEquals(listOf("avoiding"), Coaching.selectPlaybooks(null, emptyList(), setOf("avoid_reason")))
        assertEquals(listOf("avoiding", "stuck"), Coaching.selectPlaybooks(null, listOf("avoided"), listOf("stuck", "overwhelmed", "completed")))
        assertEquals(listOf("celebrate"), Coaching.selectPlaybooks(null, emptyList(), setOf("completed")))
        assertEquals(emptyList<String>(), Coaching.selectPlaybooks(null, listOf("clarified")))
    }

    @Test fun chatSignals() {
        assertEquals(setOf("stuck"), Coaching.chatSignals("I'm stuck", emptyList(), false, at(10), 0))
        assertEquals(setOf("stuck"), Coaching.chatSignals("hello", emptyList(), true, at(10), 0))
        assertEquals(setOf("avoid_reason"), Coaching.chatSignals("גדול מדי", emptyList(), false, at(10), 0))
        assertEquals(setOf("overwhelmed"), Coaching.chatSignals("hm", emptyList(), false, at(15), 3))
        assertEquals(emptySet<String>(), Coaching.chatSignals("hm", emptyList(), false, at(14, 59), 3))
        assertEquals(emptySet<String>(), Coaching.chatSignals("hm", emptyList(), false, at(16), 2))
        assertEquals(setOf("completed"), Coaching.chatSignals("yay", listOf("event" to "[Marked done: Taxes]"), false, at(10), 0))
    }

    // ------------------------------------------------------------ Today's 3 and who gets check-ins

    @Test fun pickFocusOrder() {
        val tasks = listOf(
            task(1, priority = 3), task(2, due = at(9, d = 8)), task(3, due = at(18)), task(4, kind = "meeting", due = at(11)),
            task(5, due = at(9, d = 7), blocked = at(12)), task(6, due = at(9, d = 1), parentId = 2), task(7),
        )
        assertEquals(listOf(2L, 3L, 1L), Focus.pickFocus(tasks, at(7, 30)))
        assertEquals(listOf(8L, 7L), Focus.pickFocus(listOf(task(7), task(8, priority = 2)), at(7, 30)))
        assertEquals(emptyList<Long>(), Focus.pickFocus(emptyList(), at(7, 30)))
    }

    @Test fun mayCheckIn() {
        val now = at(10).i
        assertTrue(Focus.mayCheckIn(task(1, kind = "meeting"), now, TODAY))
        assertTrue(Focus.mayCheckIn(task(1, kind = "fence"), now, TODAY))
        assertTrue(Focus.mayCheckIn(task(1, remindRequested = true), now, TODAY))
        assertTrue(Focus.mayCheckIn(task(1, focus = TODAY), now, TODAY))
        assertTrue(Focus.mayCheckIn(task(1, due = at(10).plusHours(47)), now, TODAY))
        assertTrue(Focus.mayCheckIn(task(1, due = at(10).minusDays(3)), now, TODAY))
        assertFalse(Focus.mayCheckIn(task(1, due = at(10).plusHours(49)), now, TODAY))
        assertFalse(Focus.mayCheckIn(task(1), now, TODAY))
        assertFalse(Focus.mayCheckIn(task(1, focus = LocalDate.of(2026, 11, 8)), now, TODAY))
    }

    // ============================================================ test_logic_phase2.py

    private fun focus(id: Long, rank: Int, estimate: Int? = null, status: String = "open", recurrence: String? = null) =
        task(id, focus = TODAY, focusRank = rank, estimate = estimate, status = status, recurrence = recurrence)

    private fun pick(
        tasks: List<com.prominder.myapp.data.TaskRow>, meeting: java.time.ZonedDateTime? = null, lowEnergy: Boolean = false,
        done: Set<Long> = emptySet(), exclude: List<Long> = emptyList(),
    ) = Focus.pickNext(tasks, at(10).i, meeting?.i, lowEnergy = lowEnergy, today = TODAY, doneIds = done, exclude = exclude)

    @Test fun firstFocusTaskWhenItFits() {
        val p = pick(listOf(focus(1, 1, 10), focus(2, 2, 5)), at(11))!!
        assertEquals(listOf<Any?>(1L, "fits", 15, 15), listOf(p.task?.id, p.reason, p.estimate, p.minutes))
    }

    @Test fun neverLongerThanTheTimeToTheNextMeeting() {
        val p = pick(listOf(focus(1, 1, 60), focus(2, 2, 20), focus(3, 3, 10)), at(10, 40))!!
        assertEquals(3L, p.task?.id)
        assertEquals("fits", p.reason)
        assertTrue(p.minutes <= 40 && p.estimate!! <= 40)
    }

    @Test fun sessionLengthIsCappedByTheMeeting() {
        val p = pick(listOf(focus(1, 1, 20)), at(10, 30))!!
        assertEquals("fits" to 25, p.reason to p.minutes)
    }

    @Test fun nothingFitsGivesA5MinuteStartOfTheFirst() {
        val p = pick(listOf(focus(1, 1, 60), focus(2, 2, 45)), at(10, 20))!!
        assertEquals(listOf<Any?>(1L, "short_start", 5), listOf(p.task?.id, p.reason, p.minutes))
    }

    @Test fun noMeetingMeansEverythingFits() {
        val p = pick(listOf(focus(1, 1, 240)))!!
        assertEquals(listOf<Any?>(1L, "fits", 45), listOf(p.task?.id, p.reason, p.minutes))
    }

    @Test fun unknownEstimateCountsAs15Minutes() {
        val p = pick(listOf(focus(1, 1)), at(10, 30))!!
        assertEquals(listOf<Any?>("fits", 23, 25), listOf(p.reason, p.estimate, p.minutes))
    }

    @Test fun lowEnergyPicksTheSmallestShortTask() {
        val p = pick(listOf(focus(1, 1, 60), focus(2, 2, 15), task(3, estimate = 5), task(4)), lowEnergy = true)!!
        assertEquals(listOf<Any?>(3L, "small", 15), listOf(p.task?.id, p.reason, p.minutes))
        assertEquals(2L, pick(listOf(focus(1, 1, 60), focus(2, 2, 15), task(4)), lowEnergy = true)!!.task?.id)
    }

    @Test fun lowEnergyWithNothingSmallOffers5MinutesOfTheTopOne() {
        val p = pick(listOf(focus(1, 1, 60), task(2, estimate = 30)), lowEnergy = true)!!
        assertEquals(listOf<Any?>(1L, "short_start", 5), listOf(p.task?.id, p.reason, p.minutes))
    }

    @Test fun somethingElseGivesTheNextCandidateThenStartsOver() {
        val tasks = listOf(focus(1, 1), focus(2, 2), task(3))
        assertEquals(2L, pick(tasks, exclude = listOf(1))!!.task?.id)
        assertEquals(3L, pick(tasks, exclude = listOf(1, 2))!!.task?.id)
        assertEquals(1L, pick(tasks, exclude = listOf(1, 2, 3))!!.task?.id)
    }

    @Test fun withoutFocusTasksDueSoonestThenPriorityThenOldest() {
        val tasks = listOf(task(1, priority = 3, created = at(7)), task(2, due = at(18)), task(3, due = at(12)),
            task(4, created = at(6)), task(5, priority = 3, created = at(8)))
        val order = mutableListOf<Long>()
        repeat(tasks.size) { order += pick(tasks, exclude = order.toList())!!.task!!.id }
        assertEquals(listOf(3L, 2L, 1L, 5L, 4L), order)
    }

    @Test fun allFocusDoneOffersOneBonusTask() {
        val p = pick(listOf(task(2, estimate = 10)), done = setOf(1))!!
        assertEquals(2L to "bonus", p.task?.id to p.reason)
    }

    @Test fun allFocusDoneAndNothingElseOpen() {
        val p = pick(emptyList(), done = setOf(1))!!
        assertNull(p.task)
        assertEquals("all_done", p.reason)
        assertNull(pick(emptyList()))
    }

    @Test fun skipsMeetingsStepsBlockedAndDoneToday() {
        val tasks = listOf(task(1, kind = "meeting"), task(2, parentId = 9), task(3, blocked = at(12)), focus(4, 1, recurrence = "daily"), task(5))
        assertEquals(5L, pick(tasks, done = setOf(4))!!.task?.id)
    }

    @Test fun whatNowTextsInBothLanguages() {
        val t = task(1, title = "Taxes", nextAction = "Find last year's PDF", focus = TODAY, focusRank = 1, estimate = 10)
        val p = pick(listOf(t))
        assertEquals("Do this: Find last year's PDF — Taxes (15 min).", Texts.whatNowText(p, "en"))
        assertEquals("עכשיו: Find last year's PDF — Taxes (15 דק׳).", Texts.whatNowText(p, "he"))
        val short = pick(listOf(t.copy(estimateMin = 90)), at(10, 20))
        assertEquals("Before 10:20: just 5 minutes of this: Find last year's PDF — Taxes.", Texts.whatNowText(short, "en", at(10, 20)))
        assertTrue(Texts.whatNowText(null, "en").startsWith("Nothing open right now"))
        assertTrue(Texts.whatNowText(pick(emptyList(), done = setOf(1)), "he").startsWith("כל שלוש המשימות"))
    }

    @Test fun stepOfAndSessionText() {
        assertEquals("Gym", Texts.stepOf(task(1, title = "Gym")))
        val t = task(1, title = "Taxes", nextAction = "Open the form")
        assertEquals("⏱ Time's up: Open the form. Done, another round, or stop?", Texts.sessionText("session_end", t, 25, "en"))
        assertTrue(Texts.sessionText("session_end", t, 25, "he").startsWith("⏱ הזמן נגמר: Open the form"))
    }

    @Test fun whatNowChipsNeedNoTaskAndSessionEndChipsDo() {
        val wn = ChipRules.chipsJson(ChipRules.chipsFor("what_now", null), "en", 7)
        assertEquals(listOf(Triple("start", "start", 7L), Triple("low_energy", "what_now", null), Triple("something_else", "what_now", null)),
            wn.map { Triple(it.id, it.action, it.taskId) })
        assertEquals(listOf(7L, 7L, 7L), ChipRules.chipsJson(ChipRules.chipsFor("session_end", null), "en", 7).map { it.taskId })
    }

    // ------------------------------------------------------------ sessions

    @Test fun sessionLengthIsAMultipleOf5() {
        for ((given, length) in listOf(null to 5, 3 to 5, 5 to 5, 7 to 10, 25 to 25, 44 to 45, 500 to 120)) {
            assertEquals("$given", length, FocusSessions.sessionLength(given))
        }
    }

    @Test fun sessionStatesAndTheEndCheckIn() {
        val s = session(at(10))
        assertEquals("running", FocusSessions.sessionState(s, at(10, 24).i))
        assertFalse(FocusSessions.sessionEndDue(s, at(10, 24).i))
        assertEquals("ended", FocusSessions.sessionState(s, at(10, 25).i))
        assertTrue(FocusSessions.sessionEndDue(s, at(10, 25).i))
        assertFalse(FocusSessions.sessionEndDue(session(at(10), endSent = at(10, 25)), at(10, 30).i))
        assertEquals("closed", FocusSessions.sessionState(session(at(10), ended = at(10, 12)), at(10, 30).i))
        assertNull(FocusSessions.sessionState(null, at(10).i))
    }

    @Test fun silence15MinutesAfterTheEndCheckInMeansPaused() {
        val s = session(at(10), endSent = at(10, 28))
        assertFalse(FocusSessions.sessionSilent(s, at(10, 42).i))
        assertTrue(FocusSessions.sessionSilent(s, at(10, 43).i))
        assertTrue(FocusSessions.sessionSilent(session(at(10)), at(10, 40).i))
        assertFalse(FocusSessions.sessionEndDue(session(at(10)), at(10, 40).i))
        assertFalse(FocusSessions.sessionSilent(session(at(10), ended = at(10, 5)), at(12).i))
    }

    @Test fun closingAnOpenSessionWhenAnotherStarts() {
        assertEquals("stopped" to at(10, 10).i, FocusSessions.sessionCloseOutcome(session(at(10)), at(10, 10).i))
        assertEquals("paused" to at(10, 25).i, FocusSessions.sessionCloseOutcome(session(at(10)), at(10, 30).i))
    }

    @Test fun actualMinutes() {
        assertEquals(12, FocusSessions.actualMinutes(session(at(10), ended = at(10, 12)), at(11).i))
        assertEquals(7, FocusSessions.actualMinutes(session(at(10)), at(10, 7).i))
    }

    @Test fun focusHoldUntilTheSessionEndsPlus10() {
        assertEquals(at(10, 35).i, FocusSessions.focusHoldUntil(session(at(10))))
        assertEquals(at(10, 15).i, FocusSessions.focusHoldUntil(session(at(10), ended = at(10, 5))))
        assertNull(FocusSessions.focusHoldUntil(null))
    }

    @Test fun sessionLastActive() {
        assertEquals(at(10, 10).i, FocusSessions.sessionLastActive(session(at(10)), at(10, 10).i))
        assertEquals(at(10, 25).i, FocusSessions.sessionLastActive(session(at(10)), at(11).i))
        assertEquals(at(10, 25).i, FocusSessions.sessionLastActive(session(at(10), ended = at(10, 25)), at(12).i))
    }

    @Test fun breadcrumbOfferedAfterStopOrSilenceOnlyForAWhile() {
        val stopped = session(at(10), ended = at(10, 12), outcome = "stopped")
        assertTrue(FocusSessions.wantsBreadcrumb(stopped, at(11).i))
        assertTrue(FocusSessions.wantsBreadcrumb(stopped.copy(outcome = "paused"), at(11).i))
        assertFalse(FocusSessions.wantsBreadcrumb(stopped.copy(outcome = "done"), at(11).i))
        assertFalse(FocusSessions.wantsBreadcrumb(session(at(10), ended = at(10, 12), outcome = "stopped", noteClosed = at(10, 13)), at(11).i))
        assertFalse(FocusSessions.wantsBreadcrumb(stopped, at(13, 12).i))
        assertFalse(FocusSessions.wantsBreadcrumb(session(at(10)), at(10, 5).i))
    }

    // ------------------------------------------------------------ the focus hold in contact_decision

    private val sessionEnd = task(1, kind = "session", remindRequested = true)

    @Test fun noCheckInDuringASessionButMeetingsAndItsEndComeThrough() {
        val st = ContactState(focusHoldUntil = at(10, 35).i)
        assertEquals(at(10, 35).i, Contacts.contactDecision(task(1), st, at(10, 10), G))
        assertEquals(at(10, 35).i, Contacts.contactDecision(task(1, remindRequested = true), st, at(10, 10), G))
        assertNull(Contacts.contactDecision(task(2, kind = "meeting"), st, at(10, 10), G))
        assertNull(Contacts.contactDecision(task(3, kind = "fence"), st, at(10, 10), G))
        assertNull(Contacts.contactDecision(sessionEnd, st, at(10, 25), G))
        assertNull(Contacts.contactDecision(task(1), st, at(10, 36), G))
    }

    @Test fun sessionEndSkipsTheGapAndTaskCapButNotQuietHoursOrTheDailyCap() {
        val busy = ContactState(lastProactiveAt = at(10, 20).i, sentToday = 3, sentTodayByTask = mapOf(1L to 9))
        assertNull(Contacts.contactDecision(sessionEnd, busy, at(10, 25), G))
        assertEquals(at(7, d = 10).i, Contacts.contactDecision(sessionEnd, ContactState(), at(23, 10), G))
        assertNotNull(Contacts.contactDecision(sessionEnd, ContactState(sentToday = 20), at(10, 25), G))
    }

    @Test fun gapStillAppliesToOrdinaryCheckIns() =
        assertEquals(at(10, 20).i, Contacts.contactDecision(task(1), ContactState(lastProactiveAt = at(10).i, sentToday = 1), at(10, 10), G))

    @Test fun holdInThePastChangesNothing() =
        assertNull(Contacts.contactDecision(task(1), ContactState(focusHoldUntil = at(9).i), at(10), G))

    @Test fun durationSanity() = assertEquals(at(10, 25), at(10).plus(Duration.ofMinutes(25)))
}
