package io.github.towkirahmedrony.salah.feature.notifications

import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatus
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings

/**
 * Transient feedback shown after a notification-settings action.
 *
 * Only outcomes that are genuinely unknowable in advance appear here — whether a sound played,
 * whether a recalculation produced a schedule. Nothing here reports a state the screen is already
 * showing, and nothing here is fabricated: a message is set from a real result value.
 */
sealed interface NotificationSettingsMessage {

    /** A preview was started (or the choice was silent, so there was nothing to play). */
    data object SoundPreviewStarted : NotificationSettingsMessage

    /** The preview could not be played on this device. */
    data object SoundPreviewFailed : NotificationSettingsMessage

    /** A recalculation completed. */
    data object ScheduleRecalculated : NotificationSettingsMessage

    /** A recalculation could not produce a schedule; the reason is what the screen already shows. */
    data object ScheduleUnavailable : NotificationSettingsMessage
}

/**
 * What the last reconciliation actually produced.
 *
 * Reported as counts rather than prose so the screen decides how to word it, and so nothing is
 * claimed that did not happen: a `null` summary means no reconciliation has run in this session,
 * and the screen then says nothing rather than inventing a status.
 */
data class NotificationScheduleSummary(
    val scheduled: Int,
    val cancelled: Int,
    val datesWithoutSchedule: Int,
    val exactAlarmFallbackInUse: Boolean,
)

/**
 * Immutable state of the prayer-notification settings screen.
 *
 * Carries the platform permission state as a value ([permissions]) rather than letting the screen
 * query it, so the screen renders one consistent snapshot and the wording about what is missing
 * comes from one place. [permissions] is the *only* thing here that is not persisted locally.
 */
data class PrayerNotificationSettingsUiState(
    val settings: PrayerNotificationSettings,
    val permissions: NotificationPermissionStatus,
    /** Whether the configured location is set, which is what a schedule needs. */
    val locationConfigured: Boolean,
    /** True when a licensed Azan recording is available to play. `false` in this build. */
    val azanAvailable: Boolean,
    val azanAttribution: String?,
    /** The result of the most recent reconciliation in this session, or `null` if none has run. */
    val scheduleSummary: NotificationScheduleSummary?,
    val message: NotificationSettingsMessage?,
)
