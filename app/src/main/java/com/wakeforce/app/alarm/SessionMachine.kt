package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.data.RegisteredQr
import com.wakeforce.app.math.MathSession
import com.wakeforce.app.math.MathSettings
import com.wakeforce.app.qr.QrRegistry
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Lifecycle of one alarm occurrence. The first four states describe an occurrence before it
 * rings (derived from the stored alarm); the rest describe a persisted ringing [AlarmSession].
 */
enum class SessionState {
    SCHEDULED,
    PRE_ALARM_PENDING,
    PRE_ALARM_CONFIRMED,
    PRE_ALARM_SKIPPED,
    RINGING_MATH,
    RINGING_QR,
    TEMPORARY_ACCESS,
    COMPLETED,
    MISSED,
    RECOVERING,
}

/** Settings captured when ringing starts, so editing the alarm mid-session changes nothing. */
data class SessionSound(
    val volumePercent: Int,
    val soundUri: String?,
    val vibrate: Boolean,
    val tempAccessEnabled: Boolean,
    val tempAccessVolumePercent: Int,
)

data class QueuedFire(val alarmId: Int, val occurrence: String)

/** A ringing alarm occurrence. Persisted, so it survives process death and reboot. */
data class AlarmSession(
    val alarmId: Int,
    val occurrence: String,
    val state: SessionState,
    val startedAt: Long,
    /** Effective challenge for this ring (snapshot; editing the alarm later changes nothing). */
    val mode: ChallengeMode,
    val math: MathSession,
    /** Snapshot of the alarm's codes at ring time (empty in MATH mode). */
    val qrCodes: List<RegisteredQr>,
    val scannedQrIds: Set<String> = emptySet(),
    val accessEndsAt: Long? = null,
    val accessWindowsUsed: Int = 0,
    /** State to return to when a temporary-access window ends. */
    val resumeState: SessionState? = null,
    val sound: SessionSound,
    /** Other alarms that fired while this one rang; each gets its own session afterwards. */
    val queued: List<QueuedFire> = emptyList(),
    val completedAt: Long? = null,
) {
    val occurrenceId: String get() = occurrenceId(alarmId, occurrence)
    val isActive: Boolean get() = state in SessionMachine.ACTIVE
    val accessWindowsLeft: Int get() = (SessionMachine.MAX_ACCESS_WINDOWS - accessWindowsUsed).coerceAtLeast(0)

    companion object {
        fun occurrenceId(alarmId: Int, occurrence: String) = "$alarmId@$occurrence"
    }
}

data class WakeStats(
    val completedCount: Int = 0,
    val lastDismissedAt: Long? = null,
    val lastDurationMs: Long? = null,
)

enum class MathResult { NOT_ALLOWED, REJECTED, CORRECT, MATH_COMPLETE, SESSION_COMPLETE }
enum class QrResult { NOT_ALLOWED, WRONG_CODE, ALREADY_SCANNED, ACCEPTED, SESSION_COMPLETE }
enum class AccessResult { NOT_ALLOWED, DISABLED, ALREADY_ACTIVE, LIMIT_REACHED, STARTED, ENDED, TOO_EARLY }

data class Step<T>(val session: AlarmSession, val result: T)

/**
 * Pure, unit-tested rules for ringing sessions. Every transition is validated against
 * [canTransition]: e.g. a COMPLETED session can never ring again and temporary access can
 * never complete a session. Dismissal requires the alarm's selected challenge:
 *  - QR: every registered code scanned once;
 *  - MATH: every question answered correctly;
 *  - MATH_AND_QR: all questions, then every code.
 */
object SessionMachine {
    const val TEMP_ACCESS_MS = 5 * 60 * 1000L
    const val MAX_ACCESS_WINDOWS = 2

    /** A session interrupted by a power-off is resumed after reboot only within this age. */
    const val RECOVERY_MAX_AGE_MS = 2 * 60 * 60 * 1000L

    val ACTIVE = setOf(SessionState.RINGING_MATH, SessionState.RINGING_QR, SessionState.TEMPORARY_ACCESS, SessionState.RECOVERING)

    private val transitions: Map<SessionState, Set<SessionState>> = mapOf(
        SessionState.SCHEDULED to setOf(SessionState.PRE_ALARM_PENDING, SessionState.RINGING_MATH, SessionState.MISSED),
        SessionState.PRE_ALARM_PENDING to setOf(
            SessionState.PRE_ALARM_CONFIRMED, SessionState.PRE_ALARM_SKIPPED, SessionState.RINGING_MATH, SessionState.MISSED,
        ),
        SessionState.PRE_ALARM_CONFIRMED to setOf(SessionState.RINGING_MATH, SessionState.MISSED),
        SessionState.PRE_ALARM_SKIPPED to emptySet(),
        SessionState.RINGING_MATH to setOf(
            SessionState.RINGING_QR, SessionState.COMPLETED, SessionState.TEMPORARY_ACCESS, SessionState.RECOVERING,
        ),
        SessionState.RINGING_QR to setOf(SessionState.COMPLETED, SessionState.TEMPORARY_ACCESS, SessionState.RECOVERING),
        SessionState.TEMPORARY_ACCESS to setOf(SessionState.RINGING_MATH, SessionState.RINGING_QR, SessionState.RECOVERING),
        SessionState.RECOVERING to setOf(
            SessionState.RINGING_MATH, SessionState.RINGING_QR, SessionState.TEMPORARY_ACCESS, SessionState.MISSED,
        ),
        SessionState.COMPLETED to emptySet(),
        SessionState.MISSED to emptySet(),
    )

    fun canTransition(from: SessionState, to: SessionState): Boolean = to in transitions[from].orEmpty()

    private fun AlarmSession.moveTo(to: SessionState): AlarmSession {
        check(canTransition(state, to)) { "Illegal session transition $state -> $to" }
        return copy(state = to)
    }

    /**
     * A QR mode without any registered code (only possible with old or corrupt data; the
     * editor requires 1-3) falls back to MATH so the alarm can always be stopped.
     */
    fun effectiveMode(alarm: Alarm): ChallengeMode =
        if (alarm.mode.needsQr && alarm.qrCodes.isEmpty()) ChallengeMode.MATH else alarm.mode

    fun start(
        alarm: Alarm,
        occurrence: String,
        nowMs: Long,
        mathSettings: MathSettings,
        random: Random = Random.Default,
    ): AlarmSession {
        val mode = effectiveMode(alarm)
        return AlarmSession(
            alarmId = alarm.id,
            occurrence = occurrence,
            state = if (mode.needsMath) SessionState.RINGING_MATH else SessionState.RINGING_QR,
            startedAt = nowMs,
            mode = mode,
            math = if (mode.needsMath) MathSession.start(mathSettings, random) else MathSession(mathSettings.sanitized(), emptyList()),
            qrCodes = if (mode.needsQr) alarm.qrCodes else emptyList(),
            sound = SessionSound(
                alarm.volumePercent, alarm.soundUri, alarm.vibrate, alarm.tempAccessEnabled, alarm.tempAccessVolumePercent,
            ),
        )
    }

    /** Step 1: math. Advances to the QR step only after every question is answered correctly. */
    fun submitMath(session: AlarmSession, input: String, nowMs: Long, random: Random = Random.Default): Step<MathResult> {
        if (session.state != SessionState.RINGING_MATH) return Step(session, MathResult.NOT_ALLOWED)
        val math = session.math.submit(input, random)
        val updated = session.copy(math = math)
        return when {
            !math.solved && math.index > session.math.index -> Step(updated, MathResult.CORRECT)
            !math.solved -> Step(updated, MathResult.REJECTED)
            // MATH mode (no codes in the snapshot): the questions are the whole challenge.
            session.qrCodes.isEmpty() ->
                Step(updated.moveTo(SessionState.COMPLETED).copy(completedAt = nowMs), MathResult.SESSION_COMPLETE)
            else -> Step(updated.moveTo(SessionState.RINGING_QR), MathResult.MATH_COMPLETE)
        }
    }

    /** Step 2: every registered code, each counted once. Only accepted after math is done. */
    fun submitQr(session: AlarmSession, scanned: String, nowMs: Long): Step<QrResult> {
        if (session.state != SessionState.RINGING_QR) return Step(session, QrResult.NOT_ALLOWED)
        val code = QrRegistry.match(session.qrCodes, scanned) ?: return Step(session, QrResult.WRONG_CODE)
        if (code.id in session.scannedQrIds) return Step(session, QrResult.ALREADY_SCANNED)
        val updated = session.copy(scannedQrIds = session.scannedQrIds + code.id)
        return if (session.qrCodes.all { it.id in updated.scannedQrIds }) {
            Step(updated.moveTo(SessionState.COMPLETED).copy(completedAt = nowMs), QrResult.SESSION_COMPLETE)
        } else {
            Step(updated, QrResult.ACCEPTED)
        }
    }

    fun startAccess(session: AlarmSession, nowMs: Long): Step<AccessResult> {
        val result = when {
            session.state == SessionState.TEMPORARY_ACCESS || session.accessEndsAt != null -> AccessResult.ALREADY_ACTIVE
            session.state != SessionState.RINGING_MATH && session.state != SessionState.RINGING_QR -> AccessResult.NOT_ALLOWED
            !session.sound.tempAccessEnabled -> AccessResult.DISABLED
            session.accessWindowsUsed >= MAX_ACCESS_WINDOWS -> AccessResult.LIMIT_REACHED
            else -> null
        }
        if (result != null) return Step(session, result)
        return Step(
            session.moveTo(SessionState.TEMPORARY_ACCESS).copy(
                resumeState = session.state,
                accessEndsAt = nowMs + TEMP_ACCESS_MS,
                accessWindowsUsed = session.accessWindowsUsed + 1,
            ),
            AccessResult.STARTED,
        )
    }

    /** Ends the window when its time is up, or earlier if the user returns to the challenge. */
    fun endAccess(session: AlarmSession, nowMs: Long, userReturned: Boolean): Step<AccessResult> {
        if (session.state != SessionState.TEMPORARY_ACCESS) return Step(session, AccessResult.NOT_ALLOWED)
        val endsAt = session.accessEndsAt ?: nowMs
        if (!userReturned && nowMs < endsAt - END_TOLERANCE_MS) return Step(session, AccessResult.TOO_EARLY)
        val back = session.resumeState?.takeIf { it == SessionState.RINGING_MATH || it == SessionState.RINGING_QR }
            ?: if (!session.mode.needsMath || session.math.solved) SessionState.RINGING_QR else SessionState.RINGING_MATH
        return Step(session.moveTo(back).copy(accessEndsAt = null, resumeState = null), AccessResult.ENDED)
    }

    /** True if an interrupted session should resume after a reboot; false = mark it MISSED. */
    fun shouldRecover(session: AlarmSession, nowMs: Long): Boolean =
        session.isActive && nowMs - session.startedAt <= RECOVERY_MAX_AGE_MS

    fun markMissed(session: AlarmSession): AlarmSession =
        if (session.isActive) session.copy(state = SessionState.MISSED, accessEndsAt = null) else session

    /** Lifecycle state of a not-yet-ringing occurrence, derived from stored data. */
    fun occurrenceState(alarm: Alarm, occurrence: String, session: AlarmSession?, now: ZonedDateTime): SessionState {
        if (session != null && session.alarmId == alarm.id && session.occurrence == occurrence) return session.state
        return when (occurrence) {
            alarm.lastCompletedOccurrence -> SessionState.COMPLETED
            alarm.skippedOccurrence -> SessionState.PRE_ALARM_SKIPPED
            alarm.confirmedOccurrence -> SessionState.PRE_ALARM_CONFIRMED
            else -> {
                val at = AlarmTimeCalculator.occurrenceTime(occurrence, now.zone)
                if (alarm.preAlarmEnabled && at != null && !now.isBefore(at.minusMinutes(alarm.preAlarmMinutes.toLong()))) {
                    SessionState.PRE_ALARM_PENDING
                } else SessionState.SCHEDULED
            }
        }
    }

    private const val END_TOLERANCE_MS = 1_000L
}
