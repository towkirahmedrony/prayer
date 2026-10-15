package io.github.towkirahmedrony.salah.core.model

import java.time.Instant
import java.time.ZoneId

/** How a [LocationInfo] was obtained. */
enum class LocationOrigin {
    /** Entered by hand, in Settings or on the Qibla screen. */
    MANUAL_ENTRY,

    /**
     * Chosen as an administrative area — a division, district and upazila/thana — in the location
     * setup or the location screen.
     *
     * **Distinct from [MANUAL_ENTRY] on purpose.** A typed coordinate is a point the user asserted;
     * this is an area the user selected, and the coordinate stored with it is that area's published
     * centre. The two differ in what the app may claim: an area selection must never be presented as
     * the user's exact position (`docs/LOCATION_SYSTEM.md` section 7).
     */
    AREA_SELECTION,

    /**
     * Read from the device's location provider, then stored locally.
     *
     * Stored rather than held in memory: it is what lets the Qibla bearing keep working offline and
     * after a process death, at the cost of possibly describing where the user *was*. That
     * distinction is carried by [LocationInfo.accuracyMetres] and [LocationInfo.acquiredAt] and
     * surfaced through `LocationFreshness`.
     */
    DEVICE_GPS,

    /**
     * Chosen by pointing at a real map and confirming the pin's position.
     *
     * **Legacy: no screen in this build can produce this origin.** The map picker it belonged to has
     * been removed; the constant is kept because a location stored by an earlier build still carries it,
     * and an enum that lost the value would fail to read that record — losing the user's location rather
     * than describing it. Such a record keeps its behaviour: [LocationDisplayName] shows the stored label
     * (the address that was resolved for the point) unchanged, and the location screen labels it "Selected
     * on map".
     *
     * **Distinct from the other origins, and for a different reason than they are distinct from each
     * other.** An [AREA_SELECTION] stores the published centre of an administrative unit, so it is a
     * real coordinate that may be minutes from the user; a [DEVICE_GPS] record stores an observation
     * with an accuracy and a time; this stores *the point itself* — exactly the coordinates the user
     * placed and confirmed. Its `label` is the address the reverse geocoder resolved for that point when
     * one was available, and the administrative hierarchy composed from the bundled dataset when it was
     * not, so the record is self-describing either way.
     */
    MAP_SELECTION,
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

    /**
     * ISO 3166-1 alpha-2 code of the country this location was accepted as being in, or `null` when
     * it was never checked.
     *
     * Set to `BD` only after
     * [io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy] has accepted the
     * coordinate, so a stored `BD` is a statement that the check ran and passed — not an assumption
     * about where the app is used. A location typed by hand or read on the Qibla screen before this
     * policy existed carries `null`, which is the honest value for "not established".
     */
    val countryCode: String? = null,

    /**
     * The administrative areas this location belongs to, when they are known.
     *
     * Kept alongside the coordinates rather than derived from them, because for a selected area the
     * hierarchy *is* the user's input and the coordinate is derived from it — the reverse of the GPS
     * case, where the coordinate is the observation and the area is an approximate label.
     */
    val area: BangladeshAreaRef? = null,

    /**
     * The named locality the position was matched to, when one could be matched and verified.
     *
     * Distinct from [area] on purpose, and never a substitute for it: the area is the administrative
     * hierarchy the dataset publishes, while a locality is an informal place name — Mirpur, Uttara,
     * Dhanmondi — whose containing district was verified against those same administrative boundaries.
     * A record can therefore carry both ("Mirpur, Dhaka") or only the area, and the absence of a
     * locality is a real state: a rural position, an approximate fix, or a district whose locality
     * layer has no entry near the user.
     *
     * `null` also means "not established" for a record written before localities existed, which the
     * display policy handles by falling back to the administrative name rather than re-deriving one.
     */
    val locality: BangladeshLocalityRef? = null,

    /**
     * The dataset version the [area] ids were chosen from, or `null` when no dataset was involved.
     *
     * Stored so a later build can tell that the bundled geography has changed since the user made
     * their choice and revalidate it, instead of silently displaying an area the current dataset may
     * no longer define.
     */
    val datasetVersion: String? = null,
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
            countryCode: String? = null,
            area: BangladeshAreaRef? = null,
            locality: BangladeshLocalityRef? = null,
            datasetVersion: String? = null,
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
                // Trimmed and upper-cased so a stored code is comparable, and reduced to null when
                // blank: an empty string would read as "a country was recorded" without recording one.
                countryCode = countryCode?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(),
                area = area,
                locality = locality,
                datasetVersion = datasetVersion,
            )
        }
    }
}
