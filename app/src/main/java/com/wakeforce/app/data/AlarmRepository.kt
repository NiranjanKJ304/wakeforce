package com.wakeforce.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wakeforce.app.alarm.AlarmPlanner
import com.wakeforce.app.alarm.UpsertResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class AlarmRepository(context: Context) {

    private val store = WakeForceDataStore.get(context)

    val alarms: Flow<List<Alarm>> = store.data
        .map { AlarmJson.decode(it[KEY_ALARMS]) }
        .distinctUntilChanged()

    suspend fun getAll(): List<Alarm> = alarms.first()

    suspend fun get(id: Int): Alarm? = getAll().firstOrNull { it.id == id }

    /**
     * Inserts (id == NEW_ID, assigning a fresh, never-reused id) or replaces an alarm.
     * Returns null if another alarm already has the same schedule. The duplicate check runs
     * inside the DataStore transaction, so concurrent/double saves cannot both succeed.
     */
    suspend fun upsert(alarm: Alarm): Alarm? {
        var saved: Alarm? = null
        store.edit { prefs ->
            val result = AlarmPlanner.upsert(
                AlarmJson.decode(prefs[KEY_ALARMS]),
                alarm,
                prefs[KEY_NEXT_ID] ?: 1,
            )
            if (result is UpsertResult.Saved) {
                prefs[KEY_ALARMS] = AlarmJson.encode(result.alarms)
                prefs[KEY_NEXT_ID] = result.nextId
                saved = result.saved
            }
        }
        return saved
    }

    /** Atomically transforms one stored alarm; returns the new value or null if it no longer exists. */
    suspend fun update(id: Int, transform: (Alarm) -> Alarm): Alarm? {
        var updated: Alarm? = null
        store.edit { prefs ->
            val list = AlarmJson.decode(prefs[KEY_ALARMS]).toMutableList()
            val index = list.indexOfFirst { it.id == id }
            if (index >= 0) {
                val changed = transform(list[index]).copy(id = id)
                list[index] = changed
                prefs[KEY_ALARMS] = AlarmJson.encode(list)
                updated = changed
            }
        }
        return updated
    }

    suspend fun delete(id: Int) {
        store.edit { prefs ->
            val list = AlarmJson.decode(prefs[KEY_ALARMS]).filterNot { it.id == id }
            prefs[KEY_ALARMS] = AlarmJson.encode(list)
        }
    }

    /**
     * Returns true exactly once per device boot (LOCKED_BOOT_COMPLETED and BOOT_COMPLETED both
     * arrive), so missed alarms get a single catch-up ring.
     */
    suspend fun claimBootCatchUp(bootCount: Int): Boolean {
        if (bootCount < 0) return true // BOOT_COUNT unavailable on this ROM; a repeat is harmless.
        var claimed = false
        store.edit { prefs ->
            if (prefs[KEY_LAST_BOOT] != bootCount) {
                prefs[KEY_LAST_BOOT] = bootCount
                claimed = true
            }
        }
        return claimed
    }

    private companion object {
        val KEY_ALARMS = stringPreferencesKey("alarms")
        val KEY_NEXT_ID = intPreferencesKey("next_alarm_id")
        val KEY_LAST_BOOT = intPreferencesKey("last_catch_up_boot")
    }
}
