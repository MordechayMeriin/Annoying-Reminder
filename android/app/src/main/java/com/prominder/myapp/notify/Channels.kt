package com.prominder.myapp.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.prominder.myapp.R
import com.prominder.myapp.i18n.AppLanguage

/**
 * The notification channels: three levels (once Pushover's normal / urgent / emergency),
 * plus the focus timer's silent countdown. You control each one in system settings: sound, vibration, Do Not Disturb.
 *
 * A channel's sound and importance can't be changed by the app once it exists (only by you), so the
 * ids are permanent. A different default sound later means a new id ("urgent_v2").
 */
object Channels {
    const val NORMAL = "normal"
    const val URGENT = "urgent"
    const val ALARM = "alarm"

    /** The focus timer's countdown (Phase E): silent, it only shows the time left. */
    const val SESSION = "session"

    val ALL = listOf(NORMAL, URGENT, ALARM, SESSION)

    /** Creates the channels, or renames them in [tag]'s words (a channel's name and description may change; its sound can't). */
    fun ensure(app: Context, tag: String = AppLanguage.current(app)) {
        val nm = app.getSystemService(NotificationManager::class.java)
        val context = AppLanguage.localized(app, tag)

        val normal = NotificationChannel(NORMAL, context.getString(R.string.channel_normal), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.channel_normal_desc)
        }

        // A long sound (the phone's ringtone), high priority, and a distinct buzz.
        val urgent = NotificationChannel(URGENT, context.getString(R.string.channel_urgent), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_urgent_desc)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 500)
            // Only a request: Android lets you, not the app, decide. The checklist opens this setting.
            setBypassDnd(true)
        }

        // The alarm clock's sound, played as an alarm (so the alarm volume applies, not the ringer's).
        val alarm = NotificationChannel(ALARM, context.getString(R.string.channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.channel_alarm_desc)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        val session = NotificationChannel(SESSION, context.getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.channel_session_desc)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }

        nm.createNotificationChannels(listOf(normal, urgent, alarm, session))
    }
}
