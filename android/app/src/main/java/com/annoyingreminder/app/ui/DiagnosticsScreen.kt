package com.annoyingreminder.app.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.annoyingreminder.app.ui.theme.Palette

/**
 * What the app knows about itself: version, phone, permissions, the last test alarm, and its log.
 * Share sends all of it as text (paste it into the chat with Claude when something's off).
 */
@Composable
fun DiagnosticsScreen(
    facts: List<Pair<String, String>>,
    logLines: List<String>,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
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
