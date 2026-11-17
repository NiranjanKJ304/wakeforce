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
import com.wakeforce.app.receiver.AlarmReceiver
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
 * Functional audit scenarios on Robolectric (real AlarmManager registrations + DataStore):
 * the 5:00 alarm with a 1-hour pre-alarm, per-alarm challenge modes, physical QR codes,
 * isolation between alarms. Verifies logic/registrations, not physical-device delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FunctionalAuditIntegrationTest {

    private lateinit var context: Context
    private lateinit var shadowAlarms: ShadowAlarmManager
    private lateinit var controller: AlarmController
    private lateinit var alarms: AlarmRepository
    private lateinit var sessions: SessionRepository

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, s, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val evening = at(16, 21, 0) // Monday 21:00
    private val tue0500 = "2026-11-17T05:00"
    private val daily = DayOfWeek.entries.toSet()
    private val sticker = RegisteredQr("s1", "Bathroom", "https://example.com/bathroom-sticker")
    private val otherSticker = RegisteredQr("s2", "Kitchen", "KITCHEN-STICKER-42")

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

    private fun regs(action: String, alarmId: Int? = null) = shadowAlarms.scheduledAlarms.filter {
        val intent = shadowOf(it.operation).savedIntent
        intent.action == action && (alarmId == null || intent.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1) == alarmId)
    }

    private fun save(alarm: Alarm, now: ZonedDateTime = evening): Alarm = runBlocking {
        (controller.save(alarm, now) as AlarmController.SaveOutcome.Saved).alarm
    }

    /** Main alarm 5:00 AM daily, pre-alarm 1 hour before (the example in the requirements). */
    private fun fiveAm(mode: ChallengeMode = ChallengeMode.MATH, repeat: Set<DayOfWeek> = daily, codes: List<RegisteredQr> = emptyList()) =
        save(Alarm(hour = 5, minute = 0, repeatDays = repeat, mode = mode, qrCodes = codes,
            preAlarmEnabled = true, preAlarmMinutes = 60))

    private suspend fun solveMath(occurrenceId: String, now: ZonedDateTime) {
        repeat(50) {
            val s = sessions.get()!!
            if (s.occurrenceId != occurrenceId || s.state != SessionState.RINGING_MATH) return
            controller.submitMath(occurrenceId, s.math.current!!.answer.toString(), now)
        }
        error("not solved")
    }

    // --- 1. Pre-alarm -------------------------------------------------------------------------

    @Test
    fun preAlarmAtFourForFiveAmAlarm() {
        val alarm = fiveAm()
        assertEquals(ms(at(17, 4, 0)), regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).single().triggerAtMs)
        assertEquals(ms(at(17, 5, 0)), regs(AlarmReceiver.ACTION_FIRE, alarm.id).single().triggerAtMs)
        assertEquals(alarm.id, runBlocking { controller.preAlarmToShow(alarm.id, tue0500, at(17, 4, 0)) }?.id)
    }

    @Test
    fun yesThenTheMainAlarmRingsAtFive() = runBlocking {
        val alarm = fiveAm()
        assertTrue(controller.confirmPreAlarm(alarm.id, tue0500, keep = true, now = at(17, 4, 0)) is ConfirmPlan.Confirmed)
        assertEquals(ms(at(17, 5, 0)), regs(AlarmReceiver.ACTION_FIRE, alarm.id).single().triggerAtMs)
        // Asking again (duplicate tap / second prompt) is a no-op for the confirmed occurrence.
        assertNull(controller.preAlarmToShow(alarm.id, tue0500, at(17, 4, 1)))
        assertTrue(controller.startSession(alarm.id, tue0500, at(17, 5, 0)) is AlarmController.FireDecision.Ring)
    }

    @Test
    fun noSkipsOnlyFiveTodayAndTomorrowRings() = runBlocking {
        val alarm = fiveAm()
        controller.confirmPreAlarm(alarm.id, tue0500, keep = false, now = at(17, 4, 0))
        val stored = alarms.get(alarm.id)!!
        assertTrue(stored.enabled)
        assertEquals(ms(at(18, 5, 0)), regs(AlarmReceiver.ACTION_FIRE, alarm.id).single().triggerAtMs)
        assertEquals(ms(at(18, 4, 0)), regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).single().triggerAtMs)
        assertEquals(AlarmController.FireDecision.Ignore, controller.startSession(alarm.id, tue0500, at(17, 5, 0)))
        // Wednesday rings normally.
        assertTrue(controller.startSession(alarm.id, "2026-11-18T05:00", at(18, 5, 0)) is AlarmController.FireDecision.Ring)
    }

    @Test
    fun ignoredPromptIsNeverTreatedAsNo() = runBlocking {
        val alarm = fiveAm()
        // Prompt fired at 4:00, nobody answered; app opened and the clock re-synced in between.
        controller.syncAll(at(17, 4, 30))
        assertNull(alarms.get(alarm.id)!!.skippedOccurrence)
        assertEquals(ms(at(17, 5, 0)), regs(AlarmReceiver.ACTION_FIRE, alarm.id).single().triggerAtMs)
        assertTrue(controller.startSession(alarm.id, tue0500, at(17, 5, 0)) is AlarmController.FireDecision.Ring)
    }

    @Test
    fun oneTimeAlarmNoIsKeptAndReEnablingRingsAgain() = runBlocking {
        val alarm = fiveAm(repeat = emptySet())
        controller.confirmPreAlarm(alarm.id, tue0500, keep = false, now = at(17, 4, 0))
        assertFalse(alarms.get(alarm.id)!!.enabled)
        assertTrue(regs(AlarmReceiver.ACTION_FIRE, alarm.id).isEmpty())
        assertEquals(1, alarms.getAll().size) // not deleted
        // The user changes their mind at 4:30 and switches it back on: today's 5:00 rings.
        controller.setEnabled(alarm.id, true, at(17, 4, 30))
        assertEquals(ms(at(17, 5, 0)), regs(AlarmReceiver.ACTION_FIRE, alarm.id).single().triggerAtMs)
        assertNull(alarms.get(alarm.id)!!.skippedOccurrence)
    }

    @Test
    fun changingTheAlarmTimeOrIntervalMovesThePreAlarm() = runBlocking {
        val alarm = fiveAm()
        controller.save(alarms.get(alarm.id)!!.copy(hour = 6, minute = 30), evening)
        assertEquals(ms(at(17, 5, 30)), regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).single().triggerAtMs)
        controller.save(alarms.get(alarm.id)!!.copy(preAlarmMinutes = 15), evening)
        assertEquals(ms(at(17, 6, 15)), regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).single().triggerAtMs)
        controller.save(alarms.get(alarm.id)!!.copy(preAlarmEnabled = false), evening)
        assertTrue(regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).isEmpty())
    }

    @Test
    fun noDuplicatePreAlarmOrMainRegistrations() = runBlocking {
        val alarm = fiveAm()
        repeat(3) { controller.syncAll(evening) }
        controller.syncAll(evening, catchUp = true)
        assertEquals(1, regs(AlarmReceiver.ACTION_FIRE, alarm.id).size)
        assertEquals(1, regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).size)
    }

    @Test
    fun rebootDuringTheUnansweredPromptShowsItAgainOnce() = runBlocking {
        val alarm = fiveAm()
        val am = context.getSystemService(AlarmManager::class.java)
        shadowAlarms.scheduledAlarms.toList().forEach { am.cancel(it.operation!!) } // reboot wipes AlarmManager
        controller.syncAll(at(17, 4, 30), catchUp = true) // LOCKED_BOOT_COMPLETED
        val repost = regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).single()
        assertEquals(ms(at(17, 4, 30, 5)), repost.triggerAtMs)
        assertEquals(tue0500, shadowOf(repost.operation).savedIntent.getStringExtra(AlarmReceiver.EXTRA_OCCURRENCE))
        controller.syncAll(at(17, 4, 30, 2), catchUp = false) // BOOT_COMPLETED seconds later
        assertEquals(1, regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).size) // not cancelled, not doubled
        assertEquals(ms(at(17, 5, 0)), regs(AlarmReceiver.ACTION_FIRE, alarm.id).single().triggerAtMs)
    }

    @Test
    fun rebootAfterAnsweringKeepsTheAnswer() = runBlocking {
        val alarm = fiveAm()
        controller.confirmPreAlarm(alarm.id, tue0500, keep = false, now = at(17, 4, 0))
        val am = context.getSystemService(AlarmManager::class.java)
        shadowAlarms.scheduledAlarms.toList().forEach { am.cancel(it.operation!!) }
        controller.syncAll(at(17, 4, 30), catchUp = true)
        assertEquals(ms(at(18, 5, 0)), regs(AlarmReceiver.ACTION_FIRE, alarm.id).single().triggerAtMs)
        assertEquals(ms(at(18, 4, 0)), regs(AlarmReceiver.ACTION_PRE_ALARM, alarm.id).single().triggerAtMs)
    }

    // --- 2/3. Challenge modes and QR ----------------------------------------------------------

    @Test
    fun qrModeRegistrationPersistsAndOnlyItsPayloadStopsTheAlarm() = runBlocking {
        val alarm = fiveAm(mode = ChallengeMode.QR, codes = listOf(sticker))
        // Persisted: a fresh repository reads it back from storage.
        val reread = AlarmRepository(context).get(alarm.id)!!
        assertEquals(ChallengeMode.QR, reread.mode)
        assertEquals(listOf(sticker), reread.qrCodes)

        val ring = controller.startSession(alarm.id, tue0500, at(17, 5, 0)) as AlarmController.FireDecision.Ring
        val id = ring.session.occurrenceId
        assertEquals(SessionState.RINGING_QR, ring.session.state)
        listOf("", "  ", "random text", otherSticker.payload, "https://example.com/bathroom-sticker/").forEach {
            assertEquals(it, QrResult.WRONG_CODE, controller.submitQr(id, it, at(17, 5, 1)))
        }
        assertTrue(sessions.get()!!.isActive)
        assertEquals(QrResult.SESSION_COMPLETE, controller.submitQr(id, sticker.payload, at(17, 5, 2)))
        assertEquals(tue0500, alarms.get(alarm.id)!!.lastCompletedOccurrence)
    }

    @Test
    fun aQrRegisteredToAnotherAlarmIsRejected() = runBlocking {
        val five = fiveAm(mode = ChallengeMode.QR, codes = listOf(sticker))
        save(Alarm(hour = 7, minute = 0, repeatDays = daily, mode = ChallengeMode.QR, qrCodes = listOf(otherSticker)))
        val ring = controller.startSession(five.id, tue0500, at(17, 5, 0)) as AlarmController.FireDecision.Ring
        assertEquals(QrResult.WRONG_CODE, controller.submitQr(ring.session.occurrenceId, otherSticker.payload, at(17, 5, 1)))
        assertEquals(QrResult.SESSION_COMPLETE, controller.submitQr(ring.session.occurrenceId, sticker.payload, at(17, 5, 2)))
    }

    @Test
    fun mathModeNeedsOnlyMathEvenIfCodesAreStored() = runBlocking {
        val alarm = fiveAm(mode = ChallengeMode.MATH, codes = listOf(sticker))
        val ring = controller.startSession(alarm.id, tue0500, at(17, 5, 0)) as AlarmController.FireDecision.Ring
        val id = ring.session.occurrenceId
        assertEquals(QrResult.NOT_ALLOWED, controller.submitQr(id, sticker.payload, at(17, 5, 1)))
        assertEquals(MathResult.REJECTED, controller.submitMath(id, "", at(17, 5, 1)))
        assertEquals(MathResult.REJECTED, controller.submitMath(id, "0", at(17, 5, 1)))
        assertTrue(sessions.get()!!.isActive)
        solveMath(id, at(17, 5, 2))
        assertEquals(SessionState.COMPLETED, sessions.get()!!.state)
    }

    @Test
    fun modeIsSavedPerAlarmAndSnapshottedWhenRinging() = runBlocking {
        val qr = fiveAm(mode = ChallengeMode.QR, codes = listOf(sticker))
        val math = save(Alarm(hour = 7, minute = 0, repeatDays = daily, mode = ChallengeMode.MATH))
        assertEquals(ChallengeMode.QR, alarms.get(qr.id)!!.mode)
        assertEquals(ChallengeMode.MATH, alarms.get(math.id)!!.mode)
        controller.startSession(qr.id, tue0500, at(17, 5, 0))
        // Switching the ringing alarm to MATH mid-ring doesn't let math stop it.
        controller.save(alarms.get(qr.id)!!.copy(mode = ChallengeMode.MATH), at(17, 5, 1))
        val s = sessions.get()!!
        assertEquals(ChallengeMode.QR, s.mode)
        assertEquals(MathResult.NOT_ALLOWED, controller.submitMath(s.occurrenceId, "1", at(17, 5, 2)))
    }

    // --- 6. Isolation between alarms ----------------------------------------------------------

    @Test
    fun multipleAlarmsAreIsolated() = runBlocking {
        val five = fiveAm()
        val seven = save(Alarm(hour = 7, minute = 0, repeatDays = setOf(DayOfWeek.TUESDAY), mode = ChallengeMode.QR,
            qrCodes = listOf(otherSticker), preAlarmEnabled = true, preAlarmMinutes = 30))
        // Skip the 5:00; the 7:00 is untouched.
        controller.confirmPreAlarm(five.id, tue0500, keep = false, now = at(17, 4, 0))
        assertEquals(ms(at(17, 7, 0)), regs(AlarmReceiver.ACTION_FIRE, seven.id).single().triggerAtMs)
        assertEquals(ms(at(17, 6, 30)), regs(AlarmReceiver.ACTION_PRE_ALARM, seven.id).single().triggerAtMs)
        assertNull(alarms.get(seven.id)!!.skippedOccurrence)
        // A confirmation for the 5:00 can't be applied to the 7:00 alarm.
        assertEquals(ConfirmPlan.Stale, controller.confirmPreAlarm(seven.id, tue0500, keep = false, now = at(17, 4, 0)))
        // Deleting one alarm leaves the other armed.
        controller.delete(five.id)
        assertTrue(regs(AlarmReceiver.ACTION_FIRE, five.id).isEmpty())
        assertTrue(regs(AlarmReceiver.ACTION_PRE_ALARM, five.id).isEmpty())
        assertEquals(1, regs(AlarmReceiver.ACTION_FIRE, seven.id).size)
    }
}
