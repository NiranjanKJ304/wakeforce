package com.wakeforce.app.ui

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.wakeforce.app.alarm.AlarmController
import com.wakeforce.app.alarm.AlarmTimeCalculator
import com.wakeforce.app.alarm.ConfirmPlan
import com.wakeforce.app.alarm.SessionMachine
import com.wakeforce.app.alarm.SyncAction
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.ui.screens.challengeSummary
import com.wakeforce.app.service.AlarmNotifications
import com.wakeforce.app.settings.SettingsRepository
import com.wakeforce.app.ui.components.timeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * "Do you still need this alarm?" for one specific occurrence. YES keeps it, NO skips only
 * that occurrence. Closing the screen or ignoring it changes nothing: the alarm still rings.
 */
class PreAlarmActivity : ComponentActivity() {

    private val controller by lazy { AlarmController(this) }
    private var busy by mutableStateOf(false)

    /** Which alarm occurrence this screen is asking about; replaced if another prompt arrives. */
    private var request by mutableStateOf(-1 to "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        enableEdgeToEdge()
        request = readRequest(intent)

        setContent {
            WakeForceTheme {
                val (alarmId, occurrence) = request
                val state by produceState<PreAlarmData?>(null, request) {
                    value = null
                    val alarm = controller.preAlarmToShow(alarmId, occurrence)
                    val questions = SettingsRepository(this@PreAlarmActivity).mathSettings.first().questionCount
                    value = PreAlarmData(alarm, questions)
                }
                PreAlarmScreen(
                    data = state,
                    occurrence = occurrence,
                    busy = busy,
                    onKeep = { answer(alarmId, occurrence, keep = true) },
                    onSkip = { skipAfterUnlock(alarmId, occurrence) },
                    onClose = { finish() },
                )
            }
        }
    }

    /**
     * singleTask: a prompt for another alarm arriving while this screen is open is delivered
     * here. Show the newest one; the earlier alarm simply rings as planned if unanswered.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        busy = false
        request = readRequest(intent)
    }

    private fun readRequest(intent: Intent): Pair<Int, String> =
        intent.getIntExtra(EXTRA_ALARM_ID, -1) to intent.getStringExtra(EXTRA_OCCURRENCE).orEmpty()

    /** Skipping lowers your guard, so on a locked phone it first asks you to unlock. */
    private fun skipAfterUnlock(alarmId: Int, occurrence: String) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = answer(alarmId, occurrence, keep = false)
            })
        } else {
            answer(alarmId, occurrence, keep = false)
        }
    }

    private fun answer(alarmId: Int, occurrence: String, keep: Boolean) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val text = when (val plan = controller.confirmPreAlarm(alarmId, occurrence, keep)) {
                ConfirmPlan.Stale -> "This reminder is no longer current. Nothing was changed."
                is ConfirmPlan.Confirmed -> "Alarm confirmed. It will ring at ${format(plan.alarm)}."
                is ConfirmPlan.Skipped -> when (val next = plan.action) {
                    is SyncAction.Arm -> "Skipped this time. Next: ${next.at.format(DateTimeFormatter.ofPattern("EEE"))} " +
                        next.at.format(timeFormatter(this@PreAlarmActivity))
                    else -> "Skipped. This one-time alarm is now off (it is still saved)."
                }
            }
            AlarmNotifications.cancelPreAlarm(this@PreAlarmActivity, alarmId)
            Toast.makeText(this@PreAlarmActivity, text, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun format(alarm: Alarm) = LocalTime.of(alarm.hour, alarm.minute).format(timeFormatter(this))

    companion object {
        private const val EXTRA_ALARM_ID = "alarm_id"
        private const val EXTRA_OCCURRENCE = "occurrence"

        fun intent(context: Context, alarmId: Int, occurrence: String): Intent =
            Intent(context, PreAlarmActivity::class.java)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .putExtra(EXTRA_OCCURRENCE, occurrence)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
    }
}

private class PreAlarmData(val alarm: Alarm?, val mathQuestions: Int)

@Composable
private fun PreAlarmScreen(
    data: PreAlarmData?,
    occurrence: String,
    busy: Boolean,
    onKeep: () -> Unit,
    onSkip: () -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (data == null) {
            CircularProgressIndicator()
            return@Column
        }
        val alarm = data.alarm
        if (alarm == null) {
            Text("This reminder is no longer current.", textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onClose) { Text("Close") }
            return@Column
        }
        val at = AlarmTimeCalculator.occurrenceTime(occurrence, ZonedDateTime.now().zone)
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val time = at?.format(timeFormatter(context)) ?: ""
            Text("Do you need your $time alarm?", fontSize = 28.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Text(time, fontSize = 64.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.primary)
            Text(at?.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")) ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Challenge: " + when (SessionMachine.effectiveMode(alarm)) {
                    ChallengeMode.QR -> challengeSummary(alarm)
                    ChallengeMode.MATH -> "${data.mathQuestions} math questions"
                    ChallengeMode.MATH_AND_QR -> "${data.mathQuestions} math questions, then " + challengeSummary(alarm).removePrefix("Math + ")
                },
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onKeep, enabled = !busy, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("YES, I NEED IT", fontSize = 18.sp)
            }
            OutlinedButton(onClick = onSkip, enabled = !busy, modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("NO, I DON'T NEED IT", fontSize = 18.sp)
            }
            Text(
                "NO skips only this occurrence${if (alarm.isRepeating) "; later days still ring" else ""}. " +
                    "If you don't answer, the alarm rings as planned.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
