package com.wakeforce.app.alarm

import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.data.RegisteredQr
import com.wakeforce.app.math.MathSettings
import com.wakeforce.app.service.AudioMode
import com.wakeforce.app.service.AudioPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Functional audit: exactly the selected challenge stops the alarm, and audio follows the session. */
class ChallengeModeTest {

    private val sticker = RegisteredQr("s1", "Bathroom", "https://example.com/sticker")
    private val math = MathSettings(questionCount = 3)
    private val t0 = 1_800_000_000_000L

    private fun start(mode: ChallengeMode, codes: List<RegisteredQr> = listOf(sticker)) =
        SessionMachine.start(Alarm(id = 1, hour = 5, minute = 0, mode = mode, qrCodes = codes),
            "2026-11-17T05:00", t0, math, Random(3))

    private fun answerAll(s: AlarmSession): AlarmSession {
        var session = s
        repeat(20) {
            if (session.state != SessionState.RINGING_MATH) return session
            session = SessionMachine.submitMath(session, session.math.current!!.answer.toString(), t0).session
        }
        error("math not completed")
    }

    // --- QR mode -----------------------------------------------------------------------------

    @Test
    fun qrModeOpensTheScannerWithoutMath() {
        val s = start(ChallengeMode.QR)
        assertEquals(SessionState.RINGING_QR, s.state)
        assertTrue(s.math.questions.isEmpty())
        assertEquals(MathResult.NOT_ALLOWED, SessionMachine.submitMath(s, "1", t0).result)
    }

    @Test
    fun qrModeStopsOnlyForTheRegisteredPayload() {
        val s = start(ChallengeMode.QR)
        listOf("", "   ", "random", "https://example.com/other", "https://example.com/sticker2").forEach {
            val step = SessionMachine.submitQr(s, it, t0)
            assertEquals(it, QrResult.WRONG_CODE, step.result)
            assertTrue(step.session.isActive)
        }
        val ok = SessionMachine.submitQr(s, "https://example.com/sticker\n", t0)
        assertEquals(QrResult.SESSION_COMPLETE, ok.result)
        assertEquals(SessionState.COMPLETED, ok.session.state)
        assertEquals(AudioMode.STOPPED, AudioPolicy.forSession(ok.session))
    }

    @Test
    fun qrModeWithoutCodesFallsBackToMathSoItCanStillBeStopped() {
        val s = start(ChallengeMode.QR, codes = emptyList())
        assertEquals(ChallengeMode.MATH, s.mode)
        assertEquals(SessionState.COMPLETED, answerAll(s).state)
    }

    // --- Math mode ---------------------------------------------------------------------------

    @Test
    fun mathModeStopsAfterAllQuestionsAndIgnoresQrCodes() {
        val s = start(ChallengeMode.MATH)
        assertEquals(SessionState.RINGING_MATH, s.state)
        assertTrue("codes stored on the alarm are not part of a MATH challenge", s.qrCodes.isEmpty())
        assertEquals(QrResult.NOT_ALLOWED, SessionMachine.submitQr(s, sticker.payload, t0).result)
        assertEquals(SessionState.COMPLETED, answerAll(s).state)
    }

    @Test
    fun mathModeWrongAndEmptyAnswersKeepItRinging() {
        var s = start(ChallengeMode.MATH)
        s = SessionMachine.submitMath(s, "", t0).session
        s = SessionMachine.submitMath(s, (s.math.current!!.answer + 3).toString(), t0).session
        assertEquals(SessionState.RINGING_MATH, s.state)
        assertEquals(0, s.math.index)
        assertEquals(AudioMode.FULL, AudioPolicy.forSession(s))
    }

    // --- Math + QR (explicit separate configuration) -----------------------------------------

    @Test
    fun mathAndQrNeedsBothInOrder() {
        val s = start(ChallengeMode.MATH_AND_QR)
        assertEquals(QrResult.NOT_ALLOWED, SessionMachine.submitQr(s, sticker.payload, t0).result)
        val afterMath = answerAll(s)
        assertEquals(SessionState.RINGING_QR, afterMath.state)
        assertEquals(SessionState.COMPLETED, SessionMachine.submitQr(afterMath, sticker.payload, t0).session.state)
    }

    // --- Audio policy ------------------------------------------------------------------------

    @Test
    fun audioIsFullWhileRingingNoMatterHowLong() {
        val s = start(ChallengeMode.MATH)
        assertEquals(AudioMode.FULL, AudioPolicy.forSession(s))
        // No time-based dismissal: hours later the session (and audio) is unchanged.
        assertEquals(AudioMode.FULL, AudioPolicy.forSession(s.copy(startedAt = t0 - 6 * 60 * 60 * 1000L)))
    }

    @Test
    fun fiveMinuteAccessOnlyLowersAudioAndThenResumes() {
        val access = SessionMachine.startAccess(start(ChallengeMode.QR), t0).session
        assertEquals(AudioMode.SILENT, AudioPolicy.forSession(access))
        assertEquals(AudioMode.REDUCED,
            AudioPolicy.forSession(access.copy(sound = access.sound.copy(tempAccessVolumePercent = 30))))
        assertTrue(access.isActive)
        val back = SessionMachine.endAccess(access, t0 + SessionMachine.TEMP_ACCESS_MS, userReturned = false).session
        assertEquals(AudioMode.FULL, AudioPolicy.forSession(back))
        assertEquals(SessionState.RINGING_QR, back.state)
    }

    @Test
    fun audioStopsOnlyWhenTheSessionEnds() {
        assertEquals(AudioMode.STOPPED, AudioPolicy.forSession(null))
        assertEquals(AudioMode.STOPPED, AudioPolicy.forSession(SessionMachine.markMissed(start(ChallengeMode.MATH))))
        SessionState.entries.filter { it in SessionMachine.ACTIVE }.forEach {
            assertFalse(it.name, AudioPolicy.forSession(start(ChallengeMode.MATH).copy(state = it)) == AudioMode.STOPPED)
        }
    }

    @Test
    fun alarmReadinessDependsOnMode() {
        assertTrue(Alarm(hour = 5, minute = 0, mode = ChallengeMode.MATH).isChallengeReady)
        assertFalse(Alarm(hour = 5, minute = 0, mode = ChallengeMode.QR).isChallengeReady)
        assertTrue(Alarm(hour = 5, minute = 0, mode = ChallengeMode.QR, qrCodes = listOf(sticker)).isChallengeReady)
        assertFalse(Alarm(hour = 5, minute = 0, mode = ChallengeMode.MATH_AND_QR).isChallengeReady)
    }
}
