package com.wakeforce.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wakeforce.app.receiver.AlarmReceiver
import com.wakeforce.app.ui.MainActivity
import java.time.ZonedDateTime

/**
 * Thin wrapper over AlarmManager. Nothing runs in the background between events; every
 * callback is a one-shot registration:
 *  - main ring: [AlarmManager.setAlarmClock] (exact, Doze-exempt, shown as "next alarm");
 *  - pre-alarm: exact-while-idle (not shown as the next alarm, which stays the real one);
 *  - session timers (temporary-access end, post-reboot recovery): setAlarmClock.
 *
 * PendingIntent identity = action + request code, so each alarm owns exactly one main and one
 * pre-alarm registration and re-arming replaces instead of duplicating. Use [AlarmController],
 * which keeps stored state consistent, rather than calling this directly.
 */
class AlarmScheduler(context: Context) {

    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    fun arm(alarmId: Int, at: ZonedDateTime, occurrence: String) {
        setAlarmClock(at, broadcast(AlarmReceiver.ACTION_FIRE, alarmId, occurrence))
    }

    fun cancel(alarmId: Int) {
        alarmManager.cancel(broadcast(AlarmReceiver.ACTION_FIRE, alarmId, null))
    }

    /**
     * @param repost the one-off "show the unanswered prompt again after a reboot" registration;
     *   separate from the regular one so normal re-syncs can't cancel it by accident.
     */
    fun armPreAlarm(alarmId: Int, at: ZonedDateTime, occurrence: String, repost: Boolean = false) {
        val millis = at.toInstant().toEpochMilli()
        val operation = broadcast(AlarmReceiver.ACTION_PRE_ALARM, preAlarmRequestCode(alarmId, repost), occurrence, alarmId)
        try {
            if (canScheduleExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
        }
    }

    fun cancelPreAlarm(alarmId: Int, regular: Boolean = true, repost: Boolean = true) {
        if (regular) alarmManager.cancel(broadcast(AlarmReceiver.ACTION_PRE_ALARM, preAlarmRequestCode(alarmId, false), null, alarmId))
        if (repost) alarmManager.cancel(broadcast(AlarmReceiver.ACTION_PRE_ALARM, preAlarmRequestCode(alarmId, true), null, alarmId))
    }

    private fun preAlarmRequestCode(alarmId: Int, repost: Boolean) = if (repost) REPOST_REQUEST_OFFSET + alarmId else alarmId

    /** One registration per [action] (there is at most one active session). */
    fun armSessionTimer(action: String, at: ZonedDateTime, alarmId: Int, occurrence: String) {
        setAlarmClock(at, broadcast(action, SESSION_REQUEST_CODE, occurrence, alarmId))
    }

    fun cancelSessionTimer(action: String) {
        alarmManager.cancel(broadcast(action, SESSION_REQUEST_CODE, null))
    }

    private fun setAlarmClock(at: ZonedDateTime, operation: PendingIntent) {
        val millis = at.toInstant().toEpochMilli()
        try {
            if (canScheduleExact()) {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(millis, openAppPendingIntent()), operation)
            } else {
                // Exact alarms revoked by the user (API 31-32 only): best effort, may be late.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
            }
        } catch (e: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
        }
    }

    private fun broadcast(action: String, requestCode: Int, occurrence: String?, alarmId: Int = requestCode): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, AlarmReceiver::class.java)
                .setAction(action)
                .putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                .apply { occurrence?.let { putExtra(AlarmReceiver.EXTRA_OCCURRENCE, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private companion object {
        /** Alarm ids start at 1, so 0 never collides with a per-alarm registration. */
        const val SESSION_REQUEST_CODE = 0
        const val REPOST_REQUEST_OFFSET = 1_000_000
    }
}
