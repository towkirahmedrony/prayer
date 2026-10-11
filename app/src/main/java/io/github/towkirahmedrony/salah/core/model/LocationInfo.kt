package io.github.towkirahmedrony.salah.core.model

import java.time.ZoneId

/** How a [LocationInfo] was obtained. */
enum class LocationOrigin {
    /** Entered by hand in Settings. The only supported origin in this phase. */
    MANUAL_ENTRY,

    /** Read from the device location provider. Not implemented in this phase. */
    DEVICE_GPS,
}

/**
 * A location that prayer calculations and calendar display can be tied to.
 *
 * Instances can only be created through [create], which validates the input. Coordinates are
 * never invented: an unset location is represented as `null` by the repositories, which lets
 * the UI show an explicit "no location configured" state instead of a guessed default.
 */
data class LocationInfo(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val timeZoneId: String,
    val origin: LocationOrigin,
) {
    /** The zone this location's times are expressed in. */
    val zoneId: ZoneId get() = ZoneId.of(timeZoneId)

    companion object {
        const val MIN_LATITUDE = -90.0
        const val MAX_LATITUDE = 90.0
        const val MIN_LONGITUDE = -180.0
        const val MAX_LONGITUDE = 180.0

        /**
         * Builds a validated location, or returns `null` when the input is not usable.
         *
         * @param label human readable name, must not be blank.
         * @param latitude degrees, within [-90, 90].
         * @param longitude degrees, within [-180, 180].
         * @param timeZoneId an IANA zone id such as `Asia/Dhaka`.
         */
        fun create(
            label: String,
            latitude: Double,
            longitude: Double,
            timeZoneId: String,
            origin: LocationOrigin = LocationOrigin.MANUAL_ENTRY,
        ): LocationInfo? {
            val cleanLabel = label.trim()
            if (cleanLabel.isEmpty()) return null
            if (latitude.isNaN() || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) return null
            if (longitude.isNaN() || longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE) return null
            val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return null
            return LocationInfo(
                label = cleanLabel,
                latitude = latitude,
                longitude = longitude,
                timeZoneId = zone.id,
                origin = origin,
            )
        }
    }
}
