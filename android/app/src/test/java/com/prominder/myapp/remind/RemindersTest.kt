package com.prominder.myapp.remind

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.test.core.app.ApplicationProvider
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.alarm.AlarmReceiver
import com.prominder.myapp.data.MetaRow
import com.prominder.myapp.data.Store
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.notify.Channels
import com.prominder.myapp.notify.Notifier
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The tick on a simulated Android 10: notifications at the right loudness, their buttons, the next exact alarm. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RemindersTest {
    private val app: ReminderApp = ApplicationProvider.getApplicationContext()
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    /**
     * The app's own database, but with the clock fixed at Monday noon: on the real clock a run at 23:00 (quiet
     * hours) or on Shabbat would rightly hold every reminder back. The receiver uses app.store: the same database.
     */
    private val noon: Instant = java.time.ZonedDateTime.of(2026, 11, 9, 12, 0, 0, 0, java.time.ZoneId.of("Asia/Jerusalem")).toInstant()
    private val store: Store by lazy { Store(app.db, clock = { noon }) }

    @Before
    fun setUp() {
        Diag.init(app)
        Channels.ensure(app)
        runBlocking { store.dao.setMeta(MetaRow(Store.META_RESTORED_AT, Times.format(noon))) } // after the handover
    }

    private fun addDue(title: String, priority: Int = 1): TaskRow = runBlocking {
        val now = noon
        val row = TaskRow(store.dao.maxTaskId() + 1, title = title, priority = priority, remindAt = Times.format(now.minusSeconds(60)),
            remindRequested = true, createdAt = Times.format(now))
        store.dao.upsertTasks(listOf(row))
        row
    }

    private fun posted(): List<Notification> = shadowOf(notifications).allNotifications

    /**
     * The app's own 15-minute safety-net tick runs on the real clock during a test (WorkManager starts with the app),
     * so at a real daily-job time it may post that job's message. Tests look for their own notifications.
     */
    private val DAILY_TITLES = com.prominder.myapp.remind.Templates.JOB_TITLES.values + com.prominder.myapp.remind.Templates.MEETING_JOB_TITLES.values

    @Test fun `a due reminder is a notification with Done and Later, and the next tick is an exact alarm`() = runBlocking {
        val t = addDue("Call the plumber")
        Reminders.tick(app, store, "test")
        val n = posted().single { it.extras.getString(Notification.EXTRA_TITLE)!!.contains("Call the plumber") }
        assertEquals(Channels.URGENT, n.channelId) // a reminder you asked for
        assertEquals(listOf("✓ Done", "Later", "Reply"), n.actions.map { it.title.toString() })
        assertNotNull(n.actions[2].remoteInputs?.single()) // typed into the notification
        assertTrue(n.extras.getString(Notification.EXTRA_TITLE)!!.contains("Call the plumber"))
        val next = shadowOf(alarms).peekNextScheduledAlarm()
        assertNotNull(next)
        assertEquals(Reminders.next(app)!!.at, next!!.triggerAtMs)
        assertTrue(Reminders.next(app)!!.exact)
        assertEquals(1, store.dao.task(t.id)!!.nagCount)
    }

    @Test fun `nothing happens before the handover`() = runBlocking {
        store.dao.setMeta(MetaRow(Store.META_RESTORED_AT, ""))
        val fresh = Store(com.prominder.myapp.data.AppDatabase.inMemory(app))
        Reminders.tick(app, fresh, "test")
        assertTrue(posted().none { it.extras.getString(Notification.EXTRA_TITLE) !in DAILY_TITLES })
        assertNull(Reminders.lastTick(app))
    }

    @Test fun `an alarm-level message is full-screen and rings until answered, for its window only`() {
        Notifier.showNotice(app, Notice(5, "🚨 Taxes", "Taxes — overdue", Logic.ALARM, 1800, taskId = 9))
        val n = posted().single { it.channelId == Channels.ALARM }
        assertEquals(Channels.ALARM, n.channelId)
        assertNotNull(n.fullScreenIntent)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(1_800_000L, n.timeoutAfter)
        assertEquals(listOf("✓ Done", "Later"), n.actions.map { it.title.toString() })
    }

    @Test fun `Hebrew buttons for a Hebrew check-in, only Reply for a group`() {
        Notifier.showNotice(app, Notice(6, "לשלם ארנונה", "x", Logic.NORMAL, taskId = 3, lang = "he"))
        Notifier.showNotice(app, Notice(7, "A (+1)", "y", Logic.NORMAL))
        val byTitle = posted().associateBy { it.extras.getString(Notification.EXTRA_TITLE) }
        assertEquals(listOf("✓ בוצע", "אחר כך", "לענות"), byTitle.getValue("לשלם ארנונה").actions.map { it.title.toString() })
        assertEquals(listOf("Reply"), byTitle.getValue("A (+1)").actions.map { it.title.toString() })
        assertEquals(Channels.NORMAL, byTitle.getValue("A (+1)").channelId)
    }

    @Test fun `Done on the notification marks the task done and clears it`() {
        val t = addDue("Water the plants")
        runBlocking { Reminders.tick(app, store, "test") }
        val id = shadowOf(notifications).activeNotifications.single { it.notification.extras.getString(Notification.EXTRA_TITLE)!!.contains("Water the plants") }.id
        AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DONE)
            .putExtra(AlarmReceiver.EXTRA_TASK_ID, t.id).putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, id))
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { store.dao.task(t.id)!!.status } != "done" && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("done", runBlocking { store.dao.task(t.id)!!.status })
        assertTrue(shadowOf(notifications).activeNotifications.none { it.id == id })
    }

    @Test fun `a reply typed into a notification goes to the assistant, and its answer comes as a notification`() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        Notifier.showNotice(app, Notice(8, "Taxes", "Taxes — time to do this.", Logic.NORMAL, taskId = 3))
        val id = shadowOf(notifications).activeNotifications.single { it.notification.extras.getString(Notification.EXTRA_TITLE) == "Taxes" }.id
        val intent = Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REPLY).putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, id)
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AlarmReceiver.EXTRA_REPLY).build()), intent,
            Bundle().apply { putCharSequence(AlarmReceiver.EXTRA_REPLY, "I'll do it at 5") })
        AlarmReceiver().onReceive(app, intent)
        // At once: the notification shows what you wrote, without its buttons.
        val sent = shadowOf(notifications).activeNotifications.single { it.id == id }.notification
        assertEquals("Sent: I'll do it at 5", sent.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(sent.actions)
        // Then: it's your message in the chat, and (logged out here, so no model) a notification says why there's no answer.
        val deadline = System.currentTimeMillis() + 10_000
        while (posted().none { it.extras.getString(Notification.EXTRA_TITLE) == "💬 ProMinder" && it.actions != null } &&
            System.currentTimeMillis() < deadline) Thread.sleep(20)
        val answer = posted().single { it.extras.getString(Notification.EXTRA_TITLE) == "💬 ProMinder" && it.actions != null }
        assertTrue(answer.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("logged out"))
        assertEquals("I'll do it at 5", runBlocking { app.store.dao.messagesSince("") }.single { it.role == "user" }.content)
    }

    @Test fun `the tick log says why a message was held back`() = runBlocking {
        Diag.log.clear()
        addDue("A")
        addDue("B").let { runBlocking { store.dao.upsertTasks(listOf(it.copy(remindRequested = false, focusDate = null))) } }
        Reminders.tick(app, store, "test")
        val lines = Diag.log.lines().joinToString("\n")
        assertTrue(lines, "[tick] Tick: test" in lines)
        assertTrue(lines, "Sent message" in lines)
    }
}
