package com.annoyingreminder.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.annoyingreminder.app.diag.Diag

/**
 * Android forgets every alarm when the phone restarts, so this sets them again. Also runs after an
 * app update, and when "Alarms & reminders" is switched on (an inexact alarm can become exact).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "phone restarted"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> "'Alarms & reminders' switched on"
            else -> return
        }
        Diag.log("system", reason.replaceFirstChar { it.uppercase() })
        TestAlarm.restore(context, reason)
    }
}
