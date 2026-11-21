package com.prominder.myapp.logic

import com.prominder.myapp.data.SessionRow
import com.prominder.myapp.data.TaskRow
import com.prominder.myapp.data.Times
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit


/*
 * The pure scheduling logic (no I/O), by topic: Schedule, Loudness, Contacts (the guardrails), ChipRules, Coaching,
 * Focus (Today's 3, What now?), FocusSessions, Texts and Pressure, each tested in test/…/logic/. A task is a [TaskRow]
 * (times as stored text, read through the extension properties below), "now in local time" is a ZonedDateTime, and
 * moments are Instants.
 */

// ---------------------------------------------------------------- reading a row's times

val TaskRow.due: Instant? get() = Times.parseOrNull(dueAt)
val TaskRow.remind: Instant? get() = Times.parseOrNull(remindAt)
val TaskRow.blocked: Instant? get() = Times.parseOrNull(blockedUntil)
val TaskRow.parked: Instant? get() = Times.parseOrNull(parkedUntil)
val TaskRow.avoidAsked: Instant? get() = Times.parseOrNull(avoidAskedAt)
val TaskRow.avoidReset: Instant? get() = Times.parseOrNull(avoidResetAt)
val TaskRow.lastNotified: Instant? get() = Times.parseOrNull(lastNotifiedAt)
val TaskRow.created: Instant? get() = Times.parseOrNull(createdAt)
val TaskRow.staleOffered: Instant? get() = Times.parseOrNull(staleOfferedAt)
val TaskRow.focusDay: LocalDate? get() = focusDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

val SessionRow.started: Instant get() = Times.parse(startedAt)
val SessionRow.ended: Instant? get() = Times.parseOrNull(endedAt)
val SessionRow.endSent: Instant? get() = Times.parseOrNull(endSentAt)
val SessionRow.noteClosed: Instant? get() = Times.parseOrNull(noteClosedAt)

internal fun ZonedDateTime.at(t: LocalTime): ZonedDateTime = with(t).truncatedTo(ChronoUnit.MINUTES)
