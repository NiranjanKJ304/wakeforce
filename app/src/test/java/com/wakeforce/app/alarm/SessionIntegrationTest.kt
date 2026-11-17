package com.wakeforce.app.alarm

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.AlarmRepository
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.data.RegisteredQr
import com.wakeforce.app.data.SessionRepository
import com.wakeforce.app.data.WakeForceDataStore
import com.wakeforce.app.qr.QrCodes
import com.wakeforce.app.receiver.AlarmReceiver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 2.0 flows on the Robolectric Android framework: real AlarmManager registrations and real
 * DataStore persistence. Verifies logic and registrations, not physical-device delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionIntegrationTest {

    private lateinit var context: Context
    private lateinit var shadowAlarms: ShadowAlarmManager
    private lateinit var controller: AlarmController
    private lateinit var alarms: AlarmRepository
    private lateinit var sessions: SessionRepository

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, s, 0, zone)
    private val sunday10pm = at(15, 22, 0) // 2026-11-15 is a Sunday
    private val monday = "2026-11-16T06:30"
    private val codes = List(3) { RegisteredQr("c$it", "Code ${it + 1}", QrCodes.newPayload()) }
    private val daily = DayOfWeek.entries.toSet()

    @Before
    fun setUp() {
        WakeForceDataStore.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowAlarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        controller = AlarmController(context)
        alarms = AlarmRepository(context)
        sessions = SessionRepository(context)
    }

    private fun registrations(action: String) =
        shadowAlarms.scheduledAlarms.filter { shadowOf(it.operation).savedIntent.action == action }

    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    private fun save(alarm: Alarm, now: ZonedDateTime = sunday10pm): Alarm = runBlocking {
        (controller.save(alarm, now) as AlarmController.SaveOutcome.Saved).alarm
    }

    /** These scenarios exercise the full two-step challenge, i.e. the explicit MATH_AND_QR mode. */
    private fun preAlarmDaily() = save(Alarm(hour = 6, minute = 30, repeatDays = daily, mode = ChallengeMode.MATH_AND_QR,
        qrCodes = codes, preAlarmEnabled = true, preAlarmMinutes = 30))

    /** Answers every question of *this* occurrence; bounded so a logic error fails instead of hanging. */
    private suspend fun solveMath(occurrenceId: String, now: ZonedDateTime) {
        repeat(50) {
            val s = sessions.get()!!
            if (s.occurrenceId != occurrenceId || s.state != SessionState.RINGING_MATH) return
            controller.submitMath(occurrenceId, s.math.current!!.answer.toString(), now)
        }
        error("math for $occurrenceId was not completed")
    }

    private suspend fun scanAll(occurrenceId: String, list: List<RegisteredQr>, now: ZonedDateTime) =
        list.forEach { controller.submitQr(occurrenceId, it.payload, now) }

    // --- Pre-alarm ---------------------------------------------------------------------------

    @Test
    fun preAlarmIsRegisteredBeforeTheMainAlarmForThatOccurrence() {
        preAlarmDaily()
        val pre = registrations(AlarmReceiver.ACTION_PRE_ALARM).single()
        assertEquals(ms(at(16, 6, 0)), pre.triggerAtMs)
        assertEquals(monday, shadowOf(pre.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_OCCURRENCE))
        val main = registrations(AlarmReceiver.ACTION_FIRE).single()
        assertEquals(ms(at(16, 6, 30)), main.triggerAtMs)
        assertEquals(monday, shadowOf(main.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_OCCURRENCE))
    }

    @Test
    fun yesKeepsTheMainAlarmScheduled() = runBlocking {
        val alarm = preAlarmDaily()
        assertTrue(controller.confirmPreAlarm(alarm.id, monday, keep = true, now = at(16, 6, 0)) is ConfirmPlan.Confirmed)
        assertEquals(ms(at(16, 6, 30)), registrations(AlarmReceiver.ACTION_FIRE).single().triggerAtMs)
        assertTrue(registrations(AlarmReceiver.ACTION_PRE_ALARM).isEmpty())
        // A re-sync (e.g. time-zone broadcast) must not show the prompt again for this occurrence.
        controller.syncAll(at(16, 6, 5))
        assertTrue(registrations(AlarmReceiver.ACTION_PRE_ALARM).isEmpty())
        assertEquals(ms(at(16, 6, 30)), registrations(AlarmReceiver.ACTION_FIRE).single().triggerAtMs)
    }

    @Test
    fun noSkipsOnlyThisOccurrenceAndKeepsTheAlarm() = runBlocking {
        val alarm = preAlarmDaily()
        controller.confirmPreAlarm(alarm.id, monday, keep = false, now = at(16, 6, 0))
        val stored = alarms.get(alarm.id)!!
        assertTrue(stored.enabled)
        assertEquals(monday, stored.skippedOccurrence)
        assertEquals(ms(at(17, 6, 30)), registrations(AlarmReceiver.ACTION_FIRE).single().triggerAtMs)
        assertEquals(ms(at(17, 6, 0)), registrations(AlarmReceiver.ACTION_PRE_ALARM).single().triggerAtMs)
        // A late broadcast for the skipped occurrence cannot start a session.
        assertEquals(AlarmController.FireDecision.Ignore, controller.startSession(alarm.id, monday, at(16, 6, 30)))
        assertNull(sessions.get())
    }

    @Test
    fun ignoredPreAlarmStillRings() = runBlocking {
        val alarm = preAlarmDaily()
        // No answer at all: the main ring fires and starts the challenge.
        val decision = controller.startSession(alarm.id, monday, at(16, 6, 30))
        assertTrue(decision is AlarmController.FireDecision.Ring)
        assertEquals(SessionState.RINGING_MATH, sessions.get()!!.state)
    }

    @Test
    fun answersForAnotherOccurrenceAreIgnored() = runBlocking {
        val alarm = preAlarmDaily()
        assertEquals(ConfirmPlan.Stale, controller.confirmPreAlarm(alarm.id, "2026-11-15T06:30", false, at(16, 6, 0)))
        assertEquals(ConfirmPlan.Stale, controller.confirmPreAlarm(alarm.id, "2026-11-17T06:30", false, at(16, 6, 0)))
        assertNull(alarms.get(alarm.id)!!.skippedOccurrence)
        assertEquals(ms(at(16, 6, 30)), registrations(AlarmReceiver.ACTION_FIRE).single().triggerAtMs)
        assertNull(controller.preAlarmToShow(alarm.id, "2026-11-15T06:30", at(16, 6, 0)))
    }

    // --- Ringing sessions --------------------------------------------------------------------

    @Test
    fun mathThenAllQrCodesCompletesAndRecordsStats() = runBlocking {
        val alarm = preAlarmDaily()
        val ring = at(16, 6, 30)
        controller.startSession(alarm.id, monday, ring)
        val id = sessions.get()!!.occurrenceId
        assertEquals(QrResult.NOT_ALLOWED, controller.submitQr(id, codes[0].payload, ring)) // math first
        solveMath(id, ring)
        assertEquals(SessionState.RINGING_QR, sessions.get()!!.state)
        scanAll(id, codes, ring.plusMinutes(4))
        assertEquals(SessionState.COMPLETED, sessions.get()!!.state)
        assertEquals(monday, alarms.get(alarm.id)!!.lastCompletedOccurrence)
        val stats = sessions.stats.first()
        assertEquals(1, stats.completedCount)
        assertEquals(4 * 60_000L, stats.lastDurationMs)
    }

    @Test
    fun repeatedBroadcastsNeverCreateASecondSession() = runBlocking {
        val alarm = preAlarmDaily()
        val first = controller.startSession(alarm.id, monday, at(16, 6, 30)) as AlarmController.FireDecision.Ring
        val id = first.session.occurrenceId
        solveMath(id, at(16, 6, 31))
        val again = controller.startSession(alarm.id, monday, at(16, 6, 32)) as AlarmController.FireDecision.Ring
        assertEquals(SessionState.RINGING_QR, again.session.state) // resumed with progress
        scanAll(id, codes, at(16, 6, 33))
        // After completion a late duplicate is ignored: a completed alarm cannot restart.
        assertEquals(AlarmController.FireDecision.Ignore, controller.startSession(alarm.id, monday, at(16, 6, 34)))
    }

    @Test
    fun progressIsPersistedNotHeldInMemory() = runBlocking {
        val alarm = preAlarmDaily()
        controller.startSession(alarm.id, monday, at(16, 6, 30))
        val id = sessions.get()!!.occurrenceId
        solveMath(id, at(16, 6, 31))
        controller.submitQr(id, codes[1].payload, at(16, 6, 32))
        // Fresh objects, as after the process is killed and the service is redelivered.
        val revived = AlarmController(context)
        val decision = revived.startSession(alarm.id, monday, at(16, 6, 40)) as AlarmController.FireDecision.Ring
        assertEquals(SessionState.RINGING_QR, decision.session.state)
        assertEquals(setOf("c1"), decision.session.scannedQrIds)
    }

    @Test
    fun scansCannotCompleteAnUnrelatedOccurrence() = runBlocking {
        val alarm = preAlarmDaily()
        controller.startSession(alarm.id, monday, at(16, 6, 30))
        val wrongId = AlarmSession.occurrenceId(alarm.id, "2026-11-17T06:30")
        assertEquals(QrResult.NOT_ALLOWED, controller.submitQr(wrongId, codes[0].payload, at(16, 6, 31)))
        assertEquals(MathResult.NOT_ALLOWED, controller.submitMath(wrongId, "1", at(16, 6, 31)))
    }

    @Test
    fun editingOrDeletingTheRingingAlarmDoesNotWeakenTheChallenge() = runBlocking {
        val alarm = preAlarmDaily()
        controller.startSession(alarm.id, monday, at(16, 6, 30))
        val id = sessions.get()!!.occurrenceId
        // During temporary access the user edits the alarm to two brand-new codes, then deletes it.
        val newCodes = List(2) { RegisteredQr("n$it", "New", QrCodes.newPayload()) }
        controller.save(alarms.get(alarm.id)!!.copy(qrCodes = newCodes, tempAccessEnabled = false), at(16, 6, 31))
        controller.delete(alarm.id)
        val s = sessions.get()!!
        assertTrue(s.isActive)
        assertEquals(codes, s.qrCodes) // snapshot from ring time
        assertTrue(s.sound.tempAccessEnabled)
        solveMath(id, at(16, 6, 32))
        scanAll(id, newCodes, at(16, 6, 33))
        assertEquals(SessionState.RINGING_QR, sessions.get()!!.state)
        scanAll(id, codes, at(16, 6, 34))
        assertEquals(SessionState.COMPLETED, sessions.get()!!.state)
    }

    @Test
    fun secondAlarmQueuesAndThenRingsWithItsOwnCodes() = runBlocking {
        val first = preAlarmDaily()
        val otherCodes = List(2) { RegisteredQr("o$it", "Other", QrCodes.newPayload()) }
        val second = save(Alarm(hour = 6, minute = 30, repeatDays = setOf(DayOfWeek.MONDAY),
            mode = ChallengeMode.MATH_AND_QR, qrCodes = otherCodes))
        controller.startSession(first.id, monday, at(16, 6, 30))
        assertEquals(AlarmController.FireDecision.Queued, controller.startSession(second.id, monday, at(16, 6, 30)))
        assertEquals(AlarmController.FireDecision.Queued, controller.startSession(second.id, monday, at(16, 6, 30)))
        val id1 = sessions.get()!!.occurrenceId
        assertEquals(1, sessions.get()!!.queued.size) // queued once despite the repeat
        solveMath(id1, at(16, 6, 31))
        scanAll(id1, codes, at(16, 6, 32))
        val next = sessions.get()!!
        assertEquals(second.id, next.alarmId)
        assertEquals(SessionState.RINGING_MATH, next.state)
        assertEquals(otherCodes, next.qrCodes)
    }

    // --- Temporary access --------------------------------------------------------------------

    @Test
    fun temporaryAccessSchedulesOneCallbackAndPreservesProgress() = runBlocking {
        val alarm = preAlarmDaily()
        val ring = at(16, 6, 30)
        controller.startSession(alarm.id, monday, ring)
        val id = sessions.get()!!.occurrenceId
        solveMath(id, ring)
        controller.submitQr(id, codes[0].payload, ring)

        assertEquals(AccessResult.STARTED, controller.startTemporaryAccess(id, at(16, 6, 32)))
        val timer = registrations(AlarmReceiver.ACTION_ACCESS_END).single()
        assertEquals(ms(at(16, 6, 37)), timer.triggerAtMs)
        // Re-requesting (e.g. after reopening the app) neither restarts the countdown nor adds a timer.
        assertEquals(AccessResult.ALREADY_ACTIVE, controller.startTemporaryAccess(id, at(16, 6, 34)))
        assertEquals(ms(at(16, 6, 37)), sessions.get()!!.accessEndsAt)
        assertEquals(1, registrations(AlarmReceiver.ACTION_ACCESS_END).size)
        // An early timer delivery doesn't end it; the real end does.
        assertEquals(AccessResult.TOO_EARLY, controller.endTemporaryAccess(id, userReturned = false, now = at(16, 6, 35)))
        assertEquals(AccessResult.ENDED, controller.endTemporaryAccess(id, userReturned = false, now = at(16, 6, 37)))

        val back = sessions.get()!!
        assertEquals(SessionState.RINGING_QR, back.state)
        assertEquals(setOf("c0"), back.scannedQrIds)
        assertTrue(registrations(AlarmReceiver.ACTION_ACCESS_END).isEmpty())
        // The main schedule is untouched (still armed for the next occurrence).
        assertEquals(1, registrations(AlarmReceiver.ACTION_FIRE).size)
    }

    // --- Recovery ----------------------------------------------------------------------------

    @Test
    fun rebootDuringARingResumesTheSession() = runBlocking {
        val alarm = preAlarmDaily()
        controller.startSession(alarm.id, monday, at(16, 6, 30))
        solveMath(sessions.get()!!.occurrenceId, at(16, 6, 31))
        // AlarmManager is wiped by the reboot; storage is not.
        val am = context.getSystemService(AlarmManager::class.java)
        shadowAlarms.scheduledAlarms.toList().forEach { am.cancel(it.operation!!) }

        assertTrue(controller.recoverAfterBoot(at(16, 6, 40)))
        val recover = registrations(AlarmReceiver.ACTION_RECOVER).single()
        assertEquals(ms(at(16, 6, 40, 5)), recover.triggerAtMs)
        val intent = shadowOf(recover.operation).savedIntent
        assertEquals(alarm.id, intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1))
        assertEquals(monday, intent.getStringExtra(AlarmReceiver.EXTRA_OCCURRENCE))
        val resumed = controller.startSession(alarm.id, monday, at(16, 6, 40)) as AlarmController.FireDecision.Ring
        assertEquals(SessionState.RINGING_QR, resumed.session.state)
    }

    @Test
    fun staleSessionAfterALongPowerOffIsMarkedMissed() = runBlocking {
        val alarm = preAlarmDaily()
        controller.startSession(alarm.id, monday, at(16, 6, 30))
        assertFalse(controller.recoverAfterBoot(at(16, 10, 0)))
        assertEquals(SessionState.MISSED, sessions.get()!!.state)
        assertTrue(registrations(AlarmReceiver.ACTION_RECOVER).isEmpty())
        assertEquals(AlarmController.FireDecision.Ignore, controller.startSession(alarm.id, monday, at(16, 10, 0)))
    }

    @Test
    fun timeZoneChangeDuringASessionDoesNotTouchIt() = runBlocking {
        val alarm = preAlarmDaily()
        controller.startSession(alarm.id, monday, at(16, 6, 30))
        val before = sessions.get()!!
        controller.syncAll(at(16, 6, 31).withZoneSameInstant(ZoneId.of("Europe/London")))
        assertEquals(before, sessions.get())
        // The alarm itself re-arms at 06:30 local time in the new zone.
        val trigger = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(registrations(AlarmReceiver.ACTION_FIRE).single().triggerAtMs),
            ZoneId.of("Europe/London"),
        )
        assertEquals(6, trigger.hour)
        assertEquals(30, trigger.minute)
    }

    @Test
    fun editingAnotherAlarmWhileOneRingsKeepsBothConsistent() = runBlocking {
        val ringing = preAlarmDaily()
        val other = save(Alarm(hour = 8, minute = 0, repeatDays = daily, qrCodes = codes.take(2)))
        controller.startSession(ringing.id, monday, at(16, 6, 30))
        controller.save(other.copy(hour = 9), at(16, 6, 31))
        assertEquals(ringing.id, sessions.get()!!.alarmId)
        val otherRegs = registrations(AlarmReceiver.ACTION_FIRE)
            .filter { shadowOf(it.operation).savedIntent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1) == other.id }
        assertEquals(ms(at(16, 9, 0)), otherRegs.single().triggerAtMs)
    }
}
