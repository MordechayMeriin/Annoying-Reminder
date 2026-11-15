package com.annoyingreminder.app.diag

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's own log, shown on the Diagnostics screen and shared from there.
 *
 * One line per event ("2026-11-13 14:05:02 [alarm] Test alarm rang 2 s late"), appended to a file in
 * the app's private storage. When the file grows past [maxBytes] only the newest [keepLines] are kept,
 * so it never fills the phone. Plain Kotlin (no Android classes) so it's tested on a normal computer.
 */
class DiagLog(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val maxBytes: Long = 256_000,
    private val keepLines: Int = 1_500,
    /** Also print each line somewhere else (logcat in the app). */
    private val echo: (String) -> Unit = {},
) {
    private val _version = MutableStateFlow(0)

    /** Changes whenever the log does, so a screen showing it knows to reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun log(tag: String, message: String) {
        val line = "${format(clock())} [$tag] ${message.replace('\n', ' ')}"
        echo(line)
        synchronized(this) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(line + "\n")
                if (file.length() > maxBytes) trim()
            }
        }
        _version.value++
    }

    /** All lines, oldest first. */
    fun lines(): List<String> = synchronized(this) {
        if (file.exists()) runCatching { file.readLines() }.getOrDefault(emptyList()) else emptyList()
    }

    fun clear() {
        synchronized(this) { runCatching { file.writeText("") } }
        _version.value++
    }

    private fun trim() {
        val lines = file.readLines()
        file.writeText(lines.takeLast(keepLines).joinToString(separator = "\n", postfix = "\n"))
    }

    fun format(epochMillis: Long): String = FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone()))

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
