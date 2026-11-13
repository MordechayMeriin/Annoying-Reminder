package com.annoyingreminder.app.alarm

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.annoyingreminder.app.diag.Diag
import com.annoyingreminder.app.notify.Channels
import com.annoyingreminder.app.notify.Notifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** The alarm path on a simulated phone: set → Android fires it → the alarm notification rings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29]) // The Galaxy S9's Android 10
class TestAlarmTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() = Diag.init(app)

    @Test
    fun `channels exist with the right importance`() {
        Channels.ensure(app)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, notifications.getNotificationChannel(Channels.NORMAL).importance)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, notifications.getNotificationChannel(Channels.URGENT).importance)
        val alarm = notifications.getNotificationChannel(Channels.ALARM)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, alarm.importance)
        assertEquals(android.media.AudioAttributes.USAGE_ALARM, alarm.audioAttributes.usage)
    }

    @Test
    fun `on Android 10 the test alarm is an exact alarm clock`() {
        val before = System.currentTimeMillis()
        val pending = TestAlarm.schedule(app, 60_000)
        assertTrue(pending.exact)
        val scheduled = shadowOf(alarms).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        assertNotNull("set with setAlarmClock", scheduled!!.alarmClockInfo)
        assertTrue(scheduled.triggerAtMs in before + 60_000..System.currentTimeMillis() + 60_000)
        assertEquals(pending.at, TestAlarm.pending(app)?.at)
    }

    @Test
    fun `when it rings the alarm notification is full-screen and insistent`() {
        Channels.ensure(app)
        val at = System.currentTimeMillis() - 2_000
        AlarmReceiver().onReceive(
            app,
            Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST_ALARM).putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, at),
        )
        val posted = shadowOf(notifications).getNotification(Notifier.ID_ALARM)
        assertNotNull(posted)
        assertNotNull(posted.fullScreenIntent)
        assertEquals(Channels.ALARM, posted.channelId)
        assertTrue(posted.flags and android.app.Notification.FLAG_INSISTENT != 0)
        assertNull("no longer pending", TestAlarm.pending(app))
        assertTrue(TestAlarm.lastResult(app)!!.contains("on time"))
    }

    @Test
    fun `dismiss stops it`() {
        Channels.ensure(app)
        Notifier.showAlarm(app, "Test alarm", "x", System.currentTimeMillis(), 0)
        AlarmReceiver().onReceive(app, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DISMISS))
        assertNull(shadowOf(notifications).getNotification(Notifier.ID_ALARM))
    }

    @Test
    fun `cancel removes the alarm`() {
        TestAlarm.schedule(app, 60_000)
        TestAlarm.cancel(app)
        assertNull(shadowOf(alarms).peekNextScheduledAlarm())
        assertNull(TestAlarm.pending(app))
    }

    @Test
    fun `after a restart the alarm is set again`() {
        TestAlarm.schedule(app, 3_600_000)
        val saved = TestAlarm.pending(app)!!.at
        // A restart wipes every alarm…
        alarms.cancel(
            android.app.PendingIntent.getBroadcast(
                app, 1001, Intent(app, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST_ALARM),
                android.app.PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        assertNull(shadowOf(alarms).peekNextScheduledAlarm())
        // …and the boot receiver puts it back.
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(saved, shadowOf(alarms).peekNextScheduledAlarm()!!.triggerAtMs)
    }

    @Test
    fun `an alarm whose time passed while the phone was off rings right away`() {
        TestAlarm.schedule(app, 1)
        Thread.sleep(5)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val sent = shadowOf(app).broadcastIntents.last()
        assertEquals(AlarmReceiver.ACTION_TEST_ALARM, sent.action)
    }

    @Test
    fun `test notification needs notifications on`() {
        Channels.ensure(app)
        shadowOf(notifications).setNotificationsEnabled(false)
        assertFalse(Notifier.showTest(app, urgent = false))
        shadowOf(notifications).setNotificationsEnabled(true)
        assertTrue(Notifier.showTest(app, urgent = true))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestAlarmNewAndroidTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = Diag.init(app)

    @Test
    fun `without Alarms and reminders it falls back to an inexact alarm`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val pending = TestAlarm.schedule(app, 60_000)
        assertFalse(pending.exact)
        val scheduled = shadowOf(app.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()!!
        assertNull(scheduled.alarmClockInfo)
        assertTrue(scheduled.isAllowWhileIdle)
    }
}
