package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.data.RegisteredQr
import com.wakeforce.app.math.MathSettings
import com.wakeforce.app.qr.QrCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SessionMachineTest {

    private val codes = List(3) { RegisteredQr("id$it", "Code ${it + 1}", QrCodes.newPayload()) }
    /** The two-step challenge is the explicit MATH_AND_QR mode; single modes are in ChallengeModeTest. */
    private val alarm = Alarm(id = 7, hour = 6, minute = 30, mode = ChallengeMode.MATH_AND_QR, qrCodes = codes,
        tempAccessVolumePercent = 20)
    private val math = MathSettings(questionCount = 2, minValue = 10, maxValue = 99)
    private val t0 = 1_800_000_000_000L

    private fun start(a: Alarm = alarm) = SessionMachine.start(a, "2026-11-16T06:30", t0, math, Random(1))

    private fun solveMath(s: AlarmSession): AlarmSession {
        var session = s
        repeat(50) {
            if (session.state != SessionState.RINGING_MATH) return session
            session = SessionMachine.submitMath(session, session.math.current!!.answer.toString(), t0).session
        }
        error("math not completed")
    }

    private fun scanAll(s: AlarmSession): AlarmSession =
        codes.fold(s) { acc, c -> SessionMachine.submitQr(acc, c.payload, t0 + 60_000).session }

    // --- Challenge order ---------------------------------------------------------------------

    @Test
    fun startsWithMathAndSnapshotsTheAlarm() {
        val s = start()
        assertEquals(SessionState.RINGING_MATH, s.state)
        assertEquals("7@2026-11-16T06:30", s.occurrenceId)
        assertEquals(codes, s.qrCodes)
        assertEquals(20, s.sound.tempAccessVolumePercent)
        assertTrue(s.isActive)
    }

    @Test
    fun qrScansAreRejectedUntilMathIsDone() {
        val s = start()
        val step = SessionMachine.submitQr(s, codes[0].payload, t0)
        assertEquals(QrResult.NOT_ALLOWED, step.result)
        assertSame(s, step.session)
    }

    @Test
    fun mathAloneDoesNotDismiss() {
        val afterMath = solveMath(start())
        assertEquals(SessionState.RINGING_QR, afterMath.state)
        assertTrue(afterMath.isActive)
        assertNull(afterMath.completedAt)
    }

    @Test
    fun mathCompletionIsReportedOnce() {
        var s = start()
        val first = SessionMachine.submitMath(s, s.math.current!!.answer.toString(), t0)
        assertEquals(MathResult.CORRECT, first.result)
        s = first.session
        val second = SessionMachine.submitMath(s, s.math.current!!.answer.toString(), t0)
        assertEquals(MathResult.MATH_COMPLETE, second.result)
        assertEquals(MathResult.NOT_ALLOWED, SessionMachine.submitMath(second.session, "1", t0).result)
    }

    @Test
    fun wrongAndEmptyAnswersDoNotAdvance() {
        val s = start()
        assertEquals(MathResult.REJECTED, SessionMachine.submitMath(s, "", t0).result)
        val wrong = SessionMachine.submitMath(s, (s.math.current!!.answer + 1).toString(), t0)
        assertEquals(MathResult.REJECTED, wrong.result)
        assertEquals(0, wrong.session.math.index)
    }

    @Test
    fun bothChallengesCompleteTheSession() {
        val done = scanAll(solveMath(start()))
        assertEquals(SessionState.COMPLETED, done.state)
        assertFalse(done.isActive)
        assertEquals(t0 + 60_000, done.completedAt)
    }

    // --- Multi-QR rules ----------------------------------------------------------------------

    @Test
    fun everyRegisteredCodeIsRequired() {
        var s = solveMath(start())
        s = SessionMachine.submitQr(s, codes[0].payload, t0).session
        s = SessionMachine.submitQr(s, codes[1].payload, t0).session
        assertEquals(SessionState.RINGING_QR, s.state) // 2 of 3 is not enough
        val last = SessionMachine.submitQr(s, codes[2].payload, t0)
        assertEquals(QrResult.SESSION_COMPLETE, last.result)
    }

    @Test
    fun aCodeCannotCountTwice() {
        var s = solveMath(start())
        s = SessionMachine.submitQr(s, codes[0].payload, t0).session
        val again = SessionMachine.submitQr(s, codes[0].payload, t0)
        assertEquals(QrResult.ALREADY_SCANNED, again.result)
        assertEquals(setOf("id0"), again.session.scannedQrIds)
    }

    @Test
    fun wrongCodesAreRejected() {
        val s = solveMath(start())
        assertEquals(QrResult.WRONG_CODE, SessionMachine.submitQr(s, QrCodes.newPayload(), t0).result)
        assertEquals(QrResult.WRONG_CODE, SessionMachine.submitQr(s, "https://example.com", t0).result)
        assertEquals(QrResult.WRONG_CODE, SessionMachine.submitQr(s, "", t0).result)
    }

    @Test
    fun anotherAlarmsCodesDoNotWork() {
        val otherCodes = List(2) { RegisteredQr("o$it", "Other", QrCodes.newPayload()) }
        val other = solveMath(start(alarm.copy(id = 8, qrCodes = otherCodes)))
        codes.forEach { assertEquals(QrResult.WRONG_CODE, SessionMachine.submitQr(other, it.payload, t0).result) }
    }

    @Test
    fun twoCodeAlarmCompletesAfterBoth() {
        val two = alarm.copy(qrCodes = codes.take(2))
        var s = solveMath(start(two))
        s = SessionMachine.submitQr(s, codes[0].payload, t0).session
        assertEquals(QrResult.SESSION_COMPLETE, SessionMachine.submitQr(s, codes[1].payload, t0).result)
    }

    // --- Temporary access --------------------------------------------------------------------

    @Test
    fun accessWindowLastsFiveMinutesAndKeepsSessionActive() {
        val step = SessionMachine.startAccess(start(), t0)
        assertEquals(AccessResult.STARTED, step.result)
        assertEquals(SessionState.TEMPORARY_ACCESS, step.session.state)
        assertEquals(t0 + 5 * 60_000, step.session.accessEndsAt)
        assertTrue(step.session.isActive)
        assertNull(step.session.completedAt)
    }

    @Test
    fun noChallengeInputDuringAccessAndItCannotComplete() {
        val inAccess = SessionMachine.startAccess(solveMath(start()), t0).session
        assertEquals(QrResult.NOT_ALLOWED, SessionMachine.submitQr(inAccess, codes[0].payload, t0).result)
        assertEquals(MathResult.NOT_ALLOWED, SessionMachine.submitMath(inAccess, "1", t0).result)
        assertFalse(SessionMachine.canTransition(SessionState.TEMPORARY_ACCESS, SessionState.COMPLETED))
    }

    @Test
    fun progressSurvivesTheWindow() {
        var s = solveMath(start())
        s = SessionMachine.submitQr(s, codes[0].payload, t0).session
        s = SessionMachine.startAccess(s, t0).session
        val back = SessionMachine.endAccess(s, t0 + 5 * 60_000, userReturned = false)
        assertEquals(AccessResult.ENDED, back.result)
        assertEquals(SessionState.RINGING_QR, back.session.state)
        assertEquals(setOf("id0"), back.session.scannedQrIds)
        assertTrue(back.session.math.solved)
        assertNull(back.session.accessEndsAt)
    }

    @Test
    fun windowCannotBeRestartedOrExtended() {
        val s = SessionMachine.startAccess(start(), t0).session
        val again = SessionMachine.startAccess(s, t0 + 60_000)
        assertEquals(AccessResult.ALREADY_ACTIVE, again.result)
        assertEquals(t0 + 5 * 60_000, again.session.accessEndsAt) // countdown not reset
    }

    @Test
    fun windowDoesNotEndEarlyOnItsOwn() {
        val s = SessionMachine.startAccess(start(), t0).session
        assertEquals(AccessResult.TOO_EARLY, SessionMachine.endAccess(s, t0 + 60_000, userReturned = false).result)
        assertEquals(AccessResult.ENDED, SessionMachine.endAccess(s, t0 + 60_000, userReturned = true).result)
    }

    @Test
    fun limitedNumberOfWindows() {
        var s = start()
        repeat(SessionMachine.MAX_ACCESS_WINDOWS) {
            s = SessionMachine.startAccess(s, t0).session
            s = SessionMachine.endAccess(s, t0, userReturned = true).session
        }
        assertEquals(0, s.accessWindowsLeft)
        assertEquals(AccessResult.LIMIT_REACHED, SessionMachine.startAccess(s, t0).result)
    }

    @Test
    fun accessCanBeDisabledPerAlarm() {
        val s = start(alarm.copy(tempAccessEnabled = false))
        assertEquals(AccessResult.DISABLED, SessionMachine.startAccess(s, t0).result)
    }

    // --- Transitions & recovery ----------------------------------------------------------------

    @Test
    fun completedAndMissedAreTerminal() {
        SessionState.entries.forEach {
            assertFalse(SessionMachine.canTransition(SessionState.COMPLETED, it))
            assertFalse(SessionMachine.canTransition(SessionState.MISSED, it))
            assertFalse(SessionMachine.canTransition(SessionState.PRE_ALARM_SKIPPED, it))
        }
        assertFalse(SessionMachine.canTransition(SessionState.SCHEDULED, SessionState.COMPLETED))
        assertFalse(SessionMachine.canTransition(SessionState.RINGING_QR, SessionState.RINGING_MATH))
    }

    @Test
    fun interruptedSessionRecoversOnlyWhenRecent() {
        val s = solveMath(start())
        assertTrue(SessionMachine.shouldRecover(s, t0 + 30 * 60_000))
        assertFalse(SessionMachine.shouldRecover(s, t0 + 3 * 60 * 60_000))
        assertEquals(SessionState.MISSED, SessionMachine.markMissed(s).state)
        val done = scanAll(s)
        assertFalse(SessionMachine.shouldRecover(done, t0))
        assertSame(done, SessionMachine.markMissed(done))
    }

    @Test
    fun legacyAlarmWithoutCodesNeedsMathOnly() {
        // Pre-2.0 data that hasn't been given QR codes yet keeps ringing and can be stopped.
        val done = solveMath(start(alarm.copy(qrCodes = emptyList())))
        assertEquals(SessionState.COMPLETED, done.state)
    }
}
