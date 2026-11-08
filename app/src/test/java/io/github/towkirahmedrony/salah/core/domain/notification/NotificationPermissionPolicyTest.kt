package io.github.towkirahmedrony.salah.core.domain.notification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notification-permission prompt decision, exercised against the pure policy.
 *
 * The rule exists so the app never does the two things that make permission handling feel
 * broken: re-launching a runtime dialog the system will no longer show, and sending a user who
 * could simply tap "Allow" into their system settings. The platform's rationale flag alone
 * cannot tell "never asked" from "don't ask again", so the persisted has-requested fact is what
 * separates them.
 */
class NotificationPermissionPolicyTest {

    @Test
    fun `granted permission needs neither a dialog nor settings`() {
        assertEquals(
            NotificationPermissionAction.ALREADY_GRANTED,
            NotificationPermissionPolicy.action(
                notificationsAllowed = true,
                hasRequestedDialog = false,
                shouldShowRationale = false,
                requiresRuntimePermission = true,
            ),
        )
    }

    @Test
    fun `a first launch with no prior request shows the native dialog`() {
        assertEquals(
            NotificationPermissionAction.REQUEST,
            NotificationPermissionPolicy.action(
                notificationsAllowed = false,
                hasRequestedDialog = false,
                shouldShowRationale = false,
                requiresRuntimePermission = true,
            ),
        )
    }

    @Test
    fun `a single denial can still be asked again through the dialog`() {
        assertEquals(
            NotificationPermissionAction.REQUEST,
            NotificationPermissionPolicy.action(
                notificationsAllowed = false,
                hasRequestedDialog = true,
                shouldShowRationale = true,
                requiresRuntimePermission = true,
            ),
        )
    }

    @Test
    fun `a permanent denial routes to system settings instead of a dead dialog`() {
        assertEquals(
            NotificationPermissionAction.OPEN_APP_SETTINGS,
            NotificationPermissionPolicy.action(
                notificationsAllowed = false,
                hasRequestedDialog = true,
                shouldShowRationale = false,
                requiresRuntimePermission = true,
            ),
        )
    }

    @Test
    fun `below Android 13 there is no runtime dialog, only the settings toggle`() {
        assertEquals(
            NotificationPermissionAction.OPEN_APP_SETTINGS,
            NotificationPermissionPolicy.action(
                notificationsAllowed = false,
                hasRequestedDialog = false,
                shouldShowRationale = false,
                requiresRuntimePermission = false,
            ),
        )
    }

    @Test
    fun `below Android 13 an allowed app needs nothing asked`() {
        assertEquals(
            NotificationPermissionAction.ALREADY_GRANTED,
            NotificationPermissionPolicy.action(
                notificationsAllowed = true,
                hasRequestedDialog = false,
                shouldShowRationale = false,
                requiresRuntimePermission = false,
            ),
        )
    }
}
