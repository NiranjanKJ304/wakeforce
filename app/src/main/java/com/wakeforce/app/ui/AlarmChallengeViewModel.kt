package com.wakeforce.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wakeforce.app.alarm.AccessResult
import com.wakeforce.app.alarm.AlarmController
import com.wakeforce.app.alarm.AlarmSession
import com.wakeforce.app.alarm.MathResult
import com.wakeforce.app.alarm.QrResult
import com.wakeforce.app.alarm.SessionState
import com.wakeforce.app.alarm.WakeStats
import com.wakeforce.app.data.SessionRepository
import com.wakeforce.app.math.MathSession
import com.wakeforce.app.math.MathSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state for the alarm screen; all real progress lives in the persisted session. */
sealed interface SessionView {
    data object Loading : SessionView
    data class Loaded(val session: AlarmSession?) : SessionView
}

/**
 * Thin layer over [AlarmController]: every answer and scan is validated and persisted by the
 * controller, so progress survives this ViewModel, the Activity and the process.
 */
class AlarmChallengeViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = AlarmController(app)
    private val repo = SessionRepository(app)

    val session: StateFlow<SessionView> = repo.session
        .map<AlarmSession?, SessionView> { SessionView.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionView.Loading)
    val stats: StateFlow<WakeStats> = repo.stats.stateIn(viewModelScope, SharingStarted.Eagerly, WakeStats())

    var input by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Backup challenge used only when storage failed (see AlarmService degraded mode). */
    var degradedMath by mutableStateOf<MathSession?>(null)
        private set

    fun onDigit(digit: Char) {
        if (input.length < MAX_INPUT_LENGTH) input += digit
        message = null
    }

    fun onBackspace() {
        input = input.dropLast(1)
    }

    fun onSubmitMath(session: AlarmSession) {
        val answer = input
        input = ""
        viewModelScope.launch {
            message = when (controller.submitMath(session.occurrenceId, answer)) {
                MathResult.REJECTED -> if (answer.isBlank()) "Enter an answer first" else "Incorrect. Try this one."
                MathResult.MATH_COMPLETE -> "Math done! Now scan your QR codes."
                MathResult.NOT_ALLOWED -> null
                MathResult.CORRECT, MathResult.SESSION_COMPLETE -> null
            }
        }
    }

    fun onQrScanned(session: AlarmSession, text: String) {
        viewModelScope.launch {
            message = when (controller.submitQr(session.occurrenceId, text)) {
                QrResult.WRONG_CODE -> "That isn't one of this alarm's codes."
                QrResult.ALREADY_SCANNED -> "Already scanned. Find the next code."
                QrResult.ACCEPTED -> "Code accepted. Scan the next one."
                QrResult.NOT_ALLOWED, QrResult.SESSION_COMPLETE -> null
            }
        }
    }

    fun requestTemporaryAccess(session: AlarmSession, onStarted: () -> Unit) {
        viewModelScope.launch {
            when (controller.startTemporaryAccess(session.occurrenceId)) {
                AccessResult.STARTED -> onStarted()
                AccessResult.LIMIT_REACHED -> message = "No temporary access left for this alarm."
                AccessResult.DISABLED -> message = "Temporary access is turned off for this alarm."
                else -> Unit
            }
        }
    }

    fun returnFromAccess(session: AlarmSession) {
        viewModelScope.launch { controller.endTemporaryAccess(session.occurrenceId, userReturned = true) }
    }

    /** Safety net when the screen is reopened after the window ended while the CPU slept. */
    fun checkAccessExpired(session: AlarmSession?) {
        val endsAt = session?.accessEndsAt ?: return
        if (session.state == SessionState.TEMPORARY_ACCESS && endsAt <= System.currentTimeMillis()) {
            viewModelScope.launch { controller.endTemporaryAccess(session.occurrenceId, userReturned = false) }
        }
    }

    fun startDegraded() {
        if (degradedMath == null) degradedMath = MathSession.fallback(MathSettings())
    }

    fun onSubmitDegraded(): Boolean {
        val math = degradedMath ?: return false
        val answer = input
        input = ""
        val next = math.submit(answer)
        degradedMath = next
        message = next.message
        return next.solved
    }

    private companion object {
        const val MAX_INPUT_LENGTH = 6
    }
}
