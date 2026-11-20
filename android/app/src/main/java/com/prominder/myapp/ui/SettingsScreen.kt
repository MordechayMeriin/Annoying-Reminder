package com.prominder.myapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.ui.theme.Palette

data class SettingItem(
    val key: String,
    val label: String,
    val help: String,
    val group: String,
    val kind: String,
    /** The current value, as stored text ("07:30", "true", "sun"). */
    val value: String,
    val isDefault: Boolean,
    val default: String,
    /** choice: (stored value, label) */
    val options: List<Pair<String, String>> = emptyList(),
)

private val WEEKDAY_LABELS = linkedMapOf("sun" to "Sunday", "mon" to "Monday", "tue" to "Tuesday", "wed" to "Wednesday",
    "thu" to "Thursday", "fri" to "Friday", "sat" to "Saturday")

/**
 * ⚙ Settings: every setting the app keeps (backed up with your data), minus Pushover's account and sounds and
 * browser notifications, which the phone's own notification channels replace (the loudness rules stay, under
 * "How loud"). Plus what you asked of the assistant.
 */
@Composable
fun SettingsScreen(
    items: List<SettingItem>,
    preferences: List<Pair<Long, String>>,
    /** Saves a value (null: back to the default); returns what's wrong with it, or null. */
    onSave: (String, String?) -> String?,
    onDeletePreference: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { TopBar("Settings", onBack = onBack) }
        item {
            Muted("They shape when reminders come, how loud they are, and the assistant's messages. A change applies " +
                "from the next reminder check. You can also just say it in the chat.")
        }
        items.groupBy { it.group }.forEach { (group, rows) ->
            item(key = "g$group") { SectionTitle(group) }
            item(key = "c$group") {
                AppCard {
                    rows.forEachIndexed { i, s ->
                        if (i > 0) Spacer(Modifier.height(4.dp))
                        SettingLine(s, onToggle = { onSave(s.key, if (s.value == "true") "false" else "true") }, onEdit = { editing = s.key })
                    }
                }
            }
        }
        item { SectionTitle("What you asked of the assistant") }
        item {
            AppCard {
                if (preferences.isEmpty()) Muted("Nothing yet. Say it in the chat (\"shorter messages\") once the assistant is back.")
                preferences.forEach { (id, text) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onDeletePreference(id) }) { Text("✕", color = Palette.Muted) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }

    items.firstOrNull { it.key == editing }?.let { s ->
        EditSettingDialog(s, onSave = { raw -> onSave(s.key, raw).also { if (it == null) editing = null } }, onDismiss = { editing = null })
    }
}

@Composable
private fun SettingLine(s: SettingItem, onToggle: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = if (s.kind == "bool") onToggle else onEdit).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.label, fontSize = 15.sp)
            if (s.help.isNotEmpty()) Text(s.help, fontSize = 12.sp, color = Palette.Muted)
        }
        if (s.kind == "bool") {
            Switch(checked = s.value == "true", onCheckedChange = { onToggle() })
        } else {
            Text(display(s), color = if (s.isDefault) Palette.Muted else Palette.Accent, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** The fixed answers a setting picks from (a radio list instead of typing), or null. */
private fun choices(s: SettingItem): Map<String, String>? = when (s.kind) {
    "weekday" -> WEEKDAY_LABELS
    "choice" -> s.options.toMap()
    else -> null
}

private fun display(s: SettingItem): String = when (s.kind) {
    "weekday", "choice" -> choices(s)?.get(s.value) ?: s.value
    "time_off" -> if (s.value == "off") "Off" else s.value
    "times" -> s.value.ifEmpty { "None" }
    else -> s.value
}

@Composable
private fun EditSettingDialog(s: SettingItem, onSave: (String?) -> String?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(s.value) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.label) },
        text = {
            Column {
                if (s.help.isNotEmpty()) Muted(s.help)
                Spacer(Modifier.height(8.dp))
                val options = choices(s)
                if (options != null) {
                    options.forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { text = value }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = text == value, onClick = { text = value })
                            Text(label)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it; error = null }, singleLine = true, isError = error != null,
                        supportingText = { Text(error ?: hint(s.kind)) },
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard(s.kind)),
                    )
                }
                if (options != null) error?.let { Text(it, color = Palette.Danger) }
                Muted("Default: ${display(s.copy(value = s.default))}")
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!s.isDefault) TextButton(onClick = { error = onSave(null) }) { Text("Default", color = Palette.Muted) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun hint(kind: String) = when (kind) {
    "time" -> "HH:MM, e.g. 07:30"
    "times" -> "Times separated by commas, e.g. 13:00, 17:00"
    "time_off" -> "HH:MM, or off"
    "timezone" -> "e.g. Asia/Jerusalem"
    else -> ""
}

private fun keyboard(kind: String) = when (kind) {
    "int" -> KeyboardType.Number
    "float" -> KeyboardType.Decimal
    else -> KeyboardType.Text
}
