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
        handle(intent)
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

    /** Text shared from another app (WhatsApp, an email, a link), or the "New task" shortcut. */
    private fun handle(intent: Intent?) {
        val app = application as ReminderApp
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")
                if (text.isNotEmpty()) app.shared.value = text
            }
            ACTION_NEW_TASK -> app.newTask.value += 1
        }
        if (intent?.action == Intent.ACTION_SEND || intent?.action == ACTION_NEW_TASK) setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val ACTION_NEW_TASK = "com.prominder.myapp.NEW_TASK"
    }
}
