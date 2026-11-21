package com.prominder.myapp.ui

import android.content.res.Resources
import com.prominder.myapp.R
import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.llm.ToolStatus

/** The chat's line for a step the assistant takes: "📝 Writing a new task: Dentist", in the app's language. */
object ToolSteps {
    /** What it does (a string resource) and what it's about (a task's title), if anything. */
    fun of(t: ToolStatus): Pair<Int, String?> = when (t.name) {
        "add_task" -> (if (t.kind == TaskKind.MEETING) R.string.step_new_meeting else R.string.step_new_task) to t.title
        "update_setting" -> R.string.step_setting to null
        "remember_preference" -> R.string.step_preference to null
        "list_tasks" -> R.string.step_list to null
        "update_task" -> R.string.step_update to (t.taskTitle ?: t.title)
        "complete_task" -> R.string.step_complete to t.taskTitle
        "cancel_task" -> R.string.step_cancel to t.taskTitle
        "schedule_followup" -> R.string.step_followup to t.taskTitle
        "add_subtask" -> R.string.step_subtask to t.title
        "log_interaction" -> R.string.step_note to t.taskTitle
        "mark_blocked" -> R.string.step_blocked to t.taskTitle
        "set_focus" -> R.string.step_focus to null
        else -> R.string.step_working to null
    }

    fun label(res: Resources, t: ToolStatus): String {
        val (what, which) = of(t)
        return if (which != null) res.getString(R.string.step_with_subject, res.getString(what), which) else res.getString(what)
    }
}
