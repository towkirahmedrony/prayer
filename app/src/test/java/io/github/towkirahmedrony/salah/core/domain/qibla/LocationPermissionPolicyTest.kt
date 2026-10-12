package io.github.towkirahmedrony.salah.core.domain.qibla

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decision that keeps Salah from either re-prompting pointlessly or sending a user who could simply
 * allow permission into their settings.
 */
class LocationPermissionPolicyTest {

    @Test
    fun `granted permission needs no prompt`() {
        listOf(LocationPermissionState.COARSE, LocationPermissionState.PRECISE).forEach { state ->
            assertEquals(
                LocationPermissionAction.ALREADY_GRANTED,
                LocationPermissionPolicy.action(state, hasRequestedDialog = false, shouldShowRationale = false),
            )
            assertEquals(
                LocationPermissionAction.ALREADY_GRANTED,
                LocationPermissionPolicy.action(state, hasRequestedDialog = true, shouldShowRationale = true),
            )
        }
    }

    @Test
    fun `a first denial that has not been prompted for is a request`() {
        assertEquals(
            LocationPermissionAction.REQUEST,
            LocationPermissionPolicy.action(
                LocationPermissionState.DENIED,
                hasRequestedDialog = false,
                shouldShowRationale = false,
            ),
        )
    }

    @Test
    fun `a denial the system still shows a rationale for is a request`() {
        assertEquals(
            LocationPermissionAction.REQUEST,
            LocationPermissionPolicy.action(
                LocationPermissionState.DENIED,
                hasRequestedDialog = true,
                shouldShowRationale = true,
            ),
        )
    }

    @Test
    fun `a denial after a prompt, with no rationale, directs the user to app settings`() {
        assertEquals(
            LocationPermissionAction.OPEN_APP_SETTINGS,
            LocationPermissionPolicy.action(
                LocationPermissionState.DENIED,
                hasRequestedDialog = true,
                shouldShowRationale = false,
            ),
        )
    }
}
