package io.github.towkirahmedrony.salah.feature.notifications

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The wiring contract of the notification-permission flow, checked against the sources.
 *
 * Salah has no Compose UI test harness, so the arrangement — that the first-launch prompt exists
 * exactly once, that the settings screen's "Enable Notifications" action is offered only while the
 * permission is missing and hands the user to Android's own notification settings, and that the
 * permission state is re-read on return — is asserted here against the real files, in the same
 * style as [LocationSelectionWiringTest]. The decision rule itself is asserted in
 * `NotificationPermissionPolicyTest`.
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
    fun `the enable action is offered only while notifications are denied`() {
        val card = permissionCard(source("feature/notifications/PrayerNotificationSettingsScreen.kt").readText())

        val deniedGuard = Regex("""if\s*\(\s*!uiState\.permissions\.notificationsAllowed\s*\)""")
        val guard = deniedGuard.find(card)?.range
            ?: error("the permission card must gate its action on the notifications permission being denied")

        val enableLabel = card.indexOf("R.string.notifications_permission_enable")
        assertTrue(
            "the denied-state action must be the \"Enable Notifications\" button",
            enableLabel >= 0,
        )
        assertTrue(
            "the button must sit inside the denied guard, not merely somewhere on the card",
            enableLabel > guard.first,
        )
        val action = card.indexOf("onOpenAppNotificationSettings", guard.last)
        assertTrue(
            "the enable button must be the card's system-settings action inside the denied guard",
            action in (guard.last + 1)..enableLabel,
        )

        assertFalse(
            "the granted state must not show an action, so the old manage button must be gone",
            card.contains("notifications_permission_manage"),
        )
        assertFalse(
            "the settings screen must not offer a separate native-dialog grant action",
            card.contains("notifications_permission_grant"),
        )
    }

    @Test
    fun `the enable action always opens the app's notification settings`() {
        val card = permissionCard(source("feature/notifications/PrayerNotificationSettingsScreen.kt").readText())

        assertTrue(
            "the enable action must be a real button, not a text link",
            card.contains("Button("),
        )
        assertTrue(
            "the enable action must open the app's system notification settings",
            card.contains("onOpenAppNotificationSettings"),
        )
        assertFalse(
            "the enable action must not re-launch the runtime permission dialog",
            card.contains("onRequestNotificationPermission") || card.contains("RequestPermission"),
        )
    }

    @Test
    fun `the enable action appears on the notification settings screen and nowhere else`() {
        val root = File(repositoryRoot(), "$SOURCE_ROOT/$APP_PACKAGE_PATH")

        val offenders = root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.name == "PrayerNotificationSettingsScreen.kt" }
            .filter { file -> file.readText().contains("notifications_permission_enable") }
            .map { file -> file.name }
            .toList()

        assertTrue(
            "the enable button belongs only to the notification settings screen, but these " +
                "other sources render it: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `enabling reminders still routes through the shared permission policy`() {
        val screen = source("feature/notifications/PrayerNotificationSettingsScreen.kt").readText()

        assertTrue(
            "enabling reminders must still route through the shared permission policy",
            screen.contains("NotificationPermissionPolicy.action(") &&
                screen.contains("NotificationPermissionAction.OPEN_APP_SETTINGS"),
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

    /** The body of the permission card, so an assertion cannot accidentally match another card. */
    private fun permissionCard(screen: String): String {
        val start = screen.indexOf("private fun PermissionCard(")
        assertTrue("the permission card composable was not found", start >= 0)
        val end = screen.indexOf("private fun PermissionRow(", start)
        assertTrue("the permission card's end was not found", end > start)
        return screen.substring(start, end)
    }

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
