package com.annoyingreminder.app.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.annoyingreminder.app.MainActivity
import com.annoyingreminder.app.R
import com.annoyingreminder.app.alarm.AlarmActivity
import com.annoyingreminder.app.alarm.AlarmReceiver
import com.annoyingreminder.app.diag.Diag

/** Shows notifications. Phase A: the test ones; the real check-ins come in Phase C. */
object Notifier {
    private const val ID_TEST_NORMAL = 1
    private const val ID_TEST_URGENT = 2
    const val ID_ALARM = 3

    /** Alarms ring for at most this long if nobody answers, so a forgotten test can't ring all day. */
    private const val ALARM_TIMEOUT_MS = 10 * 60 * 1000L

    /** Notifications switched on, and (Android 13+) the permission granted. */
    fun canNotify(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    /** "Notify me now": a test on the normal or urgent channel. False when notifications are off. */
    fun showTest(context: Context, urgent: Boolean): Boolean {
        if (!canNotify(context)) {
            Diag.log("notify", "Test notification not shown: notifications are off")
            return false
        }
        val channel = if (urgent) Channels.URGENT else Channels.NORMAL
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (urgent) "Test: urgent" else "Test: normal")
            .setContentText(
                if (urgent) "Urgent notifications work. Meetings and hard stops will sound like this."
                else "Normal notifications work. Check-ins will look like this.",
            )
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        post(context, if (urgent) ID_TEST_URGENT else ID_TEST_NORMAL, n)
        Diag.log("notify", "Test notification shown ($channel)")
        return true
    }

    /**
     * The alarm: a full-screen screen over the lock screen when the phone is locked or idle, a heads-up
     * banner when you're using it. The sound loops ("insistent") until you answer or it times out.
     */
    fun showAlarm(context: Context, title: String, text: String, scheduledAt: Long, lateMs: Long) {
        val fullScreen = PendingIntent.getActivity(
            context, 0,
            AlarmActivity.intent(context, title, text, scheduledAt, lateMs),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Channels.ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "Dismiss", dismiss)
            .setOngoing(true)
            .setTimeoutAfter(ALARM_TIMEOUT_MS)
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        if (!post(context, ID_ALARM, n)) Diag.log("alarm", "Couldn't show the alarm: notifications are off")
    }

    fun cancelAlarm(context: Context) = NotificationManagerCompat.from(context).cancel(ID_ALARM)

    private fun post(context: Context, id: Int, n: Notification): Boolean = try {
        NotificationManagerCompat.from(context).notify(id, n)
        true
    } catch (e: SecurityException) {
        false
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
