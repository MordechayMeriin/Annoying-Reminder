package com.prominder.myapp.remind

import androidx.test.core.app.ApplicationProvider
import com.prominder.myapp.data.AppDatabase
import com.prominder.myapp.data.EventRow
import com.prominder.myapp.data.MessageRow
import com.prominder.myapp.data.SessionRow
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.logic.Logic.NORMAL
import com.prominder.myapp.logic.Logic.URGENT
import java.time.Duration
import java.time.Instant
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tick end to end on a real (in-memory) Room database, ported from backend/tests/test_tick_flow.py,
 * test_phase3_flow.py, test_shabbat.py and test_meeting_digests.py (their template paths: the LLM is Phase D).
 * Sun 8 Nov 2026 … Sat 14 Nov 2026, Israel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TickerTest {
    private val tz = ZoneId.of("Asia/Jerusalem")
    private var now: Instant = local(9, 10)
    private val db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val store = Store(db, clock = { now })
    private val ticker = Ticker(store)
    private val dao = db.dao()
    private var nextId = 0L
    private val notices = mutableListOf<Notice>()

    private fun local(d: Int, h: Int, mi: Int = 0, mo: Int = 11, y: Int = 2026): Instant = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, tz).toInstant()
    private fun t(i: Instant?) = i?.let(Times::format)
    private fun at(d: Int, h: Int, mi: Int = 0) { now = local(d, h, mi) }
    private fun advance(minutes: Long) { now = now.plus(Duration.ofMinutes(minutes)) }

    @After
    fun tearDown() = db.close()

    private fun add(
        title: String, focus: Boolean = false, kind: String = "task", priority: Int = 1, due: Instant? = null, remind: Instant? = null,
        requested: Boolean = false, nextAction: String? = null, recurrence: String? = null, created: Instant = now,
        status: String = "open", avoidAsks: Int = 0,
    ): TaskRow = runBlocking {
        val row = TaskRow(++nextId, title = title, kind = kind, priority = priority, dueAt = t(due), remindAt = t(remind),
            remindRequested = requested, nextAction = nextAction, recurrence = recurrence, createdAt = t(created)!!, status = status,
            focusDate = if (focus) Logic.focusDay(now.atZone(tz), java.time.LocalTime.of(4, 0)).toString() else null,
            avoidAsks = avoidAsks, avoidAskedAt = if (avoidAsks > 0) t(now) else null)
        dao.upsertTasks(listOf(row))
        row
    }

    private fun task(id: Long) = runBlocking { dao.task(id)!! }

    private fun events(id: Long) = runBlocking { dao.eventsSince(id, "0").sortedBy { it.createdAt } }

    private fun ignored(task: TaskRow, n: Int = 3) = runBlocking {
        repeat(n) { dao.upsertEvents(listOf(EventRow(task.id, "ignored", null, null, t(now.minusSeconds(60L * (it + 1)))!!, "r$it"))) }
    }

    private var msgId = 1000L
    private fun userSays(text: String, kind: String = "chat") = runBlocking {
        dao.upsertMessages(listOf(MessageRow(++msgId, "user", text, kind, createdAt = t(now)!!)))
    }

    private fun sent(): List<MessageRow> = runBlocking { dao.messagesSince("0").filter { it.role == "assistant" }.sortedBy { it.id } }

    private fun chipIds(m: MessageRow) = Json.parseToJsonElement(m.chips ?: "[]").jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    /** A tick's task check-ins only (the Python tests' tick()): returns the ids of the message's tasks. */
    private fun tick(jobs: Boolean = false): List<Long> = runBlocking {
        val before = sent().size
        val report = if (jobs) ticker.tick() else ticker.tickTasksOnly()
        notices += report.notices
        sent().drop(before).filter { it.kind in listOf("nudge", "reminder", "meeting") && !jobs }.flatMap { it.taskIds }
    }

    /** What the alarm does from start to end: a tick (with the daily jobs) every [stepMin] minutes. */
    private fun runDays(start: Instant, end: Instant, stepMin: Long = 30) {
        now = start
        while (now <= end) {
            tick(jobs = true)
            advance(stepMin)
        }
    }

    // ------------------------------------------------------------ test_tick_flow.py

    @Test fun `a due task gives one message and one notification`() {
        val a = add("Send the invoice", focus = true, remind = local(9, 10))
        assertEquals(listOf(a.id), tick())
        val msg = sent().single()
        assertEquals("Send the invoice — time to do this.", msg.content)
        assertEquals("nudge", msg.kind)
        assertEquals(listOf(a.id), msg.taskIds)
        assertEquals(listOf("start5", "done", "later", "stuck"), chipIds(msg))
        val n = notices.single()
        assertEquals(msg.id, n.messageId)
        assertEquals(a.id, n.taskId) // exactly one task → ✓ / Later buttons
        assertEquals(NORMAL, n.level)
        val after = task(a.id)
        assertEquals(1, after.nagCount)
        assertEquals(t(now.plus(Duration.ofDays(1))), after.remindAt) // one check-in a day
    }

    @Test fun `two due tasks give one combined message, the more important first`() {
        val a = add("Call the bank", focus = true, remind = local(9, 9, 50))
        val b = add("Book flights", focus = true, priority = 2, remind = local(9, 9, 55))
        tick()
        assertEquals(listOf(b.id, a.id), sent().single().taskIds)
        assertNull(notices.single().taskId) // no buttons for a group
        assertTrue(notices.single().title.endsWith("(+1)"))
        assertEquals("❗ Book flights (+1)", notices.single().title)
    }

    @Test fun `the template leads with the next action, overdue says since when`() {
        add("Pay the electricity bill", remind = local(9, 10), due = local(9, 9))
        add("Taxes", focus = true, nextAction = "Open the form", remind = local(9, 10))
        tick()
        val text = sent().single().content
        assertTrue(text, "Pay the electricity bill — overdue since Mon 09:00." in text)
        assertTrue(text, "▶ Open the form — Taxes" in text)
    }

    @Test fun `meeting facts come from code`() {
        val m = add("Team sync", kind = "meeting", due = local(9, 10, 15), remind = local(9, 10))
        tick()
        val msg = sent().single()
        assertEquals("📅 Team sync — 10:15 (in 15 min)", msg.content)
        assertEquals("meeting", msg.kind)
        assertEquals("[]", msg.chips)
        assertEquals(URGENT, notices.single().level)
        assertEquals(t(local(9, 10, 15)), task(m.id).remindAt) // the second reminder at its start
    }

    @Test fun `the minimum gap holds the next check-in`() {
        add("A", focus = true, remind = local(9, 10))
        val b = add("B", focus = true, remind = local(9, 10, 10))
        tick()
        advance(10)
        assertEquals(emptyList<Long>(), tick())
        assertEquals(t(local(9, 10, 20)), task(b.id).remindAt)
        advance(10)
        assertEquals(listOf(b.id), tick())
        assertEquals(2, notices.size)
    }

    @Test fun `quiet hours hold even a critical task`() {
        at(9, 23, 30)
        val a = add("Critical thing", priority = 3, focus = true, remind = local(9, 23, 20))
        assertEquals(emptyList<Long>(), tick())
        assertEquals(t(local(10, 7)), task(a.id).remindAt)
        assertTrue(notices.isEmpty())
    }

    @Test fun `the daily cap is never exceeded`() {
        repeat(30) { i ->
            add("task $i", focus = true, remind = now)
            tick()
            advance(25)
        }
        assertTrue("${notices.size}", notices.size in 11..20)
    }

    @Test fun `an ignored check-in is logged`() {
        val a = add("Clean the garage", focus = true, remind = local(9, 10), due = local(10, 12))
        tick()
        advance(30)
        tick()
        assertEquals(listOf("ignored"), events(a.id).map { it.kind })
    }

    @Test fun `a tap since the last check-in means it wasn't ignored`() {
        val a = add("Clean the garage", focus = true, remind = local(9, 10), due = local(10, 12))
        tick()
        advance(5)
        userSays("[Later: Clean the garage]", "event")
        advance(25)
        tick()
        assertEquals(emptyList<String>(), events(a.id).map { it.kind })
    }

    @Test fun `a requested reminder skips the per-task cap`() {
        val a = add("Stretch", focus = true, remind = local(9, 10), due = local(10, 12))
        repeat(4) {
            tick()
            advance(30)
        }
        assertNull(task(a.id).remindAt) // max_nags reached: nagging stops
        runBlocking { store.later(a.id) }
        now = Times.parse(task(a.id).remindAt!!)
        assertEquals(listOf(a.id), tick())
        assertEquals("reminder", sent().last().kind)
    }

    // ------------------------------------------------------------ test_phase3_flow.py

    @Test fun `an avoided task gets one reason message, never loud`() {
        val a = add("Taxes", nextAction = "Open the form", focus = true, priority = 3, remind = local(9, 10))
        ignored(a)
        assertEquals(listOf(a.id), tick())
        val msg = sent().last()
        assertEquals("Taxes keeps slipping, and that's fine. What's in the way?", msg.content)
        assertEquals(listOf("too_big", "unclear", "boring", "not_now"), chipIds(msg))
        assertTrue(msg.chips!!.endsWith("{\"id\": \"not_now\", \"label\": \"Not now\", \"action\": \"not_now\", \"task_id\": ${a.id}}]"))
        assertEquals(NORMAL, notices.last().level)
        val after = task(a.id)
        assertEquals(1, after.avoidAsks)
        assertEquals(t(now.plus(Duration.ofDays(3))), after.remindAt)
        repeat(40) {
            advance(60)
            tick()
        }
        assertEquals(1, sent().size)
    }

    @Test fun `silence means one more try in 3 days, then the task is parked`() {
        val a = add("Taxes", focus = true, remind = local(9, 10))
        ignored(a)
        tick()
        at(12, 10)
        assertEquals(listOf(a.id), tick())
        assertEquals(2, task(a.id).avoidAsks)
        assertEquals(2, sent().size)
        at(15, 10)
        assertEquals(emptyList<Long>(), tick())
        val after = task(a.id)
        assertEquals(t(now.plus(Duration.ofDays(7))), after.parkedUntil)
        assertEquals("open", after.status)
        assertEquals("parked", events(a.id).last().kind)
        for (d in 16..21) {
            at(d, 10)
            assertEquals(emptyList<Long>(), tick())
        }
        assertEquals(2, sent().size)
    }

    @Test fun `an avoided task waits while other tasks are due`() {
        val avoided = add("Taxes", focus = true, remind = local(9, 10))
        ignored(avoided)
        val other = add("Call the bank", focus = true, remind = local(9, 10))
        assertEquals(listOf(other.id), tick())
        assertEquals(listOf("start5", "done"), chipIds(sent().last()).take(2))
        advance(25)
        assertEquals(listOf(avoided.id), tick())
        assertEquals("too_big", chipIds(sent().last()).first())
    }

    @Test fun `undated tasks get one normal check-in a day`() {
        val a = add("Sort the photos", priority = 3, focus = true, remind = local(9, 10))
        tick()
        repeat(20) {
            advance(30)
            tick()
        }
        assertEquals(1, sent().size)
        assertEquals(listOf(NORMAL), notices.map { it.level })
        assertEquals(t(local(10, 10)), task(a.id).remindAt) // tomorrow (the LLM's follow-ups are Phase D)
    }

    @Test fun `a near deadline still escalates`() {
        add("File the report", priority = 2, focus = true, remind = local(9, 10), due = local(9, 18))
        repeat(3) {
            tick()
            advance(20)
        }
        assertEquals(listOf(URGENT, URGENT), notices.map { it.level }.take(2))
        assertEquals(3, notices.size)
    }

    @Test fun `an important task due today, ignored twice, gets an alarm`() {
        add("Call the doctor", priority = 2, focus = true, due = local(9, 15), remind = local(9, 10))
        repeat(3) {
            tick()
            advance(30)
        }
        assertEquals(listOf(URGENT, URGENT, Logic.ALARM), notices.map { it.level })
        assertEquals(3600L, notices.last().alarmSeconds)
    }

    @Test fun `three silent days give exactly one re-entry message and nothing loud`() {
        at(8, 20)
        userSays("good night") // the last activity: Sunday 20:00
        add("Sort the photos", priority = 3, remind = local(9, 10), nextAction = "Open the folder")
        add("Renew passport", priority = 2, due = local(2, 9), remind = local(9, 11)) // long overdue
        val meeting = add("Team sync", kind = "meeting", due = local(11, 15), remind = local(11, 14, 30))
        runDays(local(9, 6), local(12, 23))

        val byDay = sent().groupBy({ Times.parse(it.createdAt).atZone(tz).dayOfMonth }, { it.kind })
        assertEquals("morning", byDay[9]!!.first())
        assertTrue("evening" in byDay[9]!!)
        assertEquals("morning", byDay[10]!!.first())
        assertFalse("evening" in byDay[10]!!) // 48 hours pass on Tuesday at 20:00
        assertEquals(listOf("reentry", "meeting", "meeting"), byDay[11])
        assertNull(byDay[12])
        val reentry = sent().first { it.kind == "reentry" }
        assertEquals("Good to see you. Want me to pick one thing for today?", reentry.content)
        assertEquals(listOf("pick_one", "move_overdue"), chipIds(reentry))
        assertEquals(t(local(11, 7, 30)), reentry.createdAt)
        val meetingMsgs = sent().filter { it.taskIds == listOf(meeting.id) }.map { it.id }.toSet()
        val loud = notices.filter { it.level > NORMAL }
        assertEquals(2, loud.size)
        assertTrue(loud.all { it.messageId in meetingMsgs })
        assertTrue(sent().none { it.kind == "nudge" && it.createdAt >= t(local(10, 20))!! })
    }

    @Test fun `coming back ends the lapse`() {
        at(5, 20)
        userSays("bye")
        val a = add("Taxes", remind = local(9, 10), due = local(9, 9))
        val asked = add("Call mum", remind = local(9, 10), requested = true)
        at(9, 10)
        assertEquals(listOf(asked.id), tick()) // a reminder they asked for still arrives
        at(9, 10, 30)
        assertEquals(emptyList<Long>(), tick())
        userSays("[What now?]", "event") // one tap: they're back
        assertEquals(listOf(a.id), tick())
    }

    @Test fun `Shabbat alone is not a lapse`() {
        at(13, 12)
        userSays("shabbat shalom")
        val a = add("Pay", due = local(15, 9), remind = local(15, 13))
        at(15, 13) // Sunday 13:00: 49 hours later
        assertEquals(listOf(a.id), tick())
    }

    // ------------------------------------------------------------ fences

    @Test fun `a fence fires during a running session, once`() {
        val work = add("Write the report", nextAction = "List the headings", focus = true)
        val fence = add("Pick up the kids", kind = "fence", due = local(9, 10, 10), remind = local(9, 10, 10), requested = true,
            recurrence = "weekdays")
        runBlocking {
            dao.upsertSessions(listOf(SessionRow(1, work.id, t(local(9, 10))!!, 25)))
            dao.upsertMessages(listOf(MessageRow(++msgId, "assistant", "an earlier message", "nudge", createdAt = t(local(9, 10))!!)))
        }
        at(9, 10, 10)
        assertEquals(listOf(fence.id), tick())
        val msg = sent().last()
        assertEquals("⛔ 10:10 — Pick up the kids. Time to stop.", msg.content)
        assertEquals("reminder" to "[]", msg.kind to msg.chips)
        assertEquals(URGENT, notices.last().level)
        assertEquals("⛔ Pick up the kids", notices.last().title)
        val after = task(fence.id)
        assertEquals("open", after.status)
        assertEquals(t(local(10, 10, 10)), after.dueAt)
        assertEquals(after.dueAt, after.remindAt)
        assertNull(runBlocking { dao.latestSession() }!!.endedAt)
        advance(5)
        assertEquals(emptyList<Long>(), tick())
    }

    @Test fun `a one-off fence closes and a late one is skipped`() {
        val once = add("Leave for the airport", kind = "fence", due = local(9, 10), remind = local(9, 10), requested = true)
        assertEquals(listOf(once.id), tick())
        assertEquals("done", task(once.id).status)
        assertTrue(events(once.id).none { it.kind == "done" })
        val night = add("Go to bed", kind = "fence", due = local(9, 23, 30), remind = local(9, 23, 30), requested = true, recurrence = "daily")
        at(9, 23, 30)
        assertEquals(emptyList<Long>(), tick())
        assertEquals(t(local(10, 7)), task(night.id).remindAt)
        at(10, 7)
        assertEquals(emptyList<Long>(), tick())
        assertEquals(1, sent().size)
        assertEquals(t(local(10, 23, 30)), task(night.id).dueAt)
    }

    // ------------------------------------------------------------ Shabbat (test_shabbat.py)

    @Test fun `nothing is sent on Shabbat and everything comes after`() {
        at(14, 11)
        val a = add("Call the bank", priority = 3, focus = true, remind = local(14, 10))
        val b = add("Team sync", kind = "meeting", due = local(14, 19), remind = local(14, 11))
        assertEquals(emptyList<Long>(), tick())
        assertTrue(notices.isEmpty())
        assertEquals(t(local(14, 18, 20)), task(a.id).remindAt)
        assertEquals(t(local(14, 18, 20)), task(b.id).remindAt)
        at(14, 18, 20)
        assertEquals(setOf(a.id, b.id), tick().toSet())
        assertEquals(1, notices.size)
    }

    @Test fun `daily jobs skip Shabbat`() {
        at(14, 7, 35)
        tick(jobs = true)
        at(13, 21, 35)
        tick(jobs = true)
        assertTrue(sent().isEmpty())
        at(15, 7, 35)
        tick(jobs = true)
        assertEquals("morning", sent().single().kind)
    }

    @Test fun `a daily job runs right after Shabbat if still in its window`() {
        add("Dentist", kind = "meeting", due = local(4, 9, mo = 7, y = 2027))
        now = local(3, 21, 30, mo = 7, y = 2027)
        tick(jobs = true)
        assertTrue(sent().isEmpty())
        now = local(3, 21, 35, mo = 7, y = 2027)
        tick(jobs = true)
        assertTrue(sent().any { it.content.startsWith("📅 Tomorrow (Sun 04/07):") })
    }

    // ------------------------------------------------------------ daily messages

    @Test fun `the morning plan picks Today's 3 by code and links them`() {
        at(9, 7, 30)
        val a = add("Overdue", due = local(8, 9))
        val b = add("Today", due = local(9, 18), nextAction = "Open the file")
        val c = add("Important", priority = 3)
        add("Later", due = local(20, 9))
        add("Standup", kind = "meeting", due = local(9, 9, 30))
        tick(jobs = true)
        val msg = sent().single()
        assertEquals("☀️ Today's 3:\n1. Overdue\n2. Today\n3. Important\n📅 Meetings:\n09:30 Standup", msg.content)
        assertEquals("morning", msg.kind)
        assertEquals(listOf(a.id, b.id, c.id), msg.taskIds)
        assertEquals(listOf("start_first", "change"), chipIds(msg))
        assertEquals("2026-11-09", task(a.id).focusDate)
        assertEquals("☀️ Morning plan", notices.single().title)
    }

    @Test fun `the morning plan on the first day of the week carries wins, cleanup and the week's meetings`() {
        at(8, 7, 30)
        val monthAgo = now.minus(Duration.ofDays(30))
        listOf("Report", "Invoice", "Call the bank").forEach { add(it, priority = 2) }
        val stale = listOf("Learn Spanish", "Fix the shelf").map { add(it, created = monthAgo) }
        val moving = add("Write the book", created = monthAgo)
        runBlocking {
            dao.upsertEvents(listOf(EventRow(moving.id, "progress", null, null, t(now.minus(Duration.ofDays(4)))!!, "p")))
        }
        add("Visa", created = monthAgo, due = local(20, 9))
        repeat(2) { i ->
            val done = add("done $i", status = "done")
            runBlocking { dao.upsertEvents(listOf(EventRow(done.id, "done", null, null, t(now.minus(Duration.ofDays(3)))!!, "d$i"))) }
        }
        add("Dentist", kind = "meeting", due = local(10, 9, 30))
        runDays(local(8, 7, 30), local(8, 11))
        val msgs = sent()
        assertEquals(listOf("morning"), msgs.map { it.kind })
        assertEquals(
            "☀️ Today's 3:\n1. Report\n2. Invoice\n3. Call the bank\n\n" +
                "✅ Last week you finished 2 things.\n" +
                "🧹 Open for 3+ weeks with no progress: Learn Spanish · Fix the shelf. Drop them or keep?\n\n" +
                "📅 Your meetings this week:\nTue 10/11 09:30 — Dentist",
            msgs[0].content,
        )
        assertEquals(listOf("start_first", "change", "drop", "keep"), chipIds(msgs[0]))
        val ids = Json.parseToJsonElement(msgs[0].chips!!).jsonArray[2].jsonObject["task_ids"]!!.jsonArray.map { it.jsonPrimitive.long }
        assertEquals(stale.map { it.id }, ids)
        assertEquals(1, notices.size)
    }

    @Test fun `the mid-day check-in names the next of Today's 3, and is skipped when you were active`() {
        at(9, 7, 30)
        add("Report", priority = 2, nextAction = "Write the intro")
        tick(jobs = true)
        at(9, 13)
        tick(jobs = true)
        assertEquals("Next from Today's 3: ▶ Write the intro (Report)", sent().last().content)
        assertEquals(listOf("start_next", "what_now"), chipIds(sent().last()))
        at(9, 16, 50)
        userSays("on it")
        at(9, 17)
        tick(jobs = true)
        assertEquals("checkin", sent().last().kind) // 13:00's; 17:00 was skipped
        assertEquals(2, sent().size)
    }

    @Test fun `the evening review counts the wins`() {
        at(9, 21, 30)
        val a = add("Report")
        runBlocking { store.complete(a.id) }
        add("Invoice", due = local(9, 12))
        tick(jobs = true)
        assertEquals("🌙 Done today: 1. 1 open item(s) carry over to tomorrow.", sent().last().content)
    }

    // ------------------------------------------------------------ meeting digests (test_meeting_digests.py)

    @Test fun `the week's digest lists the coming week`() {
        at(8, 8)
        runBlocking { store.saveSetting("morning_time", "06:00") } // not merged into the morning plan
        val m1 = add("Dentist", kind = "meeting", due = local(10, 9, 30))
        val m2 = add("Team sync", kind = "meeting", due = local(8, 11))
        add("Already over", kind = "meeting", due = local(8, 7))
        add("Next week", kind = "meeting", due = local(15, 10))
        add("Pay bill", due = local(9, 12))
        tick(jobs = true)
        val msg = sent().single { it.kind == "meeting" }
        assertEquals("📅 Your meetings this week:\nToday 11:00 — Team sync\nTue 10/11 09:30 — Dentist", msg.content)
        assertEquals(listOf(m2.id, m1.id), msg.taskIds)
        val n = notices.single { it.messageId == msg.id }
        assertEquals("📅 This week's meetings", n.title)
        assertNull(n.taskId)
    }

    @Test fun `the evening digest lists tomorrow's meetings`() {
        at(9, 20)
        add("Dentist", kind = "meeting", due = local(10, 9, 30))
        add("Lunch with Dani", kind = "meeting", due = local(10, 13))
        add("Wednesday thing", kind = "meeting", due = local(11, 9))
        add("Tonight", kind = "meeting", due = local(9, 21))
        add("Cancelled", kind = "meeting", due = local(10, 9), status = "cancelled")
        tick(jobs = true)
        assertEquals("📅 Tomorrow (Tue 10/11):\n09:30 — Dentist\n13:00 — Lunch with Dani", sent().single().content)
        assertEquals("📅 Tomorrow's meetings", notices.single().title)
    }

    @Test fun `each daily job runs once a day`() {
        at(9, 20)
        add("Dentist", kind = "meeting", due = local(10, 9, 30))
        tick(jobs = true)
        advance(30)
        tick(jobs = true)
        assertEquals(1, sent().size)
    }

    // ------------------------------------------------------------ the next wake-up

    @Test fun `the next wake-up is the soonest reminder or daily job, at least a minute away`() = runBlocking {
        at(9, 10)
        assertEquals(local(9, 13), ticker.nextWake()) // the 13:00 check-in
        add("Call", remind = local(9, 11))
        assertEquals(local(9, 11), ticker.nextWake())
        add("Now", remind = local(9, 9))
        assertEquals(local(9, 10, 1), ticker.nextWake())
    }

    @Test fun `on Shabbat the next wake-up includes its end`() = runBlocking {
        at(14, 15)
        assertEquals(local(14, 18, 20), ticker.nextWake())
    }

    @Test fun `a tick that changes nothing doesn't schedule a backup`() = runBlocking {
        var changes = 0
        val quiet = Ticker(Store(db, clock = { now }, onChange = { changes++ }))
        quiet.tickTasksOnly()
        assertEquals(0, changes)
        add("A", focus = true, remind = local(9, 10))
        quiet.tickTasksOnly()
        assertEquals(1, changes)
    }
}
