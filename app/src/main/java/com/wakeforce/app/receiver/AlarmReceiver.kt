package com.wakeforce.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.wakeforce.app.alarm.AlarmController
import com.wakeforce.app.alarm.AlarmSession
import com.wakeforce.app.service.AlarmNotifications
import com.wakeforce.app.service.AlarmService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * All AlarmManager callbacks land here:
 *  - FIRE: start the ringing service first, then re-arm the alarm (here, not in the service,
 *    so a repeating alarm is never lost when the service cannot start);
 *  - PRE_ALARM: show the "still need this alarm?" confirmation if it's still current;
 *  - ACCESS_END: a temporary-access window is over, resume full alarm;
 *  - RECOVER: resume a session that was ringing when the phone restarted.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val occurrence = intent.getStringExtra(EXTRA_OCCURRENCE)
        when (intent.action) {
            ACTION_FIRE -> {
                if (alarmId < 0) return
                startRinging(context, alarmId, occurrence)
                AlarmNotifications.cancelPreAlarm(context, alarmId)
                runAsync { AlarmController(context).onFired(alarmId) }
            }
            ACTION_RECOVER -> if (alarmId >= 0 && occurrence != null) startRinging(context, alarmId, occurrence)
            ACTION_PRE_ALARM -> {
                if (alarmId < 0 || occurrence == null) return
                runAsync {
                    AlarmController(context).preAlarmToShow(alarmId, occurrence)?.let {
                        AlarmNotifications.showPreAlarm(context, it, occurrence)
                    }
                }
            }
            ACTION_ACCESS_END -> {
                if (alarmId < 0 || occurrence == null) return
                runAsync {
                    AlarmController(context).endTemporaryAccess(AlarmSession.occurrenceId(alarmId, occurrence), userReturned = false)
                }
            }
        }
    }

    private fun startRinging(context: Context, alarmId: Int, occurrence: String?) {
        try {
            // Allowed from the background: alarm-clock broadcasts are exempt from FGS start limits.
            AlarmService.start(context, alarmId, occurrence)
        } catch (e: Exception) {
            // E.g. ForegroundServiceStartNotAllowedException if exact alarms were revoked and the
            // alarm arrived inexactly. Fall back to a full-screen notification; the alarm screen
            // then starts the service itself from the foreground.
            AlarmNotifications.showFallback(context, alarmId, occurrence)
        }
    }

    private fun runAsync(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                block()
            } catch (_: Exception) {
                // Storage failure: the next app start / boot sync re-arms from stored state.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.wakeforce.app.action.FIRE_ALARM"
        const val ACTION_PRE_ALARM = "com.wakeforce.app.action.PRE_ALARM"
        const val ACTION_ACCESS_END = "com.wakeforce.app.action.ACCESS_END"
        const val ACTION_RECOVER = "com.wakeforce.app.action.RECOVER"
        const val EXTRA_ALARM_ID = "alarm_id"
        const val EXTRA_OCCURRENCE = "occurrence"
    }
}
