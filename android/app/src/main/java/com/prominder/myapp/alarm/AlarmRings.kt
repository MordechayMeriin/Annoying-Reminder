package com.prominder.myapp.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.edit
import com.prominder.myapp.MainActivity
import com.prominder.myapp.diag.Diag
import com.prominder.myapp.notify.Notifier
import com.prominder.myapp.remind.Notice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The alarm's repeat rules, as Pushover's emergency priority did on the web app: an alarm-level message rings (full
 * screen, on the alarm volume) for up to [RING_MS], then rings again every "Alarm repeats every" minutes (⚙, default
 * 2) until you answer it, for at most its window: an hour, and never into quiet hours or Shabbat (the tick worked the
 * window out, Notice.alarmSeconds).
 *
 * Answering stops it: ✓ Done, Later, Open or Dismiss on the alarm screen or its notification, or anything you write or
 * tap in the app (Store's onUserActivity), since you've clearly seen it. A newer alarm replaces a ringing one.
 *
 * Each ring is a fresh notification, so it's loud again even if the phone went back to sleep. Between rings the alarm
 * screen stays open if it was showing. The next ring is an alarm-clock alarm (AlarmManager.setAlarmClock), the only
 * kind Doze lets through more than once every 9 minutes. What's ringing is saved, so a restart doesn't lose it.
 */
object AlarmRings {
    /** One ring at most this long; then quiet until the next one. */
    const val RING_MS = 60_000L

    private const val PREFS = "alarm_rings"
    private const val REQUEST_CODE = 2002

    /** The alarm that's repeating: what it says, and when it stops by itself. */
    data class Ringing(
        val messageId: Long,
        val title: String,
        val text: String,
        val taskId: Long?,
        val lang: String,
        val startedAt: Long,
        val until: Long,
        val everyMs: Long,
        val rings: Int = 1,
    ) {
        /** How long this ring lasts: a minute, or less when the next ring or the end is closer. */
        fun ringMs(now: Long): Long = minOf(RING_MS, everyMs, until - now).coerceAtLeast(1_000L)

        /** When the next ring is due, or null when none fits before the end. */
        fun nextRingAt(now: Long): Long? = (now + everyMs).takeIf { it < until }
    }

    private val _stops = MutableStateFlow(0)

    /** Counts the alarms stopped: an open alarm screen closes itself when this changes. */
    val stops: StateFlow<Int> = _stops

    /** An alarm-level message from the tick: the first ring, and the next one planned. */
    fun start(context: Context, notice: Notice, now: Long = System.currentTimeMillis()) {
        val everyMs = notice.alarmEveryMin.coerceIn(1, 30) * 60_000L
        val r = Ringing(notice.messageId, notice.title, notice.text, notice.taskId, notice.lang, now,
            now + notice.alarmSeconds * 1000, everyMs)
        save(context, r)
        ring(context, r, now)
        Diag.log("alarm", "Alarm for message #${r.messageId}: rings every ${everyMs / 60_000} min until " +
            "${Diag.log.format(r.until)} or until you answer it")
    }

    /** The receiver: the next ring, unless it was answered meanwhile or its window is over. */
    fun ringAgain(context: Context, now: Long = System.currentTimeMillis()) {
        val r = current(context) ?: return
        if (now >= r.until) {
            clear(context)
            Notifier.cancelAlarm(context)
            Diag.log("alarm", "Alarm for message #${r.messageId} stopped by itself after ${r.rings} rings: its window is over")
            return
        }
        val next = r.copy(rings = r.rings + 1)
        save(context, next)
        ring(context, next, now)
        Diag.log("alarm", "Alarm for message #${r.messageId} rang again (${next.rings})")
    }

    /** You answered (or dismissed it): no more rings. Nothing happens when no alarm is ringing. */
    fun stop(context: Context, why: String) {
        val r = current(context) ?: return
        clear(context)
        context.getSystemService(AlarmManager::class.java).cancel(pending(context))
        Notifier.cancelAlarm(context)
        _stops.value += 1
        Diag.log("alarm", "Alarm for message #${r.messageId} stopped: $why")
    }

    /** After a restart or an app update: plan the next ring again (alarms are forgotten), or forget a finished one. */
    fun restore(context: Context, reason: String, now: Long = System.currentTimeMillis()) {
        val r = current(context) ?: return
        if (now >= r.until) {
            clear(context)
            return
        }
        schedule(context, now + 5_000L)
        Diag.log("alarm", "Alarm for message #${r.messageId} still unanswered: rings again ($reason)")
    }

    fun current(context: Context): Ringing? {
        val p = prefs(context)
        val id = p.getLong("message_id", -1)
        if (id < 0) return null
        return Ringing(id, p.getString("title", "").orEmpty(), p.getString("text", "").orEmpty(),
            p.getLong("task_id", -1).takeIf { it >= 0 }, p.getString("lang", "en") ?: "en",
            p.getLong("started_at", 0), p.getLong("until", 0), p.getLong("every_ms", 120_000), p.getInt("rings", 1))
    }

    private fun ring(context: Context, r: Ringing, now: Long) {
        Notifier.showAlarmRing(context, r, r.ringMs(now))
        val next = r.nextRingAt(now)
        if (next != null) schedule(context, next) else context.getSystemService(AlarmManager::class.java).cancel(pending(context))
    }

    private fun schedule(context: Context, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val fire = pending(context)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            val show = PendingIntent.getActivity(context, REQUEST_CODE, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), fire)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fire)
        }
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_ALARM_RING),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun save(context: Context, r: Ringing) = prefs(context).edit {
        putLong("message_id", r.messageId).putString("title", r.title).putString("text", r.text)
            .putLong("task_id", r.taskId ?: -1).putString("lang", r.lang).putLong("started_at", r.startedAt)
            .putLong("until", r.until).putLong("every_ms", r.everyMs).putInt("rings", r.rings)
    }

    private fun clear(context: Context) = prefs(context).edit { clear() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
