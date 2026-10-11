package io.github.towkirahmedrony.salah.core.domain.repository

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
     * @param accuracyMetres reported horizontal accuracy, or `null` when the provider gave none.
     * @param acquiredAtEpochMillis when the fix was obtained. Required rather than optional: a
     *   device fix with no time cannot be aged, so the UI would have to present it as current.
     * @return `true` when the input was valid and stored, `false` when it was rejected.
     */
    suspend fun saveDeviceLocation(
        label: String,
        latitude: Double,
        longitude: Double,
        timeZoneId: String,
        accuracyMetres: Double?,
        acquiredAtEpochMillis: Long,
    ): Boolean

    suspend fun clearLocation()
}
