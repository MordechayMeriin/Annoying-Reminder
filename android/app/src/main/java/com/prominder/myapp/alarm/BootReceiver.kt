package com.prominder.myapp.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.remind.Reminders

/**
 * Android forgets every alarm when the phone restarts, so this sets them again. Also runs after an
 * app update, when "Alarms & reminders" is switched on (an inexact alarm can become exact), and when the clock
 * or the time zone changes (the next tick's time moves).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "phone restarted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> "'Alarms & reminders' switched on"
            Intent.ACTION_TIME_CHANGED -> "clock changed"
            Intent.ACTION_TIMEZONE_CHANGED -> "time zone changed"
            else -> return
        }
        Diag.log("system", reason.replaceFirstChar { it.uppercase() })
        TestAlarm.restore(context, reason)
        // The reminders: a tick now catches anything that came due meanwhile, and sets the next alarm.
        Reminders.tickSoon(context, reason)
    }
}
