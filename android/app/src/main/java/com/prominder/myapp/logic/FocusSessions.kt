package com.prominder.myapp.logic

import com.prominder.myapp.data.SessionRow
import java.time.Duration
import java.time.Instant

/** Focus sessions (▶ Start): their lengths, when one ends, goes silent or holds other check-ins, "Where did you stop?". Pure: no I/O. */
object FocusSessions {
    const val START_MIN_NOTIFICATION = 5
    const val START_MIN_APP = 25
    val SESSION_CHOICES = listOf(5, 15, 25, 45)
    const val SESSION_MAX_MIN = 120
    const val SESSION_SILENCE_MIN = 15L
    const val FOCUS_HOLD_MIN = 10L
    const val BREADCRUMB_HOURS = 3L

    /** A session length: a multiple of 5, 5 to SESSION_MAX_MIN. */
    fun sessionLength(minutes: Int?, default: Int = START_MIN_NOTIFICATION): Int {
        val m = if (minutes == null || minutes == 0) default else minutes
        val rounded = Math.floorDiv(-m, 5).let { -it } * 5
        return minOf(maxOf(rounded, 5), SESSION_MAX_MIN)
    }

    fun sessionEndAt(s: SessionRow): Instant = s.started.plus(Duration.ofMinutes(s.plannedMin.toLong()))

    /** running → ended (time's up) → closed (an outcome is recorded). */
    fun sessionState(s: SessionRow?, now: Instant): String? = when {
        s == null -> null
        s.endedAt != null -> "closed"
        now < sessionEndAt(s) -> "running"
        else -> "ended"
    }

    /** Nobody answered SESSION_SILENCE_MIN after its end check-in (or its end, if none went out). */
    fun sessionSilent(s: SessionRow, now: Instant): Boolean {
        if (s.endedAt != null) return false
        val since = s.endSent ?: sessionEndAt(s)
        return now >= since.plus(Duration.ofMinutes(SESSION_SILENCE_MIN))
    }

    fun sessionEndDue(s: SessionRow, now: Instant): Boolean =
        sessionState(s, now) == "ended" && s.endSentAt == null && !sessionSilent(s, now)

    /** (outcome, ended at) for an open session being replaced. */
    fun sessionCloseOutcome(s: SessionRow, now: Instant): Pair<String, Instant> {
        val end = sessionEndAt(s)
        return if (now < end) "stopped" to now else "paused" to end
    }

    fun actualMinutes(s: SessionRow, now: Instant): Int {
        val end = s.ended ?: now
        return maxOf(0, Math.round(Duration.between(s.started, end).seconds / 60.0).toInt())
    }

    /** While a session runs, other check-ins wait until it ends + FOCUS_HOLD_MIN. latest: the most recent session. */
    fun focusHoldUntil(latest: SessionRow?): Instant? {
        latest ?: return null
        return (latest.ended ?: sessionEndAt(latest)).plus(Duration.ofMinutes(FOCUS_HOLD_MIN))
    }

    fun sessionLastActive(latest: SessionRow?, now: Instant): Instant? {
        latest ?: return null
        return latest.ended ?: minOf(now, sessionEndAt(latest))
    }

    fun wantsBreadcrumb(latest: SessionRow?, now: Instant): Boolean {
        latest ?: return false
        val ended = latest.ended ?: return false
        return latest.outcome in setOf("stopped", "paused") && latest.noteClosedAt == null &&
            Duration.between(ended, now) < Duration.ofHours(BREADCRUMB_HOURS)
    }
}
