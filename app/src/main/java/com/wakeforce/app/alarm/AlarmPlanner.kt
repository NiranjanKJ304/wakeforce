package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import java.time.Instant
import java.time.ZonedDateTime

/** What to do with one alarm when re-syncing with AlarmManager. */
sealed interface SyncAction {
    val alarmId: Int

    /** Register the main ring at [at] for occurrence [occurrence] (+ its pre-alarm, if any). */
    data class Arm(override val alarmId: Int, val at: ZonedDateTime, val occurrence: String) : SyncAction

    /** Alarm is switched off: make sure nothing is registered. */
    data class Disarm(override val alarmId: Int) : SyncAction

    /** One-time alarm that rang, was skipped, or was missed while off/force-stopped: switch it off. */
    data class DisableMissed(override val alarmId: Int) : SyncAction
}

sealed interface UpsertResult {
    data class Saved(val alarms: List<Alarm>, val saved: Alarm, val nextId: Int) : UpsertResult
    data object Duplicate : UpsertResult
}

/** Result of answering a pre-alarm confirmation. */
sealed interface ConfirmPlan {
    /** The answer refers to an occurrence that is no longer pending: ignored. */
    data object Stale : ConfirmPlan

    data class Confirmed(val alarm: Alarm) : ConfirmPlan

    /** [action] arms the following occurrence (repeating) or switches a one-time alarm off. */
    data class Skipped(val alarm: Alarm, val action: SyncAction) : ConfirmPlan
}

/** Pure decision logic for scheduling and recovery (no Android APIs; unit-tested). */
object AlarmPlanner {

    /** A missed alarm still rings after a reboot if the phone came back within this window. */
    const val CATCH_UP_WINDOW_MS = 30 * 60 * 1000L

    /** Delay before a catch-up ring, so the system has finished booting. */
    const val CATCH_UP_DELAY_MS = 5_000L

    /** A future registration this close is kept as-is (it may be a pending catch-up ring). */
    const val IMMINENT_MS = 2 * 60 * 1000L

    /**
     * Inserts or replaces [alarm] in [alarms]. Rejects an alarm that would ring at exactly the
     * same moments as another one. Runs inside the storage transaction, so a double-tapped
     * Save can never create two identical alarms.
     */
    fun upsert(alarms: List<Alarm>, alarm: Alarm, nextId: Int): UpsertResult {
        if (alarms.any { it.id != alarm.id && it.sameScheduleAs(alarm) }) return UpsertResult.Duplicate
        var next = nextId
        val saved = if (alarm.id == Alarm.NEW_ID) alarm.copy(id = next++) else alarm
        val list = alarms.toMutableList()
        val index = list.indexOfFirst { it.id == saved.id }
        if (index >= 0) list[index] = saved else list += saved
        return UpsertResult.Saved(list, saved, next)
    }

    private fun arm(alarm: Alarm, occurrence: Occurrence) = SyncAction.Arm(alarm.id, occurrence.at, occurrence.key)

    /**
     * Decides how to re-register [alarm] at [now].
     *
     * @param catchUp true only for the first sync after a reboot: AlarmManager lost every
     *   registration, so an alarm whose time passed while the phone was restarting rings now.
     */
    fun planSync(alarm: Alarm, now: ZonedDateTime, catchUp: Boolean): SyncAction {
        if (!alarm.enabled) return SyncAction.Disarm(alarm.id)
        val nowMs = now.toInstant().toEpochMilli()
        val next = AlarmTimeCalculator.nextOccurrence(alarm, now)
        val armedAt = alarm.armedAt
        // v1 data has armedAt but no occurrence key: derive it from the armed instant.
        val armedOccurrence = alarm.occurrence
            ?: armedAt?.let {
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), now.zone).toLocalDateTime()
                    .truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString()
            }
            ?: next.key

        if (armedAt != null && armedAt <= nowMs) {
            // The registered occurrence is in the past and didn't ring (ringing updates armedAt).
            return when {
                catchUp && nowMs - armedAt <= CATCH_UP_WINDOW_MS && armedOccurrence != alarm.skippedOccurrence ->
                    SyncAction.Arm(alarm.id, now.plusNanos(CATCH_UP_DELAY_MS * 1_000_000), armedOccurrence)
                !alarm.isRepeating -> SyncAction.DisableMissed(alarm.id)
                else -> arm(alarm, next)
            }
        }
        if (armedAt != null && armedAt - nowMs <= IMMINENT_MS && armedOccurrence != alarm.skippedOccurrence) {
            val armed = ZonedDateTime.ofInstant(Instant.ofEpochMilli(armedAt), now.zone)
            if (armed.isBefore(next.at)) return SyncAction.Arm(alarm.id, armed, armedOccurrence)
        }
        return arm(alarm, next)
    }

    /** Where to go after an alarm rang: next occurrence for repeating alarms, off for one-time. */
    fun afterFire(alarm: Alarm, firedAt: ZonedDateTime): SyncAction =
        if (alarm.isRepeating) {
            // +1 min so the occurrence that is ringing right now is never re-armed.
            arm(alarm, AlarmTimeCalculator.nextOccurrence(alarm, firedAt.plusMinutes(1)))
        } else {
            SyncAction.DisableMissed(alarm.id)
        }

    /**
     * Applies a pre-alarm answer to [occurrence]. Only the occurrence that is currently armed
     * (and not ringing) can be confirmed or skipped, so an old confirmation can never affect a
     * later alarm. Skipping removes just that occurrence; the saved alarm and its other
     * occurrences are untouched.
     */
    fun planConfirm(alarm: Alarm, occurrence: String, keep: Boolean, now: ZonedDateTime, ringing: Boolean): ConfirmPlan {
        if (!alarm.enabled || ringing || alarm.occurrence != occurrence) return ConfirmPlan.Stale
        if (alarm.lastCompletedOccurrence == occurrence || alarm.skippedOccurrence == occurrence) return ConfirmPlan.Stale
        val at = AlarmTimeCalculator.occurrenceTime(occurrence, now.zone)
        if (at == null || !at.isAfter(now)) return ConfirmPlan.Stale
        if (keep) return ConfirmPlan.Confirmed(alarm.copy(confirmedOccurrence = occurrence))
        val skipped = alarm.copy(skippedOccurrence = occurrence, confirmedOccurrence = null)
        return if (alarm.isRepeating) {
            ConfirmPlan.Skipped(skipped, arm(skipped, AlarmTimeCalculator.nextOccurrence(skipped, now)))
        } else {
            ConfirmPlan.Skipped(skipped, SyncAction.DisableMissed(alarm.id))
        }
    }
}
