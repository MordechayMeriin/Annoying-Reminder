package com.prominder.myapp.ui

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.core.os.ConfigurationCompat
import com.prominder.myapp.R
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * How times and a task's details read on screen, in the app's language: "Today 15:00", "Wed 18 Nov 09:30" (Hebrew:
 * "היום 15:00", "יום ד׳, 18 בנוב׳ 09:30"). Day and month names and their order come from the locale; the clock is
 * always 24-hour, like the settings.
 */
object Format {
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun locale(res: Resources): Locale = ConfigurationCompat.getLocales(res.configuration)[0] ?: Locale.getDefault()

    /** A date in the locale's own order for [skeleton] (e.g. "EEEdMMM": "Wed 18 Nov", "יום ד׳, 18 בנוב׳"). */
    fun datePattern(res: Resources, skeleton: String): DateTimeFormatter {
        val locale = locale(res)
        return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    /** "Today 14:00", "Tomorrow 09:30", "Wed 18 Nov 09:30". */
    fun time(res: Resources, instant: Instant, zone: ZoneId, now: Instant): String {
        val d = instant.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val hm = d.format(HM)
        return when (d.toLocalDate()) {
            today -> res.getString(R.string.time_today, hm)
            today.plusDays(1) -> res.getString(R.string.time_tomorrow, hm)
            today.minusDays(1) -> res.getString(R.string.time_yesterday, hm)
            else -> res.getString(R.string.time_on_day, d.format(datePattern(res, "EEEdMMM")), hm)
        }
    }

    fun time(res: Resources, text: String?, zone: ZoneId, now: Instant): String? =
        Times.parseOrNull(text)?.let { time(res, it, zone, now) }

    /** A chat day separator: "Today", "Yesterday", "Tuesday 17 November". */
    fun day(res: Resources, instant: Instant, zone: ZoneId, now: Instant): String {
        val d = instant.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (d) {
            today -> res.getString(R.string.day_today)
            today.minusDays(1) -> res.getString(R.string.day_yesterday)
            else -> d.format(datePattern(res, "EEEEdMMMM"))
        }
    }

    fun clock(instant: Instant, zone: ZoneId): String = instant.atZone(zone).format(HM)

    val KIND_LABELS: Map<String, Int> = linkedMapOf("task" to R.string.kind_task, "meeting" to R.string.kind_meeting, "fence" to R.string.kind_fence)
    val PRIORITY_LABELS: Map<Int, Int> = linkedMapOf(1 to R.string.priority_normal, 2 to R.string.priority_important, 3 to R.string.priority_critical)
    val RECURRENCE_LABELS: Map<String, Int> = linkedMapOf(
        "daily" to R.string.recurrence_daily, "weekdays" to R.string.recurrence_weekdays,
        "weekly" to R.string.recurrence_weekly, "monthly" to R.string.recurrence_monthly,
    )

    /** The grey line under a task: when, how often, what kind, how important. */
    fun taskMeta(res: Resources, t: TaskRow, zone: ZoneId, now: Instant): String = buildList {
        when (t.kind) {
            "meeting" -> add(res.getString(R.string.meta_meeting))
            "fence" -> add(res.getString(R.string.meta_fence))
        }
        time(res, t.dueAt, zone, now)?.let { add(it) }
        if (t.dueAt == null) time(res, t.remindAt, zone, now)?.let { add("⏰ $it") }
        t.recurrence?.let { add("🔁 " + (RECURRENCE_LABELS[it]?.let(res::getString) ?: it)) }
        if (t.priority >= 2) PRIORITY_LABELS[t.priority]?.let { add(res.getString(it)) }
        t.estimateMin?.let { add(res.getString(R.string.meta_estimate, it)) }
    }.joinToString(" · ")

    private val EVENT_LABELS: Map<String, Int> = mapOf(
        "postponed" to R.string.event_postponed, "committed" to R.string.event_committed, "progress" to R.string.event_progress,
        "stuck" to R.string.event_stuck, "blocked" to R.string.event_blocked, "unblocked" to R.string.event_unblocked,
        "ignored" to R.string.event_ignored, "done" to R.string.event_done, "description_changed" to R.string.event_description_changed,
        "session_started" to R.string.event_session_started, "session_ended" to R.string.event_session_ended,
        "paused" to R.string.event_paused, "parked" to R.string.event_parked, "avoided" to R.string.event_avoided,
    )

    /** A history line's name ("Postponed"); an unknown kind shows as stored. */
    fun eventLabel(res: Resources, kind: String): String = EVENT_LABELS[kind]?.let(res::getString) ?: kind
}
