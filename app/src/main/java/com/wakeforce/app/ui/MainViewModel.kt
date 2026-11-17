package com.wakeforce.app.ui

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wakeforce.app.alarm.AlarmController
import com.wakeforce.app.alarm.AlarmTimeCalculator
import com.wakeforce.app.alarm.WakeStats
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.AlarmRepository
import com.wakeforce.app.data.SessionRepository
import com.wakeforce.app.math.MathSettings
import com.wakeforce.app.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

data class AppData(
    val alarms: List<Alarm>,
    val math: MathSettings,
    val stats: WakeStats,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = AlarmController(app)
    private val settingsRepo = SettingsRepository(app)

    /** Null until the first read from disk completes (a few ms), to avoid flashing defaults. */
    val data: StateFlow<AppData?> = combine(
        AlarmRepository(app).alarms,
        settingsRepo.mathSettings,
        SessionRepository(app).stats,
    ) { alarms, math, stats ->
        AppData(alarms.sortedWith(compareBy({ it.hour }, { it.minute })), math, stats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Opening the app re-arms everything: recovers from a force-stop, which clears all
        // AlarmManager registrations and cannot be detected any other way.
        viewModelScope.launch { controller.syncAll() }
    }

    fun saveAlarm(alarm: Alarm) {
        viewModelScope.launch {
            when (val outcome = controller.save(alarm)) {
                is AlarmController.SaveOutcome.Saved -> announce(outcome.trigger)
                AlarmController.SaveOutcome.Duplicate -> toast("An alarm for this time already exists")
            }
        }
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) {
        viewModelScope.launch {
            val trigger = controller.setEnabled(alarm.id, enabled)
            if (enabled) announce(trigger)
        }
    }

    fun deleteAlarm(id: Int) {
        viewModelScope.launch { controller.delete(id) }
    }

    fun saveMathSettings(settings: MathSettings) {
        viewModelScope.launch { settingsRepo.saveMathSettings(settings) }
    }

    private fun announce(trigger: ZonedDateTime?) {
        trigger ?: return
        toast("Alarm set " + AlarmTimeCalculator.describeUntil(ZonedDateTime.now(), trigger))
    }

    private fun toast(text: String) {
        Toast.makeText(getApplication(), text, Toast.LENGTH_SHORT).show()
    }
}
