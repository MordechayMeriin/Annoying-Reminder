package com.prominder.myapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.prominder.myapp.data.TaskForm
import com.prominder.myapp.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Add or edit a task by hand. Only the title is needed; everything else is optional, as on the web app,
 * where the assistant filled these in from a sentence (it comes back in Phase D).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskFormScreen(
    heading: String,
    initial: TaskForm,
    zone: ZoneId,
    /** Saves; returns what's wrong, or null when it saved. */
    onSave: (TaskForm) -> String?,
    onBack: () -> Unit,
) {
    val start = initial.dueAt?.atZone(zone)
    var title by rememberSaveable { mutableStateOf(initial.title) }
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    var priority by rememberSaveable { mutableStateOf(initial.priority) }
    var date by rememberSaveable { mutableStateOf(start?.toLocalDate()) }
    var time by rememberSaveable { mutableStateOf(start?.toLocalTime()?.withSecond(0)?.withNano(0)) }
    var recurrence by rememberSaveable { mutableStateOf(initial.recurrence) }
    var nextAction by rememberSaveable { mutableStateOf(initial.nextAction.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial.description.orEmpty()) }
    var estimate by rememberSaveable { mutableStateOf(initial.estimateMin?.toString().orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickTime by rememberSaveable { mutableStateOf(false) }

    fun save() {
        val est = estimate.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 }
        error = when {
            title.isBlank() -> "Give it a title."
            kind != "task" && date == null -> "A ${Format.KIND_LABELS[kind]?.lowercase()} needs a date and time."
            est != null && est !in 1..10000 -> "The estimate is minutes, 1 to 10000."
            else -> null
        }
        if (error != null) return
        val due = date?.let { ZonedDateTime.of(it, time ?: DEFAULT_TIME, zone).toInstant() }
        error = onSave(TaskForm(title.trim(), kind, priority, due, recurrence.takeIf { due != null }, nextAction.trim().ifEmpty { null },
            description.trim().ifEmpty { null }, est))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TopBar(heading, onBack = onBack) { TextButton(onClick = ::save) { Text("Save") } }
        OutlinedTextField(
            value = title, onValueChange = { title = it.take(200) }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )

        SectionTitle("Kind")
        Chips(Format.KIND_LABELS, kind) { kind = it }

        SectionTitle("When")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate = true }) { Text(date?.format(DATE) ?: "Pick a date") }
            if (date != null) {
                OutlinedButton(onClick = { pickTime = true }) { Text((time ?: DEFAULT_TIME).format(HM)) }
                TextButton(onClick = { date = null; time = null; recurrence = null }) { Text("Clear", color = Palette.Muted) }
            }
        }
        if (date == null) Muted("No date: it stays quiet until it's in Today's 3 or you tap ⏰ Later.", Modifier.padding(top = 4.dp))
        if (kind == "meeting" && date != null) Muted("You're reminded before it starts (⚙ Settings → Meetings).", Modifier.padding(top = 4.dp))

        if (date != null) {
            SectionTitle("Repeat")
            Chips(mapOf<String?, String>(null to "No") + Format.RECURRENCE_LABELS, recurrence) { recurrence = it }
        }

        if (kind == "task") {
            SectionTitle("How important")
            Chips(Format.PRIORITY_LABELS, priority) { priority = it }
        }

        SectionTitle("Details (optional)")
        OutlinedTextField(
            value = nextAction, onValueChange = { nextAction = it.take(200) }, label = { Text("Next step: the first small action") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = estimate, onValueChange = { estimate = it.filter(Char::isDigit).take(5) }, label = { Text("Takes about (minutes)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = description, onValueChange = { description = it.take(800) }, label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
        )

        error?.let { Text(it, color = Palette.Danger, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
        Spacer(Modifier.height(32.dp))
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                    if (time == null) pickTime = true
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val t = time ?: DEFAULT_TIME
        val state = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(state) },
        )
    }
}

@Composable
private fun <T> Chips(options: Map<T, String>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

private val DEFAULT_TIME: LocalTime = LocalTime.of(9, 0)
private val DATE = DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)
private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
