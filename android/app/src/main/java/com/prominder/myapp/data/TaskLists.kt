package com.prominder.myapp.data

import java.time.Duration
import java.time.Instant

/** The task lists the screens show, ported from tasks.list_tasks (pure functions over the open tasks). */
object TaskLists {
    private val FAR = "9999"

    /** "Overdue & today": due or reminding before the end of today, soonest first. */
    fun today(open: List<TaskRow>, endOfDay: Instant): List<TaskRow> {
        val end = Times.format(endOfDay)
        return open.filter { (it.dueAt != null && it.dueAt < end) || (it.remindAt != null && it.remindAt < end) }
            .sortedWith(compareBy({ it.dueAt ?: it.remindAt }, { it.id }))
    }

    /** "Coming up": due in the [days] after today. */
    fun upcoming(open: List<TaskRow>, endOfDay: Instant, days: Long = 7): List<TaskRow> {
        val start = Times.format(endOfDay)
        val end = Times.format(endOfDay.plus(Duration.ofDays(days)))
        return open.filter { it.dueAt != null && it.dueAt >= start && it.dueAt < end }.sortedWith(compareBy({ it.dueAt }, { it.id }))
    }

    /** "All open": dated ones first by date, then the most important, then the oldest. */
    fun all(open: List<TaskRow>): List<TaskRow> =
        open.sortedWith(compareBy<TaskRow>({ it.dueAt ?: FAR }, { -it.priority }, { it.id }))

    fun overdue(task: TaskRow, now: Instant): Boolean = task.dueAt != null && task.dueAt < Times.format(now)

    /** tasks.focus_today: today's pick (open or done, not cancelled), in its order. */
    fun focus(rows: List<TaskRow>, day: String): List<TaskRow> =
        rows.filter { it.focusDate == day && it.status != CANCELLED }
            .sortedWith(compareBy<TaskRow>({ it.focusRank == null }, { it.focusRank ?: 0 }, { it.id }))

    /** Which of Today's 3 were done today: closed, or a recurring one that rolled on after a done event. */
    fun doneToday(rows: List<TaskRow>, doneEvents: List<EventRow>): Set<Long> {
        val withEvent = doneEvents.map { it.taskId }.toSet()
        return rows.filter { it.status == DONE || it.id in withEvent }.map { it.id }.toSet()
    }
}
