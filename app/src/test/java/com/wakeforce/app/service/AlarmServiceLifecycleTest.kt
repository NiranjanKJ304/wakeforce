package com.wakeforce.app.service

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.wakeforce.app.alarm.AlarmController
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.data.SessionRepository
import com.wakeforce.app.data.WakeForceDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.time.DayOfWeek

/**
 * Runs the real ringing service (Robolectric) against the real controller and storage, and
 * checks the audio lifecycle. Verifies app logic, not the device speaker/vibrator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmServiceLifecycleTest {

    private lateinit var context: Context
    private lateinit var controller: AlarmController
    private lateinit var sessions: SessionRepository

    @Before
    fun setUp() {
        WakeForceDataStore.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        controller = AlarmController(context)
        sessions = SessionRepository(context)
        AlarmService.playbackStarts = 0
        AlarmService.audioModeForTest = AudioMode.STOPPED
        // A playable fake alarm tone for Robolectric's MediaPlayer.
        listOf(Settings.System.DEFAULT_ALARM_ALERT_URI, Settings.System.DEFAULT_RINGTONE_URI).forEach {
            ShadowMediaPlayer.addMediaInfo(DataSource.toDataSource(context, it), ShadowMediaPlayer.MediaInfo(60_000, 0))
        }
    }

    /** Runs a controller call off the main thread while pumping the main looper (the service lives there). */
    private fun <T> pumped(block: suspend () -> T): T {
        val job = CoroutineScope(Dispatchers.Default).async { block() }
        repeat(2_000) {
            shadowOf(Looper.getMainLooper()).idle()
            if (job.isCompleted) return runBlocking { job.await() }
            Thread.sleep(5)
        }
        error("timed out")
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        repeat(2_000) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("timed out waiting for $what")
    }

    private fun settle() = repeat(40) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(5)
    }

    private fun ringIntent(alarm: Alarm) = Intent(context, AlarmService::class.java)
        .setAction("com.wakeforce.app.action.RING")
        .putExtra("alarm_id", alarm.id)
        .putExtra("occurrence", alarm.occurrence)

    private fun startService(intent: Intent): ServiceController<AlarmService> =
        Robolectric.buildService(AlarmService::class.java, intent).create().also { it.startCommand(0, 1) }

    @Test
    fun audioStartsOnceAndStopsOnlyAfterTheChallenge() {
        val alarm = pumped {
            (controller.save(Alarm(hour = 5, minute = 0, repeatDays = DayOfWeek.entries.toSet(), mode = ChallengeMode.MATH))
                as AlarmController.SaveOutcome.Saved).alarm
        }.let { pumped { com.wakeforce.app.data.AlarmRepository(context).get(it.id)!! } }

        val service = startService(ringIntent(alarm))
        waitFor("alarm audio") { AlarmService.audioModeForTest == AudioMode.FULL }
        assertEquals(1, AlarmService.playbackStarts)
        assertNotNull("must run as a foreground service", shadowOf(service.get()).lastForegroundNotification)

        // Duplicate broadcast for the same occurrence: no second playback.
        service.withIntent(ringIntent(alarm)).startCommand(0, 2)
        settle()
        assertEquals(1, AlarmService.playbackStarts)
        val id = pumped { sessions.get()!! }.occurrenceId

        // Wrong and empty answers: still ringing.
        pumped { controller.submitMath(id, "") }
        pumped { controller.submitMath(id, "0") }
        settle()
        assertEquals(AudioMode.FULL, AlarmService.audioModeForTest)
        assertFalse(shadowOf(service.get()).isStoppedBySelf)

        // The 5-minute temporary access silences it but does NOT stop or dismiss it.
        pumped { controller.startTemporaryAccess(id) }
        waitFor("silenced") { AlarmService.audioModeForTest == AudioMode.SILENT }
        settle()
        assertFalse(shadowOf(service.get()).isStoppedBySelf)
        assertTrue(pumped { sessions.get()!! }.isActive)
        pumped { controller.endTemporaryAccess(id, userReturned = true) }
        waitFor("resumed") { AlarmService.audioModeForTest == AudioMode.FULL }
        assertEquals("resuming must not start a second player", 1, AlarmService.playbackStarts)

        // Solve every question: audio stops at once and the service ends.
        repeat(20) {
            val s = pumped { sessions.get()!! }
            if (!s.isActive) return@repeat
            pumped { controller.submitMath(id, s.math.current!!.answer.toString()) }
        }
        waitFor("stopped") { AlarmService.audioModeForTest == AudioMode.STOPPED && shadowOf(service.get()).isStoppedBySelf }
        assertEquals(1, AlarmService.playbackStarts)
    }

    @Test
    fun broadcastForACompletedOccurrenceDoesNotRingAgain() {
        val alarm = pumped {
            val saved = (controller.save(Alarm(hour = 5, minute = 0, repeatDays = DayOfWeek.entries.toSet(), mode = ChallengeMode.MATH))
                as AlarmController.SaveOutcome.Saved).alarm
            com.wakeforce.app.data.AlarmRepository(context).get(saved.id)!!
        }
        pumped { controller.startSession(alarm.id, alarm.occurrence) }
        val id = pumped { sessions.get()!! }.occurrenceId
        repeat(20) {
            val s = pumped { sessions.get()!! }
            if (!s.isActive) return@repeat
            pumped { controller.submitMath(id, s.math.current!!.answer.toString()) }
        }
        val service = startService(ringIntent(alarm))
        waitFor("service to stop") { shadowOf(service.get()).isStoppedBySelf }
        assertEquals(0, AlarmService.playbackStarts)
    }
}
