package com.prominder.myapp.data

import androidx.test.core.app.ApplicationProvider
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.logic.Logic.URGENT
import com.prominder.myapp.remind.Notice
import com.prominder.myapp.remind.Ticker
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Focus sessions end to end on a real (in-memory) database: Sessions.kt (actions.py) and the tick's end check-in
 * (scheduler.plan_sessions), ported from backend/tests/test_phase2_flow.py. Monday 9 Nov 2026, Israel, from 10:00.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SessionsTest {
    private val tz = ZoneId.of("Asia/Jerusalem")
    private var now: Instant = local(9, 10)
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private var userActivity = 0
    private val store = Store(db, clock = { now }, onUserActivity = { userActivity++ })
    private val ticker = Ticker(store)
    private val dao = db.dao()
    private var nextId = 0L
    private val notices = mutableListOf<Notice>()

    private fun local(d: Int, h: Int, mi: Int = 0): Instant = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, tz).toInstant()
    private fun t(i: Instant?) = i?.let(Times::format)
    private fun advance(minutes: Long) { now = now.plus(Duration.ofMinutes(minutes)) }
    private fun focusDay() = Logic.focusDay(now.atZone(tz), LocalTime.of(4, 0)).toString()

    @After
    fun tearDown() = db.close()

    private fun add(
        title: String, focus: Boolean = false, kind: String = "task", priority: Int = 1, due: Instant? = null, remind: Instant? = null,
        requested: Boolean = false, nextAction: String? = null, resumeNote: String? = null,
    ): TaskRow = runBlocking {
        val row = TaskRow(++nextId, title = title, kind = kind, priority = priority, dueAt = t(due), remindAt = t(remind),
            remindRequested = requested, nextAction = nextAction, resumeNote = resumeNote, createdAt = t(now)!!,
            focusDate = if (focus) focusDay() else null)
        dao.upsertTasks(listOf(row))
        row
    }

    private fun task(id: Long) = runBlocking { dao.task(id)!! }
    private fun sessions() = runBlocking { dao.sessionsSince("0").sortedBy { it.id } }
    private fun messages() = runBlocking { dao.messagesSince("0").sortedBy { it.id } }
    private fun lastMessage() = messages().last()
    private fun events(id: Long) = runBlocking { dao.eventsSince(id, "0").sortedBy { it.createdAt } }

    /** A tick (task check-ins and session ends, no daily jobs): the new assistant messages. */
    private fun tick(): List<MessageRow> = runBlocking {
        val before = messages().map { it.id }.toSet()
        notices += ticker.tickTasksOnly().notices
        messages().filter { it.id !in before && it.role == "assistant" }
    }

    private fun chips(m: MessageRow) = Json.parseToJsonElement(m.chips ?: "[]").jsonArray.map {
        it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["task_id"]?.jsonPrimitive?.long
    }

    @Test fun `start writes the event in the chat and counts as you answering`() {
        val t = add("Taxes", nextAction = "Open the form", focus = true)
        val s = runBlocking { store.startSession(t.id, 5) }!!
        assertEquals(5, s.plannedMin)
        assertEquals(local(9, 10, 5), Logic.sessionEndAt(s))
        assertEquals("[▶ 5 min: Open the form (Taxes)]", lastMessage().content)
        assertEquals("event", lastMessage().kind)
        assertEquals(1, userActivity)
        assertEquals("session_started", events(t.id).last().kind)
        assertEquals("5 min", events(t.id).last().note)
        // A closed task can't start, and a length is a multiple of 5 up to 120.
        val closed = add("Old").let { runBlocking { store.complete(it.id); it } }
        assertNull(runBlocking { store.startSession(closed.id, 5) })
        assertEquals(10, runBlocking { store.startSession(t.id, 7) }!!.plannedMin)
        assertEquals(120, runBlocking { store.startSession(t.id, 500) }!!.plannedMin)
    }

    @Test fun `the session's end check-in, then silence means paused`() {
        val t = add("Taxes", nextAction = "Open the form", focus = true, priority = 2)
        runBlocking { store.startSession(t.id, 5) }
        advance(4)
        assertTrue(tick().isEmpty()) // still running: nothing
        advance(1) // the alarm rings at the end itself (10:05)
        val msg = tick().single()
        assertEquals("session" to listOf(t.id), msg.kind to msg.taskIds)
        assertEquals("⏱ Time's up: Open the form. Done, another round, or stop?", msg.content)
        assertEquals(listOf("done" to t.id, "another" to t.id, "stop" to t.id), chips(msg))
        val n = notices.single()
        assertEquals("⏱ Taxes", n.title)
        assertEquals(URGENT, n.level)
        assertEquals(t.id, n.taskId)
        assertEquals(listOf("done", "another"), n.actions)
        assertNotNull(sessions().single().endSentAt)

        val sent = messages().size
        advance(10)
        tick()
        assertNull(sessions().single().outcome) // 10 minutes of silence: still waiting
        advance(5)
        tick() // 15 minutes after the check-in
        val s = sessions().single()
        assertEquals("paused" to t(local(9, 10, 5)), s.outcome to s.endedAt) // actual = planned on silence
        assertEquals("paused" to "paused, 5 of 5 min", events(t.id).last().kind to events(t.id).last().note)
        assertEquals(sent, messages().size) // nothing more is sent
        assertEquals(focusDay() to OPEN, task(t.id).focusDate to task(t.id).status)
        repeat(6) { advance(10); tick() }
        assertEquals(sent, messages().size)
    }

    @Test fun `no other check-in during a session, but a meeting comes through`() {
        val focusTask = add("Taxes", focus = true)
        val other = add("Call the bank", remind = local(9, 10, 10), focus = true)
        val meeting = add("Team sync", kind = "meeting", due = local(9, 10, 30), remind = local(9, 10, 15))
        runBlocking { store.startSession(focusTask.id, 25) } // 10:00–10:25, hold until 10:35
        val seen = mutableListOf<MessageRow>()
        repeat(6) { advance(5); seen += tick() } // 10:05 … 10:30
        assertEquals(listOf("meeting", "session", "meeting"), seen.map { it.kind }) // 10:15, 10:25 end, 10:30 start
        assertTrue(seen.none { other.id in it.taskIds })
        assertEquals(t(local(9, 10, 35)), task(other.id).remindAt)
        assertTrue(meeting.id in seen[0].taskIds)
        advance(5)
        tick() // 10:35: the hold is over, but the 10:30 meeting started the gap
        assertEquals(t(local(9, 10, 50)), task(other.id).remindAt)
        now = local(9, 10, 50)
        assertTrue(other.id in tick().single().taskIds)
    }

    @Test fun `the task's own check-in inside the session is replaced by the end check-in`() {
        val t = add("Taxes", remind = local(9, 10, 10), focus = true)
        val later = add("Dentist", remind = local(9, 17), requested = true)
        runBlocking { store.startSession(t.id, 25) }
        runBlocking { store.startSession(later.id, 5) } // a new session closes the running one
        assertNull(task(t.id).remindAt)
        assertEquals(t(local(9, 17)), task(later.id).remindAt) // outside the session: kept
        assertEquals(listOf("stopped", null), sessions().map { it.outcome })
    }

    @Test fun `Done at the session's end closes the session and the task, and clears where you stopped`() {
        val t = add("Taxes", focus = true, resumeNote = "page 3")
        runBlocking { store.startSession(t.id, 5) }
        advance(7)
        tick()
        runBlocking { store.complete(t.id) } // ✓ Done on the notification
        val s = sessions().single()
        assertEquals("done" to t(now), s.outcome to s.endedAt) // actual runs until the tap
        assertEquals(DONE, task(t.id).status)
        assertNull(task(t.id).resumeNote)
        assertEquals("[Marked done: Taxes]", lastMessage().content)
        assertEquals("session_ended" to "done, 7 of 5 min", events(t.id).first { it.kind == "session_ended" }.let { it.kind to it.note })
    }

    @Test fun `another round is as long as the last one, from the timer or after it went silent`() {
        val t = add("Taxes", nextAction = "Open the form", focus = true)
        val first = runBlocking { store.startSession(t.id, Logic.START_MIN_APP) }!!
        advance(27)
        val next = runBlocking { store.endSession(first.id, "another") }!!
        assertEquals(25, next.plannedMin)
        assertTrue(next.id != first.id)
        assertEquals("another", sessions()[0].outcome)
        assertEquals("[▶ Another round, 25 min: Open the form (Taxes)]", lastMessage().content)
        // From the notification, after the round already went silent: still a new round of the same length.
        advance(60)
        tick()
        tick()
        assertEquals("paused", sessions()[1].outcome)
        runBlocking { store.startSession(t.id, another = true) }
        assertEquals(25 to null, sessions().last().plannedMin to sessions().last().endedAt)
    }

    @Test fun `Stop offers where did you stop, and the next session and check-in use it`() {
        val t = add("Taxes", nextAction = "Fill in column F", focus = true)
        val s = runBlocking { store.startSession(t.id, 25) }!!
        advance(12)
        runBlocking { store.endSession(s.id, "stopped") }
        assertEquals("[Stopped after 12 min: Taxes]", lastMessage().content)
        val latest = runBlocking { dao.latestSession() }
        assertTrue(Logic.wantsBreadcrumb(latest, now))

        runBlocking { store.saveBreadcrumb(s.id, "row 40") }
        assertEquals("row 40", task(t.id).resumeNote)
        assertEquals("[Where I stopped: row 40]", lastMessage().content)
        assertFalse(Logic.wantsBreadcrumb(runBlocking { dao.latestSession() }, now))

        // Used by the next check-in's template too.
        runBlocking { dao.upsertTasks(listOf(task(t.id).copy(remindAt = t(now.plus(Duration.ofMinutes(30))), remindRequested = true))) }
        advance(30)
        assertTrue(tick().single().content.contains("You stopped at: row 40"))
    }

    @Test fun `ignoring where did you stop changes nothing, and it goes away by itself`() {
        val t = add("Taxes", focus = true)
        val s = runBlocking { store.startSession(t.id, 5) }!!
        advance(30)
        tick() // silence → paused
        assertTrue(Logic.wantsBreadcrumb(runBlocking { dao.latestSession() }, now))
        advance(3 * 60)
        assertFalse(Logic.wantsBreadcrumb(runBlocking { dao.latestSession() }, now)) // gone by itself
        runBlocking { store.saveBreadcrumb(s.id, null) } // Skip is fine too
        assertNull(task(t.id).resumeNote)
    }

    @Test fun `finishing the task another way ends the running session, and no end check-in comes`() {
        val t = add("Taxes", focus = true)
        runBlocking { store.startSession(t.id, 25) }
        advance(8)
        runBlocking { store.complete(t.id, chatEvent = false) } // the assistant's complete_task
        assertEquals("done", sessions().single().outcome)
        advance(30)
        assertTrue(tick().isEmpty())
    }

    @Test fun `the length can change, and +5 after the end check-in brings it back at the new end`() {
        val t = add("Taxes", focus = true)
        val s = runBlocking { store.startSession(t.id, 25) }!!
        assertEquals(45, runBlocking { store.setSessionLength(s.id, 45) }!!.plannedMin)
        advance(46)
        tick()
        assertNotNull(sessions().single().endSentAt)
        val longer = runBlocking { store.setSessionLength(s.id, 50) }!!
        assertNull(longer.endSentAt)
        advance(4)
        assertEquals("session", tick().single().kind) // the new end
        runBlocking { store.endSession(s.id, "stopped") }
        assertNull(runBlocking { store.setSessionLength(s.id, 30) }) // already ended
    }

    @Test fun `a session's end in quiet hours is never sent and becomes paused`() {
        now = local(9, 22, 50)
        val t = add("Read", focus = true)
        runBlocking { store.startSession(t.id, 15) } // ends 23:05, inside quiet hours
        val sent = mutableListOf<MessageRow>()
        repeat(6) { advance(5); sent += tick() }
        assertTrue(sent.none { it.kind == "session" })
        assertTrue(notices.isEmpty())
        assertEquals("paused", sessions().single().outcome)
    }

    @Test fun `a reply button starts and stops a session, and the countdown's next wake is the session's end`() {
        val t = add("Taxes", focus = true)
        runBlocking { store.startSession(t.id, 5) }
        val wake = runBlocking { ticker.nextWake() }
        assertEquals(local(9, 10, 5), wake)
        assertEquals(t.id, runBlocking { store.currentSession() }!!.task!!.id)
        runBlocking { store.stopSession(t.id) }
        assertNull(runBlocking { store.currentSession() })
        assertEquals("stopped", sessions().single().outcome)
    }
}
