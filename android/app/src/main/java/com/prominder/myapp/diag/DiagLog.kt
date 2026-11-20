package com.prominder.myapp.diag

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's own log, shown on the Diagnostics screen and shared from there.
 *
 * One line per event ("2026-11-13 14:05:02 [alarm] Test alarm rang 2 s late"), appended to a file in
 * the app's private storage. When the file grows past [maxBytes] only the newest [keepLines] are kept,
 * so it never fills the phone. Plain Kotlin (no Android classes) so it's tested on a normal computer.
 *
 * [log] never touches the file itself: lines are written in order by one background thread, so logging from a tap
 * or a screen costs nothing. [lines] waits for what's queued, so it reads every line logged before it; it reads the
 * file, so call it off the main thread.
 */
class DiagLog(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val maxBytes: Long = 256_000,
    private val keepLines: Int = 1_500,
    /** Also print each line somewhere else (logcat in the app). */
    private val echo: (String) -> Unit = {},
    /** Where the file is written: one thread, so lines stay in order. */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "diag-log").apply { isDaemon = true } },
) {
    private val _version = MutableStateFlow(0)

    /** Changes whenever the log does, so a screen showing it knows to reload. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** How big the file is, kept here so a line doesn't need a file-length check. */
    private var size = -1L

    fun log(tag: String, message: String) {
        val line = "${format(clock())} [$tag] ${message.replace('\n', ' ')}"
        echo(line)
        run {
            runCatching {
                file.parentFile?.mkdirs()
                if (size < 0) size = if (file.exists()) file.length() else 0
                file.appendText(line + "\n")
                size += line.toByteArray().size + 1
                if (size > maxBytes) trim()
            }
            _version.value++
        }
    }

    /** All lines, oldest first (after everything logged so far is written). Reads the file: not on the main thread. */
    fun lines(): List<String> = call {
        if (file.exists()) runCatching { file.readLines() }.getOrDefault(emptyList()) else emptyList()
    }

    fun clear() = run {
        runCatching { file.writeText("") }
        size = 0
        _version.value++
    }

    /** Waits (a little) until every line logged so far is in the file: before the app dies of a crash. */
    fun flush() {
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
    }

    private fun trim() {
        val lines = file.readLines()
        file.writeText(lines.takeLast(keepLines).joinToString(separator = "\n", postfix = "\n"))
        size = file.length()
    }

    private fun run(block: () -> Unit) {
        runCatching { (writer as Executor).execute(block) }
    }

    private fun <T> call(block: () -> T): T = writer.submit<T>(block).get()

    fun format(epochMillis: Long): String = FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(zone()))

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
