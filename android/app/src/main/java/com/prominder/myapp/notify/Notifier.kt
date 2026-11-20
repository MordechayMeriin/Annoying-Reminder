package com.prominder.myapp.notify

import com.prominder.myapp.data.ChipAction
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
import com.prominder.myapp.alarm.AlarmRings
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.logic.ChipRules
import com.prominder.myapp.logic.Loudness
import com.prominder.myapp.remind.Notice
import java.time.format.DateTimeFormatter

/** Shows notifications: the check-ins and daily messages the tick decides on, alarms, the focus timer, and Phase A's tests. */
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
    fun showAlarm(context: Context, title: String, text: String, scheduledAt: Long) {
        val fullScreen = PendingIntent.getActivity(
            context, 0,
            AlarmActivity.forTest(context, title, text, scheduledAt),
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

    /** The running focus session's countdown: one at a time. */
    const val ID_SESSION = 4

    /**
     * A message from the tick, as loud as it decided: the normal or urgent channel, or (alarm) the repeating full-screen
     * alarm (alarm/AlarmRings.kt). The buttons are [Notice.actions]: ▶ Start 5 min · ✓ Done on a check-in about one task,
     * ✓ Done · ▶ Another round at a session's end. ▶ Start and ▶ Another round open the timer; ✓ Done works without
     * opening the app. Either counts as you answering.
     */
    fun showNotice(context: Context, notice: Notice): Boolean {
        if (notice.level == Loudness.ALARM) {
            if (!canNotify(context)) {
                Diag.log("notify", "Couldn't show message #${notice.messageId}: notifications are off")
                return false
            }
            AlarmRings.start(context, notice)
            return true
        }
        val id = ID_MESSAGES + (notice.messageId % 1_000_000).toInt()
        val urgent = notice.level == Loudness.URGENT
        val b = NotificationCompat.Builder(context, if (urgent) Channels.URGENT else Channels.NORMAL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        notice.taskId?.let { taskId -> for (a in notice.actions) b.addAction(taskButton(context, a, taskId, id, notice.lang)) }
        // Reply from the notification: the text goes to the assistant as a chat message (Phase D).
        b.addAction(replyAction(context, id, notice.lang))
        val shown = post(context, id, b.build())
        if (!shown) Diag.log("notify", "Couldn't show message #${notice.messageId}: notifications are off")
        return shown
    }

    /**
     * One ring of an alarm-level message: full screen over the lock screen (a heads-up banner while you use the phone),
     * the alarm sound looping for [ringMs], ✓ Done and Later when it's about one task, Dismiss otherwise.
     */
    fun showAlarmRing(context: Context, r: AlarmRings.Ringing, ringMs: Long) {
        val fullScreen = PendingIntent.getActivity(
            context, ID_ALARM,
            AlarmActivity.forMessage(context, r),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, Channels.ALARM)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText(r.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .setOngoing(true)
            .setTimeoutAfter(ringMs)
        if (r.taskId != null) {
            b.addAction(taskButton(context, "done", r.taskId, ID_ALARM, r.lang))
            b.addAction(taskButton(context, "later", r.taskId, ID_ALARM, r.lang))
        }
        b.addAction(0, "Dismiss", PendingIntent.getBroadcast(context, 1,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        if (!post(context, ID_ALARM, n)) Diag.log("alarm", "Couldn't show the alarm: notifications are off")
    }

    /** What the countdown shows: the session, its task's next action and title, and the buttons' language. */
    data class SessionInfo(val sessionId: Long, val step: String, val title: String, val endsAt: Long, val lang: String)

    /**
     * The running focus session, counting down in the notification shade and on the lock screen: silent, can't be
     * swiped away, gone at its end (the end check-in takes over). ✓ Done and Stop work from here; a tap opens the timer.
     */
    fun showSession(context: Context, info: SessionInfo, now: Long = System.currentTimeMillis()) {
        val until = DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(info.endsAt).atZone(java.time.ZoneId.systemDefault()))
        val n = NotificationCompat.Builder(context, Channels.SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏱ ${info.step}")
            .setContentText(if (info.step != info.title) "${info.title} · until $until" else "Until $until")
            .setWhen(info.endsAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter((info.endsAt - now).coerceAtLeast(1_000L))
            .setContentIntent(openSession(context))
            .addAction(0, ChipRules.chipLabel("done", info.lang), sessionEnd(context, info.sessionId, "done"))
            .addAction(0, ChipRules.chipLabel("stop", info.lang), sessionEnd(context, info.sessionId, "stopped"))
            .build()
        post(context, ID_SESSION, n)
    }

    fun cancelSession(context: Context) = cancel(context, ID_SESSION)

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

    /**
     * A notification button about one task: done and later are handled in the background (AlarmReceiver); start and
     * another open the app straight on the timer (an activity, as Android 12+ requires for opening a screen).
     */
    private fun taskButton(context: Context, action: String, taskId: Long, notificationId: Int, lang: String): NotificationCompat.Action {
        val code = notificationId * 8 + listOf("done", "later", "start", "another").indexOf(action).coerceAtLeast(0) + 4
        val intent = when (action) {
            "start", "another" -> PendingIntent.getActivity(
                context, code,
                Intent(context, MainActivity::class.java)
                    .setAction(if (action == ChipAction.START) MainActivity.ACTION_START_SESSION else MainActivity.ACTION_ANOTHER_ROUND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId).putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            else -> PendingIntent.getBroadcast(
                context, code,
                Intent(context, AlarmReceiver::class.java).setAction(if (action == ChipAction.LATER) AlarmReceiver.ACTION_LATER else AlarmReceiver.ACTION_DONE)
                    .putExtra(AlarmReceiver.EXTRA_TASK_ID, taskId).putExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val label = ChipRules.chipLabel(if (action == ChipAction.START) "start5" else action, lang)
        return NotificationCompat.Action.Builder(0, label, intent).build()
    }

    /** ✓ Done or Stop on the countdown. */
    private fun sessionEnd(context: Context, sessionId: Long, outcome: String): PendingIntent = PendingIntent.getBroadcast(
        context, if (outcome == "done") 5 else 6,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_SESSION_END)
            .putExtra(AlarmReceiver.EXTRA_SESSION_ID, sessionId).putExtra(AlarmReceiver.EXTRA_OUTCOME, outcome),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openSession(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 7,
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SESSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
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
