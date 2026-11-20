package com.prominder.myapp.remind

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prominder.myapp.ReminderApp
import com.prominder.myapp.alarm.AlarmReceiver
import com.prominder.myapp.alarm.Timing
import com.prominder.myapp.data.Store
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Notifier
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * Runs the tick on the phone. The server checked every 5 minutes; a phone that woke up that often would drain
 * the battery and Doze would delay it anyway. So after every tick and every change, the next moment something
 * could happen is worked out (Ticker.nextWake) and ONE exact alarm is set for it, the kind alarm clocks use,
 * which gets through Doze. Three safety nets:
 *  - Android forgets alarms when the phone restarts: the boot receiver runs a tick (which sets the next one);
 *  - a time-zone or clock change does the same;
 *  - every 15 minutes WorkManager runs a tick anyway, in case Samsung's battery saver held the alarm back.
 *
 * Nothing runs before the handover: until then the phone has none of your data.
 */
object Reminders {
    private const val PREFS = "reminders"
    private const val KEY_NEXT = "next_at"
    private const val KEY_EXACT = "next_exact"
    private const val KEY_LAST = "last_tick"
    private const val REQUEST_CODE = 2001
    private const val SAFETY_NET = "tick-safety-net"

    data class Next(val at: Long, val exact: Boolean)

    private fun app(context: Context) = context.applicationContext as ReminderApp

    /** Run a tick soon, in the background (after a restart, the handover, a clock change). */
    fun tickSoon(context: Context, reason: String) {
        val app = app(context)
        app.scope.launch { runCatching { tick(app, app.store, reason) }.onFailure { Diag.log("tick", "Tick failed: $it") } }
    }

    /** After a change: set the alarm for the next tick (a new reminder, a done task…). */
    fun replan(context: Context) {
        val app = app(context)
        app.scope.launch {
            runCatching {
                if (app.store.hasData()) {
                    setAlarm(app, Ticker(app.store).nextWake())
                    SessionTimer.refresh(app, app.store)
                }
            }.onFailure { Diag.log("tick", "Couldn't plan the next tick: $it") }
        }
    }

    /**
     * One tick: send what's due (notifications), log why anything was held back, set the next alarm.
     * [scheduledAt]: when the alarm was meant to ring, to log how late it was.
     */
    suspend fun tick(context: Context, store: Store, reason: String, scheduledAt: Long? = null) {
        if (!store.hasData()) return
        val app = context.applicationContext as? ReminderApp
        val report = (if (app != null) Ticker(store, app.llm, app.promptFiles.prompts) else Ticker(store)).tick()
        report.notices.forEach { Notifier.showNotice(context, it) }
        val late = scheduledAt?.let { " (" + Timing.describeLateness(System.currentTimeMillis() - it) + ")" }.orEmpty()
        if (report.lines.isEmpty()) {
            if (reason != SAFETY_NET_REASON) Diag.log("tick", "Tick: $reason$late, nothing due")
        } else {
            Diag.log("tick", "Tick: $reason$late")
            report.lines.forEach { Diag.log("tick", "  $it") }
        }
        prefs(context).edit { putLong(KEY_LAST, System.currentTimeMillis()) }
        setAlarm(context, report.nextWake)
        SessionTimer.refresh(context, store)
    }

    /** The exact alarm for the next tick (inexact, "within a few minutes", when Alarms & reminders is off). */
    fun setAlarm(context: Context, at: Instant): Next {
        val am = context.getSystemService(AlarmManager::class.java)
        val ms = at.toEpochMilli()
        val fire = PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TICK).putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, ms),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, fire)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, fire)
        prefs(context).edit { putLong(KEY_NEXT, ms).putBoolean(KEY_EXACT, exact) }
        return Next(ms, exact)
    }

    fun next(context: Context): Next? {
        val p = prefs(context)
        val at = p.getLong(KEY_NEXT, 0)
        return if (at > 0) Next(at, p.getBoolean(KEY_EXACT, false)) else null
    }

    fun lastTick(context: Context): Long? = prefs(context).getLong(KEY_LAST, 0).takeIf { it > 0 }

    /** The 15-minute safety net (WorkManager's shortest period). */
    fun startSafetyNet(context: Context) {
        val request = PeriodicWorkRequestBuilder<TickWorker>(15, TimeUnit.MINUTES).build()
        runCatching { WorkManager.getInstance(context).enqueueUniquePeriodicWork(SAFETY_NET, ExistingPeriodicWorkPolicy.KEEP, request) }
            .onFailure { Diag.log("tick", "Can't start the safety net: ${it.message}") }
    }

    const val SAFETY_NET_REASON = "safety net"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The safety net: a tick every 15 minutes, whatever the alarm did. */
class TickWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as ReminderApp
        runCatching { Reminders.tick(app, app.store, Reminders.SAFETY_NET_REASON) }.onFailure { Diag.log("tick", "Tick failed: $it") }
        return Result.success()
    }
}
