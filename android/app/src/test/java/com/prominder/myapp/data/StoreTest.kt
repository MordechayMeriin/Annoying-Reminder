package com.prominder.myapp.data

import androidx.test.core.app.ApplicationProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The task operations on a real (in-memory) Room database, checked against what backend/app/tasks.py and
 * actions.py do (their tests: test_tools.py, test_phase1_flow.py, test_review_fixes.py).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StoreTest {
    private val tz = ZoneId.of("Asia/Jerusalem")
    private var now: Instant = local(2026, 11, 17, 10)
    private var changes = 0
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = Store(db, clock = { now }, onChange = { changes++ })
    private val dao = db.dao()

    private fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Instant = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, tz).toInstant()

    @After
    fun tearDown() = db.close()

    @Test
    fun `a new meeting reminds before it starts, a task at its time`() = runTest {
        val meeting = store.create(TaskForm("Standup", kind = "meeting", dueAt = local(2026, 11, 18, 9)))
        assertEquals(Times.format(local(2026, 11, 18, 8, 45)), meeting.remindAt)
        assertTrue(meeting.remindRequested)
        val task = store.create(TaskForm("Pay bill", dueAt = local(2026, 11, 18, 12)))
        assertEquals(task.dueAt, task.remindAt)
        val undated = store.create(TaskForm("  Read book  ", recurrence = "daily"))
        assertEquals("Read book", undated.title)
        assertNull(undated.remindAt)
        assertNull(undated.recurrence) // a repeat needs a date
        assertEquals(listOf(1L, 2L, 3L), listOf(meeting.id, task.id, undated.id))
        assertEquals(3, changes)
    }

    @Test
    fun `ids continue after the backup's counter`() = runTest {
        dao.setMeta(MetaRow(Store.META_TASK_COUNTER, "41"))
        assertEquals(42L, store.create(TaskForm("x")).id)
        assertEquals(43L, store.create(TaskForm("y")).id)
    }

    @Test
    fun `a new time resets the nagging and ends a park`() = runTest {
        val t = store.create(TaskForm("Call", dueAt = local(2026, 11, 18, 9)))
        dao.upsertTasks(listOf(t.copy(nagCount = 3, escalated = true, parkedUntil = Times.format(local(2026, 11, 20, 9)))))
        val form = TaskForm("Call", dueAt = local(2026, 11, 19, 9))
        val edited = store.edit(t.id, form)!!
        assertEquals(0, edited.nagCount)
        assertFalse(edited.escalated)
        assertNull(edited.parkedUntil)
        assertEquals(Times.format(local(2026, 11, 19, 9)), edited.remindAt)
        // Only the title changed: the nag state stays.
        dao.upsertTasks(listOf(edited.copy(nagCount = 2)))
        assertEquals(2, store.edit(t.id, form.copy(title = "Call mom"))!!.nagCount)
    }

    @Test
    fun `changing the description keeps the old text in the history`() = runTest {
        val t = store.create(TaskForm("Report", description = "First draft"))
        store.setDescription(t.id, "Final")
        val events = eventsOf(t.id)
        assertEquals(listOf("description_changed"), events.map { it.kind })
        assertEquals("First draft", events.single().note)
    }

    @Test
    fun `done closes the task and its open steps, and says so in the chat`() = runTest {
        val parent = store.create(TaskForm("Report"))
        val step = store.create(TaskForm("Outline"), parentId = parent.id)
        val done = store.complete(parent.id)!!
        assertEquals(DONE, done.status)
        assertEquals(Times.format(now), done.completedAt)
        assertNull(done.remindAt)
        assertEquals(DONE, dao.task(step.id)!!.status)
        assertEquals(listOf("done"), eventsOf(parent.id).map { it.kind })
        val msg = messages().single()
        assertEquals("[Marked done: Report]", msg.content)
        assertEquals("user" to "event", msg.role to msg.kind)
        assertEquals(listOf(parent.id), msg.taskIds)
        // A second tap changes nothing.
        store.complete(parent.id)
        assertEquals(1, messages().size)
    }

    @Test
    fun `a recurring task rolls on to its next time instead of closing`() = runTest {
        val t = store.create(TaskForm("Standup", kind = "meeting", dueAt = local(2026, 11, 12, 9), recurrence = "weekdays"))
        val rolled = store.complete(t.id)!!
        assertEquals(OPEN, rolled.status)
        // Thursday 12th is long past on the 17th: the next Sun–Thu day after now, same time, same lead.
        assertEquals(Times.format(local(2026, 11, 18, 9)), rolled.dueAt)
        assertEquals(Times.format(local(2026, 11, 18, 8, 45)), rolled.remindAt)
        assertEquals("[Marked done: Standup — next one scheduled]", messages().single().content)
    }

    @Test
    fun `a hard stop can't be done or postponed, only cancelled`() = runTest {
        val fence = store.create(TaskForm("Stop work", kind = "fence", dueAt = local(2026, 11, 17, 18)))
        assertEquals(OPEN, store.complete(fence.id)!!.status)
        assertNull(store.later(fence.id))
        assertEquals(CANCELLED, store.cancel(fence.id)!!.status)
    }

    @Test
    fun `later is decided by code and counts as postponed`() = runTest {
        val t = store.create(TaskForm("Email"))
        val at = store.later(t.id)!!
        assertEquals(local(2026, 11, 17, 12), at) // 10:00 + 2 hours
        val row = dao.task(t.id)!!
        assertEquals(Times.format(at), row.remindAt)
        assertTrue(row.remindRequested)
        assertEquals("postponed" to "later (button)", eventsOf(t.id).single().let { it.kind to it.note })
        assertEquals("[Later: Email — next check-in Tue 12:00]", messages().single().content)

        now = local(2026, 11, 17, 19) // after 18:00: tomorrow's morning plan
        assertEquals(local(2026, 11, 18, 7, 30), store.later(t.id))
    }

    @Test
    fun `cancel takes the open steps with it`() = runTest {
        val parent = store.create(TaskForm("Trip"))
        val step = store.create(TaskForm("Book"), parentId = parent.id)
        val doneStep = store.create(TaskForm("Pack"), parentId = parent.id)
        store.complete(doneStep.id)
        store.cancel(parent.id)
        assertEquals(CANCELLED, dao.task(parent.id)!!.status)
        assertEquals(CANCELLED, dao.task(step.id)!!.status)
        assertEquals(DONE, dao.task(doneStep.id)!!.status)
        assertEquals("[Cancelled: Trip]", messages().last().content)
    }

    @Test
    fun `Today's 3 takes open tasks only, at most three, in order`() = runTest {
        val day = LocalDate.of(2026, 11, 17)
        val ids = (1..5).map { store.create(TaskForm("T$it")).id }
        val meeting = store.create(TaskForm("M", kind = "meeting", dueAt = local(2026, 11, 17, 15)))
        val picked = store.setFocus(listOf(ids[2], ids[0], ids[1], ids[3]), day)
        assertEquals(listOf(ids[2], ids[0], ids[1]), picked.map { it.id })
        assertEquals(listOf(1, 2, 3), picked.map { it.focusRank })
        assertEquals(listOf(ids[2], ids[0], ids[1]), store.focusIds(day))
        // As on the server, the first three asked for count; a meeting among them is left out.
        assertEquals(listOf(ids[2], ids[0]), store.setFocus(listOf(ids[2], meeting.id, ids[0], ids[1]), day).map { it.id })

        // A new pick replaces the old one.
        store.setFocus(listOf(ids[4]), day)
        assertEquals(listOf(ids[4]), store.focusIds(day))
        assertNull(dao.task(ids[2])!!.focusDate)
    }

    @Test
    fun `the star adds and removes, and says when it's full`() = runTest {
        val ids = (1..4).map { store.create(TaskForm("T$it")).id }
        ids.take(3).forEach { assertTrue(store.toggleFocus(it)) }
        assertFalse(store.toggleFocus(ids[3]))
        assertTrue(store.toggleFocus(ids[0]))
        assertEquals(ids.subList(1, 3), store.focusIds(store.focusDay(store.settings())))
    }

    @Test
    fun `settings are checked, stored like the server's, and a reset is remembered for the backup`() = runTest {
        store.saveSetting("morning_time", "8:00")
        assertEquals("08:00", store.settings().raw("morning_time"))
        store.saveSetting("morning_time", null)
        assertEquals("07:30", store.settings().raw("morning_time"))
        assertEquals(listOf("settings" to "\"morning_time\""), dao.changedTombstones(0, Long.MAX_VALUE).map { it.table to it.key })
        try {
            store.saveSetting("morning_time", "noon")
            throw AssertionError("expected a SettingException")
        } catch (e: SettingException) {
            assertEquals("use HH:MM, e.g. 07:30", e.message)
        }
    }

    @Test
    fun `every change gets a new change number, so the backup knows what to send`() = runTest {
        val a = store.create(TaskForm("A"))
        val b = store.create(TaskForm("B"))
        assertEquals(1L, dao.task(a.id)!!.seq)
        assertEquals(2L, dao.task(b.id)!!.seq)
        store.complete(a.id)
        assertEquals(listOf(a.id), dao.changedTasks(2, 3).map { it.id })
        assertEquals(1, dao.changedMessages(2, 3).size)
        assertEquals(1, dao.changedEvents(2, 3).size)
    }

    private suspend fun eventsOf(taskId: Long) = dao.changedEvents(0, Long.MAX_VALUE).filter { it.taskId == taskId }
    private suspend fun messages() = dao.changedMessages(0, Long.MAX_VALUE).sortedBy { it.id }
}
