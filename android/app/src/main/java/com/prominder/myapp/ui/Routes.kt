package com.prominder.myapp.ui

/**
 * Where you are in the app. A small back stack of these replaces a navigation library: the app has a handful of
 * screens. Each route has a text form, so the stack survives the screen rotating or Android pausing the app.
 */
sealed class Route(val key: String) {
    data object Home : Route("home")
    data object Tasks : Route("tasks")
    data class Task(val id: Long) : Route("task/$id")
    data class NewTask(val parentId: Long? = null) : Route("new/${parentId ?: ""}")
    data class EditTask(val id: Long) : Route("edit/$id")
    data object Settings : Route("settings")
    data object Checklist : Route("checklist")
    data object Diagnostics : Route("diagnostics")

    companion object {
        fun parse(key: String): Route {
            val (name, arg) = key.split("/", limit = 2).let { it[0] to it.getOrNull(1).orEmpty() }
            return when (name) {
                "tasks" -> Tasks
                "task" -> arg.toLongOrNull()?.let(::Task) ?: Home
                "new" -> NewTask(arg.toLongOrNull())
                "edit" -> arg.toLongOrNull()?.let(::EditTask) ?: Home
                "settings" -> Settings
                "checklist" -> Checklist
                "diagnostics" -> Diagnostics
                else -> Home
            }
        }
    }
}
