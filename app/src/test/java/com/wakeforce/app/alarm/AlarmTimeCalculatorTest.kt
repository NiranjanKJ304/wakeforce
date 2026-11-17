package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmTimeCalculatorTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone)

    // 2026-11-06 is a Friday.
    private val fridayMorning = at(2026, 11, 6, 6, 0)

    @Test
    fun oneTimeAlarmLaterToday() {
        val next = AlarmTimeCalculator.nextTrigger(Alarm(hour = 7, minute = 30), fridayMorning)
        assertEquals(at(2026, 11, 6, 7, 30), next)
    }

    @Test
    fun oneTimeAlarmAlreadyPassedRollsToTomorrow() {
        val next = AlarmTimeCalculator.nextTrigger(Alarm(hour = 5, minute = 0), fridayMorning)
        assertEquals(at(2026, 11, 7, 5, 0), next)
    }

    @Test
    fun alarmAtExactlyNowIsNotRearmedForNow() {
        val next = AlarmTimeCalculator.nextTrigger(Alarm(hour = 6, minute = 0), fridayMorning)
        assertEquals(at(2026, 11, 7, 6, 0), next)
    }

    @Test
    fun repeatingAlarmSkipsUnselectedDays() {
        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        // Friday 08:00 has passed -> next is Monday.
        val next = AlarmTimeCalculator.nextTrigger(
            Alarm(hour = 8, minute = 0, repeatDays = weekdays),
            at(2026, 11, 6, 9, 0),
        )
        assertEquals(at(2026, 11, 9, 8, 0), next)
    }

    @Test
    fun repeatingSingleDayCanBeAWeekAway() {
        val next = AlarmTimeCalculator.nextTrigger(
            Alarm(hour = 5, minute = 0, repeatDays = setOf(DayOfWeek.FRIDAY)),
            fridayMorning,
        )
        assertEquals(at(2026, 11, 13, 5, 0), next)
    }

    @Test
    fun rescheduleAfterFiringMovesToNextOccurrence() {
        // Service re-arms with now + 1 minute while the 06:00 daily alarm is ringing.
        val daily = Alarm(hour = 6, minute = 0, repeatDays = DayOfWeek.entries.toSet())
        val next = AlarmTimeCalculator.nextTrigger(daily, at(2026, 11, 6, 6, 0, 1).plusMinutes(1))
        assertEquals(at(2026, 11, 7, 6, 0), next)
    }

    @Test
    fun dstGapShiftsForward() {
        val ny = ZoneId.of("America/New_York")
        // 2026-03-08 02:30 does not exist in New York (clocks jump 02:00 -> 03:00).
        val next = AlarmTimeCalculator.nextTrigger(
            Alarm(hour = 2, minute = 30),
            ZonedDateTime.of(2026, 3, 8, 1, 0, 0, 0, ny),
        )
        assertEquals(ZonedDateTime.of(2026, 3, 8, 3, 30, 0, 0, ny), next)
    }

    @Test
    fun nextAlarmIgnoresDisabled() {
        val alarms = listOf(
            Alarm(id = 1, hour = 6, minute = 30, enabled = false),
            Alarm(id = 2, hour = 7, minute = 0),
            Alarm(id = 3, hour = 9, minute = 0),
        )
        assertEquals(2, AlarmTimeCalculator.nextAlarm(alarms, fridayMorning)?.first?.id)
        assertNull(AlarmTimeCalculator.nextAlarm(alarms.map { it.copy(enabled = false) }, fridayMorning))
    }

    @Test
    fun describeUntilRoundsUp() {
        assertEquals("in 1 h 30 m", AlarmTimeCalculator.describeUntil(fridayMorning, at(2026, 11, 6, 7, 30)))
        assertEquals("in 1 m", AlarmTimeCalculator.describeUntil(fridayMorning, fridayMorning.plusSeconds(20)))
        assertEquals("in 1 d 2 h", AlarmTimeCalculator.describeUntil(fridayMorning, at(2026, 11, 7, 8, 0)))
    }
}
