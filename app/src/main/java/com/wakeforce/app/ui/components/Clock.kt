package com.wakeforce.app.ui.components

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.wakeforce.app.data.Alarm
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Current time that updates once per minute, aligned to the minute boundary, and only
 * while the screen is visible. Avoids a per-second recomposition loop.
 */
@Composable
fun rememberMinuteClock(): State<ZonedDateTime> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initialValue = ZonedDateTime.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val now = ZonedDateTime.now()
                value = now
                val msIntoMinute = now.second * 1_000L + now.nano / 1_000_000
                delay(60_000L - msIntoMinute + 20)
            }
        }
    }
}

fun timeFormatter(context: Context): DateTimeFormatter =
    DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a")

fun formatAlarmTime(context: Context, alarm: Alarm): String =
    LocalTime.of(alarm.hour, alarm.minute).format(timeFormatter(context))

val WeekOrder: List<DayOfWeek> = DayOfWeek.entries

fun DayOfWeek.shortName(): String = getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun describeRepeat(days: Set<DayOfWeek>): String = when {
    days.isEmpty() -> "Once"
    days.size == 7 -> "Every day"
    days == WeekOrder.take(5).toSet() -> "Weekdays"
    days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Weekends"
    else -> WeekOrder.filter { it in days }.joinToString(", ") { it.shortName() }
}
