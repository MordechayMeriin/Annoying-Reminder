package com.prominder.myapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.diag.Diag
import androidx.core.app.RemoteInput
import com.prominder.myapp.notify.Notifier
import com.prominder.myapp.data.endSession
import com.prominder.myapp.remind.Reminders
import kotlinx.coroutines.launch

/**
 * Runs when an alarm goes off (even with the screen off and the app closed): the next tick, an alarm's next ring, or
 * the test alarm. Also the notification buttons: Dismiss, ✓ Done, Later, and the focus timer's ✓ Done and Stop.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TICK -> background(context, "tick", "Tick failed") { app ->
                Reminders.tick(app, app.store, "alarm", intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis()))
            }
            ACTION_ALARM_RING -> AlarmRings.ringAgain(context)
            ACTION_DONE, ACTION_LATER -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                if (notificationId == Notifier.ID_ALARM) {
                    AlarmRings.stop(context, (if (intent.action == ACTION_DONE) "✓ Done" else "Later") + " on the alarm's notification")
                }
                Notifier.cancel(context, notificationId)
                val button = if (intent.action == ACTION_DONE) "✓ Done" else "Later"
                background(context, "notify", "$button from the notification failed") { app ->
                    val title = app.store.dao.task(taskId)?.title ?: "task #$taskId"
                    if (intent.action == ACTION_DONE) {
                        app.store.complete(taskId)
                        Diag.log("notify", "✓ Done from the notification: $title")
                    } else {
                        val at = app.store.later(taskId)
                        Diag.log("notify", "Later from the notification: $title" + (at?.let { " (next check-in ${Diag.log.format(it.toEpochMilli())})" } ?: ""))
                    }
                }
            }
            ACTION_SESSION_END -> {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1)
                val outcome = intent.getStringExtra(EXTRA_OUTCOME) ?: "stopped"
                Notifier.cancelSession(context)
                background(context, "notify", "The focus timer's ${if (outcome == "done") "✓ Done" else "Stop"} failed") { app ->
                    app.store.endSession(sessionId, outcome)
                    Diag.log("notify", "Focus session #$sessionId: ${if (outcome == "done") "✓ Done" else "Stop"} from the timer's notification")
                }
            }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(EXTRA_REPLY)?.toString()?.trim().orEmpty()
                val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)
                if (text.isEmpty()) {
                    Notifier.cancel(context, notificationId)
                } else {
                    // Android waits for the notification to change after a reply; the answer comes in a new one.
                    Notifier.showSent(context, notificationId, text)
                    ReplyWorker.enqueue(context, text)
                    Diag.log("notify", "Reply from a notification sent to the assistant")
                }
            }
            ACTION_TEST_ALARM -> {
                val now = System.currentTimeMillis()
                val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, now)
                val late = now - scheduledAt
                val result = TestAlarm.markRang(context, scheduledAt, late)
                Diag.log("alarm", "Test alarm rang. $result")
                Notifier.showAlarm(
                    context,
                    title = "Test alarm",
                    text = "Rang ${Timing.describeLateness(late)}. Tap Dismiss to stop it.",
                    scheduledAt = scheduledAt,
                )
            }
            ACTION_DISMISS -> {
                if (AlarmRings.current(context) != null) {
                    AlarmRings.stop(context, "dismissed on its notification")
                } else {
                    Notifier.cancelAlarm(context)
                    Diag.log("alarm", "Alarm dismissed from the notification")
                }
            }
        }
    }

    /**
     * Work that touches the database, with the receiver kept alive until it's done (goAsync). A failure is logged under
     * [tag] as "[what]: …", so Diagnostics shows a failed button as a button, not as a failed tick.
     */
    private fun background(context: Context, tag: String, what: String, block: suspend (ReminderApp) -> Unit) {
        val app = context.applicationContext as ReminderApp
        // Null when called directly rather than by Android (a test): then there's nothing to keep alive.
        val pending: PendingResult? = goAsync()
        app.scope.launch {
            try {
                block(app)
            } catch (e: Exception) {
                Diag.log(tag, "$what: ${e::class.java.simpleName}: ${e.message}")
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.prominder.myapp.TICK"
        const val ACTION_DONE = "com.prominder.myapp.DONE"
        const val ACTION_LATER = "com.prominder.myapp.LATER"
        const val ACTION_REPLY = "com.prominder.myapp.REPLY"
        const val ACTION_ALARM_RING = "com.prominder.myapp.ALARM_RING"
        const val ACTION_SESSION_END = "com.prominder.myapp.SESSION_END"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_OUTCOME = "outcome"
        const val EXTRA_REPLY = "reply"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val ACTION_TEST_ALARM = "com.prominder.myapp.TEST_ALARM"
        const val ACTION_DISMISS = "com.prominder.myapp.DISMISS_ALARM"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
