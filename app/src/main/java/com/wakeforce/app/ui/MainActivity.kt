package com.wakeforce.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.wakeforce.app.alarm.SessionState
import com.wakeforce.app.data.Alarm
import com.wakeforce.app.data.SessionRepository
import com.wakeforce.app.service.AlarmNotifications
import com.wakeforce.app.ui.screens.EditAlarmScreen
import com.wakeforce.app.ui.screens.HomeScreen
import com.wakeforce.app.ui.screens.MathConfigScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AlarmNotifications.ensureChannel(this)
        setContent {
            WakeForceTheme { WakeForceApp(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        // While an alarm is ringing the app shows the challenge first, except during a
        // temporary-access window, when normal use is allowed.
        lifecycleScope.launch {
            val session = SessionRepository(this@MainActivity).get()
            if (session != null && (session.state == SessionState.RINGING_MATH || session.state == SessionState.RINGING_QR)) {
                startActivity(AlarmActivity.intent(this@MainActivity, session.alarmId, false, session.occurrence))
            }
        }
    }
}

/** Minimal state-based navigation: a saveable back stack of route strings, no nav library. */
private object Routes {
    const val HOME = "home"
    const val MATH = "math"
    fun edit(id: Int) = "edit/$id"
}

@Composable
private fun WakeForceApp(vm: MainViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val backStack: SnapshotStateList<String> = rememberSaveable(
        saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
    ) { mutableStateListOf(Routes.HOME) }
    val stateHolder = rememberSaveableStateHolder()

    fun navigate(route: String) {
        backStack.add(route)
    }

    fun back() {
        if (backStack.size > 1) stateHolder.removeState(backStack.removeAt(backStack.lastIndex))
    }

    BackHandler(enabled = backStack.size > 1) { back() }
    RequestNotificationPermissionOnce()

    val appData = data
    if (appData == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }

    val route = backStack.last()
    stateHolder.SaveableStateProvider(route) {
        when {
            route == Routes.MATH -> MathConfigScreen(
                current = appData.math,
                onSave = vm::saveMathSettings,
                onBack = ::back,
            )
            route.startsWith("edit/") -> {
                val id = route.removePrefix("edit/").toIntOrNull() ?: Alarm.NEW_ID
                val existing = appData.alarms.firstOrNull { it.id == id }
                if (id != Alarm.NEW_ID && existing == null) {
                    LaunchedEffect(route) { back() } // Alarm was deleted.
                } else {
                    EditAlarmScreen(
                        initial = existing,
                        otherAlarms = appData.alarms,
                        mathSettings = appData.math,
                        onSave = vm::saveAlarm,
                        onDelete = vm::deleteAlarm,
                        onBack = ::back,
                        onOpenMathConfig = { navigate(Routes.MATH) },
                    )
                }
            }
            else -> HomeScreen(
                alarms = appData.alarms,
                stats = appData.stats,
                onAddAlarm = { navigate(Routes.edit(Alarm.NEW_ID)) },
                onEditAlarm = { navigate(Routes.edit(it.id)) },
                onToggleAlarm = vm::setEnabled,
                onOpenMathConfig = { navigate(Routes.MATH) },
            )
        }
    }
}

@Composable
private fun RequestNotificationPermissionOnce() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!asked && !granted) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
