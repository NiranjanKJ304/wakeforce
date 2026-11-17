package com.wakeforce.app.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wakeforce.app.alarm.SessionState
import com.wakeforce.app.service.AlarmService
import com.wakeforce.app.ui.screens.AccessScreen
import com.wakeforce.app.ui.screens.AlarmScreen
import com.wakeforce.app.ui.screens.DegradedScreen
import com.wakeforce.app.ui.screens.SuccessScreen
import com.wakeforce.app.ui.screens.isFreshCompletion
import kotlinx.coroutines.delay

/**
 * Full-screen ringing UI. Appears over the lock screen and turns the screen on. There is
 * deliberately no dismiss or snooze button and Back does nothing: the alarm stops only when
 * the math challenge *and* every registered QR code are completed. Everything shown comes
 * from the persisted session, so closing or recreating this screen loses nothing.
 */
class AlarmActivity : ComponentActivity() {

    private val vm: AlarmChallengeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            WakeForceTheme {
                val view by vm.session.collectAsStateWithLifecycle()
                val stats by vm.stats.collectAsStateWithLifecycle()
                val degraded by AlarmService.degradedAlarm.collectAsStateWithLifecycle()
                val session = (view as? SessionView.Loaded)?.session
                val active = session?.takeIf { it.isActive }

                LaunchedEffect(view, degraded) {
                    // Nothing to show (stale launch, or finished elsewhere): close shortly.
                    if (view is SessionView.Loaded && active == null && degraded == null &&
                        !(session != null && isFreshCompletion(session))
                    ) {
                        delay(4_000)
                        finish()
                    }
                }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    when {
                        active?.state == SessionState.TEMPORARY_ACCESS ->
                            AccessScreen(active, onReturn = { vm.returnFromAccess(active) })
                        active != null -> AlarmScreen(vm, active, onAccessStarted = { finish() })
                        degraded != null -> DegradedScreen(vm) {
                            AlarmService.dismissDegraded(this@AlarmActivity)
                            finish()
                        }
                        session != null && isFreshCompletion(session) ->
                            SuccessScreen(session, stats, onDone = { finish() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.checkAccessExpired((vm.session.value as? SessionView.Loaded)?.session)
        // Fallback path (service couldn't start from the background). Done once the activity
        // is visible, when starting a foreground service is always permitted. Idempotent: an
        // existing session for the occurrence is resumed, never duplicated.
        val intent = intent ?: return
        if (intent.getBooleanExtra(EXTRA_START_SERVICE, false)) {
            intent.removeExtra(EXTRA_START_SERVICE)
            val id = intent.getIntExtra(EXTRA_ALARM_ID, -1)
            if (id >= 0) {
                try {
                    AlarmService.start(this, id, intent.getStringExtra(EXTRA_OCCURRENCE))
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_OCCURRENCE = "occurrence"
        private const val EXTRA_START_SERVICE = "start_service"

        fun intent(context: Context, alarmId: Int, startServiceIfNeeded: Boolean, occurrence: String?): Intent =
            Intent(context, AlarmActivity::class.java)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_START_SERVICE, startServiceIfNeeded)
                .apply { occurrence?.let { putExtra(EXTRA_OCCURRENCE, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}
