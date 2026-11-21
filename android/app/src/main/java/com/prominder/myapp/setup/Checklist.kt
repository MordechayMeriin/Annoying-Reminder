package com.prominder.myapp.setup

import androidx.annotation.StringRes
import com.prominder.myapp.R

/**
 * The first-run checklist: everything the phone must allow for reminders to arrive on time, loudly,
 * with the screen off. Built from plain [DeviceFacts] so the rules are tested on a computer;
 * [DeviceSetup] reads the facts from the phone and opens the right settings screens.
 */
enum class CheckId { NOTIFICATIONS, EXACT_ALARMS, FULL_SCREEN, BATTERY, SAMSUNG_SLEEP, DND }

/** A row of the checklist. Its words are string resources, so the screen shows them in the app's language. */
data class CheckItem(
    val id: CheckId,
    @StringRes val title: Int,
    @StringRes val why: Int,
    val done: Boolean,
    /** Android can't tell the app whether this is done, so you tick it yourself. */
    val confirmedByUser: Boolean = false,
    @StringRes val action: Int,
    /** Filled into [why] (%1$s): a string resource too, e.g. the notification channel's name. */
    @StringRes val whyArg: Int? = null,
)

data class DeviceFacts(
    val sdk: Int,
    val isSamsung: Boolean,
    val notificationsOn: Boolean,
    val exactAlarmsOn: Boolean,
    val fullScreenOn: Boolean,
    val batteryUnrestricted: Boolean,
    val samsungNeverSleepingConfirmed: Boolean,
    val urgentBypassesDnd: Boolean,
    val alarmBypassesDnd: Boolean,
)

object Checklist {
    /** Only what applies to this phone: on Android 10 exact alarms and full-screen alarms need nothing. */
    fun build(f: DeviceFacts): List<CheckItem> = buildList {
        val open = R.string.setup_open_settings
        add(CheckItem(CheckId.NOTIFICATIONS, R.string.check_notifications, R.string.check_notifications_why, f.notificationsOn,
            action = if (f.sdk >= 33) R.string.setup_allow else open))
        if (f.sdk >= 31) {
            add(CheckItem(CheckId.EXACT_ALARMS, R.string.check_exact_alarms, R.string.check_exact_alarms_why, f.exactAlarmsOn, action = open))
        }
        if (f.sdk >= 34) {
            add(CheckItem(CheckId.FULL_SCREEN, R.string.check_full_screen, R.string.check_full_screen_why, f.fullScreenOn, action = open))
        }
        add(CheckItem(CheckId.BATTERY, R.string.check_battery, R.string.check_battery_why, f.batteryUnrestricted, action = open))
        if (f.isSamsung) {
            add(CheckItem(CheckId.SAMSUNG_SLEEP, R.string.check_samsung, R.string.check_samsung_why, f.samsungNeverSleepingConfirmed,
                confirmedByUser = true, action = open))
        }
        // Points at the channel still missing: Urgent first, then Alarm.
        add(CheckItem(CheckId.DND, R.string.check_dnd, R.string.check_dnd_why, f.urgentBypassesDnd && f.alarmBypassesDnd, action = open,
            whyArg = if (!f.urgentBypassesDnd) R.string.channel_urgent else R.string.channel_alarm))
    }

    fun doneCount(items: List<CheckItem>) = items.count { it.done }
}
