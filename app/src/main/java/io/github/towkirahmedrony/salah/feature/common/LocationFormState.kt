package io.github.towkirahmedrony.salah.feature.common

import io.github.towkirahmedrony.salah.core.model.LocationInfo
import io.github.towkirahmedrony.salah.core.model.LocationOrigin

/**
 * Raw text of the manual-location form, kept as text until it is validated on save.
 *
 * Lives in `feature/common` rather than inside the Settings feature because both Settings and the
 * Qibla screen offer the same manual entry, backed by the same repository. A second copy would be a
 * second set of validation rules waiting to disagree with the first — and, more importantly, two
 * places to forget the time zone.
 *
 * Held as text, not numbers, so a half-typed `-` or `23.` is representable while the user types.
 * Validation happens once, at save, in `LocationInfo.create`.
 */
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
    /**
     * Displays the stored location until the user edits the form.
     *
     * Only a manually entered location pre-fills the form. A device fix deliberately does not: this
     * form is how the user *asserts* a place, and seeding it with an observation would blur the two
     * and make it easy to save a moving position as a declared one.
     */
    fun withStoredLocation(location: LocationInfo?): LocationFormState {
        if (isDirty || location == null || location.origin != LocationOrigin.MANUAL_ENTRY) return this
        return copy(
            label = location.label,
            latitude = location.latitude.toString(),
            longitude = location.longitude.toString(),
            timeZoneId = location.timeZoneId,
        )
    }

    companion object {
        /**
         * A blank form, with the device's own zone suggested.
         *
         * The zone is pre-filled because pasting coordinates without one is the most common way to
         * end up with prayer times silently computed in the wrong zone. It stays editable, and the
         * Qibla screen labels it so the suggestion is visible rather than silent.
         */
        fun blankWithSuggestedZone(deviceZoneId: String): LocationFormState =
            LocationFormState(timeZoneId = deviceZoneId)
    }
}
