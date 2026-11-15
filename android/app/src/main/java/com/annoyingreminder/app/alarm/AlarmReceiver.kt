package com.annoyingreminder.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.annoyingreminder.app.diag.Diag
import com.annoyingreminder.app.notify.Notifier

/** Runs when an alarm goes off (even with the screen off and the app closed), and for "Dismiss". */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
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

    companion object {
        const val ACTION_TEST_ALARM = "com.annoyingreminder.app.TEST_ALARM"
        const val ACTION_DISMISS = "com.annoyingreminder.app.DISMISS_ALARM"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
