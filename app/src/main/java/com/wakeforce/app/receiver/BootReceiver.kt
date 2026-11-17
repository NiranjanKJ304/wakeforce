package com.wakeforce.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.wakeforce.app.alarm.AlarmController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Re-arms all alarms whenever the system may have dropped or invalidated them: reboot
 * (including before first unlock), app update, manual clock change, time-zone change, or an
 * exact-alarm permission change. Rescheduling is idempotent.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                handle(context, intent)
            } catch (_: Exception) {
                // Storage unavailable: the next trigger (unlock BOOT_COMPLETED / app start) retries.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_TIME_ZONE = "time-zone"

        /** The receiver's work, separated from goAsync() so it can be tested directly. */
        suspend fun handle(context: Context, intent: Intent) {
            val action = intent.action
            if (action !in HANDLED_ACTIONS) return
            val now = nowFor(intent)
            val isBoot = action == Intent.ACTION_LOCKED_BOOT_COMPLETED || action == Intent.ACTION_BOOT_COMPLETED
            val controller = AlarmController(context)
            val catchUp = isBoot && controller.claimBootCatchUp(bootCount(context))
            controller.syncAll(now, catchUp)
            // An alarm that was ringing when the phone went off resumes with its progress.
            if (isBoot) controller.recoverAfterBoot(now)
        }

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )

        /**
         * For a time-zone change, use the zone carried by the broadcast: a process that is
         * already running may still have the old default zone cached when this arrives.
         */
        fun nowFor(intent: Intent): ZonedDateTime {
            val zoneId = if (intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
                // "time-zone" == Intent.EXTRA_TIMEZONE (public only from API 30, sent on all versions).
                intent.getStringExtra(EXTRA_TIME_ZONE)?.let { runCatching { ZoneId.of(it) }.getOrNull() }
            } else null
            return ZonedDateTime.now(zoneId ?: ZoneId.systemDefault())
        }

        private fun bootCount(context: Context): Int =
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }
}
