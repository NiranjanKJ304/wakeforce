package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class PreAlarmTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone)
    private val daily = DayOfWeek.entries.toSet()

    /** 2026-11-16 is a Monday. Alarm 06:30 daily, armed for Monday's occurrence. */
    private val monday0630 = "2026-11-16T06:30"
    private fun alarm(repeat: Set<DayOfWeek> = daily, minutes: Int = 30) = Alarm(
        id = 1, hour = 6, minute = 30, repeatDays = repeat, preAlarmEnabled = true, preAlarmMinutes = minutes,
        occurrence = monday0630, armedAt = at(16, 6, 30).toInstant().toEpochMilli(),
    )

    @Test
    fun confirmationTimeForEveryOption() {
        val expected = mapOf(10 to "06:20", 15 to "06:15", 20 to "06:10", 30 to "06:00", 45 to "05:45",
            60 to "05:30", 90 to "05:00", 120 to "04:30")
        assertEquals(expected.keys.toList(), Alarm.PRE_ALARM_OPTIONS)
        expected.forEach { (minutes, time) ->
            val pre = AlarmTimeCalculator.preAlarmTime(alarm(minutes = minutes), at(16, 6, 30))!!
            assertEquals(time, pre.toLocalTime().toString())
        }
    }

    @Test
    fun confirmationCrossesMidnight() {
        val a = alarm(minutes = 120).copy(hour = 1, minute = 0)
        assertEquals(at(15, 23, 0), AlarmTimeCalculator.preAlarmTime(a, at(16, 1, 0)))
    }

    @Test
    fun noConfirmationWhenDisabledAndNeverMoreThanTwoHours() {
        assertNull(AlarmTimeCalculator.preAlarmTime(alarm().copy(preAlarmEnabled = false), at(16, 6, 30)))
        assertEquals(at(16, 4, 30), AlarmTimeCalculator.preAlarmTime(alarm(minutes = 500), at(16, 6, 30)))
    }

    @Test
    fun yesKeepsTheOccurrenceUnchanged() {
        val plan = AlarmPlanner.planConfirm(alarm(), monday0630, keep = true, now = at(16, 6, 0), ringing = false)
        plan as ConfirmPlan.Confirmed
        assertEquals(monday0630, plan.alarm.confirmedOccurrence)
        // Still armed for the same moment.
        val sync = AlarmPlanner.planSync(plan.alarm, at(16, 6, 0), catchUp = false) as SyncAction.Arm
        assertEquals(at(16, 6, 30), sync.at)
        assertEquals(monday0630, sync.occurrence)
    }

    @Test
    fun noSkipsOnlyThisOccurrenceOfARepeatingAlarm() {
        val plan = AlarmPlanner.planConfirm(alarm(), monday0630, keep = false, now = at(16, 6, 0), ringing = false)
        plan as ConfirmPlan.Skipped
        assertTrue("alarm stays enabled", plan.alarm.enabled)
        assertEquals(monday0630, plan.alarm.skippedOccurrence)
        val next = plan.action as SyncAction.Arm
        assertEquals(at(17, 6, 30), next.at) // Tuesday still rings
        assertEquals("2026-11-17T06:30", next.occurrence)
    }

    @Test
    fun skippedOccurrenceIsNeverReArmedByLaterSyncs() {
        val skipped = (AlarmPlanner.planConfirm(alarm(), monday0630, false, at(16, 6, 0), false) as ConfirmPlan.Skipped).alarm
        // App start / time change / reboot at 06:10, still before the skipped 06:30.
        val sync = AlarmPlanner.planSync(skipped.copy(occurrence = "2026-11-17T06:30",
            armedAt = at(17, 6, 30).toInstant().toEpochMilli()), at(16, 6, 10), catchUp = true) as SyncAction.Arm
        assertEquals(at(17, 6, 30), sync.at)
        assertEquals(at(17, 6, 30), AlarmTimeCalculator.nextTrigger(skipped, at(16, 6, 10)))
    }

    @Test
    fun repeatingAlarmKeepsWeeklyScheduleAfterASkip() {
        val weekly = alarm(repeat = setOf(DayOfWeek.MONDAY))
        val plan = AlarmPlanner.planConfirm(weekly, monday0630, false, at(16, 6, 0), false) as ConfirmPlan.Skipped
        assertEquals(at(23, 6, 30), (plan.action as SyncAction.Arm).at)
        // And the week after that is unaffected too.
        val afterNext = AlarmPlanner.afterFire(plan.alarm, at(23, 6, 30)) as SyncAction.Arm
        assertEquals(at(30, 6, 30), afterNext.at)
    }

    @Test
    fun noOnAOneTimeAlarmSwitchesItOffButKeepsIt() {
        val once = alarm(repeat = emptySet())
        val plan = AlarmPlanner.planConfirm(once, monday0630, false, at(16, 6, 0), false) as ConfirmPlan.Skipped
        assertEquals(SyncAction.DisableMissed(1), plan.action)
        assertEquals(monday0630, plan.alarm.skippedOccurrence)
    }

    @Test
    fun ignoredConfirmationChangesNothing() {
        // No answer: the stored alarm is untouched, so the planner keeps the original ring.
        val sync = AlarmPlanner.planSync(alarm(), at(16, 6, 20), catchUp = false) as SyncAction.Arm
        assertEquals(at(16, 6, 30), sync.at)
        assertEquals(monday0630, sync.occurrence)
    }

    @Test
    fun staleAnswersAreIgnored() {
        val a = alarm()
        // Old prompt for an earlier occurrence.
        assertEquals(ConfirmPlan.Stale, AlarmPlanner.planConfirm(a, "2026-11-15T06:30", false, at(16, 6, 0), false))
        // A future occurrence that isn't the one armed now (e.g. a prompt crafted for tomorrow).
        assertEquals(ConfirmPlan.Stale, AlarmPlanner.planConfirm(a, "2026-11-17T06:30", false, at(16, 6, 0), false))
        // Answer arrives after the alarm time.
        assertEquals(ConfirmPlan.Stale, AlarmPlanner.planConfirm(a, monday0630, false, at(16, 6, 31), false))
        // The alarm is already ringing.
        assertEquals(ConfirmPlan.Stale, AlarmPlanner.planConfirm(a, monday0630, false, at(16, 6, 0), ringing = true))
        // Alarm switched off, or already skipped.
        assertEquals(ConfirmPlan.Stale, AlarmPlanner.planConfirm(a.copy(enabled = false), monday0630, true, at(16, 6, 0), false))
        assertEquals(ConfirmPlan.Stale, AlarmPlanner.planConfirm(a.copy(skippedOccurrence = monday0630), monday0630, false, at(16, 6, 0), false))
    }

    @Test
    fun occurrenceStatesAreDerivedCorrectly() {
        val a = alarm()
        assertEquals(SessionState.SCHEDULED, SessionMachine.occurrenceState(a, monday0630, null, at(16, 5, 0)))
        assertEquals(SessionState.PRE_ALARM_PENDING, SessionMachine.occurrenceState(a, monday0630, null, at(16, 6, 5)))
        assertEquals(SessionState.PRE_ALARM_CONFIRMED,
            SessionMachine.occurrenceState(a.copy(confirmedOccurrence = monday0630), monday0630, null, at(16, 6, 5)))
        assertEquals(SessionState.PRE_ALARM_SKIPPED,
            SessionMachine.occurrenceState(a.copy(skippedOccurrence = monday0630), monday0630, null, at(16, 6, 5)))
        assertEquals(SessionState.COMPLETED,
            SessionMachine.occurrenceState(a.copy(lastCompletedOccurrence = monday0630), monday0630, null, at(16, 7, 0)))
    }

    @Test
    fun skippedKeyIsLocalTimeSoItSurvivesTimeZoneChanges() {
        val skipped = alarm().copy(skippedOccurrence = monday0630)
        val london = ZoneId.of("Europe/London")
        // Sunday night in London: Monday 06:30 London is still the skipped local occurrence.
        val next = AlarmTimeCalculator.nextOccurrence(skipped, ZonedDateTime.of(2026, 11, 15, 22, 0, 0, 0, london))
        assertEquals("2026-11-17T06:30", next.key)
    }
}
