package com.annoyingreminder.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.annoyingreminder.app.R

/**
 * The three notification channels: the same three levels as the web app (Pushover normal / urgent /
 * emergency). You control each one in system settings: sound, vibration, Do Not Disturb.
 *
 * A channel's sound and importance can't be changed by the app once it exists (only by you), so the
 * ids are permanent. A different default sound later means a new id ("urgent_v2").
 */
object Channels {
    const val NORMAL = "normal"
    const val URGENT = "urgent"
    const val ALARM = "alarm"

    val ALL = listOf(NORMAL, URGENT, ALARM)

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)

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

        nm.createNotificationChannels(listOf(normal, urgent, alarm))
    }
}
