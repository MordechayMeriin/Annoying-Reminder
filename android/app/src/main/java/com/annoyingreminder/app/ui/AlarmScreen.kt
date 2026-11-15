package com.annoyingreminder.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annoyingreminder.app.ui.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The full-screen alarm. Phase E adds ✓ / ⏰ / Open; for now it rings and you dismiss it. */
@Composable
fun AlarmScreen(
    title: String,
    text: String,
    scheduledAt: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    onDismiss: () -> Unit,
) {
    val time = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(scheduledAt).atZone(zone))
    Column(
        Modifier.fillMaxSize().background(Palette.Bg).safeDrawingPadding().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🔔", fontSize = 64.sp)
        Spacer(Modifier.height(16.dp))
        Text(time, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Muted(text)
        Spacer(Modifier.height(48.dp))
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Dismiss", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
