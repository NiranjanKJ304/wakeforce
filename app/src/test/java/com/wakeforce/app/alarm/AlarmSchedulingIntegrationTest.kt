package com.wakeforce.app.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.AlarmRepository
import com.wakeforce.app.data.WakeForceDataStore
import com.wakeforce.app.receiver.AlarmReceiver
import com.wakeforce.app.receiver.BootReceiver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * Exercises the real scheduling path (controller -> AlarmManager + DataStore) on the
 * Robolectric Android framework. Verifies registrations, not physical-device delivery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmSchedulingIntegrationTest {

    private lateinit var context: Context
    private lateinit var shadowAlarms: ShadowAlarmManager
    private lateinit var controller: AlarmController
    private lateinit var repo: AlarmRepository

    private val zone = ZoneId.of("Asia/Kolkata")
    private val friday6am = ZonedDateTime.of(2026, 11, 6, 6, 0, 0, 0, zone)
    private val weekdays = DayOfWeek.entries.take(5).toSet()

    @Before
    fun setUp() {
        WakeForceDataStore.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowAlarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        controller = AlarmController(context)
        repo = AlarmRepository(context)
    }

    private fun registrations() = shadowAlarms.scheduledAlarms

    private fun savedAlarm(alarm: Alarm): Alarm = runBlocking {
        (controller.save(alarm, friday6am) as AlarmController.SaveOutcome.Saved).alarm
    }

    @Test
    fun savingArmsOneExactAlarmClock() = runBlocking {
        val alarm = savedAlarm(Alarm(hour = 7, minute = 30))
        val regs = registrations()
        assertEquals(1, regs.size)
        assertEquals(friday6am.withHour(7).withMinute(30).toInstant().toEpochMilli(), regs[0].triggerAtMs)
        assertNotNull("must use setAlarmClock (Doze-exempt)", regs[0].alarmClockInfo)
        assertEquals(friday6am.withHour(7).withMinute(30).toInstant().toEpochMilli(), repo.get(alarm.id)!!.armedAt)
    }

    @Test
    fun editingAndResyncingNeverDuplicatesRegistrations() = runBlocking {
        val alarm = savedAlarm(Alarm(hour = 7, minute = 0, repeatDays = weekdays))
        controller.save(alarm.copy(minute = 15), friday6am)
        controller.syncAll(friday6am)
        controller.syncAll(friday6am)
        BootReceiver.handle(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(1, registrations().size)
    }

    @Test
    fun duplicateAlarmIsRejectedAtStorageLevel() = runBlocking {
        savedAlarm(Alarm(hour = 7, minute = 0))
        val second = controller.save(Alarm(hour = 7, minute = 0), friday6am)
        assertEquals(AlarmController.SaveOutcome.Duplicate, second)
        assertEquals(1, repo.getAll().size)
        assertEquals(1, registrations().size)
    }

    @Test
    fun disablingAndDeletingRemoveRegistration() = runBlocking {
        val a = savedAlarm(Alarm(hour = 7, minute = 0))
        val b = savedAlarm(Alarm(hour = 8, minute = 0))
        assertEquals(2, registrations().size)
        controller.setEnabled(a.id, false, friday6am)
        assertEquals(1, registrations().size)
        assertNull(repo.get(a.id)!!.armedAt)
        controller.delete(b.id)
        assertEquals(0, registrations().size)
        assertEquals(listOf(a.id), repo.getAll().map { it.id })
    }

    @Test
    fun rebootRestoresRegistrationsFromStorage() = runBlocking {
        // Repeating alarms: BootReceiver uses the real clock, and these stay armed whatever it is.
        savedAlarm(Alarm(hour = 22, minute = 0, repeatDays = DayOfWeek.entries.toSet()))
        savedAlarm(Alarm(hour = 5, minute = 0, repeatDays = weekdays))
        // Simulate the reboot wiping AlarmManager while storage survives.
        val am = context.getSystemService(AlarmManager::class.java)
        registrations().toList().forEach { am.cancel(it.operation!!) }
        assertEquals(0, registrations().size)

        BootReceiver.handle(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(2, registrations().size)
        assertTrue(registrations().all { it.alarmClockInfo != null })
        // The later BOOT_COMPLETED broadcast must not add more.
        BootReceiver.handle(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(2, registrations().size)
    }

    @Test
    fun firedRepeatingAlarmIsRearmedAndOneTimeIsSwitchedOff() = runBlocking {
        val daily = savedAlarm(Alarm(hour = 7, minute = 0, repeatDays = DayOfWeek.entries.toSet()))
        val once = savedAlarm(Alarm(hour = 7, minute = 30))
        val ringAt = friday6am.withHour(7).withSecond(1)

        controller.onFired(daily.id, ringAt)
        controller.onFired(once.id, ringAt.withMinute(30))

        val regs = registrations()
        assertEquals(1, regs.size)
        assertEquals(ringAt.plusDays(1).withSecond(0).toInstant().toEpochMilli(), regs[0].triggerAtMs)
        assertFalse(repo.get(once.id)!!.enabled)
        assertTrue(repo.get(daily.id)!!.enabled)
    }

    @Test
    fun timeZoneBroadcastUsesTheNewZoneFromTheIntent() {
        val intent = Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, "America/New_York")
        assertEquals(ZoneId.of("America/New_York"), BootReceiver.nowFor(intent).zone)
        val bogus = Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, "Not/AZone")
        assertEquals(ZoneId.systemDefault(), BootReceiver.nowFor(bogus).zone)
    }

    @Test
    fun timeZoneChangeRearmsAtLocalTimeInNewZone() = runBlocking {
        savedAlarm(Alarm(hour = 7, minute = 0, repeatDays = DayOfWeek.entries.toSet()))
        val ny = ZoneId.of("America/New_York")
        controller.syncAll(friday6am.withZoneSameInstant(ny))
        val trigger = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(registrations()[0].triggerAtMs), ny)
        assertEquals(7, trigger.hour)
        assertEquals(0, trigger.minute)
    }

    @Test
    fun alarmReceiverPendingIntentTargetsReceiverWithAlarmId() = runBlocking {
        val alarm = savedAlarm(Alarm(hour = 7, minute = 0))
        val fired = shadowOf(registrations()[0].operation).savedIntent
        assertEquals(AlarmReceiver.ACTION_FIRE, fired.action)
        assertEquals(alarm.id, fired.getIntExtra(AlarmReceiver.EXTRA_ALARM_ID, -1))
        assertEquals(AlarmReceiver::class.java.name, fired.component?.className)
    }
}
