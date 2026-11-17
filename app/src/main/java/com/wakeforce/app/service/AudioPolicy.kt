package com.wakeforce.app.service

import com.wakeforce.app.alarm.AlarmSession
import com.wakeforce.app.alarm.SessionState

enum class AudioMode { FULL, REDUCED, SILENT, STOPPED }

/**
 * What the alarm audio must be doing for a given persisted session. Pure and unit-tested.
 * There is deliberately no time-based rule: audio only stops when the session is no longer
 * active (challenge completed, or an interrupted session marked MISSED after a long power-off).
 * The 5-minute temporary-access window only lowers/mutes it and never ends it.
 */
object AudioPolicy {
    fun forSession(session: AlarmSession?): AudioMode = when {
        session == null || !session.isActive -> AudioMode.STOPPED
        session.state == SessionState.TEMPORARY_ACCESS ->
            if (session.sound.tempAccessVolumePercent == 0) AudioMode.SILENT else AudioMode.REDUCED
        else -> AudioMode.FULL
    }
}
