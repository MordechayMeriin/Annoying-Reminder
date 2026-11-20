package com.prominder.myapp.data

/*
 * The names that travel as text (in the database, the backup and the model's tool calls), as constants: code compares
 * with these, so a typo is a compile error instead of a branch that silently never runs. Task statuses are OPEN, DONE
 * and CANCELLED (Rows.kt); settings are read through AppSettings' typed properties.
 */

/** What a task is ([TaskRow.kind]). */
object TaskKind {
    const val TASK = "task"
    const val MEETING = "meeting"
    /** A hard stop: it fires at its time and can only be cancelled. */
    const val FENCE = "fence"

    val ALL = listOf(TASK, MEETING, FENCE)
}

/** Who wrote a message ([MessageRow.role]). */
object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/** What a message is ([MessageRow.kind]). */
object MessageKind {
    const val CHAT = "chat"
    /** Something you did outside the chat (a tap), written as your message: "[Marked done: …]". */
    const val EVENT = "event"
    const val MORNING = "morning"
    const val CHECKIN = "checkin"
    const val EVENING = "evening"
    /** A check-in about tasks. */
    const val NUDGE = "nudge"
    /** A reminder you asked for, a meeting's or a hard stop's. */
    const val REMINDER = "reminder"
    const val MEETING = "meeting"
    /** A focus session's end. */
    const val SESSION = "session"
    const val REENTRY = "reentry"
}

/** What happened to a task ([EventRow.kind]). */
object EventKind {
    const val POSTPONED = "postponed"
    const val COMMITTED = "committed"
    const val PROGRESS = "progress"
    const val STUCK = "stuck"
    const val BLOCKED = "blocked"
    const val UNBLOCKED = "unblocked"
    const val IGNORED = "ignored"
    const val DONE = "done"
    const val DESCRIPTION_CHANGED = "description_changed"
    const val SESSION_STARTED = "session_started"
    const val SESSION_ENDED = "session_ended"
    const val PAUSED = "paused"
    const val PARKED = "parked"
    const val AVOIDED = "avoided"

    val ALL = setOf(POSTPONED, COMMITTED, PROGRESS, STUCK, BLOCKED, UNBLOCKED, IGNORED, DONE, DESCRIPTION_CHANGED,
        SESSION_STARTED, SESSION_ENDED, PAUSED, PARKED, AVOIDED)
}

/** What a reply button does without the model ([com.prominder.myapp.logic.ChipRules.ChipJson.action]); null: it sends its label. */
object ChipAction {
    const val START = "start"
    const val ANOTHER = "another"
    const val STOP = "stop"
    const val DONE = "done"
    const val LATER = "later"
    const val NOT_NOW = "not_now"
    const val MOVE_OVERDUE = "move_overdue"
    const val DROP = "drop"
    const val KEEP = "keep"
    const val WHAT_NOW = "what_now"
}
