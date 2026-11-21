package com.prominder.myapp.llm

import com.prominder.myapp.logic.Schedule
import androidx.test.core.app.ApplicationProvider
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.Chips
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.remind.Ticker
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The coach writing the tick's messages: the LLM's words when it answers, the template when it doesn't, code's facts
 * either way. The cases of test_model_tag.py and the LLM paths of test_tick_flow.py and test_phase*_flow.py (the
 * Python server's tests, removed in b09e82e).
 * Mon 9 Nov 2026, Israel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CoachTest {
    private val tz = ZoneId.of("Asia/Jerusalem")
    private var now: Instant = local(9, 10)
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = Store(db, clock = { now })
    private val dao = db.dao()
    private val prompts = testPrompts()
    private var nextId = 0L

    private fun local(d: Int, h: Int, mi: Int = 0): Instant = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, tz).toInstant()
    private fun t(i: Instant?) = i?.let(Times::format)

    @After
    fun tearDown() = db.close()

    private fun add(title: String, kind: String = "task", focus: Boolean = false, due: Instant? = null, remind: Instant? = null,
                    requested: Boolean = false, nextAction: String? = null, priority: Int = 1): TaskRow = runBlocking {
        val row = TaskRow(++nextId, title = title, kind = kind, priority = priority, dueAt = t(due), remindAt = t(remind), remindRequested = requested,
            nextAction = nextAction, createdAt = t(now)!!,
            focusDate = if (focus) Schedule.focusDay(now.atZone(tz), LocalTime.of(4, 0)).toString() else null)
        dao.upsertTasks(listOf(row))
        row
    }

    private fun tick(llm: Llm, jobs: Boolean = false) = runBlocking {
        val ticker = Ticker(store, llm, prompts)
        if (jobs) ticker.tick() else ticker.tickTasksOnly()
    }

    private fun sent(): List<MessageRow> = runBlocking { dao.messagesSince("").filter { it.role == "assistant" }.sortedBy { it.id } }

    @Test fun `a check-in by the LLM carries its words and model, the template doesn't`() {
        add("Send the invoice", focus = true, remind = now, nextAction = "Open the invoice template")
        val llm = ScriptedLlm(listOf(says("How's the invoice going? Open the template first?")), model = "google/gemini-2.5-flash")
        val report = tick(llm)
        val m = sent().single()
        assertEquals("How's the invoice going? Open the template first?", m.content)
        assertEquals("google/gemini-2.5-flash", m.model)
        assertEquals(listOf("start5", "done", "later", "stuck"), Chips.decode(m.chips).map { it.id })
        assertTrue(report.lines.single().endsWith("written by google/gemini-2.5-flash"))
        // What the model was told: the task with its next action, and the buttons.
        val user = llm.content(0, 1)
        assertTrue(user.contains("#1 \"Send the invoice\"") && user.contains("Next action: Open the invoice template"))
        assertTrue(llm.content(0, 0).contains("▶ Start 5 min · ✓ Done · Later · I'm stuck"))

        now = now.plus(Duration.ofDays(1))
        add("Pay the bill", remind = now, due = now)
        tick(ScriptedLlm(listOf(Fails(LlmUnavailable("502")))))
        val second = sent().last()
        assertTrue(second.content.contains("Pay the bill"))
        assertNull(second.model)
    }

    @Test fun `a check-in whose task was finished while it was written doesn't mention it`() {
        val report = add("Write the report", focus = true, remind = now)
        add("Call the bank", focus = true, remind = now)
        // While the model writes about both, the report is marked done (a tap on the phone).
        val llm = Llm { _, _, _, _ ->
            store.complete(report.id, chatEvent = false)
            LlmReply("Report and bank: which one first?", emptyList(), "test/model", message("assistant", "Report and bank: which one first?"))
        }
        val r = tick(llm)
        val m = sent().singleOrNull() ?: error(r.lines.joinToString("\n"))
        assertEquals(listOf(2L), m.taskIds)
        assertTrue(m.content, "Call the bank" in m.content && "report" !in m.content.lowercase())
        assertNull(m.model)   // rewritten from the template for the one still open
        assertTrue(r.lines.any { it.startsWith("Check-in rewritten: #1") })
    }

    @Test fun `messages that fall in one tick are written side by side`() {
        add("Write the report", focus = true, remind = local(9, 8, 0))
        now = local(9, 8, 0)   // the morning plan and a check-in together
        var running = 0
        var most = 0
        val llm = Llm { _, _, _, _ ->
            running++
            most = maxOf(most, running)
            kotlinx.coroutines.delay(200)
            running--
            LlmReply("Written.", emptyList(), "test/model", message("assistant", "Written."))
        }
        tick(llm, jobs = true)
        assertEquals(2, most)
    }

    @Test fun `meeting lines come from code, the LLM only adds around them`() {
        add("Standup", kind = "meeting", due = local(9, 10, 15), remind = now, requested = true)
        tick(ScriptedLlm(listOf(says("Anything to prepare?"))))
        assertEquals("📅 Standup — 10:15 (in 15 min)\nAnything to prepare?", sent().single().content)
    }

    @Test fun `a message with only a hard stop never asks the LLM`() {
        add("Pick up the kids", kind = "fence", due = now, remind = now, requested = true)
        val llm = ScriptedLlm(listOf(says("never")))
        tick(llm)
        assertEquals(0, llm.requests.size)
        assertTrue(sent().single().content.startsWith("⛔"))
    }

    @Test fun `the coach may choose the next check-in, clamped by code`() {
        val task = add("Write the report", focus = true, remind = now)
        tick(ScriptedLlm(listOf(
            calls("schedule_followup", "task_id" to task.id, "minutes_from_now" to 3, "reason" to "soon"),
            says("Start with the outline?"),
        )))
        assertEquals(t(now.plus(Duration.ofMinutes(10))), runBlocking { dao.task(task.id)!! }.remindAt) // at least 10 minutes away
    }

    @Test fun `an avoided task's reason message keeps code's 3 days`() {
        val task = add("Taxes", focus = true, remind = now)
        runBlocking {
            repeat(3) { dao.upsertEvents(listOf(com.prominder.myapp.data.EventRow(task.id, "postponed", null, null, t(now.minusSeconds(60L * (it + 1)))!!, "r$it"))) }
        }
        val llm = ScriptedLlm(listOf(calls("schedule_followup", "task_id" to task.id, "minutes_from_now" to 60, "reason" to "x"),
            says("This keeps slipping, and that's fine. What's in the way?")))
        tick(llm)
        assertTrue(llm.content(0, 1).contains("NOT an ordinary check-in"))
        assertEquals(listOf("too_big", "unclear", "boring", "not_now"), Chips.decode(sent().single().chips).map { it.id })
        assertEquals(t(now.plus(Duration.ofDays(3))), runBlocking { dao.task(task.id)!! }.remindAt)
    }

    @Test fun `the morning plan by the LLM picks Today's 3 with set_focus`() {
        now = local(10, 7, 30)
        val a = add("Report", priority = 2)
        val b = add("Groceries")
        val llm = ScriptedLlm(listOf(calls("set_focus", "task_ids" to listOf(b.id)), says("☀️ Today: Groceries first.")))
        tick(llm, jobs = true)
        val m = sent().single { it.kind == "morning" }
        assertEquals("☀️ Today: Groceries first.", m.content)
        assertEquals(listOf(b.id), m.taskIds)
        assertEquals("2026-11-10", runBlocking { dao.task(b.id)!! }.focusDate)
        assertNull(runBlocking { dao.task(a.id)!! }.focusDate)
        assertTrue(llm.content(0, 1).contains("Suggested Today's 3"))
    }

    @Test fun `without the LLM the morning plan is code's pick, saved`() {
        now = local(10, 7, 30)
        val a = add("Report", priority = 2)
        tick(ScriptedLlm(listOf(Fails(LlmUnavailable("down")))), jobs = true)
        val m = sent().single { it.kind == "morning" }
        assertTrue(m.content.startsWith("☀️ Today's 3:\n1. Report"))
        assertNull(m.model)
        assertEquals("2026-11-10", runBlocking { dao.task(a.id)!! }.focusDate)
    }

    @Test fun `the evening review by the LLM`() {
        now = local(9, 21, 30)
        add("Report", focus = true)
        val llm = ScriptedLlm(listOf(says("🌙 Nice day. The report carries over.")))
        tick(llm, jobs = true)
        assertEquals("🌙 Nice day. The report carries over.", sent().single { it.kind == "evening" }.content)
        assertEquals(0.5, llm.requests.single().temperature, 0.0)
    }

    @Test fun `a task done while the message was being written isn't contacted about`() {
        val task = add("Call mom", focus = true, remind = now)
        val llm = Llm { _, _, _, _ ->
            store.complete(task.id) // you tap ✓ Done meanwhile
            BackendLlm.reply(kotlinx.serialization.json.JsonObject(mapOf(
                "role" to kotlinx.serialization.json.JsonPrimitive("assistant"),
                "content" to kotlinx.serialization.json.JsonPrimitive("Call mom now?"),
            )), "m")
        }
        val report = tick(llm)
        assertTrue(sent().isEmpty())
        assertTrue(report.lines.single().startsWith("Check-in dropped"))
    }
}
