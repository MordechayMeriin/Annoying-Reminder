package com.prominder.myapp.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prominder.myapp.ui.theme.Palette

data class HandoverState(
    /** The data was already moved to a phone (this is a reinstall or a new phone): restore it. Null: not known yet. */
    val alreadyHandedOver: Boolean? = null,
    /** When the backup was last updated, for a restore ("Today 14:02"). */
    val lastBackup: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * The phone holds none of your data yet. The first time, this is the handover: the phone downloads everything and
 * from then on it owns the data. After a reinstall (or on a new phone) it restores the backup.
 */
@Composable
fun HandoverScreen(state: HandoverState, onMove: () -> Unit, onLogout: () -> Unit) {
    val restore = state.alreadyHandedOver == true
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        TopBar("🔔 ProMinder") { TextButton(onClick = onLogout) { Text("Log out", color = Palette.Muted) } }
        AppCard {
            Text(if (restore) "Restore your data" else "Move your data to this phone", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (restore) {
                Muted("Your tasks and chat live on your phone and are backed up" +
                    (state.lastBackup?.let { " (last backup: $it)" } ?: "") + ". This downloads the backup onto this phone.")
            } else {
                Muted("Your tasks, Today's 3, the chat history and your settings are downloaded once. From then on this phone " +
                    "keeps them, works without internet, and backs them up to the server once a day (and when you leave " +
                    "the app on Wi-Fi).")
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onMove, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(when {
                state.busy -> "Downloading…"
                restore -> "Restore my data"
                else -> "Move my data to this phone"
            })
        }
        state.message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = if (state.busy) Palette.Muted else Palette.Accent)
        }
    }
}
