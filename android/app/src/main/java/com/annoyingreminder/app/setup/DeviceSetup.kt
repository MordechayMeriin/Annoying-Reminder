package com.annoyingreminder.app.setup

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.annoyingreminder.app.alarm.TestAlarm
import com.annoyingreminder.app.diag.Diag
import com.annoyingreminder.app.notify.Channels
import com.annoyingreminder.app.notify.Notifier

/** Reads the checklist's facts from the phone, and opens the settings screen for each item. */
object DeviceSetup {
    private const val PREFS = "setup"
    private const val KEY_SAMSUNG_CONFIRMED = "samsung_never_sleeping"
    private const val KEY_CHECKLIST_SEEN = "checklist_seen"

    fun facts(context: Context): DeviceFacts {
        val nm = context.getSystemService(NotificationManager::class.java)
        val pm = context.getSystemService(PowerManager::class.java)
        return DeviceFacts(
            sdk = Build.VERSION.SDK_INT,
            isSamsung = isSamsung(),
            notificationsOn = Notifier.canNotify(context),
            exactAlarmsOn = TestAlarm.canScheduleExact(context),
            fullScreenOn = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent(),
            batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName),
            samsungNeverSleepingConfirmed = prefs(context).getBoolean(KEY_SAMSUNG_CONFIRMED, false),
            urgentBypassesDnd = nm.getNotificationChannel(Channels.URGENT)?.canBypassDnd() == true,
            alarmBypassesDnd = nm.getNotificationChannel(Channels.ALARM)?.canBypassDnd() == true,
        )
    }

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun setSamsungConfirmed(context: Context, confirmed: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAMSUNG_CONFIRMED, confirmed).apply()
        Diag.log("setup", "Samsung never-sleeping " + if (confirmed) "ticked" else "unticked")
    }

    /** The checklist opens by itself once, right after the first login. */
    fun checklistSeen(context: Context) = prefs(context).getBoolean(KEY_CHECKLIST_SEEN, false)
    fun markChecklistSeen(context: Context) = prefs(context).edit().putBoolean(KEY_CHECKLIST_SEEN, true).apply()

    /** Opens the settings screen for [id]. (Notifications on Android 13+ are a permission dialog instead.) */
    @SuppressLint("InlinedApi") // Each newer screen is only listed on the Android versions that have it.
    fun open(context: Context, id: CheckId, facts: DeviceFacts) {
        val pkg = context.packageName
        val pkgUri = Uri.parse("package:$pkg")
        val candidates: List<Intent> = when (id) {
            CheckId.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg),
            )
            CheckId.EXACT_ALARMS -> listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri))
            CheckId.FULL_SCREEN -> listOf(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri))
            // The list of apps, not the one-tap "stop optimizing" dialog, which Play allows only for some apps.
            CheckId.BATTERY -> listOf(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            CheckId.SAMSUNG_SLEEP -> listOf(
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")),
                Intent().setComponent(ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")),
                Intent("com.samsung.android.sm.ACTION_BATTERY"),
            )
            CheckId.DND -> listOf(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, if (!facts.urgentBypassesDnd) Channels.URGENT else Channels.ALARM),
            )
        }
        // Every phone has the app's own settings page; it's the fallback when a screen doesn't exist.
        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)
        for (intent in candidates + fallback) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Diag.log("setup", "Opened settings for $id")
                return
            } catch (e: ActivityNotFoundException) {
                continue
            } catch (e: SecurityException) {
                continue
            }
        }
        Diag.log("setup", "No settings screen found for $id")
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
