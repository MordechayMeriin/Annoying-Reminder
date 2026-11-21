package com.prominder.myapp.ui

import androidx.compose.runtime.remember
import com.prominder.myapp.R
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.prominder.myapp.data.TaskKind
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
 * Add or edit a task by hand. Only the title is needed; everything else is optional,
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
    val res = LocalResources.current
    val dateFormat = remember(res.configuration) { Format.datePattern(res, "EEEdMMMyyyy") }

    fun save() {
        val est = estimate.trim().takeIf { it.isNotEmpty() }?.let { it.toIntOrNull() ?: -1 }
        error = when {
            title.isBlank() -> res.getString(R.string.form_needs_title)
            kind == TaskKind.MEETING && date == null -> res.getString(R.string.form_meeting_needs_date)
            kind == TaskKind.FENCE && date == null -> res.getString(R.string.form_fence_needs_date)
            est != null && est !in 1..10000 -> res.getString(R.string.form_estimate_range)
            else -> null
        }
        if (error != null) return
        val due = date?.let { ZonedDateTime.of(it, time ?: DEFAULT_TIME, zone).toInstant() }
        error = onSave(TaskForm(title.trim(), kind, priority, due, recurrence.takeIf { due != null }, nextAction.trim().ifEmpty { null },
            description.trim().ifEmpty { null }, est))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TopBar(heading, onBack = onBack) { TextButton(onClick = ::save) { Text(stringResource(R.string.save)) } }
        OutlinedTextField(
            value = title, onValueChange = { title = it.take(200) }, label = { Text(stringResource(R.string.form_title)) }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )

        SectionTitle(stringResource(R.string.form_kind))
        Chips(Format.KIND_LABELS.mapValues { stringResource(it.value) }, kind) { kind = it }

        SectionTitle(stringResource(R.string.form_when))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pickDate = true }) { Text(date?.format(dateFormat) ?: stringResource(R.string.form_pick_date)) }
            if (date != null) {
                OutlinedButton(onClick = { pickTime = true }) { Text((time ?: DEFAULT_TIME).format(HM)) }
                TextButton(onClick = { date = null; time = null; recurrence = null }) { Text(stringResource(R.string.clear), color = Palette.Muted) }
            }
        }
        if (date == null) Muted(stringResource(R.string.form_no_date), Modifier.padding(top = 4.dp))
        if (kind == TaskKind.MEETING && date != null) Muted(stringResource(R.string.form_meeting_hint), Modifier.padding(top = 4.dp))

        if (date != null) {
            SectionTitle(stringResource(R.string.form_repeat))
            Chips(mapOf<String?, String>(null to stringResource(R.string.form_repeat_no)) + Format.RECURRENCE_LABELS.mapValues { stringResource(it.value) },
                recurrence) { recurrence = it }
        }

        if (kind == TaskKind.TASK) {
            SectionTitle(stringResource(R.string.form_importance))
            Chips(Format.PRIORITY_LABELS.mapValues { stringResource(it.value) }, priority) { priority = it }
        }

        SectionTitle(stringResource(R.string.form_details))
        OutlinedTextField(
            value = nextAction, onValueChange = { nextAction = it.take(200) }, label = { Text(stringResource(R.string.form_next_step)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = estimate, onValueChange = { estimate = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.form_estimate)) },
            modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = description, onValueChange = { description = it.take(800) }, label = { Text(stringResource(R.string.task_description)) },
            modifier = Modifier.fillMaxWidth(), minLines = 3,
        )

        error?.let { Text(it, color = Palette.Danger, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save)) }
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
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val t = time ?: DEFAULT_TIME
        val state = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); pickTime = false }) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.cancel)) } },
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
private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
