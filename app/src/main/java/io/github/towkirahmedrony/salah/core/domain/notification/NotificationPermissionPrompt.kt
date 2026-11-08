package io.github.towkirahmedrony.salah.core.domain.notification

/**
 * Which notification-permission affordance Salah should offer, given what the platform allows.
 *
 * Mirrors the location-permission policy: three cases need three different actions, and only the
 * platform knows which applies. Re-launching a runtime dialog the system will no longer show
 * reads as a broken button, so a permanent denial must route to the app's system settings instead.
 */
enum class NotificationPermissionAction {

    /** Notifications are already allowed; nothing needs to be asked or opened. */
    ALREADY_GRANTED,

    /** The native runtime dialog (`POST_NOTIFICATIONS`, Android 13+) can be shown. */
    REQUEST,

    /**
     * The runtime dialog will not help — either the permission does not exist on this Android
     * version (only the app-level toggle can change) or the user has permanently denied it — so
     * the only way to grant it is the app's notification settings / App Info page.
     */
    OPEN_APP_SETTINGS,
}

/**
 * Decides which [NotificationPermissionAction] applies.
 *
 * Pure and platform-free so the rule is directly testable; the caller supplies the facts only the
 * platform can answer — whether the dialog has been shown before, and whether the system still
 * considers a rationale useful.
 *
 * **How the two facts distinguish the cases.** Android reports
 * `shouldShowRequestPermissionRationale` as `false` both *before* the first request and *after*
 * the user has chosen "don't ask again", so that flag alone cannot tell the two apart. Pairing it
 * with [hasRequestedDialog] can: before the first request there is a dialog to show (so `REQUEST`),
 * and once a dialog has been shown and the rationale flag is `false` the system will not prompt
 * again (so `OPEN_APP_SETTINGS`).
 */
object NotificationPermissionPolicy {

    /**
     * @param notificationsAllowed the current platform state (see [NotificationPermissionStatus]).
     * @param hasRequestedDialog whether Salah has already shown the runtime dialog (persisted, so
     *   this survives process death and an upgrade).
     * @param shouldShowRationale `shouldShowRequestPermissionRationale` for `POST_NOTIFICATIONS`.
     * @param requiresRuntimePermission true on Android 13+ (`POST_NOTIFICATIONS` exists); below
     *   that there is no runtime dialog to show at all.
     */
    fun action(
        notificationsAllowed: Boolean,
        hasRequestedDialog: Boolean,
        shouldShowRationale: Boolean,
        requiresRuntimePermission: Boolean,
    ): NotificationPermissionAction = when {
        notificationsAllowed -> NotificationPermissionAction.ALREADY_GRANTED
        // No runtime permission exists on this version: only the app-level toggle can change
        // the state, and that lives in system settings.
        !requiresRuntimePermission -> NotificationPermissionAction.OPEN_APP_SETTINGS
        !hasRequestedDialog -> NotificationPermissionAction.REQUEST
        shouldShowRationale -> NotificationPermissionAction.REQUEST
        else -> NotificationPermissionAction.OPEN_APP_SETTINGS
    }
}
