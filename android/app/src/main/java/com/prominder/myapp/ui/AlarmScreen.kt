package com.prominder.myapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the alarm screen offers: ✓ Done and ⏰ Later for an alarm about one task, Open for any message, Dismiss always. */
data class AlarmUi(
    val title: String,
    val text: String,
    val scheduledAt: Long,
    val doneLabel: String? = null,
    val laterLabel: String? = null,
    val canOpen: Boolean = false,
    /** How many times it has rung, from the second ring on ("Rang 3 times"). */
    val rings: Int = 1,
)

/**
 * The full-screen alarm, over the lock screen. A message's alarm rings again every few minutes until you answer:
 * ✓ Done and ⏰ Later act on the task, Open goes to the chat, Dismiss only stops it (you've seen it). The test alarm
 * has Dismiss only.
 */
@Composable
fun AlarmScreen(
    ui: AlarmUi,
    zone: ZoneId = ZoneId.systemDefault(),
    onDone: () -> Unit = {},
    onLater: () -> Unit = {},
    onOpen: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ui.scheduledAt).atZone(zone))
    Column(
        Modifier.fillMaxSize().background(Palette.Bg).safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔔", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(time, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
        if (ui.rings > 1) Muted("Rang ${ui.rings} times")
        Spacer(Modifier.height(8.dp))
        Text(ui.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Muted(ui.text)
        Spacer(Modifier.height(40.dp))
        if (ui.doneLabel != null) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Ok, contentColor = Palette.Bg)) {
                Text(ui.doneLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.laterLabel != null) {
                OutlinedButton(onClick = onLater, modifier = Modifier.weight(1f).height(56.dp)) { Text("⏰ ${ui.laterLabel}", fontSize = 18.sp) }
            }
            if (ui.canOpen) {
                OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f).height(56.dp)) { Text("Open", fontSize = 18.sp) }
            }
        }
        if (ui.doneLabel == null && !ui.canOpen) {
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Dismiss", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Dismiss", color = Palette.Muted, fontSize = 16.sp) }
        }
    }
}
