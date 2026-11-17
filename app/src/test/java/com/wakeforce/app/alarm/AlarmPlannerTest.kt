package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmPlannerTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, s, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val weekdays = DayOfWeek.entries.take(5).toSet()

    /** Expected Arm action; [occurrence] is the nominal occurrence (differs from [t] only for catch-ups). */
    private fun arm(id: Int, t: ZonedDateTime, occurrence: ZonedDateTime = t) =
        SyncAction.Arm(id, t, occurrence.toLocalDateTime().toString())

    // --- Duplicate prevention ---------------------------------------------------------------

    @Test
    fun newAlarmsGetMonotonicIds() {
        val r1 = AlarmPlanner.upsert(emptyList(), Alarm(hour = 7, minute = 0), nextId = 1) as UpsertResult.Saved
        val r2 = AlarmPlanner.upsert(r1.alarms, Alarm(hour = 8, minute = 0), r1.nextId) as UpsertResult.Saved
        assertEquals(1, r1.saved.id)
        assertEquals(2, r2.saved.id)
        assertEquals(3, r2.nextId)
        assertEquals(2, r2.alarms.size)
    }

    @Test
    fun doubleSaveOfSameNewAlarmIsRejected() {
        val draft = Alarm(hour = 7, minute = 0, repeatDays = weekdays)
        val first = AlarmPlanner.upsert(emptyList(), draft, 1) as UpsertResult.Saved
        assertEquals(UpsertResult.Duplicate, AlarmPlanner.upsert(first.alarms, draft, first.nextId))
    }

    @Test
    fun duplicateCheckIgnoresUnrelatedFields() {
        val existing = listOf(Alarm(id = 1, hour = 6, minute = 30, enabled = false))
        val sameTimeDifferentTone = Alarm(hour = 6, minute = 30, soundUri = "content://x", vibrate = false)
        assertEquals(UpsertResult.Duplicate, AlarmPlanner.upsert(existing, sameTimeDifferentTone, 2))
    }

    @Test
    fun sameTimeDifferentDaysIsAllowed() {
        val existing = listOf(Alarm(id = 1, hour = 6, minute = 30, repeatDays = weekdays))
        val weekend = Alarm(hour = 6, minute = 30, repeatDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        assertTrue(AlarmPlanner.upsert(existing, weekend, 2) is UpsertResult.Saved)
    }

    @Test
    fun editingAnAlarmReplacesItInPlace() {
        val existing = listOf(Alarm(id = 1, hour = 6, minute = 30), Alarm(id = 2, hour = 9, minute = 0))
        val result = AlarmPlanner.upsert(existing, existing[0].copy(minute = 45), 3) as UpsertResult.Saved
        assertEquals(2, result.alarms.size)
        assertEquals(45, result.alarms.first { it.id == 1 }.minute)
        assertEquals(3, result.nextId)
    }

    // --- Sync / recovery ---------------------------------------------------------------------

    @Test
    fun disabledAlarmIsDisarmed() {
        val action = AlarmPlanner.planSync(Alarm(id = 1, hour = 7, minute = 0, enabled = false), at(6, 6, 0), false)
        assertEquals(SyncAction.Disarm(1), action)
    }

    @Test
    fun upcomingAlarmIsArmedAtNextOccurrence() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, armedAt = ms(at(6, 7, 0)))
        assertEquals(arm(1, at(6, 7, 0)), AlarmPlanner.planSync(alarm, at(6, 6, 0), false))
    }

    @Test
    fun oneTimeAlarmMissedWhileOffIsDisabledNotMovedToTomorrow() {
        // Armed for 07:00, phone was off until 09:00.
        val alarm = Alarm(id = 1, hour = 7, minute = 0, armedAt = ms(at(6, 7, 0)))
        assertEquals(SyncAction.DisableMissed(1), AlarmPlanner.planSync(alarm, at(6, 9, 0), catchUp = true))
        assertEquals(SyncAction.DisableMissed(1), AlarmPlanner.planSync(alarm, at(6, 9, 0), catchUp = false))
    }

    @Test
    fun alarmMissedDuringRebootRingsRightAway() {
        // Phone restarted for an update at 06:59 and was back at 07:03.
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = weekdays, armedAt = ms(at(6, 7, 0)))
        val now = at(6, 7, 3)
        val action = AlarmPlanner.planSync(alarm, now, catchUp = true)
        assertEquals(arm(1, now.plusSeconds(5), occurrence = at(6, 7, 0)), action)
    }

    @Test
    fun catchUpHappensOnlyOnTheFirstBootSync() {
        // Without catchUp (e.g. app start after force-stop), a missed repeating alarm moves on.
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = weekdays, armedAt = ms(at(6, 7, 0)))
        assertEquals(arm(1, at(9, 7, 0)), AlarmPlanner.planSync(alarm, at(6, 7, 3), catchUp = false))
    }

    @Test
    fun pendingCatchUpRingSurvivesTheSecondBootBroadcast() {
        // LOCKED_BOOT_COMPLETED armed a catch-up for 07:03:05; BOOT_COMPLETED arrives at 07:03:02.
        val catchUpAt = at(6, 7, 3, 5)
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = weekdays, armedAt = ms(catchUpAt),
            occurrence = "2026-11-06T07:00")
        // Kept at its catch-up time *and* still identified as the 07:00 occurrence.
        assertEquals(arm(1, catchUpAt, occurrence = at(6, 7, 0)), AlarmPlanner.planSync(alarm, at(6, 7, 3, 2), catchUp = false))
    }

    @Test
    fun missBeyondCatchUpWindowIsNotRung() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = weekdays, armedAt = ms(at(6, 7, 0)))
        assertEquals(arm(1, at(9, 7, 0)), AlarmPlanner.planSync(alarm, at(6, 8, 0), catchUp = true))
    }

    @Test
    fun timeZoneChangeKeepsLocalWallClockTime() {
        // Armed for 07:00 India time; user flies to London (UTC) while it's 22:00 India time.
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = DayOfWeek.entries.toSet(), armedAt = ms(at(7, 7, 0)))
        val london = ZoneId.of("Europe/London")
        val nowLondon = at(6, 22, 0).withZoneSameInstant(london)
        val action = AlarmPlanner.planSync(alarm, nowLondon, catchUp = false) as SyncAction.Arm
        assertEquals(7, action.at.hour)
        assertEquals(0, action.at.minute)
        assertEquals(london, action.at.zone)
    }

    @Test
    fun clockSetBackwardsRearmsForTheNewLocalTime() {
        // Armed 07:00 tomorrow; the user moves the clock back a day: alarm should ring "today".
        val alarm = Alarm(id = 1, hour = 7, minute = 0, armedAt = ms(at(7, 7, 0)))
        assertEquals(arm(1, at(6, 7, 0)), AlarmPlanner.planSync(alarm, at(6, 6, 0), false))
    }

    // --- After ringing -----------------------------------------------------------------------

    @Test
    fun repeatingAlarmIsRearmedForNextSelectedDay() {
        // Friday 07:00 rang -> next weekday is Monday.
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = weekdays)
        assertEquals(arm(1, at(9, 7, 0)), AlarmPlanner.afterFire(alarm, at(6, 7, 0, 1)))
    }

    @Test
    fun oneTimeAlarmIsSwitchedOffAfterRinging() {
        assertEquals(SyncAction.DisableMissed(1), AlarmPlanner.afterFire(Alarm(id = 1, hour = 7, minute = 0), at(6, 7, 0)))
    }

    @Test
    fun lateCatchUpRingStillMovesToNextDay() {
        val alarm = Alarm(id = 1, hour = 7, minute = 0, repeatDays = DayOfWeek.entries.toSet())
        assertEquals(arm(1, at(7, 7, 0)), AlarmPlanner.afterFire(alarm, at(6, 7, 25)))
    }

    @Test
    fun dstFallBackDoesNotRingTwice() {
        // 2026-11-01 01:30 happens twice in New York; after ringing at the first one, the
        // next occurrence must be the following day, not the repeated 01:30.
        val ny = ZoneId.of("America/New_York")
        val daily = Alarm(id = 1, hour = 1, minute = 30, repeatDays = DayOfWeek.entries.toSet())
        val firstRing = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ny) // earlier offset (EDT)
        val next = (AlarmPlanner.afterFire(daily, firstRing) as SyncAction.Arm).at
        assertEquals(ZonedDateTime.of(2026, 11, 2, 1, 30, 0, 0, ny), next)
    }
}
