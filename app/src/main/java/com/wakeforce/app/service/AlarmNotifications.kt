package com.wakeforce.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import com.wakeforce.app.R
import com.wakeforce.app.alarm.AlarmTimeCalculator
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.ui.AlarmActivity
import com.wakeforce.app.ui.PreAlarmActivity
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

object AlarmNotifications {
    const val CHANNEL_ID = "wakeforce_ringing"
    const val PRE_ALARM_CHANNEL_ID = "wakeforce_prealarm"
    const val RINGING_ID = 1001
    private const val FALLBACK_ID = 1002
    private const val PRE_ALARM_ID_BASE = 20_000
    private const val FALLBACK_REQUEST_OFFSET = 1_000_000
    private const val PRE_ALARM_REQUEST_OFFSET = 2_000_000

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_ringing), NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = context.getString(R.string.channel_ringing_desc)
                        setSound(null, null) // The service plays the alarm tone itself on the alarm stream.
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
            )
        }
        if (manager.getNotificationChannel(PRE_ALARM_CHANNEL_ID) == null) {
            // Default notification sound: a gentle prompt, not an alarm.
            manager.createNotificationChannel(
                NotificationChannel(
                    PRE_ALARM_CHANNEL_ID, context.getString(R.string.channel_prealarm), NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_prealarm_desc)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    /** Ongoing notification for the foreground service; its full-screen intent opens the challenge. */
    fun ringing(
        context: Context,
        alarmId: Int,
        startServiceIfNeeded: Boolean = false,
        occurrence: String? = null,
        accessEndsAt: Long? = null,
    ): Notification {
        ensureChannel(context)
        // Distinct request codes: PendingIntent identity ignores extras, so without this the
        // fallback (start-service) and normal intents would overwrite each other's extras.
        val open = PendingIntent.getActivity(
            context,
            if (startServiceIfNeeded) FALLBACK_REQUEST_OFFSET + alarmId else alarmId,
            AlarmActivity.intent(context, alarmId, startServiceIfNeeded, occurrence),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val inAccess = accessEndsAt != null
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(if (inAccess) "Temporary access" else "Alarm ringing")
            .setContentText(
                if (inAccess) "Alarm resumes at ${clock(context, accessEndsAt)}. Tap to return to the challenge."
                else "Complete your challenges to stop it",
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(inAccess)
            .setContentIntent(open)
            .apply { if (!inAccess) setFullScreenIntent(open, true) }
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Used only if the foreground service could not be started from the background. */
    fun showFallback(context: Context, alarmId: Int, occurrence: String?) {
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(FALLBACK_ID, ringing(context, alarmId, startServiceIfNeeded = true, occurrence = occurrence))
        } catch (e: SecurityException) {
            // Notification permission missing; nothing more we can do from the background.
        }
    }

    fun cancelFallback(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(FALLBACK_ID)
    }

    /** "Do you still need this alarm?" for one specific occurrence; disappears at alarm time. */
    fun showPreAlarm(context: Context, alarm: Alarm, occurrence: String) {
        ensureChannel(context)
        val now = ZonedDateTime.now()
        val at = AlarmTimeCalculator.occurrenceTime(occurrence, now.zone) ?: return
        val open = PendingIntent.getActivity(
            context,
            PRE_ALARM_REQUEST_OFFSET + alarm.id,
            PreAlarmActivity.intent(context, alarm.id, occurrence),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val time = LocalTime.of(alarm.hour, alarm.minute).format(formatter(context))
        val notification = NotificationCompat.Builder(context, PRE_ALARM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("Do you need your $time alarm?")
            .setContentText("Tap to keep it or skip just this one. No answer = it rings as planned.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setTimeoutAfter(Duration.between(now, at).toMillis().coerceAtLeast(1))
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(PRE_ALARM_ID_BASE + alarm.id, notification)
        } catch (e: SecurityException) {
            // Notifications disabled: the main alarm still rings as scheduled.
        }
    }

    fun cancelPreAlarm(context: Context, alarmId: Int) {
        context.getSystemService(NotificationManager::class.java).cancel(PRE_ALARM_ID_BASE + alarmId)
    }

    private fun formatter(context: Context) =
        DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")

    private fun clock(context: Context, epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()).format(formatter(context))
}
