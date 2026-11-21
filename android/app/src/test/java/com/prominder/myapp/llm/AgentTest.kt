package com.prominder.myapp.llm

import com.prominder.myapp.R
import com.prominder.myapp.ui.ToolSteps
import androidx.test.core.app.ApplicationProvider
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.Chips
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
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

/**
 * The chat agent on a real (in-memory) database: replies continue the thread, the LLM decides the task, tool calling
 * is robust, progress is reported. The cases of test_agent.py, test_chat_progress.py and test_model_tag.py (the
 * Python server's tests, removed in b09e82e).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AgentTest {
    private val tz = ZoneId.of("Asia/Jerusalem")
    private var now: Instant = local(9, 10)
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = Store(db, clock = { now })
    private val dao = db.dao()
    private val prompts = testPrompts()
    private var nextId = 0L
    private var msgId = 500L

    private fun local(d: Int, h: Int, mi: Int = 0): Instant = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, tz).toInstant()
    private fun advance(minutes: Long) { now = now.plus(Duration.ofMinutes(minutes)) }

    @After
    fun tearDown() = db.close()

    private fun task(title: String, description: String? = null): TaskRow = runBlocking {
        val row = TaskRow(++nextId, title = title, description = description, createdAt = Times.format(now))
        dao.upsertTasks(listOf(row))
        row
    }

    private fun said(role: String, content: String, kind: String, taskIds: List<Long> = emptyList(), at: Instant = now, chips: String = "[]") = runBlocking {
        dao.upsertMessages(listOf(MessageRow(++msgId, role, content, kind, taskIds.firstOrNull(), taskIds, chips, null, Times.format(at))))
    }

    private fun chat(llm: Llm, text: String, progress: (ChatProgress) -> Unit = {}): ChatResult = runBlocking {
        Agent(store, llm, prompts).handle(text, onProgress = progress)
    }

    private fun messages(): List<MessageRow> = runBlocking { dao.messagesSince("").sortedBy { it.id } }
    private fun events(id: Long) = runBlocking { dao.eventsSince(id, "").sortedBy { it.createdAt } }

    // ------------------------------------------------------------ test_agent.py

    @Test fun `history alternates roles and starts with the user`() {
        val out = Agent.normalizeRoles(listOf("assistant" to "plan", "assistant" to "nudge", "user" to "[Marked done: X]", "user" to "thanks"))
        assertEquals(listOf("user", "assistant", "user"), out.map { it["role"].toString().trim('"') })
        assertEquals("plan\n\nnudge", out[1]["content"].toString().trim('"').replace("\\n", "\n"))
    }

    @Test fun `a stuck reply after a check-in gets help and tools`() {
        val t = task("Tax return", "Annual return, deadline end of month")
        said("assistant", "How's the tax return going?", "nudge", listOf(t.id))
        advance(20)
        val llm = ScriptedLlm(listOf(
            calls("log_interaction", "task_id" to t.id, "kind" to "stuck", "note" to "the form"),
            calls("add_subtask", "task_id" to t.id, "title" to "Download form 1301"),
            calls("schedule_followup", "task_id" to t.id, "minutes_from_now" to 45, "reason" to "after first step", "requested_by" to "assistant"),
            says("Let's just download form 1301 first (10 min). I'll check back in 45 minutes."),
        ))
        val r = chat(llm, "I'm stuck on the form")

        assertEquals(listOf(t.id), r.taskIds)
        val system = llm.content(0, 0)
        assertTrue(system.contains("Tax return") && system.contains("How's the tax return going?")) // the likely topic
        assertEquals(listOf("stuck"), events(t.id).map { it.kind })
        assertEquals(listOf("Download form 1301"), runBlocking { dao.children(t.id) }.map { it.title })
        val after = runBlocking { dao.task(t.id)!! }
        assertFalse(after.remindRequested)
        assertEquals(Times.format(now.plus(Duration.ofMinutes(45))), after.remindAt)
        assertEquals(listOf(t.id), messages().first { it.id == r.userMessageId }.taskIds)
        // The reply about exactly one open task gets ▶ Start 5 min · ✓ Done.
        assertEquals(listOf("start5", "done"), Chips.decode(messages().last().chips).map { it.id })
    }

    @Test fun `a reply without a tool links to an unambiguous check-in`() {
        val t = task("Gym")
        said("assistant", "Gym today?", "nudge", listOf(t.id))
        assertEquals(listOf(t.id), chat(ScriptedLlm(listOf(says("Great, enjoy!"))), "yes, going at 6").taskIds)
    }

    @Test fun `a reply to a grouped check-in without an action stays unlinked`() {
        val a = task("Gym")
        val b = task("Groceries")
        said("assistant", "Gym and groceries?", "nudge", listOf(a.id, b.id))
        assertEquals(emptyList<Long>(), chat(ScriptedLlm(listOf(says("Which one first?"))), "ok").taskIds)
    }

    @Test fun `the LLM decides a different task than the check-in`() {
        val gym = task("Gym")
        val plumber = task("Call the plumber")
        said("assistant", "Gym today?", "nudge", listOf(gym.id))
        val llm = ScriptedLlm(listOf(calls("complete_task", "id" to plumber.id), says("Nice, plumber done.")))
        val r = chat(llm, "btw I called the plumber")
        assertEquals(listOf(plumber.id), r.taskIds)
        assertTrue(llm.content(0, 0).contains("Call the plumber")) // a word match made it a candidate
        assertEquals("done", runBlocking { dao.task(plumber.id)!!.status })
        // The tool doesn't write "[Marked done: …]" in the chat: only taps do.
        assertTrue(messages().none { it.kind == "event" })
        assertNull(Chips.decode(messages().last().chips).firstOrNull()) // done: no buttons
    }

    @Test fun `a check-in from before the daily reset is still the topic`() {
        now = local(10, 5, 30) // the day reset was at 04:00
        val t = task("Read chapter 3")
        said("assistant", "Chapter 3 tonight?", "nudge", listOf(t.id), at = local(10, 3))
        val llm = ScriptedLlm(listOf(says("Good morning! Tonight then?")))
        chat(llm, "sorry fell asleep")
        assertTrue(llm.content(0, 0).contains("Chapter 3 tonight?")) // shown as the last check-in
        assertTrue((1 until llm.requests[0].messages.size).none { llm.content(0, it).contains("Chapter 3 tonight?") }) // not today's history
    }

    @Test fun `bad arguments get one retry`() {
        val llm = ScriptedLlm(listOf(
            calls("add_task", "title" to "Dentist", "due_at" to "thursday at 10"),
            calls("add_task", "title" to "Dentist", "due_at" to "2026-11-12T10:00"),
            says("Saved: Dentist, Thu 10:00."),
        ))
        val r = chat(llm, "dentist thursday 10")
        assertEquals("Saved: Dentist, Thu 10:00.", r.reply)
        assertTrue(r.changed)
        assertTrue(llm.requests[1].messages.last()["content"].toString().contains("ISO")) // the error went back to the model
        val saved = runBlocking { dao.openTasks() }
        assertEquals(listOf("Dentist"), saved.map { it.title })
        assertEquals(Times.format(local(12, 10)), saved[0].dueAt)
    }

    @Test fun `gives up after a second bad call`() {
        val r = chat(ScriptedLlm(listOf(calls("complete_task", "id" to 999), calls("complete_task", "id" to 998), says("never reached"))),
            "done with that thing")
        assertEquals("Sorry, I couldn't process that. Try again?", r.reply)
        assertFalse(r.changed)
    }

    @Test fun `a tool call written as JSON text`() {
        val r = chat(ScriptedLlm(listOf(
            says("""{"name": "add_task", "arguments": {"title": "לשלם ארנונה", "due_at": "2026-11-15T09:00"}}"""),
            says("שמרתי: לשלם ארנונה, ראשון 09:00"),
        )), "לשלם ארנונה עד ראשון")
        assertTrue(r.changed)
        assertEquals(listOf("לשלם ארנונה"), runBlocking { dao.openTasks() }.map { it.title })
        assertTrue(r.reply.startsWith("שמרתי"))
    }

    @Test fun `no LLM keeps your message`() {
        assertThrows(LlmUnavailable::class.java) { chat(ScriptedLlm(listOf(Fails(LlmUnavailable("down")))), "hello?") }
        assertEquals(listOf("hello?"), messages().map { it.content })
    }

    @Test fun `requests alternate roles`() {
        said("assistant", "Morning plan", "morning")
        said("assistant", "Check-in", "nudge")
        said("user", "[Marked done: X]", "event")
        val llm = ScriptedLlm(listOf(says("ok")))
        chat(llm, "hi")
        assertEquals(listOf("user", "assistant", "user"), llm.requests[0].messages.drop(1).map { it["role"].toString().trim('"') })
    }

    // ------------------------------------------------------------ test_chat_progress.py

    @Test fun `progress events in order`() {
        val t = task("Call the bank")
        val events = mutableListOf<ChatProgress>()
        val r = chat(ScriptedLlm(listOf(
            calls("add_task", "title" to "Dentist", "kind" to "meeting"),
            calls("complete_task", "id" to t.id),
            says("Saved the dentist and marked the bank call done."),
        )), "dentist thursday; called the bank") { events += it }

        assertEquals(listOf("Received", "Thinking", "Tool", "Thinking", "Tool", "Thinking"), events.map { it::class.simpleName })
        assertEquals(ChatProgress.Received(r.userMessageId), events[0])
        assertEquals(ChatProgress.Tool(ToolStatus("add_task", title = "Dentist", kind = "meeting")), events[2])
        assertEquals(ChatProgress.Tool(ToolStatus("complete_task", taskTitle = "Call the bank")), events[4])
        assertEquals(R.string.step_new_meeting to "Dentist", ToolSteps.of((events[2] as ChatProgress.Tool).status))
        assertEquals(R.string.step_complete to "Call the bank", ToolSteps.of((events[4] as ChatProgress.Tool).status))
    }

    @Test fun `a failing progress callback never breaks the turn`() {
        val r = chat(ScriptedLlm(listOf(calls("add_task", "title" to "Gym"), says("Saved."))), "gym at 6") { throw IllegalStateException("UI bug") }
        assertEquals("Saved.", r.reply)
        assertTrue(r.changed)
    }

    // ------------------------------------------------------------ test_model_tag.py

    @Test fun `the reply carries the model, your message doesn't`() {
        chat(ScriptedLlm(listOf(says("Hi! What's on your mind?")), model = "anthropic/claude-haiku"), "hi")
        assertEquals("anthropic/claude-haiku", messages().last().model)
        assertNull(messages().first().model)
    }

    // ------------------------------------------------------------ the phone's own

    @Test fun `try again answers the saved message without saving it twice`() {
        assertThrows(LlmUnavailable::class.java) { chat(ScriptedLlm(listOf(Fails(LlmUnavailable("down")))), "dentist tomorrow") }
        val saved = messages().single()
        val llm = ScriptedLlm(listOf(says("Saved: dentist.")))
        val r = runBlocking { Agent(store, llm, prompts).handle(saved.content, retryOf = saved.id) }
        assertEquals(saved.id, r.userMessageId)
        assertEquals(listOf("dentist tomorrow", "Saved: dentist."), messages().map { it.content })
        assertEquals(listOf("user"), llm.requests[0].messages.drop(1).map { it["role"].toString().trim('"') })
    }

    @Test fun `a tap on a reason button records why the task is avoided`() {
        val t = task("Tax return")
        said("assistant", "This keeps slipping, and that's fine. What's in the way?", "nudge", listOf(t.id),
            chips = Chips.encode(com.prominder.myapp.logic.ChipRules.chipsJson(com.prominder.myapp.logic.ChipRules.chipsFor("avoided", null), "en", t.id)))
        val r = chat(ScriptedLlm(listOf(says("Let's make it smaller."))), "Too big")
        assertEquals(listOf(t.id), r.taskIds)
        val e = events(t.id).single()
        assertEquals("avoided" to "too_big", e.kind to e.avoidReason)
        assertTrue(runBlocking { dao.task(t.id)!! }.avoidResetAt != null)
    }

    @Test fun `settings and preferences from the chat`() {
        val llm = ScriptedLlm(listOf(
            calls("update_setting", "key" to "quiet_start", "value" to "22:00"),
            calls("remember_preference", "text" to "Shorter   messages please"),
            says("Done: quiet hours now start at 22:00 (was 23:00)."),
        ))
        chat(llm, "don't message me after 22:00, and keep it short")
        assertEquals("22:00", runBlocking { store.settings() }.raw("quiet_start"))
        assertEquals(listOf("Shorter messages please"), runBlocking { dao.preferences() }.map { it.text })
        val result = llm.requests[1].messages.last()["content"].toString()
        assertTrue(result.contains("\\\"old\\\":\\\"23:00\\\"") && result.contains("\\\"new\\\":\\\"22:00\\\""))
        // The next turn's instructions include it.
        val next = ScriptedLlm(listOf(says("ok")))
        chat(next, "thanks")
        assertTrue(next.content(0, 0).contains("- Shorter messages please"))
    }

    @Test fun `set_focus picks Today's 3, a meeting can't be one`() {
        val a = task("Report")
        val meeting = runBlocking { TaskRow(++nextId, title = "Standup", kind = "meeting", createdAt = Times.format(now)).also { dao.upsertTasks(listOf(it)) } }
        val llm = ScriptedLlm(listOf(calls("set_focus", "task_ids" to listOf(a.id, meeting.id)), calls("set_focus", "task_ids" to listOf(a.id)), says("Done.")))
        chat(llm, "make the report my focus")
        assertTrue(llm.requests[1].messages.last()["content"].toString().contains("meetings can't be focus tasks"))
        assertEquals("2026-11-09", runBlocking { dao.task(a.id)!! }.focusDate)
    }
}
