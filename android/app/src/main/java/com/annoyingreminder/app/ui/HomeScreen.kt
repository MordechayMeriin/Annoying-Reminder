package com.annoyingreminder.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.annoyingreminder.app.ui.theme.Palette

data class HomeState(
    val email: String,
    val setupDone: Int,
    val setupTotal: Int,
    /** "Test alarm set for 14:05:00 (exact)", or null when none is waiting. */
    val pendingAlarm: String? = null,
    /** The last test alarm's result, e.g. "Set for 14:05:00, rang on time". */
    val lastAlarmResult: String? = null,
    /** A short answer to the last tap ("Notifications are off: see the checklist"). */
    val notice: String? = null,
)

/**
 * Phase A's home: the app can log in, notify and ring, nothing more yet. Your tasks move here in Phase B.
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onOpenChecklist: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onNotifyNow: () -> Unit,
    onUrgentNow: () -> Unit,
    onAlarmIn1Min: () -> Unit,
    onAlarmIn1Hour: () -> Unit,
    onCancelAlarm: () -> Unit,
    onLogout: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        TopBar("🔔 Reminder") {
            TextButton(onClick = onLogout) { Text("Log out", color = Palette.Muted) }
        }
        AppCard {
            Text("Hello, Android", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Muted(
                "Logged in as ${state.email}. This first version only tests the phone: notifications, alarms and " +
                    "the settings they need. Your tasks and chat move here in the next phase.",
            )
        }

        SectionTitle("Setup")
        val allDone = state.setupDone == state.setupTotal
        AppCard(Modifier.clickable(onClick = onOpenChecklist)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(allDone)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (allDone) "The phone is ready" else "${state.setupDone} of ${state.setupTotal} done",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Muted(if (allDone) "Reminders can arrive on time." else "A few settings, so reminders arrive on time.")
                }
                Text("›", color = Palette.Muted, style = MaterialTheme.typography.titleLarge)
            }
        }

        SectionTitle("Tests")
        AppCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNotifyNow, modifier = Modifier.weight(1f)) { Text("Notify me now") }
                OutlinedButton(onClick = onUrgentNow, modifier = Modifier.weight(1f)) { Text("Urgent now") }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAlarmIn1Min, modifier = Modifier.weight(1f)) { Text("Alarm in 1 min") }
                OutlinedButton(onClick = onAlarmIn1Hour, modifier = Modifier.weight(1f)) { Text("Alarm in 1 hour") }
            }
            Spacer(Modifier.height(8.dp))
            Muted("The Doze test: set the 1-hour alarm, lock the phone and leave it alone. It should ring on time.")
            state.pendingAlarm?.let {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⏰ $it", modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelAlarm) { Text("Cancel", color = Palette.Muted) }
                }
            }
            state.lastAlarmResult?.let {
                Spacer(Modifier.height(8.dp))
                Muted("Last test: $it")
            }
            state.notice?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Palette.Accent)
            }
        }

        SectionTitle("Help")
        AppCard(Modifier.clickable(onClick = onOpenDiagnostics)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Diagnostics", fontWeight = FontWeight.SemiBold)
                    Muted("The app's own log, to share when something's off.")
                }
                Text("›", color = Palette.Muted, style = MaterialTheme.typography.titleLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
