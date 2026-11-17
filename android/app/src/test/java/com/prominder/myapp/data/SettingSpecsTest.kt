package com.prominder.myapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The stored text must be what backend/app/config.py's Spec.canonical writes, so both apps read it the same. */
class SettingSpecsTest {
    private fun canon(key: String, raw: String) = SettingSpecs.canonical(SettingSpecs.BY_KEY.getValue(key), raw)
    private fun error(key: String, raw: String) = assertThrows(SettingException::class.java) { canon(key, raw) }.message

    @Test
    fun `values are normalised like the server does`() {
        assertEquals("07:05", canon("morning_time", " 7:5 "))
        assertEquals("09:00, 13:00", canon("checkin_times", "13:00,9:00,"))
        assertEquals("", canon("checkin_times", ""))
        assertEquals("off", canon("week_meetings_time", "Off"))
        assertEquals("08:00", canon("week_meetings_time", "8:00"))
        assertEquals("sun", canon("week_start_day", "Sunday"))
        assertEquals("true", canon("shabbat_mode", "yes"))
        assertEquals("Asia/Jerusalem", canon("timezone", "Asia/Jerusalem"))
        assertEquals("30", canon("nag_interval_min", "30"))
        assertEquals("32.0956", canon("latitude", "32.0956"))
        assertEquals("60", canon("altitude", "60.0"))
        assertEquals("3", canon("reply_link_hours", "3"))
        assertEquals("2.5", canon("reply_link_hours", "2.50"))
    }

    @Test
    fun `wrong values say why, in the server's words`() {
        assertEquals("use HH:MM, e.g. 07:30", error("morning_time", "25:00"))
        assertEquals("must be a whole number", error("max_nags", "four"))
        assertEquals("must be at least 5", error("nag_interval_min", "1"))
        assertEquals("must be at most 90", error("latitude", "91"))
        assertEquals("use sun, mon, tue, wed, thu, fri or sat", error("week_start_day", "someday"))
        assertEquals("unknown time zone (e.g. Asia/Jerusalem)", error("timezone", "Mars/Olympus"))
        assertEquals("use true or false", error("diaspora", "maybe"))
    }

    @Test
    fun `stored values override the defaults, invalid ones are ignored`() {
        val s = AppSettings(mapOf("morning_time" to "08:00", "timezone" to "Nowhere/Land", "unknown" to "x"))
        assertEquals("08:00", s.raw("morning_time"))
        assertEquals("Asia/Jerusalem", s.zone.id)
        assertEquals(mapOf("morning_time" to "08:00"), s.overrides)
        assertEquals(15, AppSettings.DEFAULTS.meetingLeadMin)
    }
}
