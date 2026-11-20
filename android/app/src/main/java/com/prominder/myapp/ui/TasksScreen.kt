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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prominder.myapp.ui.theme.Palette

data class TaskItem(
    val id: Long,
    val title: String,
    val meta: String,
    val overdue: Boolean = false,
    val inFocus: Boolean = false,
    /** "Step of: Write report" for a step. */
    val parent: String? = null,
    val canBeDone: Boolean = true,
)

data class TasksState(
    val tab: Int = 0,
    val today: List<TaskItem> = emptyList(),
    val upcoming: List<TaskItem> = emptyList(),
    val all: List<TaskItem> = emptyList(),
)

/** The task lists: Today (overdue & today, coming up) and All open. */
@Composable
fun TasksScreen(
    state: TasksState,
    onTab: (Int) -> Unit,
    onOpen: (Long) -> Unit,
    onDone: (Long) -> Unit,
    onNew: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Tasks", onBack = onBack) { TextButton(onClick = onNew) { Text("＋ New") } }
        TabRow(selectedTabIndex = state.tab, containerColor = Palette.Bg, contentColor = Palette.Text) {
            Tab(selected = state.tab == 0, onClick = { onTab(0) }, text = { Text("Today") })
            Tab(selected = state.tab == 1, onClick = { onTab(1) }, text = { Text("All open (${state.all.size})") })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (state.tab == 0) {
                item { SectionTitle("Overdue & today") }
                if (state.today.isEmpty()) item { Muted("Nothing due today.", Modifier.padding(start = 4.dp)) }
                items(state.today, key = { "t${it.id}" }) { TaskLine(it, onOpen, onDone) }
                item { SectionTitle("Coming up") }
                if (state.upcoming.isEmpty()) item { Muted("Nothing in the next 7 days.", Modifier.padding(start = 4.dp)) }
                items(state.upcoming, key = { "u${it.id}" }) { TaskLine(it, onOpen, onDone) }
            } else {
                item { Spacer(Modifier.height(8.dp)) }
                if (state.all.isEmpty()) item { Muted("No open tasks.", Modifier.padding(start = 4.dp)) }
                items(state.all, key = { "a${it.id}" }) { TaskLine(it, onOpen, onDone) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun TaskLine(item: TaskItem, onOpen: (Long) -> Unit, onDone: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (item.canBeDone) DoneCircle(false) { onDone(item.id) } else Text("⛔", fontSize = 18.sp)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text((if (item.inFocus) "★ " else "") + item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(item.parent, item.meta.takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = if (item.overdue) Palette.Danger else Palette.Muted)
        }
        Text("›", color = Palette.Muted)
    }
}
