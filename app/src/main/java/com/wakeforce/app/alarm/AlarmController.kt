package com.wakeforce.app.alarm

import android.content.Context
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.AlarmRepository
import com.wakeforce.app.data.SessionRepository
import com.wakeforce.app.receiver.AlarmReceiver
import com.wakeforce.app.service.AlarmNotifications
import com.wakeforce.app.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * The single authority for alarm state, AlarmManager registrations and ringing-session
 * transitions.
 *
 * Every operation runs under one process-wide lock and re-reads the latest stored state, so
 * the app-start sync, boot/time-change sync, post-ring rescheduling, pre-alarm answers,
 * challenge submissions and user edits can never interleave or apply to a stale occurrence.
 */
class AlarmController(context: Context) {

    private val appContext = context.applicationContext
    private val repo = AlarmRepository(context)
    private val sessions = SessionRepository(context)
    private val settings = SettingsRepository(context)
    private val scheduler = AlarmScheduler(context)

    sealed interface SaveOutcome {
        data class Saved(val alarm: Alarm, val trigger: ZonedDateTime?) : SaveOutcome
        data object Duplicate : SaveOutcome
    }

    sealed interface FireDecision {
        /** Ring (new session, or resume of the same occurrence after process death/reboot). */
        data class Ring(val session: AlarmSession) : FireDecision
        /** Another alarm is ringing; this one gets its own session afterwards. */
        data object Queued : FireDecision
        /** Skipped, already completed, deleted, or a repeated broadcast. */
        data object Ignore : FireDecision
    }

    // --- Alarm configuration --------------------------------------------------------------------

    suspend fun save(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): SaveOutcome = lock.withLock {
        val stored = if (alarm.id == Alarm.NEW_ID) null else repo.get(alarm.id)
        // User edits only change configuration; bookkeeping always comes from storage, and a
        // skip/confirmation no longer applies once the schedule itself changes.
        val keepAnswers = stored != null && stored.sameScheduleAs(alarm)
        val merged = alarm.copy(
            armedAt = stored?.armedAt,
            occurrence = stored?.occurrence,
            skippedOccurrence = if (keepAnswers) stored.skippedOccurrence else null,
            confirmedOccurrence = if (keepAnswers) stored.confirmedOccurrence else null,
            lastCompletedOccurrence = stored?.lastCompletedOccurrence,
        )
        val saved = repo.upsert(merged) ?: return@withLock SaveOutcome.Duplicate
        SaveOutcome.Saved(saved, armFresh(saved, now))
    }

    suspend fun setEnabled(id: Int, enabled: Boolean, now: ZonedDateTime = ZonedDateTime.now()): ZonedDateTime? =
        lock.withLock {
            val updated = repo.update(id) {
                // Switching an alarm back on is a fresh decision: an earlier skip/confirmation no longer applies.
                if (enabled && !it.enabled) it.copy(enabled = true, skippedOccurrence = null, confirmedOccurrence = null)
                else it.copy(enabled = enabled)
            } ?: return@withLock null
            armFresh(updated, now)
        }

    suspend fun delete(id: Int) = lock.withLock {
        scheduler.cancel(id)
        scheduler.cancelPreAlarm(id)
        AlarmNotifications.cancelPreAlarm(appContext, id)
        repo.delete(id)
    }

    /**
     * Re-registers every alarm (app start, reboot, update, clock or time-zone change).
     * @param catchUp first sync after a reboot: alarms missed during the restart ring now.
     */
    suspend fun syncAll(now: ZonedDateTime = ZonedDateTime.now(), catchUp: Boolean = false) = lock.withLock {
        for (alarm in repo.getAll()) apply(AlarmPlanner.planSync(alarm, now, catchUp), now, catchUp)
    }

    /** Called once per delivered alarm: re-arms repeating alarms, switches one-time alarms off. */
    suspend fun onFired(id: Int, now: ZonedDateTime = ZonedDateTime.now()) = lock.withLock {
        val alarm = repo.get(id) ?: return@withLock
        apply(AlarmPlanner.afterFire(alarm, now), now)
    }

    suspend fun claimBootCatchUp(bootCount: Int): Boolean = repo.claimBootCatchUp(bootCount)

    // --- Pre-alarm confirmation -----------------------------------------------------------------

    /** The alarm whose pre-alarm for [occurrence] should be shown now, or null if it's stale. */
    suspend fun preAlarmToShow(alarmId: Int, occurrence: String, now: ZonedDateTime = ZonedDateTime.now()): Alarm? =
        lock.withLock {
            val alarm = repo.get(alarmId) ?: return@withLock null
            val at = AlarmTimeCalculator.occurrenceTime(occurrence, now.zone) ?: return@withLock null
            val active = sessions.get()?.takeIf { it.isActive }
            val valid = alarm.enabled && alarm.preAlarmEnabled && alarm.occurrence == occurrence && at.isAfter(now) &&
                occurrence != alarm.skippedOccurrence && occurrence != alarm.confirmedOccurrence &&
                occurrence != alarm.lastCompletedOccurrence &&
                !(active != null && active.alarmId == alarmId && active.occurrence == occurrence)
            alarm.takeIf { valid }
        }

    /**
     * "YES, I need it" keeps the occurrence; "NO" skips only that occurrence. Ignoring the
     * prompt changes nothing, so the alarm rings as scheduled.
     */
    suspend fun confirmPreAlarm(
        alarmId: Int,
        occurrence: String,
        keep: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): ConfirmPlan = lock.withLock {
        val alarm = repo.get(alarmId) ?: return@withLock ConfirmPlan.Stale
        val ringing = sessions.get()?.let { it.isActive && it.alarmId == alarmId && it.occurrence == occurrence } == true
        val plan = AlarmPlanner.planConfirm(alarm, occurrence, keep, now, ringing)
        when (plan) {
            ConfirmPlan.Stale -> Unit
            is ConfirmPlan.Confirmed -> {
                repo.update(alarmId) { it.copy(confirmedOccurrence = occurrence) }
                scheduler.cancelPreAlarm(alarmId)
            }
            is ConfirmPlan.Skipped -> {
                repo.update(alarmId) { it.copy(skippedOccurrence = occurrence, confirmedOccurrence = null) }
                scheduler.cancelPreAlarm(alarmId)
                apply(plan.action, now)
            }
        }
        plan
    }

    // --- Ringing session ------------------------------------------------------------------------

    suspend fun startSession(alarmId: Int, occurrence: String?, now: ZonedDateTime = ZonedDateTime.now()): FireDecision =
        lock.withLock {
            val alarm = repo.get(alarmId)
            // Registrations made by v1 carry no occurrence: derive it from today's alarm time.
            val occ = occurrence ?: alarm?.let { LocalDateTime.of(now.toLocalDate(), LocalTime.of(it.hour, it.minute)).toString() }
                ?: return@withLock FireDecision.Ignore
            val current = sessions.get()
            if (current != null && current.occurrenceId == AlarmSession.occurrenceId(alarmId, occ)) {
                // Repeated broadcast / redelivery: resume, never a second session; completed stays completed.
                return@withLock if (current.isActive) FireDecision.Ring(current) else FireDecision.Ignore
            }
            if (alarm == null || occ == alarm.skippedOccurrence || occ == alarm.lastCompletedOccurrence) {
                return@withLock FireDecision.Ignore
            }
            if (current != null && current.isActive) {
                if (current.queued.none { it.alarmId == alarmId && it.occurrence == occ }) {
                    sessions.set(current.copy(queued = current.queued + QueuedFire(alarmId, occ)))
                }
                return@withLock FireDecision.Queued
            }
            val session = SessionMachine.start(alarm, occ, now.toInstant().toEpochMilli(), settings.mathSettings.first())
            sessions.set(session)
            FireDecision.Ring(session)
        }

    /** Applies one math answer. [occurrenceId] must be the ringing occurrence. */
    suspend fun submitMath(occurrenceId: String, input: String, now: ZonedDateTime = ZonedDateTime.now()): MathResult =
        lock.withLock {
            val session = sessions.get()?.takeIf { it.occurrenceId == occurrenceId } ?: return@withLock MathResult.NOT_ALLOWED
            val step = SessionMachine.submitMath(session, input, now.toInstant().toEpochMilli())
            persist(step.session, now)
            step.result
        }

    /** Applies one QR scan. A scan can only count towards the occurrence it was made for. */
    suspend fun submitQr(occurrenceId: String, scanned: String, now: ZonedDateTime = ZonedDateTime.now()): QrResult =
        lock.withLock {
            val session = sessions.get()?.takeIf { it.occurrenceId == occurrenceId } ?: return@withLock QrResult.NOT_ALLOWED
            val step = SessionMachine.submitQr(session, scanned, now.toInstant().toEpochMilli())
            if (step.session != session) persist(step.session, now)
            step.result
        }

    suspend fun startTemporaryAccess(occurrenceId: String, now: ZonedDateTime = ZonedDateTime.now()): AccessResult =
        lock.withLock {
            val session = sessions.get()?.takeIf { it.occurrenceId == occurrenceId } ?: return@withLock AccessResult.NOT_ALLOWED
            val step = SessionMachine.startAccess(session, now.toInstant().toEpochMilli())
            if (step.result == AccessResult.STARTED) {
                sessions.set(step.session)
                val endsAt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(step.session.accessEndsAt!!), now.zone)
                scheduler.armSessionTimer(AlarmReceiver.ACTION_ACCESS_END, endsAt, session.alarmId, session.occurrence)
            }
            step.result
        }

    suspend fun endTemporaryAccess(
        occurrenceId: String,
        userReturned: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
    ): AccessResult = lock.withLock {
        val session = sessions.get()?.takeIf { it.occurrenceId == occurrenceId } ?: return@withLock AccessResult.NOT_ALLOWED
        val step = SessionMachine.endAccess(session, now.toInstant().toEpochMilli(), userReturned)
        if (step.result == AccessResult.ENDED) {
            sessions.set(step.session)
            scheduler.cancelSessionTimer(AlarmReceiver.ACTION_ACCESS_END)
        }
        step.result
    }

    /** Re-registers the access-end callback (AlarmManager registrations don't survive reboot). */
    suspend fun rearmAccessTimer(now: ZonedDateTime = ZonedDateTime.now()) = lock.withLock {
        val session = sessions.get() ?: return@withLock
        val endsAt = session.accessEndsAt ?: return@withLock
        if (session.state != SessionState.TEMPORARY_ACCESS) return@withLock
        val at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(endsAt), now.zone)
        scheduler.armSessionTimer(AlarmReceiver.ACTION_ACCESS_END, if (at.isAfter(now)) at else now.plusSeconds(1),
            session.alarmId, session.occurrence)
    }

    /**
     * After a reboot: an alarm that was still ringing resumes (progress kept) if it started
     * within [SessionMachine.RECOVERY_MAX_AGE_MS]; older sessions are marked MISSED.
     */
    suspend fun recoverAfterBoot(now: ZonedDateTime = ZonedDateTime.now()): Boolean = lock.withLock {
        val session = sessions.get()?.takeIf { it.isActive } ?: return@withLock false
        if (!SessionMachine.shouldRecover(session, now.toInstant().toEpochMilli())) {
            sessions.set(SessionMachine.markMissed(session))
            return@withLock false
        }
        scheduler.armSessionTimer(
            AlarmReceiver.ACTION_RECOVER, now.plusNanos(AlarmPlanner.CATCH_UP_DELAY_MS * 1_000_000),
            session.alarmId, session.occurrence,
        )
        true
    }

    // --- Internals ------------------------------------------------------------------------------

    private suspend fun persist(session: AlarmSession, now: ZonedDateTime) {
        if (session.state != SessionState.COMPLETED) {
            sessions.set(session)
            return
        }
        val completedAt = session.completedAt ?: now.toInstant().toEpochMilli()
        sessions.setWithStats(session) {
            it.copy(
                completedCount = it.completedCount + 1,
                lastDismissedAt = completedAt,
                lastDurationMs = completedAt - session.startedAt,
            )
        }
        repo.update(session.alarmId) { it.copy(lastCompletedOccurrence = session.occurrence) }
        scheduler.cancelSessionTimer(AlarmReceiver.ACTION_ACCESS_END)
        startNextQueued(session, now)
    }

    /** Alarms that fired during the finished session ring next, each with its own challenge. */
    private suspend fun startNextQueued(done: AlarmSession, now: ZonedDateTime) {
        done.queued.forEachIndexed { index, q ->
            val alarm = repo.get(q.alarmId) ?: return@forEachIndexed
            if (q.occurrence == alarm.skippedOccurrence || q.occurrence == alarm.lastCompletedOccurrence) return@forEachIndexed
            val next = SessionMachine.start(alarm, q.occurrence, now.toInstant().toEpochMilli(), settings.mathSettings.first())
                .copy(queued = done.queued.drop(index + 1))
            sessions.set(next)
            return
        }
    }

    private suspend fun armFresh(alarm: Alarm, now: ZonedDateTime): ZonedDateTime? {
        val action = if (alarm.enabled) {
            val next = AlarmTimeCalculator.nextOccurrence(alarm, now)
            SyncAction.Arm(alarm.id, next.at, next.key)
        } else {
            SyncAction.Disarm(alarm.id)
        }
        apply(action, now)
        return (action as? SyncAction.Arm)?.at
    }

    private suspend fun apply(action: SyncAction, now: ZonedDateTime, afterReboot: Boolean = false) {
        when (action) {
            is SyncAction.Arm -> {
                scheduler.arm(action.alarmId, action.at, action.occurrence)
                val millis = action.at.toInstant().toEpochMilli()
                var previousOccurrence: String? = null
                val alarm = repo.update(action.alarmId) {
                    previousOccurrence = it.occurrence
                    if (it.armedAt == millis && it.occurrence == action.occurrence) it
                    else it.copy(armedAt = millis, occurrence = action.occurrence)
                } ?: return
                // A prompt still on screen for the old occurrence (e.g. the time was edited) is withdrawn.
                if (previousOccurrence != null && previousOccurrence != action.occurrence) {
                    AlarmNotifications.cancelPreAlarm(appContext, action.alarmId)
                }
                armPreAlarm(alarm, action, now, afterReboot, occurrenceChanged = previousOccurrence != action.occurrence)
            }
            is SyncAction.Disarm -> {
                scheduler.cancel(action.alarmId)
                scheduler.cancelPreAlarm(action.alarmId)
                AlarmNotifications.cancelPreAlarm(appContext, action.alarmId)
                repo.update(action.alarmId) {
                    if (it.armedAt == null && it.occurrence == null) it else it.copy(armedAt = null, occurrence = null)
                }
            }
            is SyncAction.DisableMissed -> {
                scheduler.cancel(action.alarmId)
                scheduler.cancelPreAlarm(action.alarmId)
                AlarmNotifications.cancelPreAlarm(appContext, action.alarmId)
                repo.update(action.alarmId) { it.copy(enabled = false, armedAt = null, occurrence = null) }
            }
        }
    }

    /**
     * The pre-alarm belongs to one occurrence and only if its time is still ahead. After a
     * reboot (which clears notifications), a prompt that was due but unanswered is shown again,
     * as long as the alarm itself is still ahead.
     */
    private fun armPreAlarm(
        alarm: Alarm,
        arm: SyncAction.Arm,
        now: ZonedDateTime,
        afterReboot: Boolean,
        occurrenceChanged: Boolean,
    ) {
        val occurrenceAt = AlarmTimeCalculator.occurrenceTime(arm.occurrence, now.zone) ?: arm.at
        val preAt = AlarmTimeCalculator.preAlarmTime(alarm, occurrenceAt)
        val answered = arm.occurrence == alarm.confirmedOccurrence || arm.occurrence == alarm.skippedOccurrence
        when {
            preAt == null || answered -> scheduler.cancelPreAlarm(alarm.id)
            preAt.isAfter(now) -> {
                scheduler.cancelPreAlarm(alarm.id, repost = true)
                scheduler.armPreAlarm(alarm.id, preAt, arm.occurrence)
            }
            afterReboot && occurrenceAt.isAfter(now.plusMinutes(1)) -> {
                scheduler.cancelPreAlarm(alarm.id, regular = true, repost = false)
                scheduler.armPreAlarm(alarm.id, now.plusNanos(AlarmPlanner.CATCH_UP_DELAY_MS * 1_000_000), arm.occurrence, repost = true)
            }
            // Prompt time already passed. A pending post-reboot prompt for this same occurrence is
            // kept (the second boot broadcast must not cancel it); anything else is removed.
            else -> scheduler.cancelPreAlarm(alarm.id, regular = true, repost = occurrenceChanged)
        }
    }

    private companion object {
        /** Process-wide: receivers, the service and the UI each create their own controller. */
        val lock = Mutex()
    }
}
