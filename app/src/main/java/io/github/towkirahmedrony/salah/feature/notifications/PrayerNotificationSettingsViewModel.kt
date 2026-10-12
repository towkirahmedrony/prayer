package io.github.towkirahmedrony.salah.feature.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.towkirahmedrony.salah.core.domain.notification.AzanPlaybackManager
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatus
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationPermissionStatusProvider
import io.github.towkirahmedrony.salah.core.domain.notification.NotificationSoundPreviewer
import io.github.towkirahmedrony.salah.core.domain.notification.PrayerNotificationCoordinator
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileReason
import io.github.towkirahmedrony.salah.core.domain.notification.ReconcileOutcome
import io.github.towkirahmedrony.salah.core.domain.repository.LocationRepository
import io.github.towkirahmedrony.salah.core.domain.repository.PrayerNotificationPreferencesRepository
import io.github.towkirahmedrony.salah.core.model.NotificationSound
import io.github.towkirahmedrony.salah.core.model.PrayerName
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationPreference
import io.github.towkirahmedrony.salah.core.model.PrayerNotificationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Settings for prayer notifications.
 *
 * Every action follows the same two steps, in this order: persist the change, then reconcile. The
 * order is what makes the feature behave predictably — a reminder scheduled from stale settings for
 * even a moment is exactly the kind of ghost the reconciliation exists to remove.
 *
 * Writes go through [PrayerNotificationPreferencesRepository.update], which reads and writes inside
 * one DataStore transaction, so two quick taps cannot lose each other's change and the UI cannot
 * disagree with what is stored. Nothing here is Android-specific beyond `ViewModel`: the permission
 * state arrives through a port and the reconciliation through the same coordinator the reboot
 * receiver uses, so there is one scheduling path rather than one per entry point.
 */
class PrayerNotificationSettingsViewModel(
    private val notificationPreferences: PrayerNotificationPreferencesRepository,
    private val locationRepository: LocationRepository,
    private val permissions: NotificationPermissionStatusProvider,
    private val coordinator: PrayerNotificationCoordinator,
    private val soundPreviewer: NotificationSoundPreviewer,
    private val azanPlayback: AzanPlaybackManager,
) : ViewModel() {

    /** Read once so the very first frame already reports the real state rather than a guess. */
    private val initialPermissions: NotificationPermissionStatus =
        runCatching { permissions.status() }.getOrDefault(NotificationPermissionStatus.UNKNOWN)

    private val permissionState = MutableStateFlow(initialPermissions)
    private val scheduleSummary = MutableStateFlow<NotificationScheduleSummary?>(null)
    private val message = MutableStateFlow<NotificationSettingsMessage?>(null)

    val uiState: StateFlow<PrayerNotificationSettingsUiState> = combine(
        notificationPreferences.settings,
        locationRepository.location,
        permissionState,
        scheduleSummary,
        message,
    ) { settings, location, permissions, summary, currentMessage ->
        PrayerNotificationSettingsUiState(
            settings = settings,
            permissions = permissions,
            locationConfigured = location != null,
            azanAvailable = azanPlayback.isAvailable,
            azanAttribution = azanPlayback.attribution(),
            scheduleSummary = summary,
            message = currentMessage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = PrayerNotificationSettingsUiState(
            settings = PrayerNotificationSettings.DEFAULT,
            permissions = initialPermissions,
            locationConfigured = false,
            azanAvailable = azanPlayback.isAvailable,
            azanAttribution = azanPlayback.attribution(),
            scheduleSummary = null,
            message = null,
        ),
    )

    /**
     * Re-reads the platform permission state.
     *
     * Called when the screen appears and again when the user comes back from system settings, where
     * they can change either permission without Salah being told. Nothing is cached across these
     * reads, so a permission revoked in the background is reflected immediately.
     */
    fun refreshPermissionState() {
        permissionState.value = permissions.status()
    }

    // --- Global ---------------------------------------------------------------

    /** Turns every prayer notification on or off, and reconciles either way. */
    fun setMasterEnabled(enabled: Boolean) = updateSettings(
        message = null,
    ) { current -> current.copy(masterEnabled = enabled) }

    // --- Per prayer -----------------------------------------------------------

    fun setPrayerEnabled(prayer: PrayerName, enabled: Boolean) = updatePrayer(prayer) {
        it.copy(enabled = enabled)
    }

    fun setPrayerSound(prayer: PrayerName, sound: NotificationSound) = updatePrayer(prayer) {
        it.copy(sound = sound)
    }

    fun setPrayerVibration(prayer: PrayerName, vibration: Boolean) = updatePrayer(prayer) {
        it.copy(vibration = vibration)
    }

    /**
     * Sets, or clears, the advance reminder for [prayer].
     *
     * A value outside the supported range is clamped by the model, so the screen can never store a
     * value it cannot render.
     */
    fun setPrayerAdvance(prayer: PrayerName, minutes: Int) = updatePrayer(prayer) {
        it.copy(advanceMinutes = minutes)
    }

    /** Shifts [prayer]'s advance reminder to the next offered value. */
    fun shiftPrayerAdvance(prayer: PrayerName, forward: Boolean) {
        val choices = PrayerNotificationSettings.ADVANCE_MINUTE_CHOICES
        val current = choices.indexOf(settings().preferenceFor(prayer).advanceMinutes)
            .let { if (it >= 0) it else 0 }
        val next = (current + if (forward) 1 else -1).coerceIn(0, choices.lastIndex)
        setPrayerAdvance(prayer, choices[next])
    }

    // --- Solar events ---------------------------------------------------------

    /**
     * Turns the sunrise reminder on or off.
     *
     * Separate from the prayers on purpose: sunrise is an astronomical event, and offering it inside
     * the list of obligatory prayers would present it as one.
     */
    fun setSunriseReminderEnabled(enabled: Boolean) = updateSettings(message = null) { current ->
        current.copy(sunriseReminderEnabled = enabled)
    }

    // --- Ramadan ---------------------------------------------------------------

    /**
     * Turns the Sehri reminder on or off.
     *
     * The write is followed by the same reconciliation every other change goes through, so enabling it
     * schedules the Sehri reminders for the Ramadan days already in the rolling window and disabling it
     * cancels them — with no separate code path for Ramadan.
     */
    fun setSehriReminderEnabled(enabled: Boolean) = updateSettings(message = null) { current ->
        current.withSehriReminder(enabled)
    }

    /** Moves the Sehri lead-in to the next offered value. */
    fun shiftSehriLead(forward: Boolean) = updateSettings(message = null) { current ->
        current.withSehriReminder(
            enabled = current.sehriReminderEnabled,
            leadMinutes = nextChoice(
                current.sehriLeadMinutes,
                PrayerNotificationSettings.SEHRI_LEAD_MINUTE_CHOICES,
                forward,
            ),
        )
    }

    /** Turns the Iftar reminder on or off. */
    fun setIftarReminderEnabled(enabled: Boolean) = updateSettings(message = null) { current ->
        current.withIftarReminder(enabled)
    }

    /** Moves the Iftar lead-in to the next offered value; `0` means "at Iftar only". */
    fun shiftIftarLead(forward: Boolean) = updateSettings(message = null) { current ->
        current.withIftarReminder(
            enabled = current.iftarReminderEnabled,
            leadMinutes = nextChoice(
                current.iftarLeadMinutes,
                PrayerNotificationSettings.IFTAR_LEAD_MINUTE_CHOICES,
                forward,
            ),
        )
    }

    private fun nextChoice(current: Int, choices: List<Int>, forward: Boolean): Int {
        val index = choices.indexOf(current).let { if (it >= 0) it else 0 }
        return choices[(index + if (forward) 1 else -1).coerceIn(0, choices.lastIndex)]
    }

    // --- Actions --------------------------------------------------------------

    /**
     * Plays a preview of [sound].
     *
     * Reported honestly: a silent choice counts as success because silence is what the user asked
     * for, and a sound the device refuses is reported as a failure rather than silently ignored.
     */
    fun previewSound(sound: NotificationSound) {
        val played = soundPreviewer.preview(sound)
        message.value = if (played) {
            NotificationSettingsMessage.SoundPreviewStarted
        } else {
            NotificationSettingsMessage.SoundPreviewFailed
        }
    }

    /** Forces a recalculation of the schedule, for the "Recalculate now" action. */
    fun recalculate() {
        message.value = null
        viewModelScope.launch {
            reconcile(ReconcileReason.EXPLICIT)
        }
    }

    /** Clears the transient message once it has been shown. */
    fun dismissMessage() {
        message.value = null
    }

    override fun onCleared() {
        // The preview is user-initiated and must never outlive the screen. `onCleared` has an empty
        // super implementation, so there is nothing else to chain to.
        soundPreviewer.stopPreview()
    }

    // --- Internals ------------------------------------------------------------

    private fun updatePrayer(
        prayer: PrayerName,
        transform: (PrayerNotificationPreference) -> PrayerNotificationPreference,
    ) = updateSettings(message = null) { current ->
        current.withPreference(prayer, transform(current.preferenceFor(prayer)))
    }

    private fun updateSettings(
        message: NotificationSettingsMessage?,
        transform: (PrayerNotificationSettings) -> PrayerNotificationSettings,
    ) {
        this.message.value = message
        viewModelScope.launch {
            notificationPreferences.update(transform)
            reconcile(ReconcileReason.SETTINGS_CHANGED)
        }
    }

    private suspend fun reconcile(reason: ReconcileReason) {
        when (val outcome = coordinator.reconcile(reason)) {
            is ReconcileOutcome.Applied -> {
                scheduleSummary.value = NotificationScheduleSummary(
                    scheduled = outcome.scheduled,
                    cancelled = outcome.cancelled,
                    datesWithoutSchedule = outcome.datesWithoutSchedule.size,
                    exactAlarmFallbackInUse = outcome.exactAlarmFallbackInUse,
                )
                message.value = if (outcome.datesWithSchedule.isEmpty()) {
                    NotificationSettingsMessage.ScheduleUnavailable
                } else {
                    NotificationSettingsMessage.ScheduleRecalculated
                }
            }

            is ReconcileOutcome.Disabled -> {
                scheduleSummary.value = NotificationScheduleSummary(
                    scheduled = 0,
                    cancelled = outcome.cancelled,
                    datesWithoutSchedule = 0,
                    exactAlarmFallbackInUse = permissionState.value.exactAlarmFallbackInUse,
                )
                message.value = NotificationSettingsMessage.ScheduleRecalculated
            }

            is ReconcileOutcome.NoLocation, is ReconcileOutcome.Failed -> {
                scheduleSummary.value = null
                message.value = NotificationSettingsMessage.ScheduleUnavailable
            }
        }

        // Permission can have changed while the reconciliation ran; keep the banner truthful.
        refreshPermissionState()
    }

    /** The current settings, read once for an action that needs them synchronously. */
    private fun settings(): PrayerNotificationSettings =
        uiState.value.settings

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
