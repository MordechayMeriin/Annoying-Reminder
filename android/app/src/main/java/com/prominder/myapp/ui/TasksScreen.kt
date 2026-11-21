package com.prominder.myapp.ui

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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
        TopBar(stringResource(R.string.tasks_title), onBack = onBack) { LinkButton(stringResource(R.string.tasks_new), onNew) }
        TabRow(selectedTabIndex = state.tab, containerColor = Palette.Bg, contentColor = Palette.Text) {
            Tab(selected = state.tab == 0, onClick = { onTab(0) }, text = { Text(stringResource(R.string.tasks_tab_today)) })
            Tab(selected = state.tab == 1, onClick = { onTab(1) }, text = { Text(stringResource(R.string.tasks_tab_all, state.all.size)) })
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (state.tab == 0) {
                item { SectionTitle(stringResource(R.string.tasks_overdue_today)) }
                if (state.today.isEmpty()) item { Muted(stringResource(R.string.tasks_nothing_today), Modifier.padding(start = 4.dp)) }
                items(state.today, key = { "t${it.id}" }) { TaskLine(it, onOpen, onDone) }
                item { SectionTitle(stringResource(R.string.tasks_coming_up)) }
                if (state.upcoming.isEmpty()) item { Muted(stringResource(R.string.tasks_nothing_soon), Modifier.padding(start = 4.dp)) }
                items(state.upcoming, key = { "u${it.id}" }) { TaskLine(it, onOpen, onDone) }
            } else {
                item { Spacer(Modifier.height(8.dp)) }
                if (state.all.isEmpty()) item { Muted(stringResource(R.string.tasks_none_open), Modifier.padding(start = 4.dp)) }
                items(state.all, key = { "a${it.id}" }) { TaskLine(it, onOpen, onDone) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun TaskLine(item: TaskItem, onOpen: (Long) -> Unit, onDone: (Long) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onOpen(item.id) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // A hard stop can't be "done", only cancelled: a sign in the ring's place instead.
        if (item.canBeDone) {
            DoneCircle(false) { onDone(item.id) }
        } else {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Text("⛔", style = MaterialTheme.typography.titleMedium) }
        }
        Column(Modifier.weight(1f).padding(start = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Text((if (item.inFocus) "★ " else "") + item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(item.parent, item.meta.takeIf { it.isNotEmpty() }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = if (item.overdue) Palette.Danger else Palette.Muted)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Palette.Muted)
    }
}
