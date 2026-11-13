package com.annoyingreminder.app

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
import com.annoyingreminder.app.ui.AppRoot
import com.annoyingreminder.app.ui.theme.Palette
import com.annoyingreminder.app.ui.theme.ReminderTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val auth = (application as ReminderApp).auth
        // Keep the login fresh while there's internet; offline, the saved login keeps working.
        lifecycleScope.launch { runCatching { auth.accessToken() } }
        setContent {
            ReminderTheme {
                Surface(color = Palette.Bg, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.safeDrawingPadding()) { AppRoot(auth) }
                }
            }
        }
    }
}
