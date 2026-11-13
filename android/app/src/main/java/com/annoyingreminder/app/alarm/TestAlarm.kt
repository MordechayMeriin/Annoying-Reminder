package com.annoyingreminder.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.annoyingreminder.app.MainActivity
import com.annoyingreminder.app.diag.Diag

/**
 * The "alarm in 1 minute / 1 hour" test: one exact alarm that rings through Doze, like an alarm clock.
 * The real scheduler (Phase C) works the same way: one exact alarm for the next thing that's due.
 *
 * The pending time is saved so the boot receiver can set it again after a restart (Android forgets
 * alarms when the phone turns off), and the result is saved for the Diagnostics screen.
 */
object TestAlarm {
    private const val PREFS = "test_alarm"
    private const val KEY_AT = "at"
    private const val KEY_EXACT = "exact"
    private const val KEY_LAST_RESULT = "last_result"
    private const val REQUEST_CODE = 1001

    data class Pending(val at: Long, val exact: Boolean)

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun schedule(context: Context, delayMs: Long): Pending {
        val at = System.currentTimeMillis() + delayMs
        val pending = setAlarm(context, at)
        prefs(context).edit().putLong(KEY_AT, at).putBoolean(KEY_EXACT, pending.exact).apply()
        Diag.log(
            "alarm",
            "Test alarm set for ${Diag.log.format(at)} (" +
                (if (pending.exact) "exact" else "inexact: 'Alarms & reminders' is off, so it may be minutes late") + ")",
        )
        return pending
    }

    fun cancel(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(fireIntent(context, 0))
        prefs(context).edit().remove(KEY_AT).remove(KEY_EXACT).apply()
        Diag.log("alarm", "Test alarm cancelled")
    }

    fun pending(context: Context): Pending? {
        val p = prefs(context)
        val at = p.getLong(KEY_AT, 0)
        return if (at > 0) Pending(at, p.getBoolean(KEY_EXACT, false)) else null
    }

    /** After a restart or an app update: set the saved alarm again, or ring now if its time passed. */
    fun restore(context: Context, reason: String) {
        val saved = pending(context) ?: return
        if (saved.at <= System.currentTimeMillis()) {
            Diag.log("alarm", "Test alarm's time passed while the alarm wasn't set ($reason): ringing now")
            context.sendBroadcast(fireIntentRaw(context, saved.at))
        } else {
            val again = setAlarm(context, saved.at)
            prefs(context).edit().putBoolean(KEY_EXACT, again.exact).apply()
            Diag.log("alarm", "Test alarm for ${Diag.log.format(saved.at)} set again ($reason)")
        }
    }

    /** Called by the receiver when the alarm rings: forget the pending one, remember the result. */
    fun markRang(context: Context, scheduledAt: Long, lateMs: Long): String {
        val result = "Set for ${Diag.log.format(scheduledAt)}, rang ${Timing.describeLateness(lateMs)}" +
            if (Timing.passesDozeTest(lateMs)) "" else " (too late: see the checklist)"
        prefs(context).edit().remove(KEY_AT).remove(KEY_EXACT).putString(KEY_LAST_RESULT, result).apply()
        return result
    }

    fun lastResult(context: Context): String? = prefs(context).getString(KEY_LAST_RESULT, null)

    private fun setAlarm(context: Context, at: Long): Pending {
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = fireIntent(context, at)
        return if (canScheduleExact(context)) {
            // setAlarmClock: the most reliable kind; it also shows the alarm icon in the status bar.
            val show = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
            Pending(at, exact = true)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
            Pending(at, exact = false)
        }
    }

    private fun fireIntentRaw(context: Context, at: Long): Intent =
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_TEST_ALARM)
            .putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, at)

    private fun fireIntent(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, fireIntentRaw(context, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
