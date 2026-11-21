package com.prominder.myapp.alarm

import com.prominder.myapp.R
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.prominder.myapp.MainActivity
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Notifier
import com.prominder.myapp.ui.AlarmScreen
import com.prominder.myapp.ui.AlarmUi
import com.prominder.myapp.ui.theme.ReminderTheme
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The full-screen alarm, like the clock app's. It opens over the lock screen and turns the screen on (see the
 * manifest). The sound belongs to the alarm notification. A message's alarm (AlarmRings) has ✓ Done, ⏰ Later, Open
 * and Dismiss; each stops the repeats. The test alarm has Dismiss only. A ring that comes while the screen is open
 * updates it; the screen closes itself when the alarm is answered some other way (the notification, the chat).
 */
class AlarmActivity : AppCompatActivity() {
    private val ui = mutableStateOf(AlarmUi("", "", System.currentTimeMillis()))
    private var taskId: Long? = null
    private var isTest = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        read(intent)
        Diag.log("alarm", "Alarm screen shown")
        lifecycleScope.launch { AlarmRings.stops.drop(1).collect { if (!isTest) finish() } }
        setContent {
            ReminderTheme {
                AlarmScreen(
                    ui = ui.value,
                    onDone = { answer("✓ Done on the alarm screen") { app -> taskId?.let { app.store.complete(it) } } },
                    onLater = { answer("Later on the alarm screen") { app -> taskId?.let { app.store.later(it) } } },
                    onOpen = {
                        AlarmRings.stop(this, "Open on the alarm screen")
                        // Over the lock screen: ask to unlock, then the chat opens.
                        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
                        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    },
                    onDismiss = {
                        if (isTest) {
                            Notifier.cancelAlarm(this)
                            Diag.log("alarm", "Alarm dismissed from the alarm screen")
                        } else {
                            AlarmRings.stop(this, "dismissed on the alarm screen")
                        }
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
    }

    private fun read(intent: Intent) {
        isTest = intent.getBooleanExtra(EXTRA_TEST, true)
        taskId = intent.getLongExtra(EXTRA_TASK_ID, -1).takeIf { it >= 0 }
        ui.value = AlarmUi(
            title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.alarm_title),
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, System.currentTimeMillis()),
            doneLabel = taskId?.let { getString(R.string.action_done) },
            laterLabel = taskId?.let { getString(R.string.action_later) },
            canOpen = !isTest,
            rings = intent.getIntExtra(EXTRA_RINGS, 1),
        )
    }

    /** Stop the alarm, then act on the task in the background (the app may close this screen first). */
    private fun answer(why: String, block: suspend (ReminderApp) -> Unit) {
        AlarmRings.stop(this, why)
        val app = application as ReminderApp
        app.scope.launch { runCatching { block(app) }.onFailure { Diag.log("alarm", "Couldn't do it: ${it.message}") } }
        finish()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_SCHEDULED_AT = "scheduled_at"
        private const val EXTRA_TASK_ID = "task_id"
        private const val EXTRA_TEST = "test"
        private const val EXTRA_RINGS = "rings"

        /** The Diagnostics test alarm: Dismiss only. */
        fun forTest(context: Context, title: String, text: String, scheduledAt: Long): Intent =
            base(context).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_SCHEDULED_AT, scheduledAt)
                .putExtra(EXTRA_TEST, true)

        /** A message's alarm: its words, the task its buttons act on (if one), when it first rang. */
        fun forMessage(context: Context, r: AlarmRings.Ringing): Intent =
            base(context).putExtra(EXTRA_TITLE, r.title).putExtra(EXTRA_TEXT, r.text).putExtra(EXTRA_SCHEDULED_AT, r.startedAt)
                .putExtra(EXTRA_TASK_ID, r.taskId ?: -1).putExtra(EXTRA_TEST, false)
                .putExtra(EXTRA_RINGS, r.rings)

        private fun base(context: Context) = Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
