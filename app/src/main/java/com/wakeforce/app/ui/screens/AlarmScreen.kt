package com.wakeforce.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeforce.app.alarm.AlarmSession
import com.wakeforce.app.alarm.SessionState
import com.wakeforce.app.alarm.WakeStats
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.settings.SystemSettings
import com.wakeforce.app.ui.AlarmChallengeViewModel
import com.wakeforce.app.ui.components.NumberKeypad
import com.wakeforce.app.ui.components.QrScannerView
import com.wakeforce.app.ui.components.rememberCameraPermissionState
import com.wakeforce.app.ui.components.rememberMinuteClock
import com.wakeforce.app.ui.components.timeFormatter
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter

@Composable
private fun AlarmFrame(content: @Composable ColumnScope.() -> Unit) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val formatter = remember { timeFormatter(context) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(now.format(formatter), fontSize = 72.sp, fontWeight = FontWeight.Light)
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Ringing: step 1 math, then step 2 every QR code. No dismiss or snooze button exists. */
@Composable
fun AlarmScreen(vm: AlarmChallengeViewModel, session: AlarmSession, onAccessStarted: () -> Unit) {
    AlarmFrame {
        val qrStep = session.state == SessionState.RINGING_QR
        val codes = if (session.qrCodes.size > 1) "your ${session.qrCodes.size} QR codes" else "your QR code"
        Text(
            when (session.mode) {
                ChallengeMode.QR -> "Scan $codes to stop the alarm"
                ChallengeMode.MATH -> "Solve the math to stop the alarm"
                ChallengeMode.MATH_AND_QR -> if (qrStep) "Step 2 of 2 · Scan $codes" else "Step 1 of 2 · Solve the math"
            },
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        if (qrStep) QrStep(vm, session) else MathStep(vm, session)

        if (session.sound.tempAccessEnabled && session.accessWindowsLeft > 0) {
            OutlinedButton(onClick = { vm.requestTemporaryAccess(session, onAccessStarted) }) {
                Text("Temporary access – 5 minutes (${session.accessWindowsLeft} left)")
            }
        }
    }
}

@Composable
private fun MathStep(vm: AlarmChallengeViewModel, session: AlarmSession) {
    val question = session.math.current
    if (question == null) {
        CircularProgressIndicator()
        return
    }
    Text(
        "Question ${session.math.index + 1} of ${session.math.questions.size}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(question.text, fontSize = 40.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    InputBox(vm.input)
    Text(vm.message ?: " ", color = MaterialTheme.colorScheme.error)
    NumberKeypad(
        onDigit = vm::onDigit,
        onBackspace = vm::onBackspace,
        onSubmit = { vm.onSubmitMath(session) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun InputBox(input: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            input.ifEmpty { "?" },
            fontSize = 36.sp,
            color = if (input.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun QrStep(vm: AlarmChallengeViewModel, session: AlarmSession) {
    val context = LocalContext.current
    val camera = rememberCameraPermissionState()
    var cameraError by remember { mutableStateOf<String?>(null) }

    session.qrCodes.forEach { code ->
        val done = code.id in session.scannedQrIds
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (done) "✓" else "○", fontSize = 22.sp,
                color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))
            Text(code.label, Modifier.weight(1f), fontSize = 18.sp)
            Text(if (done) "Scanned" else "Waiting", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text("${session.scannedQrIds.size} of ${session.qrCodes.size} scanned", color = MaterialTheme.colorScheme.onSurfaceVariant)

    when {
        cameraError != null -> Text(
            "$cameraError. Use temporary access to check the camera or its permission in Settings.",
            color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center,
        )
        camera.granted -> QrScannerView(
            onCode = { vm.onQrScanned(session, it) },
            onError = { cameraError = it },
            modifier = Modifier.size(280.dp).clip(RoundedCornerShape(20.dp)),
        )
        else -> CameraPermissionPrompt(
            denied = camera.denied,
            onRequest = camera.request,
            onOpenSettings = { SystemSettings.open(context, SystemSettings.appDetails(context)) },
        )
    }
    Text(vm.message ?: " ", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
}

/** Temporary access: alarm quieted, session still active, countdown from the persisted end time. */
@Composable
fun AccessScreen(session: AlarmSession, onReturn: () -> Unit) {
    val endsAt = session.accessEndsAt ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (now < endsAt) {
            delay(1_000 - now % 1_000)
            now = System.currentTimeMillis()
        }
    }
    val left = ((endsAt - now).coerceAtLeast(0) + 999) / 1000
    AlarmFrame {
        Text("Temporary access", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        Text("%d:%02d".format(left / 60, left % 60), fontSize = 56.sp, fontWeight = FontWeight.Light)
        Text(
            "The alarm is still active and resumes at full volume when the countdown ends. " +
                "Your challenge progress is kept.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onReturn, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Return to the challenge now") }
    }
}

@Composable
fun SuccessScreen(session: AlarmSession, stats: WakeStats, onDone: () -> Unit) {
    LaunchedEffect(session.occurrenceId) {
        delay(15_000)
        onDone()
    }
    val took = ((session.completedAt ?: session.startedAt) - session.startedAt) / 1000
    AlarmFrame {
        Text("You're up!", fontSize = 40.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Text("Alarm stopped after %d min %02d s.".format(took / 60, took % 60), textAlign = TextAlign.Center)
        Text("Wake-ups completed: ${stats.completedCount}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
    }
}

/** Only if storage failed: a longer math challenge so the alarm can still be stopped. */
@Composable
fun DegradedScreen(vm: AlarmChallengeViewModel, onSolved: () -> Unit) {
    LaunchedEffect(Unit) { vm.startDegraded() }
    val math = vm.degradedMath
    AlarmFrame {
        Text("Solve to stop the alarm", color = MaterialTheme.colorScheme.primary)
        val question = math?.current ?: return@AlarmFrame
        Text("Question ${math.index + 1} of ${math.questions.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(question.text, fontSize = 40.sp, fontWeight = FontWeight.Medium)
        InputBox(vm.input)
        Text(vm.message ?: " ", color = MaterialTheme.colorScheme.error)
        NumberKeypad(
            onDigit = vm::onDigit,
            onBackspace = vm::onBackspace,
            onSubmit = { if (vm.onSubmitDegraded()) onSolved() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Success screen is shown only right after completion, never for an old session. */
fun isFreshCompletion(session: AlarmSession, nowMs: Long = System.currentTimeMillis()): Boolean =
    session.state == SessionState.COMPLETED && nowMs - (session.completedAt ?: 0) < SUCCESS_SCREEN_WINDOW_MS

private const val SUCCESS_SCREEN_WINDOW_MS = 2 * 60 * 1000L
