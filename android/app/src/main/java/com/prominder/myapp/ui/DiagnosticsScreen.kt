package com.prominder.myapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.ui.theme.Palette

/** Phase A's test buttons, kept here: notifications, the alarm, and the Doze test. */
data class PhoneTests(
    /** "Test alarm set for 14:05:00", or null when none is waiting. */
    val pendingAlarm: String? = null,
    val lastAlarmResult: String? = null,
    /** A short answer to the last tap ("Notifications are off: see the checklist"). */
    val notice: String? = null,
    val onNotifyNow: () -> Unit = {},
    val onUrgentNow: () -> Unit = {},
    val onAlarmIn1Min: () -> Unit = {},
    val onAlarmIn1Hour: () -> Unit = {},
    val onCancelAlarm: () -> Unit = {},
)

/**
 * What the app knows about itself: version, phone, permissions, the backup, the reminders (next check, last tick),
 * the last test alarm, and its log. Every tick writes why it sent or held back each message ([tick] lines).
 * Share sends all of it as text (paste it into the chat with Claude when something's off).
 */
@Composable
fun DiagnosticsScreen(
    facts: List<Pair<String, String>>,
    logLines: List<String>,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onSyncNow: (() -> Unit)? = null,
    /** Run a tick now (its result goes into the log below, tagged [tick]). */
    onTickNow: (() -> Unit)? = null,
    tests: PhoneTests? = null,
) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        item { TopBar("Diagnostics", onBack = onBack) }
        item {
            AppCard {
                facts.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(k, color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.weight(0.45f))
                        Text(v, fontSize = 14.sp, modifier = Modifier.weight(0.55f))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share") }
                OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) { Text("Clear log") }
            }
            onSyncNow?.let {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
            }
            onTickNow?.let {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Check reminders now") }
            }
            tests?.let { PhoneTestsCard(it) }
            SectionTitle("Log, newest first")
        }
        if (logLines.isEmpty()) {
            item { Muted("Nothing logged yet.", Modifier.padding(4.dp)) }
        }
        items(logLines.asReversed()) { line ->
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp, horizontal = 4.dp)) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false,
                    fontWeight = if ("[crash]" in line) FontWeight.Bold else FontWeight.Normal,
                    color = if ("[crash]" in line) Palette.Danger else Palette.Text)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun PhoneTestsCard(t: PhoneTests) {
    SectionTitle("Tests")
    AppCard {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = t.onNotifyNow, modifier = Modifier.weight(1f)) { Text("Notify me now") }
            OutlinedButton(onClick = t.onUrgentNow, modifier = Modifier.weight(1f)) { Text("Urgent now") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = t.onAlarmIn1Min, modifier = Modifier.weight(1f)) { Text("Alarm in 1 min") }
            OutlinedButton(onClick = t.onAlarmIn1Hour, modifier = Modifier.weight(1f)) { Text("Alarm in 1 hour") }
        }
        Spacer(Modifier.height(8.dp))
        Muted("The Doze test: set the 1-hour alarm, lock the phone and leave it alone. It should ring on time.")
        t.pendingAlarm?.let {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⏰ $it", modifier = Modifier.weight(1f))
                TextButton(onClick = t.onCancelAlarm) { Text("Cancel", color = Palette.Muted) }
            }
        }
        t.lastAlarmResult?.let {
            Spacer(Modifier.height(8.dp))
            Muted("Last test: $it")
        }
        t.notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Palette.Accent)
        }
    }
}
