package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** One scheduled ring: [key] is its local date-time identity, [at] the actual instant. */
data class Occurrence(val key: String, val at: ZonedDateTime)

/** Pure time math (no Android APIs) so it is unit-testable. Uses the device's local time zone. */
object AlarmTimeCalculator {

    /**
     * Next occurrence strictly after [now], skipping the one the user declined.
     * The key is the *nominal local* date-time ("2026-11-14T06:30"), so it stays the same
     * across time-zone changes and DST shifts; a 02:30 alarm on a spring-forward day keeps
     * key T02:30 while ringing at 03:30.
     */
    fun nextOccurrence(alarm: Alarm, now: ZonedDateTime): Occurrence {
        val time = LocalTime.of(alarm.hour, alarm.minute)
        val today = now.toLocalDate()
        for (offset in 0L..14L) {
            val date = today.plusDays(offset)
            if (alarm.isRepeating && date.dayOfWeek !in alarm.repeatDays) continue
            val key = LocalDateTime.of(date, time).toString()
            if (key == alarm.skippedOccurrence) continue
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (candidate.isAfter(now)) return Occurrence(key, candidate)
        }
        // Unreachable: within 15 days every weekday occurs at least twice.
        val date = today.plusDays(1)
        return Occurrence(LocalDateTime.of(date, time).toString(), ZonedDateTime.of(date, time, now.zone))
    }

    fun nextTrigger(alarm: Alarm, now: ZonedDateTime): ZonedDateTime = nextOccurrence(alarm, now).at

    /** Moment the pre-alarm confirmation should appear, or null if disabled. */
    fun preAlarmTime(alarm: Alarm, occurrenceAt: ZonedDateTime): ZonedDateTime? =
        if (alarm.preAlarmEnabled) occurrenceAt.minusMinutes(alarm.preAlarmMinutes.coerceAtMost(MAX_PRE_ALARM_MINUTES).toLong()) else null

    /** Instant of an occurrence key in [zone], or null if the key is malformed. */
    fun occurrenceTime(key: String, zone: ZoneId): ZonedDateTime? =
        runCatching { ZonedDateTime.of(LocalDateTime.parse(key), zone) }.getOrNull()

    fun nextAlarm(alarms: List<Alarm>, now: ZonedDateTime): Pair<Alarm, ZonedDateTime>? =
        alarms.asSequence()
            .filter { it.enabled }
            .map { it to nextTrigger(it, now) }
            .minByOrNull { it.second }

    /** "in 7 h 5 m", rounded up to the minute. */
    fun describeUntil(from: ZonedDateTime, to: ZonedDateTime): String {
        val seconds = Duration.between(from, to).seconds.coerceAtLeast(0)
        val totalMinutes = (seconds + 59) / 60
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> if (hours > 0) "in $days d $hours h" else "in $days d"
            hours > 0 -> if (minutes > 0) "in $hours h $minutes m" else "in $hours h"
            else -> "in ${minutes.coerceAtLeast(1)} m"
        }
    }

    const val MAX_PRE_ALARM_MINUTES = 120
}
