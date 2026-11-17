package com.prominder.myapp.logic

import com.prominder.myapp.data.SessionRow
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The Python tests' helpers: local times in Israel, tasks and sessions built like their dicts. */
val TZ: ZoneId = ZoneId.of("Asia/Jerusalem")

fun local(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): ZonedDateTime = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, TZ)

fun hm(h: Int, m: Int = 0): LocalTime = LocalTime.of(h, m)

private fun txt(t: ZonedDateTime?): String? = t?.let { Times.format(it.toInstant()) }

fun task(
    id: Long = 1,
    title: String = "Task $id",
    kind: String = "task",
    priority: Int = 1,
    status: String = "open",
    due: ZonedDateTime? = null,
    remind: ZonedDateTime? = null,
    remindRequested: Boolean = false,
    blocked: ZonedDateTime? = null,
    parked: ZonedDateTime? = null,
    focus: LocalDate? = null,
    focusRank: Int? = null,
    nextAction: String? = null,
    estimate: Int? = null,
    created: ZonedDateTime = local(2026, 11, 9, 8),
    parentId: Long? = null,
    recurrence: String? = null,
    avoidAsks: Int = 0,
    avoidAsked: ZonedDateTime? = null,
    nagCount: Int = 0,
    lastNotified: ZonedDateTime? = null,
    resumeNote: String? = null,
) = TaskRow(
    id = id, title = title, kind = kind, priority = priority, status = status, dueAt = txt(due), remindAt = txt(remind),
    remindRequested = remindRequested, blockedUntil = txt(blocked), parkedUntil = txt(parked), focusDate = focus?.toString(),
    focusRank = focusRank, nextAction = nextAction, estimateMin = estimate, createdAt = txt(created)!!, parentId = parentId,
    recurrence = recurrence, avoidAsks = avoidAsks, avoidAskedAt = txt(avoidAsked), nagCount = nagCount,
    lastNotifiedAt = txt(lastNotified), resumeNote = resumeNote,
)

fun session(
    start: ZonedDateTime,
    planned: Int = 25,
    ended: ZonedDateTime? = null,
    outcome: String? = null,
    endSent: ZonedDateTime? = null,
    noteClosed: ZonedDateTime? = null,
) = SessionRow(1, 1, txt(start)!!, planned, txt(ended), outcome, txt(endSent), txt(noteClosed))

val ZonedDateTime.i: Instant get() = toInstant()
