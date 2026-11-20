package com.prominder.myapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.prominder.myapp.alarm.AlarmReceiver
import com.prominder.myapp.data.startSession
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.notify.Notifier
import com.prominder.myapp.sync.SyncScheduler
import kotlinx.coroutines.launch
import com.prominder.myapp.ui.AppRoot
import com.prominder.myapp.ui.theme.Palette
import com.prominder.myapp.ui.theme.ReminderTheme


class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as ReminderApp).visible = true
    }

    /** Leaving the app: back up what changed now, rather than in a couple of minutes. */
    override fun onStop() {
        super.onStop()
        val app = application as ReminderApp
        app.visible = false
        lifecycleScope.launch {
            if (app.store.hasData() && app.sync.pending() > 0) SyncScheduler.now(app)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = (application as ReminderApp).auth
        // Only on a fresh start: a rotation recreates the screen with the same intent (▶ Start mustn't run twice).
        if (savedInstanceState == null) handle(intent)
        setContent {
            ReminderTheme {
                Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { AppRoot(auth) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /**
     * Text shared from another app (WhatsApp, an email, a link), the "New task" shortcut, or a notification's ▶ Start /
     * ▶ Another round (the session starts, then the timer opens) and the countdown (the timer opens).
     */
    private fun handle(intent: Intent?) {
        val app = application as ReminderApp
        when (intent?.action) {
            ACTION_START_SESSION, ACTION_ANOTHER_ROUND -> {
                val taskId = intent.getLongExtra(AlarmReceiver.EXTRA_TASK_ID, -1)
                val another = intent.action == ACTION_ANOTHER_ROUND
                Notifier.cancel(this, intent.getIntExtra(AlarmReceiver.EXTRA_NOTIFICATION_ID, 0))
                app.scope.launch {
                    val started = app.store.startSession(taskId, if (another) null else Logic.START_MIN_NOTIFICATION, another = another)
                    Diag.log("notify", (if (another) "▶ Another round" else "▶ Start") + " from a notification: " +
                        if (started != null) "${started.plannedMin} min on task #$taskId" else "task #$taskId isn't open")
                    if (started != null) app.openSession.value += 1
                }
            }
            ACTION_OPEN_SESSION -> app.openSession.value += 1
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
                if (text.isNotEmpty()) app.shared.value = text
            }
            ACTION_NEW_TASK -> app.newTask.value += 1
        }
        if (intent?.action in HANDLED) setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val ACTION_NEW_TASK = "com.prominder.myapp.NEW_TASK"
        const val ACTION_START_SESSION = "com.prominder.myapp.START_SESSION"
        const val ACTION_ANOTHER_ROUND = "com.prominder.myapp.ANOTHER_ROUND"
        const val ACTION_OPEN_SESSION = "com.prominder.myapp.OPEN_SESSION"

        /** Handled once: a rotation or a return to the app mustn't run them again. */
        private val HANDLED = setOf(Intent.ACTION_SEND, ACTION_NEW_TASK, ACTION_START_SESSION, ACTION_ANOTHER_ROUND, ACTION_OPEN_SESSION)
    }
}
