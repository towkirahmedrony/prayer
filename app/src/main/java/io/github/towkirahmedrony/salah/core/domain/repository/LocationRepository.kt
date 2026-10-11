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

    suspend fun clearLocation()
}
