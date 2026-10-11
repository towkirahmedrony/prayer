package io.github.towkirahmedrony.salah.data.notification

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatus
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatusProvider

/**
 * Reads the two real platform permissions, using the API level the device actually runs.
 *
 * ```
 * notificationsAllowed
 *   API 33+ : POST_NOTIFICATIONS runtime permission AND the user's app notification toggle
 *   API <33 : the user's app notification toggle only (the permission does not exist)
 *
 * exactAlarmsAllowed
 *   API 31+ : AlarmManager.canScheduleExactAlarms()
 *   API <31 : true — SCHEDULE_EXACT_ALARM does not exist and exact alarms are unrestricted
 * ```
 *
 * Both checks are read fresh on every call and nothing is cached, because the user can change
 * either one at any moment deep in system settings, without Salah being told.
 *
 * This never throws. A permission state that cannot be read is reported as **not granted**, which
 * fails safe: Salah then declines to promise delivery rather than scheduling something that
 * cannot appear.
 */
class AndroidNotificationPermissionStatusProvider(
    private val context: Context,
) : NotificationPermissionStatusProvider {

    override fun status(): NotificationPermissionStatus = NotificationPermissionStatus(
        notificationsAllowed = areNotificationsAllowed(),
        exactAlarmsAllowed = areExactAlarmsAllowed(),
    )

    private fun areNotificationsAllowed(): Boolean {
        // The user-facing app notification toggle, available at every API level. When this is off
        // the platform records posts but shows nothing, so it is checked on its own.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        // POST_NOTIFICATIONS has existed since API 33; below that, notification permission is
        // granted implicitly and must not be required.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun areExactAlarmsAllowed(): Boolean {
        // SCHEDULE_EXACT_ALARM was introduced in API 31. Below that, setExactAndAllowWhileIdle is
        // unrestricted and there is nothing to ask for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true

        val alarms = context.getSystemService(AlarmManager::class.java) ?: return false
        return alarms.canScheduleExactAlarms()
    }
}
