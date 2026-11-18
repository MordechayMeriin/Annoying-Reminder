package com.prominder.myapp.notify

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.prominder.myapp.MainActivity
import com.prominder.myapp.R
import com.prominder.myapp.alarm.AlarmActivity
import com.prominder.myapp.alarm.AlarmReceiver
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.remind.Notice

/** Shows notifications: the check-ins and daily messages the tick decides on, and Phase A's tests. */
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

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Message notifications get ids from here up (the message id), so each message is its own notification. */
    private const val ID_MESSAGES = 1000

    /**
     * A message from the tick, as loud as it decided: the normal or urgent channel, or (alarm) the alarm channel
     * with the full-screen alarm, ringing until you answer it or [Notice.alarmSeconds] pass. A check-in about one
     * task gets ✓ Done and Later, which work without opening the app (and count as you answering).
     */
    fun showNotice(context: Context, notice: Notice): Boolean {
        val alarm = notice.level == Logic.ALARM
        val id = if (alarm) ID_ALARM else ID_MESSAGES + (notice.messageId % 1_000_000).toInt()
        val channel = when (notice.level) {
            Logic.ALARM -> Channels.ALARM
            Logic.URGENT -> Channels.URGENT
            else -> Channels.NORMAL
        }
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setPriority(if (notice.level >= Logic.URGENT) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (notice.level >= Logic.URGENT) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        notice.taskId?.let { taskId ->
            b.addAction(0, Logic.chipLabel("done", notice.lang), taskAction(context, AlarmReceiver.ACTION_DONE, taskId, id))
            b.addAction(0, Logic.chipLabel("later", notice.lang), taskAction(context, AlarmReceiver.ACTION_LATER, taskId, id))
        }
        // Reply from the notification: the text goes to the assistant as a chat message (Phase D).
        if (!alarm) b.addAction(replyAction(context, id, notice.lang))
        if (alarm) {
            val fullScreen = PendingIntent.getActivity(
                context, id,
                AlarmActivity.intent(context, notice.title, notice.text, System.currentTimeMillis(), 0),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreen, true)
                .setOngoing(true)
                .setTimeoutAfter(notice.alarmSeconds * 1000)
            if (notice.taskId == null) {
                b.addAction(0, "Dismiss", PendingIntent.getBroadcast(context, 1,
                    Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DISMISS),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        val n = b.build()
        if (alarm) n.flags = n.flags or Notification.FLAG_INSISTENT
        val shown = post(context, id, n)
        if (!shown) Diag.log("notify", "Couldn't show message #${notice.messageId}: notifications are off")
        return shown
    }

    /** The assistant's answer to a reply typed into a notification (when the app isn't open). */
    fun showReply(context: Context, text: String, messageId: Long, lang: String) {
        val id = ID_MESSAGES + (messageId % 1_000_000).toInt()
        val n = NotificationCompat.Builder(context, Channels.NORMAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("💬 ProMinder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .addAction(replyAction(context, id, lang))
            .build()
        post(context, id, n)
    }

    /** After a reply: the notification shows what you wrote, without its buttons, until the answer comes. */
    fun showSent(context: Context, notificationId: Int, text: String) {
        val n = NotificationCompat.Builder(context, Channels.NORMAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("💬 ProMinder")
            .setContentText("Sent: $text")
            .setRemoteInputHistory(arrayOf<CharSequence>(text))
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(2 * 60 * 1000L)
            .setAutoCancel(true)
            .build()
        post(context, notificationId, n)
    }

    private fun replyAction(context: Context, notificationId: Int, lang: String): NotificationCompat.Action {
        val label = if (lang == "he") "לענות" else "Reply"
        val input = RemoteInput.Builder(AlarmReceiver.EXTRA_REPLY).setLabel(label).build()
        // Mutable: Android writes the typed text into this intent.
        val intent = PendingIntent.getBroadcast(
            context, notificationId * 4 + 3,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_REPLY).putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(0, label, intent).addRemoteInput(input).setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build()
    }

    private fun taskAction(context: Context, action: String, taskId: Long, notificationId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, notificationId * 4 + if (action == AlarmReceiver.ACTION_DONE) 1 else 2,
            Intent(context, AlarmReceiver::class.java).setAction(action)
                .putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId).putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
