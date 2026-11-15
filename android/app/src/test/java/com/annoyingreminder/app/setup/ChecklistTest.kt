package com.annoyingreminder.app.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {
    private val s9 = DeviceFacts(
        sdk = 29, isSamsung = true, notificationsOn = true, exactAlarmsOn = true, fullScreenOn = true,
        batteryUnrestricted = false, samsungNeverSleepingConfirmed = false,
        urgentBypassesDnd = false, alarmBypassesDnd = false,
    )

    @Test
    fun `Galaxy S9 on Android 10 skips what Android 10 allows anyway`() {
        val ids = Checklist.build(s9).map { it.id }
        assertEquals(listOf(CheckId.NOTIFICATIONS, CheckId.BATTERY, CheckId.SAMSUNG_SLEEP, CheckId.DND), ids)
    }

    @Test
    fun `a new non-Samsung phone gets every Android item and no Samsung one`() {
        val ids = Checklist.build(s9.copy(sdk = 35, isSamsung = false)).map { it.id }
        assertEquals(
            listOf(CheckId.NOTIFICATIONS, CheckId.EXACT_ALARMS, CheckId.FULL_SCREEN, CheckId.BATTERY, CheckId.DND),
            ids,
        )
    }

    @Test
    fun `Android 12 and 13 ask for exact alarms but not full-screen`() {
        val ids = Checklist.build(s9.copy(sdk = 33)).map { it.id }
        assertTrue(CheckId.EXACT_ALARMS in ids)
        assertFalse(CheckId.FULL_SCREEN in ids)
    }

    @Test
    fun `Samsung item is ticked by hand`() {
        val item = Checklist.build(s9).first { it.id == CheckId.SAMSUNG_SLEEP }
        assertTrue(item.confirmedByUser)
        assertFalse(item.done)
        assertTrue(Checklist.build(s9.copy(samsungNeverSleepingConfirmed = true)).first { it.id == CheckId.SAMSUNG_SLEEP }.done)
    }

    @Test
    fun `Do Not Disturb needs both channels and points at the missing one`() {
        val none = Checklist.build(s9).first { it.id == CheckId.DND }
        assertFalse(none.done)
        assertTrue("Urgent" in none.why)
        val urgentOnly = Checklist.build(s9.copy(urgentBypassesDnd = true)).first { it.id == CheckId.DND }
        assertFalse(urgentOnly.done)
        assertTrue("Alarm category" in urgentOnly.why)
        assertTrue(Checklist.build(s9.copy(urgentBypassesDnd = true, alarmBypassesDnd = true)).first { it.id == CheckId.DND }.done)
    }

    @Test
    fun `done count`() {
        val all = s9.copy(batteryUnrestricted = true, samsungNeverSleepingConfirmed = true, urgentBypassesDnd = true, alarmBypassesDnd = true)
        assertEquals(4, Checklist.doneCount(Checklist.build(all)))
        assertEquals(1, Checklist.doneCount(Checklist.build(s9)))
    }
}
