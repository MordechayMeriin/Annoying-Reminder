package com.annoyingreminder.app.setup

/**
 * The first-run checklist: everything the phone must allow for reminders to arrive on time, loudly,
 * with the screen off. Built from plain [DeviceFacts] so the rules are tested on a computer;
 * [DeviceSetup] reads the facts from the phone and opens the right settings screens.
 */
enum class CheckId { NOTIFICATIONS, EXACT_ALARMS, FULL_SCREEN, BATTERY, SAMSUNG_SLEEP, DND }

data class CheckItem(
    val id: CheckId,
    val title: String,
    val why: String,
    val done: Boolean,
    /** Android can't tell the app whether this is done, so you tick it yourself. */
    val confirmedByUser: Boolean = false,
    val action: String,
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
        add(
            CheckItem(
                CheckId.NOTIFICATIONS, "Notifications",
                "Every reminder is a notification.",
                f.notificationsOn,
                action = if (f.sdk >= 33) "Allow" else "Open settings",
            ),
        )
        if (f.sdk >= 31) {
            add(
                CheckItem(
                    CheckId.EXACT_ALARMS, "Alarms & reminders",
                    "Lets reminders arrive on the minute, even when the phone is asleep. Without it they can be minutes late.",
                    f.exactAlarmsOn, action = "Open settings",
                ),
            )
        }
        if (f.sdk >= 34) {
            add(
                CheckItem(
                    CheckId.FULL_SCREEN, "Full-screen alarms",
                    "Lets the alarm fill the screen over the lock screen, like the clock app.",
                    f.fullScreenOn, action = "Open settings",
                ),
            )
        }
        add(
            CheckItem(
                CheckId.BATTERY, "Battery: unrestricted",
                "Find Reminder in the list and choose \"Don't optimize\" (or \"Unrestricted\"), so Android doesn't delay it to save battery.",
                f.batteryUnrestricted, action = "Open settings",
            ),
        )
        if (f.isSamsung) {
            add(
                CheckItem(
                    CheckId.SAMSUNG_SLEEP, "Samsung: never sleeping",
                    "Samsung puts apps to sleep on its own. In Battery, open App power management → Apps that won't be put to sleep " +
                        "(newer phones: Background usage limits → Never sleeping apps) and add Reminder. Then come back and tick it here.",
                    f.samsungNeverSleepingConfirmed, confirmedByUser = true, action = "Open settings",
                ),
            )
        }
        add(
            CheckItem(
                CheckId.DND, "Through Do Not Disturb",
                "Urgent reminders and alarms should get through Do Not Disturb. Turn on \"Override Do Not Disturb\" " +
                    "(\"Ignore Do Not Disturb\" on Samsung) for the " +
                    (if (!f.urgentBypassesDnd) "Urgent" else "Alarm") + " category.",
                f.urgentBypassesDnd && f.alarmBypassesDnd, action = "Open settings",
            ),
        )
    }

    fun doneCount(items: List<CheckItem>) = items.count { it.done }
}
