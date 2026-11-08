package io.github.towkirahmedrony.salah.feature.notifications

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring contract of the notification-permission flow, checked against the sources.
 *
 * Salah has no Compose UI test harness, so the arrangement — that the first-launch prompt exists
 * exactly once, that the settings screen routes a permanent denial to system settings instead of
 * a dead dialog, and that the permission state is re-read on return — is asserted here against
 * the real files, in the same style as [LocationSelectionWiringTest]. The decision rule itself
 * is asserted in `NotificationPermissionPolicyTest`.
 */
class NotificationPermissionWiringTest {

    @Test
    fun `the first-launch prompt is in the app shell and only there`() {
        val shell = source("navigation/SalahApp.kt").readText()

        assertTrue(
            "the shell must launch the native permission dialog once the location gate opens",
            shell.contains("ActivityResultContracts.RequestPermission()") &&
                shell.contains("Manifest.permission.POST_NOTIFICATIONS") &&
                shell.contains("LocationSetupState.Complete"),
        )
        assertTrue(
            "the shell must consult the persisted has-requested flag so the prompt never repeats",
            shell.contains("notificationPermissionRequested") &&
                shell.contains("markNotificationPermissionRequested()"),
        )
        assertTrue(
            "the shell must not prompt below Android 13, where the permission does not exist",
            shell.contains("Build.VERSION_CODES.TIRAMISU"),
        )
    }

    @Test
    fun `the settings screen routes through the shared permission policy`() {
        val screen = source("feature/notifications/PrayerNotificationSettingsScreen.kt").readText()

        assertTrue(
            "a permanent denial must open system settings, not re-launch a silent dialog",
            screen.contains("NotificationPermissionPolicy.action(") &&
                screen.contains("NotificationPermissionAction.OPEN_APP_SETTINGS"),
        )
        assertTrue(
            "the denied-state action must offer to enable notifications via settings",
            screen.contains("R.string.notifications_permission_enable"),
        )
        assertTrue(
            "launching the dialog must record the attempt so the next decision is informed",
            screen.contains("markNotificationPermissionRequested()"),
        )
    }

    @Test
    fun `opening system settings falls back to App Info when the first screen is unavailable`() {
        val screen = source("feature/notifications/PrayerNotificationSettingsScreen.kt").readText()

        assertTrue(
            "the notification settings intent must be the primary destination",
            screen.contains("Settings.ACTION_APP_NOTIFICATION_SETTINGS"),
        )
        assertTrue(
            "an unavailable settings activity must fall back to App Info, not crash",
            screen.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS") &&
                screen.contains("ActivityNotFoundException"),
        )
    }

    @Test
    fun `the permission state is refreshed on return from system settings`() {
        val screen = source("feature/notifications/PrayerNotificationSettingsScreen.kt").readText()

        assertTrue(
            "the settings launcher result must re-read the platform state",
            screen.contains("ActivityResultContracts.StartActivityForResult()") &&
                screen.contains("viewModel.refreshPermissionState()"),
        )
        assertTrue(
            "returning to the foreground must also re-read the platform state",
            screen.contains("Lifecycle.Event.ON_RESUME"),
        )
    }

    // --- Reading the real files --------------------------------------------------

    private fun source(relative: String): File {
        val root = repositoryRoot()
        val file = File(root, "$SOURCE_ROOT/$APP_PACKAGE_PATH/$relative")
        assertTrue("missing source file: ${file.absolutePath}", file.isFile)
        return file
    }

    private fun repositoryRoot(): File {
        var base: File? = File(".").absoluteFile
        repeat(SEARCH_DEPTH) {
            val current = base
            if (current != null && File(current, "$SOURCE_ROOT/$APP_PACKAGE_PATH").isDirectory) {
                return current
            }
            base = current?.parentFile
        }
        error(
            "The app's sources were not found. Searched upwards from " +
                "${File(".").absolutePath} for $SOURCE_ROOT.",
        )
    }

    private companion object {
        const val SEARCH_DEPTH = 5
        const val SOURCE_ROOT: String = "app/src/main/java"
        const val APP_PACKAGE_PATH: String = "io/github/towkirahmedrony/salah"
    }
}
