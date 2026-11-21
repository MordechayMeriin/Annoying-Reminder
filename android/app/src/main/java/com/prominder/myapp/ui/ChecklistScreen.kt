package com.prominder.myapp.ui

import com.prominder.myapp.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.prominder.myapp.setup.CheckId
import com.prominder.myapp.setup.CheckItem
import com.prominder.myapp.ui.theme.Palette

/**
 * The first-run checklist. Each row checks itself when you come back from the settings screen
 * (except Samsung's sleeping apps, which Android can't report, so you tick it).
 */
@Composable
fun ChecklistScreen(
    items: List<CheckItem>,
    onOpen: (CheckId) -> Unit,
    onConfirm: (CheckId, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val done = items.count { it.done }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        TopBar(stringResource(R.string.setup_title), onBack = onBack)
        Muted(stringResource(R.string.setup_intro, done, items.size), Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item -> ChecklistRow(item, onOpen, onConfirm) }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (done == items.size) R.string.done else R.string.later))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ChecklistRow(item: CheckItem, onOpen: (CheckId) -> Unit, onConfirm: (CheckId, Boolean) -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            StatusDot(item.done)
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(item.title), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Muted(item.whyArg?.let { stringResource(item.why, stringResource(it)) } ?: stringResource(item.why))
                if (!item.done || item.confirmedByUser) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!item.done) {
                            OutlinedButton(onClick = { onOpen(item.id) }) { Text(stringResource(item.action)) }
                        }
                        if (item.confirmedByUser) {
                            Spacer(Modifier.width(8.dp))
                            Checkbox(
                                checked = item.done,
                                onCheckedChange = { onConfirm(item.id, it) },
                                colors = CheckboxDefaults.colors(checkedColor = Palette.Ok),
                            )
                            Text(stringResource(R.string.done))
                        }
                    }
                }
            }
        }
    }
}
