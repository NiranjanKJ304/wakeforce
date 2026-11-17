package com.wakeforce.app.data

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wakeforce.app.qr.QrRegistry
import org.json.JSONArray

/**
 * v1 -> v2. Runs once, atomically, before the first read; existing alarms are never dropped.
 * v1 had a single global QR code; v2 stores QR codes per alarm. A v1 QR-mode alarm keeps its
 * QR mode with the old printed code as its first registered code; a v1 MATH alarm stays MATH
 * (the per-alarm mode itself is decoded by [AlarmJson]).
 */
internal object SchemaMigration : DataMigration<Preferences> {

    const val CURRENT_VERSION = 2
    val SCHEMA_VERSION = intPreferencesKey("schema_version")
    private val ALARMS = stringPreferencesKey("alarms")
    private val LEGACY_QR_PAYLOAD = stringPreferencesKey("qr_payload")

    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        (currentData[SCHEMA_VERSION] ?: 1) < CURRENT_VERSION

    override suspend fun migrate(currentData: Preferences): Preferences = migratePreferences(currentData)

    override suspend fun cleanUp() = Unit

    /** Pure, unit-tested transform. */
    fun migratePreferences(prefs: Preferences, newId: () -> String = QrRegistry::newId): Preferences {
        val out = prefs.toMutablePreferences()
        val raw = prefs[ALARMS]
        val legacyPayload = prefs[LEGACY_QR_PAYLOAD]
        if (raw != null) {
            val qrModeIds = legacyQrModeIds(raw)
            val migrated = AlarmJson.decode(raw).map { alarm ->
                if (legacyPayload != null && alarm.id in qrModeIds && alarm.qrCodes.isEmpty()) {
                    alarm.copy(qrCodes = listOf(RegisteredQr(newId(), "Code 1", legacyPayload)))
                } else alarm
            }
            out[ALARMS] = AlarmJson.encode(migrated)
        }
        out.remove(LEGACY_QR_PAYLOAD)
        out[SCHEMA_VERSION] = CURRENT_VERSION
        return out.toPreferences()
    }

    private fun legacyQrModeIds(raw: String): Set<Int> = runCatching {
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.takeIf { it.optString("mode") == "QR" }?.optInt("id")
        }.toSet()
    }.getOrDefault(emptySet())
}
