package com.annoyingreminder.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingTest {
    @Test
    fun `within five seconds is on time`() {
        assertEquals("on time", Timing.describeLateness(0))
        assertEquals("on time", Timing.describeLateness(4_999))
        assertEquals("on time", Timing.describeLateness(-3_000))
    }

    @Test
    fun `seconds minutes and hours`() {
        assertEquals("40 s late", Timing.describeLateness(40_000))
        assertEquals("3 min 5 s late", Timing.describeLateness(185_000))
        assertEquals("2 min late", Timing.describeLateness(120_000))
        assertEquals("1 h 2 min late", Timing.describeLateness(3_720_000))
        assertEquals("1 h late", Timing.describeLateness(3_600_000))
        assertEquals("8 s early", Timing.describeLateness(-8_000))
    }

    @Test
    fun `doze test passes within a minute`() {
        assertTrue(Timing.passesDozeTest(59_000))
        assertFalse(Timing.passesDozeTest(60_000))
    }
}
