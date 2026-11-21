package com.prominder.myapp.ui

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.material3.LocalTextStyle
import java.util.Locale
import java.time.format.TextStyle
import com.prominder.myapp.i18n.AppLanguage
import com.prominder.myapp.data.SettingSpecs
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import com.prominder.myapp.R
import androidx.compose.ui.res.stringResource
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

/** A setting as the screen shows it: its words already in the app's language (DataRoutes.settingItems). */
data class SettingItem(
    val key: String,
    val label: String,
    val help: String,
    /** The section's title. */
    val group: String,
    val kind: String,
    /** The current value, as stored text ("07:30", "true", "sun"). */
    val value: String,
    val isDefault: Boolean,
    val default: String,
    /** choice: (stored value, label) */
    val options: List<Pair<String, String>> = emptyList(),
)

/** The weekdays as stored ("sun"), with their names in the app's language, Sunday first. */
private fun weekdayLabels(locale: Locale): Map<String, String> =
    listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat").associateWith {
        SettingSpecs.weekday(it).getDisplayName(TextStyle.FULL, locale)
    }

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
    /** The app's language: AppLanguage.SYSTEM ("") or one of AppLanguage.SUPPORTED. */
    language: String = AppLanguage.SYSTEM,
    onLanguage: (String) -> Unit = {},
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingLanguage by rememberSaveable { mutableStateOf(false) }
    val res = LocalResources.current
    val weekdays = remember(res.configuration) { weekdayLabels(Format.locale(res)) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item { TopBar(stringResource(R.string.settings_title), onBack = onBack) }
        item { Muted(stringResource(R.string.settings_intro)) }
        // On this phone only (like Android's own per-app language), so not backed up with the settings below.
        item { SectionTitle(stringResource(R.string.settings_language)) }
        item {
            AppCard {
                Row(Modifier.fillMaxWidth().clickable { pickingLanguage = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_language), fontSize = 15.sp)
                        Text(stringResource(R.string.settings_language_help), fontSize = 12.sp, color = Palette.Muted)
                    }
                    Text(languageName(language), color = if (language == AppLanguage.SYSTEM) Palette.Muted else Palette.Accent,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        items.groupBy { it.group }.forEach { (group, rows) ->
            item(key = "g$group") { SectionTitle(group) }
            item(key = "c$group") {
                AppCard {
                    rows.forEachIndexed { i, s ->
                        if (i > 0) Spacer(Modifier.height(4.dp))
                        SettingLine(s, weekdays, onToggle = { onSave(s.key, if (s.value == "true") "false" else "true") }, onEdit = { editing = s.key })
                    }
                }
            }
        }
        item { SectionTitle(stringResource(R.string.settings_asked)) }
        item {
            AppCard {
                if (preferences.isEmpty()) Muted(stringResource(R.string.settings_asked_empty))
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
        EditSettingDialog(s, weekdays, onSave = { raw -> onSave(s.key, raw).also { if (it == null) editing = null } }, onDismiss = { editing = null })
    }
    if (pickingLanguage) {
        LanguageDialog(language, onPick = { pickingLanguage = false; if (it != language) onLanguage(it) }, onDismiss = { pickingLanguage = false })
    }
}

/** A language's name: in its own words (as language pickers show them), or "Phone's language". */
@Composable
private fun languageName(tag: String): String = when (tag) {
    "en" -> stringResource(R.string.language_en)
    "he" -> stringResource(R.string.language_he)
    else -> stringResource(R.string.language_system)
}

@Composable
private fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                (listOf(AppLanguage.SYSTEM) + AppLanguage.SUPPORTED).forEach { tag ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(tag) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = tag == current, onClick = { onPick(tag) })
                        Text(languageName(tag))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SettingLine(s: SettingItem, weekdays: Map<String, String>, onToggle: () -> Unit, onEdit: () -> Unit) {
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
            // Times and numbers read left-to-right in Hebrew too ("13:00, 17:00", not "17:00 ,13:00").
            val dataValue = s.kind != "weekday" && s.kind != "choice" && !(s.kind == "time_off" && s.value == "off") && s.value.isNotEmpty()
            Text(display(s, weekdays, LocalResources.current), color = if (s.isDefault) Palette.Muted else Palette.Accent, fontWeight = FontWeight.SemiBold,
                style = LocalTextStyle.current.copy(textDirection = if (dataValue) TextDirection.Ltr else TextDirection.Content),
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** The fixed answers a setting picks from (a radio list instead of typing), or null. */
private fun choices(s: SettingItem, weekdays: Map<String, String>): Map<String, String>? = when (s.kind) {
    "weekday" -> weekdays
    "choice" -> s.options.toMap()
    else -> null
}

private fun display(s: SettingItem, weekdays: Map<String, String>, res: Resources): String = when (s.kind) {
    "weekday", "choice" -> choices(s, weekdays)?.get(s.value) ?: s.value
    "time_off" -> if (s.value == "off") res.getString(R.string.settings_off) else s.value
    "times" -> s.value.ifEmpty { res.getString(R.string.settings_none) }
    else -> s.value
}

@Composable
private fun EditSettingDialog(s: SettingItem, weekdays: Map<String, String>, onSave: (String?) -> String?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(s.value) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.label) },
        text = {
            Column {
                if (s.help.isNotEmpty()) Muted(s.help)
                Spacer(Modifier.height(8.dp))
                val options = choices(s, weekdays)
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
                        supportingText = { Text(error ?: hint(s.kind)?.let { stringResource(it) }.orEmpty()) },
                        keyboardOptions = KeyboardOptions(keyboardType = keyboard(s.kind)),
                    )
                }
                if (options != null) error?.let { Text(it, color = Palette.Danger) }
                Muted(stringResource(R.string.settings_default_is, display(s.copy(value = s.default), weekdays, LocalResources.current)))
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = {
            Row {
                if (!s.isDefault) TextButton(onClick = { error = onSave(null) }) { Text(stringResource(R.string.settings_default), color = Palette.Muted) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

private fun hint(kind: String): Int? = when (kind) {
    "time" -> R.string.settings_hint_time
    "times" -> R.string.settings_hint_times
    "time_off" -> R.string.settings_hint_time_off
    "timezone" -> R.string.settings_hint_timezone
    else -> null
}

private fun keyboard(kind: String) = when (kind) {
    "int" -> KeyboardType.Number
    "float" -> KeyboardType.Decimal
    else -> KeyboardType.Text
}
