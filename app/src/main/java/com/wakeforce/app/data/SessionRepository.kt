package com.wakeforce.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.wakeforce.app.alarm.AlarmSession
import com.wakeforce.app.alarm.WakeStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The persisted ringing session (at most one) and wake-up statistics. The session is the
 * single source of truth for the ringing service and alarm screen, so challenge progress and
 * temporary-access timing survive the Activity, process death and reboots.
 */
class SessionRepository(context: Context) {

    private val store = WakeForceDataStore.get(context)

    val session: Flow<AlarmSession?> = store.data.map { SessionJson.decode(it[KEY_SESSION]) }.distinctUntilChanged()
    val stats: Flow<WakeStats> = store.data.map { SessionJson.decodeStats(it[KEY_STATS]) }.distinctUntilChanged()

    suspend fun get(): AlarmSession? = session.first()

    suspend fun set(session: AlarmSession?) {
        store.edit { if (session == null) it.remove(KEY_SESSION) else it[KEY_SESSION] = SessionJson.encode(session) }
    }

    /** Atomically stores a completed session together with the updated statistics. */
    suspend fun setWithStats(session: AlarmSession, transform: (WakeStats) -> WakeStats) {
        store.edit {
            it[KEY_SESSION] = SessionJson.encode(session)
            it[KEY_STATS] = SessionJson.encodeStats(transform(SessionJson.decodeStats(it[KEY_STATS])))
        }
    }

    private companion object {
        val KEY_SESSION = stringPreferencesKey("active_session")
        val KEY_STATS = stringPreferencesKey("wake_stats")
    }
}
