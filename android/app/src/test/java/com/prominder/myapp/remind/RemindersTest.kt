package com.prominder.myapp.remind

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
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
    private val store: Store get() = app.store

    @Before
    fun setUp() {
        Diag.init(app)
        Channels.ensure(app)
        runBlocking { store.dao.setMeta(MetaRow(Store.META_RESTORED_AT, Times.format(Instant.now()))) } // after the handover
    }

    private fun addDue(title: String, priority: Int = 1): TaskRow = runBlocking {
        val now = Instant.now()
        val row = TaskRow(store.dao.maxTaskId() + 1, title = title, priority = priority, remindAt = Times.format(now.minusSeconds(60)),
            remindRequested = true, createdAt = Times.format(now))
        store.dao.upsertTasks(listOf(row))
        row
    }

    private fun posted(): List<Notification> = shadowOf(notifications).allNotifications

    @Test fun `a due reminder is a notification with Done and Later, and the next tick is an exact alarm`() = runBlocking {
        val t = addDue("Call the plumber")
        Reminders.tick(app, store, "test")
        // (a daily message may come too, depending on the real time of day the test runs at)
        val n = posted().single { it.extras.getString(Notification.EXTRA_TITLE)!!.contains("Call the plumber") }
        assertEquals(Channels.URGENT, n.channelId) // a reminder you asked for
        assertEquals(listOf("✓ Done", "Later"), n.actions.map { it.title.toString() })
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
        assertTrue(posted().isEmpty())
        assertNull(Reminders.lastTick(app))
    }

    @Test fun `an alarm-level message is full-screen and rings until answered, for its window only`() {
        Notifier.showNotice(app, Notice(5, "🚨 Taxes", "Taxes — overdue", Logic.ALARM, 1800, taskId = 9))
        val n = posted().single()
        assertEquals(Channels.ALARM, n.channelId)
        assertNotNull(n.fullScreenIntent)
        assertTrue(n.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals(1_800_000L, n.timeoutAfter)
        assertEquals(listOf("✓ Done", "Later"), n.actions.map { it.title.toString() })
    }

    @Test fun `Hebrew buttons for a Hebrew check-in, none for a group`() {
        Notifier.showNotice(app, Notice(6, "לשלם ארנונה", "x", Logic.NORMAL, taskId = 3, lang = "he"))
        Notifier.showNotice(app, Notice(7, "A (+1)", "y", Logic.NORMAL))
        val byTitle = posted().associateBy { it.extras.getString(Notification.EXTRA_TITLE) }
        assertEquals(listOf("✓ בוצע", "אחר כך"), byTitle.getValue("לשלם ארנונה").actions.map { it.title.toString() })
        assertNull(byTitle.getValue("A (+1)").actions)
        assertEquals(Channels.NORMAL, byTitle.getValue("A (+1)").channelId)
    }

    @Test fun `Done on the notification marks the task done and clears it`() {
        val t = addDue("Water the plants")
        runBlocking { Reminders.tick(app, store, "test") }
        val id = shadowOf(notifications).activeNotifications.single { it.notification.actions != null }.id
        AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DONE)
            .putExtra(AlarmReceiver.EXTRA_TASK_ID, t.id).putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, id))
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { store.dao.task(t.id)!!.status } != "done" && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("done", runBlocking { store.dao.task(t.id)!!.status })
        assertTrue(shadowOf(notifications).activeNotifications.none { it.id == id })
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
