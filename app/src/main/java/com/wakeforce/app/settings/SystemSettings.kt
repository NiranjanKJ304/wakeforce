package com.wakeforce.app.settings

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.wakeforce.app.service.AlarmNotifications

/**
 * Android system permissions/settings that decide whether an alarm can ring reliably.
 * Everything here is a cheap synchronous check, evaluated only when the UI resumes.
 */
object SystemSettings {

    enum class Requirement(val title: String, val detail: String) {
        NOTIFICATIONS(
            "Allow alarm notifications",
            "Needed to show the full-screen alarm when it rings.",
        ),
        EXACT_ALARMS(
            "Allow alarms & reminders",
            "Without it Android may delay your alarm by several minutes.",
        ),
        FULL_SCREEN(
            "Allow full-screen alarms",
            "Lets the alarm take over the lock screen instead of a small notification.",
        ),
    }

    fun missingRequirements(context: Context): List<Requirement> = buildList {
        if (!notificationsAllowed(context)) add(Requirement.NOTIFICATIONS)
        if (!exactAlarmsAllowed(context)) add(Requirement.EXACT_ALARMS)
        if (!fullScreenAllowed(context)) add(Requirement.FULL_SCREEN)
    }

    fun notificationsAllowed(context: Context): Boolean {
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(AlarmNotifications.CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun fullScreenAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    fun intentFor(context: Context, requirement: Requirement): Intent {
        val pkg = Uri.fromParts("package", context.packageName, null)
        return when (requirement) {
            Requirement.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            Requirement.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg)
                } else appDetails(context)
            Requirement.FULL_SCREEN ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg)
                } else appDetails(context)
        }
    }

    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Opens a settings screen, falling back to the app's details page on unusual ROMs. */
    fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(appDetails(context))
            } catch (_: ActivityNotFoundException) {
            }
        }
    }
}
