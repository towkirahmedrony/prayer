package io.github.towkirahmedrony.salah.feature.settings

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin
import io.github.towkirahmedrony.salah.core.model.ThemeMode

/** Transient feedback shown after a settings action. */
sealed interface SettingsMessage {
    data object LocationSaved : SettingsMessage
    data object LocationCleared : SettingsMessage
    data object LocationRejected : SettingsMessage
}

/** Raw text of the location form, kept as text until it is validated on save. */
data class LocationFormState(
    val label: String = "",
    val latitude: String = "",
    val longitude: String = "",
    val timeZoneId: String = "",
    /** True once the user edited the form, so stored values are not overwritten while typing. */
    val isDirty: Boolean = false,
    /** True when the last save attempt was rejected because the input was invalid. */
    val hasValidationError: Boolean = false,
) {
    /** Displays the stored location until the user edits the form. */
    fun withStoredLocation(location: LocationInfo?): LocationFormState {
        if (isDirty || location == null || location.origin != LocationOrigin.MANUAL_ENTRY) return this
        return copy(
            label = location.label,
            latitude = location.latitude.toString(),
            longitude = location.longitude.toString(),
            timeZoneId = location.timeZoneId,
        )
    }
}

data class SettingsUiState(
    val themeMode: ThemeMode,
    val location: LocationInfo?,
    val form: LocationFormState,
    val message: SettingsMessage?,
)
