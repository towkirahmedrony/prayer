package io.github.towkirahmedrony.salah.core.domain.qibla

/**
 * Which permission affordance Salah should offer, given what the platform will actually allow.
 *
 * Three cases, because they need three different actions and only the platform knows which one
 * applies: permission already granted, a runtime dialog the user can accept or decline, and a denial
 * the system will no longer re-prompt for. Collapsing the last two — the way a bare
 * [LocationPermissionState.DENIED] does — makes the app either re-launch a dialog that will
 * immediately fail, or send a user who could simply tap "Allow" into their system settings.
 */
enum class LocationPermissionAction {

    /** Permission has already been granted; a fix can be requested without prompting. */
    ALREADY_GRANTED,

    /** The runtime dialog can be shown. */
    REQUEST,

    /**
     * The system will not show the dialog again, so the only way to grant permission is the app's
     * settings screen. Re-requesting here would silently do nothing and read as a broken button.
     */
    OPEN_APP_SETTINGS,
}

/**
 * Decides which of [LocationPermissionAction] applies.
 *
 * Pure and platform-free so the rule is directly testable; the caller supplies the two facts only the
 * platform can answer — whether the dialog has been shown before, and whether the system still
 * considers a rationale useful.
 *
 * **How the two facts distinguish the cases.** Android reports
 * `shouldShowRequestPermissionRationale` as `false` both *before* the first request and *after* the
 * user has chosen "don't ask again", so that flag alone cannot tell the two apart. Pairing it with
 * [hasRequestedDialog] can: before the first request there is a dialog to show (so `REQUEST`), and
 * once a dialog has been shown and the rationale flag is `false` the system will not prompt again (so
 * `OPEN_APP_SETTINGS`). The flag is a hint rather than a guarantee, which is why the outcome only
 * decides which affordance to offer and never claims that permission is impossible.
 */
object LocationPermissionPolicy {

    /**
     * @param state what the platform reports the permission state to be.
     * @param hasRequestedDialog whether Salah has already shown the runtime dialog this session.
     * @param shouldShowRationale `shouldShowRequestPermissionRationale` for the requested permissions.
     */
    fun action(
        state: LocationPermissionState,
        hasRequestedDialog: Boolean,
        shouldShowRationale: Boolean,
    ): LocationPermissionAction = when {
        state.isGranted -> LocationPermissionAction.ALREADY_GRANTED
        !hasRequestedDialog -> LocationPermissionAction.REQUEST
        shouldShowRationale -> LocationPermissionAction.REQUEST
        else -> LocationPermissionAction.OPEN_APP_SETTINGS
    }
}
