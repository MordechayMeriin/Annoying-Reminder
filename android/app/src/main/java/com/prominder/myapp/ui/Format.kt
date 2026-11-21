package com.prominder.myapp.ui

import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How times read on screen: "Today 15:00", "Wed 09:30", "in 2 h". */
object Format {
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    /** "Today 14:00", "Tomorrow 09:30", "Wed 18 Nov 09:30". */
    fun time(instant: Instant, zone: ZoneId, now: Instant): String {
        val d = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val prefix = when (d.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(DAY)
        }
        return "$prefix ${d.format(HM)}"
    }

    fun time(text: String?, zone: ZoneId, now: Instant): String? = Times.parseOrNull(text)?.let { time(it, zone, now) }

    /** A chat day separator: "Today", "Yesterday", "Tuesday 17 November". */
    fun day(instant: Instant, zone: ZoneId, now: Instant): String {
        val d = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (d) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> d.format(LONG_DAY)
        }
    }

    fun clock(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(HM)

    val KIND_LABELS = mapOf("task" to "Task", "meeting" to "Meeting", "fence" to "Hard stop")
    val PRIORITY_LABELS = mapOf(1 to "Normal", 2 to "Important", 3 to "Critical")
    val RECURRENCE_LABELS = mapOf("daily" to "Every day", "weekdays" to "Sun–Thu", "weekly" to "Every week", "monthly" to "Every month")

    /** The grey line under a task: when, how often, what kind, how important. */
    fun taskMeta(t: TaskRow, zone: ZoneId, now: Instant): String = buildList {
        when (t.kind) {
            "meeting" -> add("📅 Meeting")
            "fence" -> add("⛔ Hard stop")
        }
        time(t.dueAt, zone, now)?.let { add(it) }
        if (t.dueAt == null) time(t.remindAt, zone, now)?.let { add("⏰ $it") }
        t.recurrence?.let { add("🔁 " + (RECURRENCE_LABELS[it] ?: it)) }
        if (t.priority >= 2) add(PRIORITY_LABELS.getValue(t.priority))
        t.estimateMin?.let { add("~$it min") }
    }.joinToString(" · ")

    val EVENT_LABELS = mapOf(
        "postponed" to "Postponed", "committed" to "Committed", "progress" to "Progress", "stuck" to "Stuck",
        "blocked" to "Blocked", "unblocked" to "Unblocked", "ignored" to "No answer", "done" to "Done",
        "description_changed" to "Description changed (old text)", "session_started" to "▶ Session started",
        "session_ended" to "Session ended", "paused" to "Paused", "parked" to "Not now", "avoided" to "Avoided",
    )
}
