package com.prominder.myapp

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
    /** Leaving the app: back up what changed now, rather than in a couple of minutes. */
    override fun onStop() {
        super.onStop()
        val app = application as ReminderApp
        lifecycleScope.launch {
            if (app.store.hasData() && app.sync.pending() > 0) SyncScheduler.now(app)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = (application as ReminderApp).auth
        setContent {
            ReminderTheme {
                Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { AppRoot(auth) }
                }
            }
        }
    }
}
