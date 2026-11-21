package com.prominder.myapp.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.data.OPEN
import com.prominder.myapp.ui.theme.Palette

data class HistoryItem(val label: String, val time: String, val note: String? = null)

data class TaskDetailState(
    val id: Long,
    val title: String,
    val meta: String,
    val status: String = OPEN,
    val kind: String = "task",
    val parent: String? = null,
    val nextAction: String? = null,
    val blocked: String? = null,
    val resumeNote: String? = null,
    val description: String = "",
    val inFocus: Boolean = false,
    val steps: List<TaskItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
)

/** One task: what it is, its steps and history, and the buttons (✓ Done, ⏰ Later, ☆ Today's 3, Edit, Cancel). */
@Composable
fun TaskDetailScreen(
    state: TaskDetailState,
    onSaveDescription: (String) -> Unit,
    onDone: () -> Unit,
    onLater: () -> Unit,
    onToggleFocus: () -> Unit,
    onEdit: () -> Unit,
    onCancelTask: () -> Unit,
    onAddStep: (String) -> Unit,
    onOpenStep: (Long) -> Unit,
    onDoneStep: (Long) -> Unit,
    onBack: () -> Unit,
    /** ▶ Start: a 25-minute focus session on the next step. */
    onStart: () -> Unit = {},
) {
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val open = state.status == OPEN
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TopBar("Task", onBack = onBack) { if (open) TextButton(onClick = onEdit) { Text("Edit") } }
        Text(state.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        val status = when (state.status) { "done" -> "✓ Done"; "cancelled" -> "Cancelled"; else -> null }
        val meta = listOfNotNull(status, state.parent, state.meta.takeIf { it.isNotEmpty() }).joinToString(" · ")
        if (meta.isNotEmpty()) Muted(meta, Modifier.padding(top = 4.dp))

        state.nextAction?.let { Callout("Next step", it) }
        state.resumeNote?.let { Callout("Where you stopped", it) }
        state.blocked?.let { Callout("Waiting", it, Palette.Danger) }

        if (open) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.kind != TaskKind.FENCE) {
                    Button(onClick = onDone, colors = ButtonDefaults.buttonColors(containerColor = Palette.Ok)) { Text("✓ Done") }
                    OutlinedButton(onClick = onLater) { Text("⏰ Later") }
                }
                if (state.kind == TaskKind.TASK) OutlinedButton(onClick = onStart) { Text("▶ Start") }
                if (state.kind == TaskKind.TASK) OutlinedButton(onClick = onToggleFocus) { Text(if (state.inFocus) "★ In Today's 3" else "☆ Today's 3") }
                TextButton(onClick = { confirmCancel = true }) { Text("Cancel task", color = Palette.Muted) }
            }
        }

        SectionTitle("Description")
        var text by rememberSaveable(state.id, state.description) { mutableStateOf(state.description) }
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(800) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
            placeholder = { Text("Details, links, who it's for…") },
        )
        if (text != state.description) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { text = state.description }) { Text("Undo", color = Palette.Muted) }
                Button(onClick = { onSaveDescription(text) }) { Text("Save") }
            }
        }

        if (state.parent == null) {
            SectionTitle("Steps")
            state.steps.forEach { TaskLine(it, onOpenStep, onDoneStep) }
            if (open) {
                var step by rememberSaveable { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = step, onValueChange = { step = it.take(200) }, modifier = Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("Add a step") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (step.isNotBlank()) { onAddStep(step); step = "" } }),
                    )
                    TextButton(onClick = { onAddStep(step); step = "" }, enabled = step.isNotBlank()) { Text("Add") }
                }
            } else if (state.steps.isEmpty()) {
                Muted("No steps.")
            }
        }

        if (state.history.isNotEmpty()) {
            SectionTitle("History")
            state.history.forEach { h ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Row {
                        Text(h.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(h.time, fontSize = 12.sp, color = Palette.Muted)
                    }
                    h.note?.let { Text(it, fontSize = 13.sp, color = Palette.Muted) }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this task?") },
            text = { Text("\"${state.title}\"" + if (state.steps.any()) " and its open steps" else "") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; onCancelTask() }) { Text("Cancel task", color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Keep it") } },
        )
    }
}

@Composable
private fun Callout(label: String, text: String, color: androidx.compose.ui.graphics.Color = Palette.Accent) {
    Spacer(Modifier.height(12.dp))
    AppCard {
        Text(label.uppercase(), color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(text)
    }
}
