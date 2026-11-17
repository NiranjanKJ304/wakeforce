package com.wakeforce.app.data

import java.time.DayOfWeek

/** How a ringing alarm is stopped. Saved per alarm. */
enum class ChallengeMode(val label: String) {
    /** Scan the alarm's registered QR code(s). */
    QR("QR scan"),
    /** Solve the configured addition questions. */
    MATH("Math addition"),
    /** Optional stricter configuration: math first, then the QR code(s). */
    MATH_AND_QR("Math + QR"),
    ;

    val needsQr: Boolean get() = this != MATH
    val needsMath: Boolean get() = this != QR
}

/** A QR code registered to one alarm. [payload] is the code's content; never log it. */
data class RegisteredQr(
    val id: String,
    val label: String,
    val payload: String,
)

data class Alarm(
    val id: Int = NEW_ID,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    /** Empty = rings once at the next occurrence of [hour]:[minute]. */
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val vibrate: Boolean = true,
    /** Alarm stream volume while ringing, as a percentage of the device maximum. */
    val volumePercent: Int = 100,
    /** Alarm tone content URI; null = system default alarm sound. */
    val soundUri: String? = null,
    /** The challenge that stops this alarm. */
    val mode: ChallengeMode = ChallengeMode.MATH,
    /** 1-3 codes, all of which must be scanned in a QR mode. Kept (unused) in MATH mode. */
    val qrCodes: List<RegisteredQr> = emptyList(),
    val preAlarmEnabled: Boolean = false,
    /** Minutes before the alarm that the "still need it?" confirmation appears. */
    val preAlarmMinutes: Int = 30,
    val tempAccessEnabled: Boolean = true,
    /** Alarm volume during a temporary-access window, % of the ringing volume (0 = muted). */
    val tempAccessVolumePercent: Int = 0,

    // --- Scheduler bookkeeping (not user configuration) ---------------------------------------
    /** Epoch millis registered with AlarmManager for the main ring (may differ from [occurrence] for a catch-up). */
    val armedAt: Long? = null,
    /** Local date-time key (e.g. "2026-11-14T06:30") of the occurrence currently armed. */
    val occurrence: String? = null,
    /** Occurrence the user skipped from the pre-alarm screen; it never rings. */
    val skippedOccurrence: String? = null,
    /** Occurrence the user confirmed ("YES, I need it") from the pre-alarm screen. */
    val confirmedOccurrence: String? = null,
    /** Occurrence whose challenge was last completed; a repeated broadcast for it is ignored. */
    val lastCompletedOccurrence: String? = null,
) {
    val isRepeating: Boolean get() = repeatDays.isNotEmpty()

    /** True when [other] would ring at exactly the same moments (used to block duplicates). */
    fun sameScheduleAs(other: Alarm): Boolean =
        hour == other.hour && minute == other.minute && repeatDays == other.repeatDays

    /** True when the selected challenge can actually be completed (QR modes need 1-3 codes). */
    val isChallengeReady: Boolean get() = !mode.needsQr || qrCodes.size in MIN_QR_CODES..MAX_QR_CODES

    companion object {
        const val NEW_ID = 0
        const val MIN_QR_CODES = 1
        const val MAX_QR_CODES = 3
        val PRE_ALARM_OPTIONS = listOf(10, 15, 20, 30, 45, 60, 90, 120)
        const val MAX_TEMP_ACCESS_VOLUME = 50
    }
}
