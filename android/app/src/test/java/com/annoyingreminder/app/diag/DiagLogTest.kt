package com.annoyingreminder.app.diag

import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagLogTest {
    private val dir: File = Files.createTempDirectory("diag").toFile()
    private val jerusalem = ZoneId.of("Asia/Jerusalem")

    @Test
    fun `lines carry local time and tag`() {
        // 2026-11-13 12:00:00 UTC is 14:00 in Jerusalem (winter time, UTC+2).
        val log = DiagLog(File(dir, "a.log"), clock = { 1_794_571_200_000 }, zone = { jerusalem })
        log.log("alarm", "rang")
        assertEquals(listOf("2026-11-13 14:00:00 [alarm] rang"), log.lines())
    }

    @Test
    fun `newlines in a message stay on one line`() {
        val log = DiagLog(File(dir, "b.log"))
        log.log("crash", "first\nsecond")
        assertEquals(1, log.lines().size)
        assertTrue(log.lines()[0].endsWith("first second"))
    }

    @Test
    fun `keeps only the newest lines once the file is too big`() {
        val log = DiagLog(File(dir, "c.log"), maxBytes = 2_000, keepLines = 10)
        repeat(200) { log.log("t", "line $it") }
        val lines = log.lines()
        assertTrue(lines.size <= 60)
        assertTrue(lines.last().endsWith("line 199"))
    }

    @Test
    fun `clear empties the log and bumps the version`() {
        val log = DiagLog(File(dir, "d.log"))
        log.log("t", "x")
        val before = log.version.value
        log.clear()
        assertEquals(emptyList<String>(), log.lines())
        assertTrue(log.version.value > before)
    }

    @Test
    fun `missing file reads as empty`() {
        assertEquals(emptyList<String>(), DiagLog(File(dir, "nope/never.log")).lines())
    }
}
