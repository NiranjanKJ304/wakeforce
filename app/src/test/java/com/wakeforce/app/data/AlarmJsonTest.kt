package com.wakeforce.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/** Stored state is what reboot recovery rebuilds from, so it must round-trip exactly. */
class AlarmJsonTest {

    @Test
    fun roundTripsEveryField() {
        val alarms = listOf(
            Alarm(
                id = 4, hour = 6, minute = 45, enabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                vibrate = false, volumePercent = 70,
                soundUri = "content://media/internal/audio/media/12", armedAt = 1_800_000_000_000L,
                qrCodes = listOf(RegisteredQr("a1", "Bathroom", "WAKEFORCE1:aaa"), RegisteredQr("b2", "Kitchen", "WAKEFORCE1:bbb")),
                preAlarmEnabled = true, preAlarmMinutes = 90,
                tempAccessEnabled = false, tempAccessVolumePercent = 30,
                occurrence = "2026-11-16T06:45", skippedOccurrence = "2026-11-13T06:45",
                confirmedOccurrence = "2026-11-16T06:45", lastCompletedOccurrence = "2026-11-12T06:45",
            ),
            Alarm(id = 5, hour = 23, minute = 59, enabled = false),
        )
        assertEquals(alarms, AlarmJson.decode(AlarmJson.encode(alarms)))
    }

    @Test
    fun corruptEntriesAreSkippedNotFatal() {
        val json = """[{"id":1,"hour":7,"minute":0},{"oops":true},"x",{"id":2,"hour":8,"minute":30,"days":[1,9,7],"mode":"BOGUS"}]"""
        val decoded = AlarmJson.decode(json)
        assertEquals(listOf(1, 2), decoded.map { it.id })
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), decoded[1].repeatDays)
        // A leftover v1 "mode" field is tolerated; 2.0 fields get safe defaults.
        assertTrue(decoded[1].qrCodes.isEmpty())
        assertEquals(false, decoded[1].preAlarmEnabled)
        assertEquals(true, decoded[1].tempAccessEnabled)
    }

    @Test
    fun invalidQrListsAndPreAlarmValuesAreSanitized() {
        val json = """[{"id":1,"hour":7,"minute":0,"preAlarmMin":999,"tempAccessVol":90,
            "qr":[{"id":"a","payload":"P1"},{"id":"a","payload":"P2"},{"id":"b","payload":"P1"},{"payload":"P3"},
                  {"id":"c","payload":"P4"},{"id":"d","payload":"P5"},{"id":"e","payload":"P6"}]}]"""
        val alarm = AlarmJson.decode(json).single()
        assertEquals(30, alarm.preAlarmMinutes) // not an allowed option -> default
        assertEquals(Alarm.MAX_TEMP_ACCESS_VOLUME, alarm.tempAccessVolumePercent)
        assertEquals(listOf("a", "c", "d"), alarm.qrCodes.map { it.id }) // dupes dropped, max 3
    }

    @Test
    fun garbageOrEmptyYieldsNoAlarms() {
        assertTrue(AlarmJson.decode(null).isEmpty())
        assertTrue(AlarmJson.decode("").isEmpty())
        assertTrue(AlarmJson.decode("{not json").isEmpty())
    }
}
