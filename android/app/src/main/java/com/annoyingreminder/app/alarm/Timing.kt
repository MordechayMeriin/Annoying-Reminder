package com.annoyingreminder.app.alarm

import kotlin.math.abs
import kotlin.math.roundToLong

/** Plain helpers for alarm times, kept free of Android classes so they're tested on a computer. */
object Timing {
    /**
     * How late an alarm rang, in words: "on time" within 5 seconds (Android's own wake-up jitter),
     * otherwise "40 s late" / "3 min 5 s late" / "1 h 2 min late". Early is reported too ("2 s early").
     */
    fun describeLateness(lateMs: Long): String {
        if (abs(lateMs) < 5_000) return "on time"
        val seconds = (abs(lateMs) / 1000.0).roundToLong()
        val words = when {
            seconds < 60 -> "$seconds s"
            seconds < 3600 -> "${seconds / 60} min" + if (seconds % 60 != 0L) " ${seconds % 60} s" else ""
            else -> "${seconds / 3600} h" + if ((seconds % 3600) / 60 != 0L) " ${(seconds % 3600) / 60} min" else ""
        }
        return if (lateMs > 0) "$words late" else "$words early"
    }

    /** The Doze test passes when the alarm rang within a minute of its time. */
    fun passesDozeTest(lateMs: Long): Boolean = lateMs < 60_000
}
