package io.github.towkirahmedrony.salah.core.domain.repository

import io.github.towkirahmedrony.salah.core.model.BangladeshAreaRef
import io.github.towkirahmedrony.salah.core.model.BangladeshLocalityRef
import io.github.towkirahmedrony.salah.core.model.LocationInfo
import kotlinx.coroutines.flow.Flow

/**
 * Local persistence of the user's location.
 *
 * A location is optional: `null` means the user has not configured one, and every consumer
 * must handle that state explicitly rather than falling back to a default city.
 */
interface LocationRepository {

    /** Emits the stored location, or `null` when none is configured. */
    val location: Flow<LocationInfo?>

    /**
     * Validates and stores a manually entered location.
     *
     * @return `true` when the input was valid and stored, `false` when it was rejected.
     */
    suspend fun saveManualLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
    ): Boolean

    /**
     * Validates and stores a position obtained from the device.
     *
     * A separate method rather than extra parameters on [saveManualLocation], because the two differ
     * in what they *mean*, not only in what they carry: [saveManualLocation] records an assertion by
     * the user, while this records an observation the platform made at a moment in time. Collapsing
     * them would lose that distinction at the call site, and `LocationOrigin`, `accuracyMetres` and
     * `acquiredAt` exist precisely so it survives storage.
     *
     * @param accuracyMetres reported horizontal accuracy, or `null` when the provider gave none. It is
     *   also the input that decides whether a locality may be named at all: a position that is only
     *   accurate to a kilometre or two cannot be attributed to a neighbourhood, and the resolver reports
     *   that instead of naming one.
     * @param acquiredAtEpochMillis when the fix was obtained, or `null` (or a non-positive value)
     *   when the platform did not record one. A fix with no time is stored with no acquisition time,
     *   so it ages as unknown rather than being silently presented as a current reading.
     * @return `true` when the input was valid and stored, `false` when it was rejected.
     */
    suspend fun saveDeviceLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
        accuracyMetres: Double?,
        acquiredAtEpochMillis: Long?,
        /**
         * The country the coordinates were accepted as being in, or `null` when no check ran.
         *
         * Optional so the Qibla screen's existing save keeps working unchanged: that screen accepts a
         * position anywhere, and storing `BD` for it would be a claim it never verified.
         */
        countryCode: String? = null,

        /** The administrative area the fix was attributed to, when it could be resolved. */
        area: BangladeshAreaRef? = null,

        /**
         * The named locality the fix was matched to, when one could be matched and verified.
         *
         * Written by the device-fix path only. A manually selected area stores no locality: the area's
         * coordinate is the published centre of an administrative unit the user chose, not a position the
         * device observed, so naming the locality around that centre would describe the dataset's point
         * rather than the user.
         */
        locality: BangladeshLocalityRef? = null,

        /** The dataset the [area] ids came from, or `null` when none was involved. */
        datasetVersion: String? = null,
    ): Boolean

    /**
     * Validates and stores a location chosen as a Bangladesh administrative area.
     *
     * A third method rather than a variant of the two above, because this stores a *selection*: the
     * coordinate is the chosen area's published centre, the hierarchy is the user's actual input, and
     * the origin is [io.github.towkirahmedrony.salah.core.model.LocationOrigin.AREA_SELECTION].
     *
     * **No time zone parameter.** Every area in the bundled dataset is in Bangladesh, which has one
     * zone, so the method uses
     * [io.github.towkirahmedrony.salah.core.domain.location.BangladeshLocationPolicy.TIME_ZONE_ID]
     * itself. Accepting a zone here would allow a combination the dataset cannot produce — Sylhet's
     * coordinates with another zone — which is exactly the kind of mismatch the area hierarchy exists
     * to make impossible.
     *
     * @param label a human-readable label for the chosen area, built from its own names.
     * @param reference the division, district and upazila/thana the user selected.
     * @param datasetVersion the version of the bundled dataset the ids were chosen from.
     * @return `true` when the input was valid and stored, `false` when it was rejected.
     */
    suspend fun saveAreaLocation(
        latitude: Double,
        longitude: Double,
        label: String,
        reference: BangladeshAreaRef,
        datasetVersion: String,
    ): Boolean

    suspend fun clearLocation()
}
