package com.wakeforce.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.AlarmJson
import com.wakeforce.app.data.ChallengeMode
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.wakeforce.app.math.MathSettings
import com.wakeforce.app.qr.QrRegistry
import com.wakeforce.app.ui.components.WeekOrder
import com.wakeforce.app.ui.components.WfIcons
import com.wakeforce.app.ui.components.shortName
import com.wakeforce.app.ui.components.timeFormatter
import java.time.DayOfWeek
import java.time.LocalTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditAlarmScreen(
    initial: Alarm?,
    otherAlarms: List<Alarm>,
    mathSettings: MathSettings,
    onSave: (Alarm) -> Unit,
    onDelete: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenMathConfig: () -> Unit,
) {
    val context = LocalContext.current
    val base = initial ?: Alarm(hour = 7, minute = 0)
    val isNew = initial == null

    val timeState = rememberTimePickerState(base.hour, base.minute, DateFormat.is24HourFormat(context))
    var daysMask by rememberSaveable { mutableIntStateOf(base.repeatDays.toMask()) }
    var enabled by rememberSaveable { mutableStateOf(base.enabled || isNew) }
    var vibrate by rememberSaveable { mutableStateOf(base.vibrate) }
    var volume by rememberSaveable { mutableFloatStateOf(base.volumePercent.toFloat()) }
    var soundUri by rememberSaveable { mutableStateOf(base.soundUri) }
    var mode by rememberSaveable { mutableStateOf(base.mode) }
    var qrJson by rememberSaveable { mutableStateOf(AlarmJson.encodeQrString(base.qrCodes)) }
    var qrStartScan by rememberSaveable { mutableStateOf(false) }
    var preEnabled by rememberSaveable { mutableStateOf(base.preAlarmEnabled) }
    var preMinutes by rememberSaveable { mutableIntStateOf(base.preAlarmMinutes) }
    var tempEnabled by rememberSaveable { mutableStateOf(base.tempAccessEnabled) }
    var tempVolume by rememberSaveable { mutableFloatStateOf(base.tempAccessVolumePercent.toFloat()) }
    var editingQr by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val qrCodes = AlarmJson.decodeQrString(qrJson)
    if (editingQr) {
        QrCodesEditor(
            initial = qrCodes,
            startScanning = qrStartScan,
            onDone = { qrJson = AlarmJson.encodeQrString(it); editingQr = false; qrStartScan = false },
            onCancel = { editingQr = false; qrStartScan = false },
        )
        return
    }

    val days = daysMask.toDays()
    val candidate = base.copy(
        hour = timeState.hour,
        minute = timeState.minute,
        enabled = enabled,
        repeatDays = days,
        vibrate = vibrate,
        volumePercent = volume.toInt(),
        soundUri = soundUri,
        mode = mode,
        qrCodes = qrCodes,
        preAlarmEnabled = preEnabled,
        preAlarmMinutes = preMinutes,
        tempAccessEnabled = tempEnabled,
        tempAccessVolumePercent = tempVolume.toInt(),
    )
    val duplicate = otherAlarms.any { it.id != base.id && it.sameScheduleAs(candidate) }
    val qrValid = QrRegistry.isValidForAlarm(qrCodes)
    val challengeReady = !mode.needsQr || qrValid
    val soundTitle = remember(soundUri) { ringtoneTitle(context, soundUri) }
    val formatter = remember { timeFormatter(context) }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = result.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            if (picked != null) {
                soundUri = if (picked == Settings.System.DEFAULT_ALARM_ALERT_URI) null else picked.toString()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New alarm" else "Edit alarm") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(WfIcons.Back, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // --- A. Basic alarm ----------------------------------------------------------------
            Section("Basic alarm")
            TimePicker(state = timeState)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WeekOrder.forEach { day ->
                    FilterChip(
                        selected = day in days,
                        onClick = { daysMask = daysMask xor day.bit() },
                        label = { Text(day.shortName()) },
                    )
                }
            }
            Hint(if (days.isEmpty()) "Rings once" else "Repeats weekly")
            SwitchRow("Alarm on", enabled) { enabled = it }
            Row(
                Modifier.fillMaxWidth().clickable {
                    soundPicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_ALARM_ALERT_URI)
                            .putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                soundUri?.toUri() ?: Settings.System.DEFAULT_ALARM_ALERT_URI,
                            ),
                    )
                }.padding(vertical = 12.dp),
            ) {
                Text("Alarm tone", Modifier.weight(1f))
                Text(soundTitle, color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
            LabeledSlider("Volume", "${volume.toInt()}%", volume, 10f..100f, steps = 8) { volume = it }
            SwitchRow("Vibrate", vibrate) { vibrate = it }

            // --- B. Challenges -----------------------------------------------------------------
            Section("Stop the alarm by")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ChallengeMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = mode == option,
                        onClick = {
                            mode = option
                            // QR needs a registered code: go straight to the camera if there is none.
                            if (option.needsQr && qrCodes.isEmpty()) {
                                qrStartScan = true
                                editingQr = true
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, ChallengeMode.entries.size),
                    ) { Text(option.label, maxLines = 1) }
                }
            }
            Hint(
                when (mode) {
                    ChallengeMode.QR -> "Scan the registered QR code to stop the alarm."
                    ChallengeMode.MATH -> "Solve every addition question to stop the alarm."
                    ChallengeMode.MATH_AND_QR -> "Stricter: solve the math, then scan the QR code."
                },
            )
            if (mode.needsMath) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Math addition")
                        Hint("${mathSettings.questionCount} questions · ${mathSettings.difficulty.label}: " +
                            mathSettings.difficulty.description)
                    }
                    TextButton(onClick = onOpenMathConfig) { Text("Configure") }
                }
            }
            if (mode.needsQr) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("QR codes: ${qrCodes.size}/${Alarm.MAX_QR_CODES} registered")
                        if (!qrValid) {
                            Text("Scan a QR code to register it", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        } else if (qrCodes.size > 1) {
                            Hint("All ${qrCodes.size} codes must be scanned.")
                        }
                    }
                    TextButton(onClick = { qrStartScan = qrCodes.isEmpty(); editingQr = true }) {
                        Text(if (qrCodes.isEmpty()) "Scan QR" else "Manage")
                    }
                }
            }

            // --- C. Pre-alarm ------------------------------------------------------------------
            Section("Pre-alarm confirmation")
            SwitchRow("Ask if I still need this alarm", preEnabled) { preEnabled = it }
            if (preEnabled) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Alarm.PRE_ALARM_OPTIONS.forEach { minutes ->
                        FilterChip(selected = preMinutes == minutes, onClick = { preMinutes = minutes },
                            label = { Text(describeMinutes(minutes)) })
                    }
                }
                val confirmAt = LocalTime.of(timeState.hour, timeState.minute).minusMinutes(preMinutes.toLong())
                Hint("Confirmation appears at ${confirmAt.format(formatter)}. If you don't answer, the alarm rings as planned.")
            }

            // --- D. Temporary access -----------------------------------------------------------
            Section("Temporary access")
            SwitchRow("Allow 5-minute access while ringing", tempEnabled) { tempEnabled = it }
            if (tempEnabled) {
                LabeledSlider(
                    "Alarm volume during access",
                    if (tempVolume.toInt() == 0) "Muted" else "${tempVolume.toInt()}%",
                    tempVolume, 0f..Alarm.MAX_TEMP_ACCESS_VOLUME.toFloat(), steps = 4,
                ) { tempVolume = it }
                Hint("Duration: 5 minutes, up to 2 times per alarm. The alarm then resumes at full volume.")
            }

            Spacer(Modifier.height(20.dp))
            if (duplicate) {
                Text("An alarm with this time and repeat pattern already exists.", color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
            }
            Button(
                onClick = {
                    onSave(candidate)
                    onBack()
                },
                enabled = !duplicate && challengeReady,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Save") }
            if (!isNew) {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Delete alarm", color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this alarm?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(base.id)
                    onBack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
}

fun describeMinutes(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

private fun DayOfWeek.bit(): Int = 1 shl (value - 1)
private fun Set<DayOfWeek>.toMask(): Int = fold(0) { acc, d -> acc or d.bit() }
private fun Int.toDays(): Set<DayOfWeek> = DayOfWeek.entries.filter { this and it.bit() != 0 }.toSet()

private fun ringtoneTitle(context: Context, uri: String?): String {
    if (uri == null) return "Default alarm"
    return try {
        RingtoneManager.getRingtone(context, uri.toUri())?.getTitle(context) ?: "Custom"
    } catch (e: Exception) {
        "Custom"
    }
}
