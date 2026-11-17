package com.wakeforce.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.wakeforce.app.alarm.AlarmController
import com.wakeforce.app.alarm.AlarmSession
import com.wakeforce.app.alarm.SessionSound
import com.wakeforce.app.alarm.SessionState
import com.wakeforce.app.data.SessionRepository
import com.wakeforce.app.ui.AlarmActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Foreground service that exists *only while an alarm session is active*. It follows the
 * persisted [AlarmSession] (the single source of truth): full alarm while ringing, the
 * configured reduced level (or silence) during a temporary-access window, and a full stop
 * when the session completes. Challenge progress lives in storage, not here, so it survives
 * the Activity, process death and reboot.
 *
 * Re-arming the next occurrence is done by [com.wakeforce.app.receiver.AlarmReceiver], so it
 * happens even if this service cannot start.
 */
class AlarmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { AlarmController(this) }
    private val sessions by lazy { SessionRepository(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var tone: ToneGenerator? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var originalVolume: Int? = null

    private var observer: Job? = null
    private var ringingOccurrence: String? = null
    private var reduced = false
    private var degraded = false
    private var accessCheck: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FIRE -> onFire(intent.getIntExtra(EXTRA_ALARM_ID, -1), intent.getStringExtra(EXTRA_OCCURRENCE))
            ACTION_DISMISS_DEGRADED -> if (degraded) {
                degraded = false
                releaseRinging()
                stopNow()
            }
            else -> if (observer == null && !degraded) stopNow()
        }
        // If the process is killed mid-ring, Android redelivers the FIRE intent; the persisted
        // session is then resumed (never duplicated).
        return START_REDELIVER_INTENT
    }

    private fun onFire(alarmId: Int, occurrence: String?) {
        // Must be promoted to the foreground promptly after startForegroundService().
        if (!enterForeground(alarmId, occurrence)) return
        AlarmNotifications.cancelFallback(this)
        if (alarmId < 0) {
            observe()
            return
        }
        scope.launch {
            val decision = try {
                controller.startSession(alarmId, occurrence)
            } catch (e: Exception) {
                null
            }
            if (decision == null) startDegraded(alarmId) else observe()
        }
    }

    /** Ring/stop/reduce strictly from the persisted session. */
    private fun observe() {
        if (observer != null) return
        observer = scope.launch { sessions.session.collect { render(it) } }
    }

    private fun render(session: AlarmSession?) {
        val audio = AudioPolicy.forSession(session)
        if (session == null || audio == AudioMode.STOPPED) {
            if (!degraded) {
                releaseRinging()
                stopNow()
            }
            return
        }
        if (session.occurrenceId != ringingOccurrence) {
            // New session (or the next queued alarm): ring with *its* captured sound settings.
            releaseRinging()
            ringingOccurrence = session.occurrenceId
            reduced = false
            startRinging(session.sound)
            showAlarmScreen(session, initial = true)
        }
        if (audio != AudioMode.FULL) {
            val endsAt = session.accessEndsAt ?: System.currentTimeMillis()
            if (endsAt <= System.currentTimeMillis()) {
                scope.launch { controller.endTemporaryAccess(session.occurrenceId, userReturned = false) }
                return
            }
            if (!reduced) {
                reduced = true
                reduceAudio(session.sound)
                updateNotification(session)
                // AlarmManager is the reliable wake-up; it's re-armed here in case this is a
                // post-reboot recovery. The handler is a free in-process backup (no CPU while asleep).
                scope.launch { controller.rearmAccessTimer() }
                scheduleAccessCheck(session, endsAt)
            }
        } else if (reduced) {
            reduced = false
            cancelAccessCheck()
            restoreAudio(session.sound)
            updateNotification(session)
            showAlarmScreen(session, initial = false)
        }
    }

    private fun enterForeground(alarmId: Int, occurrence: String?): Boolean = try {
        ServiceCompat.startForeground(
            this,
            AlarmNotifications.RINGING_ID,
            AlarmNotifications.ringing(this, alarmId, occurrence = occurrence),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
        true
    } catch (e: Exception) {
        if (alarmId >= 0) AlarmNotifications.showFallback(this, alarmId, occurrence)
        stopSelf()
        false
    }

    private fun updateNotification(session: AlarmSession) {
        try {
            getSystemService(NotificationManager::class.java).notify(
                AlarmNotifications.RINGING_ID,
                AlarmNotifications.ringing(this, session.alarmId, occurrence = session.occurrence,
                    accessEndsAt = session.accessEndsAt.takeIf { session.state == SessionState.TEMPORARY_ACCESS }),
            )
        } catch (_: SecurityException) {
        }
    }

    private fun showAlarmScreen(session: AlarmSession, initial: Boolean) {
        // The full-screen intent is the primary path; this direct start succeeds on
        // devices/states where background activity starts are allowed.
        try {
            startActivity(AlarmActivity.intent(this, session.alarmId, false, session.occurrence))
        } catch (_: Exception) {
        }
        // Returning from temporary access: post a fresh full-screen prompt (an update of the
        // ongoing notification would not re-launch the screen).
        if (!initial) AlarmNotifications.showFallback(this, session.alarmId, session.occurrence)
    }

    private fun scheduleAccessCheck(session: AlarmSession, endsAt: Long) {
        cancelAccessCheck()
        val check = Runnable {
            scope.launch { controller.endTemporaryAccess(session.occurrenceId, userReturned = false) }
        }
        accessCheck = check
        handler.postDelayed(check, (endsAt - System.currentTimeMillis()).coerceAtLeast(0) + 250)
    }

    private fun cancelAccessCheck() {
        accessCheck?.let(handler::removeCallbacks)
        accessCheck = null
    }

    /** Storage unreadable: ring anyway with defaults; the screen shows a longer math challenge. */
    private fun startDegraded(alarmId: Int) {
        if (ringingOccurrence != null || degraded) return
        degraded = true
        _degradedAlarm.value = alarmId
        startRinging(SessionSound(100, null, vibrate = true, tempAccessEnabled = false, tempAccessVolumePercent = 0))
        try {
            startActivity(AlarmActivity.intent(this, alarmId, false, null))
        } catch (_: Exception) {
        }
    }

    // --- Audio / vibration ----------------------------------------------------------------------

    private val alarmAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun startRinging(sound: SessionSound) {
        acquireWakeLock()
        val audio = getSystemService(AudioManager::class.java)

        // Configurable loudness: temporarily set the alarm stream, restored when the session ends.
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = ceil(max * sound.volumePercent / 100.0).toInt().coerceIn(1, max)
            if (originalVolume == null) originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: SecurityException) {
            originalVolume = null
        }

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttributes)
            .build()
            .also { audio.requestAudioFocus(it) }

        val candidates = listOfNotNull(
            sound.soundUri?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        for (uri in candidates) {
            player = tryPlay(uri)
            if (player != null) break
        }
        if (player == null) startTone(ToneGenerator.MAX_VOLUME)
        if (sound.vibrate) startVibration()
        playbackStarts++
        audioModeForTest = AudioMode.FULL
    }

    /** Temporary access: configured reduced level, or silence; no vibration; CPU may sleep. */
    private fun reduceAudio(sound: SessionSound) {
        val level = sound.tempAccessVolumePercent.coerceIn(0, 100)
        player?.let {
            if (level == 0) runCatching { it.pause() } else it.setVolume(level / 100f, level / 100f)
        }
        if (tone != null || player == null) {
            stopTone()
            if (level > 0) startTone(level)
        }
        vibrator?.cancel()
        releaseWakeLock()
        audioModeForTest = if (level == 0) AudioMode.SILENT else AudioMode.REDUCED
    }

    private fun restoreAudio(sound: SessionSound) {
        acquireWakeLock()
        player?.let {
            it.setVolume(1f, 1f)
            if (!it.isPlaying) runCatching { it.start() }
        }
        if (player == null) {
            stopTone()
            startTone(ToneGenerator.MAX_VOLUME)
        }
        if (sound.vibrate) startVibration()
        audioModeForTest = AudioMode.FULL
    }

    private fun tryPlay(uri: Uri): MediaPlayer? {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(alarmAttributes)
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            // A decoder/media-server error mid-ring would otherwise leave the alarm silent.
            mp.setOnErrorListener { failed, _, _ ->
                if (player === failed && ringingOccurrence != null) {
                    failed.release()
                    player = null
                    if (!reduced) startTone(ToneGenerator.MAX_VOLUME)
                }
                true
            }
            mp.prepare()
            mp.start()
            mp
        } catch (e: Exception) {
            mp.release()
            null
        }
    }

    /** Last resort (e.g. custom tone unreadable before first unlock): built-in repeating ring. */
    private fun startTone(volume: Int) {
        if (tone != null) return
        tone = try {
            ToneGenerator(AudioManager.STREAM_ALARM, volume).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) }
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun stopTone() {
        tone?.release()
        tone = null
    }

    private fun startVibration() {
        val v = vibrator ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (!v.hasVibrator()) return
        vibrator = v
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeForce:ringing")
            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Idempotent: stops audio and vibration and restores the user's alarm volume. */
    private fun releaseRinging() {
        cancelAccessCheck()
        AlarmNotifications.cancelFallback(this)
        player?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        player = null
        stopTone()
        vibrator?.cancel()
        vibrator = null

        val audio = getSystemService(AudioManager::class.java)
        originalVolume?.let {
            try {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0)
            } catch (_: SecurityException) {
            }
        }
        originalVolume = null
        focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        releaseWakeLock()
        ringingOccurrence = null
        reduced = false
        audioModeForTest = AudioMode.STOPPED
    }

    private fun stopNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseRinging()
        _degradedAlarm.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_FIRE = "com.wakeforce.app.action.RING"
        private const val ACTION_DISMISS_DEGRADED = "com.wakeforce.app.action.DISMISS_DEGRADED"
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_OCCURRENCE = "occurrence"

        /** Safety net only; the media player holds its own wake lock while playing. */
        private const val WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L

        /** Test observability only: how many times alarm audio was started, and its current mode. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var playbackStarts = 0

        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var audioModeForTest = AudioMode.STOPPED

        private val _degradedAlarm = MutableStateFlow<Int?>(null)

        /** Non-null only when storage failed and the alarm rings without a persisted session. */
        val degradedAlarm: StateFlow<Int?> = _degradedAlarm.asStateFlow()

        fun start(context: Context, alarmId: Int, occurrence: String?) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AlarmService::class.java)
                    .setAction(ACTION_FIRE)
                    .putExtra(EXTRA_ALARM_ID, alarmId)
                    .apply { occurrence?.let { putExtra(EXTRA_OCCURRENCE, it) } },
            )
        }

        /** Degraded mode only (storage failure): stop after the backup math challenge. */
        fun dismissDegraded(context: Context) {
            _degradedAlarm.value = null
            try {
                context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_DISMISS_DEGRADED))
            } catch (_: Exception) {
            }
        }
    }
}
