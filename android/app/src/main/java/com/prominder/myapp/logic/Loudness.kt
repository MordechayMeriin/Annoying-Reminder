package com.prominder.myapp.logic

import com.prominder.myapp.data.TaskKind
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.logic.Schedule.nextTimeAt
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/** How loud a contact is: normal, urgent or a ringing alarm, by the task and how many check-ins went unanswered. Pure: no I/O. */
object Loudness {
    const val NORMAL = 0
    const val URGENT = 1
    const val ALARM = 2
    const val NEAR_DEADLINE_H = 48L

    private val ALARM_MIN_PRIORITY = mapOf("critical" to 3, "important" to 2, "all" to 1)

    data class AlertPolicy(
        val urgentAfter: Int = 1,
        /** critical | important | all | off (by task priority) */
        val alarmFor: String = "important",
        val alarmAfter: Int = 2,
        val maxNags: Int = 4,
    )

    /** May a contact about this task be louder than normal, and come more than once a day? */
    fun escalates(task: TaskRow, now: Instant): Boolean {
        if (task.kind == TaskKind.MEETING || task.kind == TaskKind.FENCE || task.remindRequested) return true
        val due = task.due ?: return false
        val window = Duration.ofHours(NEAR_DEADLINE_H)
        return now.minus(window) < due && due < now.plus(window)
    }

    /** How loud a contact about one task should be. ignored: check-ins about it in a row with no reply. */
    fun alertLevel(task: TaskRow, ignored: Int, p: AlertPolicy, now: Instant): Int {
        if (task.kind == TaskKind.MEETING || task.kind == TaskKind.FENCE) return URGENT
        if (!escalates(task, now)) return NORMAL
        val minPrio = ALARM_MIN_PRIORITY[p.alarmFor]
        if (minPrio != null && task.priority >= minPrio && ignored >= minOf(p.alarmAfter, maxOf(p.maxNags - 1, 0))) return ALARM
        if (task.remindRequested || task.priority >= 2 || ignored >= p.urgentAfter) return URGENT
        return NORMAL
    }

    /** Seconds an alarm may keep ringing: up to maxS, but never into quiet hours or Shabbat. */
    fun alarmWindowS(now: ZonedDateTime, quietStart: LocalTime, nextRestStart: Instant?, maxS: Long = 3600): Long {
        var end = now.toInstant().plusSeconds(maxS)
        end = minOf(end, nextTimeAt(now, quietStart).toInstant())
        if (nextRestStart != null && nextRestStart > now.toInstant()) end = minOf(end, nextRestStart)
        return maxOf(0, Duration.between(now.toInstant(), end).seconds)
    }
}
