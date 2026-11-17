package com.wakeforce.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wakeforce.app.alarm.AlarmTimeCalculator
import com.wakeforce.app.alarm.WakeStats
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.ChallengeMode
import com.wakeforce.app.settings.SystemSettings
import com.wakeforce.app.ui.components.SetupChecklist
import com.wakeforce.app.ui.components.WfIcons
import com.wakeforce.app.ui.components.describeRepeat
import com.wakeforce.app.ui.components.formatAlarmTime
import com.wakeforce.app.ui.components.rememberMinuteClock
import com.wakeforce.app.ui.components.timeFormatter
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    alarms: List<Alarm>,
    stats: WakeStats,
    onAddAlarm: () -> Unit,
    onEditAlarm: (Alarm) -> Unit,
    onToggleAlarm: (Alarm, Boolean) -> Unit,
    onOpenMathConfig: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WakeForce", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = onOpenMathConfig) { Text("Math settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAlarm,
                icon = { Icon(WfIcons.Add, contentDescription = null) },
                text = { Text("Add alarm") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { ClockHeader(alarms, stats) }
            item(key = "setup") { SetupChecklist() }
            if (alarms.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No alarms yet. Tap \"Add alarm\" to create one.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(alarms, key = { it.id }) { alarm ->
                AlarmRow(alarm, onClick = { onEditAlarm(alarm) }, onToggle = { onToggleAlarm(alarm, it) })
            }
            item(key = "footer") { ReliabilityFooter() }
        }
    }
}

@Composable
private fun ClockHeader(alarms: List<Alarm>, stats: WakeStats) {
    val context = LocalContext.current
    val now by rememberMinuteClock()
    val formatter = remember { timeFormatter(context) }
    val dateFormatter = remember { DateTimeFormatter.ofPattern("EEEE, d MMMM") }
    val next = remember(alarms, now) { AlarmTimeCalculator.nextAlarm(alarms, now) }

    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(now.format(formatter), fontSize = 64.sp, fontWeight = FontWeight.Light)
        Text(now.format(dateFormatter), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = next?.let { (_, at) ->
                "Next alarm: ${at.format(DateTimeFormatter.ofPattern("EEE"))} ${at.format(formatter)} · " +
                    AlarmTimeCalculator.describeUntil(now, at)
            } ?: "No alarm scheduled",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp),
        )
        stats.lastDismissedAt?.let { last ->
            val at = java.time.Instant.ofEpochMilli(last).atZone(now.zone)
            val took = (stats.lastDurationMs ?: 0) / 60_000
            Text(
                "Last wake-up: ${at.format(DateTimeFormatter.ofPattern("EEE"))} ${at.format(formatter)} " +
                    "(stopped in $took min) · ${stats.completedCount} total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val dim = if (alarm.enabled) 1f else 0.45f
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatAlarmTime(context, alarm),
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                )
                Text(
                    describeRepeat(alarm.repeatDays) + " · " + challengeSummary(alarm) +
                        if (alarm.preAlarmEnabled) " · Ask ${describeMinutes(alarm.preAlarmMinutes)} before" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
                )
                if (alarm.enabled && alarm.occurrence != null && alarm.occurrence == alarm.confirmedOccurrence) {
                    Text("Confirmed for next time", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (!alarm.isChallengeReady) {
                    // QR mode without a code (old data): it rings with math until a code is scanned.
                    Text("No QR code yet: uses math until you scan one",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Switch(checked = alarm.enabled, onCheckedChange = onToggle)
        }
    }
}

/** "QR scan", "QR scan (2 codes)", "Math", "Math + QR". Shared with the pre-alarm screen. */
fun challengeSummary(alarm: Alarm): String {
    val codes = if (alarm.qrCodes.size > 1) " (${alarm.qrCodes.size} codes)" else ""
    return when (alarm.mode) {
        ChallengeMode.QR -> "QR scan$codes"
        ChallengeMode.MATH -> "Math"
        ChallengeMode.MATH_AND_QR -> "Math + QR$codes"
    }
}

@Composable
private fun ReliabilityFooter() {
    val context = LocalContext.current
    Column(Modifier.padding(top = 16.dp)) {
        Text(
            "Alarms are scheduled with Android's alarm clock and work with the app closed and the " +
                "screen locked. They cannot ring if WakeForce is force-stopped or the phone is " +
                "switched off; open the app once after a force-stop to re-arm them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!SystemSettings.batteryUnrestricted(context)) {
            TextButton(
                onClick = { SystemSettings.open(context, SystemSettings.batterySettingsIntent()) },
                contentPadding = PaddingValues(0.dp),
            ) { Text("Phone kills background apps? Set battery to Unrestricted") }
        }
    }
}
