package com.wakeforce.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wakeforce.app.data.WakeForceDataStore
import com.wakeforce.app.math.Difficulty
import com.wakeforce.app.math.MathSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Global math challenge configuration (applies to every alarm). QR codes are per alarm
 * since 2.0; the old global code is migrated by [com.wakeforce.app.data.SchemaMigration].
 */
class SettingsRepository(context: Context) {

    private val store = WakeForceDataStore.get(context)

    val mathSettings: Flow<MathSettings> = store.data.map { p ->
        val defaults = MathSettings()
        MathSettings(
            questionCount = p[MATH_COUNT] ?: defaults.questionCount,
            minValue = p[MATH_MIN] ?: defaults.minValue,
            maxValue = p[MATH_MAX] ?: defaults.maxValue,
            difficulty = p[MATH_DIFFICULTY]
                ?.let { name -> Difficulty.entries.firstOrNull { it.name == name } }
                ?: defaults.difficulty,
        ).sanitized()
    }.distinctUntilChanged()

    suspend fun saveMathSettings(settings: MathSettings) {
        val s = settings.sanitized()
        store.edit {
            it[MATH_COUNT] = s.questionCount
            it[MATH_MIN] = s.minValue
            it[MATH_MAX] = s.maxValue
            it[MATH_DIFFICULTY] = s.difficulty.name
        }
    }

    private companion object {
        val MATH_COUNT = intPreferencesKey("math_count")
        val MATH_MIN = intPreferencesKey("math_min")
        val MATH_MAX = intPreferencesKey("math_max")
        val MATH_DIFFICULTY = stringPreferencesKey("math_difficulty")
    }
}
