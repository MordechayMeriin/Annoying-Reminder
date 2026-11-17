package com.prominder.myapp.remind

import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.logic.Logic
import com.prominder.myapp.logic.due
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive

/**
 * The words of the proactive messages when no LLM writes them, ported from backend/app/coach.py's templates
 * (template_line, meeting_line, fence_line, morning / evening / mid-day templates, the meeting digests). Meeting
 * facts always come from code. Phase D puts the LLM in front of these; they stay as the fallback.
 */
object Templates {
    val PRIORITY_PREFIX = mapOf(1 to "", 2 to "❗ ", 3 to "🚨 ")
    const val MAX_TEXT = 1200
    const val PUSH_TITLE_MAX = 90

    val JOB_TITLES = mapOf(
        "morning" to "☀️ Morning plan", "checkin" to "⏰ Check-in", "evening" to "🌙 Evening review",
        "reentry" to "👋 Good to see you", "meeting" to "📅 This week's meetings",
    )
    val MEETING_JOB_TITLES = mapOf("week_meetings" to "📅 This week's meetings", "meetings_eve" to "📅 Tomorrow's meetings")
    val JOB_CHIPS = mapOf("morning" to "morning", "checkin" to "midday", "evening" to "evening")

    private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val DAY_HHMM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)
    private val DAY_DATE = DateTimeFormatter.ofPattern("EEE dd/MM", Locale.ENGLISH)

    /** meeting | reminder | nudge: a mixed group counts as a nudge (the per-task cap only gets stricter). */
    fun messageKind(group: List<TaskRow>): String = when {
        group.all { it.kind == "meeting" } -> "meeting"
        group.all { it.kind == "meeting" || it.kind == "fence" || it.remindRequested } -> "reminder"
        else -> "nudge"
    }

    fun meetingLine(task: TaskRow, now: Instant, zone: ZoneId): String {
        val start = task.due!!
        val mins = Math.floorDiv(Duration.between(now, start).seconds, 60)
        val whenText = when {
            mins < -2 -> "started ${-mins} min ago" // held back by Shabbat / quiet hours
            mins <= 1 -> "starting now"
            else -> "in $mins min"
        }
        return "📅 ${task.title} — ${start.atZone(zone).format(HHMM)} ($whenText)"
    }

    fun fenceLine(task: TaskRow, zone: ZoneId, lang: String = "en"): String =
        Logic.text("fence", lang, "at" to (task.due?.atZone(zone)?.format(HHMM) ?: ""), "title" to task.title)

    /** The fixed text for one task: the next action first, no counting failures. */
    fun templateLine(task: TaskRow, now: Instant, zone: ZoneId): String {
        if (task.kind == "meeting" && task.due != null) return meetingLine(task, now, zone)
        if (task.kind == "fence") return fenceLine(task, zone)
        val title = PRIORITY_PREFIX[task.priority].orEmpty() + task.title
        val due = task.due
        val whenText = when {
            due != null && due < now -> "overdue since ${due.atZone(zone).format(DAY_HHMM)}"
            due != null -> "due ${due.atZone(zone).format(DAY_HHMM)}"
            else -> ""
        }
        if (!task.nextAction.isNullOrEmpty()) {
            val line = "▶ ${task.nextAction} — $title" + if (whenText.isNotEmpty()) " ($whenText)" else ""
            return line + if (!task.resumeNote.isNullOrEmpty()) "\n  You stopped at: ${task.resumeNote}" else ""
        }
        if (whenText.isNotEmpty()) return "$title — $whenText."
        if (task.nagCount > 0) return "$title — still open. One small step?"
        return "$title — time to do this."
    }

    fun pushTitle(group: List<TaskRow>): String {
        val first = group.first()
        val icon = mapOf("meeting" to "📅 ", "fence" to "⛔ ")[first.kind] ?: PRIORITY_PREFIX[first.priority].orEmpty()
        var title = icon + first.title
        if (first.kind == "task" && !first.nextAction.isNullOrEmpty()) title += ": ${first.nextAction}"
        title = Logic.clip(title, PUSH_TITLE_MAX)
        return if (group.size == 1) title else "$title (+${group.size - 1})"
    }

    /** The notification's buttons, for a check-in about exactly one task: ✓ Done and Later. */
    fun hasTaskButtons(group: List<TaskRow>): Boolean = group.size == 1 && group[0].kind == "task"

    /** The morning plan without the LLM: Today's 3 (with the first step of #1), then meetings. */
    fun morningTemplate(focus: List<TaskRow>, meetings: List<Pair<Instant, TaskRow>>, zone: ZoneId): String {
        val lines = mutableListOf(if (focus.isNotEmpty()) "☀️ Today's 3:" else "☀️ Nothing open for today.")
        focus.forEachIndexed { i, t ->
            var line = "${i + 1}. ${t.title}"
            if (i == 0 && !t.nextAction.isNullOrEmpty()) line += " — first step: ${t.nextAction}"
            lines += line
        }
        if (meetings.isNotEmpty()) {
            lines += "📅 Meetings:"
            meetings.forEach { (w, m) -> lines += "${w.atZone(zone).format(HHMM)} ${m.title}" }
        }
        return lines.joinToString("\n")
    }

    /** The evening review without the LLM: wins first (steps and sessions count), then what carries over. */
    fun eveningTemplate(doneCount: Int, sessions: Pair<Int, Int>, openCount: Int): String {
        val (n, minutes) = sessions
        val wins = "Done today: $doneCount" + if (n > 0) " · $n focus session${if (n != 1) "s" else ""} ($minutes min)" else ""
        val rest = if (openCount > 0) "$openCount open item(s) carry over to tomorrow." else "Nothing left open today."
        return "🌙 $wins. $rest"
    }

    /** The week's / tomorrow's meetings, or null when there are none. (text, title, meeting ids) */
    fun meetingDigest(job: String, occurrences: List<Pair<Instant, TaskRow>>, now: ZonedDateTime): Triple<String, String, List<Long>>? {
        if (occurrences.isEmpty()) return null
        val zone = now.zone
        val text = if (job == "week_meetings") {
            val today = now.toLocalDate()
            (listOf("📅 Your meetings this week:") + occurrences.map { (w, m) ->
                val local = w.atZone(zone)
                "${dayLabel(local, today)} ${local.format(HHMM)} — ${m.title}"
            }).joinToString("\n")
        } else {
            val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(zone)
            (listOf("📅 Tomorrow (${tomorrow.format(DAY_DATE)}):") + occurrences.map { (w, m) -> "${w.atZone(zone).format(HHMM)} — ${m.title}" })
                .joinToString("\n")
        }
        return Triple(Logic.clip(text, MAX_TEXT), MEETING_JOB_TITLES.getValue(job), occurrences.map { it.second.id }.distinct())
    }

    private fun dayLabel(local: ZonedDateTime, today: LocalDate): String = when (local.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> local.format(DAY_DATE)
    }

    /** Chips as the server stored them: Python's json.dumps(…, ensure_ascii=False). */
    fun encodeChips(chips: List<Logic.ChipJson>): String = chips.joinToString(", ", "[", "]") { c ->
        val fields = mutableListOf("\"id\": ${str(c.id)}", "\"label\": ${str(c.label)}")
        c.action?.let { fields += "\"action\": ${str(it)}" }
        c.minutes?.let { fields += "\"minutes\": $it" }
        c.taskId?.let { fields += "\"task_id\": $it" }
        c.taskIds?.let { ids -> fields += "\"task_ids\": " + ids.joinToString(", ", "[", "]") }
        fields.joinToString(", ", "{", "}")
    }

    private fun str(s: String) = JsonPrimitive(s).toString()
}
