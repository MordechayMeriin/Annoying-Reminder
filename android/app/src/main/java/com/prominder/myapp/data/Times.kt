package com.prominder.myapp.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Times are stored the way the server stores them: fixed-width UTC text with microseconds
 * (2026-11-16T08:30:00.000000Z). Text in that format sorts in time order, so SQL can compare it, and a row
 * restored from the backup goes back to it byte for byte. Dates are YYYY-MM-DD.
 */
object Times {
    private val FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

    fun format(instant: Instant): String = FORMAT.format(instant)

    fun parse(text: String): Instant = Instant.from(FORMAT.parse(text))

    fun parseOrNull(text: String?): Instant? = text?.let { runCatching { parse(it) }.getOrNull() }

    fun date(text: String): LocalDate = LocalDate.parse(text)
}
