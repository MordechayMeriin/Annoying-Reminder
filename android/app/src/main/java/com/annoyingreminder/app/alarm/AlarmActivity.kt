package com.annoyingreminder.app.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.annoyingreminder.app.diag.Diag
import com.annoyingreminder.app.notify.Notifier
import com.annoyingreminder.app.ui.AlarmScreen
import com.annoyingreminder.app.ui.theme.ReminderTheme

/**
 * The full-screen alarm, like the clock app's. It opens over the lock screen and turns the screen on
 * (see the manifest). The sound belongs to the alarm notification, so it keeps ringing until you tap
 * Dismiss here or on the notification.
 */
class AlarmActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarm"
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis())
        Diag.log("alarm", "Alarm screen shown")
        setContent {
            ReminderTheme {
                AlarmScreen(
                    title = title,
                    text = text,
                    scheduledAt = scheduledAt,
                    onDismiss = {
                        Notifier.cancelAlarm(this)
                        Diag.log("alarm", "Alarm dismissed from the alarm screen")
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SCHEDULED_AT = "scheduled_at"

        fun intent(context: Context, title: String, text: String, scheduledAt: Long, lateMs: Long): Intent =
            Intent(context, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
                .putExtra("late_ms", lateMs)
    }
}
