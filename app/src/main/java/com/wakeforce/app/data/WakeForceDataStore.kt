package com.wakeforce.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile

/**
 * Single process-wide DataStore holding alarms and settings.
 *
 * It lives in *device-protected* storage so it can be read before the user unlocks
 * the phone after a reboot. That lets [com.wakeforce.app.receiver.BootReceiver]
 * re-arm alarms on LOCKED_BOOT_COMPLETED and lets an alarm ring even if the phone
 * restarted overnight (e.g. after a system update) and was never unlocked.
 */
object WakeForceDataStore {
    @Volatile
    private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> =
        instance ?: synchronized(this) {
            instance ?: PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                migrations = listOf(SchemaMigration),
                produceFile = {
                    context.applicationContext
                        .createDeviceProtectedStorageContext()
                        .preferencesDataStoreFile("wakeforce")
                },
            ).also { instance = it }
        }

    /** Tests only: each Robolectric test gets a fresh app sandbox and therefore a new file. */
    @androidx.annotation.VisibleForTesting
    internal fun resetForTests() {
        instance = null
    }
}
