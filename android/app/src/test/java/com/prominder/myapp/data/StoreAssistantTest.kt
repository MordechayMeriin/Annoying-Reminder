package com.prominder.myapp.data

import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The assistant's changes and the multi-task reply buttons (StoreAssistant.kt) on a real database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StoreAssistantTest {
    private val tz = ZoneId.of("Asia/Jerusalem")
    private var now: Instant = local(17, 10)
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = Store(db, clock = { now })
    private val dao = db.dao()

    private fun local(d: Int, h: Int, mi: Int = 0): Instant = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, tz).toInstant()
    private fun t(i: Instant) = Times.format(i)

    @After
    fun tearDown() = db.close()

    @Test fun `a new time resets the nagging and ends a park, a new description keeps the old`() = runTest {
        val task = store.createTask("Report", description = "first draft")
        dao.upsertTasks(listOf(dao.task(task.id)!!.copy(nagCount = 3, escalated = true, parkedUntil = t(local(20, 9)))))
        val new = store.updateTask(task.id, mapOf("due_at" to local(18, 12), "description" to "second draft", "priority" to 2))!!
        assertEquals(t(local(18, 12)), new.dueAt)
        assertEquals(t(local(18, 12)), new.remindAt)
        assertTrue(new.remindRequested)
        assertEquals(0, new.nagCount)
        assertFalse(new.escalated)
        assertNull(new.parkedUntil)
        assertEquals(2, new.priority)
        assertEquals(listOf("description_changed" to "first draft"), dao.eventsSince(task.id, "").map { it.kind to it.note })
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.updateTask(task.id, mapOf("priority" to 7)) } }
    }

    @Test fun `a meeting created by the assistant reminds before it starts`() = runTest {
        val m = store.createTask("Standup", kind = "meeting", dueAt = local(18, 9))
        assertEquals(t(local(18, 8, 45)), m.remindAt)
    }

    @Test fun `move overdue to next week, without postponed events`() = runTest {
        val late = store.createTask("Taxes", dueAt = local(16, 9))
        val ok = store.createTask("Later", dueAt = local(19, 9))
        val moved = store.moveOverdue()
        assertEquals(listOf(late.id), moved.map { it.id })
        assertEquals(t(local(23, 9)), dao.task(late.id)!!.dueAt)
        assertEquals(t(local(19, 9)), dao.task(ok.id)!!.dueAt)
        assertTrue(dao.eventsSince(late.id, "").isEmpty())
        assertEquals("[Moved 1 overdue task to next week: Taxes]", dao.messagesSince("").single().content)
    }

    @Test fun `drop them and keep`() = runTest {
        val a = store.createTask("Old A")
        val b = store.createTask("Old B")
        store.drop(listOf(a.id))
        store.keep(listOf(b.id))
        assertEquals(CANCELLED, dao.task(a.id)!!.status)
        assertEquals(OPEN, dao.task(b.id)!!.status)
        assertEquals(listOf("[Dropped: Old A]", "[Kept: Old B]"), dao.messagesSince("").sortedBy { it.id }.map { it.content })
    }

    @Test fun `not now parks a task for a week`() = runTest {
        val a = store.createTask("Taxes")
        store.setFocus(listOf(a.id), java.time.LocalDate.of(2026, 11, 17))
        val until = store.notNow(a.id)!!
        val row = dao.task(a.id)!!
        assertEquals(t(local(24, 10)), row.parkedUntil)
        assertEquals(t(until), row.remindAt)
        assertNull(row.focusDate)
        assertEquals("parked", dao.eventsSince(a.id, "").single().kind)
    }

    @Test fun `what now answers with one task and its buttons`() = runTest {
        store.createTask("Write the report", nextAction = "Open the doc", estimateMin = 20)
        val reply = store.whatNow()
        val msgs = dao.messagesSince("").sortedBy { it.id }
        assertEquals("[What now?]", msgs[0].content)
        assertEquals(reply.id, msgs[1].id)
        assertTrue(reply.content.contains("Open the doc"))
        val chips = Chips.decode(reply.chips)
        assertEquals(listOf("start", "low_energy", "something_else"), chips.map { it.id })
        assertEquals(true, chips[1].lowEnergy)
        assertEquals(listOf(1L), chips[2].exclude)
    }

    @Test fun `preferences are one line each, without repeats, at most 20`() = runTest {
        store.addPreference("  shorter   messages ")
        store.addPreference("Shorter messages")
        assertEquals(listOf("shorter messages"), dao.preferences().map { it.text })
        repeat(19) { store.addPreference("rule $it") }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.addPreference("one more") } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { store.addPreference("   ") } }
    }

    @Test fun `chips keep What now's extra fields the server stored`() {
        val text = """[{"id": "something_else", "label": "Something else", "action": "what_now", "exclude": [3, 4], "low_energy": false}]"""
        val chips = Chips.decode(text)
        assertEquals(listOf(3L, 4L), chips[0].exclude)
        assertEquals(text, Chips.encode(chips))
    }
}
