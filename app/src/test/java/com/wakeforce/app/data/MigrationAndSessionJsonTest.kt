package com.wakeforce.app.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wakeforce.app.alarm.QueuedFire
import com.wakeforce.app.alarm.SessionMachine
import com.wakeforce.app.alarm.SessionState
import com.wakeforce.app.alarm.WakeStats
import com.wakeforce.app.math.MathSettings
import com.wakeforce.app.qr.QrCodes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MigrationAndSessionJsonTest {

    private val alarmsKey = stringPreferencesKey("alarms")
    private val legacyQrKey = stringPreferencesKey("qr_payload")

    private val v1Alarms = """[
        {"id":1,"hour":6,"minute":30,"enabled":true,"days":[1,2,3,4,5],"mode":"QR","vibrate":true,"volume":80,"armedAt":1800000000000},
        {"id":2,"hour":9,"minute":0,"enabled":false,"days":[],"mode":"MATH","vibrate":false,"volume":100}
    ]"""

    @Test
    fun v1DataIsMigratedWithoutLosingAlarms() = runBlocking {
        val v1 = mutablePreferencesOf(alarmsKey to v1Alarms, legacyQrKey to "WAKEFORCE1:legacy")
        assertTrue(SchemaMigration.shouldMigrate(v1))
        val v2 = SchemaMigration.migratePreferences(v1) { "fixedid" }
        val alarms = AlarmJson.decode(v2[alarmsKey])

        assertEquals(listOf(1, 2), alarms.map { it.id })
        val qrAlarm = alarms[0]
        assertEquals(6, qrAlarm.hour)
        assertEquals(80, qrAlarm.volumePercent)
        assertEquals(1_800_000_000_000L, qrAlarm.armedAt)
        assertEquals(listOf(RegisteredQr("fixedid", "Code 1", "WAKEFORCE1:legacy")), qrAlarm.qrCodes)
        assertEquals(ChallengeMode.QR, qrAlarm.mode) // keeps its single-challenge QR mode
        // MATH-mode alarm keeps its settings and gets no codes.
        assertFalse(alarms[1].enabled)
        assertTrue(alarms[1].qrCodes.isEmpty())
        assertEquals(ChallengeMode.MATH, alarms[1].mode)
        // New settings default safely.
        assertFalse(qrAlarm.preAlarmEnabled)
        assertTrue(qrAlarm.tempAccessEnabled)
        // Global secret removed; schema marked current so it runs once.
        assertNull(v2[legacyQrKey])
        assertFalse(SchemaMigration.shouldMigrate(v2))
    }

    @Test
    fun freshInstallMigratesToEmptyV2() = runBlocking {
        val v2 = SchemaMigration.migratePreferences(mutablePreferencesOf())
        assertNull(v2[alarmsKey])
        assertEquals(SchemaMigration.CURRENT_VERSION, v2[SchemaMigration.SCHEMA_VERSION])
    }

    @Test
    fun migrationIsIdempotent() {
        val once = SchemaMigration.migratePreferences(mutablePreferencesOf(alarmsKey to v1Alarms, legacyQrKey to "WAKEFORCE1:x")) { "a" }
        val twice = SchemaMigration.migratePreferences(once) { "b" }
        assertEquals(AlarmJson.decode(once[alarmsKey]), AlarmJson.decode(twice[alarmsKey]))
    }

    @Test
    fun sessionRoundTripsWithProgress() {
        val codes = List(3) { RegisteredQr("q$it", "Code $it", QrCodes.newPayload()) }
        val alarm = Alarm(id = 3, hour = 6, minute = 0, mode = ChallengeMode.MATH_AND_QR, qrCodes = codes,
            soundUri = "content://x", tempAccessVolumePercent = 10)
        var s = SessionMachine.start(alarm, "2026-11-16T06:00", 123L, MathSettings(questionCount = 4), Random(9))
        s = SessionMachine.submitMath(s, s.math.current!!.answer.toString(), 200L).session
        s = SessionMachine.startAccess(s, 300L).session.copy(
            scannedQrIds = setOf("q1"), queued = listOf(QueuedFire(5, "2026-11-16T06:00")),
        )
        val decoded = SessionJson.decode(SessionJson.encode(s))!!
        assertEquals(s.copy(math = s.math.copy(message = null)), decoded)
        assertEquals(SessionState.TEMPORARY_ACCESS, decoded.state)
        assertEquals(ChallengeMode.MATH_AND_QR, decoded.mode)
        assertEquals(1, decoded.math.index)
        assertEquals(300L + SessionMachine.TEMP_ACCESS_MS, decoded.accessEndsAt)
    }

    @Test
    fun corruptSessionOrStatsDecodeSafely() {
        assertNull(SessionJson.decode("{broken"))
        assertNull(SessionJson.decode(null))
        assertEquals(WakeStats(), SessionJson.decodeStats("nope"))
        val stats = WakeStats(4, 1_000L, 90_000L)
        assertEquals(stats, SessionJson.decodeStats(SessionJson.encodeStats(stats)))
    }
}
