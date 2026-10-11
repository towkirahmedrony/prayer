package io.github.towkirahmedrony.salah.core.model

import java.time.Instant
import java.time.ZoneId

/** How a [LocationInfo] was obtained. */
enum class LocationOrigin {
    /** Entered by hand, in Settings or on the Qibla screen. */
    MANUAL_ENTRY,

    /**
     * Read from the device's location provider, then stored locally.
     *
     * Stored rather than held in memory: it is what lets the Qibla bearing keep working offline and
     * after a process death, at the cost of possibly describing where the user *was*. That
     * distinction is carried by [LocationInfo.accuracyMetres] and [LocationInfo.acquiredAt] and
     * surfaced through `LocationFreshness`.
     */
    DEVICE_GPS,
}

/**
 * A location that prayer calculations, the Qibla bearing and calendar display can be tied to.
 *
 * Instances can only be created through [create], which validates the input. Coordinates are
 * never invented: an unset location is represented as `null` by the repositories, which lets
 * the UI show an explicit "no location configured" state instead of a guessed default.
 *
 * The two optional fields describe the *acquisition*, not the place, and are what allow the UI to
 * distinguish a live fix from a saved one. They are nullable rather than defaulted to a sentinel so
 * that "unknown" cannot be mistaken for "zero accuracy" or "the epoch".
 */
data class LocationInfo(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val timeZoneId: String,
    val origin: LocationOrigin,

    /**
     * Reported horizontal accuracy of a device fix, in metres (the radius of the circle the fix is
     * expected to lie within), or `null` when unknown or not applicable.
     */
    val accuracyMetres: Double? = null,

    /** When a device fix was obtained, or `null` when unknown or not applicable. */
    val acquiredAt: Instant? = null,
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
         * @param accuracyMetres reported accuracy in metres; a non-finite or non-positive value is
         *   discarded to `null` rather than stored, because "±0 m" and "±NaN" would both render as
         *   a precision claim Salah cannot support.
         */
        fun create(
            label: String,
            latitude: Double,
            longitude: Double,
            timeZoneId: String,
            origin: LocationOrigin = LocationOrigin.MANUAL_ENTRY,
            accuracyMetres: Double? = null,
            acquiredAt: Instant? = null,
        ): LocationInfo? {
            val cleanLabel = label.trim()
            if (cleanLabel.isEmpty()) return null
            if (latitude.isNaN() || latitude < MIN_LATITUDE || latitude > MAX_LATITUDE) return null
            if (longitude.isNaN() || longitude < MIN_LONGITUDE || longitude > MAX_LONGITUDE) return null
            val zone = runCatching { ZoneId.of(timeZoneId.trim()) }.getOrNull() ?: return null
            val usableAccuracy = accuracyMetres?.takeIf { it.isFinite() && it > 0.0 }
            return LocationInfo(
                label = cleanLabel,
                latitude = latitude,
                longitude = longitude,
                timeZoneId = zone.id,
                origin = origin,
                accuracyMetres = usableAccuracy,
                acquiredAt = acquiredAt,
            )
        }
    }
}
