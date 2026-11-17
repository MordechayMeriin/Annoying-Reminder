package com.prominder.myapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Notifier
import com.prominder.myapp.remind.Reminders
import kotlinx.coroutines.launch

/**
 * Runs when an alarm goes off (even with the screen off and the app closed): the next tick, or the test alarm.
 * Also the notification buttons: Dismiss, ✓ Done and Later.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TICK -> background(context) { app ->
                Reminders.tick(app, app.store, "alarm", intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis()))
            }
            ACTION_DONE, ACTION_LATER -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
                Notifier.cancel(context, intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
                background(context) { app ->
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
                    lateMs = late,
                )
            }
            ACTION_DISMISS -> {
                Notifier.cancelAlarm(context)
                Diag.log("alarm", "Alarm dismissed from the notification")
            }
        }
    }

    /** Work that touches the database, with the receiver kept alive until it's done (goAsync). */
    private fun background(context: Context, block: suspend (ReminderApp) -> Unit) {
        val app = context.applicationContext as ReminderApp
        // Null when called directly rather than by Android (a test): then there's nothing to keep alive.
        val pending: PendingResult? = goAsync()
        app.scope.launch {
            try {
                block(app)
            } catch (e: Exception) {
                Diag.log("tick", "Failed: ${e::class.java.simpleName}: ${e.message}")
            } finally {
                pending?.finish()
            }
        }
    }

    companion object {
        const val ACTION_TICK = "com.prominder.myapp.TICK"
        const val ACTION_DONE = "com.prominder.myapp.DONE"
        const val ACTION_LATER = "com.prominder.myapp.LATER"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val ACTION_TEST_ALARM = "com.prominder.myapp.TEST_ALARM"
        const val ACTION_DISMISS = "com.prominder.myapp.DISMISS_ALARM"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
